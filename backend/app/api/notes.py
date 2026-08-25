"""笔记两端点（需求文档 §6.7）：upsert 语义。"""
from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Body, Depends, Query

from app.api.deps import get_current_user
from app.core.config import get_settings
from app.core.errors import BadRequestError
from app.repositories import user_repo
from app.schemas.common import envelope

router = APIRouter(prefix="/api/questions", tags=["notes"])


@router.get("/{question_id}/notes")
def get_notes(question_id: int, bank_id: str = Query(...), user=Depends(get_current_user)):
    """当前用户某题笔记；无笔记返回 note_text=None。"""
    db = str(get_settings().data_root / "user_data.db")
    row = user_repo.notes_get(db, user["id"], bank_id, question_id)
    return envelope({
        "question_id": question_id,
        "bank_id": bank_id,
        "note_text": row["note_text"] if row else None,
        "updated_at": row["updated_at"] if row else None,
    })


@router.put("/{question_id}/notes")
def put_notes(question_id: int, body: dict = Body(...), user=Depends(get_current_user)):
    """保存（upsert）笔记；note_text ≤10000 字符。"""
    bank_id = body.get("bank_id")
    note_text = body.get("note_text")
    if not bank_id:
        raise BadRequestError("bank_id is required")
    if not isinstance(note_text, str) or len(note_text) > 10000:
        raise BadRequestError("note_text required and must be <= 10000 chars")
    db = str(get_settings().data_root / "user_data.db")
    now_iso = datetime.now(timezone.utc).isoformat()
    user_repo.notes_upsert(db, user["id"], bank_id, question_id, note_text, now_iso)
    return envelope({"question_id": question_id, "bank_id": bank_id, "saved": True})
