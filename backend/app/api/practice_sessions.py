"""练习会话七端点（EC-01 / 后端目标需求文档 §6.10）。

- POST   /api/practice-sessions              创建会话（两步法选题，限流）
- GET    /api/practice-sessions              分页列表
- GET    /api/practice-sessions/{sid}        会话详情（题目+进度+草稿）
- PUT    /api/practice-sessions/{sid}/draft  草稿更新（last-write-wins，不限流）
- POST   /api/practice-sessions/{sid}/submit 交卷（条件更新幂等，限流）
- GET    /api/practice-sessions/{sid}/result 逐题结果 + 摘要
- DELETE /api/practice-sessions/{sid}        删除会话

选题两步法（题库库与用户库分属不同数据源，禁止跨库 JOIN）：
第一步在题库库按 collection_id IN + type_code IN 取候选（DISTINCT）；
第二步在 user_data.db 以无 IN 形态取用户该 bank 全部已答题集，
与候选在 Python 侧求交。未答优先、random.sample 抽取、不足补已答。
ESSAY 与 AI 题不进入会话（EC-01 红线）。
"""
from __future__ import annotations

import json
import random
import sqlite3
from datetime import date
from typing import List, Optional

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field

from app.api.deps import get_current_user
from app.api.question_banks import _build_dto
from app.core.bank_registry import QUESTION_TYPE_MAPPING, BankEntry, get_registry
from app.core.config import get_settings
from app.core.errors import BadRequestError, ConflictError, NotFoundError
from app.core.ratelimit import check_user_limit
from app.repositories import bank_repo, practice_session_repo as repo
from app.schemas.common import envelope, page

router = APIRouter(prefix="/api/practice-sessions", tags=["practice-sessions"])

MAX_SESSION_COUNT = 50
# ESSAY（含 AI 批改链路的主观作文）不进入练习会话；AI 题不在任何
# subject 映射内，白名单过滤自然排除（EC-01 MUST NOT 红线）。
_EXCLUDED_TYPE_CODES = {"ESSAY"}


class CreateSessionBody(BaseModel):
    bank_id: str
    collection_ids: Optional[List[int]] = None
    type_codes: Optional[List[str]] = None
    count: int = Field(default=20, ge=1)  # >50 服务端钳制，不 422


class DraftBody(BaseModel):
    current_index: int = Field(ge=0)
    draft: dict = Field(default_factory=dict)
    elapsed_seconds: int = Field(ge=0)


class SubmitBody(BaseModel):
    client_request_id: str = Field(min_length=1)


def _user_db() -> str:
    return str(get_settings().data_root / "user_data.db")


def _entry_or_404(bank_id: str) -> BankEntry:
    entry = get_registry().get(bank_id)
    if entry is None:
        raise NotFoundError(f"question bank '{bank_id}' not found or disabled")
    return entry


def _questions_dto(entry: BankEntry, question_ids: List[int]) -> list[dict]:
    """按给定顺序组装统一题目 DTO（不含答案，作答经 attempts/result 获取）。"""
    return [
        _build_dto(entry, bank_repo.get_question(str(entry.path), qid), include_answer=False)
        for qid in question_ids
    ]


def _pick_question_ids(
    entry: BankEntry,
    user_db: str,
    user_id: int,
    collection_ids: Optional[List[int]],
    type_codes: Optional[List[str]],
    count: int,
) -> List[int]:
    """两步法选题，返回去重后的最终题目序列。"""
    mapping = QUESTION_TYPE_MAPPING.get(entry.subject_id, {})
    reverse = {v: k for k, v in mapping.items()}
    if type_codes:
        for tc in type_codes:
            if tc not in reverse:
                raise BadRequestError(f"invalid type_code '{tc}' for bank '{entry.id}'")

    # type_code 白名单：映射内且非 ESSAY；与请求过滤求交
    whitelist = {c for c in mapping.values() if c not in _EXCLUDED_TYPE_CODES}
    wanted = whitelist & set(type_codes) if type_codes else whitelist
    type_ids = [tid for tid, code in mapping.items() if code in wanted]
    if not type_ids:
        raise BadRequestError("no eligible question types after excluding ESSAY/AI")

    # ── 第一步：题库库候选（DISTINCT 防止"不足补已答"使同题重复入会话）──
    conn = bank_repo.get_connection(str(entry.path))
    placeholders = ",".join("?" * len(type_ids))
    sql = f"SELECT DISTINCT id FROM questions WHERE question_type_id IN ({placeholders})"
    args: list = [*type_ids]
    if collection_ids:
        cph = ",".join("?" * len(collection_ids))
        sql += f" AND collection_id IN ({cph})"
        args.extend(collection_ids)
    candidates = [r["id"] for r in conn.execute(sql, args).fetchall()]
    if not candidates:
        raise BadRequestError("no eligible questions for given filters")

    # ── 第二步：用户库已答集（无 IN 形态，结果集以用户历史为界）──
    with repo.user_connection(user_db) as uconn:
        answered_set = {
            r["question_id"]
            for r in uconn.execute(
                "SELECT DISTINCT question_id FROM practice_records WHERE user_id = ? AND bank_id = ?",
                (user_id, entry.id),
            ).fetchall()
        }

    # ── Python 侧合并：未答优先随机抽取，不足补已答 ──
    unanswered = [q for q in candidates if q not in answered_set]
    picked: List[int] = []
    if unanswered:
        picked = random.sample(unanswered, min(count, len(unanswered)))
    remaining = count - len(picked)
    if remaining > 0:
        answered_avail = [q for q in candidates if q in answered_set]
        picked += random.sample(answered_avail, min(remaining, len(answered_avail)))
    return picked


@router.post("")
def create_session(body: CreateSessionBody, user=Depends(get_current_user)):
    """创建练习会话。限流 60 次/分/用户。"""
    check_user_limit("practice_sessions", user["id"], 60, 60)
    entry = _entry_or_404(body.bank_id)
    user_db = _user_db()
    count = min(body.count, MAX_SESSION_COUNT)  # >50 钳制为 50

    picked = _pick_question_ids(
        entry, user_db, user["id"],
        body.collection_ids, body.type_codes, count,
    )
    title = f"{entry.name} {date.today().isoformat()}"
    session_id = repo.create_session(
        user_db, user["id"], entry.id, title, picked, len(picked)
    )
    return envelope({
        "session_id": session_id,
        "bank_id": entry.id,
        "title": title,
        "total_count": len(picked),
        "questions": _questions_dto(entry, picked),
    })


@router.get("")
def list_sessions(
    page_num: int = Query(1, ge=1, alias="page"),
    page_size: int = Query(20, ge=1, le=100, alias="page_size"),
    user=Depends(get_current_user),
):
    """分页列出本人会话（created_at DESC），附每会话已答数。"""
    rows, total = repo.list_sessions(_user_db(), user["id"], page_num, page_size)
    items = [
        {
            "id": r["id"],
            "bank_id": r["bank_id"],
            "title": r["title"],
            "status": r["status"],
            "total_count": r["total_count"],
            "answered": r["answered"],
            "created_at": r["created_at"],
        }
        for r in rows
    ]
    has_more = page_num * page_size < total
    return envelope(page(items, total, has_more))


@router.get("/{session_id}")
def get_session(session_id: int, user=Depends(get_current_user)):
    """会话详情：session 元信息 + 题目序列 + 实时进度。非本人 404。"""
    user_db = _user_db()
    row = repo.get_session(user_db, user["id"], session_id)
    if row is None:
        raise NotFoundError("session not found")
    entry = _entry_or_404(row["bank_id"])
    items = repo.get_items(user_db, session_id)
    qids = [i["question_id"] for i in items]
    draft = json.loads(row["draft_json"]) if row["draft_json"] else {}
    answered = repo.count_answered(user_db, user["id"], row["bank_id"], qids)
    return envelope({
        "session": {
            "id": row["id"],
            "bank_id": row["bank_id"],
            "title": row["title"],
            "status": row["status"],
            "total_count": row["total_count"],
            "current_index": row["current_index"],
            "elapsed_seconds": row["elapsed_seconds"],
            "draft": draft,
        },
        "questions": _questions_dto(entry, qids),
        "progress": {"answered": answered, "total": row["total_count"]},
    })


@router.put("/{session_id}/draft")
def update_draft(session_id: int, body: DraftBody, user=Depends(get_current_user)):
    """草稿更新（last-write-wins，无冲突合并）。节流由客户端负责，服务端不限流。

    会话已提交返回 409；不存在/非本人 404。
    """
    user_db = _user_db()
    if repo.get_session(user_db, user["id"], session_id) is None:
        raise NotFoundError("session not found")
    result = repo.update_draft(
        user_db, user["id"], session_id,
        body.current_index, body.draft, body.elapsed_seconds,
    )
    if result is None:
        raise ConflictError("session already submitted")
    return envelope(data=result)


@router.post("/{session_id}/submit")
def submit_session(session_id: int, body: SubmitBody, user=Depends(get_current_user)):
    """交卷（条件更新幂等）。限流 60 次/分/用户。

    summary = submit 时点 practice_records 现状（不新增判分）；
    客户端必须在交卷前完成 attempts 同步。重放返回 replayed=true 且摘要不变。
    """
    check_user_limit("practice_sessions", user["id"], 60, 60)
    summary, replayed = repo.submit_session(
        _user_db(), user["id"], session_id, body.client_request_id
    )
    if summary is None:
        raise NotFoundError("session not found")
    return envelope(data={"summary": summary, "replayed": replayed})


@router.get("/{session_id}/result")
def get_result(session_id: int, user=Depends(get_current_user)):
    """逐题结果（is_correct/time_spent 取每题最新一条记录）+ 摘要。非本人 404。"""
    user_db = _user_db()
    row = repo.get_session(user_db, user["id"], session_id)
    if row is None:
        raise NotFoundError("session not found")
    items_rows = repo.get_items(user_db, session_id)
    qids = [i["question_id"] for i in items_rows]
    records = repo.latest_records(user_db, user["id"], row["bank_id"], qids)
    items = []
    for item in items_rows:
        rec = records.get(item["question_id"])
        items.append({
            "question_id": item["question_id"],
            "position": item["position"],
            "answered": rec is not None,
            "is_correct": (rec["is_correct"] == 1) if (rec is not None and rec["is_correct"] is not None) else None,
            "time_spent": rec["time_spent"] if rec is not None else 0,
        })
    # 摘要与 submit 同口径实时聚合
    summary = repo.summarize_session(user_db, row)
    return envelope(data={"items": items, "summary": summary})


@router.delete("/{session_id}")
def delete_session(session_id: int, user=Depends(get_current_user)):
    """删除会话及其题目序列。非本人/不存在 404。"""
    ok = repo.delete_session(_user_db(), user["id"], session_id)
    if not ok:
        raise NotFoundError("session not found")
    return envelope(message="deleted")
