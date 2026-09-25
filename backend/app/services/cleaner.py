"""markdown 残留清洗管线（需求文档 §4.3）。

- 只影响对外输出，不修改库内原始数据
- 规则集中本文件一处实现；开关 CLEAN_MARKDOWN 关闭时原样透传
- content / answer_text / solution 同一管线，不做语义改写
"""
from __future__ import annotations

import re

_HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s*", re.MULTILINE)
_LIST_MARK_RE = re.compile(r"^\s{0,3}[-*+]\s+", re.MULTILINE)
# M-325：DOTALL 使加粗标记可跨行匹配（"**要点\n说明**"）
_BOLD_RE = re.compile(r"\*\*(.+?)\*\*", re.DOTALL)
# 分隔符后必须跟空白或行尾（H-104：零空白匹配会把 "3.14" 截成 "14"）
_NUM_PREFIX_RE = re.compile(r"^\s{0,3}(?:###\s*)?\d{1,3}[\.、．](?:\s+|$)", re.MULTILINE)


def clean_markdown_text(text: str | None, enabled: bool = True) -> str | None:
    """清洗展示文本：去行首 markdown 标记与重复题号前缀。"""
    if text is None:
        return None
    if not enabled:
        return text

    line = _HEADING_RE.sub("", text)
    line = _LIST_MARK_RE.sub("", line)
    line = _NUM_PREFIX_RE.sub("", line)
    line = _BOLD_RE.sub(r"\1", line)
    return line
