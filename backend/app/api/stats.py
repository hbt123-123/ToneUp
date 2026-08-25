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
from app.repositories import bank_repo, tags_repo
from app.schemas.common import envelope

router = APIRouter(prefix="/api/stats", tags=["stats"])


def _user_db() -> str:
    return str(get_settings().data_root / "user_data.db")


def _subject_banks(subject_id: str | None) -> list[str] | None:
    if subject_id is None:
        return None
    return [e.id for e in get_registry().entries.values() if e.subject_id == subject_id]


def _streak_days(db: str, user_id: int, tz: ZoneInfo) -> int:
    """连续学习天数：按配置时区切日，从今天（或昨天）往回连续有作答的天数。

    游标惰性逐行读取（新→旧），遇到首个日期空档即终止，
    不再把全量 created_at 物化到内存。
    """
    today = datetime.now(tz).date()
    conn = sqlite3.connect(db)
    streak = 0
    expected = None
    try:
        cursor = conn.execute(
            "SELECT created_at FROM practice_records WHERE user_id = ? ORDER BY created_at DESC",
            (user_id,),
        )
        for (created_at,) in cursor:
            day = datetime.fromisoformat(created_at).astimezone(tz).date()
            if expected is None:
                if day > today:
                    continue
                if day < today - timedelta(days=1):
                    break
                expected = day
            if day == expected:
                streak += 1
                expected -= timedelta(days=1)
            elif day < expected:
                break
    finally:
        conn.close()
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

    # 按库批量取题：每库一条 IN 查询，替代逐行 get_question 的 N+1
    qtype_by_key: dict[tuple[str, int], str | None] = {}
    by_bank: dict[str, list[int]] = {}
    for r in rows:
        if registry.entries.get(r["bank_id"]) is not None:
            by_bank.setdefault(r["bank_id"], []).append(r["question_id"])
    for bank_id, qids in by_bank.items():
        entry = registry.entries[bank_id]
        mapping = QUESTION_TYPE_MAPPING.get(entry.subject_id, {})
        try:
            conn_q = bank_repo.get_connection(str(entry.path))
            unique_ids = sorted(set(qids))
            placeholders = ",".join("?" * len(unique_ids))
            qrows = conn_q.execute(
                f"SELECT id, question_type_id FROM questions WHERE id IN ({placeholders})",
                unique_ids,
            ).fetchall()
            for qr in qrows:
                qtype_by_key[(bank_id, qr["id"])] = mapping.get(qr["question_type_id"])
        except sqlite3.Error:
            pass

    # tags 库与用户库各复用一条连接贯穿循环，try/finally 确保关闭
    tags_conn = None
    uconn = None
    by_type: dict[tuple, list[int]] = {}
    by_tag: dict[int, list[int]] = {}
    try:
        try:
            tags_conn = sqlite3.connect(str(settings.data_root / "knowledge_tags.db"))
        except sqlite3.Error:
            tags_conn = None
        try:
            uconn = sqlite3.connect(db)
            uconn.row_factory = sqlite3.Row
        except sqlite3.Error:
            uconn = None

        for r in rows:
            entry = registry.entries.get(r["bank_id"])
            if entry is None:
                continue
            qtype = qtype_by_key.get((r["bank_id"], r["question_id"]))
            key_t = (entry.subject_id, qtype or "UNKNOWN")
            by_type.setdefault(key_t, []).append(r["is_correct"])

            tag_ids: list[int] = []
            if tags_conn is not None:
                try:
                    trows = tags_conn.execute(
                        "SELECT tag_id FROM question_tags WHERE bank_id = ? AND question_id = ?",
                        (r["bank_id"], r["question_id"]),
                    ).fetchall()
                    tag_ids = [t[0] for t in trows]
                except sqlite3.Error:
                    pass
            if uconn is not None:
                try:
                    fb = uconn.execute(
                        "SELECT tag_ids_json FROM ai_feedback WHERE bank_id = ? AND question_id = ? AND tag_ids_json IS NOT NULL "
                        "ORDER BY created_at DESC LIMIT 1",
                        (r["bank_id"], r["question_id"]),
                    ).fetchone()
                    if fb and fb["tag_ids_json"]:
                        tag_ids.extend(int(t) for t in json.loads(fb["tag_ids_json"]))
                except (sqlite3.Error, ValueError, TypeError):
                    pass
            for tid in set(tag_ids):
                by_tag.setdefault(tid, []).append(r["is_correct"])
    finally:
        if tags_conn is not None:
            tags_conn.close()
        if uconn is not None:
            uconn.close()

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
    # 标签名整表查一次，循环内查字典
    tags_db = str(settings.data_root / "knowledge_tags.db")
    name_rows = tags_repo.list_tags_by_subject(tags_db, subject_id or "")
    tag_names = {t["id"]: t["tag_name"] for t in name_rows}
    for tid, results in by_tag.items():
        n = len(results)
        if n >= 5:
            acc = sum(1 for x in results if x == 1) / n
            if acc < 0.6:
                items.append({
                    "dimension": "tag", "subject_id": subject_id, "key": tag_names.get(tid, str(tid)),
                    "attempts": n, "accuracy": round(acc, 4), "wrong_rate": round(1 - acc, 4),
                })

    items.sort(key=lambda x: x["wrong_rate"], reverse=True)
    return envelope({"items": items[:limit]})
