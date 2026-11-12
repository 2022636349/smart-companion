from typing import Annotated, NoReturn

from fastapi import APIRouter, Depends, HTTPException, status

from app.agents.assessment import (
    AssessmentParseAgent,
    AssessmentParseDeadlineExceededError,
    AssessmentParseModelOutputError,
    AssessmentParseRequestIdMismatchError,
    create_assessment_parse_agent,
)
from app.agents.grading import (
    GradingAgent,
    GradingAgentError,
    GradingDeadlineExceededError,
    GradingModelOutputError,
    GradingRequestIdMismatchError,
    GradingSensitiveRubricError,
    create_grading_agent,
)
from app.clients.grading_attachment import (
    GradingAttachmentAuthenticationError,
    GradingAttachmentInputError,
    GradingAttachmentNotFoundError,
    GradingAttachmentRequestError,
    GradingAttachmentServerError,
    GradingAttachmentTimeoutError,
    GradingAttachmentTooLargeError,
    GradingAttachmentTransportError,
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
from app.contracts.assessment import (
    AttachmentGradeRequest,
    AttachmentGradeResponse,
    AssessmentParseRequest,
    AssessmentParseResponse,
    SubjectiveGradeRequest,
)
from app.core.config import (
    AssessmentParseConfigurationError,
    AssessmentSourceConfigurationError,
    GradingConfigurationError,
)
from app.services.grading_extraction import (
    GradingExtractionInvalidError,
    GradingExtractionTooLargeError,
    GradingExtractionUnsupportedError,
    GradingExtractionWorkerError,
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


router = APIRouter(
    prefix="/internal/ai",
    tags=["assessment-ai"],
)


@router.post(
    "/assessment/parse",
    operation_id="parseAssessmentFile",
    response_model=AssessmentParseResponse,
    response_model_exclude_none=True,
)
async def parse_assessment(
    request: AssessmentParseRequest,
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> AssessmentParseResponse:
    agent: AssessmentParseAgent | None = None
    try:
        agent = create_assessment_parse_agent()
        return await agent.parse(request, trace_request_id=trace_request_id)
    except AssessmentParseRequestIdMismatchError:
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "ASSESSMENT_PARSE_REQUEST_ID_MISMATCH",
            "request_id must match X-Request-Id.",
            trace_request_id,
        )
    except AssessmentDocumentUnsupportedError:
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "ASSESSMENT_PARSE_UNSUPPORTED_FILE_TYPE",
            "Only TXT, Markdown, DOCX and PDF assessment files are supported.",
            trace_request_id,
        )
    except (AssessmentSourceTooLargeError, AssessmentDocumentTooLargeError):
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "ASSESSMENT_PARSE_FILE_TOO_LARGE",
            "The assessment source exceeds the configured parsing limit.",
            trace_request_id,
        )
    except (
        AssessmentSourceInputError,
        AssessmentSourceNotFoundError,
        AssessmentDocumentInvalidError,
    ):
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "ASSESSMENT_PARSE_INVALID_SOURCE",
            "The assessment source is missing, invalid or has no extractable text.",
            trace_request_id,
        )
    except AssessmentSourceTimeoutError:
        _raise_typed_error(
            status.HTTP_504_GATEWAY_TIMEOUT,
            "ASSESSMENT_PARSE_SOURCE_TIMEOUT",
            "The Spring assessment source download timed out.",
            trace_request_id,
        )
    except AssessmentParseDeadlineExceededError:
        _raise_typed_error(
            status.HTTP_504_GATEWAY_TIMEOUT,
            "ASSESSMENT_PARSE_TOTAL_TIMEOUT",
            "Assessment parsing exceeded the configured total timeout.",
            trace_request_id,
        )
    except AssessmentDocumentWorkerError:
        _raise_typed_error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "ASSESSMENT_PARSE_WORKER_UNAVAILABLE",
            "The isolated assessment document worker is unavailable.",
            trace_request_id,
        )
    except AssessmentParseConfigurationError:
        _raise_typed_error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "ASSESSMENT_PARSE_CONFIGURATION_INVALID",
            "Assessment parsing is unavailable because its configuration is invalid.",
            trace_request_id,
        )
    except (
        AssessmentSourceAuthenticationError,
        AssessmentSourceServerError,
        AssessmentSourceTransportError,
        AssessmentSourceConfigurationError,
    ):
        _raise_typed_error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "ASSESSMENT_PARSE_SOURCE_UNAVAILABLE",
            "The Spring assessment source is unavailable.",
            trace_request_id,
        )
    except AssessmentSourceRequestError:
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            "ASSESSMENT_PARSE_SOURCE_REJECTED",
            "Spring rejected the internal assessment source request.",
            trace_request_id,
        )
    except ModelRateLimitError:
        _raise_typed_error(
            status.HTTP_429_TOO_MANY_REQUESTS,
            "ASSESSMENT_PARSE_MODEL_RATE_LIMITED",
            "The generation model is temporarily rate limited.",
            trace_request_id,
        )
    except ModelTimeoutError:
        _raise_typed_error(
            status.HTTP_504_GATEWAY_TIMEOUT,
            "ASSESSMENT_PARSE_MODEL_TIMEOUT",
            "The generation model timed out.",
            trace_request_id,
        )
    except (
        ModelAuthenticationError,
        ModelConfigurationError,
        ModelServerError,
        ModelTransportError,
    ):
        _raise_typed_error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "ASSESSMENT_PARSE_MODEL_UNAVAILABLE",
            "The generation model is unavailable.",
            trace_request_id,
        )
    except (AssessmentParseModelOutputError, ModelResponseError):
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            "ASSESSMENT_PARSE_MODEL_INVALID_RESPONSE",
            "The generation model returned an invalid assessment draft.",
            trace_request_id,
        )
    except ModelRequestError:
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            "ASSESSMENT_PARSE_MODEL_REQUEST_REJECTED",
            "The generation model rejected the assessment parse request.",
            trace_request_id,
        )
    finally:
        if agent is not None:
            await agent.aclose()


@router.post(
    "/attachment/grade",
    operation_id="gradeAttachment",
    response_model=AttachmentGradeResponse,
)
async def grade_attachment(
    request: AttachmentGradeRequest,
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> AttachmentGradeResponse:
    return await _run_grading(
        request,
        trace_request_id=trace_request_id,
        with_attachments=True,
        operation="ATTACHMENT_GRADING",
    )


@router.post(
    "/subjective/suggest-grade",
    operation_id="suggestSubjectiveGrade",
    response_model=AttachmentGradeResponse,
)
async def suggest_subjective_grade(
    request: SubjectiveGradeRequest,
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> AttachmentGradeResponse:
    return await _run_grading(
        request,
        trace_request_id=trace_request_id,
        with_attachments=False,
        operation="SUBJECTIVE_GRADING",
    )


async def _run_grading(
    request: AttachmentGradeRequest | SubjectiveGradeRequest,
    *,
    trace_request_id: str,
    with_attachments: bool,
    operation: str,
) -> AttachmentGradeResponse:
    agent: GradingAgent | None = None
    try:
        agent = create_grading_agent(with_attachments=with_attachments)
        if isinstance(request, AttachmentGradeRequest):
            return await agent.grade_attachment(
                request,
                trace_request_id=trace_request_id,
            )
        return await agent.suggest_subjective(
            request,
            trace_request_id=trace_request_id,
        )
    except GradingRequestIdMismatchError:
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            f"{operation}_REQUEST_ID_MISMATCH",
            "request_id must match X-Request-Id.",
            trace_request_id,
        )
    except (
        GradingSensitiveRubricError,
        GradingAttachmentInputError,
        GradingAttachmentNotFoundError,
        GradingExtractionInvalidError,
        GradingExtractionUnsupportedError,
        GradingExtractionTooLargeError,
        GradingAttachmentTooLargeError,
    ):
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            f"{operation}_INVALID_MATERIAL",
            "The grading material is invalid, unsupported or exceeds a safety limit; manual grading remains available.",
            trace_request_id,
        )
    except (
        GradingDeadlineExceededError,
        GradingAttachmentTimeoutError,
        ModelTimeoutError,
    ):
        _raise_typed_error(
            status.HTTP_504_GATEWAY_TIMEOUT,
            f"{operation}_TIMEOUT",
            "AI grading exceeded its configured deadline; manual grading remains available.",
            trace_request_id,
        )
    except ModelRateLimitError:
        _raise_typed_error(
            status.HTTP_429_TOO_MANY_REQUESTS,
            f"{operation}_MODEL_RATE_LIMITED",
            "The generation model is temporarily rate limited; manual grading remains available.",
            trace_request_id,
        )
    except (
        GradingAttachmentAuthenticationError,
        GradingAttachmentServerError,
        GradingAttachmentTransportError,
        GradingExtractionWorkerError,
        GradingConfigurationError,
        AssessmentSourceConfigurationError,
        ModelAuthenticationError,
        ModelConfigurationError,
        ModelServerError,
        ModelTransportError,
    ):
        _raise_typed_error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            f"{operation}_UNAVAILABLE",
            "AI grading is temporarily unavailable; manual grading remains available.",
            trace_request_id,
        )
    except (GradingModelOutputError, ModelResponseError):
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            f"{operation}_MODEL_INVALID_RESPONSE",
            "The model returned an invalid grading suggestion; manual grading remains available.",
            trace_request_id,
        )
    except (GradingAttachmentRequestError, ModelRequestError, GradingAgentError):
        _raise_typed_error(
            status.HTTP_502_BAD_GATEWAY,
            f"{operation}_REQUEST_REJECTED",
            "A grading dependency rejected the request; manual grading remains available.",
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
