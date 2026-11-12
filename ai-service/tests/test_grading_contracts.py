from __future__ import annotations

import math
import unittest

from pydantic import ValidationError

from app.contracts.assessment import (
    AttachmentGradeRequest,
    GradeModelOutput,
    SubjectiveGradeRequest,
)


def rubric() -> list[dict]:
    return [
        {"label": "概念", "description": "解释核心概念", "max_score": 6.0},
        {"label": "示例", "description": "给出正确示例", "max_score": 4.0},
    ]


class GradingContractsTest(unittest.TestCase):
    def test_subjective_request_enforces_contract_bounds_and_rubric_total(self) -> None:
        request = SubjectiveGradeRequest.model_validate(
            {
                "request_id": "trace-grading-0001",
                "submission_answer_id": 8,
                "answer_text": "函数通过参数接收输入。",
                "reference_answer": None,
                "rubric_items": rubric(),
                "max_score": 10.0,
            }
        )
        self.assertEqual(request.max_score, 10.0)

        invalid_payloads = [
            {"answer_text": " "},
            {"answer_text": "x" * 100_001},
            {"max_score": math.nan},
            {"max_score": 10.001},
            {"max_score": 11.0},
            {"rubric_items": [rubric()[0], rubric()[0]]},
        ]
        base = {
            "request_id": "trace-grading-0001",
            "submission_answer_id": 8,
            "answer_text": "有效答案",
            "rubric_items": rubric(),
            "max_score": 10.0,
        }
        for updates in invalid_payloads:
            with self.subTest(updates=list(updates)):
                with self.assertRaises(ValidationError):
                    SubjectiveGradeRequest.model_validate({**base, **updates})

    def test_attachment_request_requires_unique_bounded_spring_candidates(self) -> None:
        base = {
            "request_id": "trace-grading-0002",
            "submission_answer_id": 8,
            "attachment_download_urls": [
                "https://business.test/api/files/7/content"
            ],
            "rubric_items": rubric(),
            "max_score": 10.0,
        }
        self.assertEqual(
            len(AttachmentGradeRequest.model_validate(base).attachment_download_urls),
            1,
        )
        for urls in (
            [],
            ["https://business.test/api/files/7/content"] * 2,
            [f"https://business.test/api/files/{index}/content" for index in range(1, 7)],
        ):
            with self.subTest(count=len(urls)):
                with self.assertRaises(ValidationError):
                    AttachmentGradeRequest.model_validate(
                        {**base, "attachment_download_urls": urls}
                    )

    def test_model_output_rejects_nan_over_precision_and_positive_without_evidence(self) -> None:
        base = {
            "suggested_score": 6.0,
            "rubric_grades": [
                {
                    "label": "概念",
                    "score": 6.0,
                    "max_score": 6.0,
                    "evidence": ["函数通过参数接收输入"],
                    "confidence": 0.9,
                    "comment": "概念清楚",
                }
            ],
            "overall_comment": "仅供教师复核",
            "warnings": [],
        }
        GradeModelOutput.model_validate(base)
        invalid = [
            {"suggested_score": math.nan},
            {"suggested_score": 6.001},
            {"rubric_grades": [{**base["rubric_grades"][0], "score": 7.0}]},
            {"rubric_grades": [{**base["rubric_grades"][0], "evidence": []}]},
            {"rubric_grades": [{**base["rubric_grades"][0], "confidence": 1.1}]},
        ]
        for updates in invalid:
            with self.subTest(updates=list(updates)):
                with self.assertRaises(ValidationError):
                    GradeModelOutput.model_validate({**base, **updates})


if __name__ == "__main__":
    unittest.main()
