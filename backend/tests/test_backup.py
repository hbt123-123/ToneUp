"""Backup script tests for ToneUp.

Uses importlib to load backup.py because `scripts` is not a package.
"""

import importlib.util
import os
import zipfile
from pathlib import Path

import pytest

# Load backup.py via importlib since `scripts` is not a package.
_BACKUP_PATH = Path(__file__).parent.parent / "scripts" / "backup.py"
spec = importlib.util.spec_from_file_location("backup", _BACKUP_PATH)
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


def _tmp_backup_dir(tmp_path: Path, *, source_files: list[Path] | None = None) -> Path:
    """Create a temporary data root with optional source files and backups dir."""
    data_root = tmp_path / "data"
    data_root.mkdir(parents=True, exist_ok=True)
    backups_root = data_root / "backups"
    backups_root.mkdir(parents=True, exist_ok=True)
    if source_files:
        for f in source_files:
            # Ensure parent dir exists
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text("{}")
    return data_root


# ------------------------------------------------------------------ #
# Test 1 — three source files present → success + zip contains them
# ------------------------------------------------------------------ #
def test_backup_three_sources_success(tmp_path):
    """backup() returns 0 and zip namelist contains the three file names."""
    source_files = [
        tmp_path / "data" / "user_data.db",
        tmp_path / "data" / "knowledge_tags.db",
        tmp_path / "data" / "manifest.json",
    ]
    data_root = _tmp_backup_dir(tmp_path, source_files=source_files)

    exit_code = backup.backup(data_root=data_root, keep=7)
    assert exit_code == 0

    backups_dir = data_root / "backups"
    zip_files = sorted(backups_dir.glob("backup-*.zip"))
    assert len(zip_files) == 1

    with zipfile.ZipFile(zip_files[0], "r") as zf:
        namelist = zf.namelist()
    assert len(namelist) == 3
    assert {"user_data.db", "knowledge_tags.db", "manifest.json"} <= set(namelist)


# ------------------------------------------------------------------ #
# Test 2 — pruning: 9 old backups + 1 new → keep=7 → only 7 remain
# ------------------------------------------------------------------ #
def test_backup_prunes_to_keep(tmp_path):
    """After running backup with keep=7, directory contains exactly 7 zips."""
    source_files = [
        tmp_path / "data" / "user_data.db",
        tmp_path / "data" / "knowledge_tags.db",
        tmp_path / "data" / "manifest.json",
    ]
    data_root = _tmp_backup_dir(tmp_path, source_files=source_files)

    # Pre-create 9 old backups
    backups_dir = data_root / "backups"
    for i in range(9):
        zip_path = backups_dir / f"backup-{i:04d}.zip"
        with zipfile.ZipFile(zip_path, "w") as zf:
            zf.writestr("dummy.txt", "")
        os.utime(zip_path, (i * 100, i * 100))

    # Run backup with keep=7
    exit_code = backup.backup(data_root=data_root, keep=7)
    assert exit_code == 0

    remaining = sorted(data_root.glob("backups/backup-*.zip"))
    assert len(remaining) == 7, f"Expected 7 backups, got {len(remaining)}: {[p.name for p in remaining]}"


# ------------------------------------------------------------------ #
# Test 3 — all three source files missing → exit code 1
# ------------------------------------------------------------------ #
def test_backup_missing_all_sources_returns_one(tmp_path):
    """backup() returns 1 when no source files exist."""
    data_root = _tmp_backup_dir(tmp_path)  # no source files

    exit_code = backup.backup(data_root=data_root, keep=7)
    assert exit_code == 1


# ------------------------------------------------------------------ #
# Test 4 --keep=3 时保留 3 个
# ------------------------------------------------------------------ #
def test_backup_keep_3_preserves_3(tmp_path):
    """With keep=3, only the 3 newest backups are retained."""
    source_files = [
        tmp_path / "data" / "user_data.db",
        tmp_path / "data" / "knowledge_tags.db",
        tmp_path / "data" / "manifest.json",
    ]
    data_root = _tmp_backup_dir(tmp_path, source_files=source_files)

    backups_dir = data_root / "backups"

    # Create 5 zip files with different mtimes (newest = i=4)
    for i in range(5):
        zip_path = backups_dir / f"backup-{i:04d}.zip"
        with zipfile.ZipFile(zip_path, "w") as zf:
            zf.writestr("dummy.txt", "")
        os.utime(zip_path, (i * 100, i * 100))

    exit_code = backup.backup(data_root=data_root, keep=3)
    assert exit_code == 0

    remaining = sorted(backups_dir.glob("backup-*.zip"))
    assert len(remaining) == 3, f"Expected 3 backups, got {len(remaining)}: {[p.name for p in remaining]}"