"""移动端测验数据库迁移脚本 — 幂等执行。

新增表与列：
- question_feedback
- note_likes
- favorite_questions
- user_notes 增加 visibility、like_count 列
- 对应索引

Usage:
    python migrate_mobile_tables.py [-db PATH]
    python -c "from migrate_mobile_tables import migrate_mobile_tables; migrate_mobile_tables('path/to/db')"
"""

import argparse
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


def migrate_mobile_tables(db_path: str) -> None:
    """幂等迁移：创建新表与索引，为 user_notes 补充新列。"""
    conn = sqlite3.connect(db_path)
    try:
        cursor = conn.cursor()
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA busy_timeout=5000")

        # ── question_feedback ──────────────────────────────
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS question_feedback (
                id TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                bank_id TEXT NOT NULL,
                question_id INTEGER NOT NULL,
                category TEXT NOT NULL CHECK(category IN ('答案有误','解析有误','题干有误','图片显示异常','其他')),
                content TEXT NOT NULL,
                image_id TEXT,
                status TEXT NOT NULL DEFAULT '待处理' CHECK(status IN ('待处理','已确认','已忽略')),
                created_at TEXT NOT NULL
            )
            """
        )
        cursor.execute(
            "CREATE INDEX IF NOT EXISTS idx_qf_user ON question_feedback(user_id, created_at)"
        )
        cursor.execute(
            "CREATE INDEX IF NOT EXISTS idx_qf_question ON question_feedback(question_id)"
        )

        # ── note_likes ─────────────────────────────────────
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS note_likes (
                note_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY(note_id, user_id)
            )
            """
        )

        # ── favorite_questions ─────────────────────────────
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS favorite_questions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                bank_id TEXT NOT NULL,
                question_id INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                UNIQUE(user_id, bank_id, question_id)
            )
            """
        )
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_favorite_questions_user ON favorite_questions(user_id)")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_favorite_questions_user_bank ON favorite_questions(user_id, bank_id)")

        # ── user_notes 新增列（幂等）───────────────────────
        for column_def in [
            ("id", "INTEGER"),
            ("visibility", "TEXT NOT NULL DEFAULT 'public' CHECK(visibility IN ('private','public'))"),
            ("like_count", "INTEGER NOT NULL DEFAULT 0"),
        ]:
            col_name, col_type = column_def
            try:
                cursor.execute(
                    f"ALTER TABLE user_notes ADD COLUMN {col_name} {col_type}"
                )
            except sqlite3.OperationalError as exc:
                # 仅"列已存在"属预期幂等场景；其余 OperationalError
                # （表不存在、权限、语法）必须暴露（H-108）
                if "duplicate column name" not in str(exc).lower():
                    raise
        # 回填已有行的 id（ALTER 新增列为 NULL）
        cursor.execute("UPDATE user_notes SET id = rowid WHERE id IS NULL")

        conn.commit()
    finally:
        conn.close()


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Migrate mobile quiz tables: question_feedback, note_likes, favorite_questions, user_notes columns"
    )
    parser.add_argument(
        "--db",
        default="../data/user_data.db",
        help="Path to SQLite database (default: ../data/user_data.db)",
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    migrate_mobile_tables(args.db)
