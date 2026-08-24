"""管理三端点（需求文档 §6.9 / D4）：role=admin 守卫 + 审计日志。"""
from __future__ import annotations

import threading
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Query, Request

from app.api.deps import require_admin
from app.core.bank_registry import get_registry
from app.core.config import get_settings
from app.core.errors import NotFoundError
from app.repositories import bank_repo
from app.schemas.common import envelope

router = APIRouter(prefix="/api/admin", tags=["admin"])

_health_tasks: dict[str, dict] = {}
_health_lock = threading.Lock()


def _audit(request: Request, user, action: str, detail: str = "") -> None:
    """审计日志：操作者/时间/来源 IP/request_id（structlog JSON）。"""
    import structlog

    structlog.get_logger().info(
        "admin_audit",
        action=action,
        operator=user["id"],
        operator_name=user["username"],
        client_ip=request.client.host if request.client else "unknown",
        request_id=request.headers.get("X-Request-ID", ""),
        detail=detail,
    )


@router.post("/catalog/reload")
def catalog_reload(request: Request, user=Depends(require_admin)):
    """重载 manifest：校验通过原子替换；失败保持旧索引。"""
    from app.core.bank_registry import BankRegistry

    settings = get_settings()
    new_registry = BankRegistry()
    try:
        warnings = new_registry.load(settings.data_root)
    except Exception as exc:
        _audit(request, user, "catalog_reload_failed", str(exc)[:200])
        return envelope(
            {"reloaded": False, "error": str(exc)[:300]},
            message="reload failed; old index kept", success=False,
        )
    import app.core.bank_registry as br

    br._registry = new_registry
    bank_repo.close_all_connections()
    enabled = sum(1 for e in new_registry.entries.values() if e.enabled)
    _audit(request, user, "catalog_reload", f"enabled={enabled} warnings={len(warnings)}")
    return envelope({
        "reloaded": True,
        "banks_enabled": enabled,
        "banks_total": len(new_registry.entries),
        "warnings": warnings,
    })


def _run_health_check(task_id: str, data_root, bank_id: str | None) -> None:
    """后台健康检查：表结构/JSON 合法性/MIME/解析完整性。"""
    registry = get_registry()
    results = []
    entries = list(registry.entries.values())
    if bank_id:
        entries = [e for e in entries if e.id == bank_id]
    for entry in entries:
        issues: list[str] = []
        if not entry.enabled:
            issues.append("disabled by registry")
        else:
            try:
                conn = bank_repo.get_connection(str(entry.path))
                tables = {
                    r[0] for r in conn.execute(
                        "SELECT name FROM sqlite_master WHERE type='table'"
                    ).fetchall()
                }
                for required in ("collections", "questions", "images"):
                    if required not in tables:
                        issues.append(f"missing table {required}")
                bad_json = conn.execute(
                    "SELECT COUNT(*) FROM questions WHERE options IS NOT NULL AND options != '' "
                    "AND json_valid(options) = 0"
                ).fetchone()[0]
                if bad_json:
                    issues.append(f"{bad_json} rows with invalid options JSON")
                bad_mime = conn.execute(
                    "SELECT COUNT(*) FROM images WHERE mime IS NULL OR mime NOT LIKE 'image/%'"
                ).fetchone()[0]
                if bad_mime:
                    issues.append(f"{bad_mime} images with invalid mime")
            except Exception as exc:
                issues.append(f"open failed: {exc}")
        results.append({"bank_id": entry.id, "ok": not issues, "issues": issues})

    with _health_lock:
        task = _health_tasks.get(task_id)
        if task is not None:
            task["status"] = "done"
            task["results"] = results
            task["finished_at"] = datetime.now(timezone.utc).isoformat()


@router.get("/health")
def health(request: Request, bank_id: str | None = Query(None), user=Depends(require_admin)):
    """健康检查：即时结论 + 任务 id（大库走异步线程）。"""
    settings = get_settings()
    task_id = uuid.uuid4().hex
    with _health_lock:
        _health_tasks[task_id] = {"status": "running", "results": None,
                                  "created_at": datetime.now(timezone.utc).isoformat()}
    t = threading.Thread(
        target=_run_health_check, args=(task_id, settings.data_root, bank_id), daemon=True
    )
    t.start()
    t.join(timeout=5)
    with _health_lock:
        snapshot = dict(_health_tasks[task_id])
    _audit(request, user, "health_check", f"bank_id={bank_id}")
    return envelope({"task_id": task_id, **snapshot})


@router.get("/health/{task_id}")
def health_result(task_id: str, user=Depends(require_admin)):
    """异步检查结果；进程内存活，重启后旧 task_id 404 属预期。"""
    with _health_lock:
        task = _health_tasks.get(task_id)
    if task is None:
        raise NotFoundError("health task not found (may have been cleared by restart)")
    return envelope({"task_id": task_id, **task})
