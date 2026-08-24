"""Tests for backend/scripts/init_knowledge_tags.py

D5 决策：首次建库即空表，不插入示例数据。
使用 importlib 而非包导入，符合项目规范。
"""

import importlib.util
import pathlib
import sqlite3
import sys

# ── import the script via importlib (not package import) ───────────────────
_p = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "init_knowledge_tags.py"
_spec = importlib.util.spec_from_file_location("init_knowledge_tags", _p)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

init_knowledge_tags = _mod.init_knowledge_tags


# ── fixture: temporary database path ─────────────────────────────────────────
def _tmp_db_path(tmp_path) -> str:
    """Return a temporary .db path inside the test workspace."""
    return str(tmp_path / "knowledge_tags.db")


# ── Test 1: idempotent – running twice raises no error ─────────────────────
def test_init_idempotent_when_run_twice(tmp_path):
    """init_knowledge_tags 连跑两次不抛错（幂等）"""
    db = _tmp_db_path(tmp_path)
    init_knowledge_tags(db)      # 第一次
    init_knowledge_tags(db)      # 第二次，应无异常


# ── Test 2: tables exist in sqlite_master and are empty ─────────────────────
def test_init_creates_empty_tables_in_master(tmp_path):
    """sqlite_master 含 tags 与 question_tags 且两表行数均为 0（D5 空表交付）"""
    db = _tmp_db_path(tmp_path)
    init_knowledge_tags(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        )
        tables = {row[0] for row in cur.fetchall()}
        assert "tags" in tables, "tags table missing from sqlite_master"
        assert "question_tags" in tables, "question_tags table missing from sqlite_master"

        cur.execute("SELECT COUNT(*) FROM tags")
        assert cur.fetchone()[0] == 0, "tags table should be empty (D5)"

        cur.execute("SELECT COUNT(*) FROM question_tags")
        assert cur.fetchone()[0] == 0, "question_tags table should be empty (D5)"
    finally:
        conn.close()


# ── Test 3: UNIQUE constraint on tags(subject, parent_id, tag_name) ─────────
def test_tags_unique_constraint_when_duplicate_insert(tmp_path):
    """tags 插入 ('math',0,'极限计算') 成功后同三元组再插抛 sqlite3.IntegrityError"""
    db = _tmp_db_path(tmp_path)
    init_knowledge_tags(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        # First insert should succeed
        cur.execute(
            "INSERT INTO tags (subject, parent_id, tag_name) VALUES (?, ?, ?)",
            ("math", 0, "极限计算"),
        )
        conn.commit()

        # Second insert of the same tuple should raise IntegrityError
        # SQLite treats NULL distinctly in UNIQUE, so we use 0 (non-NULL) to properly test the constraint
        try:
            cur.execute(
                "INSERT INTO tags (subject, parent_id, tag_name) VALUES (?, ?, ?)",
                ("math", 0, "极限计算"),
            )
            conn.commit()
            assert False, "Expected sqlite3.IntegrityError on duplicate UNIQUE"
        except sqlite3.IntegrityError:
            # expected
            conn.rollback()
    finally:
        conn.close()


# ── Test 4: PRIMARY KEY constraint on question_tags ────────────────────────
def test_question_tags_pk_constraint_when_duplicate_insert(tmp_path):
    """question_tags 同主键 (bank_id,question_id,tag_id) 二次插入抛 IntegrityError"""
    db = _tmp_db_path(tmp_path)
    init_knowledge_tags(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        # Insert a valid row
        cur.execute(
            "INSERT INTO question_tags (bank_id, question_id, tag_id, source, created_at) VALUES (?, ?, ?, ?, ?)",
            ("bank1", 1, 1, "manual", "2026-01-01"),
        )
        conn.commit()

        # Second insert with same primary key should raise IntegrityError
        try:
            cur.execute(
                "INSERT INTO question_tags (bank_id, question_id, tag_id, source, created_at) VALUES (?, ?, ?, ?, ?)",
                ("bank1", 1, 1, "manual", "2026-01-02"),
            )
            conn.commit()
            assert False, "Expected sqlite3.IntegrityError on duplicate PK"
        except sqlite3.IntegrityError:
            # expected
            conn.rollback()
    finally:
        conn.close()


# ── Test 5: source defaults to 'manual' when not provided ───────────────────
def test_source_defaults_to_manual_when_not_passed(tmp_path):
    """不传 source 时查询默认 'manual'"""
    db = _tmp_db_path(tmp_path)
    init_knowledge_tags(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        # Insert a tag first (needed for FK reference, though tags.parent_id is self-referential)
        cur.execute(
            "INSERT INTO tags (subject, parent_id, tag_name) VALUES (?, ?, ?)",
            ("math", None, "微积分"),
        )
        conn.commit()

        # Insert question_tags without specifying source
        cur.execute(
            "INSERT INTO question_tags (bank_id, question_id, tag_id, created_at) VALUES (?, ?, ?, ?)",
            ("bank1", 1, 1, "2026-01-01"),
        )
        conn.commit()

        # Query back and verify source defaults to 'manual'
        cur.execute(
            "SELECT source FROM question_tags WHERE bank_id = ? AND question_id = ?",
            ("bank1", 1),
        )
        row = cur.fetchone()
        assert row is not None, "row should exist"
        assert row[0] == "manual", f"Expected source='manual', got '{row[0]}'"
    finally:
        conn.close()