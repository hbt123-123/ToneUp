"""题干内图片引用改写器（需求文档 §6.3）。

引用格式以实现期勘察为准（scripts/survey_image_refs.py 可随时复核）。
本改写器防御性覆盖常见模式，把引用中的图片 id 改写为：
    /api/images/{image_id}?bank_id={bank_id}
未识别的模式原样透传并记 warning 日志。
"""
from __future__ import annotations

import re

import structlog

logger = structlog.get_logger()

_IMG_TAG_RE = re.compile(r"<img[^>]*src=[\"']([^\"']*)[\"'][^>]*>", re.IGNORECASE)
_MD_IMAGE_RE = re.compile(r"!\[[^\]]*\]\(([^)]+)\)")
_BRACKET_CN_RE = re.compile(r"\[图\s*(\d+)\]")
_PLACEHOLDER_RE = re.compile(r"__(?:IMAGE|IMG)_?(\d+)__", re.IGNORECASE)
_BARE_ID_RE = re.compile(r"(?<![\w/])(\d{1,6})(?![\w/.]|\d)(?:\.(?:png|jpg|jpeg|webp|gif))?", re.IGNORECASE)


def _endpoint(image_id: str, bank_id: str) -> str:
    return f"/api/images/{image_id}?bank_id={bank_id}"


def rewrite_image_refs(content: str | None, bank_id: str, enabled: bool = True) -> str | None:
    """把题干中的图片引用改写为图片端点 URL；未识别模式原样透传。"""
    if content is None or not enabled:
        return content

    rewritten = False

    def _replace_ids_in(text: str) -> str:
        nonlocal rewritten

        def repl(m: re.Match) -> str:
            nonlocal rewritten
            rewritten = True
            return _endpoint(m.group(1), bank_id)

        text = _PLACEHOLDER_RE.sub(repl, text)

        def repl_cn(m: re.Match) -> str:
            nonlocal rewritten
            rewritten = True
            return f"[图]({_endpoint(m.group(1), bank_id)})"

        text = _BRACKET_CN_RE.sub(repl_cn, text)

        def repl_md(m: re.Match) -> str:
            nonlocal rewritten
            target = m.group(1).strip()
            ids = _PLACEHOLDER_RE.findall(target) or _BARE_ID_RE.findall(target)
            if ids:
                rewritten = True
                return m.group(0).replace(target, _endpoint(ids[0], bank_id))
            return m.group(0)

        text = _MD_IMAGE_RE.sub(repl_md, text)

        def repl_tag(m: re.Match) -> str:
            nonlocal rewritten
            src = m.group(1)
            ids = _PLACEHOLDER_RE.findall(src) or _BARE_ID_RE.findall(src)
            if ids:
                rewritten = True
                return m.group(0).replace(src, _endpoint(ids[0], bank_id))
            return m.group(0)

        text = _IMG_TAG_RE.sub(repl_tag, text)
        return text

    result = _replace_ids_in(content)
    if not rewritten and ("img" in content.lower() or "图" in content):
        logger.warning("image_ref_pattern_unrecognized", bank_id=bank_id, snippet=content[:80])
    return result
