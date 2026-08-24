"""图片端点（需求文档 §6.3 / D3）。

- bank_id 必填：各题库 image_id 空间独立且重叠
- 校验图片确属该启用题库后才返回
- 单次读取 bytes -> Response，全程零二次复制；immutable 长缓存 + ETag
"""
from __future__ import annotations

import hashlib

from fastapi import APIRouter, Query, Response

from app.core.bank_registry import get_registry
from app.core.errors import BadRequestError, NotFoundError
from app.repositories import bank_repo

router = APIRouter(prefix="/api/images", tags=["images"])

CACHE_HEADERS = {
    "Cache-Control": "public, max-age=31536000, immutable",
}


@router.get("/{image_id}")
def get_image(image_id: int, bank_id: str = Query(...)):
    """流式返回 BLOB；Content-Type 取库内 mime。错配/不存在 404。"""
    entry = get_registry().get(bank_id)
    if entry is None:
        raise NotFoundError(f"question bank '{bank_id}' not found or disabled")
    row = bank_repo.get_image(str(entry.path), image_id)
    if row is None or row["data"] is None:
        raise NotFoundError(f"image {image_id} not found in bank '{bank_id}'")

    data = row["data"]
    etag = hashlib.md5(data).hexdigest()
    headers = dict(CACHE_HEADERS)
    headers["ETag"] = f'"{etag}"'
    mime = row["mime"] or "application/octet-stream"
    return Response(content=data, media_type=mime, headers=headers)
