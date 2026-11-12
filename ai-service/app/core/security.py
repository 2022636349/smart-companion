import os
import secrets
from typing import Annotated

from fastapi import Header, HTTPException, Security, status
from fastapi.security import APIKeyHeader


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
    """Enforce the frozen B-to-D internal service headers."""
    expected = os.getenv(
        "INTERNAL_SERVICE_TOKEN", "local-development-internal-token-2026"
    )
    if not internal_token or not secrets.compare_digest(internal_token, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={
                "code": "INTERNAL_UNAUTHORIZED",
                "owner": "D",
                "message": "Internal token is invalid.",
            },
        )
    return request_id
