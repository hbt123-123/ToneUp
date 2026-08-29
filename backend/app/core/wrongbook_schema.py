"""Wrong questions table DDL definition.

This module defines the SQL schema for the wrong_questions table,
which tracks user wrong answers across different question banks.
"""

WRONG_QUESTIONS_DDL = """
CREATE TABLE IF NOT EXISTS wrong_questions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL REFERENCES users(id),
    bank_id         TEXT    NOT NULL,
    question_id     INTEGER NOT NULL,
    attempt_count   INTEGER NOT NULL DEFAULT 1,
    last_wrong_at   TEXT    NOT NULL,
    tags            TEXT    DEFAULT '[]',
    created_at      TEXT    NOT NULL,
    UNIQUE(user_id, bank_id, question_id)
);
"""

WRONG_QUESTIONS_INDEXES = [
    "CREATE INDEX IF NOT EXISTS idx_wrong_questions_user_created ON wrong_questions(user_id, created_at)",
    "CREATE INDEX IF NOT EXISTS idx_wrong_questions_user_bank ON wrong_questions(user_id, bank_id)",
]
