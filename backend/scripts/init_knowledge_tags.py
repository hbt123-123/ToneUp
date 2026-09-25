"""知识点标签库两表空结构初始化脚本。

D5 决策：首次建库即空表。仅创建表结构，不插入任何示例数据。

Usage:
    python init_knowledge_tags.py [-db PATH]
    python -c "from init_knowledge_tags import init_knowledge_tags; init_knowledge_tags('path/to/db')"
"""

import argparse
import sqlite3
from pathlib import Path


def init_knowledge_tags(db_path: str) -> None:
    """Initialize the two empty tables for the knowledge tags library.

    Creates the following tables if they do not already exist:
      - tags:         subject + parent_id + tag_name UNIQUE constraint
      - question_tags: bank_id + question_id + tag_id PRIMARY KEY

    Neither table receives any row data (D5: empty table delivery).

    Args:
        db_path: Path to the SQLite database file.
    """
    # M-340：目标目录不存在时先创建（默认 ../data 依赖 CWD 的旧路径下易失败）
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    try:
        cursor = conn.cursor()

        # ── tags table ──────────────────────────────────────────────
        # §5.3 原文：CREATE TABLE IF NOT EXISTS tags (
        # │   id INTEGER PRIMARY KEY,
        # │   subject TEXT NOT NULL,
        # │   parent_id INTEGER REFERENCES tags(id),
        # │   tag_name TEXT NOT NULL,
        # │   UNIQUE(subject, parent_id, tag_name)
        # │);
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS tags (
                id              INTEGER PRIMARY KEY,
                subject         TEXT    NOT NULL,
                parent_id       INTEGER REFERENCES tags (id),
                tag_name        TEXT    NOT NULL,
                UNIQUE (subject, parent_id, tag_name)
            )
            """
        )

        # ── question_tags table ─────────────────────────────────────
        # §5.3 原文：CREATE TABLE IF NOT EXISTS question_tags (
        # │   bank_id            TEXT    NOT NULL,
        # │   question_id        INTEGER NOT NULL,
        # │   tag_id             INTEGER NOT NULL,
        # │   source             TEXT    NOT NULL DEFAULT 'manual',
        # │   confidence         REAL,
        # │   created_at         TEXT    NOT NULL,
        # │   PRIMARY KEY (bank_id, question_id, tag_id)
        # │);
        # CREATE INDEX IF NOT EXISTS idx_question_tags_tag ON question_tags(tag_id);
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS question_tags (
                bank_id      TEXT    NOT NULL,
                question_id  INTEGER NOT NULL,
                tag_id       INTEGER NOT NULL,
                source       TEXT    NOT NULL DEFAULT 'manual',
                confidence   REAL,
                created_at   TEXT    NOT NULL,
                PRIMARY KEY (bank_id, question_id, tag_id)
            )
            """
        )

        # ── index on tag_id ─────────────────────────────────────────
        cursor.execute(
            "CREATE INDEX IF NOT EXISTS idx_question_tags_tag ON question_tags (tag_id)"
        )

        # M-341：表级 UNIQUE(subject, parent_id, tag_name) 对 parent_id=NULL
        # 不生效（SQL 中 NULL 互不相等），顶级标签可无限重复；
        # 用表达式唯一索引把 NULL 归一为 0 后强制唯一
        cursor.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_tags_subject_parent_tag
            ON tags (subject, COALESCE(parent_id, 0), tag_name)
            """
        )

        conn.commit()
    finally:
        conn.close()


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Initialize knowledge_tags.db empty table structure"
    )
    parser.add_argument(
        "--db",
        # M-349：默认路径基于脚本位置解析，不依赖 CWD
        default=str(Path(__file__).resolve().parents[1] / "data" / "knowledge_tags.db"),
        help="Path to SQLite database (default: <backend>/data/knowledge_tags.db)",
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    init_knowledge_tags(args.db)