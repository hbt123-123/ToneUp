"""用户库仓储 — 每操作独立连接（架构裁决 B1），全参数绑定。

连接策略：FastAPI 线程池承载同步 def，sqlite3 连接绝不跨线程共享；
每次操作经 user_connection() 现开现关，WAL + busy_timeout=5000 抗写并发。
写操作用 `with conn:` 事务包裹（成功提交 / 异常回滚）。
"""

import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Iterator, List, Optional

__all__ = [
    "UserExistsError",
    "user_connection",
    "create_user",
    "get_user_by_username",
    "get_user_by_id",
    "insert_attempt",
    "get_attempt",
    "update_attempt_result",
    "mastery_submit",
    "mastery_apply_terminal",
    "apply_attempt_terminal",
    "notes_get",
    "notes_upsert",
    "ai_feedback_create",
    "ai_feedback_set_status",
    "ai_feedback_get",
    "notes_list_public",
    "notes_list_mine",
    "note_increment_likes",
    "note_decrement_likes",
    "note_is_liked_by",
    "note_likes_for_notes",
    "notes_get_by_id",
    "notes_update",
    "notes_delete",
    "note_add_like",
    "note_remove_like",
]


class UserExistsError(Exception):
    """users.username 唯一约束冲突。"""


@contextmanager
def user_connection(db_path: str) -> Iterator[sqlite3.Connection]:
    """每操作独立连接：timeout=5 + busy_timeout=5000 + Row 工厂，用毕即关。"""
    conn = sqlite3.connect(db_path, timeout=5)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=5000")
    # M-301：SQLite 默认关闭外键约束，需连接层显式开启，
    # practice_session 级联删除（ON DELETE CASCADE）依赖此开关
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        yield conn
    finally:
        conn.close()


# ── users ────────────────────────────────────────────────────────────────────

def create_user(db_path: str, username: str, password_hash: str) -> int:
    """插入新用户（created_at 由仓储层生成 UTC ISO 时间），返回自增 id。

    username 重复抛 UserExistsError——本 INSERT 仅可能触发
    users.username 唯一约束，故 IntegrityError 可安全转译。
    """
    created_at = datetime.now(timezone.utc).isoformat()
    with user_connection(db_path) as conn:
        try:
            with conn:
                cur = conn.execute(
                    "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                    (username, password_hash, created_at),
                )
        except sqlite3.IntegrityError as exc:
            raise UserExistsError(f"username already exists: {username}") from exc
        return int(cur.lastrowid)


def get_user_by_username(db_path: str, username: str) -> Optional[sqlite3.Row]:
    """按用户名查询，无则 None。"""
    with user_connection(db_path) as conn:
        return conn.execute(
            "SELECT * FROM users WHERE username = ?",
            (username,),
        ).fetchone()


def get_user_by_id(db_path: str, user_id: int) -> Optional[sqlite3.Row]:
    """按 id 查询，无则 None。"""
    with user_connection(db_path) as conn:
        return conn.execute(
            "SELECT * FROM users WHERE id = ?",
            (user_id,),
        ).fetchone()


# ── practice_records ─────────────────────────────────────────────────────────

def insert_attempt(
    db_path: str,
    user_id: int,
    bank_id: str,
    question_id: int,
    user_answer: str,
    time_spent: int,
    mode: str,
    client_request_id: str,
    created_at: str,
) -> tuple[int, bool]:
    """插入练习记录（is_correct/score 置 NULL 待判），返回 (id, is_duplicate)。

    幂等基石 uq_attempt_request(user_id, client_request_id)：同 key 重放时
    IntegrityError 后回查既有行，返回 (既有id, True)，不产生第二行。
    """
    with user_connection(db_path) as conn:
        try:
            with conn:
                cur = conn.execute(
                    """
                    INSERT INTO practice_records
                        (user_id, bank_id, question_id, user_answer,
                         is_correct, score, time_spent, mode,
                         client_request_id, created_at)
                    VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?)
                    """,
                    (user_id, bank_id, question_id, user_answer,
                     time_spent, mode, client_request_id, created_at),
                )
        except sqlite3.IntegrityError:
            row = conn.execute(
                "SELECT id FROM practice_records WHERE user_id = ? AND client_request_id = ?",
                (user_id, client_request_id),
            ).fetchone()
            if row is None:
                raise
            return int(row["id"]), True
        return int(cur.lastrowid), False


def get_attempt(db_path: str, attempt_id: int) -> Optional[sqlite3.Row]:
    """按 id 查询练习记录，无则 None。"""
    with user_connection(db_path) as conn:
        return conn.execute(
            "SELECT * FROM practice_records WHERE id = ?",
            (attempt_id,),
        ).fetchone()


def update_attempt_result(db_path: str, attempt_id: int, is_correct: bool, score: float) -> bool:
    """写入判定结果，返回是否生效。

    WHERE is_correct IS NULL 为一次性终态守卫：已判定的记录拒绝覆盖，
    防止自评/复判互相踩踏。
    """
    with user_connection(db_path) as conn:
        with conn:
            cur = conn.execute(
                """
                UPDATE practice_records
                SET is_correct = ?, score = ?
                WHERE id = ? AND is_correct IS NULL
                """,
                (int(is_correct), score, attempt_id),
            )
        return cur.rowcount == 1


# ── user_mastery ─────────────────────────────────────────────────────────────

def mastery_submit(db_path: str, user_id: int, bank_id: str, question_id: int, now_iso: str) -> None:
    """提交即计数：首插 total_attempts=1，冲突则 +1 并刷新 last_practice_at。"""
    with user_connection(db_path) as conn:
        with conn:
            conn.execute(
                """
                INSERT INTO user_mastery (user_id, bank_id, question_id, total_attempts, last_practice_at)
                VALUES (?, ?, ?, 1, ?)
                ON CONFLICT (user_id, bank_id, question_id)
                DO UPDATE SET total_attempts = total_attempts + 1,
                              last_practice_at = excluded.last_practice_at
                """,
                (user_id, bank_id, question_id, now_iso),
            )


def mastery_apply_terminal(
    db_path: str,
    user_id: int,
    bank_id: str,
    question_id: int,
    is_correct: bool,
    next_review_at: str,
    confidence_level: int,
    now_iso: str,
) -> None:
    """终态回填掌握度：correct_attempts 按 is_correct(1/0) 增量，覆写复习计划与置信度。"""
    with user_connection(db_path) as conn:
        with conn:
            conn.execute(
                """
                UPDATE user_mastery
                SET correct_attempts = correct_attempts + ?,
                    confidence_level = ?,
                    next_review_at = ?,
                    last_practice_at = ?
                WHERE user_id = ? AND bank_id = ? AND question_id = ?
                """,
                (int(bool(is_correct)), confidence_level, next_review_at,
                 now_iso, user_id, bank_id, question_id),
            )


def get_mastery(db_path: str, user_id: int, bank_id: str, question_id: int) -> Optional[sqlite3.Row]:
    """查单题掌握度行，无则 None。"""
    with user_connection(db_path) as conn:
        return conn.execute(
            "SELECT * FROM user_mastery WHERE user_id = ? AND bank_id = ? AND question_id = ?",
            (user_id, bank_id, question_id),
        ).fetchone()


def apply_attempt_terminal(
    db_path: str,
    attempt_id: int,
    user_id: int,
    bank_id: str,
    question_id: int,
    is_correct: bool,
    score: Optional[float],
    next_review_at: Optional[str],
    confidence_level: int,
    now_iso: str,
) -> bool:
    """M-329：判定结果写入与掌握度终态回填合并为单事务。

    先以 is_correct IS NULL 守卫写 practice_records（防自评/复判踩踏），
    rowcount==1 才继续回填 user_mastery；任一步失败整体回滚，
    消除「records 已判、mastery 丢失」的中间态。
    返回是否成功应用（False 表示 attempt 已被判定，迟到结果丢弃）。
    """
    with user_connection(db_path) as conn:
        with conn:
            cur = conn.execute(
                """
                UPDATE practice_records
                SET is_correct = ?, score = ?
                WHERE id = ? AND is_correct IS NULL
                """,
                (int(bool(is_correct)), score, attempt_id),
            )
            if cur.rowcount != 1:
                return False
            conn.execute(
                """
                UPDATE user_mastery
                SET correct_attempts = correct_attempts + ?,
                    confidence_level = ?,
                    next_review_at = ?,
                    last_practice_at = ?
                WHERE user_id = ? AND bank_id = ? AND question_id = ?
                """,
                (int(bool(is_correct)), confidence_level, next_review_at,
                 now_iso, user_id, bank_id, question_id),
            )
            return True


def latest_pending_attempt(
    db_path: str, user_id: int, bank_id: str, question_id: int
) -> Optional[sqlite3.Row]:
    """本人该题最新一条待判分流水（is_correct IS NULL），无则 None。"""
    with user_connection(db_path) as conn:
        return conn.execute(
            """
            SELECT * FROM practice_records
            WHERE user_id = ? AND bank_id = ? AND question_id = ? AND is_correct IS NULL
            ORDER BY id DESC LIMIT 1
            """,
            (user_id, bank_id, question_id),
        ).fetchone()


def ai_feedback_get_by_attempt(
    db_path: str, attempt_id: int
) -> Optional[sqlite3.Row]:
    """按 attempt 查 AI 判分任务（取最新一条）。"""
    with user_connection(db_path) as conn:
        return conn.execute(
            "SELECT * FROM ai_feedback WHERE attempt_id = ? ORDER BY created_at DESC LIMIT 1",
            (attempt_id,),
        ).fetchone()


# ── user_notes ───────────────────────────────────────────────────────────────

def notes_get(db_path: str, user_id: int, bank_id: str, question_id: int) -> Optional[sqlite3.Row]:
    """查单题笔记，无则 None。"""
    with user_connection(db_path) as conn:
        return conn.execute(
            "SELECT * FROM user_notes WHERE user_id = ? AND bank_id = ? AND question_id = ?",
            (user_id, bank_id, question_id),
        ).fetchone()


def notes_upsert(
    db_path: str,
    user_id: int,
    bank_id: str,
    question_id: int,
    note_text: str,
    visibility: str,
    now_iso: str,
) -> int:
    """保存（upsert）笔记；回查返回 note_id。

    M-318：ON CONFLICT 走 UPDATE 分支时 cur.lastrowid 不可靠，
    事务内按唯一键回查真实 id。
    """
    with user_connection(db_path) as conn:
        with conn:
            conn.execute(
                """
                INSERT INTO user_notes (user_id, bank_id, question_id, note_text, visibility, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, bank_id, question_id)
                DO UPDATE SET note_text = excluded.note_text,
                              visibility = excluded.visibility,
                              updated_at = excluded.updated_at
                """,
                (user_id, bank_id, question_id, note_text, visibility, now_iso),
            )
            row = conn.execute(
                "SELECT id FROM user_notes WHERE user_id = ? AND bank_id = ? AND question_id = ?",
                (user_id, bank_id, question_id),
            ).fetchone()
            return int(row["id"])


# ── ai_feedback ──────────────────────────────────────────────────────────────

def ai_feedback_create(
    db_path: str,
    feedback_id: str,
    user_id: int,
    attempt_id: int,
    bank_id: str,
    question_id: int,
    image_path: str,
    created_at: str,
) -> None:
    """创建 AI 批改任务，初始 status='queued'。"""
    with user_connection(db_path) as conn:
        with conn:
            conn.execute(
                """
                INSERT INTO ai_feedback
                    (id, user_id, attempt_id, bank_id, question_id,
                     status, image_path, created_at)
                VALUES (?, ?, ?, ?, ?, 'queued', ?, ?)
                """,
                (feedback_id, user_id, attempt_id, bank_id, question_id,
                 image_path, created_at),
            )


def ai_feedback_set_status(
    db_path: str,
    feedback_id: str,
    new_status: str,
    expected_old_status: str,
    completed_at: Optional[str] = None,
    error_message: Optional[str] = None,
    raw_response: Optional[str] = None,
    error_reason: Optional[str] = None,
    tag_ids_json: Optional[str] = None,
    is_correct: Optional[bool] = None,
) -> bool:
    """带期望旧状态守卫的状态迁移，返回 rowcount==1。

    动态 SET 仅包含非 None 字段（列名来自下方固定白名单，非外部输入）。
    注意：ai_feedback 表无 score 列——分数持久化在 practice_records 上。
    """
    extras = {
        "completed_at": completed_at,
        "error_message": error_message,
        "raw_response": raw_response,
        "error_reason": error_reason,
        "tag_ids_json": tag_ids_json,
        "is_correct": None if is_correct is None else int(is_correct),
    }
    set_parts = ["status = ?"]
    params: list = [new_status]
    for column, value in extras.items():
        if value is not None:
            set_parts.append(f"{column} = ?")
            params.append(value)
    params.extend([feedback_id, expected_old_status])

    with user_connection(db_path) as conn:
        with conn:
            cur = conn.execute(
                f"UPDATE ai_feedback SET {', '.join(set_parts)} "
                f"WHERE id = ? AND status = ?",
                params,
            )
        return cur.rowcount == 1


def ai_feedback_get(db_path: str, feedback_id: str) -> Optional[sqlite3.Row]:
    """按 id 查询 AI 批改任务，无则 None。"""
    with user_connection(db_path) as conn:
        return conn.execute(
            "SELECT * FROM ai_feedback WHERE id = ?",
            (feedback_id,),
        ).fetchone()


def notes_list_public(
    db_path: str,
    bank_id: str,
    question_id: int,
    page: int = 1,
    page_size: int = 20,
) -> tuple[List[sqlite3.Row], int]:
    with user_connection(db_path) as conn:
        total = conn.execute(
            """
            SELECT COUNT(*) FROM user_notes
            WHERE bank_id = ? AND question_id = ? AND visibility = 'public'
            """,
            (bank_id, question_id),
        ).fetchone()[0]
        offset = (page - 1) * page_size
        rows = conn.execute(
            """
            SELECT * FROM user_notes
            WHERE bank_id = ? AND question_id = ? AND visibility = 'public'
            ORDER BY updated_at DESC LIMIT ? OFFSET ?
            """,
            (bank_id, question_id, page_size, offset),
        ).fetchall()
    return rows, total


def notes_list_mine(
    db_path: str,
    user_id: int,
    bank_id: str,
    question_id: int,
) -> Optional[sqlite3.Row]:
    """查询用户在指定题目上的笔记（单条）。

    Args:
        db_path: 数据库路径
        user_id: 用户ID
        bank_id: 题库ID（必填，确保资源归属验证）
        question_id: 题目ID（必填，确保资源归属验证）

    Returns:
        用户笔记记录，不存在则返回 None
    """
    with user_connection(db_path) as conn:
        return conn.execute(
            "SELECT * FROM user_notes WHERE user_id = ? AND bank_id = ? AND question_id = ?",
            (user_id, bank_id, question_id),
        ).fetchone()


def note_increment_likes(db_path: str, note_id: int) -> None:
    with user_connection(db_path) as conn:
        with conn:
            conn.execute(
                "UPDATE user_notes SET like_count = like_count + 1 WHERE id = ?",
                (note_id,),
            )


def note_decrement_likes(db_path: str, note_id: int) -> None:
    with user_connection(db_path) as conn:
        with conn:
            conn.execute(
                "UPDATE user_notes SET like_count = MAX(0, like_count - 1) WHERE id = ?",
                (note_id,),
            )


def note_is_liked_by(db_path: str, note_id: int, user_id: int) -> bool:
    with user_connection(db_path) as conn:
        row = conn.execute(
            "SELECT 1 FROM note_likes WHERE note_id = ? AND user_id = ?",
            (note_id, user_id),
        ).fetchone()
    return row is not None


def note_likes_for_notes(db_path: str, user_id: int, note_ids: list[int]) -> set[int]:
    """批量查询用户对一组笔记的点赞 id 集合（M-282 N+1 批量化支撑）。"""
    if not note_ids:
        return set()
    with user_connection(db_path) as conn:
        result: set[int] = set()
        for i in range(0, len(note_ids), 500):
            chunk = note_ids[i:i + 500]
            placeholders = ",".join("?" * len(chunk))
            rows = conn.execute(
                f"SELECT note_id FROM note_likes WHERE user_id = ? AND note_id IN ({placeholders})",
                (user_id, *chunk),
            ).fetchall()
            result.update(int(r["note_id"]) for r in rows)
    return result


def notes_get_by_id(db_path: str, note_id: int) -> Optional[sqlite3.Row]:
    """按 note id 查询单条笔记，无则 None。"""
    with user_connection(db_path) as conn:
        return conn.execute(
            "SELECT * FROM user_notes WHERE id = ?",
            (note_id,),
        ).fetchone()


def notes_update(
    db_path: str,
    note_id: int,
    user_id: int,
    note_text: Optional[str] = None,
    visibility: Optional[str] = None,
    now_iso: Optional[str] = None,
) -> bool:
    """更新笔记内容/可见性，仅限本人。返回是否生效。"""
    parts: list[str] = []
    params: list[object] = []
    if note_text is not None:
        parts.append("note_text = ?")
        params.append(note_text)
    if visibility is not None:
        parts.append("visibility = ?")
        params.append(visibility)
    if now_iso is not None:
        parts.append("updated_at = ?")
        params.append(now_iso)
    if not parts:
        return True
    params.extend([note_id, user_id])
    with user_connection(db_path) as conn:
        with conn:
            cur = conn.execute(
                f"UPDATE user_notes SET {', '.join(parts)} WHERE id = ? AND user_id = ?",
                params,
            )
    return cur.rowcount == 1


def notes_delete(db_path: str, note_id: int, user_id: int) -> bool:
    """删除笔记，仅限本人。返回是否生效。"""
    with user_connection(db_path) as conn:
        with conn:
            cur = conn.execute(
                "DELETE FROM user_notes WHERE id = ? AND user_id = ?",
                (note_id, user_id),
            )
    return cur.rowcount == 1


def note_add_like(db_path: str, note_id: int, user_id: int, now_iso: str) -> bool:
    """点赞笔记，返回是否成功（重复返回 False）。"""
    with user_connection(db_path) as conn:
        try:
            with conn:
                conn.execute(
                    "INSERT INTO note_likes (note_id, user_id, created_at) VALUES (?, ?, ?)",
                    (note_id, user_id, now_iso),
                )
            return True
        except sqlite3.IntegrityError:
            return False


def note_remove_like(db_path: str, note_id: int, user_id: int) -> bool:
    """取消点赞，返回是否成功（未点赞返回 False）。"""
    with user_connection(db_path) as conn:
        with conn:
            cur = conn.execute(
                "DELETE FROM note_likes WHERE note_id = ? AND user_id = ?",
                (note_id, user_id),
            )
    return cur.rowcount == 1


def note_get_like_count(db_path: str, note_id: int) -> int:
    """获取笔记当前点赞数。"""
    with user_connection(db_path) as conn:
        row = conn.execute(
            "SELECT like_count FROM user_notes WHERE id = ?",
            (note_id,),
        ).fetchone()
    return row["like_count"] if row else 0
