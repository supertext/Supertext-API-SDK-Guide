"""The automatic API key check (spec/api-key-check.json).

Plugins run it on every settings save, when a key from the environment changed and on
"Test connection", and show the result to administrators.
"""

from __future__ import annotations

import hashlib
import math
import re
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Final, Literal, cast

from .errors import ErrorCode, SupertextError

ApiKeyCheckStatus = Literal["ok", "invalid", "missing", "unreachable", "error"]
"""``ok`` (2xx), ``invalid`` (401/403), ``missing`` (no key, nothing sent), ``unreachable`` (no answer, 5xx),
``error`` (anything else)."""

KEY_CHECK_TIMEOUT: Final = 10.0
"""Seconds the key check waits for Supertext (at most; a shorter ``request_timeout`` wins)."""

_PREFIX = re.compile(r"^supertext-auth-key(?:\s+|$)", re.IGNORECASE)


@dataclass(frozen=True)
class ApiKeyCheck:
    """The result of :meth:`supertext.v1.SupertextClient.check_api_key`.

    ``to_dict()`` / ``from_dict()`` use the shared camelCase keys (``checkedAt``, ``httpStatus``,
    ``errorCode``), so the stored form is the same in every Supertext SDK.
    """

    status: ApiKeyCheckStatus
    checked_at: str
    """When the check ran: UTC ISO 8601 without fractions (``2026-10-13T08:00:00Z``)."""
    http_status: int | None = None
    """The HTTP status Supertext answered with; ``None`` without an answer."""
    error_code: str | None = None
    """The body's JSON ``error_code`` (at most 100 characters); ``None`` if none."""
    fingerprint: str = ""
    """SHA-256 of the key (lower-case hex), to notice a changed key. Kept locally, never sent. '' for no key."""

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "checkedAt": self.checked_at,
            "httpStatus": self.http_status,
            "errorCode": self.error_code,
            "fingerprint": self.fingerprint,
        }

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> ApiKeyCheck:
        """Reads the stored form; raises ``ValueError`` without a ``status`` or ``checkedAt`` string."""
        status, checked_at = data.get("status"), data.get("checkedAt")
        if not isinstance(status, str) or not isinstance(checked_at, str):
            raise ValueError("not an API key check")
        http_status = data.get("httpStatus")
        error_code = data.get("errorCode")
        fingerprint = data.get("fingerprint")
        return cls(
            status=cast(ApiKeyCheckStatus, status),
            checked_at=checked_at,
            http_status=http_status if isinstance(http_status, int) and not isinstance(http_status, bool) else None,
            error_code=error_code if isinstance(error_code, str) else None,
            fingerprint=fingerprint if isinstance(fingerprint, str) else "",
        )


def api_key_fingerprint(api_key: str | None) -> str:
    """SHA-256 (lower-case hex) of the key as :func:`supertext.auth_header` would send it; '' for a blank key."""
    key = _PREFIX.sub("", (api_key or "").strip()).strip()
    return hashlib.sha256(key.encode("utf-8")).hexdigest() if key else ""


def api_key_check_from_error(error: BaseException, checked_at: str, fingerprint: str) -> ApiKeyCheck:
    """The check for a failed ``GET features``."""
    if not isinstance(error, SupertextError):
        return ApiKeyCheck("error", checked_at, None, None, fingerprint)
    http_status = error.status
    error_code = error.api_error_code[:100] if error.api_error_code else None
    status: ApiKeyCheckStatus
    if error.code == ErrorCode.MISSING_API_KEY:
        status = "missing"
    elif http_status in (401, 403):
        status = "invalid"
    elif http_status is None or http_status >= 500:
        status = "unreachable"
    else:
        status = "error"
    return ApiKeyCheck(status, checked_at, http_status, error_code, fingerprint)


def iso_seconds(seconds: float) -> str:
    """``2026-10-13T08:00:00Z`` for a Unix time in seconds (fractions dropped)."""
    return datetime.fromtimestamp(math.floor(seconds), tz=UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(value: str) -> float | None:
    """Unix seconds of an ISO 8601 time (``Z`` or an offset; none = UTC); ``None`` if it isn't one."""
    text = value.strip()
    if text.endswith(("Z", "z")):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed.timestamp()
