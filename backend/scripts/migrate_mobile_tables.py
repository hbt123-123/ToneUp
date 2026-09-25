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

# M-346：init_user_db.py 的完整 user_notes schema（重建依据）
_USER_NOTES_DDL = """
CREATE TABLE user_notes_new (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL,
    bank_id     TEXT    NOT NULL,
    question_id INTEGER NOT NULL,
    note_text   TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL,
    visibility  TEXT    NOT NULL DEFAULT 'public' CHECK(visibility IN ('private','public')),
    like_count  INTEGER NOT NULL DEFAULT 0,
    UNIQUE (user_id, bank_id, question_id)
)
"""


def _needs_user_notes_rebuild(conn: sqlite3.Connection) -> bool:
    """M-346：user_notes.id 缺失或非 INTEGER PRIMARY KEY 时需整表重建。

    旧 schema 为复合主键、无 id 列；先前用 ALTER ADD COLUMN id INTEGER
    补的列不会自增——新插入行 id 恒为 NULL，note_id 全链路失效
    （notes_upsert/GET/PUT/DELETE 均按 id 定位），必须重建。
    """
    cols = conn.execute("PRAGMA table_info(user_notes)").fetchall()
    if not cols:
        return False  # 表不存在：由 init_user_db 正常建表
    id_col = next((c for c in cols if c[1] == "id"), None)
    if id_col is None:
        return True
    # PRAGMA table_info: (cid, name, type, notnull, dflt_value, pk)
    return not (str(id_col[2]).upper().startswith("INTEGER") and id_col[5] == 1)


def _rebuild_user_notes(conn: sqlite3.Connection) -> None:
    """M-346：重建 user_notes 为带自增主键的完整 schema。

    动态列交集拷贝（id 列除外——旧值不可靠，交给 AUTOINCREMENT 重分配），
    全程单事务，失败回滚不留半成品。
    """
    old_cols = [r[1] for r in conn.execute("PRAGMA table_info(user_notes)").fetchall()]
    new_cols = [
        "user_id", "bank_id", "question_id", "note_text",
        "updated_at", "visibility", "like_count",
    ]
    common = [c for c in new_cols if c in old_cols]
    cols_csv = ", ".join(common)
    conn.execute("DROP TABLE IF EXISTS user_notes_new")
    conn.execute(_USER_NOTES_DDL)
    if common:
        conn.execute(
            f"INSERT INTO user_notes_new ({cols_csv}) SELECT {cols_csv} FROM user_notes"
        )
    conn.execute("DROP TABLE user_notes")
    conn.execute("ALTER TABLE user_notes_new RENAME TO user_notes")


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
        # M-346：id 列缺失或非 INTEGER PRIMARY KEY 时整表重建；
        # 仅当表结构已正确时才走 ALTER 补列的轻量路径
        if _needs_user_notes_rebuild(conn):
            _rebuild_user_notes(conn)
        else:
            for column_def in [
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
            # 回填历史遗留的 NULL id（仅防御中间态库）
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
        # M-346：默认路径基于脚本位置解析，不依赖 CWD
        default=str(Path(__file__).resolve().parents[1] / "data" / "user_data.db"),
        help="Path to SQLite database (default: <backend>/data/user_data.db)",
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    migrate_mobile_tables(args.db)
