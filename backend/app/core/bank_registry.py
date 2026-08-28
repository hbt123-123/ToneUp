"""题库注册表：manifest.json 加载器 + 五条校验规则 + 题型映射配置。

对 ``data_root/manifest.json`` 的每条 bank 依次执行五条规则，
任一失败仅禁用该条目并记一条含 bank_id 的 warning：

① id 全局唯一（重复时后者被禁用）
② subject_id/type_id 必须归属 subjects 树
③ path 相对 data_root 解析后不得逃逸出 data_root（os.path.commonpath 判定）
④ 文件存在且为合法 SQLite；必需表 collections/questions/images 齐全；
   questions 列齐全；options/sub_questions 非空时必须为合法 JSON；
   display_order 无 NULL；images.mime 非空且形如 image/xxx；
   questions.collection_id 均存在于 collections.id
⑤ 失败粒度为单条目——不影响其余条目
"""
from __future__ import annotations

import json
import os
import sqlite3
from dataclasses import dataclass, replace
from pathlib import Path

from app.core.config import get_settings

# ----------------------------------------------------------------------
# 题型映射配置（§4.1）：subject -> question_type_id -> 类型码
# ----------------------------------------------------------------------
QUESTION_TYPE_MAPPING: dict[str, dict[int, str]] = {
    "math": {1: "SINGLE", 2: "FILL_BLANK", 3: "SOLUTION"},
    "english": {1: "CLOZE", 2: "READING", 3: "ORDERING", 4: "TRANSLATION", 5: "ESSAY"},
    "politics": {1: "SINGLE", 2: "MULTI", 3: "ESSAY"},
}

# 全部合法类型码枚举；MULTI/JUDGE 仅在此枚举中，不出现在任何 subject 映射里
VALID_TYPE_CODES: frozenset[str] = frozenset({
    "SINGLE", "MULTI", "FILL_BLANK", "SOLUTION",
    "CLOZE", "READING", "ORDERING", "TRANSLATION", "ESSAY", "JUDGE",
})

_REQUIRED_TABLES: tuple[str, ...] = ("collections", "questions", "images")
_REQUIRED_QUESTION_COLUMNS: frozenset[str] = frozenset({
    "id", "collection_id", "question_type_id", "number", "content", "options",
    "sub_questions", "answer_text", "solution", "score", "display_order",
})


@dataclass(frozen=True, slots=True)
class BankEntry:
    """manifest 中一条 bank 的解析结果；path 恒为解析后的绝对路径。"""

    id: str
    subject_id: str
    type_id: str
    name: str
    path: Path
    schema_version: int
    enabled: bool


def _is_within(child: Path, parent: Path) -> bool:
    """commonpath 判定 child 是否位于 parent 内（跨盘符 ValueError 视为不在）。"""
    try:
        return os.path.commonpath([str(parent), str(child)]) == str(parent)
    except ValueError:
        return False


def _check_sqlite(path: Path) -> str | None:
    """规则④：校验 SQLite 结构与数据，返回问题描述；None 表示通过。"""
    if not path.is_file():
        return f"数据库文件不存在: {path.name}"
    # Windows 下 SQLite URI 必须使用正斜杠
    con = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
    try:
        tables = {row[0] for row in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        missing = [t for t in _REQUIRED_TABLES if t not in tables]
        if missing:
            return f"缺少必需表 {','.join(missing)}"

        cols = {row[1] for row in con.execute("PRAGMA table_info(questions)")}
        absent = sorted(_REQUIRED_QUESTION_COLUMNS - cols)
        if absent:
            return f"questions 缺少列 {','.join(absent)}"

        collection_ids = {row[0] for row in con.execute("SELECT id FROM collections")}
        cursor = con.execute(
            "SELECT collection_id, options, sub_questions, display_order FROM questions")
        for row in cursor:  # 逐行流式校验，不整表载入内存
            if row[3] is None:
                return "questions.display_order 存在 NULL"
            if row[0] not in collection_ids:
                return f"questions.collection_id {row[0]!r} 不存在于 collections"
            for json_value in (row[1], row[2]):
                if json_value is not None and str(json_value).strip():
                    try:
                        json.loads(json_value)
                    except (TypeError, ValueError):
                        return "questions.options/sub_questions 存在非法 JSON"

        for (mime,) in con.execute("SELECT mime FROM images"):
            if not mime or not str(mime).startswith("image/"):
                return "images.mime 为空或非 image/* 前缀"
        return None
    except sqlite3.Error as exc:
        return f"非合法 SQLite 或读取失败: {exc}"
    finally:
        con.close()


class BankRegistry:
    """manifest.json 加载与五规则校验。

    成员：
    - entries: 全部可构造条目（含校验失败被禁用的，enabled=False）
    - subjects_raw: manifest 中 subjects 原始列表
    - warnings: 全部告警（格式 ``"<bank_id>: <原因>"``）
    """

    def __init__(self) -> None:
        self.entries: dict[str, BankEntry] = {}
        self.subjects_raw: list[dict] = []
        self.warnings: list[str] = []
        self._data_root: Path | None = None

    # ------------------------------------------------------------------
    def load(self, data_root: Path) -> list[str]:
        """读取并校验 ``data_root/manifest.json``，返回 warnings 列表。"""
        self.entries = {}
        self.subjects_raw = []
        self.warnings = []
        self._data_root = data_root.resolve()

        manifest_path = self._data_root / "manifest.json"
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            self.warnings.append(f"manifest: 无法读取 manifest.json: {exc}")
            return self.warnings

        self.subjects_raw = manifest.get("subjects", [])
        subject_types = self._index_subject_types(self.subjects_raw)
        for raw in manifest.get("banks", []):
            self._load_entry(raw, subject_types, self._data_root)
        return self.warnings

    def get(self, bank_id: str) -> BankEntry | None:
        """取已启用条目；不存在或已禁用均返回 None。"""
        entry = self.entries.get(bank_id)
        if entry is None or not entry.enabled:
            return None
        return entry

    def resolve(self, bank_id: str) -> Path | None:
        """取条目绝对路径；取用前再次 commonpath 复检防逃逸。"""
        entry = self.get(bank_id)
        if entry is None or self._data_root is None:
            return None
        if not _is_within(entry.path, self._data_root):
            return None
        return entry.path

    # ------------------------------------------------------------------
    @staticmethod
    def _index_subject_types(subjects: list[dict]) -> dict[str, set[str]]:
        return {
            str(s.get("id")): {str(t.get("id")) for t in s.get("types", [])}
            for s in subjects
        }

    def _load_entry(
        self, raw: dict, subject_types: dict[str, set[str]], data_root: Path
    ) -> None:
        """边界解析单条 bank 并执行规则①②③④⑤。raw 为 json 边界数据。"""
        bank_id = str(raw.get("id", "<missing>"))
        try:
            entry = BankEntry(
                id=bank_id,
                subject_id=str(raw["subject_id"]),
                type_id=str(raw["type_id"]),
                name=str(raw["name"]),
                path=(data_root / str(raw["path"])).resolve(),
                schema_version=int(raw["schema_version"]),
                enabled=bool(raw["enabled"]),
            )
        except (KeyError, TypeError, ValueError) as exc:
            self.warnings.append(f"{bank_id}: 条目字段缺失或非法: {exc}")
            return

        # ① id 全局唯一：重复时后者禁用（保留先者）
        if entry.id in self.entries:
            self.warnings.append(f"{entry.id}: bank id 重复，后者被禁用")
            return

        problem = self._validate_entry(entry, subject_types, data_root)
        if problem is not None:
            self.warnings.append(f"{entry.id}: {problem}")
            self.entries[entry.id] = replace(entry, enabled=False)
            return
        self.entries[entry.id] = entry

    def _validate_entry(
        self, entry: BankEntry, subject_types: dict[str, set[str]], data_root: Path
    ) -> str | None:
        """规则②③④，返回问题描述；None 表示全部通过。"""
        # ② subject/type 归属 subjects 树
        allowed_types = subject_types.get(entry.subject_id)
        if allowed_types is None:
            return f"未知 subject_id {entry.subject_id!r}"
        if entry.type_id not in allowed_types:
            return f"type_id {entry.type_id!r} 不属于 subject {entry.subject_id!r}"

        # ③ 路径不得逃逸 data_root
        if not _is_within(entry.path, data_root):
            return f"path 逃逸 data_root: {entry.path}"

        # ④ SQLite 结构与数据
        return _check_sqlite(entry.path)


# ----------------------------------------------------------------------
# 模块级单例访问器
# ----------------------------------------------------------------------
_registry: BankRegistry | None = None


def get_registry() -> BankRegistry:
    """返回全局注册表单例；首次调用时按 Settings.data_root 加载。"""
    global _registry
    if _registry is None:
        _registry = BankRegistry()
        _registry.load(get_settings().data_root)
    return _registry


def reset_registry() -> None:
    """清空单例（测试/热重载用）；下次 get_registry() 重新加载。"""
    global _registry
    _registry = None
