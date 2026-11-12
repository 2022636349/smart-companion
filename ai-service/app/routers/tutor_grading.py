from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException

from app.agents.tutor_grading import TutorShortAnswerGrader
from app.contracts.tutor_grading import TutorQuizGradeRequest, TutorQuizGradeResponse
from app.core.security import require_internal_headers
from app.providers.base import ModelProviderError, ModelRateLimitError, ModelResponseError, ModelTimeoutError

router = APIRouter(prefix="/internal/ai/tutor", tags=["tutor-ai"])


@router.post("/quiz/suggest-grade", operation_id="internalTutorQuizSuggestGrade", response_model=TutorQuizGradeResponse)
async def suggest_quiz_grade(
    request: TutorQuizGradeRequest,
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> TutorQuizGradeResponse:
    if request.request_id != trace_request_id:
        raise HTTPException(422, detail={"code": "TUTOR_REQUEST_ID_MISMATCH", "message": "request_id must match X-Request-Id."})
    try:
        return await TutorShortAnswerGrader().grade(request)
    except ModelProviderError as exc:
        status = (429 if isinstance(exc, ModelRateLimitError) else 504 if isinstance(exc, ModelTimeoutError)
                  else 502 if isinstance(exc, ModelResponseError) else 503)
        raise HTTPException(status, detail={"code": exc.code, "message": "The short-answer grading service could not complete the request."}) from exc
