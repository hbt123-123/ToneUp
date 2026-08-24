"""标签库仓储 — 只读查询，每次现开现关（无 WAL）。

提供两个函数：
- list_tags_by_subject：根据学科查询标签
- filter_valid_tag_ids：校验标签 ID 是否在 tags 表中存在且 subject 匹配
"""

import sqlite3
from typing import List


def list_tags_by_subject(tags_db_path: str, subject: str) -> list[dict]:
    """查询指定学科下的所有标签。

    语句: SELECT id, tag_name FROM tags WHERE subject=? ORDER BY id
    返回: [{"id": ..., "tag_name": ...}, ...]
    """
    conn = sqlite3.connect(tags_db_path)
    try:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute(
            "SELECT id, tag_name FROM tags WHERE subject=? ORDER BY id",
            (subject,),
        )
        rows = cursor.fetchall()
        return [{"id": row["id"], "tag_name": row["tag_name"]} for row in rows]
    finally:
        conn.close()


def filter_valid_tag_ids(tags_db_path: str, subject: str, tag_ids: list[int]) -> list[int]:
    """从给定 ID 列表中筛选出属于指定学科的有效标签 ID。

    规则：
    - ID 必须存在于 tags 表中
    - 且 tags 表的 subject 必须等于传入 subject
    - 返回排序后去重后的合法子集
    - D5 空标签容忍：当 tags 表为空时返回空列表，不抛出异常

    参数:
        tags_db_path: SQLite 数据库路径
        subject: 学科字符串，如 'math'
        tag_ids: 待校验的标签 ID 列表

    返回:
        合法的、属于该学科的 tag_id 列表（已排序去重）
    """
    if not tag_ids:
        return []

    conn = sqlite3.connect(tags_db_path)
    try:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()

        # 使用 IN 子查询一次性校验：SELECT id FROM tags WHERE subject=? AND id IN (?,?,...)
        # 动态生成占位符
        placeholders = ",".join("?" * len(tag_ids))
        cursor.execute(
            f"SELECT id FROM tags WHERE subject=? AND id IN ({placeholders})",
            (subject,) + tuple(tag_ids),
        )
        valid_ids = {row["id"] for row in cursor.fetchall()}

        # 保序去重：按原始 tag_ids 顺序保留，仅保留合法的，并去重
        seen: set[int] = set()
        result: list[int] = []
        for tid in tag_ids:
            if tid in valid_ids and tid not in seen:
                seen.add(tid)
                result.append(tid)
        return result
    finally:
        conn.close()