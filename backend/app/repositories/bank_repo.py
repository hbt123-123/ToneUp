"""题库只读仓储层。

架构裁决 B1：
- 连接以 URI 只读方式打开（file:...?mode=ro）+ PRAGMA query_only 双保险
- 连接线程本地缓存复用（M-310/311）：不跨线程共享；代际计数支持 admin reload 整体失效
- 本层不做业务判断，不做 markdown 清洗（清洗在 services/cleaner）
- answer_text 解析失败：error 日志并返回 None，绝不静默放水
"""
from __future__ import annotations

import json
import re
import sqlite3
import threading
from pathlib import Path

import structlog

logger = structlog.get_logger()

_thread_local = threading.local()
_open_connections: set[sqlite3.Connection] = set()
_connections_lock = threading.Lock()
_generation = 0


def _open_ro_connection(db_path: str) -> sqlite3.Connection:
    """新开只读连接并登记到全局集合，供 close_all_connections 统一关闭。

    注意：虽然是只读连接，仍启用外键约束以确保跨表查询的引用完整性验证。
    """
    posix = Path(db_path).resolve().as_posix()
    conn = sqlite3.connect(f"file:{posix}?mode=ro", uri=True)
    conn.execute("PRAGMA query_only = ON")
    # 启用外键约束，确保跨表查询时引用完整性
    conn.execute("PRAGMA foreign_keys = ON")
    conn.row_factory = sqlite3.Row
    with _connections_lock:
        _open_connections.add(conn)
    return conn


def get_connection(db_path: str) -> sqlite3.Connection:
    """按绝对路径取只读连接（线程本地缓存，代际失效，上限即线程数×库数）。"""
    if getattr(_thread_local, "gen", None) != _generation:
        _thread_local.gen = _generation
        _thread_local.conns = {}
    conns: dict[str, sqlite3.Connection] = _thread_local.conns
    conn = conns.get(db_path)
    if conn is None:
        conn = _open_ro_connection(db_path)
        conns[db_path] = conn
    return conn


def close_all_connections() -> None:
    """关闭全部只读连接并推进代际（admin reload 时对失效题库调用）。"""
    global _generation
    with _connections_lock:
        _generation += 1
        stale = list(_open_connections)
        _open_connections.clear()
    for conn in stale:
        try:
            conn.close()
        except sqlite3.Error:
            pass


def list_questions(
    db_path: str,
    question_type_id: int | None = None,
    year: int | None = None,
    page: int = 1,
    page_size: int = 20,
) -> tuple[list[sqlite3.Row], int]:
    """分页查询题目（可按题型/年份过滤），返回 (rows, total)。"""
    conn = get_connection(db_path)
    where: list[str] = []
    args: list[object] = []
    if question_type_id is not None:
        where.append("q.question_type_id = ?")
        args.append(question_type_id)
    if year is not None:
        where.append("c.year = ?")
        args.append(year)
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    total = conn.execute(
        f"SELECT COUNT(*) FROM questions q JOIN collections c ON c.id = q.collection_id{where_sql}",
        args,
    ).fetchone()[0]
    offset = (page - 1) * page_size
    rows = conn.execute(
        "SELECT q.*, c.year AS year FROM questions q JOIN collections c ON c.id = q.collection_id"
        f"{where_sql} ORDER BY c.year, q.display_order LIMIT ? OFFSET ?",
        [*args, page_size, offset],
    ).fetchall()
    return rows, total


def get_question(db_path: str, question_id: int) -> sqlite3.Row | None:
    """按主键取单题（含 year）。"""
    conn = get_connection(db_path)
    return conn.execute(
        "SELECT q.*, c.year AS year FROM questions q JOIN collections c ON c.id = q.collection_id WHERE q.id = ?",
        (question_id,),
    ).fetchone()


def _chunked(ids: list[int], size: int = 500) -> list[list[int]]:
    return [ids[i:i + size] for i in range(0, len(ids), size)]


def get_questions(db_path: str, question_ids: list[int]) -> dict[int, sqlite3.Row]:
    """批量按主键取题（含 year），返回 {id: row}（M-285/M-293 N+1 批量化支撑）。"""
    conn = get_connection(db_path)
    result: dict[int, sqlite3.Row] = {}
    for chunk in _chunked(question_ids):
        placeholders = ",".join("?" * len(chunk))
        rows = conn.execute(
            "SELECT q.*, c.year AS year FROM questions q JOIN collections c ON c.id = q.collection_id"
            f" WHERE q.id IN ({placeholders})",
            chunk,
        ).fetchall()
        for row in rows:
            result[int(row["id"])] = row
    return result


def get_passage(db_path: str, passage_id: int) -> sqlite3.Row | None:
    """取文章正文（仅英语库有 passages 表）。"""
    conn = get_connection(db_path)
    try:
        return conn.execute("SELECT * FROM passages WHERE id = ?", (passage_id,)).fetchone()
    except sqlite3.OperationalError as exc:
        # M-312：仅吞 "no such table"（库不含 passages 表），其余 OperationalError 上抛
        if "no such table" not in str(exc):
            raise
        return None


def get_passages(db_path: str, passage_ids: list[int]) -> dict[int, sqlite3.Row]:
    """批量取文章正文，返回 {id: row}；无 passages 表的库返回空 dict（M-284 支撑）。"""
    conn = get_connection(db_path)
    result: dict[int, sqlite3.Row] = {}
    for chunk in _chunked(passage_ids):
        placeholders = ",".join("?" * len(chunk))
        try:
            rows = conn.execute(
                f"SELECT * FROM passages WHERE id IN ({placeholders})",
                chunk,
            ).fetchall()
        except sqlite3.OperationalError as exc:
            if "no such table" not in str(exc):
                raise
            return {}
        for row in rows:
            result[int(row["id"])] = row
    return result


def has_passages_table(db_path: str) -> bool:
    conn = get_connection(db_path)
    row = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='passages'"
    ).fetchone()
    return row is not None


def get_image(db_path: str, image_id: int) -> sqlite3.Row | None:
    """取图片 BLOB 与 mime。"""
    conn = get_connection(db_path)
    return conn.execute("SELECT data, mime FROM images WHERE id = ?", (image_id,)).fetchone()


def parse_answer(answer_text: str | None, type_code: str):
    """把 answer_text 解析为判分用标准结构；解析失败 error 日志并返回 None。

    SINGLE/READING -> "A" 形式标签 str
    CLOZE          -> ["B","D",...] 按空序标签数组
    ORDERING       -> ["3","1","2",...] 顺序数组
    主观题四类      -> 原文 str（无需结构化）
    """
    if type_code in ("FILL_BLANK", "SOLUTION", "TRANSLATION", "ESSAY"):
        return answer_text
    if answer_text is None:
        logger.error("answer_parse_failed", type_code=type_code, reason="answer_text is NULL")
        return None

    text = answer_text.strip()

    def _labels_from_list(raw: str) -> list[str] | None:
        s = raw.strip()
        if s.startswith("["):
            try:
                arr = json.loads(s)
                if isinstance(arr, list) and all(isinstance(x, (str, int)) for x in arr):
                    return [str(x).strip().upper() for x in arr]
            except json.JSONDecodeError:
                pass
            return None
        parts = [p.strip().upper() for p in s.replace("，", ",").split(",") if p.strip()]
        if len(parts) > 1 or (len(parts) == 1 and len(parts[0]) == 1):
            return parts
        # 限定 ASCII 字母数字（H-101：str.isalnum() 对 CJK 为真，
        # "选AB" 会被误解析成 ['选', 'A', 'B']）
        chars = [ch.upper() for ch in s if ch.isascii() and ch.isalnum()]
        return chars or None

    if type_code in ("SINGLE", "READING"):
        upper = text.upper()
        for ch in upper:
            if "A" <= ch <= "Z":
                return ch
        logger.error("answer_parse_failed", type_code=type_code, answer=text[:50])
        return None

    if type_code == "CLOZE":
        labels = _labels_from_list(text)
        if not labels:
            logger.error("answer_parse_failed", type_code=type_code, answer=text[:50])
            return None
        return labels

    if type_code == "ORDERING":
        s = text.strip()
        if s.startswith("["):
            try:
                arr = json.loads(s)
                if isinstance(arr, list):
                    return [str(x).strip() for x in arr]
            except json.JSONDecodeError:
                pass
        compact = [p.strip() for p in s.replace("，", ",").replace(" ", "").split(",") if p.strip()]
        if len(compact) > 1:
            return compact
        # M-313：按连续数字段切分，避免 "12 3" 被逐字符拆成 1/2/3
        tokens = re.findall(r"\d+", s)
        if len(tokens) > 1:
            return tokens
        if tokens:
            return list(tokens[0])
        logger.error("answer_parse_failed", type_code=type_code, answer=text[:50])
        return None

    logger.error("answer_parse_failed", type_code=type_code, reason="unknown type_code")
    return None
