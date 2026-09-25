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


def list_all_tags(tags_db_path: str) -> list[dict]:
    """查询全部标签（不按学科过滤，M-292 支撑）。

    返回: [{"id": ..., "tag_name": ...}, ...]
    """
    conn = sqlite3.connect(tags_db_path)
    try:
        conn.row_factory = sqlite3.Row
        rows = conn.execute("SELECT id, tag_name FROM tags ORDER BY id").fetchall()
        return [{"id": row["id"], "tag_name": row["tag_name"]} for row in rows]
    finally:
        conn.close()


def filter_valid_tag_ids(tags_db_path: str, subject: str, tag_ids: list[int]) -> list[int]:
    """从给定 ID 列表中筛选出属于指定学科的有效标签 ID。

    规则：
    - ID 必须存在于 tags 表中
    - 且 tags 表的 subject 必须等于传入 subject
    - 返回保序去重后的合法子集（M-316：实现本为保序，修正文档描述）
    - D5 空标签容忍：当 tags 表为空时返回空列表，不抛出异常

    参数:
        tags_db_path: SQLite 数据库路径
        subject: 学科字符串，如 'math'
        tag_ids: 待校验的标签 ID 列表

    返回:
        合法的、属于该学科的 tag_id 列表（按原顺序去重）
    """
    if not tag_ids:
        return []

    # M-317：输入先去重，再分块 IN 查询，规避超长 IN 列表触顶 SQLite 变量上限
    deduped: list[int] = []
    seen_input: set[int] = set()
    for tid in tag_ids:
        if tid not in seen_input:
            seen_input.add(tid)
            deduped.append(tid)

    conn = sqlite3.connect(tags_db_path)
    try:
        conn.row_factory = sqlite3.Row
        valid_ids: set[int] = set()
        for i in range(0, len(deduped), 500):
            chunk = deduped[i:i + 500]
            placeholders = ",".join("?" * len(chunk))
            rows = conn.execute(
                f"SELECT id FROM tags WHERE subject=? AND id IN ({placeholders})",
                (subject, *chunk),
            ).fetchall()
            valid_ids.update(row["id"] for row in rows)

        # 保序：按去重后的输入顺序保留合法子集
        return [tid for tid in deduped if tid in valid_ids]
    finally:
        conn.close()