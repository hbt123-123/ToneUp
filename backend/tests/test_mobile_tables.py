"""Tests for mobile quiz database tables and repositories.

Uses importlib to load scripts/init_user_db.py for base DDL, then runs
migrate_mobile_tables.py to add mobile tables. Each test gets a fresh DB.
"""

import importlib.util
import pathlib
import sqlite3

import pytest

from app.repositories import feedback_repo, favorites_repo, user_repo

_p = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "init_user_db.py"
_spec = importlib.util.spec_from_file_location("init_user_db", _p)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

init_user_db = _mod.init_user_db

_m_p = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "migrate_mobile_tables.py"
_m_spec = importlib.util.spec_from_file_location("migrate_mobile_tables", _m_p)
_m_mod = importlib.util.module_from_spec(_m_spec)
_m_spec.loader.exec_module(_m_mod)
migrate_mobile_tables = _m_mod.migrate_mobile_tables

NOW = "2026-08-24T12:00:00+00:00"


# ── fixtures ───────────────────────────────────────────────────────────
@pytest.fixture()
def db(tmp_path) -> str:
    db_path = str(tmp_path / "user_data.db")
    init_user_db(db_path)
    migrate_mobile_tables(db_path)
    return db_path


def _create_user(db_path: str, username: str = "alice") -> int:
    return user_repo.create_user(db_path, username, password_hash="hash-x")


# ── migration: table existence ─────────────────────────────────────────

def test_question_feedback_table_exists(db):
    conn = sqlite3.connect(db)
    try:
        row = conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='question_feedback'"
        ).fetchone()
        assert row is not None
    finally:
        conn.close()


def test_note_likes_table_exists(db):
    conn = sqlite3.connect(db)
    try:
        row = conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='note_likes'"
        ).fetchone()
        assert row is not None
    finally:
        conn.close()


def test_favorite_questions_table_exists(db):
    conn = sqlite3.connect(db)
    try:
        row = conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='favorite_questions'"
        ).fetchone()
        assert row is not None
    finally:
        conn.close()


def test_user_notes_has_visibility_column(db):
    conn = sqlite3.connect(db)
    try:
        row = conn.execute(
            "PRAGMA table_info(user_notes)"
        ).fetchall()
        cols = {r[1] for r in row}
        assert "visibility" in cols
        assert "like_count" in cols
    finally:
        conn.close()


def test_migration_idempotent(db):
    """Running migrate twice must not error."""
    migrate_mobile_tables(db)
    migrate_mobile_tables(db)
    conn = sqlite3.connect(db)
    try:
        count = conn.execute("SELECT COUNT(*) FROM question_feedback").fetchone()[0]
        assert count == 0
    finally:
        conn.close()


# ── question_feedback CRUD ─────────────────────────────────────────────

def test_create_feedback(db):
    uid = _create_user(db)
    fid = feedback_repo.create_feedback(
        db, uid, "bank-math", 7, "答案有误", "wrong answer", None, NOW
    )
    assert fid is not None and len(fid) > 0

    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    try:
        fb = conn.execute("SELECT * FROM question_feedback WHERE id = ?", (fid,)).fetchone()
        assert fb is not None
        assert fb["user_id"] == uid
        assert fb["category"] == "答案有误"
        assert fb["status"] == "待处理"
    finally:
        conn.close()


def test_list_feedback_filters(db):
    uid = _create_user(db)
    feedback_repo.create_feedback(db, uid, "bank-math", 7, "答案有误", "a", None, NOW)
    feedback_repo.create_feedback(db, uid, "bank-math", 8, "解析有误", "b", None, NOW)
    feedback_repo.create_feedback(db, uid, "bank-chinese", 7, "答案有误", "c", None, NOW)

    items, total = feedback_repo.list_feedback(db, bank_id="bank-math")
    assert total == 2
    assert len(items) == 2

    items2, total2 = feedback_repo.list_feedback(db, question_id=7)
    assert total2 == 2

    items3, total3 = feedback_repo.list_feedback(db, status="待处理")
    assert total3 == 3


def test_list_feedback_pagination(db):
    uid = _create_user(db)
    for i in range(5):
        feedback_repo.create_feedback(db, uid, "bank-math", 7, "答案有误", f"msg-{i}", None, NOW)

    items, total = feedback_repo.list_feedback(db, page=1, page_size=2)
    assert total == 5
    assert len(items) == 2

    items2, _ = feedback_repo.list_feedback(db, page=2, page_size=2)
    assert len(items2) == 2


def test_note_is_liked_by(db):
    uid = _create_user(db)
    user_repo.notes_upsert(db, uid, "bank-math", 7, "hello", NOW)
    row = user_repo.notes_get(db, uid, "bank-math", 7)
    note_id = row["id"] if row else None
    assert note_id is not None

    assert user_repo.note_is_liked_by(db, note_id, uid) is False

    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    try:
        with conn:
            conn.execute(
                "INSERT INTO note_likes (note_id, user_id, created_at) VALUES (?, ?, ?)",
                (note_id, uid, NOW),
            )
    finally:
        conn.close()

    assert user_repo.note_is_liked_by(db, note_id, uid) is True


def test_note_increment_decrement_likes(db):
    uid = _create_user(db)
    user_repo.notes_upsert(db, uid, "bank-math", 7, "hello", NOW)
    row = user_repo.notes_get(db, uid, "bank-math", 7)
    note_id = row["id"]

    user_repo.note_increment_likes(db, note_id)
    row = user_repo.notes_get(db, uid, "bank-math", 7)
    assert row["like_count"] == 1

    user_repo.note_increment_likes(db, note_id)
    row = user_repo.notes_get(db, uid, "bank-math", 7)
    assert row["like_count"] == 2

    user_repo.note_decrement_likes(db, note_id)
    row = user_repo.notes_get(db, uid, "bank-math", 7)
    assert row["like_count"] == 1

    user_repo.note_decrement_likes(db, note_id)
    row = user_repo.notes_get(db, uid, "bank-math", 7)
    assert row["like_count"] == 0

    user_repo.note_decrement_likes(db, note_id)
    row = user_repo.notes_get(db, uid, "bank-math", 7)
    assert row["like_count"] == 0

    user_repo.note_decrement_likes(db, note_id)
    row = user_repo.notes_get(db, uid, "bank-math", 7)
    assert row["like_count"] == 0


# ── notes_list_public / notes_list_mine ─────────────────────────────────

def test_notes_list_public(db):
    uid = _create_user(db)
    user_repo.notes_upsert(db, uid, "bank-math", 7, "public note", NOW)

    items, total = user_repo.notes_list_public(db, "bank-math", 7, page=1, page_size=20)
    assert total == 1
    assert len(items) == 1
    assert items[0]["note_text"] == "public note"


def test_notes_list_mine(db):
    uid = _create_user(db)
    user_repo.notes_upsert(db, uid, "bank-math", 7, "my note", NOW)

    row = user_repo.notes_list_mine(db, uid, "bank-math", 7)
    assert row is not None
    assert row["note_text"] == "my note"

    assert user_repo.notes_list_mine(db, uid, "bank-chinese", 7) is None


# ── favorites_repo ──────────────────────────────────────────────────────

def test_toggle_favorite(db):
    uid = _create_user(db)
    result = favorites_repo.toggle_favorite(db, uid, "bank-math", 7)
    assert result is True

    assert favorites_repo.is_favorited(db, uid, "bank-math", 7) is True

    result2 = favorites_repo.toggle_favorite(db, uid, "bank-math", 7)
    assert result2 is False
    assert favorites_repo.is_favorited(db, uid, "bank-math", 7) is False


def test_list_favorite_banks(db):
    uid = _create_user(db)
    favorites_repo.toggle_favorite(db, uid, "bank-math", 7)
    favorites_repo.toggle_favorite(db, uid, "bank-math", 8)
    favorites_repo.toggle_favorite(db, uid, "bank-chinese", 9)

    banks = favorites_repo.list_favorite_banks(db, uid)
    bank_ids = {b["bank_id"] for b in banks}
    assert "bank-math" in bank_ids
    assert "bank-chinese" in bank_ids

    math_bank = [b for b in banks if b["bank_id"] == "bank-math"][0]
    assert math_bank["favorite_count"] == 2
