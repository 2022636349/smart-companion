from __future__ import annotations

import asyncio
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.contracts.graph import GraphNode, GraphRagContext
from app.contracts.memory import MemorySearchHit, MemorySearchRequest
from app.contracts.tutor import RouterToolName, TutorChatRequest, TutorRouterDecision
from app.memory.store import MemoryStoreError
from app.prompts.grading import sanitize_student_material
from app.providers.base import EmbeddingProviderError
from app.services.memory import MemoryDeadlineExceededError, MemoryService


class RouterToolError(RuntimeError):
    code = "TUTOR_ROUTER_TOOL_ERROR"


class RouterToolNotAllowedError(RouterToolError):
    code = "TUTOR_ROUTER_TOOL_NOT_ALLOWED"


class RouterToolArgumentError(RouterToolError):
    code = "TUTOR_ROUTER_TOOL_ARGUMENT_INVALID"


class RouterToolTimeoutError(RouterToolError):
    code = "TUTOR_ROUTER_TOOL_TIMEOUT"


class RouterKnowledgeClient(Protocol):
    async def get_rag_context(
        self, knowledge_point_id: str, *, course_id: int, request_id: str
    ): ...

    async def search_graph(
        self, query: str, *, course_id: int, request_id: str, limit: int = 5
    ): ...

    async def get_prerequisites(
        self,
        knowledge_point_id: str,
        *,
        course_id: int,
        request_id: str,
        max_depth: int = 5,
    ): ...

    async def recommend_next(
        self,
        *,
        course_id: int,
        student_id: int,
        mastery: list[dict[str, object]],
        request_id: str,
        limit: int = 5,
    ): ...


MemoryServiceFactory = Callable[[], MemoryService]


@dataclass(frozen=True, slots=True)
class RouterToolResult:
    tool: RouterToolName
    item_count: int
    observation: dict[str, Any]
    graph_contexts: tuple[GraphRagContext, ...] = ()
    graph_nodes: tuple[GraphNode, ...] = ()
    memory_hits: tuple[MemorySearchHit, ...] = ()
    memory_index_epoch: str | None = None
    memory_rebuild_required: bool = False


class _RagArguments(BaseModel):
    model_config = ConfigDict(extra="forbid")
    knowledge_point_id: str = Field(min_length=1, max_length=64)


class _SearchArguments(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str = Field(min_length=1, max_length=500)
    limit: int = Field(default=5, ge=1, le=20)


class _PrerequisiteArguments(BaseModel):
    model_config = ConfigDict(extra="forbid")
    knowledge_point_id: str = Field(min_length=1, max_length=64)
    max_depth: int = Field(default=5, ge=1, le=10)


class _RecommendArguments(BaseModel):
    model_config = ConfigDict(extra="forbid")
    limit: int = Field(default=5, ge=1, le=20)


class _MemoryArguments(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    query: str | None = Field(default=None, min_length=1, max_length=10_000)
    knowledge_point_id: str | None = Field(default=None, min_length=1, max_length=64)
    limit: int = Field(default=5, ge=1, le=10)
    min_score: float = Field(default=0.25, ge=0, le=1)


class RouterToolRegistry:
    """A closed, read-only tool registry. No dynamic imports or arbitrary calls."""

    ALLOWED_TOOLS: frozenset[str] = frozenset(
        {
            "graph.rag_context",
            "graph.search",
            "graph.prerequisites",
            "graph.recommend_next",
            "memory.search",
        }
    )

    def __init__(
        self,
        knowledge_client: RouterKnowledgeClient,
        *,
        memory_service_factory: MemoryServiceFactory,
        tool_timeout_seconds: float,
    ) -> None:
        self._knowledge_client = knowledge_client
        self._memory_service_factory = memory_service_factory
        self._tool_timeout_seconds = tool_timeout_seconds

    async def execute(
        self,
        decision: TutorRouterDecision,
        request: TutorChatRequest,
        *,
        trace_request_id: str,
    ) -> RouterToolResult:
        if decision.action == "FINAL" or decision.action not in self.ALLOWED_TOOLS:
            raise RouterToolNotAllowedError("The requested router tool is not allowed.")
        try:
            async with asyncio.timeout(self._tool_timeout_seconds):
                if decision.action == "graph.rag_context":
                    return await self._rag(
                        decision.arguments, request.course_id, trace_request_id
                    )
                if decision.action == "graph.search":
                    return await self._search(
                        decision.arguments, request.course_id, trace_request_id
                    )
                if decision.action == "graph.prerequisites":
                    return await self._prerequisites(
                        decision.arguments, request.course_id, trace_request_id
                    )
                if decision.action == "graph.recommend_next":
                    return await self._recommend(
                        decision.arguments, request, trace_request_id
                    )
                if decision.action == "memory.search":
                    return await self._memory(
                        decision.arguments, request, trace_request_id
                    )
        except TimeoutError as exc:
            raise RouterToolTimeoutError("The router tool exceeded its deadline.") from exc
        raise RouterToolNotAllowedError("The requested router tool is not allowed.")

    async def _rag(
        self, arguments: dict[str, Any], course_id: int, request_id: str
    ) -> RouterToolResult:
        args = self._arguments(_RagArguments, arguments)
        context = await self._knowledge_client.get_rag_context(
            args.knowledge_point_id,
            course_id=course_id,
            request_id=request_id,
        )
        try:
            context.ensure_approved_and_traceable(args.knowledge_point_id)
            if context.course_id != course_id:
                raise ValueError("The graph context belongs to another course.")
        except ValueError as exc:
            raise RouterToolError("The RAG tool returned unapproved graph data.") from exc
        if context.context_status != "READY" or not context.sources:
            raise RouterToolError("The RAG tool returned insufficient graph data.")
        return RouterToolResult(
            tool="graph.rag_context",
            item_count=1,
            observation={
                "knowledge_point_id": context.knowledge_point.id,
                "name": context.knowledge_point.name,
                "graph_version": context.graph_version,
                "source_count": len(context.sources),
            },
            graph_contexts=(context,),
            graph_nodes=(
                context.knowledge_point,
                *context.prerequisites,
                *context.successors,
                *context.related_nodes,
            ),
        )

    async def _search(
        self, arguments: dict[str, Any], course_id: int, request_id: str
    ) -> RouterToolResult:
        args = self._arguments(_SearchArguments, arguments)
        result = await self._knowledge_client.search_graph(
            args.query,
            course_id=course_id,
            request_id=request_id,
            limit=args.limit,
        )
        try:
            result.ensure_approved_knowledge_points()
        except ValueError as exc:
            raise RouterToolError("The search tool returned unapproved graph data.") from exc
        return RouterToolResult(
            tool="graph.search",
            item_count=len(result.items),
            observation={
                "query": args.query,
                "items": [
                    {"knowledge_point_id": item.id, "name": item.name}
                    for item in result.items
                ],
            },
            graph_nodes=tuple(result.items),
        )

    async def _prerequisites(
        self, arguments: dict[str, Any], course_id: int, request_id: str
    ) -> RouterToolResult:
        args = self._arguments(_PrerequisiteArguments, arguments)
        result = await self._knowledge_client.get_prerequisites(
            args.knowledge_point_id,
            course_id=course_id,
            request_id=request_id,
            max_depth=args.max_depth,
        )
        try:
            result.ensure_approved_knowledge_points()
        except ValueError as exc:
            raise RouterToolError(
                "The prerequisite tool returned unapproved graph data."
            ) from exc
        return RouterToolResult(
            tool="graph.prerequisites",
            item_count=len(result.items),
            observation={
                "knowledge_point_id": args.knowledge_point_id,
                "items": [
                    {"knowledge_point_id": item.id, "name": item.name}
                    for item in result.items
                ],
            },
            graph_nodes=tuple(result.items),
        )

    async def _recommend(
        self,
        arguments: dict[str, Any],
        request: TutorChatRequest,
        request_id: str,
    ) -> RouterToolResult:
        args = self._arguments(_RecommendArguments, arguments)
        if not request.mastery_context:
            raise RouterToolArgumentError(
                "graph.recommend_next requires Spring-provided mastery_context."
            )
        result = await self._knowledge_client.recommend_next(
            course_id=request.course_id,
            student_id=request.user_id,
            mastery=[item.model_dump(mode="python") for item in request.mastery_context],
            request_id=request_id,
            limit=args.limit,
        )
        # GraphRecommendationResponse validates APPROVED knowledge points itself;
        # retain a second explicit boundary for non-conforming fakes/adapters.
        if result.course_id != request.course_id or any(
            item.node.review_status != "APPROVED"
            or item.node.node_type != "knowledge_point"
            for item in result.items
        ):
            raise RouterToolError("Recommendation returned unapproved graph data.")
        return RouterToolResult(
            tool="graph.recommend_next",
            item_count=len(result.items),
            observation={
                "graph_version": result.graph_version,
                "items": [
                    {
                        "knowledge_point_id": item.node.id,
                        "name": item.node.name,
                        "reason": item.reason,
                        "priority": item.priority,
                    }
                    for item in result.items
                ],
            },
            graph_nodes=tuple(item.node for item in result.items),
        )

    async def _memory(
        self,
        arguments: dict[str, Any],
        request: TutorChatRequest,
        request_id: str,
    ) -> RouterToolResult:
        args = self._arguments(_MemoryArguments, arguments)
        if not (
            request.student_context
            and request.student_context.personalization_enabled
        ):
            raise RouterToolArgumentError(
                "memory.search requires explicit personalization permission."
            )
        if (
            args.knowledge_point_id is not None
            and args.knowledge_point_id != request.knowledge_point_id
        ):
            raise RouterToolArgumentError(
                "memory.search cannot leave the current knowledge point scope."
            )
        memory_service = await asyncio.to_thread(self._memory_service_factory)
        try:
            response = await memory_service.search(
                MemorySearchRequest(
                    course_id=request.course_id,
                    request_id=request.request_id,
                    student_id=request.user_id,
                    query=args.query or request.message,
                    knowledge_point_id=request.knowledge_point_id,
                    limit=args.limit,
                    min_score=args.min_score,
                ),
                trace_request_id=request_id,
            )
        except (EmbeddingProviderError, MemoryStoreError, MemoryDeadlineExceededError):
            raise
        if (
            response.student_id != request.user_id
            or response.course_id != request.course_id
            or any(
                item.student_id != request.user_id
                or item.course_id != request.course_id
                for item in response.items
            )
        ):
            raise RouterToolError("Memory search violated student or course isolation.")
        return RouterToolResult(
            tool="memory.search",
            item_count=len(response.items),
            observation={
                "items": [
                    {
                        "memory_type": item.memory_type,
                        "knowledge_point_id": item.knowledge_point_id,
                        "content": sanitize_student_material(item.content),
                        "score": item.score,
                    }
                    for item in response.items
                ]
            },
            memory_hits=tuple(response.items),
            memory_index_epoch=response.memory_index_epoch,
            memory_rebuild_required=response.memory_rebuild_required,
        )

    @staticmethod
    def _arguments(model: type[BaseModel], values: dict[str, Any]):
        try:
            return model.model_validate(values)
        except ValidationError as exc:
            raise RouterToolArgumentError("Router tool arguments are invalid.") from exc
