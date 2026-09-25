"""图片端点（需求文档 §6.3 / D3）。

- bank_id 必填：各题库 image_id 空间独立且重叠
- 校验图片确属该启用题库后才返回
- 单次读取 bytes -> Response，全程零二次复制；immutable 长缓存 + ETag
"""
from __future__ import annotations

import hashlib

from fastapi import APIRouter, Query, Request, Response

from app.core.bank_registry import get_registry
from app.core.errors import BadRequestError, NotFoundError
from app.repositories import bank_repo

router = APIRouter(prefix="/api/images", tags=["images"])

CACHE_HEADERS = {
    "Cache-Control": "public, max-age=31536000, immutable",
}


@router.get("/{image_id}")
def get_image(request: Request, image_id: int, bank_id: str = Query(...)):
    """流式返回 BLOB；Content-Type 取库内 mime。错配/不存在 404。

    M-281：支持 If-None-Match 协商缓存，命中即 304 短路，免重传整个 BLOB。
    """
    entry = get_registry().get(bank_id)
    if entry is None:
        raise NotFoundError(f"question bank '{bank_id}' not found or disabled")
    row = bank_repo.get_image(str(entry.path), image_id)
    if row is None or row["data"] is None:
        raise NotFoundError(f"image {image_id} not found in bank '{bank_id}'")

    data = row["data"]
    etag = hashlib.md5(data).hexdigest()
    strong_etag = f'"{etag}"'
    headers = dict(CACHE_HEADERS)
    headers["ETag"] = strong_etag

    # M-281：If-None-Match 命中（含 W/ 弱校验器与 * 通配）→ 304 不带 body
    inm = request.headers.get("if-none-match")
    if inm:
        for candidate in inm.split(","):
            c = candidate.strip()
            if c.startswith("W/"):
                c = c[2:]
            if c == strong_etag or c == "*":
                return Response(status_code=304, headers=headers)

    mime = row["mime"] or "application/octet-stream"
    return Response(content=data, media_type=mime, headers=headers)
