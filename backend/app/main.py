"""ToneUp FastAPI 应用工厂与入口。

- 导入即调用 setup_logging()
- 导出模块级 app = create_app()
"""

from __future__ import annotations
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.core.config import get_settings, validate_startup
from app.core.errors import register_exception_handlers
from app.core.request_context import add_request_id_middleware
from app.schemas.common import envelope

from .core.logging import logger, setup_logging


# ----------------------------------------------------------------------
# 工厂函数
# ----------------------------------------------------------------------


def create_app() -> FastAPI:
    """创建并配置 FastAPI 应用实例。"""

    # 结构化日志（模块已在 import 时调用，此处再次确保）
    setup_logging()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # 启动时校验关键配置（JWT 不合法直接拒启）
        validate_startup(get_settings())

        # 加载题库索引（校验失败仅禁用单条目）
        from app.core.bank_registry import get_registry

        registry_warnings = get_registry().load(get_settings().data_root)
        for w in registry_warnings:
            logger.warning("registry_warning", detail=w)

        # 启动判分 worker 并恢复积压任务
        from app.services.grading_worker import recover_pending, start_worker, stop_worker

        start_worker()
        user_db = str(get_settings().data_root / "user_data.db")
        try:
            recover_pending(user_db)
        except Exception as exc:
            logger.error("grading_recovery_failed", error=str(exc))

        yield

        stop_worker()

    # lifespan 必须在构造时传入，事后赋值不生效
    app = FastAPI(title="ToneUp API", lifespan=lifespan)

    # --- CORS 中间件 ---
    app.add_middleware(
        CORSMiddleware,
        allow_origins=get_settings().cors_allow_origins,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # --- request_id 中间件 ---
    add_request_id_middleware(app)

    # --- 兜底限流（240/min/IP，注册在 request_id 之后=更内层，响应体可带 request_id）---
    from app.core.ratelimit import DefaultRateLimitMiddleware

    app.add_middleware(DefaultRateLimitMiddleware)

    # --- 全局异常处理器注册 ---
    register_exception_handlers(app)

    # --- 路由注册 ---
    from app.api import admin, ai_feedback, attempts, auth, catalog, images, notes, question_banks, reviews, stats

    app.include_router(auth.router)
    app.include_router(catalog.router)
    app.include_router(question_banks.router)
    app.include_router(images.router)
    app.include_router(attempts.router)
    app.include_router(notes.router)
    app.include_router(reviews.router)
    app.include_router(stats.router)
    app.include_router(ai_feedback.router)
    app.include_router(admin.router)

    # --- 路由 ---
    @app.get("/api/health", include_in_schema=False)
    def health() -> dict:
        return envelope({"status": "ok"})

    return app


# ----------------------------------------------------------------------
# 模块级应用实例（直接运行 uvicorn 时使用）
# ----------------------------------------------------------------------
app = create_app()