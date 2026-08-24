"""用户库五表 DDL + WAL + 幂等索引初始化脚本。

D4 决策：users.role 默认 'user'；所有 DDL 使用 IF NOT EXISTS（幂等）。
Usage:
    python init_user_db.py [-db PATH]
    python -c "from init_user_db import init_user_db; init_user_db('path/to/db')"
"""

import argparse
import sqlite3


def init_user_db(db_path: str) -> None:
    """Initialize the user database with five tables, WAL mode, and idempotent indexes.

    Creates the following tables if they do not already exist (all DDL uses IF NOT EXISTS):
      - users
      - practice_records
      - user_mastery
      - user_notes
      - ai_feedback

    After connecting, sets PRAGMA journal_mode=WAL and PRAGMA busy_timeout=5000.

    Args:
        db_path: Path to the SQLite database file.
    """
    conn = sqlite3.connect(db_path)
    try:
        cursor = conn.cursor()

        # ── PRAGMAs ──────────────────────────────────────────────
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA busy_timeout=5000")

        # ── users ────────────────────────────────────────────────
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                username        TEXT    NOT NULL UNIQUE,
                password_hash   TEXT    NOT NULL,
                role            TEXT    NOT NULL DEFAULT 'user',
                created_at      TEXT    NOT NULL
            )
            """
        )

        # ── practice_records ─────────────────────────────────────
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS practice_records (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id             INTEGER NOT NULL REFERENCES users (id),
                bank_id             TEXT    NOT NULL,
                question_id         INTEGER NOT NULL,
                user_answer         TEXT    NOT NULL,
                is_correct          INTEGER,
                score               REAL,
                time_spent          INTEGER NOT NULL,
                mode                TEXT    NOT NULL,
                client_request_id   TEXT    NOT NULL,
                created_at          TEXT    NOT NULL
            )
            """
        )

        # ── user_mastery ─────────────────────────────────────────
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS user_mastery (
                user_id             INTEGER NOT NULL,
                bank_id             TEXT    NOT NULL,
                question_id         INTEGER NOT NULL,
                total_attempts      INTEGER NOT NULL DEFAULT 0,
                correct_attempts    INTEGER NOT NULL DEFAULT 0,
                last_practice_at    TEXT,
                confidence_level    INTEGER NOT NULL DEFAULT 0,
                next_review_at      TEXT,
                PRIMARY KEY (user_id, bank_id, question_id)
            )
            """
        )

        # ── user_notes ───────────────────────────────────────────
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS user_notes (
                user_id             INTEGER NOT NULL,
                bank_id             TEXT    NOT NULL,
                question_id         INTEGER NOT NULL,
                note_text           TEXT    NOT NULL,
                updated_at          TEXT    NOT NULL,
                PRIMARY KEY (user_id, bank_id, question_id)
            )
            """
        )

        # ── ai_feedback ──────────────────────────────────────────
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS ai_feedback (
                id                    TEXT    PRIMARY KEY,
                user_id               INTEGER NOT NULL,
                attempt_id            INTEGER,
                bank_id               TEXT    NOT NULL,
                question_id           INTEGER NOT NULL,
                status                TEXT    NOT NULL,
                image_path            TEXT,
                is_correct            INTEGER,
                error_reason          TEXT,
                tag_ids_json          TEXT,
                raw_response          TEXT,
                error_message         TEXT,
                created_at            TEXT    NOT NULL,
                completed_at          TEXT
            )
            """
        )

        # ── indexes ──────────────────────────────────────────────
        cursor.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_attempt_request ON practice_records (user_id, client_request_id)"
        )
        cursor.execute(
            "CREATE INDEX IF NOT EXISTS idx_practice_user_time ON practice_records (user_id, created_at)"
        )
        cursor.execute(
            "CREATE INDEX IF NOT EXISTS idx_practice_bank_question ON practice_records (bank_id, question_id)"
        )
        cursor.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_feedback_user ON ai_feedback (user_id, created_at)"
        )

        conn.commit()
    finally:
        conn.close()


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Initialize user database with five tables, WAL mode, and idempotent indexes"
    )
    parser.add_argument(
        "--db",
        default="../data/user_data.db",
        help="Path to SQLite database (default: ../data/user_data.db)",
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    init_user_db(args.db)