from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from typing import List

from app.repositories.user_repo import user_connection

__all__ = [
    "toggle_favorite",
    "is_favorited",
    "list_favorite_banks",
]


def toggle_favorite(
    db_path: str,
    user_id: int,
    bank_id: str,
    question_id: int,
) -> bool:
    with user_connection(db_path) as conn:
        cur = conn.execute(
            "SELECT 1 FROM favorite_questions WHERE user_id = ? AND bank_id = ? AND question_id = ?",
            (user_id, bank_id, question_id),
        ).fetchone()
        if cur is not None:
            with conn:
                conn.execute(
                    "DELETE FROM favorite_questions WHERE user_id = ? AND bank_id = ? AND question_id = ?",
                    (user_id, bank_id, question_id),
                )
            return False
        with conn:
            conn.execute(
                """
                INSERT INTO favorite_questions (user_id, bank_id, question_id, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (user_id, bank_id, question_id, datetime.now(timezone.utc).isoformat()),
            )
        return True


def is_favorited(
    db_path: str,
    user_id: int,
    bank_id: str,
    question_id: int,
) -> bool:
    with user_connection(db_path) as conn:
        row = conn.execute(
            "SELECT 1 FROM favorite_questions WHERE user_id = ? AND bank_id = ? AND question_id = ?",
            (user_id, bank_id, question_id),
        ).fetchone()
    return row is not None


def list_favorite_banks(
    db_path: str,
    user_id: int,
) -> List[dict]:
    with user_connection(db_path) as conn:
        rows = conn.execute(
            """
            SELECT f.bank_id, COUNT(*) AS favorite_count
            FROM favorite_questions f
            WHERE f.user_id = ?
            GROUP BY f.bank_id
            """,
            (user_id,),
        ).fetchall()
    result: List[dict] = []
    for row in rows:
        result.append({
            "bank_id": row["bank_id"],
            "name": row["bank_id"],
            "favorite_count": row["favorite_count"],
        })
    return result
