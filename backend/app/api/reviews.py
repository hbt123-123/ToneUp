"""复习两端点（需求文档 §6.5 / §9.2 / §9.3）。"""
from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Body, Depends, Query, Request

from app.api.deps import get_current_user
from app.core.bank_registry import QUESTION_TYPE_MAPPING, get_registry
from app.core.config import get_settings
from app.core.errors import BadRequestError, NotFoundError
from app.repositories import user_repo
from app.schemas.common import envelope, page

router = APIRouter(prefix="/api/reviews", tags=["reviews"])


def _user_db() -> str:
    return str(get_settings().data_root / "user_data.db")


def _subject_filter_banks(subject_id: str | None) -> list[str] | None:
    """subject_id -> bank_id 白名单；None 表示不过滤。"""
    if subject_id is None:
        return None
    registry = get_registry()
    return [e.id for e in registry.entries.values() if e.subject_id == subject_id]


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
    allowed_banks = _subject_filter_banks(subject_id)

    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    try:
        where = ["user_id = ?", "next_review_at IS NOT NULL", "next_review_at <= ?"]
        args: list = [user["id"], now_iso]
        if allowed_banks is not None:
            if not allowed_banks:
                return envelope(page([], 0, False))
            where.append(f"bank_id IN ({','.join('?' * len(allowed_banks))})")
            args.extend(allowed_banks)
        where_sql = " AND ".join(where)
        rows = conn.execute(
            f"SELECT * FROM user_mastery WHERE {where_sql} ORDER BY next_review_at ASC LIMIT ?",
            [*args, limit],
        ).fetchall()
        total_due = conn.execute(
            f"SELECT COUNT(*) FROM user_mastery WHERE {where_sql}", args
        ).fetchone()[0]
    finally:
        conn.close()

    items = []
    for m in rows:
        entry = get_registry().get(m["bank_id"])
        if entry is None:
            continue
        mapping = QUESTION_TYPE_MAPPING.get(entry.subject_id, {})
        from app.repositories import bank_repo

        q = bank_repo.get_question(str(entry.path), m["question_id"])
        if q is None:
            continue
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
    body: dict = Body(default={}),
    user=Depends(get_current_user),
):
    """顺延复习：默认 +1 天；next_review_at 可覆盖；不动掌握度不计统计。"""
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
        new_next = str(override)
        try:
            datetime.fromisoformat(new_next)
        except ValueError as exc:
            raise BadRequestError("next_review_at must be ISO8601") from exc
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
