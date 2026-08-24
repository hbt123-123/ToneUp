"""题库只读仓储层。

架构裁决 B1：
- 连接以 URI 只读方式打开（file:...?mode=ro）+ PRAGMA query_only 双保险
- functools.lru_cache(maxsize=5) 管理连接，check_same_thread=False 允许线程池复用
- 本层不做业务判断，不做 markdown 清洗（清洗在 services/cleaner）
- answer_text 解析失败：error 日志并返回 None，绝不静默放水
"""
from __future__ import annotations

import json
import sqlite3
from functools import lru_cache
from pathlib import Path

import structlog

logger = structlog.get_logger()


@lru_cache(maxsize=5)
def get_connection(db_path: str) -> sqlite3.Connection:
    """按绝对路径取只读连接（缓存复用，上限 5 对应四库+余量）。"""
    posix = Path(db_path).resolve().as_posix()
    conn = sqlite3.connect(f"file:{posix}?mode=ro", uri=True, check_same_thread=False)
    conn.execute("PRAGMA query_only = ON")
    conn.row_factory = sqlite3.Row
    return conn


def close_all_connections() -> None:
    """清空连接缓存（admin reload 时对失效题库调用；连接对象由 GC 关闭）。"""
    get_connection.cache_clear()


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


def get_passage(db_path: str, passage_id: int) -> sqlite3.Row | None:
    """取文章正文（仅英语库有 passages 表）。"""
    conn = get_connection(db_path)
    try:
        return conn.execute("SELECT * FROM passages WHERE id = ?", (passage_id,)).fetchone()
    except sqlite3.OperationalError:
        return None


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
        chars = [ch.upper() for ch in s if ch.isalnum()]
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
        digits = [ch for ch in s if ch.isdigit()]
        if digits:
            return digits
        logger.error("answer_parse_failed", type_code=type_code, answer=text[:50])
        return None

    logger.error("answer_parse_failed", type_code=type_code, reason="unknown type_code")
    return None
