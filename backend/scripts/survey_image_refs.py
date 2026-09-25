"""只读勘察：题干 content 中的图片引用格式（需求文档 §6.3 授权的 M1 勘察）。

用法：
    cd backend
    uv run python scripts/survey_image_refs.py

只读打开四库，统计各引用模式的命中数并输出样本，供改写器核对。
"""
from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

BANKS = {
    "math1": Path(__file__).resolve().parents[1] / "data/math/math1.db",
    "math2": Path(__file__).resolve().parents[1] / "data/math/math2.db",
    "english1": Path(__file__).resolve().parents[1] / "data/english/english1.db",
    "english2": Path(__file__).resolve().parents[1] / "data/english/english2.db",
}

PATTERNS = ["<img", "![](", "[图", "见图", "__IMAGE", "__IMG", "{img", "images/", ".png", ".jpg"]


def main() -> None:
    for name, path in BANKS.items():
        print(f"\n===== {name} =====")
        if not path.exists():
            # M-353：缺库跳过，不让单个库缺失中断整体勘察
            print("  跳过：库文件不存在")
            continue
        conn = None
        try:
            conn = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
            for pat in PATTERNS:
                # M-352：统一小写匹配，覆盖 "<IMG"、".PNG" 等大写变体
                n = conn.execute(
                    "SELECT COUNT(*) FROM questions WHERE instr(lower(content), ?) > 0",
                    (pat.lower(),),
                ).fetchone()[0]
                if n:
                    print(f"  {pat!r}: {n} hits")
                    sample = conn.execute(
                        "SELECT id, substr(content, MAX(1, instr(lower(content), ?) - 30), 100) "
                        "FROM questions WHERE instr(lower(content), ?) > 0 LIMIT 2",
                        (pat.lower(), pat.lower()),
                    ).fetchall()
                    for qid, ctx in sample:
                        print(f"    qid={qid}: ...{ctx!r}...")
        except sqlite3.Error as exc:
            print(f"  查询失败: {exc}")
        finally:
            if conn is not None:
                conn.close()
    print("\nDONE")


if __name__ == "__main__":
    main()
