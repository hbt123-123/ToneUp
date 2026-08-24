"""Password hashing (Argon2id) and JWT token utilities.

- Password hashing uses Argon2id with OWASP-recommended parameters adapted
  for small-memory machines: memory_cost=19456 KiB, time_cost=2, parallelism=1.
- JWT tokens use HS256 with a provided secret; payload contains sub, role, exp, iat.
- Token decode errors are unified into a single TokenError(Exception) so the
  API layer can map 401 semantics consistently.
"""

from __future__ import annotations

import time
from datetime import datetime, timezone, timedelta

from argon2 import PasswordHasher as _Argon2PasswordHasher
from argon2.exceptions import VerifyMismatchError, InvalidHashError
import jwt

from app.core.config import Settings, get_settings


# ----------------------------------------------------------------------
# Argon2id password hasher (OWASP-adapted for small-memory machines)
# ----------------------------------------------------------------------
# memory_cost=19456 KiB (19 MiB), time_cost=2, parallelism=1
# These parameters lower the memory footprint at the cost of increased time,
# making the scheme viable on constrained environments (e.g. embedded, edge).
# ----------------------------------------------------------------------
_password_hasher = _Argon2PasswordHasher(
    memory_cost=19456,
    time_cost=2,
    parallelism=1,
)


def hash_password(plain: str) -> str:
    """Hash a plain-text password using Argon2id.

    Returns theargon2 hash string (includes version, parameters, salt, hash).
    """
    return _password_hasher.hash(plain)


def verify_password(plain: str, hashed: str) -> bool:
    """Verify a plain-text password against an Argon2 hash.

    Returns True if the password matches, False otherwise.
    All Argon2 verification errors (mismatch, invalid hash format) are
    caught and result in False — the caller should not treat these as
    security events; a simple auth-failure is sufficient.
    """
    try:
        _password_hasher.verify(hashed, plain)
        return True
    except (VerifyMismatchError, InvalidHashError):
        return False


# ----------------------------------------------------------------------
# JWT access token creation / decoding
# ----------------------------------------------------------------------

class TokenError(Exception):
    """Raised when a JWT token cannot be decoded/verified.

    The API layer should map this to a 401 Unauthorized response.
    """


def create_access_token(
    sub: int,
    role: str,
    jwt_secret: str,
    expires_hours: int,
) -> str:
    """Create a JWT access token (HS256).

    Payload claims:
        sub   — subject identifier (int, stored as str)
        role  — user role string
        exp   — expiration datetime (UTC), issued as iat + expires_hours
        iat   — issued-at timestamp (UTC, now)

    The token is encoded with HS256 using the provided ``jwt_secret``.
    """
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(sub),
        "role": role,
        "exp": now + timedelta(hours=expires_hours),
        "iat": now,
    }
    return jwt.encode(payload, jwt_secret, algorithm="HS256")


def decode_token(token: str, jwt_secret: str) -> dict:
    """Decode a JWT access token and return its payload.

    Raises ``TokenError`` (subclass of ``Exception``) when the token is
    expired, malformed, or signed with a wrong secret.  All internal
    exceptions (ExpiredSignatureError, InvalidTokenError, etc.) are
    unified so the API layer can map a single 401 response.

    Args:
        token: JWT string fresh from ``create_access_token``.
        jwt_secret: The same secret used to sign the token (from settings).

    Returns:
        The decoded payload dict.

    Raises:
        TokenError: if the token is invalid, expired, or signed with a
                    different secret.
    """
    try:
        payload = jwt.decode(token, jwt_secret, algorithms=["HS256"])
        return payload
    except (
        jwt.ExpiredSignatureError,
        jwt.InvalidTokenError,
        # Pydantic-internal exceptions that may surface via jwt-lib
        # edge versions; catch BaseException subset safely.
        Exception,
    ):
        raise TokenError("Invalid or expired token") from None