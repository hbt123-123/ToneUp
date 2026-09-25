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

# M-299：UNIQUE(user_id, bank_id, question_id) 已隐式创建等价复合索引，
# 以下两个单列/前缀索引（最左前缀子集）纯冗余；改为 DROP 清理历史库中
# 已创建的冗余索引（init_user_db.py 对每条语句直接 execute，DROP 同样适用）
FAVORITE_QUESTIONS_INDEXES = [
    "DROP INDEX IF EXISTS idx_favorite_questions_user",
    "DROP INDEX IF EXISTS idx_favorite_questions_user_bank",
]
