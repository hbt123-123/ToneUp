"""错题本 CRUD + 批量同步端点（需求文档 FR-WB）。

- GET    /api/wrong-questions：分页列表（可按 bank_id / subject_id 过滤）
- POST   /api/wrong-questions：单题 upsert（存在则 attempt_count+1 并刷新 last_wrong_at）
- DELETE /api/wrong-questions/{id}：删除（校验 user_id 归属）
- POST   /api/wrong-questions/sync：localStorage 批量 upsert（冲突取 MAX）
"""
from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone

from fastapi import APIRouter, Body, Depends, Query

from app.api.deps import get_current_user
from app.core.bank_registry import get_registry
from app.core.config import get_settings
from app.core.errors import BadRequestError, NotFoundError
from app.repositories import bank_repo
from app.schemas.common import envelope, page

router = APIRouter(prefix="/api/wrong-questions", tags=["wrong-questions"])


def _user_db() -> str:
    return str(get_settings().data_root / "user_data.db")


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _row_to_item(row: sqlite3.Row) -> dict:
    """wrong_questions 行 -> API 条目（tags 由 JSON 字符串解析为列表）。"""
    try:
        tags = json.loads(row["tags"]) if row["tags"] else []
    except (TypeError, ValueError):
        tags = []
    return {
        "id": row["id"],
        "bank_id": row["bank_id"],
        "question_id": row["question_id"],
        "attempt_count": row["attempt_count"],
        "last_wrong_at": row["last_wrong_at"],
        "tags": tags,
        "created_at": row["created_at"],
    }


def _subject_filter_banks(subject_id: str | None) -> list[str] | None:
    """subject_id -> bank_id 白名单；None 表示不过滤。"""
    if subject_id is None:
        return None
    registry = get_registry()
    return [e.id for e in registry.entries.values() if e.subject_id == subject_id]


@router.get("")
def list_wrong_questions(
    bank_id: str | None = None,
    subject_id: str | None = None,
    page_num: int = Query(1, ge=1, alias="page"),
    page_size: int = Query(20, ge=1, le=100, alias="page_size"),
    user=Depends(get_current_user),
):
    """分页列出当前用户错题；可按 bank_id / subject_id 过滤。"""
    db = _user_db()
    where = ["user_id = ?"]
    args: list = [user["id"]]
    if bank_id:
        where.append("bank_id = ?")
        args.append(bank_id)
    allowed_banks = _subject_filter_banks(subject_id)
    if allowed_banks is not None:
        if not allowed_banks:
            return envelope(page([], 0, False))
        where.append(f"bank_id IN ({','.join('?' * len(allowed_banks))})")
        args.extend(allowed_banks)
    where_sql = " AND ".join(where)

    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    try:
        total = int(
            conn.execute(
                f"SELECT COUNT(*) FROM wrong_questions WHERE {where_sql}", args
            ).fetchone()[0]
        )
        offset = (page_num - 1) * page_size
        rows = conn.execute(
            f"SELECT * FROM wrong_questions WHERE {where_sql} "
            "ORDER BY last_wrong_at DESC LIMIT ? OFFSET ?",
            [*args, page_size, offset],
        ).fetchall()
    finally:
        conn.close()

    items = [_row_to_item(r) for r in rows]
    has_more = offset + len(items) < total
    return envelope(page(items, total, has_more))


@router.post("")
def add_wrong_question(body: dict = Body(...), user=Depends(get_current_user)):
    """新增或 upsert 一道错题：存在则 attempt_count+1 并刷新 last_wrong_at。"""
    bank_id = body.get("bank_id")
    question_id = body.get("question_id")
    if not bank_id or question_id is None:
        raise BadRequestError("bank_id and question_id are required")
    if isinstance(question_id, bool) or not isinstance(question_id, int):
        raise BadRequestError("question_id must be an integer")

    # M-293：校验 bank_id/question_id 真实存在，防止幽灵行入库后
    # 永远占据 list 分页且无法在题库侧打开
    entry = get_registry().get(str(bank_id))
    if entry is None:
        raise BadRequestError(f"unknown bank_id '{bank_id}'")
    if bank_repo.get_question(str(entry.path), question_id) is None:
        raise BadRequestError(f"question {question_id} not found in bank '{bank_id}'")

    db = _user_db()
    now_iso = _now_iso()
    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    try:
        with conn:
            conn.execute(
                """
                INSERT INTO wrong_questions
                    (user_id, bank_id, question_id, attempt_count, last_wrong_at, tags, created_at)
                VALUES (?, ?, ?, 1, ?, '[]', ?)
                ON CONFLICT (user_id, bank_id, question_id)
                DO UPDATE SET attempt_count = attempt_count + 1,
                              last_wrong_at = excluded.last_wrong_at
                """,
                (user["id"], bank_id, question_id, now_iso, now_iso),
            )
        row = conn.execute(
            "SELECT * FROM wrong_questions WHERE user_id = ? AND bank_id = ? AND question_id = ?",
            (user["id"], bank_id, question_id),
        ).fetchone()
    finally:
        conn.close()
    return envelope(_row_to_item(row))


@router.delete("/{wrong_id}")
def delete_wrong_question(wrong_id: int, user=Depends(get_current_user)):
    """删除错题；仅本人可删（校验 user_id 归属）。"""
    db = _user_db()
    conn = sqlite3.connect(db)
    try:
        with conn:
            cur = conn.execute(
                "DELETE FROM wrong_questions WHERE id = ? AND user_id = ?",
                (wrong_id, user["id"]),
            )
    finally:
        conn.close()
    if cur.rowcount == 0:
        # M-294：统一返回 404——区分"存在但属于他人"（403）会向调用方
        # 泄漏其他用户记录的存在性，可用于枚举探测
        raise NotFoundError("wrong question not found")
    return envelope({"id": wrong_id, "deleted": True})


@router.post("/sync")
def sync_wrong_questions(body: dict = Body(...), user=Depends(get_current_user)):
    """localStorage 批量 upsert：冲突时取 MAX(wrong_count) 与 MAX(last_practice_at)。"""
    items = body.get("items")
    if not isinstance(items, list):
        raise BadRequestError("items must be a list")
    if len(items) > 1000:
        raise BadRequestError("items too large (max 1000)")

    # M-293：先做结构校验 + 按库分组批量核对 (bank_id, question_id) 存在性，
    # 任何无效项整单拒绝（原实现不校验存在性，幽灵行入库后无法清除）
    by_bank: dict[str, list[int]] = {}
    for it in items:
        if not isinstance(it, dict):
            raise BadRequestError("each item must be an object")
        bank_id = it.get("bank_id")
        question_id = it.get("question_id")
        if not isinstance(bank_id, str) or not bank_id.strip() or question_id is None:
            raise BadRequestError("each item requires bank_id and question_id")
        if isinstance(question_id, bool) or not isinstance(question_id, int):
            raise BadRequestError("question_id must be an integer")
        if get_registry().get(bank_id) is None:
            raise BadRequestError(f"unknown bank_id '{bank_id}'")
        by_bank.setdefault(bank_id, []).append(question_id)
    for bank_id, qids in by_bank.items():
        entry = get_registry().get(bank_id)
        found = bank_repo.get_questions(str(entry.path), qids)
        for qid in qids:
            if qid not in found:
                raise BadRequestError(f"question {qid} not found in bank '{bank_id}'")

    db = _user_db()
    now_iso = _now_iso()
    conn = sqlite3.connect(db)
    try:
        with conn:
            for it in items:
                if not isinstance(it, dict):
                    raise BadRequestError("each item must be an object")
                bank_id = it.get("bank_id")
                question_id = it.get("question_id")
                if not bank_id or question_id is None:
                    raise BadRequestError("each item requires bank_id and question_id")
                if isinstance(question_id, bool) or not isinstance(question_id, int):
                    raise BadRequestError("question_id must be an integer")
                wrong_count = it.get("wrong_count")
                # M-295：None 保持默认 1（向后兼容）；bool 是 int 子类必须显式排除；
                # 其余畸形值显式 400 而非静默回退 1（计数被静默改写更难排查）
                if wrong_count is None:
                    wrong_count = 1
                if isinstance(wrong_count, bool) or not isinstance(wrong_count, int):
                    raise BadRequestError("wrong_count must be an integer")
                if wrong_count < 1:
                    raise BadRequestError("wrong_count must be >= 1")
                # 客户端时间统一规范化为 UTC ISO（H-93：SQL MAX 在 TEXT 列上
                # 是字典序比较，混入 naive/非零偏移会导致"最近错误时间"错乱）
                last_raw = it.get("last_practice_at")
                last_practice_at = now_iso
                if last_raw:
                    try:
                        parsed_dt = datetime.fromisoformat(str(last_raw))
                        if parsed_dt.tzinfo is None:
                            parsed_dt = parsed_dt.replace(tzinfo=timezone.utc)
                        last_practice_at = parsed_dt.astimezone(timezone.utc).isoformat()
                    except ValueError:
                        last_practice_at = now_iso
                conn.execute(
                    """
                    INSERT INTO wrong_questions
                        (user_id, bank_id, question_id, attempt_count, last_wrong_at, tags, created_at)
                    VALUES (?, ?, ?, ?, ?, '[]', ?)
                    ON CONFLICT (user_id, bank_id, question_id)
                    DO UPDATE SET attempt_count = MAX(attempt_count, excluded.attempt_count),
                                  last_wrong_at = MAX(last_wrong_at, excluded.last_wrong_at)
                    """,
                    (user["id"], bank_id, question_id, wrong_count, last_practice_at, now_iso),
                )
    finally:
        conn.close()
    return envelope({"synced": len(items)})
