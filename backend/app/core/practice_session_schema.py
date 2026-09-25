"""Practice session tables DDL definition.

This module defines the SQL schema for the practice_session and
practice_session_item tables, which provide server-side practice
sessions with cross-device resume support (EC-01).

- practice_session: 会话主表。status='active'|'submitted'；draft_json 存
  未提交作答草稿（last-write-wins，无冲突合并）；client_request_id 在
  submit 时写入，配合唯一索引实现幂等交卷。
- practice_session_item: 会话题目序列。answered 一律由 practice_records
  实时统计，本表不冗余该列。
"""

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

# M-301：上方的 REFERENCES ... ON DELETE CASCADE 仅在连接开启
# PRAGMA foreign_keys=ON 时生效（SQLite 默认 OFF）。统一在
# user_repo.user_connection 建连时开启；其它直接 sqlite3.connect
# 的只读/旁路连接不享受级联，属已知约束。

PRACTICE_SESSION_INDEXES = [
    # SQLite 中 NULL 互异：活跃会话 client_request_id=NULL 不受唯一约束影响；
    # 同一 user 的同一 client_request_id 只能绑定一个会话（幂等交卷依据）。
    "CREATE UNIQUE INDEX IF NOT EXISTS uq_practice_session_request ON practice_session(user_id, client_request_id)",
    "CREATE INDEX IF NOT EXISTS idx_practice_session_user ON practice_session(user_id, created_at DESC)",
]
