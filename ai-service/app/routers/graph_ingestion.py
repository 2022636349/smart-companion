from typing import Annotated, NoReturn

from fastapi import APIRouter, Depends, HTTPException, status

from app.agents.graph_ingestion import (
    GraphExtractorOutputError,
    GraphIngestionAgent,
    GraphIngestionAgentError,
    GraphIngestionDeadlineExceededError,
    GraphIngestionRequestIdMismatchError,
    GraphReviewerOutputError,
    create_graph_ingestion_agent,
)
from app.clients.assessment_source import (
    AssessmentSourceAuthenticationError,
    AssessmentSourceInputError,
    AssessmentSourceNotFoundError,
    AssessmentSourceRequestError,
    AssessmentSourceServerError,
    AssessmentSourceTimeoutError,
    AssessmentSourceTooLargeError,
    AssessmentSourceTransportError,
)
from app.contracts.graph_ingestion import (
    GraphExtractReviewRequest,
    GraphExtractReviewResponse,
)
from app.core.config import (
    AssessmentSourceConfigurationError,
    GraphIngestionConfigurationError,
)
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
from app.services.document_extraction import (
    AssessmentDocumentInvalidError,
    AssessmentDocumentTooLargeError,
    AssessmentDocumentUnsupportedError,
    AssessmentDocumentWorkerError,
)


router = APIRouter(prefix="/internal/ai/graph", tags=["graph-ingestion-ai"])


@router.post(
    "/extract-review",
    operation_id="extractAndReviewGraphCandidates",
    response_model=GraphExtractReviewResponse,
)
async def extract_and_review_graph_candidates(
    request: GraphExtractReviewRequest,
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> GraphExtractReviewResponse:
    agent: GraphIngestionAgent | None = None
    try:
        agent = create_graph_ingestion_agent()
        return await agent.extract_and_review(
            request,
            trace_request_id=trace_request_id,
        )
    except GraphIngestionRequestIdMismatchError:
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "GRAPH_INGESTION_REQUEST_ID_MISMATCH",
            "request_id must match X-Request-Id.",
            trace_request_id,
        )
    except (
        AssessmentSourceInputError,
        AssessmentSourceNotFoundError,
        AssessmentSourceTooLargeError,
        AssessmentDocumentInvalidError,
        AssessmentDocumentTooLargeError,
        AssessmentDocumentUnsupportedError,
    ) as exc:
        code = (
            "GRAPH_INGESTION_DOCUMENT_UNSUPPORTED"
            if isinstance(exc, AssessmentDocumentUnsupportedError)
            else "GRAPH_INGESTION_LIMIT_EXCEEDED"
            if isinstance(exc, (AssessmentSourceTooLargeError, AssessmentDocumentTooLargeError))
            else "GRAPH_INGESTION_SOURCE_INVALID"
        )
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            code,
            "The graph source is invalid, unsupported, empty, or exceeds a safety limit.",
            trace_request_id,
        )
    except (GraphIngestionDeadlineExceededError, AssessmentSourceTimeoutError, ModelTimeoutError):
        _raise_typed_error(
            status.HTTP_504_GATEWAY_TIMEOUT,
            "GRAPH_INGESTION_TIMEOUT",
            "Graph extraction and review exceeded its configured deadline.",
            trace_request_id,
        )
    except ModelRateLimitError:
        _raise_typed_error(
            status.HTTP_429_TOO_MANY_REQUESTS,
            "GRAPH_INGESTION_MODEL_RATE_LIMITED",
            "The generation model is temporarily rate limited.",
            trace_request_id,
        )
    except GraphExtractorOutputError:
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            "GRAPH_EXTRACTOR_INVALID_RESPONSE",
            "The extractor returned invalid or untraceable candidates.",
            trace_request_id,
        )
    except GraphReviewerOutputError:
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            "GRAPH_REVIEWER_INVALID_RESPONSE",
            "The reviewer returned invalid or incomplete decisions.",
            trace_request_id,
        )
    except (
        AssessmentSourceAuthenticationError,
        AssessmentSourceServerError,
        AssessmentSourceTransportError,
        AssessmentDocumentWorkerError,
        AssessmentSourceConfigurationError,
        GraphIngestionConfigurationError,
        ModelAuthenticationError,
        ModelConfigurationError,
        ModelServerError,
        ModelTransportError,
    ):
        _raise_typed_error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "GRAPH_INGESTION_UNAVAILABLE",
            "Graph extraction and review is temporarily unavailable.",
            trace_request_id,
        )
    except (AssessmentSourceRequestError, ModelRequestError, ModelResponseError, GraphIngestionAgentError):
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            "GRAPH_INGESTION_DEPENDENCY_REJECTED",
            "A graph ingestion dependency rejected the request.",
            trace_request_id,
        )
    finally:
        if agent is not None:
            await agent.aclose()


def _raise_typed_error(
    status_code: int,
    code: str,
    message: str,
    request_id: str,
) -> NoReturn:
    raise HTTPException(
        status_code=status_code,
        detail={
            "code": code,
            "owner": "D",
            "message": message,
            "request_id": request_id,
        },
    )

