from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException

from app.agents.smart_companion import CompanionExplainer
from app.agents.teaching_plan import CompanionPlanner
from app.contracts.teaching_plan import PlanningRequest, PlanningResponse
from app.contracts.smart_companion import ExplainRequest, ExplainResponse
from app.core.security import require_internal_headers
from app.providers.base import ModelProviderError, ModelResponseError

router = APIRouter(prefix='/internal/ai/smart-companion', tags=['smart-companion'])


@router.post('/plan', operation_id='internalSmartCompanionPlan', response_model=PlanningResponse)
async def plan(request: PlanningRequest,
               request_id: Annotated[str, Depends(require_internal_headers)]) -> PlanningResponse:
    try:
        return await CompanionPlanner().plan(request, request_id=request_id)
    except ModelProviderError as exc:
        raise HTTPException(502 if isinstance(exc, ModelResponseError) else 503, detail={
            'code': exc.code, 'message': 'The teaching plan service could not complete the request.'}) from exc


@router.post('/explain', operation_id='internalSmartCompanionExplain', response_model=ExplainResponse)
async def explain(request: ExplainRequest,
                  request_id: Annotated[str, Depends(require_internal_headers)]) -> ExplainResponse:
    try:
        return await CompanionExplainer().explain(request, request_id=request_id)
    except ModelProviderError as exc:
        raise HTTPException(502 if isinstance(exc, ModelResponseError) else 503, detail={
            'code': exc.code, 'message': 'The explanation service could not complete the request.'}) from exc
