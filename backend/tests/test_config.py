"""Config unit tests — environment validation & pydantic field checks."""
import pytest

from app.core.config import Settings, get_settings, validate_startup


# ------------------------------------------------------------------ #
# Test 1 — JWT_SECRET 未设置（空）→ validate_startup() 抛 RuntimeError
# ------------------------------------------------------------------ #
def test_validate_startup_when_jwt_secret_missing_raises_runtimeerror():
    """JWT_SECRET 缺失或空时，启动校验应抛 RuntimeError。"""
    settings = Settings(jwt_secret="")
    with pytest.raises(
        RuntimeError,
        match="JWT_SECRET must be set and at least 32 characters",
    ):
        validate_startup(settings)


# ------------------------------------------------------------------ #
# Test 2 — JWT_SECRET 设为 31 字符 → RuntimeError
# ------------------------------------------------------------------ #
def test_validate_startup_when_jwt_secret_31chars_raises_runtimeerror(monkeypatch):
    """JWT_SECRET 31 字符时，启动校验应抛 RuntimeError。"""
    monkeypatch.setenv("JWT_SECRET", "x" * 31)
    get_settings.cache_clear()
    with pytest.raises(
        RuntimeError,
        match="JWT_SECRET must be set and at least 32 characters",
    ):
        validate_startup()


# ------------------------------------------------------------------ #
# Test 3 — JWT_SECRET 设为 32+ 字符 → validate_startup() 静默通过
# ------------------------------------------------------------------ #
def test_validate_startup_when_jwt_secret_32chars_passes(monkeypatch):
    """JWT_SECRET 32 字符时，启动校验应静默通过（无异常）。"""
    monkeypatch.setenv("JWT_SECRET", "x" * 32)
    get_settings.cache_clear()
    # 应该不抛出任何异常
    validate_startup()


# ------------------------------------------------------------------ #
# Test 4 — Settings 字段校验：jwt_expire_hours > 24 抛 ValidationError
# ------------------------------------------------------------------ #
def test_settings_jwt_expire_hours_25_raises_validationerror(monkeypatch):
    """Settings(jwt_expire_hours=25) 应抛 pydantic ValidationError（>24 校验器生效）。"""
    monkeypatch.delenv("JWT_SECRET", raising=False)
    monkeypatch.delenv("JWT_EXPIRE_HOURS", raising=False)
    get_settings.cache_clear()
    with pytest.raises(
        ValueError, match="jwt_expire_hours must be <= 24"
    ):
        Settings(jwt_expire_hours=25)


def test_settings_jwt_expire_hours_24_passes(monkeypatch):
    """Settings(jwt_expire_hours=24) 应构造成功（校验器通过）。"""
    monkeypatch.setenv("JWT_SECRET", "x" * 32)
    get_settings.cache_clear()
    s = Settings(jwt_expire_hours=24)
    assert s.jwt_expire_hours == 24