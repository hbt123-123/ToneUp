"""Tests for backend/scripts/init_user_db.py

D4 决策：users.role 默认 'user'；所有 DDL 使用 IF NOT EXISTS（幂等）。
使用 importlib 而非包导入，符合项目规范。
"""

import importlib.util
import pathlib
import sqlite3
import sys

# ── import the script via importlib (not package import) ───────────────────
_p = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "init_user_db.py"
_spec = importlib.util.spec_from_file_location("init_user_db", _p)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

init_user_db = _mod.init_user_db


# ── fixture: temporary database path ─────────────────────────────────────────
def _tmp_db_path(tmp_path) -> str:
    """Return a temporary .db path inside the test workspace."""
    return str(tmp_path / "user_data.db")


# ── Test 1: idempotent – running twice raises no error ─────────────────────
def test_init_idempotent_when_run_twice(tmp_path):
    """init_user_db 连跑两次不抛错（幂等）"""
    db = _tmp_db_path(tmp_path)
    init_user_db(db)      # 第一次
    init_user_db(db)      # 第二次，应无异常


# ── Test 2: tables exist in sqlite_master and are the correct set ────────────
def test_init_creates_five_tables_in_master(tmp_path):
    """sqlite_master 含 users, practice_records, user_mastery, user_notes, ai_feedback 且恰为这五张表"""
    db = _tmp_db_path(tmp_path)
    init_user_db(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name != 'sqlite_sequence' ORDER BY name"
        )
        tables = {row[0] for row in cur.fetchall()}
        expected = {
            "users",
            "practice_records",
            "user_mastery",
            "user_notes",
            "ai_feedback",
            "wrong_questions",
            "favorite_questions",
            "question_feedback",
            "note_likes",
            "practice_session",
            "practice_session_item",
        }
        assert tables == expected, f"Expected table set {expected}, got {tables}"
    finally:
        conn.close()


# ── Test 3: PRAGMA journal_mode returns 'wal' ───────────────────────────────
def test_journal_mode_is_wal(tmp_path):
    """PRAGMA journal_mode 返回 'wal'"""
    db = _tmp_db_path(tmp_path)
    init_user_db(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        cur.execute("PRAGMA journal_mode")
        mode = cur.fetchone()[0]
        assert mode == "wal", f"Expected journal_mode='wal', got '{mode}'"
    finally:
        conn.close()


# ── Test 4: users username UNIQUE constraint ─────────────────────────────────
def test_users_username_unique_when_duplicate_insert(tmp_path):
    """users 插入同 username 两行抛 IntegrityError"""
    db = _tmp_db_path(tmp_path)
    init_user_db(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        # First insert should succeed
        cur.execute(
            "INSERT INTO users (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
            ("testuser", "hash123", "user", "2026-01-01"),
        )
        conn.commit()

        # Second insert with same username should raise IntegrityError
        try:
            cur.execute(
                "INSERT INTO users (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
                ("testuser", "hash456", "user", "2026-01-02"),
            )
            conn.commit()
            assert False, "Expected sqlite3.IntegrityError on duplicate username"
        except sqlite3.IntegrityError:
            # expected
            conn.rollback()
    finally:
        conn.close()


# ── Test 5: practice_records uq_attempt_request constraint ───────────────────
def test_practice_records_unique_request_when_duplicate_insert(tmp_path):
    """practice_records 同 (user_id, client_request_id) 二次插入抛 IntegrityError（uq_attempt_request 生效）"""
    db = _tmp_db_path(tmp_path)
    init_user_db(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        # Insert a user first (needed for FK)
        cur.execute(
            "INSERT INTO users (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
            ("testuser", "hash123", "user", "2026-01-01"),
        )
        conn.commit()
        user_id = cur.lastrowid

        # Insert practice_records with same client_request_id should raise IntegrityError
        cur.execute(
            "INSERT INTO practice_records (user_id, bank_id, question_id, user_answer, is_correct, score, time_spent, mode, client_request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (user_id, "bank1", 1, "answer", 1, 10.0, 30, "practice", "req1", "2026-01-01"),
        )
        conn.commit()

        # Second insert with same client_request_id should raise IntegrityError
        try:
            cur.execute(
                "INSERT INTO practice_records (user_id, bank_id, question_id, user_answer, is_correct, score, time_spent, mode, client_request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (user_id, "bank1", 2, "answer2", 0, 5.0, 15, "practice", "req1", "2026-01-02"),
            )
            conn.commit()
            assert False, "Expected sqlite3.IntegrityError on duplicate uq_attempt_request"
        except sqlite3.IntegrityError:
            # expected
            conn.rollback()
    finally:
        conn.close()


# ── Test 6: role defaults to 'user' when not passed ─────────────────────────
def test_role_defaults_to_user_when_not_passed(tmp_path):
    """不传 role 时，users 表插入的行 role 默认 'user'"""
    db = _tmp_db_path(tmp_path)
    init_user_db(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        cur.execute(
            "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
            ("testuser_norole", "hash123", "2026-01-01"),
        )
        conn.commit()

        cur.execute(
            "SELECT role FROM users WHERE username = ?",
            ("testuser_norole",),
        )
        row = cur.fetchone()
        assert row is not None, "row should exist"
        assert row[0] == "user", f"Expected role='user', got '{row[0]}'"
    finally:
        conn.close()


# ── Test 7: indexes exist in sqlite_master ──────────────────────────────────
def test_indexes_exist_in_sqlite_master(tmp_path):
    """sqlite_master 中存在名为 uq_attempt_request 的 index 且含 idx_ai_feedback_user"""
    db = _tmp_db_path(tmp_path)
    init_user_db(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        # Check uq_attempt_request index exists
        cur.execute(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='uq_attempt_request'"
        )
        idx_name = cur.fetchone()
        assert idx_name is not None, "Index uq_attempt_request should exist in sqlite_master"

        # Check idx_ai_feedback_user index exists
        cur.execute(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_ai_feedback_user'"
        )
        idx_ai = cur.fetchone()
        assert idx_ai is not None, "Index idx_ai_feedback_user should exist in sqlite_master"
    finally:
        conn.close()


# ── Test 8: practice_session tables and unique index exist ──────────────────
def test_practice_session_tables_and_unique_index_exist(tmp_path):
    """sqlite_master 含 practice_session 两表与 uq_practice_session_request 唯一索引"""
    db = _tmp_db_path(tmp_path)
    init_user_db(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('practice_session','practice_session_item')"
        )
        tables = {row[0] for row in cur.fetchall()}
        assert tables == {"practice_session", "practice_session_item"}, (
            f"Expected practice_session tables, got {tables}"
        )

        cur.execute(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='uq_practice_session_request'"
        )
        assert cur.fetchone() is not None, (
            "Index uq_practice_session_request should exist in sqlite_master"
        )
    finally:
        conn.close()


# ── Test 9: practice_session (user_id, client_request_id) uniqueness ────────
def test_practice_session_unique_request_when_duplicate_insert(tmp_path):
    """practice_session 插入同 (user_id, client_request_id) 两行抛 IntegrityError"""
    db = _tmp_db_path(tmp_path)
    init_user_db(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        cur.execute(
            "INSERT INTO practice_session (user_id, bank_id, client_request_id) VALUES (?, ?, ?)",
            (1, "bank1", "req1"),
        )
        conn.commit()

        try:
            cur.execute(
                "INSERT INTO practice_session (user_id, bank_id, client_request_id) VALUES (?, ?, ?)",
                (1, "bank1", "req1"),
            )
            conn.commit()
            assert False, "Expected sqlite3.IntegrityError on duplicate (user_id, client_request_id)"
        except sqlite3.IntegrityError:
            conn.rollback()
    finally:
        conn.close()


# ── Test 10: practice_session NULL client_request_id is allowed repeatedly ──
def test_practice_session_null_request_id_allows_multiple_rows(tmp_path):
    """活跃会话 client_request_id=NULL 互异：同一 user 可插入多行 NULL（唯一索引不受影响）"""
    db = _tmp_db_path(tmp_path)
    init_user_db(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        cur.execute(
            "INSERT INTO practice_session (user_id, bank_id) VALUES (?, ?)", (1, "bank1")
        )
        cur.execute(
            "INSERT INTO practice_session (user_id, bank_id) VALUES (?, ?)", (1, "bank1")
        )
        conn.commit()

        cur.execute("SELECT COUNT(*) FROM practice_session WHERE user_id = 1")
        assert cur.fetchone()[0] == 2, "Two rows with NULL client_request_id should coexist"
    finally:
        conn.close()


# ── Test 11: practice_session_item (session_id, position) uniqueness ────────
def test_practice_session_item_unique_position(tmp_path):
    """practice_session_item 同会话同 position 二次插入抛 IntegrityError"""
    db = _tmp_db_path(tmp_path)
    init_user_db(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        cur.execute(
            "INSERT INTO practice_session (user_id, bank_id) VALUES (?, ?)", (1, "bank1")
        )
        session_id = cur.lastrowid

        cur.execute(
            "INSERT INTO practice_session_item (session_id, position, question_id) VALUES (?, ?, ?)",
            (session_id, 0, 101),
        )
        conn.commit()

        try:
            cur.execute(
                "INSERT INTO practice_session_item (session_id, position, question_id) VALUES (?, ?, ?)",
                (session_id, 0, 102),
            )
            conn.commit()
            assert False, "Expected sqlite3.IntegrityError on duplicate (session_id, position)"
        except sqlite3.IntegrityError:
            conn.rollback()
    finally:
        conn.close()