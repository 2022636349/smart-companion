import asyncio
import unittest

from app.agents.router import RouterExecutionResult, TutorRouter
from app.agents.tutor import TutorAgent
from app.contracts.graph import (
    GraphNodeListResponse,
    GraphRecommendationResponse,
    GraphSearchResponse,
)
from app.contracts.memory import MemorySearchResponse
from app.contracts.tutor import RouterToolInvocation, TutorChatRequest
from app.prompts.router import (
    TUTOR_ROUTER_ANSWER_PROMPT_VERSION,
    TUTOR_ROUTER_PROMPT_VERSION,
)
from app.providers.base import (
    ModelCallMetadata,
    ModelRequest,
    ModelResponse,
    TokenUsage,
)
from app.tools.registry import RouterToolRegistry, RouterToolResult
from tests.test_tutor_agent import (
    FakeKnowledgeClient,
    approved_context,
    model_output,
)


def response(content, prompt_version: str) -> ModelResponse:
    return ModelResponse(
        text="structured",
        structured_content=content,
        metadata=ModelCallMetadata(
            provider="mock",
            model="mock-router-model",
            token_usage=TokenUsage(1, 1, 2),
            finish_reason="stop",
            latency_ms=1,
            provider_request_id="provider-router-1",
            prompt_version=prompt_version,
            attempts=1,
        ),
    )


class QueueProvider:
    provider_name = "mock"
    model_name = "mock-router-model"

    def __init__(self, item: ModelResponse, *, delay: float = 0.0) -> None:
        self.item = item
        self.delay = delay
        self.requests: list[ModelRequest] = []
        self.closed = False

    async def generate(self, request, *, output_model=None):
        self.requests.append(request)
        if self.delay:
            await asyncio.sleep(self.delay)
        return self.item

    async def aclose(self):
        self.closed = True

    async def check_health(self):
        raise AssertionError


class ProviderFactory:
    def __init__(self, responses):
        self.providers = [
            QueueProvider(
                item[0] if isinstance(item, tuple) else item,
                delay=item[1] if isinstance(item, tuple) else 0.0,
            )
            for item in responses
        ]
        self.calls = 0

    def __call__(self):
        provider = self.providers[self.calls]
        self.calls += 1
        return provider


def tutor_request(
    *, mode="ROUTER", personalized=False, course_id: int = 1
) -> TutorChatRequest:
    return TutorChatRequest(
        course_id=course_id,
        course_name="Python程序设计" if course_id == 1 else "高等数学",
        enabled_activity_types=["EXPLAIN", "QUIZ", "CODE", "VISUAL"],
        request_id="trace-request-0001",
        user_id=987654321,
        knowledge_point_id="kp-python-functions",
        message="我应该先学什么？",
        student_context={
            "entry_source": "GRAPH",
            "personalization_enabled": personalized,
        },
        agent_mode=mode,
        teaching_mode="EXPLAIN",
        mastery_context=[
            {
                "knowledge_point_id": "kp-python-functions",
                "score": 40,
                "confidence": 0.9,
            }
        ],
    )


class RouterKnowledgeClient(FakeKnowledgeClient):
    def __init__(self, *, pending_search=False, delay=0.0):
        super().__init__()
        self.pending_search = pending_search
        self.delay = delay
        self.tool_calls = []

    async def search_graph(self, query, *, course_id, request_id, limit=5):
        self.tool_calls.append(("search", course_id, query, request_id, limit))
        if self.delay:
            await asyncio.sleep(self.delay)
        status = "PENDING" if self.pending_search else "APPROVED"
        return GraphSearchResponse.model_validate(
            {
                "query": query,
                "items": [
                    {
                        "id": "kp-python-functions",
                        "name": "Python 函数",
                        "level": 3,
                        "node_type": "knowledge_point",
                        "review_status": status,
                        "content_version": 1,
                    }
                ],
            }
        )

    async def get_prerequisites(
        self, knowledge_point_id, *, course_id, request_id, max_depth=5
    ):
        self.tool_calls.append(
            ("prerequisites", course_id, knowledge_point_id, request_id, max_depth)
        )
        return GraphNodeListResponse(items=[])

    async def recommend_next(
        self, *, course_id, student_id, mastery, request_id, limit=5
    ):
        self.tool_calls.append(
            ("recommend", course_id, student_id, mastery, request_id, limit)
        )
        return GraphRecommendationResponse.model_validate(
            {"items": [], "graph_version": "graph-v1", "course_id": course_id}
        )


class CrossStudentMemory:
    async def search(self, request, *, trace_request_id):
        return MemorySearchResponse.model_validate(
            {
                "request_id": request.request_id,
                "course_id": request.course_id,
                "student_id": request.student_id,
                "items": [
                    {
                        "memory_id": 1,
                        "course_id": request.course_id,
                        "student_id": request.student_id + 1,
                        "memory_type": "MISCONCEPTION",
                        "content": "cross student",
                        "score": 0.9,
                        "version": 1,
                        "generation": 1,
                    }
                ],
                "collection": "learning_memory",
                "memory_index_epoch": "0123456789abcdef",
                "memory_rebuild_required": False,
            }
        )


class CrossCourseMemory:
    async def search(self, request, *, trace_request_id):
        return MemorySearchResponse.model_validate(
            {
                "request_id": request.request_id,
                "course_id": request.course_id,
                "student_id": request.student_id,
                "items": [
                    {
                        "memory_id": 2,
                        "course_id": request.course_id + 1,
                        "student_id": request.student_id,
                        "memory_type": "MISCONCEPTION",
                        "content": "cross course",
                        "score": 0.9,
                        "version": 1,
                        "generation": 1,
                    }
                ],
                "collection": "learning_memory",
                "memory_index_epoch": "0123456789abcdef",
                "memory_rebuild_required": False,
            }
        )


class CapturingMemory:
    def __init__(self) -> None:
        self.requests = []

    async def search(self, request, *, trace_request_id):
        self.requests.append((request, trace_request_id))
        return MemorySearchResponse.model_validate(
            {
                "request_id": request.request_id,
                "course_id": request.course_id,
                "student_id": request.student_id,
                "items": [],
                "collection": "learning_memory",
                "memory_index_epoch": "0123456789abcdef",
                "memory_rebuild_required": False,
            }
        )


class StubRouter:
    def __init__(self, result):
        self.result = result
        self.calls = 0

    async def run(self, request, context, *, trace_request_id):
        self.calls += 1
        return self.result


class TutorRouterTest(unittest.IsolatedAsyncioTestCase):
    def make_router(self, decisions, *, knowledge=None, memory=None, timeout=1.0):
        factory = ProviderFactory(
            [response(item, TUTOR_ROUTER_PROMPT_VERSION) for item in decisions]
        )
        registry = RouterToolRegistry(
            knowledge or RouterKnowledgeClient(),
            memory_service_factory=lambda: memory or CrossStudentMemory(),
            tool_timeout_seconds=timeout,
        )
        return (
            TutorRouter(
                registry,
                provider_factory=factory,
                max_steps=2,
                total_timeout_seconds=2,
            ),
            factory,
        )

    async def test_unknown_tool_is_rejected_without_execution(self):
        router, factory = self.make_router(
            [
                {
                    "action": "shell.exec",
                    "arguments": {"command": "whoami"},
                    "reason_code": "MALICIOUS",
                    "confidence": 1,
                }
            ]
        )
        result = await router.run(
            tutor_request(), approved_context(), trace_request_id="trace-request-0001"
        )
        self.assertFalse(result.succeeded)
        self.assertEqual(result.fallback_reason, "TOOL_NOT_ALLOWED")
        self.assertEqual(result.invocations, ())
        self.assertEqual(factory.calls, 1)
        self.assertEqual(factory.providers[0].requests[0].max_tokens, 2_000)

    async def test_invalid_tool_arguments_are_rejected_and_traced(self):
        router, _ = self.make_router(
            [
                {
                    "action": "memory.search",
                    "arguments": {"student_id": 999},
                    "reason_code": "USE_MEMORY",
                    "confidence": 0.9,
                }
            ]
        )
        result = await router.run(
            tutor_request(personalized=True),
            approved_context(),
            trace_request_id="trace-request-0001",
        )
        self.assertEqual(result.fallback_reason, "TOOL_ARGUMENT_INVALID")
        self.assertEqual(result.invocations[0].status, "REJECTED")

    async def test_model_cannot_override_course_scope(self):
        router, _ = self.make_router(
            [
                {
                    "action": "graph.search",
                    "arguments": {"query": "函数", "course_id": 2},
                    "reason_code": "SEARCH_OTHER_COURSE",
                    "confidence": 0.9,
                }
            ]
        )
        result = await router.run(
            tutor_request(course_id=1),
            approved_context(),
            trace_request_id="trace-request-0001",
        )
        self.assertEqual(result.fallback_reason, "TOOL_ARGUMENT_INVALID")
        self.assertEqual(result.invocations[0].status, "REJECTED")

    async def test_graph_tool_injects_spring_course_for_same_concept(self):
        knowledge = RouterKnowledgeClient()
        router, _ = self.make_router(
            [
                {
                    "action": "graph.search",
                    "arguments": {"query": "函数"},
                    "reason_code": "SEARCH_CONCEPT",
                    "confidence": 0.9,
                },
                {
                    "action": "FINAL",
                    "arguments": {},
                    "reason_code": "CONTEXT_READY",
                    "confidence": 0.9,
                },
            ],
            knowledge=knowledge,
        )
        result = await router.run(
            tutor_request(course_id=2),
            approved_context().model_copy(update={"course_id": 2}),
            trace_request_id="trace-request-0001",
        )
        self.assertTrue(result.succeeded)
        self.assertEqual(knowledge.tool_calls[0][0:3], ("search", 2, "函数"))

    async def test_memory_tool_injects_and_validates_course_scope(self):
        memory = CapturingMemory()
        router, _ = self.make_router(
            [
                {
                    "action": "memory.search",
                    "arguments": {},
                    "reason_code": "USE_MEMORY",
                    "confidence": 0.9,
                },
                {
                    "action": "FINAL",
                    "arguments": {},
                    "reason_code": "MEMORY_READY",
                    "confidence": 0.9,
                },
            ],
            memory=memory,
        )
        result = await router.run(
            tutor_request(personalized=True, course_id=2),
            approved_context().model_copy(update={"course_id": 2}),
            trace_request_id="trace-request-0001",
        )
        self.assertTrue(result.succeeded)
        self.assertEqual(memory.requests[0][0].course_id, 2)

    async def test_cross_course_memory_result_fails_closed(self):
        router, _ = self.make_router(
            [
                {
                    "action": "memory.search",
                    "arguments": {},
                    "reason_code": "USE_MEMORY",
                    "confidence": 0.9,
                }
            ],
            memory=CrossCourseMemory(),
        )
        result = await router.run(
            tutor_request(personalized=True, course_id=1),
            approved_context(),
            trace_request_id="trace-request-0001",
        )
        self.assertFalse(result.succeeded)
        self.assertEqual(result.fallback_reason, "TOOL_FAILED")

    async def test_pending_graph_result_fails_closed(self):
        router, _ = self.make_router(
            [
                {
                    "action": "graph.search",
                    "arguments": {"query": "函数"},
                    "reason_code": "SEARCH_CONCEPT",
                    "confidence": 0.9,
                }
            ],
            knowledge=RouterKnowledgeClient(pending_search=True),
        )
        result = await router.run(
            tutor_request(), approved_context(), trace_request_id="trace-request-0001"
        )
        self.assertFalse(result.succeeded)
        self.assertEqual(result.fallback_reason, "TOOL_FAILED")
        self.assertEqual(result.invocations[0].status, "FAILED")

    async def test_cross_student_memory_result_fails_closed(self):
        router, _ = self.make_router(
            [
                {
                    "action": "memory.search",
                    "arguments": {},
                    "reason_code": "USE_MEMORY",
                    "confidence": 0.9,
                }
            ],
            memory=CrossStudentMemory(),
        )
        result = await router.run(
            tutor_request(personalized=True),
            approved_context(),
            trace_request_id="trace-request-0001",
        )
        self.assertFalse(result.succeeded)
        self.assertEqual(result.fallback_reason, "TOOL_FAILED")

    async def test_router_executes_at_most_two_steps(self):
        router, factory = self.make_router(
            [
                {
                    "action": "graph.search",
                    "arguments": {"query": "函数"},
                    "reason_code": "SEARCH_CONCEPT",
                    "confidence": 0.9,
                },
                {
                    "action": "graph.prerequisites",
                    "arguments": {"knowledge_point_id": "kp-python-functions"},
                    "reason_code": "CHECK_PREREQUISITES",
                    "confidence": 0.9,
                },
                {
                    "action": "graph.search",
                    "arguments": {"query": "must-not-run"},
                    "reason_code": "THIRD_STEP",
                    "confidence": 0.9,
                },
            ]
        )
        result = await router.run(
            tutor_request(), approved_context(), trace_request_id="trace-request-0001"
        )
        self.assertTrue(result.succeeded)
        self.assertEqual(len(result.invocations), 2)
        self.assertEqual(factory.calls, 2)

    async def test_tool_timeout_is_bounded_and_explicit(self):
        router, _ = self.make_router(
            [
                {
                    "action": "graph.search",
                    "arguments": {"query": "函数"},
                    "reason_code": "SEARCH_CONCEPT",
                    "confidence": 0.9,
                }
            ],
            knowledge=RouterKnowledgeClient(delay=0.05),
            timeout=0.01,
        )
        result = await router.run(
            tutor_request(), approved_context(), trace_request_id="trace-request-0001"
        )
        self.assertEqual(result.fallback_reason, "TOOL_TIMEOUT")
        self.assertEqual(result.invocations[0].status, "TIMED_OUT")

    async def test_total_timeout_preserves_completed_first_step_trace(self):
        first = response(
            {
                "action": "graph.search",
                "arguments": {"query": "函数"},
                "reason_code": "SEARCH_CONCEPT",
                "confidence": 0.9,
            },
            TUTOR_ROUTER_PROMPT_VERSION,
        )
        second = response(
            {
                "action": "FINAL",
                "arguments": {},
                "reason_code": "BASE_CONTEXT_SUFFICIENT",
                "confidence": 0.9,
            },
            TUTOR_ROUTER_PROMPT_VERSION,
        )
        factory = ProviderFactory([first, (second, 0.1)])
        router = TutorRouter(
            RouterToolRegistry(
                RouterKnowledgeClient(),
                memory_service_factory=CrossStudentMemory,
                tool_timeout_seconds=1,
            ),
            provider_factory=factory,
            max_steps=2,
            total_timeout_seconds=0.03,
        )

        result = await router.run(
            tutor_request(), approved_context(), trace_request_id="trace-request-0001"
        )

        self.assertFalse(result.succeeded)
        self.assertEqual(result.fallback_reason, "TOOL_TIMEOUT")
        self.assertEqual(len(result.invocations), 1)
        self.assertEqual(result.invocations[0].tool, "graph.search")
        self.assertEqual(result.invocations[0].status, "SUCCEEDED")
        self.assertEqual(len(result.tool_results), 1)
        self.assertEqual(result.tool_results[0].tool, "graph.search")
        self.assertNotIn("observation", result.invocations[0].model_dump(mode="json"))

    async def test_invalid_json_like_output_is_explicit(self):
        factory = ProviderFactory(
            [
                response(None, TUTOR_ROUTER_PROMPT_VERSION),
                response(None, TUTOR_ROUTER_PROMPT_VERSION),
            ]
        )
        router = TutorRouter(
            RouterToolRegistry(
                RouterKnowledgeClient(),
                memory_service_factory=CrossStudentMemory,
                tool_timeout_seconds=1,
            ),
            provider_factory=factory,
        )
        result = await router.run(
            tutor_request(), approved_context(), trace_request_id="trace-request-0001"
        )
        self.assertEqual(result.fallback_reason, "ROUTER_OUTPUT_INVALID")
        self.assertEqual(factory.calls, 2)

    async def test_invalid_later_plan_is_repaired_without_reexecuting_tool(self):
        factory = ProviderFactory(
            [
                response(
                    {
                        "action": "graph.search",
                        "arguments": {"query": "函数"},
                        "reason_code": "SEARCH_CONCEPT",
                        "confidence": 0.9,
                    },
                    TUTOR_ROUTER_PROMPT_VERSION,
                ),
                response(None, TUTOR_ROUTER_PROMPT_VERSION),
                response(
                    {
                        "action": "FINAL",
                        "arguments": {},
                        "reason_code": "CONTEXT_READY",
                        "confidence": 0.9,
                    },
                    TUTOR_ROUTER_PROMPT_VERSION,
                ),
            ]
        )
        knowledge = RouterKnowledgeClient()
        router = TutorRouter(
            RouterToolRegistry(
                knowledge,
                memory_service_factory=CrossStudentMemory,
                tool_timeout_seconds=1,
            ),
            provider_factory=factory,
            max_steps=2,
            total_timeout_seconds=2,
        )

        result = await router.run(
            tutor_request(), approved_context(), trace_request_id="trace-request-0001"
        )

        self.assertTrue(result.succeeded)
        self.assertEqual(len(result.invocations), 1)
        self.assertEqual(len(knowledge.tool_calls), 1)
        self.assertEqual(factory.calls, 3)
        self.assertIn(
            "上一次路由 JSON 未通过结构校验",
            factory.providers[2].requests[0].messages[-1].content,
        )

    async def test_failed_router_uses_explicit_fixed_fallback(self):
        fixed_provider = ProviderFactory(
            [response(model_output(), "tutor-graph-rag-v4")]
        )
        agent = TutorAgent(
            FakeKnowledgeClient(),
            provider_factory=fixed_provider,
            router_factory=lambda: StubRouter(
                RouterExecutionResult(
                    succeeded=False,
                    fallback_reason="TOOL_NOT_ALLOWED",
                    invocations=(),
                    tool_results=(),
                )
            ),
        )
        result = await agent.chat(
            tutor_request(), trace_request_id="trace-request-0001"
        )
        self.assertEqual(result.agent_trace.effective_mode, "FIXED_FALLBACK")
        self.assertEqual(result.agent_trace.fallback_reason, "TOOL_NOT_ALLOWED")
        self.assertEqual(result.prompt_version, "tutor-graph-rag-v4")

    async def test_router_final_answer_has_router_trace_and_no_cot(self):
        provider_factory = ProviderFactory(
            [
                response(
                    {
                        "action": "FINAL",
                        "arguments": {},
                        "reason_code": "BASE_CONTEXT_SUFFICIENT",
                        "confidence": 0.95,
                    },
                    TUTOR_ROUTER_PROMPT_VERSION,
                ),
                response(model_output(), TUTOR_ROUTER_ANSWER_PROMPT_VERSION),
            ]
        )
        agent = TutorAgent(
            RouterKnowledgeClient(),
            provider_factory=provider_factory,
        )
        result = await agent.chat(
            tutor_request(), trace_request_id="trace-request-0001"
        )
        trace = result.agent_trace.model_dump(mode="json")
        self.assertEqual(trace["effective_mode"], "ROUTER")
        self.assertEqual(trace["tool_invocations"], [])
        self.assertNotIn("reasoning", trace)
        self.assertNotIn("thought", trace)
        self.assertEqual(result.prompt_version, TUTOR_ROUTER_ANSWER_PROMPT_VERSION)

    async def test_invalid_final_answer_retries_without_rerunning_router_or_tools(self):
        route = RouterExecutionResult(
            succeeded=True,
            fallback_reason=None,
            invocations=(
                RouterToolInvocation(
                    step=1,
                    tool="graph.rag_context",
                    reason_code="GROUND_IN_GRAPH",
                    status="SUCCEEDED",
                    duration_ms=1,
                    item_count=1,
                ),
            ),
            tool_results=(
                RouterToolResult(
                    tool="graph.rag_context",
                    item_count=1,
                    observation={"knowledge_point_id": "kp-python-functions"},
                ),
            ),
        )
        router = StubRouter(route)
        provider_factory = ProviderFactory(
            [
                response(
                    model_output(source_id="invented-source"),
                    TUTOR_ROUTER_ANSWER_PROMPT_VERSION,
                ),
                response(model_output(), TUTOR_ROUTER_ANSWER_PROMPT_VERSION),
            ]
        )
        agent = TutorAgent(
            FakeKnowledgeClient(),
            provider_factory=provider_factory,
            router_factory=lambda: router,
        )

        result = await agent.chat(
            tutor_request(), trace_request_id="trace-request-0001"
        )

        self.assertEqual(router.calls, 1)
        self.assertEqual(provider_factory.calls, 2)
        self.assertTrue(all(item.closed for item in provider_factory.providers))
        self.assertEqual(result.agent_trace.total_steps, 1)
        self.assertEqual(result.agent_trace.tool_invocations[0].tool, "graph.rag_context")
        self.assertEqual(result.agent_trace.effective_mode, "ROUTER")

    async def test_shadow_keeps_fixed_answer_and_records_shadow_mode(self):
        fixed_provider = ProviderFactory(
            [response(model_output(), "tutor-graph-rag-v4")]
        )
        agent = TutorAgent(
            FakeKnowledgeClient(),
            provider_factory=fixed_provider,
            router_factory=lambda: StubRouter(
                RouterExecutionResult(
                    succeeded=True,
                    fallback_reason=None,
                    invocations=(),
                    tool_results=(),
                )
            ),
        )
        result = await agent.chat(
            tutor_request(mode="SHADOW"),
            trace_request_id="trace-request-0001",
        )
        self.assertEqual(result.agent_trace.effective_mode, "SHADOW")
        self.assertEqual(result.prompt_version, "tutor-graph-rag-v4")


if __name__ == "__main__":
    unittest.main()
