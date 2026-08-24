"""统计两端点（需求文档 §6.6 / §9.4）。

- overview：正确率/刷题量/连续学习天数（按 STATS_TZ 切日）
- weaknesses：学科×题型×知识点聚合；窗口=最近90天或200次先到；
  入选 ≥5 次且正确率 <60%；错误率降序
"""
from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, Query

from app.api.deps import get_current_user
from app.core.bank_registry import QUESTION_TYPE_MAPPING, get_registry
from app.core.config import get_settings
from app.core.errors import BadRequestError
from app.repositories import tags_repo
from app.schemas.common import envelope

router = APIRouter(prefix="/api/stats", tags=["stats"])


def _user_db() -> str:
    return str(get_settings().data_root / "user_data.db")


def _subject_banks(subject_id: str | None) -> list[str] | None:
    if subject_id is None:
        return None
    return [e.id for e in get_registry().entries.values() if e.subject_id == subject_id]


def _streak_days(db: str, user_id: int, tz: ZoneInfo) -> int:
    """连续学习天数：按配置时区切日，从今天（或昨天）往回连续有作答的天数。"""
    conn = sqlite3.connect(db)
    try:
        rows = conn.execute(
            "SELECT created_at FROM practice_records WHERE user_id = ? ORDER BY created_at DESC",
            (user_id,),
        ).fetchall()
    finally:
        conn.close()
    days = {
        datetime.fromisoformat(r[0]).astimezone(tz).date()
        for r in rows
    }
    if not days:
        return 0
    today = datetime.now(tz).date()
    cursor = today if today in days else today - timedelta(days=1)
    streak = 0
    while cursor in days:
        streak += 1
        cursor -= timedelta(days=1)
    return streak


@router.get("/overview")
def overview(
    from_: str | None = Query(None, alias="from"),
    to: str | None = None,
    subject_id: str | None = None,
    user=Depends(get_current_user),
):
    """正确率、刷题量、连续学习天数。from/to 为 ISO 日期且 from<=to。"""
    if from_ or to:
        try:
            d_from = datetime.fromisoformat(from_).date() if from_ else None
            d_to = datetime.fromisoformat(to).date() if to else None
        except ValueError as exc:
            raise BadRequestError("from/to must be ISO dates") from exc
        if d_from and d_to and d_from > d_to:
            raise BadRequestError("from must be <= to")
    else:
        d_from = d_to = None

    db = _user_db()
    settings = get_settings()
    banks = _subject_banks(subject_id)

    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    try:
        where = ["user_id = ?"]
        args: list = [user["id"]]
        if banks is not None:
            where.append(f"bank_id IN ({','.join('?' * len(banks))})")
            args.extend(banks)
        if d_from:
            where.append("substr(created_at, 1, 10) >= ?")
            args.append(d_from.isoformat())
        if d_to:
            where.append("substr(created_at, 1, 10) <= ?")
            args.append(d_to.isoformat())
        row = conn.execute(
            f"SELECT COUNT(*) AS total, SUM(CASE WHEN is_correct = 1 THEN 1 ELSE 0 END) AS correct "
            f"FROM practice_records WHERE {' AND '.join(where)}",
            args,
        ).fetchone()
    finally:
        conn.close()

    total = row["total"] or 0
    correct = row["correct"] or 0
    tz = ZoneInfo(settings.stats_tz)
    return envelope({
        "total_attempts": total,
        "correct_attempts": correct,
        "accuracy": round(correct / total, 4) if total else 0.0,
        "streak_days": _streak_days(db, user["id"], tz),
    })


@router.get("/weaknesses")
def weaknesses(
    subject_id: str | None = None,
    limit: int = Query(10, ge=1, le=100),
    user=Depends(get_current_user),
):
    """薄弱项聚合：学科×type_code×tag 三维度平铺输出。"""
    db = _user_db()
    settings = get_settings()
    banks = _subject_banks(subject_id)

    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    try:
        where = ["user_id = ?"]
        args: list = [user["id"]]
        if banks is not None:
            where.append(f"bank_id IN ({','.join('?' * len(banks))})")
            args.extend(banks)
        rows = conn.execute(
            f"SELECT bank_id, question_id, is_correct, created_at FROM practice_records "
            f"WHERE {' AND '.join(where)} ORDER BY created_at DESC LIMIT 200",
            args,
        ).fetchall()
    finally:
        conn.close()

    cutoff = (datetime.now(timezone.utc) - timedelta(days=90)).isoformat()
    rows = [r for r in rows if r["created_at"] >= cutoff]

    registry = get_registry()
    by_type: dict[tuple, list[int]] = {}
    by_tag: dict[int, list[int]] = {}
    for r in rows:
        entry = registry.entries.get(r["bank_id"])
        if entry is None:
            continue
        mapping = QUESTION_TYPE_MAPPING.get(entry.subject_id, {})
        qtype = None
        try:
            from app.repositories import bank_repo

            q = bank_repo.get_question(str(entry.path), r["question_id"])
            if q is not None:
                qtype = mapping.get(q["question_type_id"])
        except Exception:
            qtype = None
        key_t = (entry.subject_id, qtype or "UNKNOWN")
        by_type.setdefault(key_t, []).append(r["is_correct"])

        tag_ids: list[int] = []
        try:
            tconn = sqlite3.connect(str(settings.data_root / "knowledge_tags.db"))
            trows = tconn.execute(
                "SELECT tag_id FROM question_tags WHERE bank_id = ? AND question_id = ?",
                (r["bank_id"], r["question_id"]),
            ).fetchall()
            tconn.close()
            tag_ids = [t[0] for t in trows]
        except sqlite3.Error:
            pass
        fb = None
        try:
            uconn = sqlite3.connect(db)
            uconn.row_factory = sqlite3.Row
            fb = uconn.execute(
                "SELECT tag_ids_json FROM ai_feedback WHERE bank_id = ? AND question_id = ? AND tag_ids_json IS NOT NULL "
                "ORDER BY created_at DESC LIMIT 1",
                (r["bank_id"], r["question_id"]),
            ).fetchone()
            uconn.close()
            if fb and fb["tag_ids_json"]:
                tag_ids.extend(int(t) for t in json.loads(fb["tag_ids_json"]))
        except (sqlite3.Error, ValueError, TypeError):
            pass
        for tid in set(tag_ids):
            by_tag.setdefault(tid, []).append(r["is_correct"])

    items = []
    for (subj, tcode), results in by_type.items():
        n = len(results)
        if n >= 5:
            acc = sum(1 for x in results if x == 1) / n
            if acc < 0.6:
                items.append({
                    "dimension": "type", "subject_id": subj, "key": tcode,
                    "attempts": n, "accuracy": round(acc, 4), "wrong_rate": round(1 - acc, 4),
                })
    tags_db = str(settings.data_root / "knowledge_tags.db")
    for tid, results in by_tag.items():
        n = len(results)
        if n >= 5:
            acc = sum(1 for x in results if x == 1) / n
            if acc < 0.6:
                name_rows = tags_repo.list_tags_by_subject(tags_db, subject_id or "")
                tag_name = next((t["tag_name"] for t in name_rows if t["id"] == tid), str(tid))
                items.append({
                    "dimension": "tag", "subject_id": subject_id, "key": tag_name,
                    "attempts": n, "accuracy": round(acc, 4), "wrong_rate": round(1 - acc, 4),
                })

    items.sort(key=lambda x: x["wrong_rate"], reverse=True)
    return envelope({"items": items[:limit]})
