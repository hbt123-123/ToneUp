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

from .core.logging import setup_logging


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
        yield

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

    # --- 全局异常处理器注册 ---
    register_exception_handlers(app)

    # --- 路由 ---
    @app.get("/api/health", include_in_schema=False)
    def health() -> dict:
        return envelope({"status": "ok"})

    return app


# ----------------------------------------------------------------------
# 模块级应用实例（直接运行 uvicorn 时使用）
# ----------------------------------------------------------------------
app = create_app()