from __future__ import annotations

import asyncio
import hashlib
import re
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol

from pydantic import ValidationError

from app.clients.knowledge import KnowledgeServiceClient
from app.contracts.memory import MemorySearchRequest
from app.contracts.graph import GraphRagContext
from app.contracts.common import ContentBlock
from app.contracts.tutor import (
    TutorActivityDraft,
    TutorAgentTrace,
    TutorChatRequest,
    TutorChatResponse,
    TutorModelOutput,
    TutorSpecialistInvocation,
    TutorSource,
    TutorTeachingPlan,
)
from app.prompts.tutor import TUTOR_PROMPT_VERSION, build_tutor_messages
from app.prompts.router import TUTOR_ROUTER_ANSWER_PROMPT_VERSION
from app.core.config import (
    MemoryConfigurationError,
    TutorRouterConfigurationError,
    TutorGenerationSettings,
    TutorRouterSettings,
)
from app.memory.store import MemoryStoreError
from app.providers.base import (
    EmbeddingProviderError,
    ModelProvider,
    ModelProviderError,
    ModelMessage,
    ModelRequest,
    ModelResponse,
    ModelResponseError,
    ModelTimeoutError,
)
from app.providers.factory import create_animation_model_provider, create_model_provider
from app.services.memory import MemoryDeadlineExceededError, MemoryService, create_memory_service
from app.agents.router import RouterExecutionResult, TutorRouter
from app.agents.teaching import (
    ActivitySpecialist,
    SpecialistActivityResult,
    TeachingPlanOutputError,
    TeachingPlanner,
    constrain_teaching_plan,
)
from app.tools.registry import RouterToolRegistry


class TutorAgentError(RuntimeError):
    code = "TUTOR_AGENT_ERROR"


class TutorRequestIdMismatchError(TutorAgentError):
    code = "TUTOR_REQUEST_ID_MISMATCH"


class TutorGraphContextInsufficientError(TutorAgentError):
    code = "TUTOR_GRAPH_CONTEXT_INSUFFICIENT"

    def __init__(self, warnings: list[str]) -> None:
        super().__init__("The approved graph context is insufficient for tutoring.")
        self.warnings = tuple(warnings)


class TutorGraphContextInvalidError(TutorAgentError):
    code = "TUTOR_GRAPH_CONTEXT_INVALID"


class TutorModelOutputError(TutorAgentError):
    code = "TUTOR_MODEL_OUTPUT_INVALID"


class KnowledgeContextClient(Protocol):
    async def get_rag_context(
        self,
        knowledge_point_id: str,
        *,
        course_id: int,
        request_id: str,
    ) -> GraphRagContext: ...

    async def aclose(self) -> None: ...


ProviderFactory = Callable[[], ModelProvider]
MemoryServiceFactory = Callable[[], MemoryService]
MAX_TUTOR_GENERATION_ATTEMPTS = 2
TEACHING_PLANNER_TIMEOUT_SECONDS = 20.0
ACTIVITY_SPECIALIST_TIMEOUT_SECONDS = 60.0


@dataclass(frozen=True, slots=True)
class ExplanationSpecialistResult:
    generated: TutorModelOutput
    sources: tuple[TutorSource, ...]
    model_version: str
    trace: TutorSpecialistInvocation


class TutorAgent:
    """Graph-grounded tutor with fixed, bounded Router and shadow strategies."""

    def __init__(
        self,
        knowledge_client: KnowledgeContextClient,
        *,
        provider_factory: ProviderFactory = create_model_provider,
        animation_provider_factory: ProviderFactory | None = None,
        memory_service_factory: MemoryServiceFactory = create_memory_service,
        router_factory: Callable[[], TutorRouter] | None = None,
    ) -> None:
        self._knowledge_client = knowledge_client
        self._provider_factory = provider_factory
        self._animation_provider_factory = animation_provider_factory or (
            lambda: create_animation_model_provider(default_factory=self._provider_factory)
        )
        self._memory_service_factory = memory_service_factory
        self._router_factory = router_factory

    async def aclose(self) -> None:
        await self._knowledge_client.aclose()

    async def chat(
        self,
        request: TutorChatRequest,
        *,
        trace_request_id: str,
    ) -> TutorChatResponse:
        if request.request_id != trace_request_id:
            raise TutorRequestIdMismatchError(
                "The body request_id must match X-Request-Id."
            )

        context = await self._knowledge_client.get_rag_context(
            request.knowledge_point_id,
            course_id=request.course_id,
            request_id=trace_request_id,
        )
        self._validate_context(context, request.knowledge_point_id)
        if context.course_id != request.course_id:
            raise TutorGraphContextInvalidError(
                "The graph context belongs to another course."
            )

        if request.agent_mode == "FIXED":
            return await self._fixed_response(
                request,
                context,
                trace_request_id=trace_request_id,
                trace=TutorAgentTrace(
                    requested_mode="FIXED",
                    effective_mode="FIXED",
                    tool_invocations=[],
                    total_steps=0,
                ),
            )

        if request.agent_mode == "SHADOW":
            fixed = await self._fixed_response(
                request,
                context,
                trace_request_id=trace_request_id,
                trace=TutorAgentTrace(
                    requested_mode="SHADOW",
                    effective_mode="SHADOW",
                    tool_invocations=[],
                    total_steps=0,
                ),
            )
            route = await self._safe_route(
                request,
                context,
                trace_request_id=trace_request_id,
            )
            return fixed.model_copy(
                update={
                    "agent_trace": TutorAgentTrace(
                        requested_mode="SHADOW",
                        effective_mode="SHADOW",
                        fallback_reason=route.fallback_reason,
                        tool_invocations=list(route.invocations),
                        total_steps=len(route.invocations),
                    )
                }
            )

        route = await self._safe_route(
            request,
            context,
            trace_request_id=trace_request_id,
        )
        if not route.succeeded:
            return await self._fixed_response(
                request,
                context,
                trace_request_id=trace_request_id,
                trace=TutorAgentTrace(
                    requested_mode="ROUTER",
                    effective_mode="FIXED_FALLBACK",
                    fallback_reason=route.fallback_reason or "TOOL_FAILED",
                    tool_invocations=list(route.invocations),
                    total_steps=len(route.invocations),
                ),
            )

        return await self._router_response(
            request,
            context,
            route,
            trace_request_id=trace_request_id,
        )

    async def _safe_route(
        self,
        request: TutorChatRequest,
        context: GraphRagContext,
        *,
        trace_request_id: str,
    ) -> RouterExecutionResult:
        try:
            router = self._create_router()
            return await router.run(
                request,
                context,
                trace_request_id=trace_request_id,
            )
        except (TutorRouterConfigurationError, ValueError):
            return RouterExecutionResult(
                succeeded=False,
                fallback_reason="TOOL_FAILED",
                invocations=(),
                tool_results=(),
            )

    def _create_router(self) -> TutorRouter:
        if self._router_factory is not None:
            return self._router_factory()
        settings = TutorRouterSettings.from_env()
        return TutorRouter(
            RouterToolRegistry(
                self._knowledge_client,
                memory_service_factory=self._memory_service_factory,
                tool_timeout_seconds=settings.tool_timeout_seconds,
            ),
            provider_factory=self._provider_factory,
            max_steps=settings.max_steps,
            total_timeout_seconds=settings.total_timeout_seconds,
        )

    async def _fixed_response(
        self,
        request: TutorChatRequest,
        context: GraphRagContext,
        *,
        trace_request_id: str,
        trace: TutorAgentTrace,
    ) -> TutorChatResponse:
        memory_hits, memory_epoch, rebuild_required = await self._fixed_memory(
            request,
            trace_request_id=trace_request_id,
        )
        return await self._generate_response(
            request,
            context,
            memory_hits=memory_hits,
            memory_index_epoch=memory_epoch,
            memory_rebuild_required=rebuild_required,
            supplemental_contexts=(),
            extra_node_ids=set(),
            tool_observations=[],
            prompt_version=TUTOR_PROMPT_VERSION,
            capability="TUTOR_CHAT",
            trace=trace,
            trace_request_id=trace_request_id,
        )

    async def _router_response(
        self,
        request: TutorChatRequest,
        context: GraphRagContext,
        route: RouterExecutionResult,
        *,
        trace_request_id: str,
    ) -> TutorChatResponse:
        supplemental_contexts: list[GraphRagContext] = []
        memory_hits = []
        extra_node_ids: set[str] = set()
        tool_observations: list[dict[str, object]] = []
        memory_epoch: str | None = None
        rebuild_required = False
        seen_memory_ids: set[int] = set()
        for result in route.tool_results:
            tool_observations.append(
                {
                    "tool": result.tool,
                    "item_count": result.item_count,
                    "data": result.observation,
                }
            )
            for additional in result.graph_contexts:
                self._validate_context(additional, additional.knowledge_point.id)
                if additional.course_id != request.course_id:
                    raise TutorGraphContextInvalidError(
                        "Router graph results crossed courses."
                    )
                supplemental_contexts.append(additional)
            extra_node_ids.update(node.id for node in result.graph_nodes)
            for hit in result.memory_hits:
                if (
                    hit.student_id != request.user_id
                    or hit.course_id != request.course_id
                ):
                    raise TutorGraphContextInvalidError(
                        "A router memory result violated student isolation."
                    )
                if hit.memory_id not in seen_memory_ids:
                    seen_memory_ids.add(hit.memory_id)
                    memory_hits.append(hit)
            if result.memory_index_epoch is not None:
                if memory_epoch is not None and memory_epoch != result.memory_index_epoch:
                    raise TutorGraphContextInvalidError(
                        "Router memory results crossed index epochs."
                    )
                memory_epoch = result.memory_index_epoch
            rebuild_required = rebuild_required or result.memory_rebuild_required
        return await self._generate_response(
            request,
            context,
            memory_hits=memory_hits,
            memory_index_epoch=memory_epoch,
            memory_rebuild_required=rebuild_required,
            supplemental_contexts=tuple(supplemental_contexts),
            extra_node_ids=extra_node_ids,
            tool_observations=tool_observations,
            prompt_version=TUTOR_ROUTER_ANSWER_PROMPT_VERSION,
            capability="TUTOR_CHAT_ROUTER",
            trace=TutorAgentTrace(
                requested_mode="ROUTER",
                effective_mode="ROUTER",
                tool_invocations=list(route.invocations),
                total_steps=len(route.invocations),
            ),
            trace_request_id=trace_request_id,
        )

    async def _fixed_memory(
        self,
        request: TutorChatRequest,
        *,
        trace_request_id: str,
    ):
        memory_hits = []
        memory_index_epoch: str | None = None
        memory_rebuild_required = False
        if request.student_context and request.student_context.personalization_enabled:
            try:
                memory_service = await asyncio.to_thread(self._memory_service_factory)
                memory_response = await memory_service.search(
                    MemorySearchRequest(
                        course_id=request.course_id,
                        request_id=request.request_id,
                        student_id=request.user_id,
                        query=request.message,
                        knowledge_point_id=request.knowledge_point_id,
                        limit=10,
                        min_score=0.25,
                    ),
                    trace_request_id=trace_request_id,
                )
                if (
                    memory_response.student_id != request.user_id
                    or memory_response.course_id != request.course_id
                    or any(
                        item.student_id != request.user_id
                        or item.course_id != request.course_id
                        for item in memory_response.items
                    )
                ):
                    raise MemoryStoreError(
                        "Memory response violated student or course isolation."
                    )
                memory_index_epoch = memory_response.memory_index_epoch
                memory_rebuild_required = memory_response.memory_rebuild_required
                memory_hits = memory_response.items
            except (
                MemoryConfigurationError,
                EmbeddingProviderError,
                MemoryStoreError,
                MemoryDeadlineExceededError,
            ):
                memory_hits = []
        return memory_hits, memory_index_epoch, memory_rebuild_required

    async def _generate_response(
        self,
        request: TutorChatRequest,
        context: GraphRagContext,
        *,
        memory_hits,
        memory_index_epoch: str | None,
        memory_rebuild_required: bool,
        supplemental_contexts: tuple[GraphRagContext, ...],
        extra_node_ids: set[str],
        tool_observations: list[dict[str, object]],
        prompt_version: str,
        capability: str,
        trace: TutorAgentTrace,
        trace_request_id: str,
    ) -> TutorChatResponse:
        contexts = (context, *supplemental_contexts)
        context_node_ids = set(extra_node_ids)
        source_by_id = {}
        for item in contexts:
            context_node_ids.update(
                {
                    item.knowledge_point.id,
                    *(node.id for node in item.prerequisites),
                    *(node.id for node in item.successors),
                    *(node.id for node in item.related_nodes),
                }
            )
            for source in item.sources:
                previous = source_by_id.setdefault(source.id, source)
                if previous != source:
                    raise TutorGraphContextInvalidError(
                        "Graph contexts contain conflicting source identifiers."
                    )

        settings = TutorGenerationSettings.from_env()
        try:
            async with asyncio.timeout(settings.total_timeout_seconds):
                planner = TeachingPlanner(provider_factory=self._provider_factory)
                try:
                    teaching_plan = await planner.plan(
                        request,
                        context,
                        supplemental_contexts=supplemental_contexts,
                        trace_request_id=trace_request_id,
                        timeout_seconds=min(
                            TEACHING_PLANNER_TIMEOUT_SECONDS,
                            settings.total_timeout_seconds,
                        ),
                    )
                except (
                    TeachingPlanOutputError,
                    ModelProviderError,
                    ModelTimeoutError,
                    ValidationError,
                    TypeError,
                    ValueError,
                ):
                    # Planning is an enhancement, not a reason to discard a valid
                    # explanation. MIXED keeps one bounded activity specialist so
                    # the explicit user intent is still attempted deterministically.
                    teaching_plan = _fallback_teaching_plan(
                        request.teaching_mode,
                        request.message,
                    )

                teaching_plan = constrain_teaching_plan(
                    teaching_plan, request.enabled_activity_types
                )

                try:
                    messages = build_tutor_messages(
                        request,
                        context,
                        memory_hits,
                        supplemental_contexts=supplemental_contexts,
                        tool_observations=tool_observations,
                        teaching_plan=teaching_plan,
                    )
                except ValueError as exc:
                    raise TutorGraphContextInvalidError(
                        "The approved graph context cannot be used safely in a prompt."
                    ) from exc

                specialist_calls = []
                for specialist_agent in teaching_plan.specialist_agents:
                    if specialist_agent == "EXPLANATION":
                        specialist_calls.append(
                            self._run_explanation_specialist(
                                request,
                                messages=messages,
                                memory_hits=memory_hits,
                                context_node_ids=context_node_ids,
                                source_by_id=source_by_id,
                                prompt_version=prompt_version,
                                capability=capability,
                                trace_request_id=trace_request_id,
                            )
                        )
                    else:
                        specialist_calls.append(
                            ActivitySpecialist(
                                specialist_agent,
                                provider_factory=(self._animation_provider_factory
                                    if specialist_agent == "ANIMATION" else self._provider_factory),
                            ).run(
                                request,
                                context,
                                supplemental_contexts=supplemental_contexts,
                                allowed_knowledge_point_ids=context_node_ids,
                                trace_request_id=trace_request_id,
                                timeout_seconds=min(
                                    ACTIVITY_SPECIALIST_TIMEOUT_SECONDS,
                                    settings.total_timeout_seconds,
                                ),
                            )
                        )

                specialist_results = await asyncio.gather(
                    *specialist_calls,
                    return_exceptions=True,
                )
                explanation: ExplanationSpecialistResult | None = None
                activities: list[TutorActivityDraft] = []
                specialist_trace: list[TutorSpecialistInvocation] = []
                for specialist_agent, result in zip(
                    teaching_plan.specialist_agents,
                    specialist_results,
                    strict=True,
                ):
                    if isinstance(result, BaseException):
                        raise result
                    if specialist_agent == "EXPLANATION":
                        if not isinstance(result, ExplanationSpecialistResult):
                            raise TutorModelOutputError(
                                "The explanation specialist returned an invalid result."
                            )
                        explanation = result
                        specialist_trace.append(result.trace)
                        continue
                    if not isinstance(result, SpecialistActivityResult):
                        raise TutorModelOutputError(
                            "An activity specialist returned an invalid result."
                        )
                    specialist_trace.append(result.trace)
                    if result.activity is not None:
                        activities.append(result.activity)

                if explanation is None:
                    raise TutorModelOutputError(
                        "The bounded teaching plan omitted its explanation specialist."
                    )
                if len({item.activity_ref for item in activities}) != len(activities):
                    raise TutorModelOutputError(
                        "Activity specialists returned duplicate references."
                    )

                # Reserve the public block budget from the teaching intent before
                # activities are clipped.  A hostile/verbose explanation must not
                # consume all 20 blocks and thereby suppress requested interactions.
                public_explanation = _compose_public_explanation(
                    explanation.generated,
                    teaching_plan,
                    activities,
                )
                blocks = list(public_explanation.blocks)
                available_activity_blocks = max(0, 20 - len(blocks))
                if len(activities) > available_activity_blocks:
                    dropped_agents = {
                        {
                            "QUIZ": "QUIZ",
                            "CODE_LAB": "CODE_LAB",
                            "ANIMATION": "ANIMATION",
                        }[item.activity_type]
                        for item in activities[available_activity_blocks:]
                    }
                    activities = activities[:available_activity_blocks]
                    specialist_trace = [
                        item.model_copy(
                            update={
                                "status": "SKIPPED",
                                "artifact_count": 0,
                                "error_code": "CONTENT_BLOCK_LIMIT",
                            }
                        )
                        if item.agent in dropped_agents
                        else item
                        for item in specialist_trace
                    ]
                # Recompose from the final activity list so guidance only names
                # cards that actually survived verification and budgeting.
                public_explanation = _compose_public_explanation(
                    explanation.generated,
                    teaching_plan,
                    activities,
                )
                blocks = list(public_explanation.blocks)
                used_block_ids = {
                    block.block_id for block in blocks if block.block_id is not None
                }
                for activity in activities:
                    reference_block = _activity_reference_block(activity)
                    if reference_block.block_id in used_block_ids:
                        raise TutorModelOutputError(
                            "An activity block identifier collided with explanation output."
                        )
                    used_block_ids.add(reference_block.block_id)
                    blocks.append(reference_block)

                return TutorChatResponse(
                    course_id=request.course_id,
                    request_id=request.request_id,
                    knowledge_point_id=request.knowledge_point_id,
                    answer=public_explanation.answer,
                    blocks=blocks,
                    activities=activities,
                    teaching_plan=teaching_plan,
                    specialist_trace=specialist_trace,
                    sources=list(explanation.sources),
                    cognitive_labels=public_explanation.cognitive_labels,
                    memory_ids_used=[item.memory_id for item in memory_hits],
                    memory_index_epoch=memory_index_epoch,
                    memory_rebuild_required=memory_rebuild_required,
                    agent_trace=trace,
                    suggested_next_step=public_explanation.suggested_next_step,
                    model_version=explanation.model_version,
                    prompt_version=prompt_version,
                )
        except TimeoutError as exc:
            raise ModelTimeoutError(
                "Tutor multi-agent generation exceeded the shared deadline.",
            ) from exc

    async def _run_explanation_specialist(
        self,
        request: TutorChatRequest,
        *,
        messages,
        memory_hits,
        context_node_ids: set[str],
        source_by_id: dict[str, object],
        prompt_version: str,
        capability: str,
        trace_request_id: str,
    ) -> ExplanationSpecialistResult:
        del memory_hits
        started = time.perf_counter()
        last_output_error: ModelResponseError | TutorModelOutputError | None = None
        for attempt in range(MAX_TUTOR_GENERATION_ATTEMPTS):
            provider = self._provider_factory()
            try:
                try:
                    attempt_messages = messages
                    if attempt > 0:
                        attempt_messages = (
                            *messages,
                            ModelMessage(
                                role="user",
                                content=(
                                    "上一次 JSON 未通过结构校验。请从头返回一个完整 JSON 对象；"
                                    "blocks 只能使用 markdown 或 code，不能包含 quiz、code_lab、"
                                    "animation、activity_ref 或 resource_id；cited_source_ids 只能引用"
                                    " GRAPH_RAG_CONTEXT.sources 中的 APPROVED id 且至少一个；"
                                    "cognitive_labels 只能引用上下文已有知识点 id，不能确定时返回空数组；"
                                    "如果教学计划包含 QUIZ 或 CODE_LAB，cognitive_labels 返回空数组，"
                                    "suggested_next_step 不得包含答案、参考解或测试期望输出；"
                                    "不要解释修复过程。"
                                ),
                            ),
                        )
                    model_response = await provider.generate(
                        ModelRequest(
                            messages=attempt_messages,
                            prompt_version=prompt_version,
                            # Reasoning-capable providers count hidden reasoning before
                            # emitting the bounded JSON answer.  Four thousand tokens
                            # intermittently produced an empty/incomplete completion in
                            # the real DeepSeek gate, while the public answer remains
                            # capped independently at 20,000 characters by the DTO.
                            max_tokens=8_000,
                            temperature=0.2,
                            request_id=trace_request_id,
                            capability=capability,
                        ),
                        output_model=TutorModelOutput,
                    )
                    return self._validate_explanation_output(
                        model_response,
                        context_node_ids=context_node_ids,
                        source_by_id=source_by_id,
                        prompt_version=prompt_version,
                        started=started,
                    )
                except (ModelResponseError, TutorModelOutputError) as exc:
                    last_output_error = exc
                    if attempt + 1 == MAX_TUTOR_GENERATION_ATTEMPTS:
                        raise
            finally:
                await provider.aclose()
        raise TutorModelOutputError(
            "The explanation specialist did not return valid structured output."
        ) from last_output_error

    @staticmethod
    def _validate_explanation_output(
        model_response: ModelResponse,
        *,
        context_node_ids: set[str],
        source_by_id: dict[str, object],
        prompt_version: str,
        started: float,
    ) -> ExplanationSpecialistResult:
        if model_response.structured_content is None:
            raise TutorModelOutputError(
                "The model did not return the required structured tutor output."
            )
        try:
            generated = TutorModelOutput.model_validate(
                model_response.structured_content
            )
        except ValidationError as exc:
            raise TutorModelOutputError(
                "The model returned invalid structured tutor output."
            ) from exc
        if model_response.metadata.prompt_version != prompt_version:
            raise TutorModelOutputError(
                "The model response prompt version does not match the tutor prompt."
            )

        if any(
            label.knowledge_point_id not in context_node_ids
            for label in generated.cognitive_labels
        ):
            raise TutorModelOutputError(
                "The model returned a cognitive label outside approved tool context."
            )

        cited_sources: list[TutorSource] = []
        seen_source_ids: set[str] = set()
        for source_id in generated.cited_source_ids:
            if not source_id or source_id in seen_source_ids:
                raise TutorModelOutputError(
                    "The model returned an empty or duplicate source citation."
                )
            source = source_by_id.get(source_id)
            if source is None or source.review_status != "APPROVED":
                raise TutorModelOutputError(
                    "The model cited a source outside approved graph contexts."
                )
            seen_source_ids.add(source_id)
            cited_sources.append(
                TutorSource.model_validate(
                    {
                        **source.model_dump(mode="json"),
                        "review_status": "APPROVED",
                        "reviewed": True,
                    }
                )
            )
        cited_resource_ids = {
            source.resource_id
            for source in cited_sources
            if source.resource_id is not None
        }
        if any(
            block.resource_id is not None
            and block.resource_id not in cited_resource_ids
            for block in generated.blocks
        ):
            raise TutorModelOutputError(
                "A model content block references an uncited graph resource."
            )
        model_version = model_response.metadata.model.strip()
        if not model_version or len(model_version) > 100:
            raise TutorModelOutputError(
                "The model response is missing a valid model version."
            )
        return ExplanationSpecialistResult(
            generated=generated,
            sources=tuple(cited_sources),
            model_version=model_version,
            trace=TutorSpecialistInvocation(
                agent="EXPLANATION",
                status="SUCCEEDED",
                duration_ms=_elapsed_ms(started),
                artifact_count=0,
            ),
        )

    @staticmethod
    def _validate_context(context: GraphRagContext, expected_node_id: str) -> None:
        try:
            context.ensure_approved_and_traceable(expected_node_id)
        except ValueError as exc:
            raise TutorGraphContextInvalidError(
                "The graph context failed approval or traceability validation."
            ) from exc
        if context.context_status != "READY" or not context.sources:
            raise TutorGraphContextInsufficientError(context.warnings)


def _activity_reference_block(activity: TutorActivityDraft) -> ContentBlock:
    block_type = {
        "QUIZ": "quiz",
        "CODE_LAB": "code_lab",
        "ANIMATION": "animation",
    }[activity.activity_type]
    digest = hashlib.sha256(activity.activity_ref.encode("utf-8")).hexdigest()[:24]
    return ContentBlock(
        type=block_type,
        block_id=f"activity-block-{digest}",
        schema_version=1,
        activity_ref=activity.activity_ref,
        title=activity.title,
        metadata={"activity_type": activity.activity_type},
    )


def _compose_public_explanation(
    generated: TutorModelOutput,
    teaching_plan: TutorTeachingPlan,
    activities: list[TutorActivityDraft],
) -> TutorModelOutput:
    """Keep unsubmitted interactive answers out of public explanation fields."""

    planned_interactive = {
        item
        for item in teaching_plan.specialist_agents
        if item in {"QUIZ", "CODE_LAB"}
    }
    activity_types = {item.activity_type for item in activities}
    has_quiz = "QUIZ" in activity_types
    has_code_lab = "CODE_LAB" in activity_types
    if not planned_interactive and not has_quiz and not has_code_lab:
        return generated

    if not has_quiz and not has_code_lab:
        guidance = (
            "本轮互动资源暂未生成，未公开题目答案或参考解。"
            "请重试当前请求，或切换到其他教学模式。"
        )
        suggested_next_step = "请重试互动资源生成，或切换教学模式继续学习。"
    elif has_quiz and has_code_lab:
        guidance = (
            "请在下方自测卡片中选择或填写答案，并在代码实验卡片中编辑、"
            "运行代码；分别提交后会显示结果和解析。"
        )
        suggested_next_step = (
            "先完成自测和代码实验并分别提交，再根据结果和解析选择下一步学习内容。"
        )
    elif has_quiz:
        guidance = (
            "请直接在下方自测卡片中选择或填写答案，完成后点击“提交答案”；"
            "提交后会显示结果和逐题解析。"
        )
        suggested_next_step = (
            "先完成并提交下方自测，再根据逐题结果和解析继续学习。"
        )
    else:
        guidance = (
            "请直接在下方代码实验卡片中编辑并运行代码，完成后点击“提交代码”；"
            "提交后会显示评分、测试结果和解析。"
        )
        suggested_next_step = (
            "先运行并提交下方代码，再根据评分、测试结果和解析继续修改。"
        )

    return generated.model_copy(
        update={
            "answer": guidance,
            "blocks": [
                ContentBlock(
                    type="markdown",
                    block_id="interactive-guidance",
                    schema_version=1,
                    content=guidance,
                    title="互动学习提示",
                )
            ],
            "cognitive_labels": [],
            "suggested_next_step": suggested_next_step,
        }
    )


def _fallback_teaching_plan(
    teaching_mode: str,
    message: str = "",
) -> TutorTeachingPlan:
    explicit = {
        "EXPLAIN": ["EXPLANATION"],
        "QUIZ": ["EXPLANATION", "QUIZ"],
        "CODE": ["EXPLANATION", "CODE_LAB"],
        "VISUAL": ["EXPLANATION", "ANIMATION"],
    }
    specialists = explicit.get(teaching_mode)
    if specialists is None:
        normalized = message.casefold()
        inferred: list[str] = []
        keyword_groups = (
            (
                "CODE_LAB",
                r"(?:代码|编程|程序|\bcode\b|算法.{0,8}实现|实现.{0,8}算法)",
            ),
            (
                "ANIMATION",
                r"(?:动画|动态图|演示|可视化|\bvisual(?:ize|isation|ization)?\b|\banimat(?:e|ion)\b|\bsvg\b)",
            ),
            (
                "QUIZ",
                r"(?:出.{0,6}题|题目|练习题|选择题|判断题|自测|测验|考考|\bquiz\b|\bpractice\b|\btest me\b)",
            ),
        )
        for agent, pattern in keyword_groups:
            if re.search(pattern, normalized):
                inferred.append(agent)
        if teaching_mode == "MIXED" and not inferred:
            inferred.append("QUIZ")
        specialists = ["EXPLANATION", *inferred[:2]]
    return TutorTeachingPlan(
        goal="使用确定性意图识别保留可追溯讲解和用户明确请求的互动资源。",
        modalities=specialists,
        specialist_agents=specialists,
    )


def _elapsed_ms(started: float) -> int:
    return max(0, min(2_147_483_647, int((time.perf_counter() - started) * 1_000)))


def create_tutor_agent() -> TutorAgent:
    return TutorAgent(KnowledgeServiceClient())
