"""笔记端点（需求文档 §6.7）：upsert / 共享 / 点赞。"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Body, Depends, Query

from app.api.deps import get_current_user
from app.core.config import get_settings
from app.core.errors import (
    BadRequestError,
    ConflictError,
    ForbiddenError,
    NotFoundError,
)
from app.repositories import user_repo
from app.schemas.common import envelope

router = APIRouter(prefix="/api/questions", tags=["notes"])

# ── 新增路由（/api/notes/...）挂在同一 router 上，用不同前缀 ─────────────
_extra_router = APIRouter(prefix="/api/notes", tags=["notes"])


def _db() -> str:
    return str(get_settings().data_root / "user_data.db")


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


# ── GET /api/questions/{question_id}/notes ────────────────────────────────────

@router.get("/{question_id}/notes")
def get_notes(
    question_id: int,
    bank_id: str = Query(...),
    scope: str = Query("public", pattern="^(public|mine)$"),
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    user=Depends(get_current_user),
):
    """获取笔记列表：scope=public 返回公开笔记（按 like_count DESC），scope=mine 返回当前用户笔记。"""
    db = _db()
    if scope == "public":
        rows, total = user_repo.notes_list_public(db, bank_id, question_id, page, page_size)
        items = []
        for row in rows:
            item = {
                "note_id": row["id"],
                "note_text": row["note_text"],
                "visibility": row["visibility"],
                "like_count": row["like_count"],
                "user_id": row["user_id"],
                "updated_at": row["updated_at"],
                "is_liked_by_me": user_repo.note_is_liked_by(db, row["id"], user["id"]),
            }
            items.append(item)
        return envelope({"items": items, "total": total})
    else:
        # scope == "mine"
        row = user_repo.notes_list_mine(db, user["id"], bank_id, question_id)
        if row:
            items = [{
                "note_id": row["id"],
                "note_text": row["note_text"],
                "visibility": row["visibility"],
                "like_count": row["like_count"],
                "user_id": row["user_id"],
                "updated_at": row["updated_at"],
                "is_liked_by_me": user_repo.note_is_liked_by(db, row["id"], user["id"]),
            }]
        else:
            items = []
        return envelope({"items": items, "total": len(items)})


# ── PUT /api/questions/{question_id}/notes（upsert，保留兼容）────────────────

@router.put("/{question_id}/notes")
def put_notes(
    question_id: int,
    body: dict = Body(...),
    user=Depends(get_current_user),
):
    """保存（upsert）笔记；note_text ≤1000 字符，支持 visibility。"""
    bank_id = body.get("bank_id")
    note_text = body.get("note_text")
    visibility = body.get("visibility", "public")
    if not bank_id:
        raise BadRequestError("bank_id is required")
    if not isinstance(note_text, str) or not note_text:
        raise BadRequestError("note_text is required")
    if len(note_text) > 1000:
        raise BadRequestError("note_text must be <= 1000 chars")
    if visibility not in ("public", "private"):
        raise BadRequestError("visibility must be 'public' or 'private'")
    db = _db()
    now_iso = _now_iso()
    user_repo.notes_upsert(db, user["id"], bank_id, question_id, note_text, visibility, now_iso)
    return envelope({"question_id": question_id, "bank_id": bank_id, "saved": True})


# ── PUT /api/notes/{note_id} ──────────────────────────────────────────────────

@_extra_router.put("/{note_id}")
def update_note(
    note_id: int,
    body: dict = Body(...),
    user=Depends(get_current_user),
):
    """更新笔记内容/可见性，仅笔记拥有者可操作。"""
    db = _db()
    row = user_repo.notes_get_by_id(db, note_id)
    if row is None:
        raise NotFoundError("note not found")
    if row["user_id"] != user["id"]:
        raise ForbiddenError("not your note")
    note_text = body.get("note_text")
    visibility = body.get("visibility")
    if note_text is not None and (not isinstance(note_text, str) or not note_text):
        raise BadRequestError("note_text must be a non-empty string")
    if note_text is not None and len(note_text) > 1000:
        raise BadRequestError("note_text must be <= 1000 chars")
    if visibility is not None and visibility not in ("public", "private"):
        raise BadRequestError("visibility must be 'public' or 'private'")
    user_repo.notes_update(db, note_id, user["id"], note_text=note_text, visibility=visibility, now_iso=_now_iso())
    return envelope({"note_id": note_id, "updated": True})


# ── DELETE /api/notes/{note_id} ───────────────────────────────────────────────

@_extra_router.delete("/{note_id}")
def delete_note(note_id: int, user=Depends(get_current_user)):
    """删除笔记，仅笔记拥有者可操作。"""
    db = _db()
    row = user_repo.notes_get_by_id(db, note_id)
    if row is None:
        raise NotFoundError("note not found")
    if row["user_id"] != user["id"]:
        raise ForbiddenError("not your note")
    user_repo.notes_delete(db, note_id, user["id"])
    return envelope({"note_id": note_id, "deleted": True})


# ── POST /api/notes/{note_id}/like ────────────────────────────────────────────

@_extra_router.post("/{note_id}/like")
def like_note(note_id: int, user=Depends(get_current_user)):
    """点赞笔记；已点赞返回 409。

    用非切换原语（add + increment + get count）组合：toggle 原语在
    "已点赞"错误路径会先删掉赞再报 409，破坏幂等（C-10）。
    """
    db = _db()
    row = user_repo.notes_get_by_id(db, note_id)
    if row is None:
        raise NotFoundError("note not found")
    if not user_repo.note_add_like(db, note_id, user["id"], _now_iso()):
        raise ConflictError("already liked")
    user_repo.note_increment_likes(db, note_id)
    return envelope({"liked": True, "like_count": user_repo.note_get_like_count(db, note_id)})


# ── DELETE /api/notes/{note_id}/like ──────────────────────────────────

@_extra_router.delete("/{note_id}/like")
def unlike_note(note_id: int, user=Depends(get_current_user)):
    """取消点赞；未点赞返回 404。同理用非切换原语，错误路径零副作用。"""
    db = _db()
    row = user_repo.notes_get_by_id(db, note_id)
    if row is None:
        raise NotFoundError("note not found")
    if not user_repo.note_remove_like(db, note_id, user["id"]):
        raise NotFoundError("not liked")
    user_repo.note_decrement_likes(db, note_id)
    return envelope({"liked": False, "like_count": user_repo.note_get_like_count(db, note_id)})
