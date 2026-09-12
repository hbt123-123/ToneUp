from __future__ import annotations

import sqlite3
import uuid
from typing import List, Tuple

from app.repositories.user_repo import user_connection

__all__ = [
    "create_feedback",
    "list_feedback",
]


def create_feedback(
    db_path: str,
    user_id: int,
    bank_id: str,
    question_id: int,
    category: str,
    content: str,
    image_id: str | None,
    created_at: str,
) -> str:
    feedback_id = str(uuid.uuid4())
    with user_connection(db_path) as conn:
        with conn:
            conn.execute(
                """
                INSERT INTO question_feedback
                    (id, user_id, bank_id, question_id, category, content, image_id, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, '待处理', ?)
                """,
                (feedback_id, user_id, bank_id, question_id, category, content, image_id, created_at),
            )
    return feedback_id


def list_feedback(
    db_path: str,
    bank_id: str | None = None,
    question_id: int | None = None,
    status: str | None = None,
    user_id: int | None = None,
    created_after: str | None = None,
    page: int = 1,
    page_size: int = 20,
) -> Tuple[List[sqlite3.Row], int]:
    conditions: List[str] = []
    args: List[object] = []
    if bank_id is not None:
        conditions.append("bank_id = ?")
        args.append(bank_id)
    if question_id is not None:
        conditions.append("question_id = ?")
        args.append(question_id)
    if status is not None:
        conditions.append("status = ?")
        args.append(status)
    if user_id is not None:
        conditions.append("user_id = ?")
        args.append(user_id)
    if created_after is not None:
        conditions.append("created_at >= ?")
        args.append(created_after)

    where_sql = (" WHERE " + " AND ".join(conditions)) if conditions else ""

    with user_connection(db_path) as conn:
        total = conn.execute(
            f"SELECT COUNT(*) FROM question_feedback{where_sql}",
            args,
        ).fetchone()[0]

        offset = (page - 1) * page_size
        params = [*args, page_size, offset]
        rows = conn.execute(
            f"SELECT * FROM question_feedback{where_sql} ORDER BY created_at DESC LIMIT ? OFFSET ?",
            params,
        ).fetchall()

    return rows, total
