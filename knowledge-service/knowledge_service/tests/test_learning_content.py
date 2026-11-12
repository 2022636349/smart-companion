import json
from pathlib import Path

import pytest

from knowledge_service.app.services.learning_content_service import LearningContentService

CATALOG = Path(__file__).resolve().parents[2] / 'data/smart-companion/learning-content-v1.json'


@pytest.fixture
def mixed_catalog():
    # Deliberately independent of the production directory's approval lifecycle.
    return {
        'courseId': 3,
        'reviewStatus': 'APPROVED',
        'cards': [
            {'id': 'T01I1', 'reviewStatus': 'APPROVED', 'content': 'approved fixture'},
            {'id': 'T01I2', 'reviewStatus': 'PENDING', 'content': 'pending fixture'},
        ],
    }


def test_catalog_coverage():
    catalog = json.loads(CATALOG.read_text(encoding='utf-8'))
    assert {card['id'] for card in catalog['cards']} == {
        f'T{theme:02}I{indicator}' for theme in range(1, 4) for indicator in range(1, 4)
    }
    for card in catalog['cards']:
        assert card['content'] and len(card['learningSteps']) >= 3
        assert all(source['excerpt'] and 'PDF物理第' in source['location'] for source in card['sources'])
        source_ids = {source['id'] for source in card['sources']}
        assert all(set(prerequisite['sourceIds']) <= source_ids for prerequisite in card['prerequisites'])
    mapping = json.loads((CATALOG.parent / 'indicator-knowledge-map-v1.json').read_text(encoding='utf-8'))
    question_ids = [question for row in mapping['mappings'] for question in row['materialIds']]
    assert len(question_ids) == len(set(question_ids)) == 54


def test_pending_and_scope(tmp_path, mixed_catalog):
    path = tmp_path / 'catalog.json'
    service = LearningContentService(path)
    assert service.get_card('T01I1', 3) is None
    mixed_catalog['reviewStatus'] = 'PENDING'
    path.write_text(json.dumps(mixed_catalog), encoding='utf-8')
    assert service.get_card('T01I1', 3) is None
    mixed_catalog['reviewStatus'] = 'APPROVED'
    path.write_text(json.dumps(mixed_catalog), encoding='utf-8')
    assert service.get_card('T01I1', 3)['id'] == 'T01I1'
    assert service.get_card('T01I2', 3) is None
    assert service.get_card('T01I1', 1) is None
    assert service.get_card('../T01I1', 3) is None


def test_route(monkeypatch, tmp_path, mixed_catalog):
    monkeypatch.setenv('GRAPH_BACKEND', 'json')
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN', 'test-learning-token')
    from fastapi.testclient import TestClient
    from knowledge_service.main import app

    path = tmp_path / 'catalog.json'
    path.write_text(json.dumps(mixed_catalog), encoding='utf-8')
    monkeypatch.setenv('GRAPH_LEARNING_CONTENT_FILE', str(path))
    client = TestClient(app)
    url = '/internal/graph/learning-content/T01I1?course_id=3'
    assert client.get(url).status_code in (401, 403, 422)
    headers = {'X-Internal-Token': 'test-learning-token', 'X-Request-Id': 'learning-request-001'}
    response = client.get(url, headers=headers)
    assert response.status_code == 200 and response.json()['id'] == 'T01I1'
    assert client.get(url.replace('course_id=3', 'course_id=2'), headers=headers).status_code == 404
    assert client.get('/internal/graph/learning-content/T01I2?course_id=3', headers=headers).status_code == 404
    operation = app.openapi()['paths']['/internal/graph/learning-content/{indicator_id}']['get']
    assert operation['operationId'] == 'getFixedCompanionLearningContent'
