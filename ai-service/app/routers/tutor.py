from typing import Annotated, NoReturn

from fastapi import APIRouter, Depends, HTTPException, status

from app.agents.tutor import (
    TutorAgent,
    TutorGraphContextInsufficientError,
    TutorGraphContextInvalidError,
    TutorModelOutputError,
    TutorRequestIdMismatchError,
    create_tutor_agent,
)
from app.clients.knowledge import (
    KnowledgeAuthenticationError,
    KnowledgeInputError,
    KnowledgeNotFoundError,
    KnowledgeRequestError,
    KnowledgeResponseError,
    KnowledgeServerError,
    KnowledgeTimeoutError,
    KnowledgeTransportError,
)
from app.contracts.tutor import TutorChatRequest, TutorChatResponse
from app.core.config import KnowledgeServiceConfigurationError
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


router = APIRouter(prefix="/internal/ai", tags=["tutor-ai"])


@router.post(
    "/tutor/chat",
    operation_id="internalTutorChat",
    response_model=TutorChatResponse,
)
async def tutor_chat(
    request: TutorChatRequest,
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> TutorChatResponse:
    agent: TutorAgent | None = None
    try:
        agent = create_tutor_agent()
        return await agent.chat(request, trace_request_id=trace_request_id)
    except TutorRequestIdMismatchError:
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "TUTOR_REQUEST_ID_MISMATCH",
            "request_id must match X-Request-Id.",
        )
    except KnowledgeInputError:
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "TUTOR_KNOWLEDGE_POINT_ID_INVALID",
            "knowledge_point_id is invalid for a graph lookup.",
        )
    except TutorGraphContextInsufficientError:
        _raise_typed_error(
            status.HTTP_424_FAILED_DEPENDENCY,
            "TUTOR_GRAPH_CONTEXT_INSUFFICIENT",
            "The approved graph context has insufficient facts or traceable sources.",
        )
    except (TutorGraphContextInvalidError, KnowledgeResponseError):
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            "TUTOR_GRAPH_CONTEXT_INVALID",
            "The graph context failed approval or traceability validation.",
        )
    except KnowledgeNotFoundError:
        _raise_typed_error(
            status.HTTP_424_FAILED_DEPENDENCY,
            "TUTOR_GRAPH_CONTEXT_NOT_FOUND",
            "No approved graph context exists for this knowledge point.",
        )
    except KnowledgeTimeoutError:
        _raise_typed_error(
            status.HTTP_504_GATEWAY_TIMEOUT,
            "TUTOR_GRAPH_SERVICE_TIMEOUT",
            "The graph context service timed out.",
        )
    except (
        KnowledgeAuthenticationError,
        KnowledgeServerError,
        KnowledgeTransportError,
        KnowledgeServiceConfigurationError,
    ):
        _raise_typed_error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "TUTOR_GRAPH_SERVICE_UNAVAILABLE",
            "The graph context service is unavailable.",
        )
    except KnowledgeRequestError:
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            "TUTOR_GRAPH_SERVICE_REJECTED_REQUEST",
            "The graph context service rejected the internal request.",
        )
    except ModelRateLimitError:
        _raise_typed_error(
            status.HTTP_429_TOO_MANY_REQUESTS,
            "TUTOR_MODEL_RATE_LIMITED",
            "The generation model is temporarily rate limited.",
        )
    except ModelTimeoutError:
        _raise_typed_error(
            status.HTTP_504_GATEWAY_TIMEOUT,
            "TUTOR_MODEL_TIMEOUT",
            "The generation model timed out.",
        )
    except (
        ModelAuthenticationError,
        ModelConfigurationError,
        ModelServerError,
        ModelTransportError,
    ):
        _raise_typed_error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "TUTOR_MODEL_UNAVAILABLE",
            "The generation model is unavailable.",
        )
    except (TutorModelOutputError, ModelResponseError):
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            "TUTOR_MODEL_INVALID_RESPONSE",
            "The generation model returned an invalid grounded response.",
        )
    except ModelRequestError:
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            "TUTOR_MODEL_REQUEST_REJECTED",
            "The generation model rejected the tutor request.",
        )
    finally:
        if agent is not None:
            await agent.aclose()


def _raise_typed_error(status_code: int, code: str, message: str) -> NoReturn:
    raise HTTPException(
        status_code=status_code,
        detail={"code": code, "owner": "D", "message": message},
    )
