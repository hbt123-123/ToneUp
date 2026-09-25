"""复习两端点（需求文档 §6.5 / §9.2 / §9.3）。"""
from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Body, Depends, Query, Request

from app.api.deps import get_current_user
from app.core.bank_registry import QUESTION_TYPE_MAPPING, get_registry
from app.core.config import get_settings
from app.core.errors import BadRequestError, NotFoundError
from app.repositories import bank_repo, user_repo
from app.schemas.common import envelope, page

router = APIRouter(prefix="/api/reviews", tags=["reviews"])


def _user_db() -> str:
    return str(get_settings().data_root / "user_data.db")


@router.get("/today")
def today(
    limit: int = Query(20, ge=1, le=100),
    subject_id: str | None = None,
    user=Depends(get_current_user),
):
    """今日到期题目：next_review_at <= now 升序；携带 type_code 与题面信息可直接开练。"""
    settings = get_settings()
    db = _user_db()
    now_iso = datetime.now(timezone.utc).isoformat()

    # M-286：WHERE 直接限定启用库白名单。禁用/已删除库的 mastery 行在下方
    # 取题阶段必然被跳过，却会占用 LIMIT 名额，导致返回的有效条目偏少
    registry = get_registry()
    if subject_id is not None:
        bank_ids = [
            e.id for e in registry.entries.values()
            if e.enabled and e.subject_id == subject_id
        ]
    else:
        bank_ids = [e.id for e in registry.entries.values() if e.enabled]
    if not bank_ids:
        return envelope(page([], 0, False))

    where = [
        "user_id = ?",
        "next_review_at IS NOT NULL",
        "next_review_at <= ?",
        f"bank_id IN ({','.join('?' * len(bank_ids))})",
    ]
    args: list = [user["id"], now_iso, *bank_ids]
    where_sql = " AND ".join(where)

    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row

    def _fetch_due() -> tuple[list, int]:
        rows_ = conn.execute(
            f"SELECT * FROM user_mastery WHERE {where_sql} ORDER BY next_review_at ASC LIMIT ?",
            [*args, limit],
        ).fetchall()
        total = conn.execute(
            f"SELECT COUNT(*) FROM user_mastery WHERE {where_sql}", args
        ).fetchone()[0]
        return rows_, total

    def _load_qrows(current_rows: list) -> dict[str, dict[int, sqlite3.Row]]:
        """M-285：按 bank 分组批量取题，替代逐条 get_question 的 N+1 查询。"""
        grouped: dict[str, list[int]] = {}
        for m in current_rows:
            grouped.setdefault(m["bank_id"], []).append(m["question_id"])
        result: dict[str, dict[int, sqlite3.Row]] = {}
        for bid, qids in grouped.items():
            entry = registry.get(bid)
            if entry is None:
                continue
            result[bid] = bank_repo.get_questions(str(entry.path), qids)
        return result

    try:
        rows, total_due = _fetch_due()
        qrows = _load_qrows(rows)

        # M-286：启用库存在但题目已不存在（题库重载/删题遗留）的 mastery 行
        # 属孤儿数据，就地回收并重查——否则永远空占到期名额
        missing = [
            (m["bank_id"], m["question_id"])
            for m in rows
            if m["question_id"] not in qrows.get(m["bank_id"], {})
        ]
        if missing:
            with conn:
                conn.executemany(
                    "DELETE FROM user_mastery "
                    "WHERE user_id = ? AND bank_id = ? AND question_id = ?",
                    [(user["id"], bid, qid) for bid, qid in missing],
                )
            rows, total_due = _fetch_due()
            qrows = _load_qrows(rows)
    finally:
        conn.close()

    items = []
    for m in rows:
        q = qrows.get(m["bank_id"], {}).get(m["question_id"])
        if q is None:
            continue
        entry = registry.get(m["bank_id"])
        mapping = QUESTION_TYPE_MAPPING.get(entry.subject_id, {})
        items.append({
            "bank_id": m["bank_id"],
            "question_id": m["question_id"],
            "type_code": mapping.get(q["question_type_id"], ""),
            "number": q["number"],
            "content": q["content"],
            "confidence_level": m["confidence_level"],
            "next_review_at": m["next_review_at"],
        })

    # 以取回的到期行数（过滤前）对比 total_due，避免被跳过的失效行误判 has_more
    has_more = total_due > len(rows)
    return envelope(page(items[:limit], total_due, has_more))


@router.post("/{question_id}/skip")
def skip(
    question_id: int,
    request: Request,
    body: dict | None = Body(default=None),
    user=Depends(get_current_user),
):
    """顺延复习：默认 +1 天；next_review_at 可覆盖；不动掌握度不计统计。"""
    # M-287：Body 可变默认 {} 会被同进程所有请求共享（Python 缺省参数陷阱），改为 None
    body = body or {}
    bank_id = body.get("bank_id") or request.query_params.get("bank_id")
    if not bank_id:
        raise BadRequestError("bank_id is required")
    override = body.get("next_review_at")

    settings = get_settings()
    db = _user_db()
    mastery = user_repo.get_mastery(db, user["id"], str(bank_id), question_id)
    if mastery is None or mastery["next_review_at"] is None:
        raise NotFoundError("question is not in the review pool")

    if override:
        try:
            parsed_dt = datetime.fromisoformat(str(override))
        except ValueError as exc:
            raise BadRequestError("next_review_at must be ISO8601") from exc
        # 统一规范化为 UTC aware ISO 串（H-91：naive/非零偏移与 now_iso
        # 做字典序比较会错乱到期判定），naive 视为 UTC
        if parsed_dt.tzinfo is None:
            parsed_dt = parsed_dt.replace(tzinfo=timezone.utc)
        new_next = parsed_dt.astimezone(timezone.utc).isoformat()
    else:
        base = datetime.now(timezone.utc)
        new_next = (base + timedelta(days=1)).isoformat()

    conn = sqlite3.connect(db)
    try:
        with conn:
            conn.execute(
                "UPDATE user_mastery SET next_review_at = ? WHERE user_id = ? AND bank_id = ? AND question_id = ?",
                (new_next, user["id"], str(bank_id), question_id),
            )
    finally:
        conn.close()
    return envelope({"question_id": question_id, "bank_id": bank_id, "next_review_at": new_next})
