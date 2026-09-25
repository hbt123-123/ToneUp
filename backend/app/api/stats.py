"""统计端点（需求文档 §6.6 / §9.4 / §6.11）。

- overview：正确率/刷题量/连续学习天数（按 STATS_TZ 切日）
- weaknesses：学科×题型×知识点聚合；窗口=最近90天或200次先到；
  入选 ≥5 次且正确率 <60%；错误率降序
- daily-trend：近 N 天作答趋势（UTC 日历日分桶，桶内 (bank_id, question_id)
  去重取当天最新判分，空天补零；EC-03 双端图表数据源）
"""
from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import structlog
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
            if banks:
                where.append(f"bank_id IN ({','.join('?' * len(banks))})")
                args.extend(banks)
            else:
                # 学科无注册题库：空 IN () 是非法 SQL，直接短路为空结果
                where.append("1 = 0")
        if d_from:
            # M-290：created_at 存的是 UTC ISO 串，而 from/to 是 stats_tz 语义的日期。
            # 原 substr(created_at,1,10) 与本地日期直接比较会把时区偏移算错
            # （如 UTC+8 的"今天 07:00 前作答"落在 UTC 昨天）。转为 UTC 时间戳边界比较
            tz = ZoneInfo(settings.stats_tz)
            where.append("created_at >= ?")
            args.append(
                datetime.combine(d_from, datetime.min.time(), tzinfo=tz)
                .astimezone(timezone.utc).isoformat()
            )
        if d_to:
            where.append("created_at < ?")
            args.append(
                datetime.combine(d_to + timedelta(days=1), datetime.min.time(), tzinfo=tz)
                .astimezone(timezone.utc).isoformat()
            )
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
            if banks:
                where.append(f"bank_id IN ({','.join('?' * len(banks))})")
                args.extend(banks)
            else:
                # 学科无注册题库：空 IN () 是非法 SQL，直接短路为空结果
                where.append("1 = 0")
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
            # M-291：吞异常前留痕——题库打开/查询失败会让该库题型维度静默缺失
            structlog.get_logger().warning(
                "stats_weaknesses_bank_query_failed", bank_id=bank_id
            )

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
                    # M-291：tags 库查询失败会让该题丢失标签维度聚合
                    structlog.get_logger().warning(
                        "stats_weaknesses_tags_query_failed",
                        bank_id=r["bank_id"], question_id=r["question_id"],
                    )
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
                    # M-291：同上，AI 反馈标签读取失败留痕而非静默
                    structlog.get_logger().warning(
                        "stats_weaknesses_feedback_tags_failed",
                        bank_id=r["bank_id"], question_id=r["question_id"],
                    )
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
    # M-292：subject_id 为 None 时原实现查 subject_id='' 得空表，tag 维度
    # 全部退化为纯数字 key；按有无 subject 分流，None 时取全量标签
    if subject_id is not None:
        name_rows = tags_repo.list_tags_by_subject(tags_db, subject_id)
    else:
        name_rows = tags_repo.list_all_tags(tags_db)
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


@router.get("/daily-trend")
def daily_trend(
    days: int = Query(14, ge=1, le=60),
    user=Depends(get_current_user),
):
    """近 N 天作答趋势（EC-03）。

    分桶口径：`created_at` 存储即 UTC ISO（attempts 写入 `datetime.now(utc).isoformat()`），
    `substr(created_at,1,10)` 即 UTC 日历日；桶内按 (bank_id, question_id) 去重，
    取当天最新一条判分代表该题当天状态；跨天重复作答各天分别计入；
    空天补零（attempts=0, correct_rate=0.0）。correct_rate 与 overview 同为 4 位小数。
    """
    db = _user_db()
    today_utc = datetime.now(timezone.utc).date()
    start_date = today_utc - timedelta(days=days - 1)

    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    try:
        # 窗口函数去重：同 (day, bank_id, question_id) 仅保留当天最新一条
        rows = conn.execute(
            """
            SELECT day, bank_id, question_id, is_correct FROM (
                SELECT substr(created_at, 1, 10) AS day,
                       bank_id, question_id, is_correct,
                       ROW_NUMBER() OVER (
                           PARTITION BY substr(created_at, 1, 10), bank_id, question_id
                           ORDER BY created_at DESC
                       ) AS rn
                FROM practice_records
                WHERE user_id = ? AND created_at >= ?
            ) WHERE rn = 1
            """,
            (user["id"], start_date.isoformat()),
        ).fetchall()
    finally:
        conn.close()

    by_day: dict[str, list[int]] = {}
    for r in rows:
        by_day.setdefault(r["day"], []).append(r["is_correct"])

    points = []
    for offset in range(days):
        day = (start_date + timedelta(days=offset)).isoformat()
        results = by_day.get(day, [])
        correct = sum(1 for x in results if x == 1)
        points.append({
            "date": day,
            "attempts": len(results),
            "correct_rate": round(correct / len(results), 4) if results else 0.0,
        })
    return envelope({"days": days, "points": points})
