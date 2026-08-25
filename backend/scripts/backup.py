"""Backup script for ToneUp backend data.

Creates a zip archive of the three critical data files (user_data.db,
knowledge_tags.db, manifest.json) under data_root, and prunes old
archives so that only ``keep`` recent backups are retained.

Usage
-----
python scripts/backup.py [--data-root DIR] [--keep N]

The default ``data-root`` is ``<script-dir>/data``; the default ``keep``
is 7.
"""

from __future__ import annotations

import argparse
import sqlite3
import shutil
import sys
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path
from typing import List, Tuple


# ---------------------------------------------------------------------------
# Core backup logic
# ---------------------------------------------------------------------------

DATA_FILES = ["user_data.db", "knowledge_tags.db", "manifest.json"]


def _collect_sources(data_root: Path) -> List[Tuple[str, Path]]:
    """Return list of (display_name, path) for files that exist.

    Files that do not exist are silently skipped (a warning is printed
    later if *all* are missing).
    """
    sources: List[Tuple[str, Path]] = []
    for name in DATA_FILES:
        p = data_root / name
        if p.is_file():
            sources.append((name, p))
        else:
            print(f"警告: 源文件缺失，跳过: {p}")
    return sources


def _snapshot_sqlite(src: Path, dst: Path) -> None:
    """用 sqlite3 backup API 把 WAL 模式下的活动库复制成一致快照。

    直接 zipfile.write() 正被 FastAPI worker 写入的 .db 可能打包出
    缺 -wal 或半事务页的撕裂文件，灾难恢复时不可用。
    """
    src_conn = sqlite3.connect(str(src))
    try:
        dst_conn = sqlite3.connect(str(dst))
        try:
            src_conn.backup(dst_conn)
        finally:
            dst_conn.close()
    except sqlite3.DatabaseError:
        # 非 SQLite 文件或已损坏：退化为普通复制，备份任务不因单个坏文件中断
        shutil.copy2(src, dst)
    finally:
        src_conn.close()


def _zip_backup(data_root: Path, sources: List[Tuple[str, Path]]) -> Path:
    """Create a zip backup under data_root/backups/ and return its path."""
    backups_dir = data_root / "backups"
    backups_dir.mkdir(parents=True, exist_ok=True)

    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    zip_path = backups_dir / f"backup-{ts}.zip"

    with tempfile.TemporaryDirectory() as tmp_dir:
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for _display_name, path in sources:
                if path.suffix == ".db":
                    snapshot = Path(tmp_dir) / path.name
                    _snapshot_sqlite(path, snapshot)
                    zf.write(snapshot, arcname=path.name)
                else:
                    zf.write(path, arcname=path.name)

    return zip_path


def _prune_old_backups(backups_dir: Path, keep: int) -> None:
    """Keep only the ``keep`` newest zip files in *backups_dir*.

    Files are sorted by modification time (newest first); everything
    beyond the ``keep`` limit is removed.
    """
    if not backups_dir.is_dir():
        return

    zips = sorted(
        backups_dir.glob("backup-*.zip"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )

    for old in zips[keep:]:
        old.unlink()


def backup(data_root: Path, keep: int = 7) -> int:
    """Run one backup cycle.

    Returns ``0`` on success, non-zero if no data files were found.
    """
    sources = _collect_sources(data_root)

    # If *all* three source files are missing, exit with error code.
    if not sources:
        print("错误: 没有找到任何备份源文件 (user_data.db, knowledge_tags.db, manifest.json)")
        return 1

    zip_path = _zip_backup(data_root, sources)
    print(f"已创建备份: {zip_path}")

    # Prune old backups *after* the new one has been written.
    backups_dir = data_root / "backups"
    _prune_old_backups(backups_dir, keep)

    # List current count for user feedback.
    remaining = sorted(backups_dir.glob("backup-*.zip"))
    print(f"保留最近 {keep} 个备份，当前计数: {len(remaining)}")

    return 0


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def _positive_int(value: str) -> int:
    n = int(value)
    if n < 1:
        raise argparse.ArgumentTypeError("必须为 >= 1 的整数")
    return n


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="ToneUp 数据备份与清理脚本"
    )
    parser.add_argument(
        "--data-root",
        type=Path,
        default=None,
        help="数据根目录（默认：脚本同级的 data 目录）",
    )
    parser.add_argument(
        "--keep",
        type=_positive_int,
        default=7,
        help="保留的最新备份数（默认：7，最小 1）",
    )
    return parser


def main() -> None:
    parser = _build_parser()
    args = parser.parse_args()

    data_root: Path = args.data_root or (Path(__file__).parent / "data")
    keep: int = args.keep

    exit_code = backup(data_root=data_root, keep=keep)

    # Mandatory advisory print (as specified in the task).
    print(
        "生产环境请收紧备份目录文件权限（Windows: icacls / POSIX: chmod 600）"
    )

    sys.exit(exit_code)


if __name__ == "__main__":
    main()
