import unittest

from pydantic import ValidationError

from app.agents.teaching import constrain_teaching_plan
from app.contracts.graph_ingestion import GraphExtractReviewRequest
from app.contracts.memory import (
    MemorySearchHit,
    MemorySearchRequest,
    MemorySearchResponse,
    MemoryUpsertRequest,
)
from app.contracts.profile import ProfileSummarizeRequest, ProfileSummarizeResponse
from app.contracts.tutor import TutorChatRequest, TutorChatResponse, TutorTeachingPlan


class CourseTeachingCapabilityTest(unittest.TestCase):
    def test_math_course_drops_code_but_retains_enabled_quiz(self):
        plan = TutorTeachingPlan(
            goal="先讲解再编程和练习",
            modalities=["EXPLANATION", "CODE_LAB", "QUIZ"],
            specialist_agents=["EXPLANATION", "CODE_LAB", "QUIZ"],
        )
        actual = constrain_teaching_plan(plan, ["EXPLAIN", "QUIZ", "VISUAL"])
        self.assertEqual(actual.specialist_agents, ["EXPLANATION", "QUIZ"])
        self.assertEqual(actual.modalities, actual.specialist_agents)

    def test_python_course_keeps_code(self):
        plan = TutorTeachingPlan(
            goal="代码实验",
            modalities=["EXPLANATION", "CODE_LAB"],
            specialist_agents=["EXPLANATION", "CODE_LAB"],
        )
        self.assertIs(constrain_teaching_plan(plan, ["EXPLAIN", "CODE"]), plan)

    def test_explanation_only_course_has_no_implicit_quiz(self):
        plan = TutorTeachingPlan(
            goal="混合教学",
            modalities=["EXPLANATION", "QUIZ"],
            specialist_agents=["EXPLANATION", "QUIZ"],
        )
        self.assertEqual(
            constrain_teaching_plan(plan, ["EXPLAIN"]).specialist_agents,
            ["EXPLANATION"],
        )


class MultiCourseContractTest(unittest.TestCase):
    def test_all_course_scoped_internal_models_require_course_id(self):
        models = (
            TutorChatRequest,
            TutorChatResponse,
            GraphExtractReviewRequest,
            ProfileSummarizeRequest,
            ProfileSummarizeResponse,
            MemoryUpsertRequest,
            MemorySearchRequest,
            MemorySearchResponse,
            MemorySearchHit,
        )
        for model in models:
            with self.subTest(model=model.__name__):
                field = model.model_fields["course_id"]
                self.assertTrue(field.is_required())
                with self.assertRaises(ValidationError):
                    model.model_validate({})

    def test_tutor_course_capabilities_are_required_unique_and_explainable(self):
        schema = TutorChatRequest.model_json_schema()
        self.assertIn("course_name", schema["required"])
        self.assertIn("enabled_activity_types", schema["required"])
        self.assertTrue(
            schema["properties"]["enabled_activity_types"]["uniqueItems"]
        )
        base = {
            "request_id": "course-contract-request",
            "course_id": 2,
            "course_name": "高等数学",
            "user_id": 8,
            "knowledge_point_id": "shared-function-id",
            "message": "请解释函数。",
        }
        for invalid in (
            [],
            ["QUIZ"],
            ["EXPLAIN", "EXPLAIN"],
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValidationError):
                TutorChatRequest.model_validate(
                    {**base, "enabled_activity_types": invalid}
                )

    def test_course_id_rejects_missing_nonpositive_bool_and_fractional_values(self):
        base = {
            "request_id": "course-contract-request",
            "student_id": 8,
            "query": "函数",
        }
        for invalid in (None, 0, -1, True, 1.5):
            payload = dict(base)
            if invalid is not None:
                payload["course_id"] = invalid
            with self.subTest(invalid=invalid), self.assertRaises(ValidationError):
                MemorySearchRequest.model_validate(payload)
