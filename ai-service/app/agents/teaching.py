from __future__ import annotations

import asyncio
import hashlib
import re
import time
from collections.abc import Callable
from dataclasses import dataclass
from decimal import Decimal

from pydantic import BaseModel, ValidationError

from app.contracts.graph import GraphRagContext
from app.contracts.tutor import (
    TutorActivityDraft,
    TutorAnimationActivityDraft,
    TutorChatRequest,
    TutorCodeActivityDraft,
    TutorQuizActivityDraft,
    TutorSpecialistAgent,
    TutorSpecialistInvocation,
    TutorTeachingPlan,
)
from app.prompts.teaching import (
    TUTOR_TEACHING_PLANNER_PROMPT_VERSION,
    build_activity_specialist_messages,
    build_teaching_plan_messages,
    specialist_prompt_version,
)
from app.providers.base import (
    ModelProvider,
    ModelProviderError,
    ModelMessage,
    ModelRequest,
    ModelResponseError,
    ModelTimeoutError,
)
from app.providers.factory import create_model_provider


ProviderFactory = Callable[[], ModelProvider]
MAX_ACTIVITY_GENERATION_ATTEMPTS = 2
_ACTIVE_CONTENT_PATTERN = re.compile(
    r"(?:<\s*(?:script|iframe|object|embed)|javascript\s*:|data\s*:\s*text/html|"
    r"on(?:load|error|click)\s*=)",
    re.IGNORECASE,
)
_MARKUP_OR_SVG_PATTERN = re.compile(r"<\s*/?\s*[A-Za-z][A-Za-z0-9:_-]*\b", re.IGNORECASE)


class TeachingPlanOutputError(ValueError):
    """Sanitized planner failure; caller maps it to the Tutor invalid-output error."""


_SPECIALIST_ACTIVITY_TYPES = {
    "EXPLANATION": "EXPLAIN",
    "QUIZ": "QUIZ",
    "CODE_LAB": "CODE",
    "ANIMATION": "VISUAL",
}


def constrain_teaching_plan(
    plan: TutorTeachingPlan, enabled_activity_types: list[str]
) -> TutorTeachingPlan:
    """Course capabilities are authoritative, including on fallback paths."""
    allowed = set(enabled_activity_types)
    agents = [
        agent for agent in plan.specialist_agents
        if _SPECIALIST_ACTIVITY_TYPES[agent] in allowed
    ]
    if not agents or agents[0] != "EXPLANATION":
        raise TeachingPlanOutputError("Course capabilities must enable EXPLAIN.")
    if agents == plan.specialist_agents:
        return plan
    return TutorTeachingPlan(
        goal="根据当前课程已启用的教学能力提供有依据的讲解与练习。",
        modalities=agents,
        specialist_agents=agents,
    )


class TutorActivityVerificationError(ValueError):
    def __init__(self, code: str) -> None:
        super().__init__("The activity draft failed deterministic verification.")
        self.code = code


@dataclass(frozen=True, slots=True)
class SpecialistActivityResult:
    activity: TutorActivityDraft | None
    trace: TutorSpecialistInvocation


class TeachingPlanner:
    """Structured plan agent with deterministic handling for explicit user modes."""

    _EXPLICIT_PLANS: dict[str, tuple[TutorSpecialistAgent, ...]] = {
        "EXPLAIN": ("EXPLANATION",),
        "QUIZ": ("EXPLANATION", "QUIZ"),
        "CODE": ("EXPLANATION", "CODE_LAB"),
        "VISUAL": ("EXPLANATION", "ANIMATION"),
    }

    def __init__(
        self,
        *,
        provider_factory: ProviderFactory = create_model_provider,
    ) -> None:
        self._provider_factory = provider_factory

    async def plan(
        self,
        request: TutorChatRequest,
        context: GraphRagContext,
        *,
        supplemental_contexts: tuple[GraphRagContext, ...],
        trace_request_id: str,
        timeout_seconds: float,
    ) -> TutorTeachingPlan:
        explicit_agents = self._EXPLICIT_PLANS.get(request.teaching_mode)
        if explicit_agents is not None:
            return constrain_teaching_plan(
                TutorTeachingPlan(
                    goal=_explicit_goal(request.teaching_mode),
                    modalities=list(explicit_agents),
                    specialist_agents=list(explicit_agents),
                ),
                request.enabled_activity_types,
            )

        provider: ModelProvider | None = None
        try:
            async with asyncio.timeout(timeout_seconds):
                provider = self._provider_factory()
                response = await provider.generate(
                    ModelRequest(
                        messages=build_teaching_plan_messages(
                            request,
                            context,
                            supplemental_contexts=supplemental_contexts,
                        ),
                        prompt_version=TUTOR_TEACHING_PLANNER_PROMPT_VERSION,
                        max_tokens=1_200,
                        temperature=0.0,
                        request_id=trace_request_id,
                        capability="TUTOR_TEACHING_PLAN",
                    ),
                    output_model=TutorTeachingPlan,
                )
                if response.structured_content is None:
                    raise TeachingPlanOutputError(
                        "The teaching planner returned no structured content."
                    )
                try:
                    plan = TutorTeachingPlan.model_validate(
                        response.structured_content
                    )
                except ValidationError as exc:
                    raise TeachingPlanOutputError(
                        "The teaching planner returned an invalid plan."
                    ) from exc
                if (
                    response.metadata.prompt_version
                    != TUTOR_TEACHING_PLANNER_PROMPT_VERSION
                ):
                    raise TeachingPlanOutputError(
                        "The teaching planner prompt version did not match."
                    )
                _verify_plan_for_mode(plan, request.teaching_mode)
                return constrain_teaching_plan(plan, request.enabled_activity_types)
        except TimeoutError as exc:
            raise ModelTimeoutError(
                "Tutor teaching planning exceeded its bounded deadline."
            ) from exc
        finally:
            if provider is not None:
                await provider.aclose()


class TutorActivityVerifier:
    """Deterministic, non-model verifier for every stateful Tutor activity draft."""

    @classmethod
    def verify(
        cls,
        activity: TutorActivityDraft,
        *,
        expected_activity_ref: str,
        allowed_knowledge_point_ids: set[str],
    ) -> None:
        if activity.activity_ref != expected_activity_ref:
            raise TutorActivityVerificationError("ACTIVITY_REF_MISMATCH")
        if activity.knowledge_point_id not in allowed_knowledge_point_ids:
            raise TutorActivityVerificationError("ACTIVITY_KNOWLEDGE_POINT_INVALID")

        if isinstance(activity, TutorQuizActivityDraft):
            cls._verify_quiz(activity)
        elif isinstance(activity, TutorCodeActivityDraft):
            cls._verify_code(activity)
        elif isinstance(activity, TutorAnimationActivityDraft):
            cls._verify_animation(activity)
        else:  # pragma: no cover - discriminated union makes this defensive only.
            raise TutorActivityVerificationError("ACTIVITY_TYPE_INVALID")

    @staticmethod
    def _verify_quiz(activity: TutorQuizActivityDraft) -> None:
        total = sum((Decimal(str(item.score)) for item in activity.questions), Decimal(0))
        if total > Decimal("100"):
            raise TutorActivityVerificationError("QUIZ_SCORE_INVALID")
        normalized_stems = [item.stem.casefold() for item in activity.questions]
        if len(normalized_stems) != len(set(normalized_stems)):
            raise TutorActivityVerificationError("QUIZ_QUESTION_DUPLICATE")
        if not any(question.question_type != "SHORT_ANSWER" for question in activity.questions):
            raise TutorActivityVerificationError("QUIZ_CHOICE_QUESTION_REQUIRED")

    @staticmethod
    def _verify_code(activity: TutorCodeActivityDraft) -> None:
        total = sum((Decimal(str(item.score)) for item in activity.test_cases), Decimal(0))
        if total > Decimal("100"):
            raise TutorActivityVerificationError("CODE_SCORE_INVALID")
        if "\x00" in activity.reference_solution or "\x00" in activity.starter_code:
            raise TutorActivityVerificationError("CODE_SOURCE_INVALID")

        public_cases = {
            (item.input_data, item.expected_output)
            for item in activity.test_cases
            if item.visibility == "PUBLIC"
        }
        hidden_cases = {
            (item.input_data, item.expected_output)
            for item in activity.test_cases
            if item.visibility == "HIDDEN"
        }
        if not public_cases or not hidden_cases or public_cases & hidden_cases:
            raise TutorActivityVerificationError("CODE_TEST_BOUNDARY_INVALID")

    @staticmethod
    def _verify_animation(activity: TutorAnimationActivityDraft) -> None:
        entity_ids = [entity.id for entity in activity.scene.entities]
        if len(entity_ids) != len(set(entity_ids)):
            raise TutorActivityVerificationError("ANIMATION_ENTITY_DUPLICATE")
        allowed_ids = set(entity_ids)

        for entity in activity.scene.entities:
            _reject_active_content((entity.label, *entity.values))
            if entity.kind == "EDGE":
                if (
                    entity.from_id is None
                    or entity.to_id is None
                    or entity.from_id not in allowed_ids
                    or entity.to_id not in allowed_ids
                ):
                    raise TutorActivityVerificationError(
                        "ANIMATION_EDGE_REFERENCE_INVALID"
                    )
            elif entity.from_id is not None or entity.to_id is not None:
                raise TutorActivityVerificationError(
                    "ANIMATION_ENTITY_REFERENCE_INVALID"
                )

        expected_orders = list(range(1, len(activity.scene.steps) + 1))
        if [step.order for step in activity.scene.steps] != expected_orders:
            raise TutorActivityVerificationError("ANIMATION_STEP_ORDER_INVALID")

        kind_by_id = {entity.id: entity.kind for entity in activity.scene.entities}
        array_lengths = {entity.id: len(entity.values) for entity in activity.scene.entities if entity.kind == "ARRAY"}
        for step in activity.scene.steps:
            _reject_active_content((step.caption, step.value or ""))
            if any(target_id not in allowed_ids for target_id in step.target_ids):
                raise TutorActivityVerificationError(
                    "ANIMATION_STEP_REFERENCE_INVALID"
                )
            if step.action == "MOVE_POINTER" and not any(
                kind_by_id[target_id] == "POINTER" for target_id in step.target_ids
            ):
                raise TutorActivityVerificationError(
                    "ANIMATION_POINTER_REFERENCE_INVALID"
                )
            if step.action == "FOCUS_CODE" and any(
                kind_by_id[target_id] != "CODE_LINE" for target_id in step.target_ids
            ):
                raise TutorActivityVerificationError(
                    "ANIMATION_CODE_REFERENCE_INVALID"
                )
            if step.action == "SET_VALUE" and step.value is None:
                raise TutorActivityVerificationError("ANIMATION_STEP_VALUE_MISSING")
            arrays = [target for target in step.target_ids if target in array_lengths]
            if step.indices:
                if (len(arrays) != 1 or step.action not in {
                    "HIGHLIGHT", "CLEAR_HIGHLIGHT", "MOVE_POINTER", "SET_VALUE", "COMPARE", "ANNOTATE"
                }):
                    raise TutorActivityVerificationError("ANIMATION_INDEX_TARGET_INVALID")
                length = array_lengths[arrays[0]]
                if any(index < -length or index >= length for index in step.indices):
                    raise TutorActivityVerificationError("ANIMATION_INDEX_OUT_OF_RANGE")
                if step.action in {"MOVE_POINTER", "SET_VALUE"} and len(step.indices) != 1:
                    raise TutorActivityVerificationError("ANIMATION_INDEX_TARGET_INVALID")
                if step.action == "MOVE_POINTER" and sum(kind_by_id[t] == "POINTER" for t in step.target_ids) != 1:
                    raise TutorActivityVerificationError("ANIMATION_POINTER_REFERENCE_INVALID")
                if step.action == "SET_VALUE" and (len(step.target_ids) != 1 or len(step.value or "") > 100):
                    raise TutorActivityVerificationError("ANIMATION_STEP_VALUE_INVALID")
            elif step.action == "SET_VALUE":
                for target in arrays:
                    values = [value.strip() for value in (step.value or "").split(",")]
                    if len(values) > 30 or any(len(value) > 100 for value in values):
                        raise TutorActivityVerificationError("ANIMATION_STEP_VALUE_INVALID")
                    array_lengths[target] = len(values)


class ActivitySpecialist:
    """One optional bounded activity specialist; failures become sanitized trace rows."""

    _OUTPUT_MODELS: dict[TutorSpecialistAgent, type[BaseModel]] = {
        "QUIZ": TutorQuizActivityDraft,
        "CODE_LAB": TutorCodeActivityDraft,
        "ANIMATION": TutorAnimationActivityDraft,
    }
    _MAX_TOKENS: dict[TutorSpecialistAgent, int] = {
        "QUIZ": 6_000,
        "CODE_LAB": 8_000,
        "ANIMATION": 8_000,
    }

    def __init__(
        self,
        agent: TutorSpecialistAgent,
        *,
        provider_factory: ProviderFactory = create_model_provider,
    ) -> None:
        if agent not in self._OUTPUT_MODELS:
            raise ValueError("ActivitySpecialist requires an activity-producing agent.")
        self.agent = agent
        self._provider_factory = provider_factory

    async def run(
        self,
        request: TutorChatRequest,
        context: GraphRagContext,
        *,
        supplemental_contexts: tuple[GraphRagContext, ...],
        allowed_knowledge_point_ids: set[str],
        trace_request_id: str,
        timeout_seconds: float,
    ) -> SpecialistActivityResult:
        started = time.perf_counter()
        if _SPECIALIST_ACTIVITY_TYPES[self.agent] not in request.enabled_activity_types:
            return SpecialistActivityResult(
                activity=None,
                trace=TutorSpecialistInvocation(
                    agent=self.agent,
                    status="SKIPPED",
                    duration_ms=0,
                    artifact_count=0,
                    error_code="COURSE_ACTIVITY_DISABLED",
                ),
            )
        expected_ref = stable_activity_ref(request.request_id, self.agent)
        prompt_version = specialist_prompt_version(self.agent)
        try:
            async with asyncio.timeout(timeout_seconds):
                output_model = self._OUTPUT_MODELS[self.agent]
                base_messages = build_activity_specialist_messages(
                    self.agent,
                    request,
                    context,
                    supplemental_contexts=supplemental_contexts,
                    expected_activity_ref=expected_ref,
                )
                last_output_error: Exception | None = None
                for attempt in range(MAX_ACTIVITY_GENERATION_ATTEMPTS):
                    provider = self._provider_factory()
                    try:
                        attempt_messages = base_messages
                        if attempt > 0:
                            attempt_messages = (
                                *base_messages,
                                ModelMessage(
                                    role="user",
                                    content=(
                                        "上一次活动 JSON 未通过结构或业务校验。请从头返回一个完整 JSON 对象，"
                                        "严格使用 expected_activity_ref 和当前 Specialist 的唯一活动类型；"
                                        "不要解释修复过程。"
                                    ),
                                ),
                            )
                        try:
                            response = await provider.generate(
                                ModelRequest(
                                    messages=attempt_messages,
                                    prompt_version=prompt_version,
                                    max_tokens=self._MAX_TOKENS[self.agent],
                                    temperature=0.0 if attempt > 0 else 0.2,
                                    request_id=trace_request_id,
                                    capability=f"TUTOR_SPECIALIST_{self.agent}",
                                ),
                                output_model=output_model,
                            )
                            if response.structured_content is None:
                                raise TeachingPlanOutputError(
                                    "The activity specialist returned no structured content."
                                )
                            try:
                                activity = output_model.model_validate(
                                    response.structured_content
                                )
                            except ValidationError as exc:
                                raise TeachingPlanOutputError(
                                    "The activity specialist returned an invalid draft."
                                ) from exc
                            if response.metadata.prompt_version != prompt_version:
                                raise TeachingPlanOutputError(
                                    "The activity specialist prompt version did not match."
                                )
                            TutorActivityVerifier.verify(
                                activity,
                                expected_activity_ref=expected_ref,
                                allowed_knowledge_point_ids=allowed_knowledge_point_ids,
                            )
                            return SpecialistActivityResult(
                                activity=activity,
                                trace=TutorSpecialistInvocation(
                                    agent=self.agent,
                                    status="SUCCEEDED",
                                    duration_ms=_elapsed_ms(started),
                                    artifact_count=1,
                                ),
                            )
                        except (
                            ModelResponseError,
                            TeachingPlanOutputError,
                            TutorActivityVerificationError,
                            ValidationError,
                            TypeError,
                            ValueError,
                        ) as exc:
                            last_output_error = exc
                            if attempt + 1 == MAX_ACTIVITY_GENERATION_ATTEMPTS:
                                raise
                    finally:
                        await provider.aclose()
                raise TeachingPlanOutputError(
                    "The activity specialist did not return valid structured output."
                ) from last_output_error
        except (TimeoutError, ModelTimeoutError):
            return self._failure_result(
                started,
                status="TIMED_OUT",
                context=context,
                expected_activity_ref=expected_ref,
                allowed_knowledge_point_ids=allowed_knowledge_point_ids,
            )
        except TutorActivityVerificationError as exc:
            return self._failure_result(
                started,
                status="FAILED",
                context=context,
                expected_activity_ref=expected_ref,
                allowed_knowledge_point_ids=allowed_knowledge_point_ids,
            )
        except ModelProviderError:
            return self._failure_result(
                started,
                status="FAILED",
                context=context,
                expected_activity_ref=expected_ref,
                allowed_knowledge_point_ids=allowed_knowledge_point_ids,
            )
        except (TeachingPlanOutputError, ValidationError, TypeError, ValueError):
            return self._failure_result(
                started,
                status="FAILED",
                context=context,
                expected_activity_ref=expected_ref,
                allowed_knowledge_point_ids=allowed_knowledge_point_ids,
            )
        except Exception:
            return self._failure_result(
                started,
                status="FAILED",
                context=context,
                expected_activity_ref=expected_ref,
                allowed_knowledge_point_ids=allowed_knowledge_point_ids,
            )

    def _failure_result(
        self,
        started: float,
        *,
        status: str,
        context: GraphRagContext,
        expected_activity_ref: str,
        allowed_knowledge_point_ids: set[str],
    ) -> SpecialistActivityResult:
        fallback = _deterministic_fallback_activity(
            self.agent,
            activity_ref=expected_activity_ref,
            knowledge_point_id=context.knowledge_point.id,
        )
        TutorActivityVerifier.verify(
            fallback,
            expected_activity_ref=expected_activity_ref,
            allowed_knowledge_point_ids=allowed_knowledge_point_ids,
        )
        return SpecialistActivityResult(
            activity=fallback,
            trace=TutorSpecialistInvocation(
                agent=self.agent,
                status=status,
                duration_ms=_elapsed_ms(started),
                # The provider failed, but the deterministic local fallback is a
                # real verified resource and must be persisted with its reference.
                artifact_count=1,
                error_code=f"{self.agent}_FALLBACK_USED",
            ),
        )


def stable_activity_ref(request_id: str, agent: TutorSpecialistAgent) -> str:
    digest = hashlib.sha256(f"{request_id}:{agent}".encode("utf-8")).hexdigest()[:16]
    return f"tutor_{agent.lower()}_{digest}"


def _verify_plan_for_mode(plan: TutorTeachingPlan, teaching_mode: str) -> None:
    if not plan.specialist_agents or plan.specialist_agents[0] != "EXPLANATION":
        raise TeachingPlanOutputError("Teaching plans must start with EXPLANATION.")
    if teaching_mode == "MIXED" and len(plan.specialist_agents) < 2:
        raise TeachingPlanOutputError("MIXED teaching mode needs multiple modalities.")


def _explicit_goal(teaching_mode: str) -> str:
    return {
        "EXPLAIN": "基于已审核知识图谱提供清晰、可追溯的分步讲解。",
        "QUIZ": "先建立必要理解，再通过可点击客观题检验当前知识点。",
        "CODE": "先说明核心概念，再提供可运行、可评分的 Python 代码练习。",
        "VISUAL": "先说明核心概念，再用安全的声明式动画展示变化过程。",
    }[teaching_mode]


def _reject_active_content(values: tuple[str, ...]) -> None:
    if any(
        _ACTIVE_CONTENT_PATTERN.search(value) or _MARKUP_OR_SVG_PATTERN.search(value)
        for value in values
    ):
        raise TutorActivityVerificationError("ANIMATION_ACTIVE_CONTENT_REJECTED")


def _deterministic_fallback_activity(
    agent: TutorSpecialistAgent,
    *,
    activity_ref: str,
    knowledge_point_id: str,
) -> TutorActivityDraft:
    """Produce a safe, server-verifiable interaction when the model is unavailable.

    The fallback is deliberately generic: it never repeats model output, source
    documents, hidden tests, or executable/markup payloads.  Its only contextual
    value is the approved primary knowledge-point identifier.
    """
    common = {
        "activity_ref": activity_ref,
        "knowledge_point_id": knowledge_point_id,
    }
    if agent == "QUIZ":
        return TutorQuizActivityDraft.model_validate(
            {
                **common,
                "activity_type": "QUIZ",
                "title": "基础互动自测",
                "difficulty": "BASIC",
                "questions": [
                    {
                        "question_ref": "fallback_choice",
                        "question_type": "SINGLE_CHOICE",
                        "stem": "完成本节学习后，下一步最合适的是？",
                        "options": [
                            {"key": "A", "text": "用自己的话复述核心概念"},
                            {"key": "B", "text": "跳过理解直接记忆结论"},
                        ],
                        "correct_option_keys": ["A"],
                        "accepted_answers": [],
                        "case_sensitive": False,
                        "explanation": "复述能帮助检查自己是否真正理解了核心概念。",
                        "score": 50,
                    },
                    {
                        "question_ref": "fallback_short_answer",
                        "question_type": "SHORT_ANSWER",
                        "stem": "本节学习的第一步应聚焦什么？请填写四个字。",
                        "options": [],
                        "correct_option_keys": [],
                        "accepted_answers": ["核心概念"],
                        "case_sensitive": False,
                        "explanation": "先聚焦核心概念，再通过复述和练习检查理解。",
                        "score": 50,
                    },
                ],
            }
        )
    if agent == "CODE_LAB":
        return TutorCodeActivityDraft.model_validate(
            {
                **common,
                "activity_type": "CODE_LAB",
                "title": "字符串整理练习",
                "difficulty": "BASIC",
                "language": "PYTHON",
                "stem": "读取一行文本，去除首尾空白后输出整理后的文本。",
                "starter_code": (
                    "import sys\n\n"
                    "def solve(text: str) -> str:\n"
                    "    # 在这里完成文本整理\n"
                    "    return text\n\n"
                    "print(solve(sys.stdin.read()))\n"
                ),
                "reference_solution": (
                    "import sys\n\n"
                    "def solve(text: str) -> str:\n"
                    "    return text.strip()\n\n"
                    "print(solve(sys.stdin.read()))\n"
                ),
                "test_cases": [
                    {
                        "case_ref": "fallback_public",
                        "input_data": "  hello  \\n",
                        "expected_output": "hello\\n",
                        "score": 50,
                        "visibility": "PUBLIC",
                        "compare_mode": "TRIM_TRAILING_WHITESPACE",
                    },
                    {
                        "case_ref": "fallback_hidden",
                        "input_data": "\\nworld\\n",
                        "expected_output": "world\\n",
                        "score": 50,
                        "visibility": "HIDDEN",
                        "compare_mode": "TRIM_TRAILING_WHITESPACE",
                    },
                ],
            }
        )
    if agent == "ANIMATION":
        return TutorAnimationActivityDraft.model_validate(
            {
                **common,
                "activity_type": "ANIMATION",
                "title": "概念学习步骤",
                "scene": {
                    "scene_type": "CONCEPT",
                    "entities": [
                        {"id": "concept", "kind": "TEXT", "label": "核心概念"},
                        {"id": "practice", "kind": "TEXT", "label": "举例练习"},
                    ],
                    "steps": [
                        {
                            "order": 1,
                            "action": "HIGHLIGHT",
                            "target_ids": ["concept"],
                            "caption": "先聚焦需要理解的核心概念。",
                            "duration_ms": 800,
                        },
                        {
                            "order": 2,
                            "action": "HIGHLIGHT",
                            "target_ids": ["practice"],
                            "caption": "再通过一个练习检验理解。",
                            "duration_ms": 800,
                        },
                    ],
                },
            }
        )
    raise ValueError("Only activity specialists have deterministic fallbacks.")


def _elapsed_ms(started: float) -> int:
    return max(0, min(2_147_483_647, int((time.perf_counter() - started) * 1_000)))
