"""自定义背景上传端点（§10.4）。

- POST /api/backgrounds/upload — 上传自定义背景图片（单用户配额1张，新上传覆盖旧图）
- GET  /api/backgrounds/{filename} — 静态文件服务，返回背景图片
"""
from __future__ import annotations

import hashlib
import re
import time
from pathlib import Path

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from fastapi.responses import FileResponse
from starlette.concurrency import run_in_threadpool

from app.api.deps import get_current_user
from app.core.config import get_settings

router = APIRouter(prefix="/api/backgrounds", tags=["backgrounds"])

ALLOWED_TYPES = {"image/jpeg", "image/png", "image/webp"}
MAX_SIZE = 5 * 1024 * 1024  # 5 MB

# magic bytes 前缀（H-87：content_type 头完全由客户端控制，须按文件内容校验）
_MAGIC_PREFIX: dict[str, tuple[bytes, ...]] = {
    "image/jpeg": (b"\xff\xd8\xff",),
    "image/png": (b"\x89PNG\r\n\x1a\n",),
    "image/webp": (b"RIFF",),  # 结合 data[8:12] == b"WEBP" 判定
}

# 安全文件名正则：{user_id}_{timestamp}_{hash12}.{jpg|png|webp}
_FILENAME_RE = re.compile(r"^\d+_\d+_[a-f0-9]{12}\.(jpg|png|webp)$")

_EXT_MAP = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
}

_MEDIA_MAP = {
    ".jpg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
}


def _backgrounds_dir() -> Path:
    """获取或创建背景存储目录。"""
    settings = get_settings()
    d = Path(settings.data_root) / "backgrounds"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _persist_background(bg_dir: Path, user_id: int, contents: bytes, ext: str) -> str:
    """同步持久化背景图（M-276/277/278）。

    - M-277：先写新文件再清理旧背景（排除新文件名）——写盘失败时旧图仍在，
      不会出现"新图没写成、旧图已删"的空窗
    - M-278：文件名指纹用全量 SHA-256 而非前 1KB——同毫秒内上传不同图片
      （前 1KB 恰好相同，如同一模板导出）不会互相覆盖
    """
    content_hash = hashlib.sha256(contents).hexdigest()[:12]
    filename = f"{user_id}_{int(time.time() * 1000)}_{content_hash}{ext}"
    (bg_dir / filename).write_bytes(contents)
    for old in bg_dir.glob(f"{user_id}_*"):
        if old.name != filename:
            old.unlink(missing_ok=True)
    return filename


@router.post("/upload")
async def upload_background(
    file: UploadFile = File(...),
    user=Depends(get_current_user),
):
    """上传自定义背景图片。单用户配额1张，新上传覆盖旧图。

    校验：
    - Content-Type 仅允许 JPEG / PNG / WebP
    - 文件大小 ≤ 5 MB
    """
    # 1. 校验 MIME 类型
    if file.content_type not in ALLOWED_TYPES:
        raise HTTPException(
            status_code=400,
            detail=f"不支持的文件类型: {file.content_type}，仅支持 JPEG/PNG/WebP",
        )

    # 2. 分块读取并校验大小（H-88：先全量 read() 再检查会任由超大 body 灌满内存）
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await file.read(1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > MAX_SIZE:
            raise HTTPException(
                status_code=400,
                detail=f"文件过大，最大允许 {MAX_SIZE // (1024 * 1024)} MB",
            )
        chunks.append(chunk)
    contents = b"".join(chunks)

    # 3. 校验 magic bytes（防止伪装成图片的任意字节被存储/回显）
    prefixes = _MAGIC_PREFIX[file.content_type]
    if not any(contents.startswith(p) for p in prefixes) or (
        file.content_type == "image/webp" and contents[8:12] != b"WEBP"
    ):
        raise HTTPException(status_code=400, detail="文件内容与声明的图片类型不符")

    # 4. 确定扩展名
    ext = _EXT_MAP[file.content_type]

    # 5-7. 同步磁盘 IO 交给线程池执行（M-276：async 端点内直接读写文件会阻塞事件循环）
    bg_dir = _backgrounds_dir()
    filename = await run_in_threadpool(
        _persist_background, bg_dir, user["id"], contents, ext
    )

    return {"url": f"/api/backgrounds/{filename}"}


@router.get("/{filename}")
async def serve_background(filename: str):
    """静态文件服务：返回背景图片。

    安全校验：文件名必须匹配 {uid}_{ts}_{hash}.{ext} 格式，防止目录穿越。
    """
    if not _FILENAME_RE.match(filename):
        raise HTTPException(status_code=404, detail="文件不存在")

    filepath = _backgrounds_dir() / filename
    if not filepath.exists():
        raise HTTPException(status_code=404, detail="文件不存在")

    media_type = _MEDIA_MAP.get(filepath.suffix, "application/octet-stream")
    return FileResponse(filepath, media_type=media_type)
