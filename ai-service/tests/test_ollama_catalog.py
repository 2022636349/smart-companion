import httpx2
import pytest
from fastapi.testclient import TestClient

from app.main import app

HEADERS = {'X-Internal-Token': 'local-development-internal-token-2026', 'X-Request-Id': 'local-model-catalog-test-01'}


@pytest.fixture(autouse=True)
def environment(monkeypatch):
    monkeypatch.setenv('AI_RUNTIME_SETTINGS_ENABLED', 'false')
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN', HEADERS['X-Internal-Token'])


def test_catalog_reads_native_tags_without_a_model_key_or_generation(monkeypatch):
    calls = []
    async def get(self, url, **kwargs):
        calls.append((url, kwargs))
        return httpx2.Response(200, json={'models': [
            {'name': 'zeta:8b', 'size': 4000, 'details': {'parameter_size': '8B', 'quantization_level': 'Q4_K_M'}},
            {'name': 'alpha:3b', 'size': 2000}, {'name': 'alpha:3b', 'size': 2000},
        ]})
    monkeypatch.setattr(httpx2.AsyncClient, 'get', get)
    with TestClient(app) as client:
        response = client.post('/internal/models/settings/models', headers=HEADERS, json={'baseUrl': 'http://host.docker.internal:11434/'})
        assert response.status_code == 200
        assert response.json()['success'] is True
        assert [m['name'] for m in response.json()['models']] == ['alpha:3b', 'zeta:8b']
        assert response.json()['models'][1]['quantization'] == 'Q4_K_M'
        assert calls == [('http://host.docker.internal:11434/api/tags', {})]
        assert client.post('/internal/models/settings/models', json={'baseUrl': 'http://localhost:11434'}).status_code in {401, 422}


@pytest.mark.parametrize(('body', 'code'), [({'models': []}, 'OLLAMA_NO_MODELS'), ({'data': []}, 'OLLAMA_INVALID_RESPONSE'), ({'models': [{'name': 'x', 'size': -1}]}, 'OLLAMA_INVALID_RESPONSE')])
def test_empty_and_invalid_catalog_are_explicit(monkeypatch, body, code):
    async def get(self, *args, **kwargs): return httpx2.Response(200, json=body)
    monkeypatch.setattr(httpx2.AsyncClient, 'get', get)
    with TestClient(app) as client:
        response = client.post('/internal/models/settings/models', headers=HEADERS, json={'baseUrl': 'http://localhost:11434'})
        assert response.json()['success'] is False
        assert response.json()['code'] == code
        assert response.json()['models'] == []


@pytest.mark.parametrize(('failure', 'code'), [(httpx2.ConnectError('private-detail'), 'OLLAMA_UNREACHABLE'), (httpx2.ReadTimeout('private-detail'), 'OLLAMA_TIMEOUT')])
def test_network_failure_is_useful_and_never_returns_transport_detail(monkeypatch, failure, code):
    async def get(self, *args, **kwargs): raise failure
    monkeypatch.setattr(httpx2.AsyncClient, 'get', get)
    with TestClient(app) as client:
        response = client.post('/internal/models/settings/models', headers=HEADERS, json={'baseUrl': 'http://localhost:11434'})
        assert response.json()['code'] == code
        assert 'private-detail' not in response.text


def test_catalog_rejects_specific_api_path_before_network(monkeypatch):
    async def get(self, *args, **kwargs): pytest.fail('Invalid address must not be called')
    monkeypatch.setattr(httpx2.AsyncClient, 'get', get)
    with TestClient(app) as client:
        response = client.post('/internal/models/settings/models', headers=HEADERS, json={'baseUrl': 'http://localhost:11434/api/chat'})
        assert response.status_code == 422
