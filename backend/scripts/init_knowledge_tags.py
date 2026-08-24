"""知识点标签库两表空结构初始化脚本。

D5 决策：首次建库即空表。仅创建表结构，不插入任何示例数据。

Usage:
    python init_knowledge_tags.py [-db PATH]
    python -c "from init_knowledge_tags import init_knowledge_tags; init_knowledge_tags('path/to/db')"
"""

import argparse
import sqlite3


def init_knowledge_tags(db_path: str) -> None:
    """Initialize the two empty tables for the knowledge tags library.

    Creates the following tables if they do not already exist:
      - tags:         subject + parent_id + tag_name UNIQUE constraint
      - question_tags: bank_id + question_id + tag_id PRIMARY KEY

    Neither table receives any row data (D5: empty table delivery).

    Args:
        db_path: Path to the SQLite database file.
    """
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

        conn.commit()
    finally:
        conn.close()


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Initialize knowledge_tags.db empty table structure"
    )
    parser.add_argument(
        "--db",
        default="../data/knowledge_tags.db",
        help="Path to SQLite database (default: ../data/knowledge_tags.db)",
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    init_knowledge_tags(args.db)