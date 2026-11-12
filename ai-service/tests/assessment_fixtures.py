from __future__ import annotations


def five_question_model_output() -> dict:
    return {
        "title_suggestion": "Python 基础练习",
        "questions": [
            {
                "client_key": "q-001",
                "question_type": "SINGLE_CHOICE",
                "stem": "Python 中用于定义函数的关键字是？",
                "score": 2,
                "options": [
                    {"key": "A", "content": "def", "is_correct": True},
                    {"key": "B", "content": "func", "is_correct": False},
                ],
                "reference_answer": None,
                "rubric_items": [],
                "test_cases": [],
                "knowledge_point_candidates": [
                    {
                        "knowledge_point_id": "hallucinated-id",
                        "name": "不得保留的模型候选",
                        "score": 0.99,
                    }
                ],
                "confidence": "HIGH",
                "warnings": [],
            },
            {
                "client_key": "q-002",
                "question_type": "MULTIPLE_CHOICE",
                "stem": "下列哪些是 Python 可变容器？",
                "score": 3,
                "options": [
                    {"key": "A", "content": "list", "is_correct": True},
                    {"key": "B", "content": "tuple", "is_correct": False},
                    {"key": "C", "content": "dict", "is_correct": True},
                ],
                "reference_answer": None,
                "rubric_items": [],
                "test_cases": [],
                "knowledge_point_candidates": [],
                "confidence": "HIGH",
                "warnings": [],
            },
            {
                "client_key": "q-003",
                "question_type": "SUBJECTIVE",
                "stem": "简述函数参数的作用。",
                "score": 5,
                "options": [],
                "reference_answer": "参数用于向函数传入数据。",
                "rubric_items": [
                    {
                        "label": "概念",
                        "description": "说明参数用于传递输入",
                        "max_score": 3,
                    },
                    {
                        "label": "表达",
                        "description": "表达清楚并给出示例",
                        "max_score": 2,
                    },
                ],
                "test_cases": [],
                "knowledge_point_candidates": [],
                "confidence": "MEDIUM",
                "warnings": [],
            },
            {
                "client_key": "q-004",
                "question_type": "PROGRAMMING",
                "stem": "编写函数 add(a, b) 并返回两数之和。",
                "score": 10,
                "options": [],
                "reference_answer": "def add(a, b):\n    return a + b",
                "rubric_items": [],
                "test_cases": [
                    {
                        "input_data": "1 2",
                        "expected_output": "3",
                        "score": 4,
                        "visibility": "PUBLIC",
                        "compare_mode": "TRIM_TRAILING_WHITESPACE",
                    },
                    {
                        "input_data": "-5 2",
                        "expected_output": "-3",
                        "score": 6,
                        "visibility": "HIDDEN",
                        "compare_mode": "TRIM_TRAILING_WHITESPACE",
                    },
                ],
                "knowledge_point_candidates": [],
                "confidence": "MEDIUM",
                "warnings": ["测试点为 AI 候选，需要教师确认。"],
            },
            {
                "client_key": "q-005",
                "question_type": "ATTACHMENT",
                "stem": "上传实验报告文件。",
                "score": 5,
                "options": [],
                "reference_answer": None,
                "rubric_items": [
                    {
                        "label": "完整性",
                        "description": "报告包含实验步骤与结论",
                        "max_score": 5,
                    }
                ],
                "test_cases": [],
                "knowledge_point_candidates": [],
                "confidence": "HIGH",
                "warnings": ["附件策略由 Spring 安全默认值初始化，需教师确认。"],
            },
        ],
        "warnings": ["所有 AI 结果均需教师确认。"],
    }
