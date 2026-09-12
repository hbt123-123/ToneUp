"""Favorite questions table DDL definition."""

FAVORITE_QUESTIONS_DDL = """
CREATE TABLE IF NOT EXISTS favorite_questions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL REFERENCES users(id),
    bank_id         TEXT    NOT NULL,
    question_id     INTEGER NOT NULL,
    created_at      TEXT    NOT NULL,
    UNIQUE(user_id, bank_id, question_id)
);
"""

FAVORITE_QUESTIONS_INDEXES = [
    "CREATE INDEX IF NOT EXISTS idx_favorite_questions_user ON favorite_questions(user_id)",
    "CREATE INDEX IF NOT EXISTS idx_favorite_questions_user_bank ON favorite_questions(user_id, bank_id)",
]
