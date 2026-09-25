from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from typing import List

from app.core.bank_registry import get_registry
from app.repositories.user_repo import user_connection

__all__ = [
    "toggle_favorite",
    "is_favorited",
    "list_favorite_banks",
    "add_favorite",
    "remove_favorite",
]


def add_favorite(
    db_path: str,
    user_id: int,
    bank_id: str,
    question_id: int,
) -> None:
    """收藏题目（幂等：已收藏则忽略）。"""
    with user_connection(db_path) as conn:
        with conn:
            conn.execute(
                "INSERT OR IGNORE INTO favorite_questions (user_id, bank_id, question_id, created_at) VALUES (?, ?, ?, ?)",
                (user_id, bank_id, question_id, datetime.now(timezone.utc).isoformat()),
            )


def remove_favorite(
    db_path: str,
    user_id: int,
    bank_id: str,
    question_id: int,
) -> None:
    """取消收藏（幂等：未收藏则忽略）。"""
    with user_connection(db_path) as conn:
        with conn:
            conn.execute(
                "DELETE FROM favorite_questions WHERE user_id = ? AND bank_id = ? AND question_id = ?",
                (user_id, bank_id, question_id),
            )


def toggle_favorite(
    db_path: str,
    user_id: int,
    bank_id: str,
    question_id: int,
) -> bool:
    """切换收藏状态：已收藏则取消返回 False，未收藏则收藏返回 True。

    M-314：单事务原子化——先 DELETE 并以 rowcount 判定原状态，
    未命中再 INSERT；INSERT 撞唯一约束说明并发请求刚收藏，转为删除返回 False，
    消除原「先查后写」竞态窗口。
    """
    with user_connection(db_path) as conn:
        with conn:
            cur = conn.execute(
                "DELETE FROM favorite_questions WHERE user_id = ? AND bank_id = ? AND question_id = ?",
                (user_id, bank_id, question_id),
            )
            if cur.rowcount == 1:
                return False
            try:
                conn.execute(
                    """
                    INSERT INTO favorite_questions (user_id, bank_id, question_id, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    (user_id, bank_id, question_id, datetime.now(timezone.utc).isoformat()),
                )
            except sqlite3.IntegrityError:
                # 并发竞争：另一请求刚插入成功（SQLite 默认 ABORT 仅回滚本语句）
                conn.execute(
                    "DELETE FROM favorite_questions WHERE user_id = ? AND bank_id = ? AND question_id = ?",
                    (user_id, bank_id, question_id),
                )
                return False
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
        entry = get_registry().get(row["bank_id"])
        result.append({
            "bank_id": row["bank_id"],
            # M-315：展示名取注册表 entry.name，注册表未收录时回退 bank_id
            "name": entry.name if entry is not None else row["bank_id"],
            "favorite_count": row["favorite_count"],
        })
    return result
