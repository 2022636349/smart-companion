from __future__ import annotations

import copy
import unittest

from pydantic import ValidationError

from app.contracts.assessment import AssessmentModelOutput
from app.contracts.common import PROGRAMMING_TEST_TEXT_MAX_CHARS
from tests.assessment_fixtures import five_question_model_output


class AssessmentContractsTest(unittest.TestCase):
    def test_validates_all_five_question_shapes(self) -> None:
        output = AssessmentModelOutput.model_validate(five_question_model_output())

        self.assertEqual(len(output.questions), 5)

    def test_rejects_inconsistent_answers_rubrics_and_test_cases(self) -> None:
        mutations = []

        single = copy.deepcopy(five_question_model_output())
        single["questions"][0]["options"][1]["is_correct"] = True
        mutations.append(("single-choice answer", single))

        subjective = copy.deepcopy(five_question_model_output())
        subjective["questions"][2]["rubric_items"][0]["max_score"] = 2
        mutations.append(("subjective rubric total", subjective))

        one_cent_short = copy.deepcopy(five_question_model_output())
        one_cent_short["questions"][2]["rubric_items"][0]["max_score"] = 2.99
        mutations.append(("rubric total one cent short", one_cent_short))

        programming = copy.deepcopy(five_question_model_output())
        programming["questions"][3]["test_cases"][1]["visibility"] = "PUBLIC"
        mutations.append(("programming hidden test", programming))

        attachment = copy.deepcopy(five_question_model_output())
        attachment["questions"][4]["reference_answer"] = "not allowed"
        mutations.append(("attachment answer", attachment))

        duplicate = copy.deepcopy(five_question_model_output())
        duplicate["questions"][1]["client_key"] = "q-001"
        mutations.append(("duplicate client key", duplicate))

        for label, payload in mutations:
            with self.subTest(label), self.assertRaises(ValidationError):
                AssessmentModelOutput.model_validate(payload)

    def test_decimal_score_sum_accepts_exact_cent_values_without_float_drift(self) -> None:
        payload = copy.deepcopy(five_question_model_output())
        payload["questions"] = [payload["questions"][2]]
        payload["questions"][0]["score"] = 0.3
        payload["questions"][0]["rubric_items"][0]["max_score"] = 0.1
        payload["questions"][0]["rubric_items"][1]["max_score"] = 0.2

        output = AssessmentModelOutput.model_validate(payload)

        self.assertEqual(output.questions[0].score, 0.3)

    def test_score_fields_match_frozen_precision_and_upper_bound(self) -> None:
        mutations = []

        question_precision = copy.deepcopy(five_question_model_output())
        question_precision["questions"][0]["score"] = 0.001
        mutations.append(("question precision", question_precision))

        question_upper = copy.deepcopy(five_question_model_output())
        question_upper["questions"][0]["score"] = 1_000_000
        mutations.append(("question upper bound", question_upper))

        rubric_precision = copy.deepcopy(five_question_model_output())
        rubric_precision["questions"][2]["rubric_items"][0]["max_score"] = 3.001
        mutations.append(("rubric precision", rubric_precision))

        test_case_precision = copy.deepcopy(five_question_model_output())
        test_case_precision["questions"][3]["test_cases"][0]["score"] = 4.001
        mutations.append(("test case precision", test_case_precision))

        for label, payload in mutations:
            with self.subTest(label), self.assertRaises(ValidationError):
                AssessmentModelOutput.model_validate(payload)

        boundary = copy.deepcopy(five_question_model_output())
        boundary["questions"] = [boundary["questions"][0]]
        boundary["questions"][0]["score"] = 999_999.99
        validated = AssessmentModelOutput.model_validate(boundary)
        self.assertEqual(validated.questions[0].score, 999_999.99)

    def test_programming_test_payload_is_bounded(self) -> None:
        too_long = copy.deepcopy(five_question_model_output())
        too_long["questions"] = [too_long["questions"][3]]
        too_long["questions"][0]["test_cases"][0]["input_data"] = "x" * (
            PROGRAMMING_TEST_TEXT_MAX_CHARS + 1
        )
        with self.assertRaises(ValidationError):
            AssessmentModelOutput.model_validate(too_long)

        oversized = copy.deepcopy(five_question_model_output())
        programming = oversized["questions"][3]
        programming["score"] = 100
        template = programming["test_cases"]
        programming["test_cases"] = [
            {
                **copy.deepcopy(template[index % 2]),
                "input_data": "测" * 7000,
                "expected_output": "出" * 7000,
                "score": 1,
            }
            for index in range(100)
        ]
        oversized["questions"] = [programming]
        with self.assertRaises(ValidationError):
            AssessmentModelOutput.model_validate(oversized)


if __name__ == "__main__":
    unittest.main()
