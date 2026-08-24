"""bank_registry 测试：真实四库副本上验证五规则矩阵、resolve 复检与 CLI。

所有测试只操作 tmp_path 中的副本，绝不改动 backend/data 原库。
夹具自包含于本文件（不依赖 conftest.py，避免并行冲突）。
"""
from __future__ import annotations

import json
import shutil
import sqlite3
import subprocess
import sys
from dataclasses import replace
from pathlib import Path
from typing import Callable

import pytest

from app.core.bank_registry import (
    QUESTION_TYPE_MAPPING,
    VALID_TYPE_CODES,
    BankRegistry,
    get_registry,
    reset_registry,
)

BACKEND_DIR = Path(__file__).resolve().parents[1]
REAL_DATA_ROOT = BACKEND_DIR / "data"
CLI_SCRIPT = BACKEND_DIR / "scripts" / "validate_banks.py"
ALL_BANK_IDS = {"math1", "math2", "english1", "english2"}


# ----------------------------------------------------------------------
# 夹具与变体构造器（全部作用于副本）
# ----------------------------------------------------------------------
def make_data_root(tmp_path: Path) -> Path:
    """把真实数据根完整复制到 tmp_path/data，返回副本根。"""
    root = tmp_path / "data"
    shutil.copytree(REAL_DATA_ROOT, root)
    return root


def _rewrite_manifest(root: Path, mutate: Callable[[dict], None]) -> None:
    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    mutate(manifest)
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def mut_empty_db(root: Path) -> str:
    """变体 a：math1 替换为无 questions 表的空 sqlite。"""
    db = root / "math" / "math1.db"
    db.unlink()
    con = sqlite3.connect(db)
    try:
        con.execute("CREATE TABLE dummy (x INTEGER)")
        con.commit()
    finally:
        con.close()
    return "math1"


def mut_bad_options_json(root: Path) -> str:
    """变体 b：math1 首行 options 置为非法 JSON。"""
    con = sqlite3.connect(root / "math" / "math1.db")
    try:
        con.execute(
            "UPDATE questions SET options='not-json' "
            "WHERE id=(SELECT MIN(id) FROM questions)")
        con.commit()
    finally:
        con.close()
    return "math1"


def mut_escape_path(root: Path) -> str:
    """变体 c：math1 path 指向 data_root 之外。"""
    _rewrite_manifest(
        root, lambda m: m["banks"][0].__setitem__("path", "../escape.db"))
    return "math1"


def mut_empty_mime(root: Path) -> str:
    """变体 d：english1 首行 images.mime 置空。"""
    con = sqlite3.connect(root / "english" / "english1.db")
    try:
        con.execute(
            "UPDATE images SET mime='' WHERE id=(SELECT MIN(id) FROM images)")
        con.commit()
    finally:
        con.close()
    return "english1"


def mut_duplicate_id(root: Path) -> str:
    """变体 e：追加一条与 math2 同 id 的条目（后者应被禁用）。"""
    def _dup(m: dict) -> None:
        clone = dict(m["banks"][1])
        clone["name"] = "重复的 math2"
        m["banks"].append(clone)
    _rewrite_manifest(root, _dup)
    return "math2"


def mut_unknown_subject(root: Path) -> str:
    """变体 f：math1 的 subject_id 指向不存在的科目。"""
    _rewrite_manifest(
        root, lambda m: m["banks"][0].__setitem__("subject_id", "politics"))
    return "math1"


# ----------------------------------------------------------------------
# 测试
# ----------------------------------------------------------------------
class TestPristineLoad:
    def test_four_banks_all_enabled_no_warnings(self, tmp_path):
        """Given 原样四库副本 When load Then 4 条全启用且 warnings 为空。"""
        reg = BankRegistry()
        warnings = reg.load(make_data_root(tmp_path))

        assert warnings == []
        assert set(reg.entries) == ALL_BANK_IDS
        assert all(e.enabled for e in reg.entries.values())
        assert len(reg.subjects_raw) == 2


class TestBadVariantMatrix:
    @pytest.mark.parametrize("mutator", [
        mut_empty_db,
        mut_bad_options_json,
        mut_escape_path,
        mut_empty_mime,
        mut_duplicate_id,
        mut_unknown_subject,
    ], ids=["empty-db", "bad-options-json", "escape-path",
            "empty-mime", "duplicate-id", "unknown-subject"])
    def test_single_variant_disables_only_target(self, tmp_path, mutator):
        """Given 单一坏变体 When load Then 仅目标条目禁用且 warning 含其 id。"""
        root = make_data_root(tmp_path)
        target = mutator(root)

        reg = BankRegistry()
        warnings = reg.load(root)

        assert any(target in w for w in warnings), warnings
        disabled = {bid for bid, e in reg.entries.items() if not e.enabled}
        if mutator is mut_duplicate_id:
            # 后者未入库：原 math2 保持启用，仅告警
            assert disabled == set()
        else:
            assert disabled == {target}
        assert ALL_BANK_IDS <= set(reg.entries)


class TestResolve:
    def test_resolve_returns_absolute_existing_path(self, tmp_path):
        """Given 正常加载 When resolve Then 返回 data_root 内的绝对路径。"""
        reg = BankRegistry()
        root = make_data_root(tmp_path)
        reg.load(root)

        resolved = reg.resolve("math1")

        assert resolved is not None
        assert resolved.is_absolute()
        assert resolved == (root / "math" / "math1.db").resolve()

    def test_resolve_rejects_tampered_escape_path(self, tmp_path):
        """Given 内部 path 被篡改为逃逸路径 When resolve Then 返回 None。"""
        reg = BankRegistry()
        root = make_data_root(tmp_path)
        reg.load(root)
        escaped = replace(reg.entries["math1"], path=root.parent / "evil.db")
        reg.entries["math1"] = escaped

        assert reg.resolve("math1") is None


class TestGet:
    def test_get_returns_none_for_missing_and_disabled(self, tmp_path):
        """Given 不存在/已禁用条目 When get Then 均返回 None。"""
        reg = BankRegistry()
        reg.load(make_data_root(tmp_path))
        reg.entries["math1"] = replace(reg.entries["math1"], enabled=False)

        assert reg.get("math1") is None
        assert reg.get("no-such-bank") is None
        assert reg.get("math2") is not None


class TestTypeMapping:
    def test_mapping_tables_and_valid_codes(self):
        """Given 题型映射配置 Then math/english 映射正确且 MULTI/JUDGE 仅枚举。"""
        assert QUESTION_TYPE_MAPPING["math"] == {
            1: "SINGLE", 2: "FILL_BLANK", 3: "SOLUTION"}
        assert QUESTION_TYPE_MAPPING["english"] == {
            1: "CLOZE", 2: "READING", 3: "ORDERING", 4: "TRANSLATION", 5: "ESSAY"}
        assert {"MULTI", "JUDGE"} <= VALID_TYPE_CODES
        for mapping in QUESTION_TYPE_MAPPING.values():
            assert set(mapping.values()) <= VALID_TYPE_CODES


class TestSingleton:
    def test_reset_then_get_returns_fresh_instance(self, tmp_path, monkeypatch):
        """Given reset 后 When get_registry Then 重建实例并按注入根加载。"""
        from app.core import bank_registry as br

        root = make_data_root(tmp_path)
        monkeypatch.setattr(
            br, "get_settings", lambda: type("S", (), {"data_root": root})())
        reset_registry()
        try:
            first = get_registry()
            assert first.entries.keys() == ALL_BANK_IDS

            reset_registry()
            assert get_registry() is not first
        finally:
            reset_registry()  # 清理全局态，避免污染其他测试


class TestCli:
    def _run_cli(self, root: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(CLI_SCRIPT), "--data-root", str(root)],
            capture_output=True, text=True, timeout=120,
        )

    def test_exit_zero_and_ok_lines_on_good_root(self, tmp_path):
        """Given 原样四库 When CLI Then exit 0 且输出四行 OK。"""
        proc = self._run_cli(make_data_root(tmp_path))

        assert proc.returncode == 0, proc.stderr
        for bank_id in sorted(ALL_BANK_IDS):
            assert f"OK {bank_id}" in proc.stdout

    def test_exit_one_and_disabled_line_on_bad_root(self, tmp_path):
        """Given math2 文件缺失 When CLI Then exit 1 且含 DISABLED 行。"""
        root = make_data_root(tmp_path)
        (root / "math" / "math2.db").unlink()

        proc = self._run_cli(root)

        assert proc.returncode == 1
        assert "DISABLED math2" in proc.stdout
