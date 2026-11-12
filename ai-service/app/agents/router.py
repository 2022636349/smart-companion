from __future__ import annotations

import asyncio
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from pydantic import ValidationError

from app.contracts.graph import GraphRagContext
from app.contracts.tutor import (
    RouterFallbackReason,
    RouterToolInvocation,
    TutorChatRequest,
    TutorRouterDecision,
)
from app.prompts.router import TUTOR_ROUTER_PROMPT_VERSION, build_router_plan_messages
from app.providers.base import (
    ModelMessage,
    ModelProvider,
    ModelProviderError,
    ModelRequest,
    ModelResponseError,
    ModelTimeoutError,
)
from app.providers.factory import create_model_provider
from app.tools.registry import (
    RouterToolArgumentError,
    RouterToolError,
    RouterToolNotAllowedError,
    RouterToolRegistry,
    RouterToolResult,
    RouterToolTimeoutError,
)


ProviderFactory = Callable[[], ModelProvider]
MAX_ROUTER_PLAN_ATTEMPTS = 2


@dataclass(frozen=True, slots=True)
class RouterExecutionResult:
    succeeded: bool
    fallback_reason: RouterFallbackReason | None
    invocations: tuple[RouterToolInvocation, ...]
    tool_results: tuple[RouterToolResult, ...]


class TutorRouter:
    """Bounded planner/executor. It never exposes or records model reasoning text."""

    def __init__(
        self,
        tool_registry: RouterToolRegistry,
        *,
        provider_factory: ProviderFactory = create_model_provider,
        max_steps: int = 2,
        total_timeout_seconds: float = 15.0,
    ) -> None:
        if not 1 <= max_steps <= 2:
            raise ValueError("Tutor Router max_steps must be between 1 and 2.")
        self._tool_registry = tool_registry
        self._provider_factory = provider_factory
        self._max_steps = max_steps
        self._total_timeout_seconds = total_timeout_seconds

    async def run(
        self,
        request: TutorChatRequest,
        context: GraphRagContext,
        *,
        trace_request_id: str,
    ) -> RouterExecutionResult:
        observations: list[dict[str, Any]] = []
        invocations: list[RouterToolInvocation] = []
        results: list[RouterToolResult] = []
        try:
            async with asyncio.timeout(self._total_timeout_seconds):
                return await self._run_within_deadline(
                    request,
                    context,
                    trace_request_id=trace_request_id,
                    observations=observations,
                    invocations=invocations,
                    results=results,
                )
        except TimeoutError:
            # The total deadline may expire while planning a later step. Keep the
            # sanitized trace for every tool call that already completed so the
            # caller never reports an execution history that did not happen.
            return RouterExecutionResult(
                succeeded=False,
                fallback_reason="TOOL_TIMEOUT",
                invocations=tuple(invocations),
                tool_results=tuple(results),
            )

    async def _run_within_deadline(
        self,
        request: TutorChatRequest,
        context: GraphRagContext,
        *,
        trace_request_id: str,
        observations: list[dict[str, Any]],
        invocations: list[RouterToolInvocation],
        results: list[RouterToolResult],
    ) -> RouterExecutionResult:
        for step in range(1, self._max_steps + 1):
            decision, failure = await self._plan(
                request,
                context,
                observations,
                step=step,
                request_id=trace_request_id,
            )
            if decision is None:
                return RouterExecutionResult(
                    succeeded=False,
                    fallback_reason=failure or "ROUTER_OUTPUT_INVALID",
                    invocations=tuple(invocations),
                    tool_results=tuple(results),
                )
            if decision.action == "FINAL":
                return RouterExecutionResult(
                    succeeded=True,
                    fallback_reason=None,
                    invocations=tuple(invocations),
                    tool_results=tuple(results),
                )

            started = time.perf_counter()
            try:
                result = await self._tool_registry.execute(
                    decision,
                    request,
                    trace_request_id=trace_request_id,
                )
            except RouterToolNotAllowedError as exc:
                del exc
                return RouterExecutionResult(
                    succeeded=False,
                    fallback_reason="TOOL_NOT_ALLOWED",
                    invocations=tuple(invocations),
                    tool_results=tuple(results),
                )
            except RouterToolArgumentError as exc:
                invocations.append(
                    _failed_invocation(
                        step,
                        decision,
                        status="REJECTED",
                        started=started,
                        error_code=exc.code,
                    )
                )
                return RouterExecutionResult(
                    succeeded=False,
                    fallback_reason="TOOL_ARGUMENT_INVALID",
                    invocations=tuple(invocations),
                    tool_results=tuple(results),
                )
            except RouterToolTimeoutError as exc:
                invocations.append(
                    _failed_invocation(
                        step,
                        decision,
                        status="TIMED_OUT",
                        started=started,
                        error_code=exc.code,
                    )
                )
                return RouterExecutionResult(
                    succeeded=False,
                    fallback_reason="TOOL_TIMEOUT",
                    invocations=tuple(invocations),
                    tool_results=tuple(results),
                )
            except Exception as exc:
                error_code = (
                    exc.code
                    if isinstance(exc, RouterToolError)
                    else getattr(exc, "code", "TUTOR_ROUTER_TOOL_FAILED")
                )
                invocations.append(
                    _failed_invocation(
                        step,
                        decision,
                        status="FAILED",
                        started=started,
                        error_code=str(error_code)[:80],
                    )
                )
                return RouterExecutionResult(
                    succeeded=False,
                    fallback_reason="TOOL_FAILED",
                    invocations=tuple(invocations),
                    tool_results=tuple(results),
                )

            invocations.append(
                RouterToolInvocation(
                    step=step,
                    tool=result.tool,
                    reason_code=decision.reason_code,
                    status="SUCCEEDED",
                    duration_ms=_elapsed_ms(started),
                    item_count=result.item_count,
                )
            )
            results.append(result)
            observations.append(
                {
                    "tool": result.tool,
                    "item_count": result.item_count,
                    "data": result.observation,
                }
            )

        # The answer generator runs after the bounded number of successful tools;
        # no third planning call can occur.
        return RouterExecutionResult(
            succeeded=True,
            fallback_reason=None,
            invocations=tuple(invocations),
            tool_results=tuple(results),
        )

    async def _plan(
        self,
        request: TutorChatRequest,
        context: GraphRagContext,
        observations: list[dict[str, Any]],
        *,
        step: int,
        request_id: str,
    ) -> tuple[TutorRouterDecision | None, RouterFallbackReason | None]:
        base_messages = build_router_plan_messages(
            request,
            context,
            observations,
            step=step,
        )
        for attempt in range(MAX_ROUTER_PLAN_ATTEMPTS):
            provider = self._provider_factory()
            try:
                messages = base_messages
                if attempt > 0:
                    messages = (
                        *base_messages,
                        ModelMessage(
                            role="user",
                            content=(
                                "上一次路由 JSON 未通过结构校验。请从头返回一个完整 JSON 对象，"
                                "action 只能是允许的只读工具或 FINAL，arguments 必须符合该 action；"
                                "不要解释修复过程。"
                            ),
                        ),
                    )
                try:
                    response = await provider.generate(
                        ModelRequest(
                            messages=messages,
                            prompt_version=TUTOR_ROUTER_PROMPT_VERSION,
                            # Modern reasoning models charge hidden reasoning against the
                            # completion budget before emitting this small JSON decision.
                            # The step count and wall-clock deadline remain the hard bounds.
                            max_tokens=2_000,
                            temperature=0.0,
                            request_id=request_id,
                            capability="TUTOR_ROUTER_PLAN",
                        ),
                        output_model=TutorRouterDecision,
                    )
                    raw = response.structured_content
                    if not isinstance(raw, dict):
                        raise ValueError("Router output root is not an object.")
                    action = raw.get("action")
                    if action not in {*RouterToolRegistry.ALLOWED_TOOLS, "FINAL"}:
                        return None, "TOOL_NOT_ALLOWED"
                    decision = TutorRouterDecision.model_validate(raw)
                    if response.metadata.prompt_version != TUTOR_ROUTER_PROMPT_VERSION:
                        raise ValueError("Router prompt version mismatch.")
                    return decision, None
                except ModelTimeoutError:
                    return None, "TOOL_TIMEOUT"
                except (
                    ModelResponseError,
                    ValidationError,
                    ValueError,
                    TypeError,
                ):
                    if attempt + 1 == MAX_ROUTER_PLAN_ATTEMPTS:
                        return None, "ROUTER_OUTPUT_INVALID"
                except ModelProviderError:
                    return None, "ROUTER_OUTPUT_INVALID"
            finally:
                await provider.aclose()
        return None, "ROUTER_OUTPUT_INVALID"


def _elapsed_ms(started: float) -> int:
    return max(0, int((time.perf_counter() - started) * 1_000))


def _failed_invocation(
    step: int,
    decision: TutorRouterDecision,
    *,
    status: str,
    started: float,
    error_code: str,
) -> RouterToolInvocation:
    return RouterToolInvocation(
        step=step,
        tool=decision.action,
        reason_code=decision.reason_code,
        status=status,
        duration_ms=_elapsed_ms(started),
        item_count=0,
        error_code=error_code,
    )
