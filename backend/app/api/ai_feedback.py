"""AI 反馈两端点（需求文档 §6.8 / §10.4）。

- POST /api/ai/feedback：校验顺序 类型→大小→尺寸→落盘（超限 400 不落盘）；
  小图同步调 GLM，失败/大图 202+feedback_id；限流 10 次/小时/用户
- GET  /api/ai/feedback/{feedback_id}：仅本人
"""
from __future__ import annotations

import io
import json
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, File, Form, Request, UploadFile

from app.api.deps import get_current_user
from app.core.config import get_settings
from app.core.errors import BadRequestError, ForbiddenError, NotFoundError
from app.core.ratelimit import check_user_limit
from app.repositories import user_repo
from app.schemas.common import envelope

router = APIRouter(prefix="/api/ai", tags=["ai"])

ALLOWED_MIMES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}
MAX_BYTES = 5 * 1024 * 1024
MAX_EDGE_PX = 4096


def _image_size(data: bytes) -> tuple[int, int] | None:
    """解析图片宽高（PNG/JPEG 头部解析，免 PIL 依赖）。"""
    if data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) > 24:
        w = int.from_bytes(data[16:20], "big")
        h = int.from_bytes(data[20:24], "big")
        return w, h
    if data[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            seg_len = int.from_bytes(data[i + 2:i + 4], "big")
            if marker in (0xC0, 0xC1, 0xC2, 0xC3):
                h = int.from_bytes(data[i + 5:i + 7], "big")
                w = int.from_bytes(data[i + 7:i + 9], "big")
                return w, h
            i += 2 + seg_len
    return None


@router.post("/feedback")
def create_feedback(
    request: Request,
    file: UploadFile = File(...),
    bank_id: str = Form(...),
    question_id: int = Form(...),
    attempt_id: int | None = Form(None),
    user=Depends(get_current_user),
):
    """上传答案图片创建诊断任务。"""
    settings = get_settings()
    check_user_limit("ai_feedback", user["id"], 10, 3600)

    mime = (file.content_type or "").lower()
    ext = ALLOWED_MIMES.get(mime)
    if ext is None:
        raise BadRequestError("file type must be jpeg/png/webp")

    data = file.file.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise BadRequestError("file exceeds 5MB")
    size = _image_size(data)
    if size is None:
        raise BadRequestError("unrecognizable image")
    if max(size) > MAX_EDGE_PX:
        raise BadRequestError("image longest edge exceeds 4096px")

    user_db = str(settings.data_root / "user_data.db")
    now_iso = datetime.now(timezone.utc).isoformat()
    feedback_id = uuid.uuid4().hex

    uploads_dir = settings.data_root / "uploads"
    uploads_dir.mkdir(parents=True, exist_ok=True)
    image_path = uploads_dir / f"{feedback_id}{ext}"
    image_path.write_bytes(data)

    if attempt_id is not None:
        a = user_repo.get_attempt(user_db, attempt_id)
        if a is None or a["user_id"] != user["id"]:
            raise ForbiddenError("attempt not yours")

    user_repo.ai_feedback_create(
        user_db, feedback_id, user["id"], attempt_id,
        bank_id, question_id, str(image_path), created_at=now_iso,
    )

    sync_threshold_bytes = settings.ai_sync_max_bytes
    sync_threshold_px = settings.ai_sync_max_pixels
    small = len(data) <= sync_threshold_bytes and max(size) <= sync_threshold_px

    from app.services.grading_worker import enqueue_grading, start_worker

    start_worker()

    if small:
        enqueue_grading(feedback_id)
        fb = user_repo.ai_feedback_get(user_db, feedback_id)
        deadline = datetime.now(timezone.utc).timestamp() + 8
        while datetime.now(timezone.utc).timestamp() < deadline:
            fb = user_repo.ai_feedback_get(user_db, feedback_id)
            if fb and fb["status"] in ("succeeded", "failed"):
                break
            import time as _t

            _t.sleep(0.2)
        if fb and fb["status"] in ("succeeded", "failed"):
            return envelope(_feedback_payload(fb))
        return envelope({"feedback_id": feedback_id, "status": "queued"}, message="processing"), 202

    enqueue_grading(feedback_id)
    return envelope({"feedback_id": feedback_id, "status": "queued"}), 202


def _feedback_payload(fb) -> dict:
    tag_ids = []
    if fb["tag_ids_json"]:
        try:
            tag_ids = json.loads(fb["tag_ids_json"])
        except (ValueError, TypeError):
            tag_ids = []
    return {
        "feedback_id": fb["id"],
        "attempt_id": fb["attempt_id"],
        "bank_id": fb["bank_id"],
        "question_id": fb["question_id"],
        "status": fb["status"],
        "is_correct": fb["is_correct"],
        "error_reason": fb["error_reason"],
        "error_message": fb["error_message"],
        "tag_ids": tag_ids,
        "created_at": fb["created_at"],
        "completed_at": fb["completed_at"],
    }


@router.get("/feedback/{feedback_id}")
def get_feedback(feedback_id: str, user=Depends(get_current_user)):
    """查询诊断状态及结果；仅本人。"""
    db = str(get_settings().data_root / "user_data.db")
    fb = user_repo.ai_feedback_get(db, feedback_id)
    if fb is None:
        raise NotFoundError("feedback not found")
    if fb["user_id"] != user["id"]:
        raise ForbiddenError("not your feedback")
    return envelope(_feedback_payload(fb))
