"""练习会话两表迁移脚本 — 幂等执行（EC-01）。

新增表与索引：
- practice_session
- practice_session_item
- uq_practice_session_request / idx_practice_session_user

本脚本自包含（DDL 内联，不依赖 app 包导入），可在生产服务器上直接执行。

Usage:
    python migrate_practice_sessions.py [-db PATH]
"""

import argparse
import sqlite3
from pathlib import Path


PRACTICE_SESSION_DDL = """
CREATE TABLE IF NOT EXISTS practice_session (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id           INTEGER NOT NULL,
    bank_id           TEXT    NOT NULL,
    title             TEXT    NOT NULL DEFAULT '',
    status            TEXT    NOT NULL DEFAULT 'active',
    total_count       INTEGER NOT NULL DEFAULT 0,
    current_index     INTEGER NOT NULL DEFAULT 0,
    draft_json        TEXT    NOT NULL DEFAULT '{}',
    elapsed_seconds   INTEGER NOT NULL DEFAULT 0,
    client_request_id TEXT,
    created_at        TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at        TEXT    NOT NULL DEFAULT (datetime('now'))
);
"""

PRACTICE_SESSION_ITEM_DDL = """
CREATE TABLE IF NOT EXISTS practice_session_item (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  INTEGER NOT NULL REFERENCES practice_session(id) ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    question_id INTEGER NOT NULL,
    UNIQUE(session_id, position)
);
"""

PRACTICE_SESSION_INDEXES = [
    # M-347：改部分索引——client_request_id 为 NULL 的行不参与唯一约束，
    # 语义与「幂等键仅对显式请求生效」一致；先删旧的全量同名索引，
    # 否则 IF NOT EXISTS 不会替换既有索引、部分索引不会生效
    "DROP INDEX IF EXISTS uq_practice_session_request",
    """CREATE UNIQUE INDEX IF NOT EXISTS uq_practice_session_request
       ON practice_session(user_id, client_request_id) WHERE client_request_id IS NOT NULL""",
    "CREATE INDEX IF NOT EXISTS idx_practice_session_user ON practice_session(user_id, created_at DESC)",
]


def migrate_practice_sessions(db_path: str) -> None:
    """幂等迁移：创建练习会话两表与索引，重复运行不报错。"""
    conn = sqlite3.connect(db_path)
    try:
        cursor = conn.cursor()
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA busy_timeout=5000")
        # M-348：ON DELETE CASCADE 依赖连接层外键开关，迁移期一并开启保持一致
        cursor.execute("PRAGMA foreign_keys=ON")

        cursor.execute(PRACTICE_SESSION_DDL)
        cursor.execute(PRACTICE_SESSION_ITEM_DDL)
        for idx_sql in PRACTICE_SESSION_INDEXES:
            cursor.execute(idx_sql)

        conn.commit()
    finally:
        conn.close()


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Migrate practice session tables: practice_session, practice_session_item"
    )
    parser.add_argument(
        "--db",
        # M-349：默认路径基于脚本位置解析，不依赖 CWD
        default=str(Path(__file__).resolve().parents[1] / "data" / "user_data.db"),
        help="Path to SQLite database (default: <backend>/data/user_data.db)",
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    migrate_practice_sessions(args.db)
