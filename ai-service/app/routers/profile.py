from __future__ import annotations

from typing import Annotated, NoReturn

from fastapi import APIRouter, Depends, HTTPException, status

from app.agents.profile import (
    ProfileModelOutputError,
    ProfileRequestIdMismatchError,
    ProfileSummaryAgent,
)
from app.contracts.profile import ProfileSummarizeRequest, ProfileSummarizeResponse
from app.core.security import require_internal_headers
from app.providers.base import (
    ModelAuthenticationError,
    ModelConfigurationError,
    ModelRateLimitError,
    ModelRequestError,
    ModelResponseError,
    ModelServerError,
    ModelTimeoutError,
    ModelTransportError,
)


router = APIRouter(prefix="/internal/ai", tags=["learning-ai"])


@router.post(
    "/profile/summarize",
    operation_id="summarizeLearningProfile",
    response_model=ProfileSummarizeResponse,
    response_model_exclude_none=True,
)
async def summarize_profile(
    request: ProfileSummarizeRequest,
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> ProfileSummarizeResponse:
    try:
        return await ProfileSummaryAgent().summarize(
            request, trace_request_id=trace_request_id
        )
    except ProfileRequestIdMismatchError:
        _raise(status.HTTP_422_UNPROCESSABLE_CONTENT, "PROFILE_REQUEST_ID_MISMATCH", "request_id must match X-Request-Id.")
    except ModelRateLimitError:
        _raise(status.HTTP_429_TOO_MANY_REQUESTS, "PROFILE_MODEL_RATE_LIMITED", "The generation model is rate limited.")
    except ModelTimeoutError:
        _raise(status.HTTP_504_GATEWAY_TIMEOUT, "PROFILE_MODEL_TIMEOUT", "The generation model timed out.")
    except (ModelAuthenticationError, ModelConfigurationError, ModelServerError, ModelTransportError):
        _raise(status.HTTP_503_SERVICE_UNAVAILABLE, "PROFILE_MODEL_UNAVAILABLE", "The generation model is unavailable.")
    except (ProfileModelOutputError, ModelResponseError, ModelRequestError):
        _raise(status.HTTP_502_BAD_GATEWAY, "PROFILE_MODEL_INVALID_RESPONSE", "The generation model returned invalid profile candidates.")


def _raise(status_code: int, code: str, message: str) -> NoReturn:
    raise HTTPException(
        status_code=status_code,
        detail={"code": code, "owner": "D", "message": message},
    )
