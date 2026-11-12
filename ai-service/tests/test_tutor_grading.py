import asyncio
import json
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.agents.tutor_grading import TutorShortAnswerGrader
from app.contracts.tutor_grading import TutorQuizGradeRequest, TutorQuizGradeResponse
from app.main import app
from app.prompts.tutor_grading import SYSTEM_PROMPT, TUTOR_QUIZ_GRADE_PROMPT_VERSION, build_grade_messages
from app.providers.base import ModelResponseError, ModelTimeoutError, ModelRateLimitError, ModelServerError
from tests.test_assessment_agent import FakeProvider


def payload():
    return dict(request_id='tutor-grade-test-0001', course_id=1, activity_id=12, questions=[dict(
        question_ref='q1', stem='Python 强调用什么特点让代码更容易理解？',
        accepted_answers=['可读性', '代码可读性', 'readability'], explanation='可读性让代码易于理解。',
        submitted_answer='高可读性', max_score=25)])


def grade(**overrides):
    return dict(dict(question_ref='q1', score=25, max_score=25, confidence=.96,
                     feedback='“高可读性”表达准确，说明代码容易阅读和理解。'), **overrides)


def test_contract_and_prompt_distinguish_equivalence_negation_and_alternatives():
    request = TutorQuizGradeRequest.model_validate(payload())
    messages = build_grade_messages(request)
    material = json.loads(messages[-1].content.split('\n', 1)[1])
    assert material[0]['submitted_answer'] == '高可读性'
    assert 'activity_id' not in messages[-1].content
    assert '高可读性' in SYSTEM_PROMPT and '可读性差' in SYSTEM_PROMPT
    assert '不是必须全部罗列的多个要点' in SYSTEM_PROMPT
    assert '部分分' in SYSTEM_PROMPT


@pytest.mark.parametrize('change', [
    {'course_id': 0}, {'activity_id': True}, {'request_id': 'short'},
    {'questions': []}, {'questions': payload()['questions'] * 2},
    {'questions': [dict(payload()['questions'][0], submitted_answer=' ')]},
    {'questions': [dict(payload()['questions'][0], max_score=1.001)]},
])
def test_invalid_request_is_rejected(change):
    with pytest.raises(ValidationError):
        TutorQuizGradeRequest.model_validate(dict(payload(), **change))


@pytest.mark.parametrize('score', [25, 12.5, 0])
def test_accepts_valid_full_partial_and_zero_suggestions(score):
    provider = FakeProvider(structured_content={'grades': [grade(score=score)]},
                            prompt_version=TUTOR_QUIZ_GRADE_PROMPT_VERSION)
    result = asyncio.run(TutorShortAnswerGrader(provider_factory=lambda: provider).grade(
        TutorQuizGradeRequest.model_validate(payload())))
    assert result.grades[0].score == score
    assert result.request_id == payload()['request_id']
    assert result.model_version == provider.model_name
    assert provider.closed
    assert provider.requests[0].capability == 'TUTOR_SHORT_ANSWER_GRADE'


@pytest.mark.parametrize('grades', [
    [], [grade(), grade()], [grade(question_ref='invented')],
    [grade(score=26)], [grade(max_score=30)], [grade(confidence=.74)],
    [grade(score=.001)], [grade(feedback='')], [grade(score=float('nan'))],
])
def test_invalid_or_uncertain_suggestions_fail_without_zero_fallback(grades):
    provider = FakeProvider(structured_content={'grades': grades}, prompt_version=TUTOR_QUIZ_GRADE_PROMPT_VERSION)
    with pytest.raises(ModelResponseError):
        asyncio.run(TutorShortAnswerGrader(provider_factory=lambda: provider).grade(
            TutorQuizGradeRequest.model_validate(payload())))
    assert provider.closed


def test_provider_timeout_closes_provider_and_propagates():
    provider = FakeProvider()
    provider.generate = AsyncMock(side_effect=TimeoutError())
    with pytest.raises(ModelTimeoutError):
        asyncio.run(TutorShortAnswerGrader(provider_factory=lambda: provider).grade(
            TutorQuizGradeRequest.model_validate(payload())))
    assert provider.closed


def test_api_requires_internal_headers_and_matching_request_id(monkeypatch):
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN', 'test-grading-token')
    client = TestClient(app)
    path = '/internal/ai/tutor/quiz/suggest-grade'
    assert client.post(path, json=payload()).status_code in (401, 422)
    headers = {'X-Internal-Token': 'test-grading-token', 'X-Request-Id': 'tutor-grade-other-0001'}
    assert client.post(path, json=payload(), headers=headers).status_code == 422
    headers['X-Request-Id'] = payload()['request_id']
    response = TutorQuizGradeResponse(request_id=payload()['request_id'], grades=[grade()],
                                     model_version='test', prompt_version=TUTOR_QUIZ_GRADE_PROMPT_VERSION)
    with patch('app.routers.tutor_grading.TutorShortAnswerGrader.grade', new=AsyncMock(return_value=response)):
        result = client.post(path, json=payload(), headers=headers)
    assert result.status_code == 200
    assert result.json()['grades'][0]['score'] == 25


@pytest.mark.parametrize('error,status', [(ModelTimeoutError('timeout'), 504),
    (ModelResponseError('invalid'), 502), (ModelRateLimitError('limited'), 429), (ModelServerError('offline'), 503)])
def test_api_exposes_retryable_errors_not_grades(monkeypatch, error, status):
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN', 'test-grading-token')
    with patch('app.routers.tutor_grading.TutorShortAnswerGrader.grade', new=AsyncMock(side_effect=error)):
        result = TestClient(app).post('/internal/ai/tutor/quiz/suggest-grade', json=payload(), headers={
            'X-Internal-Token': 'test-grading-token', 'X-Request-Id': payload()['request_id']})
    assert result.status_code == status
    assert 'grades' not in result.json()
