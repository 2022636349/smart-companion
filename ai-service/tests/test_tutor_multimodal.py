import asyncio
import unittest
from unittest.mock import patch

from app.agents.teaching import (
    ActivitySpecialist,
    SpecialistActivityResult,
    TutorActivityVerificationError,
    TutorActivityVerifier,
    stable_activity_ref,
)
from app.agents.tutor import TutorAgent, _fallback_teaching_plan
from app.contracts.tutor import (
    TutorAnimationActivityDraft,
    TutorChatRequest,
    TutorCodeActivityDraft,
    TutorModelOutput,
    TutorQuizActivityDraft,
    TutorSpecialistInvocation,
    TutorTeachingPlan,
)
from app.providers.base import (
    ModelCallMetadata,
    ModelRequest,
    ModelResponse,
    ModelServerError,
    TokenUsage,
)
from app.prompts.teaching import (
    CODE_LAB_SYSTEM_PROMPT,
    QUIZ_SYSTEM_PROMPT,
    TUTOR_CODE_LAB_SPECIALIST_PROMPT_VERSION,
    TUTOR_QUIZ_SPECIALIST_PROMPT_VERSION,
)
from app.prompts.tutor import SYSTEM_PROMPT, TUTOR_PROMPT_VERSION
from tests.test_tutor_agent import FakeKnowledgeClient, approved_context, model_output


def _response(request: ModelRequest, content: dict) -> ModelResponse:
    return ModelResponse(
        text="structured",
        structured_content=content,
        metadata=ModelCallMetadata(
            provider="cycle8-mock",
            model="cycle8-model",
            token_usage=TokenUsage(10, 5, 15),
            finish_reason="stop",
            latency_ms=5,
            provider_request_id="cycle8-provider-request",
            prompt_version=request.prompt_version,
            attempts=1,
        ),
    )


def _quiz(activity_ref: str) -> dict:
    return {
        "activity_ref": activity_ref,
        "activity_type": "QUIZ",
        "title": "函数随堂自测",
        "knowledge_point_id": "kp-python-functions",
        "difficulty": "BASIC",
        "questions": [
            {
                "question_ref": "question_1",
                "question_type": "SINGLE_CHOICE",
                "stem": "Python 用哪个关键字定义函数？",
                "options": [
                    {"key": "A", "text": "def"},
                    {"key": "B", "text": "class"},
                ],
                "correct_option_keys": ["A"],
                "accepted_answers": [],
                "case_sensitive": False,
                "explanation": "def 用于定义函数。",
                "score": 100,
            }
        ],
    }


def _animation(activity_ref: str, *, label: str = "函数调用") -> dict:
    return {
        "activity_ref": activity_ref,
        "activity_type": "ANIMATION",
        "title": "函数调用过程",
        "knowledge_point_id": "kp-python-functions",
        "scene": {
            "scene_type": "CODE_FLOW",
            "entities": [
                {
                    "id": "line_1",
                    "kind": "CODE_LINE",
                    "label": label,
                    "values": [],
                }
            ],
            "steps": [
                {
                    "order": 1,
                    "action": "FOCUS_CODE",
                    "target_ids": ["line_1"],
                    "caption": "执行函数调用语句。",
                    "duration_ms": 500,
                }
            ],
        },
    }


def _code_lab(activity_ref: str) -> dict:
    return {
        "activity_ref": activity_ref,
        "activity_type": "CODE_LAB",
        "title": "字符串整理练习",
        "knowledge_point_id": "kp-python-functions",
        "difficulty": "BASIC",
        "language": "PYTHON",
        "stem": "实现 solve 函数，去除输入文本的首尾空白。",
        "starter_code": "def solve(text: str) -> str:\n    return text",
        "reference_solution": "def solve(text: str) -> str:\n    return text.strip()",
        "test_cases": [
            {
                "case_ref": "public_case",
                "input_data": "  hello  \n",
                "expected_output": "hello\n",
                "score": 50,
                "visibility": "PUBLIC",
                "compare_mode": "TRIM_TRAILING_WHITESPACE",
            },
            {
                "case_ref": "hidden_case",
                "input_data": "\nworld\n",
                "expected_output": "world\n",
                "score": 50,
                "visibility": "HIDDEN",
                "compare_mode": "TRIM_TRAILING_WHITESPACE",
            },
        ],
    }


class AdaptiveProvider:
    provider_name = "cycle8-mock"
    model_name = "cycle8-model"

    def __init__(self, factory) -> None:
        self.factory = factory
        self.closed = False

    async def generate(self, request: ModelRequest, *, output_model=None):
        self.factory.output_models.append(output_model)
        self.factory.active += 1
        self.factory.maximum_active = max(
            self.factory.maximum_active, self.factory.active
        )
        try:
            await asyncio.sleep(0.01)
            if output_model is TutorTeachingPlan:
                if self.factory.planner_error:
                    raise ModelServerError("planner unavailable")
                agents = self.factory.planner_agents or [
                    "EXPLANATION",
                    "QUIZ",
                    "ANIMATION",
                ]
                return _response(
                    request,
                    {
                        "goal": "讲解后用题目和动画帮助理解。",
                        "modalities": agents,
                        "specialist_agents": agents,
                    },
                )
            if output_model is TutorModelOutput:
                self.factory.explanation_calls += 1
                if (
                    self.factory.explanation_invalid_once
                    and self.factory.explanation_calls == 1
                ):
                    invalid = model_output()
                    invalid["blocks"][0]["type"] = "quiz"
                    return _response(request, invalid)
                if self.factory.explanation_calls > 1:
                    self.factory.explanation_repair_seen = (
                        "blocks 只能使用 markdown 或 code"
                        in request.messages[-1].content
                    )
                explanation_output = self.factory.explanation_output
                return _response(
                    request,
                    explanation_output
                    if explanation_output is not None
                    else model_output(),
                )
            if output_model is TutorQuizActivityDraft:
                return _response(
                    request,
                    _quiz(stable_activity_ref("cycle8-request-0001", "QUIZ")),
                )
            if output_model is TutorCodeActivityDraft:
                return _response(
                    request,
                    _code_lab(
                        stable_activity_ref("cycle8-request-0001", "CODE_LAB")
                    ),
                )
            if output_model is TutorAnimationActivityDraft:
                return _response(
                    request,
                    _animation(stable_activity_ref("cycle8-request-0001", "ANIMATION")),
                )
            raise AssertionError(f"unexpected output model: {output_model}")
        finally:
            self.factory.active -= 1

    async def aclose(self) -> None:
        self.closed = True

    async def check_health(self):
        raise AssertionError("health is not part of tutor generation")


class AdaptiveProviderFactory:
    def __init__(
        self,
        *,
        planner_error: bool = False,
        explanation_invalid_once: bool = False,
        explanation_output: dict | None = None,
        planner_agents: list[str] | None = None,
    ) -> None:
        self.planner_error = planner_error
        self.explanation_invalid_once = explanation_invalid_once
        self.explanation_output = explanation_output
        self.planner_agents = planner_agents
        self.explanation_calls = 0
        self.explanation_repair_seen = False
        self.active = 0
        self.maximum_active = 0
        self.output_models: list[type | None] = []
        self.providers: list[AdaptiveProvider] = []

    def __call__(self) -> AdaptiveProvider:
        provider = AdaptiveProvider(self)
        self.providers.append(provider)
        return provider


class FailingProvider:
    provider_name = "cycle8-failing"
    model_name = "cycle8-failing"

    async def generate(self, request: ModelRequest, *, output_model=None):
        raise ModelServerError("private upstream detail must not enter trace")

    async def aclose(self) -> None:
        return None

    async def check_health(self):
        raise AssertionError


class RepairingQuizProvider:
    provider_name = "cycle8-repairing"
    model_name = "cycle8-repairing"

    def __init__(self, factory) -> None:
        self.factory = factory
        self.closed = False

    async def generate(self, request: ModelRequest, *, output_model=None):
        self.factory.calls += 1
        if self.factory.calls == 1:
            return _response(request, _quiz("wrong_activity_ref"))
        self.factory.repair_seen = (
            "上一次活动 JSON 未通过结构或业务校验"
            in request.messages[-1].content
        )
        return _response(
            request,
            _quiz(stable_activity_ref("cycle8-request-0001", "QUIZ")),
        )

    async def aclose(self) -> None:
        self.closed = True


class RepairingQuizProviderFactory:
    def __init__(self) -> None:
        self.calls = 0
        self.repair_seen = False
        self.providers: list[RepairingQuizProvider] = []

    def __call__(self) -> RepairingQuizProvider:
        provider = RepairingQuizProvider(self)
        self.providers.append(provider)
        return provider


class TutorMultimodalTest(unittest.IsolatedAsyncioTestCase):
    @staticmethod
    def request(mode: str) -> TutorChatRequest:
        return TutorChatRequest(
            course_id=1,
            course_name="Python程序设计",
            enabled_activity_types=["EXPLAIN", "QUIZ", "CODE", "VISUAL"],
            request_id="cycle8-request-0001",
            user_id=8,
            session_id=80,
            knowledge_point_id="kp-python-functions",
            message="请用多种方式讲解 Python 函数。",
            history=[],
            student_context={
                "entry_source": "MANUAL",
                "personalization_enabled": False,
            },
            agent_mode="FIXED",
            teaching_mode=mode,
            mastery_context=[],
        )

    async def test_explicit_quiz_mode_returns_one_verified_activity_and_reference(self):
        factory = AdaptiveProviderFactory()
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)

        result = await agent.chat(
            self.request("QUIZ"), trace_request_id="cycle8-request-0001"
        )

        self.assertEqual(result.teaching_plan.specialist_agents, ["EXPLANATION", "QUIZ"])
        self.assertEqual([item.agent for item in result.specialist_trace], ["EXPLANATION", "QUIZ"])
        self.assertEqual(len(result.activities), 1)
        activity = result.activities[0]
        self.assertIsInstance(activity, TutorQuizActivityDraft)
        self.assertEqual(
            activity.activity_ref, stable_activity_ref("cycle8-request-0001", "QUIZ")
        )
        reference = [block for block in result.blocks if block.activity_ref]
        self.assertEqual(len(reference), 1)
        self.assertEqual(reference[0].type, "quiz")
        self.assertEqual(reference[0].activity_ref, activity.activity_ref)
        self.assertEqual(factory.maximum_active, 2)
        self.assertTrue(all(provider.closed for provider in factory.providers))

    async def test_verified_quiz_replaces_answer_leaking_explanation(self):
        leaking_output = model_output()
        leaking_output["answer"] = "正确答案是 A，使用 def。"
        leaking_output["suggested_next_step"] = "下一步直接选 A，答案是 def。"
        leaking_output["cognitive_labels"][0]["evidence"] = "正确答案 A 已确定。"
        leaking_output["blocks"] = [
            {
                "type": "markdown",
                "content": "请选 A；这道题的正确答案是 def。",
            },
            {
                "type": "code",
                "language": "python",
                "content": "def answer():\n    return 'def'",
            },
        ]
        factory = AdaptiveProviderFactory(explanation_output=leaking_output)
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)

        result = await agent.chat(
            self.request("QUIZ"), trace_request_id="cycle8-request-0001"
        )

        public_blocks = [block for block in result.blocks if block.activity_ref is None]
        self.assertEqual(len(public_blocks), 1)
        self.assertEqual(public_blocks[0].type, "markdown")
        self.assertEqual(public_blocks[0].content, result.answer)
        self.assertIn("自测卡片", result.answer)
        self.assertIn("提交后", result.answer)
        self.assertNotIn("正确答案", result.answer)
        self.assertNotIn("def answer", public_blocks[0].content or "")
        self.assertEqual(result.cognitive_labels, [])
        self.assertIsNotNone(result.suggested_next_step)
        self.assertIn("完成并提交", result.suggested_next_step or "")
        self.assertNotIn("选 A", result.suggested_next_step or "")
        self.assertNotIn("def", result.suggested_next_step or "")
        self.assertEqual(
            [block.type for block in result.blocks if block.activity_ref],
            ["quiz"],
        )

    async def test_verified_code_lab_replaces_reference_solution_leak(self):
        leaking_output = model_output()
        leaking_output["answer"] = "参考解是调用 text.strip()。"
        leaking_output["suggested_next_step"] = "复制参考解 text.strip() 后提交。"
        leaking_output["cognitive_labels"][0]["evidence"] = "学生应使用 strip 完成答案。"
        leaking_output["blocks"] = [
            {
                "type": "markdown",
                "content": "直接使用 strip 即可通过。",
            },
            {
                "type": "code",
                "language": "python",
                "content": "def solve(text):\n    return text.strip()",
            },
        ]
        factory = AdaptiveProviderFactory(explanation_output=leaking_output)
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)

        result = await agent.chat(
            self.request("CODE"), trace_request_id="cycle8-request-0001"
        )

        public_blocks = [block for block in result.blocks if block.activity_ref is None]
        self.assertEqual(len(public_blocks), 1)
        self.assertEqual(public_blocks[0].content, result.answer)
        self.assertIn("代码实验卡片", result.answer)
        self.assertIn("编辑并运行", result.answer)
        self.assertNotIn("strip", result.answer)
        self.assertNotIn("参考解", public_blocks[0].content or "")
        self.assertEqual(result.cognitive_labels, [])
        self.assertIsNotNone(result.suggested_next_step)
        self.assertNotIn("strip", result.suggested_next_step or "")
        self.assertNotIn("参考解", result.suggested_next_step or "")
        self.assertEqual(
            [block.type for block in result.blocks if block.activity_ref],
            ["code_lab"],
        )

    async def test_non_programming_course_explicit_code_mode_returns_explanation_only(
        self,
    ):
        factory = AdaptiveProviderFactory()
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)
        request = self.request("CODE").model_copy(
            update={
                "course_name": "高等数学",
                "enabled_activity_types": ["EXPLAIN", "QUIZ", "VISUAL"],
            }
        )

        result = await agent.chat(
            request, trace_request_id="cycle8-request-0001"
        )

        self.assertEqual(result.teaching_plan.specialist_agents, ["EXPLANATION"])
        self.assertEqual(result.activities, [])
        self.assertNotIn("code_lab", [block.type for block in result.blocks])
        self.assertEqual(result.course_id, request.course_id)
        self.assertNotIn(TutorCodeActivityDraft, factory.output_models)

    async def test_non_programming_course_fallback_cannot_infer_code_activity(
        self,
    ):
        factory = AdaptiveProviderFactory(planner_error=True)
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)
        request = self.request("AUTO").model_copy(
            update={
                "course_name": "高等数学",
                "enabled_activity_types": ["EXPLAIN", "QUIZ", "VISUAL"],
                "message": "请给我一个代码实现练习。",
            }
        )

        result = await agent.chat(
            request, trace_request_id="cycle8-request-0001"
        )

        self.assertEqual(result.teaching_plan.specialist_agents, ["EXPLANATION"])
        self.assertEqual(result.activities, [])
        self.assertNotIn("code_lab", [block.type for block in result.blocks])
        self.assertNotIn(TutorCodeActivityDraft, factory.output_models)

    async def test_disabled_code_specialist_is_skipped_before_provider_creation(self):
        factory = AdaptiveProviderFactory()
        request = self.request("CODE").model_copy(
            update={"enabled_activity_types": ["EXPLAIN", "QUIZ", "VISUAL"]}
        )
        specialist = ActivitySpecialist(
            "CODE_LAB",
            provider_factory=factory,
        )

        result = await specialist.run(
            request,
            approved_context(),
            supplemental_contexts=(),
            allowed_knowledge_point_ids={"kp-python-functions"},
            trace_request_id="cycle8-request-0001",
            timeout_seconds=1,
        )

        self.assertIsNone(result.activity)
        self.assertEqual(result.trace.status, "SKIPPED")
        self.assertEqual(result.trace.error_code, "COURSE_ACTIVITY_DISABLED")
        self.assertEqual(factory.providers, [])

    async def test_animation_only_preserves_grounded_explanation(self):
        factory = AdaptiveProviderFactory()
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)

        result = await agent.chat(
            self.request("VISUAL"), trace_request_id="cycle8-request-0001"
        )

        expected = model_output()
        self.assertEqual(result.answer, expected["answer"])
        self.assertEqual(result.suggested_next_step, expected["suggested_next_step"])
        self.assertEqual(
            [label.evidence for label in result.cognitive_labels],
            [expected["cognitive_labels"][0]["evidence"]],
        )
        public_blocks = [block for block in result.blocks if block.activity_ref is None]
        self.assertEqual([block.content for block in public_blocks], [expected["blocks"][0]["content"]])
        self.assertEqual(
            [block.type for block in result.blocks if block.activity_ref],
            ["animation"],
        )

    def test_explanation_prompt_forbids_pre_submit_answer_disclosure(self):
        self.assertIn("teaching_plan.specialist_agents 包含 QUIZ 或 CODE_LAB", SYSTEM_PROMPT)
        self.assertIn("不得提前给出题目正确选项", SYSTEM_PROMPT)
        self.assertIn("suggested_next_step 和 cognitive_labels.evidence", SYSTEM_PROMPT)
        self.assertIn("cognitive_labels 必须返回空数组", SYSTEM_PROMPT)
        self.assertIn("提交后查看结果和解析", SYSTEM_PROMPT)

    def test_prompt_versions_and_public_activity_disclosure_rules(self):
        self.assertEqual(TUTOR_PROMPT_VERSION, "tutor-graph-rag-v4")
        self.assertEqual(
            TUTOR_QUIZ_SPECIALIST_PROMPT_VERSION,
            "tutor-quiz-specialist-v3",
        )
        self.assertEqual(
            TUTOR_CODE_LAB_SPECIALIST_PROMPT_VERSION,
            "tutor-code-lab-specialist-v2",
        )
        self.assertIn("title、stem、options", QUIZ_SYSTEM_PROMPT)
        self.assertIn("不得标记或明示正确选项", QUIZ_SYSTEM_PROMPT)
        self.assertIn("title、stem、starter_code 和 PUBLIC 测试", CODE_LAB_SYSTEM_PROMPT)
        self.assertIn("不得给出完整参考解", CODE_LAB_SYSTEM_PROMPT)
        self.assertIn("不得复制或暗示 HIDDEN 测试", CODE_LAB_SYSTEM_PROMPT)

    async def test_pure_explanation_preserves_all_grounded_public_fields(self):
        expected = model_output()
        factory = AdaptiveProviderFactory(explanation_output=expected)
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)

        result = await agent.chat(
            self.request("EXPLAIN"), trace_request_id="cycle8-request-0001"
        )

        self.assertEqual(result.activities, [])
        self.assertEqual(result.answer, expected["answer"])
        self.assertEqual(result.suggested_next_step, expected["suggested_next_step"])
        self.assertEqual(
            [block.content for block in result.blocks],
            [expected["blocks"][0]["content"]],
        )
        self.assertEqual(
            [label.evidence for label in result.cognitive_labels],
            [expected["cognitive_labels"][0]["evidence"]],
        )

    async def test_quiz_and_code_lab_share_one_safe_public_composition(self):
        leaking_output = model_output()
        leaking_output["answer"] = "选 A，然后复制 text.strip() 参考解。"
        leaking_output["blocks"] = [
            {"type": "markdown", "content": "测验答案 A，代码答案是 strip。"}
        ]
        leaking_output["suggested_next_step"] = "正确选项 A，直接提交 strip。"
        leaking_output["cognitive_labels"][0]["evidence"] = "A 和 strip 是参考答案。"
        factory = AdaptiveProviderFactory(
            explanation_output=leaking_output,
            planner_agents=["EXPLANATION", "QUIZ", "CODE_LAB"],
        )
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)

        result = await agent.chat(
            self.request("AUTO"), trace_request_id="cycle8-request-0001"
        )

        self.assertEqual(
            [activity.activity_type for activity in result.activities],
            ["QUIZ", "CODE_LAB"],
        )
        public_blocks = [block for block in result.blocks if block.activity_ref is None]
        self.assertEqual(len(public_blocks), 1)
        self.assertIn("自测卡片", result.answer)
        self.assertIn("代码实验卡片", result.answer)
        self.assertNotIn("strip", result.answer)
        self.assertEqual(result.cognitive_labels, [])
        self.assertNotIn("strip", result.suggested_next_step or "")
        self.assertEqual(
            [block.type for block in result.blocks if block.activity_ref],
            ["quiz", "code_lab"],
        )

    async def test_interactive_guard_reserves_budget_before_verbose_explanation(self):
        expected = model_output()
        expected["answer"] = "正确答案是 A，保留这段泄露讲解。"
        expected["suggested_next_step"] = "下一步直接选 A。"
        expected["cognitive_labels"][0]["evidence"] = "A 是正确答案。"
        expected["blocks"] = [
            {
                "type": "markdown",
                "block_id": f"explanation_{index}",
                "content": f"讲解块 {index}",
            }
            for index in range(20)
        ]
        factory = AdaptiveProviderFactory(explanation_output=expected)
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)

        result = await agent.chat(
            self.request("QUIZ"), trace_request_id="cycle8-request-0001"
        )

        self.assertEqual(len(result.activities), 1)
        self.assertEqual(result.activities[0].activity_type, "QUIZ")
        self.assertEqual(len(result.blocks), 2)
        self.assertLessEqual(len(result.blocks), 20)
        self.assertIn("自测卡片", result.answer)
        self.assertNotIn("正确答案", result.answer)
        self.assertNotIn("选 A", result.suggested_next_step or "")
        self.assertEqual(result.cognitive_labels, [])
        quiz_trace = next(item for item in result.specialist_trace if item.agent == "QUIZ")
        self.assertEqual(quiz_trace.status, "SUCCEEDED")
        self.assertEqual(quiz_trace.artifact_count, 1)
        self.assertIsNone(quiz_trace.error_code)

    async def test_planned_interaction_without_activity_returns_safe_unavailable_message(self):
        class NoActivitySpecialist:
            def __init__(self, agent, *, provider_factory=None) -> None:
                del provider_factory
                self.agent = agent

            async def run(self, *args, **kwargs) -> SpecialistActivityResult:
                del args, kwargs
                return SpecialistActivityResult(
                    activity=None,
                    trace=TutorSpecialistInvocation(
                        agent=self.agent,
                        status="FAILED",
                        duration_ms=1,
                        artifact_count=0,
                        error_code=f"{self.agent}_UNAVAILABLE",
                    ),
                )

        leaking_output = model_output()
        leaking_output["answer"] = "正确答案是 A，使用 def。"
        leaking_output["blocks"] = [
            {"type": "markdown", "content": "选 A，正确答案是 def。"}
        ]
        leaking_output["suggested_next_step"] = "下一步直接选 A。"
        leaking_output["cognitive_labels"][0]["evidence"] = "A 是正确答案。"
        factory = AdaptiveProviderFactory(explanation_output=leaking_output)
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)

        with patch("app.agents.tutor.ActivitySpecialist", NoActivitySpecialist):
            result = await agent.chat(
                self.request("QUIZ"), trace_request_id="cycle8-request-0001"
            )

        self.assertEqual(result.activities, [])
        self.assertEqual(len(result.blocks), 1)
        self.assertLessEqual(len(result.blocks), 20)
        self.assertIn("互动资源暂未生成", result.answer)
        self.assertIn("请重试", result.answer)
        self.assertNotIn("自测卡片", result.answer)
        self.assertNotIn("正确答案是 A", result.answer)
        self.assertEqual(result.cognitive_labels, [])
        self.assertNotIn("选 A", result.suggested_next_step or "")
        quiz_trace = next(item for item in result.specialist_trace if item.agent == "QUIZ")
        self.assertEqual(quiz_trace.status, "FAILED")
        self.assertEqual(quiz_trace.artifact_count, 0)
        self.assertEqual(quiz_trace.error_code, "QUIZ_UNAVAILABLE")

    async def test_mixed_mode_runs_at_most_three_specialists_concurrently(self):
        factory = AdaptiveProviderFactory()
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)

        result = await agent.chat(
            self.request("MIXED"), trace_request_id="cycle8-request-0001"
        )

        self.assertEqual(
            result.teaching_plan.specialist_agents,
            ["EXPLANATION", "QUIZ", "ANIMATION"],
        )
        self.assertEqual(len(result.activities), 2)
        self.assertEqual({item.activity_type for item in result.activities}, {"QUIZ", "ANIMATION"})
        self.assertGreaterEqual(factory.maximum_active, 2)
        self.assertLessEqual(factory.maximum_active, 3)

    async def test_auto_planner_failure_degrades_to_traceable_explanation(self):
        factory = AdaptiveProviderFactory(planner_error=True)
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)

        result = await agent.chat(
            self.request("AUTO"), trace_request_id="cycle8-request-0001"
        )

        self.assertEqual(result.teaching_plan.specialist_agents, ["EXPLANATION"])
        self.assertEqual(result.activities, [])
        self.assertEqual(result.specialist_trace[0].status, "SUCCEEDED")
        self.assertTrue(result.answer)
        self.assertTrue(result.sources)

    async def test_explanation_invalid_interactive_block_is_repaired_once(self):
        factory = AdaptiveProviderFactory(explanation_invalid_once=True)
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=factory)

        result = await agent.chat(
            self.request("EXPLAIN"), trace_request_id="cycle8-request-0001"
        )

        self.assertTrue(result.answer)
        self.assertEqual(factory.explanation_calls, 2)
        self.assertTrue(factory.explanation_repair_seen)
        self.assertTrue(all(block.type in {"markdown", "code"} for block in result.blocks))

    async def test_activity_business_validation_failure_is_repaired_once(self):
        factory = RepairingQuizProviderFactory()
        specialist = ActivitySpecialist("QUIZ", provider_factory=factory)

        result = await specialist.run(
            self.request("QUIZ"),
            approved_context(),
            supplemental_contexts=(),
            allowed_knowledge_point_ids={"kp-python-functions"},
            trace_request_id="cycle8-request-0001",
            timeout_seconds=2,
        )

        self.assertIsInstance(result.activity, TutorQuizActivityDraft)
        self.assertEqual(result.trace.status, "SUCCEEDED")
        self.assertEqual(factory.calls, 2)
        self.assertTrue(factory.repair_seen)
        self.assertTrue(all(provider.closed for provider in factory.providers))

    async def test_activity_specialist_failure_keeps_only_a_sanitized_trace(self):
        specialist = ActivitySpecialist(
            "QUIZ", provider_factory=lambda: FailingProvider()
        )

        result = await specialist.run(
            self.request("QUIZ"),
            approved_context(),
            supplemental_contexts=(),
            allowed_knowledge_point_ids={"kp-python-functions"},
            trace_request_id="cycle8-request-0001",
            timeout_seconds=2,
        )

        self.assertIsInstance(result.activity, TutorQuizActivityDraft)
        self.assertEqual(result.activity.activity_ref, stable_activity_ref("cycle8-request-0001", "QUIZ"))
        TutorActivityVerifier.verify(
            result.activity,
            expected_activity_ref=stable_activity_ref("cycle8-request-0001", "QUIZ"),
            allowed_knowledge_point_ids={"kp-python-functions"},
        )
        self.assertEqual(result.trace.status, "FAILED")
        self.assertEqual(result.trace.artifact_count, 1)
        self.assertEqual(result.trace.error_code, "QUIZ_FALLBACK_USED")
        self.assertNotIn("private upstream", result.trace.model_dump_json())

    async def test_each_failed_activity_specialist_returns_a_verified_fallback(self):
        expected_types = {
            "QUIZ": TutorQuizActivityDraft,
            "CODE_LAB": TutorCodeActivityDraft,
            "ANIMATION": TutorAnimationActivityDraft,
        }
        for agent, expected_type in expected_types.items():
            with self.subTest(agent=agent):
                result = await ActivitySpecialist(
                    agent, provider_factory=lambda: FailingProvider()
                ).run(
                    self.request("MIXED"),
                    approved_context(),
                    supplemental_contexts=(),
                    allowed_knowledge_point_ids={"kp-python-functions"},
                    trace_request_id="cycle8-request-0001",
                    timeout_seconds=2,
                )
                self.assertIsInstance(result.activity, expected_type)
                self.assertEqual(result.trace.status, "FAILED")
                self.assertEqual(result.trace.artifact_count, 1)
                TutorActivityVerifier.verify(
                    result.activity,
                    expected_activity_ref=stable_activity_ref("cycle8-request-0001", agent),
                    allowed_knowledge_point_ids={"kp-python-functions"},
                )

    def test_short_answer_contract_requires_private_answers_and_no_options(self):
        short_answer = {
            "question_ref": "short_answer",
            "question_type": "SHORT_ANSWER",
            "stem": "Python 定义函数的关键字是什么？",
            "options": [],
            "correct_option_keys": [],
            "accepted_answers": ["def", "DEF"],
            "case_sensitive": False,
            "explanation": "使用 def 定义函数。",
            "score": 100,
        }
        with self.assertRaisesRegex(ValueError, "Accepted answers must be unique"):
            TutorQuizActivityDraft.model_validate(
                {
                    **_quiz("short_answer_ref"),
                    "questions": [short_answer],
                }
            )

        short_answer["accepted_answers"] = ["def"]
        valid = TutorQuizActivityDraft.model_validate(
            {**_quiz("short_answer_ref"), "questions": [short_answer]}
        )
        self.assertEqual(valid.questions[0].question_type, "SHORT_ANSWER")
        invalid_choice_data = {**short_answer, "options": [{"key": "A", "text": "def"}]}
        with self.assertRaisesRegex(ValueError, "cannot contain choice options"):
            TutorQuizActivityDraft.model_validate(
                {**_quiz("short_answer_ref"), "questions": [invalid_choice_data]}
            )

        missing_shape_field = dict(short_answer)
        missing_shape_field.pop("case_sensitive")
        with self.assertRaisesRegex(ValueError, "case_sensitive"):
            TutorQuizActivityDraft.model_validate(
                {**_quiz("short_answer_ref"), "questions": [missing_shape_field]}
            )

    def test_deterministic_verifier_rejects_active_animation_and_missing_hidden_tests(self):
        unsafe_animation = TutorAnimationActivityDraft.model_validate(
            _animation("animation_ref", label="<script>alert(1)</script>")
        )
        with self.assertRaisesRegex(
            TutorActivityVerificationError, "deterministic verification"
        ):
            TutorActivityVerifier.verify(
                unsafe_animation,
                expected_activity_ref="animation_ref",
                allowed_knowledge_point_ids={"kp-python-functions"},
            )

        public_only_code = TutorCodeActivityDraft.model_construct(
            activity_ref="code_ref",
            activity_type="CODE_LAB",
            title="函数代码题",
            knowledge_point_id="kp-python-functions",
            difficulty="BASIC",
            language="PYTHON",
            stem="定义并调用函数。",
            starter_code="def greet():\n    pass",
            reference_solution="def greet():\n    return 'hi'",
            test_cases=[],
        )
        with self.assertRaises(TutorActivityVerificationError):
            TutorActivityVerifier.verify(
                public_only_code,
                expected_activity_ref="code_ref",
                allowed_knowledge_point_ids={"kp-python-functions"},
            )

    def test_auto_planner_failure_preserves_obvious_interactive_intent(self):
        cases = {
            "给我出一道可点击的自测题": ["EXPLANATION", "QUIZ"],
            "请用动画演示二分查找": ["EXPLANATION", "ANIMATION"],
            "生成一个 Python 代码练习": ["EXPLANATION", "CODE_LAB"],
            "用动画讲解并出两道选择题": ["EXPLANATION", "ANIMATION", "QUIZ"],
            "解释一下函数": ["EXPLANATION"],
        }
        for message, expected in cases.items():
            with self.subTest(message=message):
                plan = _fallback_teaching_plan("AUTO", message)
                self.assertEqual(plan.specialist_agents, expected)
                self.assertEqual(plan.modalities, expected)


if __name__ == "__main__":
    unittest.main()
