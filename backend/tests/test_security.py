"""Security module tests — Argon2id password hashing and JWT token operations."""

from datetime import datetime, timezone
import jwt as pyjwt
import pytest

from argon2.exceptions import VerifyMismatchError

from app.core.security import (
    hash_password,
    verify_password,
    create_access_token,
    decode_token,
    TokenError,
)


# ------------------------------------------------------------------
# Test Group 1: hash_password / verify_password
# ------------------------------------------------------------------


def test_hash_verify_same_password_true():
    """hash → verify with the same password should return True."""
    plain = "Str0ngP@ssw0rd!2026"
    hashed = hash_password(plain)
    assert verify_password(plain, hashed) is True


def test_verify_wrong_password_false():
    """verify with a wrong password should return False."""
    plain = "Str0ngP@ssw0rd!2026"
    hashed = hash_password(plain)
    assert verify_password("wrong.password", hashed) is False


def test_verify_invalid_hash_format_false():
    """verify with a non-Argon2 hash string should return False."""
    assert verify_password("anything", "not.a.valid.argon2.hash") is False


# ------------------------------------------------------------------
# Test Group 2: create_access_token / decode_token roundtrip
# ------------------------------------------------------------------


def test_create_decode_roundtrip_payload():
    """create_access_token → decode_token should preserve all payload keys."""
    secret = "this-is-a-32-char-secret-key-for-testing!"  # 41 chars
    token = create_access_token(sub=123, role="user", jwt_secret=secret, expires_hours=1)
    payload = decode_token(token, secret)

    # All four required keys must be present
    assert "sub" in payload
    assert "role" in payload
    assert "exp" in payload
    assert "iat" in payload

    # sub and role must match what was passed
    assert payload["sub"] == "123"
    assert payload["role"] == "user"


def test_create_decode_sub_role_correct():
    """sub and role must be correctly stored and retrieved."""
    secret = "this-is-a-32-char-secret-key-for-testing!"
    token = create_access_token(sub=42, role="admin", jwt_secret=secret, expires_hours=1)
    payload = decode_token(token, secret)
    assert payload["sub"] == "42"
    assert payload["role"] == "admin"


# ------------------------------------------------------------------
# Test Group 3: expired token → TokenError
# ------------------------------------------------------------------


def test_decode_expired_token_raises_token_error(monkeypatch):
    """Manually create a JWT token with an expiration in the past (exp=-1 epoch)
    and verify that decode_token raises TokenError."""
    # Use a very old timestamp so the token is unconditionally expired
    old_time = datetime(2020, 1, 1, tzinfo=timezone.utc)
    # monkeypatch datetime.now used inside create_access_token is not easy,
    # so instead we manually encode a token with a past exp claim.
    secret = "this-is-a-32-char-secret-key-for-testing!"
    token = pyjwt.encode(
        {"sub": "1", "role": "user", "exp": old_time, "iat": old_time},
        secret,
        algorithm="HS256",
    )
    with pytest.raises(TokenError):
        decode_token(token, secret)


# ------------------------------------------------------------------
# Test Group 4: wrong secret → TokenError
# ------------------------------------------------------------------


def test_decode_wrong_secret_raises_token_error(monkeypatch):
    """Decode a token with a wrong secret should raise TokenError."""
    secret = "this-is-a-32-char-secret-key-for-testing!"  # 41 chars
    wrong_secret = "wrong-secret-that-does-not-match-at-all"

    payload = {"sub": "1", "role": "user", "exp": 9999999999, "iat": 1000000000}
    token = pyjwt.encode(payload, secret, algorithm="HS256")

    with pytest.raises(TokenError):
        decode_token(token, wrong_secret)