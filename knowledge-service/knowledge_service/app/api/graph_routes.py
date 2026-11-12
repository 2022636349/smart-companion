from typing import Any
from datetime import datetime, timezone
import threading

from fastapi import APIRouter, Depends, HTTPException, Path as ApiPath, Query, Request

from knowledge_service.app.common.security import require_internal_headers
from knowledge_service.app.domain.graph_dto import (
    GraphContextResponse,
    GraphHealthResponse,
    GraphMatchRequest,
    GraphMatchResponse,
    GraphRecommendRequest,
    GraphRecommendationResponse,
    GraphNode,
    GraphNodeListResponse,
    GraphRagContextResponse,
    GraphSearchResponse,
    GraphSourceResponse,
    GraphSubgraphResponse,
    GraphVersionResponse,
    HealthResponse,
)
from knowledge_service.app.domain.ingestion_dto import (
    BatchStatusFilter,
    CandidateTypeFilter,
    DecisionStatusFilter,
    GraphCandidateDecisionRequest,
    GraphCandidatePageResponse,
    GraphIngestionBatchDetail,
    GraphIngestionBatchListResponse,
    GraphIngestionBatchUpsertRequest,
    GraphIngestionBatchUpsertResponse,
    GraphPublishRequest,
    GraphPublishResponse,
    GraphRollbackRequest,
    GraphRollbackResponse,
    ReviewLaneFilter,
)
from knowledge_service.app.services.graph_service import GraphService
from knowledge_service.app.services.ingestion_service import (
    DraftBaseVersionConflict,
    DraftBatchNotFound,
    DraftBudgetExceeded,
    DraftDecisionInvalid,
    DraftIdempotencyConflict,
    DraftNotPublishable,
    DraftPayloadHashMismatch,
    DraftPublishClaimConflict,
    DraftReceiptCapacityExceeded,
    DraftRevisionConflict,
    DraftValidationFailed,
    GraphIngestionService,
    GraphActivationBusy,
    GraphActivationConflict,
    GraphOperationIdempotencyConflict,
    GraphReleaseConflict,
    GraphReleaseNotFound,
    GraphVersionConflict,
)
from knowledge_service.app.repository.neo4j_repo import GraphQueryBudgetExceeded


router = APIRouter(tags=["知识图谱接口"])
internal_router = APIRouter(
    prefix="/internal/graph",
    tags=["知识图谱接口"],
    dependencies=[Depends(require_internal_headers)],
)
graph_service = GraphService.from_environment()
graph_ingestion_service = GraphIngestionService.from_environment(graph_service)
_course_service_lock = threading.RLock()
_course_service_cache: dict[int, tuple[GraphService, GraphIngestionService]] = {}


def _course_services(course_id: int) -> tuple[GraphService, GraphIngestionService]:
    if course_id == 1:
        return graph_service, graph_ingestion_service
    with _course_service_lock:
        if course_id not in _course_service_cache:
            service = GraphService.from_environment(course_id)
            try:
                ingestion = GraphIngestionService.from_environment(service)
            except Exception:
                service.close()
                raise
            _course_service_cache[course_id] = (service, ingestion)
        return _course_service_cache[course_id]


def get_course_graph_service(course_id: int = Query(ge=1)) -> GraphService:
    return _course_services(course_id)[0]


def get_course_ingestion_service(course_id: int = Query(ge=1)) -> GraphIngestionService:
    return _course_services(course_id)[1]


def close_course_services() -> None:
    with _course_service_lock:
        for service, ingestion in _course_service_cache.values():
            ingestion.close()
            service.close()
        _course_service_cache.clear()


def _node_not_found(node_id: str) -> HTTPException:
    return HTTPException(
        status_code=404,
        detail={
            "code": "GRAPH_NODE_NOT_FOUND",
            "owner": "C",
            "message": f"知识点不存在或尚未审核: {node_id}",
        },
    )


def _graph_unavailable() -> HTTPException:
    return HTTPException(
        status_code=503,
        detail={
            "code": "GRAPH_SERVICE_UNAVAILABLE",
            "owner": "C",
            "message": "图谱查询超过安全预算，请缩小范围或稍后重试",
        },
    )


def _require_matching_request_id(request: Request, body_request_id: str) -> None:
    if getattr(request.state, "request_id", None) != body_request_id:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "VALIDATION_ERROR",
                "owner": "C",
                "message": "X-Request-Id 必须与 request_id 一致",
            },
        )


def _ingestion_error(exc: Exception) -> HTTPException:
    errors = getattr(exc, "issues", None)
    if isinstance(exc, DraftBatchNotFound):
        code, status, message = "GRAPH_DRAFT_BATCH_NOT_FOUND", 404, "建图草稿批次不存在"
    elif isinstance(exc, GraphReleaseNotFound):
        code, status, message = "GRAPH_RELEASE_NOT_FOUND", 404, "不可变图谱版本不存在"
    elif isinstance(exc, DraftIdempotencyConflict):
        code, status, message = (
            "GRAPH_DRAFT_IDEMPOTENCY_CONFLICT",
            409,
            "同一幂等标识对应不同请求载荷",
        )
    elif isinstance(exc, DraftRevisionConflict):
        code, status, message = "GRAPH_DRAFT_REVISION_CONFLICT", 409, "草稿批次版本已变化"
    elif isinstance(exc, DraftPublishClaimConflict):
        code, status, message = (
            "GRAPH_DRAFT_REVISION_CONFLICT",
            409,
            "草稿批次正在发布，人工决策已冻结",
        )
    elif isinstance(exc, DraftReceiptCapacityExceeded):
        code, status, message = (
            "GRAPH_DRAFT_BUDGET_EXCEEDED",
            422,
            "草稿审核幂等回执已达到 2048 条上限",
        )
    elif isinstance(exc, GraphActivationBusy):
        code, status, message = (
            "GRAPH_VERSION_CONFLICT",
            409,
            "另一图谱发布或回滚正在进行",
        )
    elif isinstance(exc, GraphActivationConflict):
        code, status, message = "GRAPH_BASE_VERSION_CONFLICT", 409, "正式图谱版本已变化"
    elif isinstance(exc, GraphOperationIdempotencyConflict):
        code, status, message = (
            "GRAPH_VERSION_CONFLICT",
            409,
            "同一 request_id 对应不同的图谱操作",
        )
    elif isinstance(exc, DraftBaseVersionConflict):
        code, status, message = "GRAPH_BASE_VERSION_CONFLICT", 409, "基准图谱版本已变化"
    elif isinstance(exc, DraftNotPublishable):
        code, status, message = (
            "GRAPH_DRAFT_NOT_PUBLISHABLE",
            409,
            "所有候选必须完成人工决策且当前规则校验无 ERROR",
        )
    elif isinstance(exc, GraphVersionConflict):
        code, status, message = "GRAPH_VERSION_CONFLICT", 409, "目标图谱版本冲突"
    elif isinstance(exc, GraphReleaseConflict):
        code, status, message = "GRAPH_RELEASE_IMMUTABLE", 409, "同版本不可变发布物内容冲突"
    elif isinstance(exc, DraftPayloadHashMismatch):
        code, status, message = (
            "GRAPH_DRAFT_VALIDATION_FAILED",
            422,
            str(exc),
        )
    elif isinstance(exc, DraftBudgetExceeded):
        code, status, message = "GRAPH_DRAFT_BUDGET_EXCEEDED", 422, str(exc)
    elif isinstance(exc, DraftValidationFailed):
        code, status, message = "GRAPH_DRAFT_VALIDATION_FAILED", 422, "候选规则校验失败"
    elif isinstance(exc, DraftDecisionInvalid):
        code, status, message = "GRAPH_DRAFT_DECISION_INVALID", 422, str(exc)
    else:
        raise exc
    detail: dict[str, Any] = {"code": code, "owner": "C", "message": message}
    if errors:
        detail["errors"] = errors
    return HTTPException(status_code=status, detail=detail)


@router.get("/health", response_model=HealthResponse, operation_id="internalHealth")
def health_check() -> dict[str, str]:
    return {
        "status": "UP",
        "service": "knowledge-service",
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


@internal_router.get("/health", response_model=GraphHealthResponse, operation_id="graphHealth")
def internal_health() -> dict[str, Any]:
    return graph_service.health()


@internal_router.get(
    "/nodes/{node_id}", response_model=GraphNode, operation_id="internalGetGraphNode"
)
def get_node_detail(node_id: str,
    service: GraphService = Depends(get_course_graph_service),
) -> dict[str, Any]:
    node = service.get_node(node_id)
    if node is None:
        raise _node_not_found(node_id)
    return node


@internal_router.get("/search", response_model=GraphSearchResponse, operation_id="internalSearchGraph")
def graph_search(
    q: str = Query(min_length=1),
    limit: int = Query(default=20, ge=1, le=100),
    service: GraphService = Depends(get_course_graph_service),
) -> dict[str, Any]:
    return {"items": service.search(q, limit), "query": q}


@internal_router.post(
    "/match",
    response_model=GraphMatchResponse,
    operation_id="internalMatchKnowledgePoints",
)
def match_knowledge_points(request: GraphMatchRequest,
    service: GraphService = Depends(get_course_graph_service),
) -> dict[str, Any]:
    try:
        return {
            "items": service.match(
                request.text,
                request.scene,
                request.current_node_id,
                request.limit,
            )
        }
    except GraphQueryBudgetExceeded as exc:
        raise _graph_unavailable() from exc


@internal_router.post(
    "/recommend-next",
    response_model=GraphRecommendationResponse,
    operation_id="internalRecommendNext",
)
def recommend_next(request: GraphRecommendRequest,
    service: GraphService = Depends(get_course_graph_service),
) -> dict[str, Any]:
    # student_id is required by B↔C contract tracing, but C intentionally does
    # not query student data or use it in ranking; only aggregate mastery facts
    # can affect the graph result.
    try:
        return service.recommend_next(
            [item.model_dump() for item in request.mastery], request.limit
        )
    except Exception as exc:
        raise _graph_unavailable() from exc


@internal_router.get(
    "/context/{node_id}", response_model=GraphContextResponse, operation_id="internalGetGraphContext"
)
def get_node_context(node_id: str,
    service: GraphService = Depends(get_course_graph_service),
) -> dict[str, Any]:
    try:
        context = service.context(node_id)
    except GraphQueryBudgetExceeded as exc:
        raise _graph_unavailable() from exc
    if context is None:
        raise _node_not_found(node_id)
    return context


@internal_router.get(
    "/rag-context/{node_id}",
    response_model=GraphRagContextResponse,
    operation_id="internalGetGraphRagContext",
)
def get_graph_rag_context(
    node_id: str,
    max_related: int = Query(default=8, ge=0, le=20),
    service: GraphService = Depends(get_course_graph_service),
) -> dict[str, Any]:
    try:
        context = service.rag_context(node_id, max_related)
    except GraphQueryBudgetExceeded as exc:
        raise _graph_unavailable() from exc
    if context is None:
        raise _node_not_found(node_id)
    return context


@internal_router.get(
    "/prerequisites/{node_id}",
    response_model=GraphNodeListResponse,
    operation_id="internalGetPrerequisites",
)
def get_prerequisites(
    node_id: str,
    max_depth: int = Query(default=5, ge=1, le=10),
    service: GraphService = Depends(get_course_graph_service),
) -> dict[str, Any]:
    try:
        items = service.prerequisites(node_id, max_depth)
    except GraphQueryBudgetExceeded as exc:
        raise _graph_unavailable() from exc
    if items is None:
        raise _node_not_found(node_id)
    return {"items": items}


@internal_router.get(
    "/sources/{node_id}",
    response_model=GraphSourceResponse,
    operation_id="internalGetGraphSources",
)
def get_graph_sources(
    node_id: str,
    approved_only: bool = Query(default=True),
    service: GraphService = Depends(get_course_graph_service),
) -> dict[str, Any]:
    try:
        items = service.sources(node_id, approved_only)
    except GraphQueryBudgetExceeded as exc:
        raise _graph_unavailable() from exc
    if items is None:
        raise _node_not_found(node_id)
    return {"items": items}


@internal_router.get(
    "/version",
    response_model=GraphVersionResponse,
    operation_id="internalGetGraphVersion",
)
def get_graph_version(
    service: GraphService = Depends(get_course_graph_service),
) -> dict[str, Any]:
    return service.version()


@internal_router.post(
    "/ingestion-batches",
    response_model=GraphIngestionBatchUpsertResponse,
    operation_id="internalUpsertGraphIngestionBatch",
)
def upsert_graph_ingestion_batch(
    body: GraphIngestionBatchUpsertRequest,
    request: Request,
    service: GraphIngestionService = Depends(get_course_ingestion_service),
) -> dict[str, Any]:
    _require_matching_request_id(request, body.request_id)
    try:
        batch, duplicate = service.upsert(body)
    except Exception as exc:
        raise _ingestion_error(exc) from exc
    return {"duplicate": duplicate, "batch": batch}


@internal_router.get(
    "/ingestion-batches",
    response_model=GraphIngestionBatchListResponse,
    operation_id="internalListGraphIngestionBatches",
)
def list_graph_ingestion_batches(
    status: BatchStatusFilter | None = Query(default=None),
    limit: int = Query(default=20, ge=1, le=100),
    cursor: str | None = Query(default=None, max_length=200),
    service: GraphIngestionService = Depends(get_course_ingestion_service),
) -> dict[str, Any]:
    try:
        return service.list(status=status, limit=limit, cursor=cursor)
    except Exception as exc:
        raise _ingestion_error(exc) from exc


@internal_router.get(
    "/ingestion-batches/{batch_id}",
    response_model=GraphIngestionBatchDetail,
    operation_id="internalGetGraphIngestionBatch",
)
def get_graph_ingestion_batch(
    batch_id: str = ApiPath(min_length=16, max_length=64, pattern=r"^[A-Za-z0-9._:-]+$"),
    service: GraphIngestionService = Depends(get_course_ingestion_service),
) -> dict[str, Any]:
    try:
        return service.get(batch_id)
    except Exception as exc:
        raise _ingestion_error(exc) from exc


@internal_router.get(
    "/ingestion-batches/{batch_id}/candidates",
    response_model=GraphCandidatePageResponse,
    operation_id="internalListGraphIngestionCandidates",
)
def list_graph_ingestion_candidates(
    batch_id: str = ApiPath(min_length=16, max_length=64, pattern=r"^[A-Za-z0-9._:-]+$"),
    candidate_type: CandidateTypeFilter | None = Query(default=None, alias="type"),
    decision_status: DecisionStatusFilter | None = Query(default=None),
    review_lane: ReviewLaneFilter | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=100),
    cursor: str | None = Query(default=None, max_length=512),
    service: GraphIngestionService = Depends(get_course_ingestion_service),
) -> dict[str, Any]:
    try:
        return service.candidates(
            batch_id,
            candidate_type=candidate_type,
            decision_status=decision_status,
            review_lane=review_lane,
            limit=limit,
            cursor=cursor,
        )
    except Exception as exc:
        raise _ingestion_error(exc) from exc


@internal_router.post(
    "/ingestion-batches/{batch_id}/decisions",
    response_model=GraphIngestionBatchDetail,
    operation_id="internalDecideGraphCandidates",
)
def decide_graph_ingestion_candidates(
    body: GraphCandidateDecisionRequest,
    request: Request,
    batch_id: str = ApiPath(min_length=16, max_length=64, pattern=r"^[A-Za-z0-9._:-]+$"),
    service: GraphIngestionService = Depends(get_course_ingestion_service),
) -> dict[str, Any]:
    _require_matching_request_id(request, body.request_id)
    try:
        return service.decide(batch_id, body)
    except Exception as exc:
        raise _ingestion_error(exc) from exc


@internal_router.post(
    "/ingestion-batches/{batch_id}/publish",
    response_model=GraphPublishResponse,
    operation_id="internalPublishGraphIngestionBatch",
)
def publish_graph_ingestion_batch(
    body: GraphPublishRequest,
    request: Request,
    batch_id: str = ApiPath(min_length=16, max_length=64, pattern=r"^[A-Za-z0-9._:-]+$"),
    service: GraphIngestionService = Depends(get_course_ingestion_service),
) -> dict[str, Any]:
    _require_matching_request_id(request, body.request_id)
    try:
        return service.publish(batch_id, body)
    except (
        DraftBatchNotFound,
        DraftRevisionConflict,
        DraftPublishClaimConflict,
        DraftBaseVersionConflict,
        DraftNotPublishable,
        DraftValidationFailed,
        GraphVersionConflict,
        GraphReleaseConflict,
        GraphReleaseNotFound,
        GraphActivationBusy,
        GraphActivationConflict,
        GraphOperationIdempotencyConflict,
    ) as exc:
        raise _ingestion_error(exc) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "GRAPH_PUBLISH_FAILED",
                "owner": "C",
                "message": "图谱发布尚未最终化；请使用相同 request_id 重试恢复",
            },
        ) from exc


@internal_router.post(
    "/releases/{graph_version}/rollback",
    response_model=GraphRollbackResponse,
    operation_id="internalRollbackGraphRelease",
)
def rollback_graph_release(
    body: GraphRollbackRequest,
    request: Request,
    graph_version: str = ApiPath(
        min_length=1,
        max_length=100,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$",
    ),
    service: GraphIngestionService = Depends(get_course_ingestion_service),
) -> dict[str, Any]:
    _require_matching_request_id(request, body.request_id)
    try:
        return service.rollback(graph_version, body)
    except (
        DraftBaseVersionConflict,
        GraphVersionConflict,
        GraphReleaseConflict,
        GraphReleaseNotFound,
        GraphActivationBusy,
        GraphActivationConflict,
        GraphOperationIdempotencyConflict,
    ) as exc:
        raise _ingestion_error(exc) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "GRAPH_ROLLBACK_FAILED",
                "owner": "C",
                "message": "图谱回滚尚未完成；请使用相同 request_id 重试并核对当前版本",
            },
        ) from exc


@internal_router.get(
    "/subgraph", response_model=GraphSubgraphResponse, operation_id="internalGetSubgraph"
)
def get_subgraph(
    center_id: str | None = Query(default=None, min_length=1),
    depth: int = Query(default=1, ge=1, le=5),
    relations: str | None = Query(default=None),
    service: GraphService = Depends(get_course_graph_service),
) -> dict[str, Any]:
    relation_set = None
    if relations:
        relation_set = {item.strip().lower() for item in relations.split(",") if item.strip()}
    try:
        subgraph = service.subgraph(center_id, depth, relation_set)
    except GraphQueryBudgetExceeded as exc:
        raise _graph_unavailable() from exc
    if subgraph is None:
        raise _node_not_found(center_id or "")
    return subgraph


@internal_router.get("/learning-content/{indicator_id}", operation_id="getFixedCompanionLearningContent")
def get_learning_content(indicator_id: str, course_id: int = Query(ge=1)) -> dict[str, Any]:
    from knowledge_service.app.services.learning_content_service import LearningContentService

    card = LearningContentService().get_card(indicator_id, course_id)
    if card is None:
        raise HTTPException(status_code=404, detail={
            "code": "LEARNING_CONTENT_NOT_FOUND", "owner": "C",
            "message": "当前课程没有该指标的已审核学习内容",
        })
    return card


router.include_router(internal_router)
