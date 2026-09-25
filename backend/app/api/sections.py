"""题库章节端点：GET /api/question-banks/{bank_id}/sections。

按 category（真题/专题）分组返回题目统计信息。
"""
from __future__ import annotations

import sqlite3

from fastapi import APIRouter, Depends

from app.api.deps import get_current_user
from app.core.bank_registry import QUESTION_TYPE_MAPPING, get_registry
from app.core.config import get_settings
from app.core.errors import NotFoundError
from app.repositories import bank_repo
from app.schemas.common import envelope

router = APIRouter(prefix="/api/question-banks", tags=["question-banks"])


@router.get("/{bank_id}/sections")
def get_sections(
    bank_id: str,
    user=Depends(get_current_user),
):
    """获取题库章节统计：按 category 分组返回题目统计数据。"""
    registry = get_registry()
    entry = registry.get(bank_id)
    if entry is None:
        raise NotFoundError(f"question bank '{bank_id}' not found or disabled")

    conn = bank_repo.get_connection(str(entry.path))
    category = _get_category_name(registry, entry)
    user_db = str(get_settings().data_root / "user_data.db")

    sections = _get_grouped_sections(conn, category, entry, user_db, user["id"], bank_id)

    return envelope({
        "bank_id": entry.id,
        "category": category,
        "sections": sections,
    })


def _get_category_name(registry, entry) -> str:
    for subj in registry.subjects_raw:
        if subj["id"] == entry.subject_id:
            for t in subj.get("types", []):
                if t["id"] == entry.type_id:
                    return t["name"]
    return entry.type_id



def _get_grouped_sections(
    conn: sqlite3.Connection,
    category: str,
    entry,
    user_db: str,
    user_id: int,
    bank_id: str,
) -> list[dict]:
    type_name_map = QUESTION_TYPE_MAPPING.get(entry.subject_id, {})
    is_zhuanti = entry.type_id != "zhenti"

    # M-289：用户库连接只开一次贯穿传入，替代 _counts 每次调用新建连接
    uconn = sqlite3.connect(user_db)
    uconn.row_factory = sqlite3.Row
    try:
        if is_zhuanti:
            return _build_zhuanti(conn, uconn, user_id, bank_id, type_name_map)
        return _build_zhenti(conn, uconn, user_id, bank_id, type_name_map)
    finally:
        uconn.close()


def _build_zhenti(
    conn: sqlite3.Connection,
    uconn: sqlite3.Connection,
    user_id: int,
    bank_id: str,
    type_name_map: dict[int, str],
) -> list[dict]:
    rows = conn.execute(
        """
        SELECT c.year, q.question_type_id, COUNT(*) AS total
        FROM questions q
        JOIN collections c ON c.id = q.collection_id
        GROUP BY c.year, q.question_type_id
        ORDER BY c.year, q.question_type_id
        """
    ).fetchall()

    year_data: dict[int, dict[int, dict]] = {}
    for r in rows:
        year = r["year"]
        type_id = r["question_type_id"]
        year_data.setdefault(year, {})[type_id] = {
            "total": r["total"], "done": 0, "wrong": 0, "favorited": 0,
        }

    if not year_data:
        return []

    # M-288：两个全量查询 + Python 分组，替代原先"每年 2 次查询"的 N+1 循环
    q_all = conn.execute(
        """
        SELECT c.year AS year, q.id, q.question_type_id
        FROM questions q
        JOIN collections c ON c.id = q.collection_id
        """
    ).fetchall()
    c_all = conn.execute("SELECT year, id FROM collections ORDER BY id").fetchall()

    year_qids: dict[int, list[int]] = {}
    year_qtype: dict[int, dict[int, int]] = {}
    for r in q_all:
        year_qids.setdefault(r["year"], []).append(r["id"])
        year_qtype.setdefault(r["year"], {})[r["id"]] = r["question_type_id"]
    year_cids: dict[int, list[int]] = {}
    for r in c_all:
        year_cids.setdefault(r["year"], []).append(r["id"])

    result: list[dict] = []
    for year in sorted(year_data.keys()):
        qids = year_qids.get(year, [])
        qid_type = year_qtype.get(year, {})
        collection_ids = year_cids.get(year, [])

        done = _counts(uconn, user_id, bank_id, qids, "practice_records")
        wrong = _counts(uconn, user_id, bank_id, qids, "wrong_questions")
        favorited = _counts(uconn, user_id, bank_id, qids, "favorite_questions")

        types_list: list[dict] = []
        for type_id in sorted(year_data[year].keys()):
            info = year_data[year][type_id]
            tc = type_name_map.get(type_id, f"UNKNOWN_{type_id}")
            t_info = {
                "type_code": tc, "type_name": tc,
                "total": info["total"], "done": 0, "wrong": 0, "favorited": 0,
            }
            for qid, tid in qid_type.items():
                if tid == type_id:
                    t_info["done"] += done.get(qid, 0)
                    t_info["wrong"] += wrong.get(qid, 0)
                    t_info["favorited"] += favorited.get(qid, 0)
            types_list.append(t_info)
        result.append({"year": year, "types": types_list, "collection_ids": collection_ids})

    return result


def _build_zhuanti(
    conn: sqlite3.Connection,
    uconn: sqlite3.Connection,
    user_id: int,
    bank_id: str,
    type_name_map: dict[int, str],
) -> list[dict]:
    rows = conn.execute(
        """
        SELECT c.title, COUNT(*) AS total
        FROM questions q
        JOIN collections c ON c.id = q.collection_id
        GROUP BY c.title
        ORDER BY c.title
        """
    ).fetchall()

    result: list[dict] = []
    for r in rows:
        title = r["title"]
        qrows = conn.execute(
            """
            SELECT q.id FROM questions q
            JOIN collections c ON c.id = q.collection_id
            WHERE c.title = ?
            """,
            (title,),
        ).fetchall()
        qids = [r["id"] for r in qrows]
        if not qids:
            continue
        collection_ids = [
            c["id"] for c in conn.execute(
                "SELECT DISTINCT c.id FROM collections c WHERE c.title = ? ORDER BY c.id",
                (title,),
            ).fetchall()
        ]
        done = _counts(uconn, user_id, bank_id, qids, "practice_records")
        wrong = _counts(uconn, user_id, bank_id, qids, "wrong_questions")
        favorited = _counts(uconn, user_id, bank_id, qids, "favorite_questions")
        result.append({
            "title": title,
            "total": len(qids),
            "done": sum(done.values()),
            "wrong": sum(wrong.values()),
            "favorited": sum(favorited.values()),
            "collection_ids": collection_ids,
        })
    return result


def _counts(
    conn: sqlite3.Connection, user_id: int, bank_id: str,
    question_ids: list[int], table: str,
) -> dict[int, int]:
    """按题目去重计数（H-92：practice_records 一题可有多行作答流水，
    COUNT(*) 会把"做过"统计成"做题次数"；DISTINCT 后三张表口径一致）。

    M-289：连接由调用方开一次贯穿传入，本函数不再自建连接。
    """
    if not question_ids:
        return {}
    ph = ",".join("?" * len(question_ids))
    rows = conn.execute(
        f"""SELECT DISTINCT question_id
        FROM {table}
        WHERE user_id = ? AND bank_id = ? AND question_id IN ({ph})""",
        [user_id, bank_id] + question_ids,
    ).fetchall()
    return {r["question_id"]: 1 for r in rows}

