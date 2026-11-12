from __future__ import annotations

import os
import secrets
from pathlib import Path
from typing import Annotated

from dotenv import load_dotenv
from fastapi import Header, HTTPException, Security, status
from fastapi.security import APIKeyHeader


PROJECT_ROOT = Path(__file__).resolve().parents[3]
load_dotenv(PROJECT_ROOT / ".env")
INTERNAL_TOKEN_HEADER = APIKeyHeader(
    name="X-Internal-Token", scheme_name="internalToken", auto_error=False
)


def require_internal_headers(
    internal_token: Annotated[str | None, Security(INTERNAL_TOKEN_HEADER)],
    request_id: Annotated[
        str,
        Header(
            alias="X-Request-Id",
            min_length=16,
            max_length=64,
            pattern=r"^[A-Za-z0-9._:-]+$",
        ),
    ],
) -> str:
    """Protect every /internal route with the shared B↔C service contract."""
    expected = os.getenv(
        "INTERNAL_SERVICE_TOKEN", "local-development-internal-token-2026"
    )
    if not internal_token or not secrets.compare_digest(internal_token, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "INTERNAL_UNAUTHORIZED", "owner": "C", "message": "内部服务令牌无效"},
        )
    return request_id
