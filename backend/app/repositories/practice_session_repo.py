"""练习会话仓储 — practice_session / practice_session_item 两表数据访问。

连接与事务范式照 user_repo.py（复用其 user_connection）：每操作独立连接
（现开现关，WAL + busy_timeout=5000 抗写并发），写操作 `with conn:` 事务包裹。

幂等语义（EC-01）：
- submit 为条件更新幂等——`UPDATE ... WHERE id=? AND user_id=? AND status='active'`，
  rowcount==1 即首次提交；rowcount==0 重查既有会话返回既有摘要（replayed=True）；
  若 UPDATE 因 UNIQUE(user_id, client_request_id) 冲突，回查该键所属会话：
  即本会话 → 视为并发重放返回 replayed=True；属别的会话 → 抛 ConflictError，
  绝不复用他人幂等键（否则会向调用方返回"别的会话"的摘要）。
- 已答数/摘要一律由 practice_records 实时聚合，本仓储不冗余计数。
"""

import json
import sqlite3
from typing import Dict, List, Optional, Tuple

from app.core.errors import ConflictError
from app.repositories.user_repo import user_connection

__all__ = [
    "create_session",
    "get_session",
    "get_items",
    "update_draft",
    "submit_session",
    "list_sessions",
    "delete_session",
    "count_answered",
    "latest_records",
    "summarize_session",
]


# ── 创建与查询 ───────────────────────────────────────────────────────────────

def create_session(
    db_path: str,
    user_id: int,
    bank_id: str,
    title: str,
    question_ids: List[int],
    total_count: int,
) -> int:
    """创建会话并写入题目序列，返回会话 id。

    question_ids 必须已去重（调用方契约）；position 从 0 起与客户端
    questionIndex 对齐。主行与 items 在单事务内写入。
    """
    with user_connection(db_path) as conn:
        with conn:
            cur = conn.execute(
                """
                INSERT INTO practice_session
                    (user_id, bank_id, title, status, total_count)
                VALUES (?, ?, ?, 'active', ?)
                """,
                (user_id, bank_id, title, total_count),
            )
            session_id = int(cur.lastrowid)
            conn.executemany(
                """
                INSERT INTO practice_session_item (session_id, position, question_id)
                VALUES (?, ?, ?)
                """,
                [(session_id, pos, qid) for pos, qid in enumerate(question_ids)],
            )
        return session_id


def get_session(db_path: str, user_id: int, session_id: int) -> Optional[sqlite3.Row]:
    """按 id 查会话，强制 user_id 归属校验，无权/不存在均返回 None。"""
    with user_connection(db_path) as conn:
        return conn.execute(
            "SELECT * FROM practice_session WHERE id = ? AND user_id = ?",
            (session_id, user_id),
        ).fetchone()


def get_items(db_path: str, session_id: int) -> List[sqlite3.Row]:
    """按 position 升序返回会话题目序列。"""
    with user_connection(db_path) as conn:
        return conn.execute(
            """
            SELECT * FROM practice_session_item
            WHERE session_id = ?
            ORDER BY position ASC
            """,
            (session_id,),
        ).fetchall()


# ── 草稿 ─────────────────────────────────────────────────────────────────────

def update_draft(
    db_path: str,
    user_id: int,
    session_id: int,
    current_index: int,
    draft_json: dict,
    elapsed_seconds: int,
) -> Optional[Dict]:
    """更新草稿（last-write-wins，无冲突合并），返回 {current_index, updated_at}。

    条件更新仅命中 active 会话；rowcount==0（已提交/不存在/非本人）
    返回 None，由路由层转 409/404。
    """
    payload = json.dumps(draft_json, ensure_ascii=False)
    with user_connection(db_path) as conn:
        with conn:
            cur = conn.execute(
                """
                UPDATE practice_session
                SET current_index = ?, draft_json = ?,
                    elapsed_seconds = ?, updated_at = datetime('now')
                WHERE id = ? AND user_id = ? AND status = 'active'
                """,
                (current_index, payload, elapsed_seconds, session_id, user_id),
            )
        if cur.rowcount == 0:
            return None
        row = conn.execute(
            "SELECT current_index, updated_at FROM practice_session WHERE id = ?",
            (session_id,),
        ).fetchone()
        return {"current_index": row["current_index"], "updated_at": row["updated_at"]}


# ── 交卷（条件更新幂等） ─────────────────────────────────────────────────────

def submit_session(
    db_path: str,
    user_id: int,
    session_id: int,
    client_request_id: str,
) -> Tuple[Optional[Dict], bool]:
    """提交会话，返回 (summary, replayed)。

    summary 由 practice_records 按本轮题目实时聚合（不新增判分）：
    {total, answered, correct, accuracy_rate, elapsed_seconds}。
    会话不存在/非本人返回 (None, True)，由路由层转 404。
    """
    with user_connection(db_path) as conn:
        try:
            with conn:
                cur = conn.execute(
                    """
                    UPDATE practice_session
                    SET status = 'submitted', client_request_id = ?,
                        updated_at = datetime('now')
                    WHERE id = ? AND user_id = ? AND status = 'active'
                    """,
                    (client_request_id, session_id, user_id),
                )
        except sqlite3.IntegrityError:
            # 同 request_id 已被占用：回查该键所属会话，仅当就是本会话时
            # 视为并发重放；属别的会话则明确拒绝（避免返回错误摘要）。
            row = conn.execute(
                """
                SELECT * FROM practice_session
                WHERE user_id = ? AND client_request_id = ?
                """,
                (user_id, client_request_id),
            ).fetchone()
            if row is None:
                raise
            if row["id"] != session_id:
                raise ConflictError(
                    "client_request_id already used by another session"
                )
            return _summarize(conn, row), True

        if cur.rowcount == 1:
            row = conn.execute(
                "SELECT * FROM practice_session WHERE id = ? AND user_id = ?",
                (session_id, user_id),
            ).fetchone()
            return _summarize(conn, row), False

        # rowcount==0：已提交（重放）/ 不存在 / 非本人
        row = conn.execute(
            "SELECT * FROM practice_session WHERE id = ? AND user_id = ?",
            (session_id, user_id),
        ).fetchone()
        if row is None:
            return None, True
        return _summarize(conn, row), True


def _summarize(conn: sqlite3.Connection, session_row: sqlite3.Row) -> Dict:
    """按本轮 question_ids 聚合 practice_records 现状生成摘要。

    去重口径与全库一致：按 (user_id, bank_id, question_id) 去重计数，
    correct 为其中 is_correct=1 的去重题数。会话题量 ≤50，IN 形态安全。
    """
    qids = [
        r["question_id"]
        for r in conn.execute(
            """
            SELECT question_id FROM practice_session_item
            WHERE session_id = ? ORDER BY position
            """,
            (session_row["id"],),
        ).fetchall()
    ]
    answered = count_answered_in_conn(conn, session_row["user_id"], session_row["bank_id"], qids)
    correct = 0
    if qids:
        placeholders = ",".join("?" * len(qids))
        correct = conn.execute(
            f"""
            SELECT COUNT(DISTINCT question_id) FROM practice_records
            WHERE user_id = ? AND bank_id = ? AND question_id IN ({placeholders})
              AND is_correct = 1
            """,
            [session_row["user_id"], session_row["bank_id"], *qids],
        ).fetchone()[0]
    accuracy = round(correct / answered, 4) if answered else 0.0
    return {
        "total": session_row["total_count"],
        "answered": answered,
        "correct": correct,
        "accuracy_rate": accuracy,
        "elapsed_seconds": session_row["elapsed_seconds"],
    }


def count_answered_in_conn(
    conn: sqlite3.Connection, user_id: int, bank_id: str, question_ids: List[int]
) -> int:
    """在既有连接上统计本轮题目已答去重数（供 _summarize 与连接内复用）。"""
    if not question_ids:
        return 0
    placeholders = ",".join("?" * len(question_ids))
    return int(conn.execute(
        f"""
        SELECT COUNT(DISTINCT question_id) FROM practice_records
        WHERE user_id = ? AND bank_id = ? AND question_id IN ({placeholders})
        """,
        [user_id, bank_id, *question_ids],
    ).fetchone()[0])


def count_answered(db_path: str, user_id: int, bank_id: str, question_ids: List[int]) -> int:
    """统计给定题目在 practice_records 的已答去重数（detail 端点 progress 用）。"""
    if not question_ids:
        return 0
    with user_connection(db_path) as conn:
        return count_answered_in_conn(conn, user_id, bank_id, question_ids)


def summarize_session(db_path: str, session_row: sqlite3.Row) -> Dict:
    """按会话行聚合 practice_records 现状生成摘要（与 submit 同口径，公开入口）。"""
    with user_connection(db_path) as conn:
        return _summarize(conn, session_row)


def latest_records(
    db_path: str, user_id: int, bank_id: str, question_ids: List[int]
) -> Dict[int, sqlite3.Row]:
    """取每题最新一条练习记录（result 端点用），返回 {question_id: row}。

    无记录的题不出现在结果中。id 升序遍历、后写覆盖 → 每题保留最新一条。
    """
    if not question_ids:
        return {}
    placeholders = ",".join("?" * len(question_ids))
    with user_connection(db_path) as conn:
        rows = conn.execute(
            f"""
            SELECT question_id, is_correct, time_spent, created_at
            FROM practice_records
            WHERE user_id = ? AND bank_id = ? AND question_id IN ({placeholders})
            ORDER BY id ASC
            """,
            [user_id, bank_id, *question_ids],
        ).fetchall()
    return {r["question_id"]: r for r in rows}


# ── 列表与删除 ───────────────────────────────────────────────────────────────

def list_sessions(
    db_path: str,
    user_id: int,
    page: int = 1,
    page_size: int = 20,
) -> Tuple[List[sqlite3.Row], int]:
    """分页返回本人会话（created_at DESC），附每会话已答数（去重计数）。"""
    with user_connection(db_path) as conn:
        total = conn.execute(
            "SELECT COUNT(*) FROM practice_session WHERE user_id = ?",
            (user_id,),
        ).fetchone()[0]
        offset = (page - 1) * page_size
        rows = conn.execute(
            """
            SELECT s.*,
                   (SELECT COUNT(DISTINCT r.question_id)
                      FROM practice_records r
                     WHERE r.user_id = s.user_id
                       AND r.bank_id = s.bank_id
                       AND r.question_id IN (
                           SELECT si.question_id FROM practice_session_item si
                           WHERE si.session_id = s.id
                       )
                   ) AS answered
              FROM practice_session s
             WHERE s.user_id = ?
             ORDER BY s.created_at DESC, s.id DESC
             LIMIT ? OFFSET ?
            """,
            (user_id, page_size, offset),
        ).fetchall()
    return rows, total


def delete_session(db_path: str, user_id: int, session_id: int) -> bool:
    """删除会话（显式先删 items 再删主行，不依赖外键级联开关），返回是否生效。"""
    with user_connection(db_path) as conn:
        with conn:
            row = conn.execute(
                "SELECT id FROM practice_session WHERE id = ? AND user_id = ?",
                (session_id, user_id),
            ).fetchone()
            if row is None:
                return False
            conn.execute(
                "DELETE FROM practice_session_item WHERE session_id = ?",
                (session_id,),
            )
            conn.execute(
                "DELETE FROM practice_session WHERE id = ? AND user_id = ?",
                (session_id, user_id),
            )
        return True
