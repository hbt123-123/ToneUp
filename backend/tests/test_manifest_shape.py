import json
import pathlib

import pytest

MANIFEST_PATH = pathlib.Path(__file__).parents[1] / "data" / "manifest.json"


def _load_manifest():
    with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


class TestManifestShape:
    def test_json_loadable(self):
        """① json.load 可解析"""
        data = _load_manifest()
        assert data is not None

    def test_subjects_contain_math_and_english_with_type_counts(self):
        """② subjects 含 math/english 且 math.types 有 3 个、english.types 有 1 个"""
        data = _load_manifest()
        subjects = data["subjects"]
        subject_ids = {s["id"] for s in subjects}
        assert "math" in subject_ids
        assert "english" in subject_ids

        math_subject = next(s for s in subjects if s["id"] == "math")
        english_subject = next(s for s in subjects if s["id"] == "english")

        assert len(math_subject["types"]) == 3
        assert len(english_subject["types"]) == 1

    def test_banks_exactly_four_with_seven_keys_each(self):
        """③ banks 恰好 4 条且每条键集合==七键"""
        data = _load_manifest()
        banks = data["banks"]
        assert len(banks) == 4

        expected_keys = {"id", "subject_id", "type_id", "name", "path", "schema_version", "enabled"}
        for bank in banks:
            assert set(bank.keys()) == expected_keys, f"Bank {bank.get('id', '?')} has keys {set(bank.keys())}"

    def test_no_backslashes_or_double_dots_in_paths(self):
        """④所有 path 无反斜杠且不含 '..'"""
        data = _load_manifest()
        banks = data["banks"]
        for bank in banks:
            path = bank["path"]
            assert "\\" not in path, f"Bank {bank['id']} has backslash in path: {path}"
            assert ".." not in path, f"Bank {bank['id']} has '..' in path: {path}"

    def test_bank_ids_belong_to_subjects_tree(self):
        """⑤每条 bank 的 subject_id/type_id 能在 subjects 树中归属"""
        data = _load_manifest()
        subjects = {s["id"]: s for s in data["subjects"]}
        banks = data["banks"]

        for bank in banks:
            sid = bank["subject_id"]
            tid = bank["type_id"]
            assert sid in subjects, f"Bank {bank['id']} has unknown subject_id: {sid}"
            assert any(t["id"] == tid for t in subjects[sid]["types"]), \
                f"Bank {bank['id']} has type_id {tid} not in subject {sid} types"

    def test_no_duplicate_bank_ids(self):
        """⑥bank id 无重复"""
        data = _load_manifest()
        bank_ids = [b["id"] for b in data["banks"]]
        assert len(bank_ids) == len(set(bank_ids)), f"Duplicate bank ids found: {bank_ids}"