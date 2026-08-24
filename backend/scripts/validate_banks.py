"""题库清单校验 CLI：加载 manifest 并逐条打印 OK/DISABLED，全启用退出 0 否则 1。

Usage:
    python scripts/validate_banks.py [--data-root DIR]

默认 data-root 为脚本上一级的 ``data`` 目录（即 backend/data）。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# 使脚本可直接以 `python scripts/validate_banks.py` 运行（sys.path[0] 为 scripts/）
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.bank_registry import BankRegistry  # noqa: E402


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="ToneUp 题库清单校验")
    parser.add_argument(
        "--data-root",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "data",
        help="数据根目录（默认：backend/data）",
    )
    return parser


def main() -> None:
    args = _build_parser().parse_args()

    registry = BankRegistry()
    registry.load(args.data_root)

    all_enabled = True
    for bank_id, entry in registry.entries.items():
        if entry.enabled:
            print(f"OK {bank_id}")
            continue
        all_enabled = False
        reason = next(
            (w.split(": ", 1)[1] for w in registry.warnings
             if w.startswith(f"{bank_id}: ")),
            "manifest 中 enabled=false",
        )
        print(f"DISABLED {bank_id}: {reason}")

    sys.exit(0 if all_enabled else 1)


if __name__ == "__main__":
    main()
