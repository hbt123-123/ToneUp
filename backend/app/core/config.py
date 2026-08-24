"""Pydantic-settings 配置体系与启动 fail-fast 校验。

- Settings(BaseSettings) 含全部字段，model_config 读取 .env
- get_settings() 带 lru_cache 的模块级访问器
- validate_startup(settings) jwt_secret 缺失或 <32 字符时抛 RuntimeError
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """全局配置快照。

    所有字段均可通过环境变量 .env 覆盖（大小写+下划线均可）。
    ``extra="ignore"`` 意味着未声明的变量会被 silently 忽略。
    """

    data_root: Path = Path("data")
    jwt_secret: str = ""
    jwt_expire_hours: int = 12
    zhipu_api_key: str = ""
    glm_vision_model: str = ""
    glm_text_model: str = ""
    clean_markdown: bool = True
    cors_allow_origins: list[str] = []
    review_retry_hours: int = 4
    ai_sync_max_bytes: int = 1048576
    ai_sync_max_pixels: int = 2048
    upload_retention_days: int | None = None
    grading_concurrency: int = 1
    stats_tz: str = "Asia/Shanghai"
    trusted_proxy_count: int = 0

    # ------------------------------------------------------------------
    # 校验器
    # ------------------------------------------------------------------
    @field_validator("jwt_expire_hours", mode="after")
    @classmethod
    def _jwt_expire_hours_not_exceed_24(cls, v: int) -> int:
        if v > 24:
            raise ValueError("jwt_expire_hours must be <= 24")
        return v

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


# ----------------------------------------------------------------------
# 模块级带缓存的访问器
# ----------------------------------------------------------------------
@lru_cache(maxsize=None)
def get_settings() -> Settings:
    """返回全局 ``Settings`` 实例，结果缓存以避免重复实例化。"""
    return Settings()


# ----------------------------------------------------------------------
# 启动 fail-fast 校验
# ----------------------------------------------------------------------
def validate_startup(settings: Settings | None = None) -> None:
    """在应用启动时调用，确保关键配置已就绪。

    - 当 ``jwt_secret`` 为空或长度不足 32 字符时抛 ``RuntimeError``
    - 否则静默通过
    """
    if settings is None:
        settings = get_settings()
    jwt_secret = settings.jwt_secret
    if not jwt_secret or len(jwt_secret) < 32:
        raise RuntimeError(
            "JWT_SECRET must be set and at least 32 characters"
        )