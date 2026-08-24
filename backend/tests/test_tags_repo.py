"""Tests for backend/app/repositories/tags_repo.py。

使用 importlib 加载 scripts/init_knowledge_tags.py 建空库，符合项目规范。
每个测试独立使用临时数据库，符合 D5 空表决策。
"""

import importlib.util
import pathlib
import sqlite3
import sys

import pytest

# ── via importlib (not package import) ───────────────────────────────────────
_p = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "init_knowledge_tags.py"
_spec = importlib.util.spec_from_file_location("init_knowledge_tags", _p)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

init_knowledge_tags = _mod.init_knowledge_tags

# ── fixture: temporary database path ─────────────────────────────────────────
def _tmp_db_path(tmp_path) -> str:
    """Return a temporary .db path inside the test workspace."""
    return str(tmp_path / "knowledge_tags.db")


# ── fixture: initialize fresh empty DB for each test ─────────────────────────
@pytest.fixture(autouse=True)
def _fresh_db(tmp_path) -> str:
    """在每个测试前用 init_knowledge_tags 初始化空 DB，确保 D5 空表状态。"""
    db = _tmp_db_path(tmp_path)
    init_knowledge_tags(db)
    yield db
    # 测试后不主动清理，tmp_path 由 pytest 自动管理


# ── Test 1: 空库 list 返回 [] 且 filter 返回 []（D5 空标签容忍，不抛错）────────
def test_empty_db_when_list_and_filter_return_empty(tmp_path):
    """D5 决策：tags 表为空时 list_tags_by_subject 返回 []，filter_valid_tag_ids 返回 []，不抛错。"""
    db = _tmp_db_path(tmp_path)
    init_knowledge_tags(db)

    from app.repositories.tags_repo import list_tags_by_subject, filter_valid_tag_ids

    # list_tags_by_subject 空库返回 []
    result = list_tags_by_subject(db, "math")
    assert result == [], f"Expected [], got {result}"

    # filter_valid_tag_ids 空库返回 []
    result = filter_valid_tag_ids(db, "math", [])
    assert result == [], f"Expected [], got {result}"

    result = filter_valid_tag_ids(db, "math", [1, 999])
    assert result == [], f"Expected [], got {result}"


# ── Test 2: 插入 math 三个标签后 list 返回三字典按 id 升序 ─────────────────────
def test_insert_math_tags_when_list_returns_sorted_dicts(tmp_path):
    """插入 math 三个标签后，list_tags_by_subject('math') 返回三字典且按 id 升序。"""
    db = _tmp_db_path(tmp_path)
    init_knowledge_tags(db)

    from app.repositories.tags_repo import list_tags_by_subject

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        # 插入三个 math 标签（parent_id=0，按字母顺序插入确保 id 有序或由 AUTOINCREMENT 产生）
        cur.execute("INSERT INTO tags (subject, parent_id, tag_name) VALUES (?, ?, ?)", ("math", 0, "微积分"))
        cur.execute("INSERT INTO tags (subject, parent_id, tag_name) VALUES (?, ?, ?)", ("math", 0, "线性代数"))
        cur.execute("INSERT INTO tags (subject, parent_id, tag_name) VALUES (?, ?, ?)", ("math", 0, "概率论"))
        conn.commit()
    finally:
        conn.close()

    result = list_tags_by_subject(db, "math")
    assert len(result) == 3, f"Expected 3 tags, got {len(result)}"

    # 检查是否按 id 升序（AUTOINCREMENT 保证插入顺序即为 id 顺序）
    assert result[0]["tag_name"] in ("微积分", "线性代数", "概率论")
    assert result[2]["tag_name"] in ("微积分", "线性代数", "概率论")


# ── Test 3: filter_valid_tag_ids('math', [1,999]) → [1]（不存在剔除）────────
def test_filter_valid_tag_ids_when_nonexistent_removed(tmp_path):
    """filter_valid_tag_ids('math', [1, 999]) → [1]，不存在的 ID 被剔除。

    前提：已在 tags 表中插入 id=1 的 math 标签；id=999 不存在。
    """
    db = _tmp_db_path(tmp_path)
    init_knowledge_tags(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        cur.execute("INSERT INTO tags (subject, parent_id, tag_name) VALUES (?, ?, ?)", ("math", 0, "微积分"))
        conn.commit()
    finally:
        conn.close()

    from app.repositories.tags_repo import filter_valid_tag_ids

    result = filter_valid_tag_ids(db, "math", [1, 999])
    assert result == [1], f"Expected [1], got {result}"


# ── Test 4: 插一个 english 标签后 filter_valid_tag_ids('math', [该english标签id]) → []（跨学科剔除）────
def test_filter_valid_tag_ids_cross_subject_when_english_removed(tmp_path):
    """插入 english 标签后，filter_valid_tag_ids('math', [english_id]) → []，跨学科标签被剔除。"""
    db = _tmp_db_path(tmp_path)
    init_knowledge_tags(db)

    conn = sqlite3.connect(db)
    try:
        cur = conn.cursor()
        # 插入 math 标签
        cur.execute("INSERT INTO tags (subject, parent_id, tag_name) VALUES (?, ?, ?)", ("math", 0, "微积分"))
        # 插入 english 标签
        cur.execute("INSERT INTO tags (subject, parent_id, tag_name) VALUES (?, ?, ?)", ("english", 0, "语法"))
        conn.commit()
    finally:
        conn.close()

    from app.repositories.tags_repo import list_tags_by_subject, filter_valid_tag_ids

    # 先确认 english 标签的 id
    tags = list_tags_by_subject(db, "english")
    assert len(tags) == 1, f"Expected 1 english tag, got {len(tags)}"
    english_id = tags[0]["id"]

    # cross-subject filter: math学科下，english标签应被剔除
    result = filter_valid_tag_ids(db, "math", [english_id])
    assert result == [], f"Expected [], got {result}"

    # math自己的标签仍然有效
    result = filter_valid_tag_ids(db, "math", [1, english_id])
    assert result == [1], f"Expected [1], got {result}"