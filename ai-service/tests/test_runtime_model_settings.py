import asyncio
import json
from functools import wraps
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx2
import pytest
from fastapi.testclient import TestClient

from app.core.runtime_models import ConnectionSettings, RuntimeProfile, deployment_profile, resolve_profile, runtime_profiles, fetch_request_profiles, validate_admin_ollama_url
from app.providers.base import ModelConfigurationError, ModelAuthenticationError, ModelRoutingUnavailableError
from app.providers.factory import create_model_provider, create_animation_model_provider
from app.main import app
from app.routers import model_settings

def async_test(function):
    @wraps(function)
    def wrapped(*args, **kwargs):
        return asyncio.run(function(*args, **kwargs))
    return wrapped


def profile(slot='DEFAULT', **changes):
    values = dict(provider='openai_compatible', baseUrl='http://127.0.0.1:9009/v1', model='test-chat',
        timeoutSeconds=5, maxRetries=0, responseFormat='json_object', tokenLimitField='max_tokens', sendTemperature=False)
    values.update(changes)
    return RuntimeProfile(slot=slot, version=1, settings=ConnectionSettings(**values), apiKey='synthetic-test-key', useEnvironmentKey=False)


HEADERS = {'X-Internal-Token': 'local-development-internal-token-2026', 'X-Request-Id': 'model-settings-test-0001'}

@pytest.fixture(autouse=True)
def isolated_environment(monkeypatch):
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN', HEADERS['X-Internal-Token'])
    monkeypatch.setenv('AI_RUNTIME_SETTINGS_ENABLED', 'false')


def payload(value):
    body = value.model_dump(mode='json')
    body['apiKey'] = value.apiKey.get_secret_value() if value.apiKey else None
    return body


def test_deployment_metadata_never_contains_credentials(monkeypatch):
    monkeypatch.setenv('DEEPSEEK_API_KEY', 'do-not-return-this-key')
    monkeypatch.setenv('DEEPSEEK_CHAT_MODEL', 'test-model')
    with TestClient(app) as client:
        r = client.get('/internal/models/settings/deployment', headers=HEADERS)
        assert r.status_code == 200
        assert r.json()[0]['apiKeyConfigured'] is True
        assert 'do-not-return-this-key' not in r.text
        assert client.get('/internal/models/settings/deployment').status_code in {401, 422}


@pytest.mark.parametrize('changes', [dict(baseUrl='https://other.example/v1'), dict(provider='openai_compatible')])
def test_environment_key_cannot_follow_changed_endpoint_or_provider(monkeypatch, changes):
    monkeypatch.setenv('AI_MODEL_PROVIDER', 'deepseek')
    monkeypatch.setenv('DEEPSEEK_BASE_URL', 'https://api.deepseek.com')
    monkeypatch.setenv('DEEPSEEK_API_KEY', 'env-test-key')
    p = profile(provider='deepseek', baseUrl='https://api.deepseek.com').model_copy(update={'apiKey': None, 'useEnvironmentKey': True})
    assert resolve_profile(p).apiKey.get_secret_value() == 'env-test-key'
    with pytest.raises(ModelConfigurationError):
        resolve_profile(p.model_copy(update={'settings': p.settings.model_copy(update=changes)}))


def test_openai_compatible_deployment_namespace_resolves_environment_key(monkeypatch):
    monkeypatch.setenv('AI_MODEL_PROVIDER', 'openai_compatible')
    monkeypatch.setenv('OPENAI_COMPATIBLE_BASE_URL', 'http://127.0.0.1:9009/v1')
    monkeypatch.setenv('OPENAI_COMPATIBLE_CHAT_MODEL', 'compat-chat')
    monkeypatch.setenv('OPENAI_COMPATIBLE_API_KEY', 'compat-env-key')
    deployment = deployment_profile('DEFAULT')
    assert deployment['settings']['provider'] == 'openai_compatible'
    assert deployment['settings']['model'] == 'compat-chat'
    assert deployment['apiKeyConfigured'] is True
    p = RuntimeProfile.model_validate({
        'slot': 'DEFAULT', 'version': 0, 'settings': deployment['settings'],
        'apiKey': None, 'useEnvironmentKey': True,
    })
    assert resolve_profile(p).apiKey.get_secret_value() == 'compat-env-key'


@async_test
async def test_openai_compatible_factory_fallback_works_without_admin_snapshot(monkeypatch):
    monkeypatch.setenv('AI_MODEL_PROVIDER', 'openai_compatible')
    monkeypatch.setenv('OPENAI_COMPATIBLE_BASE_URL', 'http://127.0.0.1:9009/v1')
    monkeypatch.setenv('OPENAI_COMPATIBLE_CHAT_MODEL', 'compat-chat')
    monkeypatch.setenv('OPENAI_COMPATIBLE_API_KEY', 'compat-env-key')
    provider = create_model_provider()
    try:
        assert provider.model_name == 'compat-chat'
    finally:
        await provider.aclose()


@pytest.mark.parametrize('changes', [dict(provider='inherit'), dict(baseUrl='https://user:password@host.test/v1'), dict(baseUrl='http://example.org/v1'), dict(baseUrl='https://host.test/v1/chat/completions'), dict(model='')])
def test_rejects_invalid_models_and_urls_without_value_echo(changes):
    with TestClient(app) as client:
        r = client.post('/internal/models/settings/validate', headers=HEADERS, json=payload(profile(**changes)))
        assert r.status_code == 422
        assert 'synthetic-test-key' not in r.text
        assert 'user:password' not in r.text


@async_test
async def test_per_request_provider_snapshot_and_animation_inherit_do_not_leak_across_tasks():
    async def run(name):
        p = profile(model=name)
        token = runtime_profiles.set((p, profile('ANIMATION', provider='inherit')))
        try:
            await asyncio.sleep(0)
            provider = create_model_provider()
            animation = create_animation_model_provider()
            try:
                assert provider.model_name == name
                assert animation.model_name == name
            finally:
                await provider.aclose(); await animation.aclose()
        finally:
            runtime_profiles.reset(token)
    await asyncio.gather(run('model-a'), run('model-b'))
    assert runtime_profiles.get() == ()


@async_test
async def test_explicit_animation_provider_is_independent():
    token = runtime_profiles.set((profile(), profile('ANIMATION', model='animation-only')))
    try:
        provider = create_animation_model_provider()
        try: assert provider.model_name == 'animation-only'
        finally: await provider.aclose()
    finally: runtime_profiles.reset(token)


def test_connection_probe_success_and_error_do_not_publish_settings(monkeypatch):
    provider = SimpleNamespace(generate=AsyncMock(return_value=SimpleNamespace(structured_content={'ok': True})), aclose=AsyncMock())
    monkeypatch.setattr(model_settings, 'provider_from_profile', lambda _: provider)
    with TestClient(app) as client:
        r = client.post('/internal/models/settings/test', headers=HEADERS, json=payload(profile()))
        assert r.status_code == 200 and r.json()['success'] is True
        assert runtime_profiles.get() == ()
        provider.generate.side_effect = ModelAuthenticationError('DO_NOT_ECHO_PROVIDER_CREDENTIAL')
        r = client.post('/internal/models/settings/test', headers=HEADERS, json=payload(profile()))
        assert r.json()['code'] == 'MODEL_AUTHENTICATION_ERROR'
        assert r.json()['success'] is False
        assert 'DO_NOT_ECHO' not in r.text
        assert provider.aclose.await_count == 2
        provider.generate.side_effect = ModelRoutingUnavailableError('DO_NOT_ECHO_ROUTE_DETAIL')
        r = client.post('/internal/models/settings/test', headers=HEADERS, json=payload(profile()))
        assert r.json()['code'] == 'MODEL_ROUTING_UNAVAILABLE'
        assert r.json()['success'] is False
        assert '当前路由没有可用渠道' in r.json()['message']
        assert 'DO_NOT_ECHO' not in r.text
        assert provider.aclose.await_count == 3


def test_runtime_read_failure_explicit_and_unauthorized_request_does_not_fetch(monkeypatch):
    monkeypatch.setenv('AI_RUNTIME_SETTINGS_ENABLED', 'true')
    async def failed_get(*args, **kwargs): raise httpx2.ConnectError('private connection detail')
    monkeypatch.setattr(httpx2.AsyncClient, 'get', failed_get)
    with TestClient(app) as client:
        r = client.get('/internal/models/health', headers=HEADERS)
        assert r.status_code == 503
        assert r.json()['detail']['code'] == 'MODEL_SETTINGS_UNAVAILABLE'
        assert 'private connection detail' not in r.text
        assert client.get('/internal/models/health', headers={'X-Request-Id': HEADERS['X-Request-Id']}).status_code == 401


@async_test
async def test_snapshot_fetch_reads_latest_each_request_and_only_uses_internal_auth(monkeypatch):
    monkeypatch.setenv('AI_RUNTIME_SETTINGS_ENABLED', 'true')
    request = SimpleNamespace(url=SimpleNamespace(path='/internal/ai/tutor/chat'), headers=HEADERS, state=SimpleNamespace(request_id=HEADERS['X-Request-Id']))
    calls = []
    async def get(self, url, **kwargs):
        calls.append(kwargs['headers'])
        return httpx2.Response(200, json=[payload(profile(model='first' if len(calls)==1 else 'second'))], request=httpx2.Request('GET',url))
    monkeypatch.setattr(httpx2.AsyncClient, 'get', get)
    assert (await fetch_request_profiles(request))[0].settings.model == 'first'
    assert (await fetch_request_profiles(request))[0].settings.model == 'second'
    assert calls[0]['X-Internal-Token'] == HEADERS['X-Internal-Token']
    assert 'Authorization' not in calls[0]


@pytest.mark.parametrize('url', ['http://host.docker.internal:11434', 'http://192.168.2.23:11434', 'http://ollama.lan:11434/proxy'])
def test_administrator_local_endpoint_does_not_require_environment_allowlist(monkeypatch, url):
    monkeypatch.setenv('OLLAMA_ALLOWED_HOSTS', 'localhost')
    resolved = resolve_profile(profile(provider='ollama', baseUrl=url))
    assert resolved.settings.baseUrl == url
    assert resolved.apiKey is None


@pytest.mark.parametrize('url', ['http://localhost:11434/api/chat', 'http://localhost:11434/api/tags/', 'http://localhost:11434/v1', 'http://user:pass@localhost:11434', 'file:///tmp', 'http://localhost:99999'])
def test_local_endpoint_rejects_chat_paths_and_invalid_addresses(url):
    with pytest.raises(ModelConfigurationError):
        validate_admin_ollama_url(url)


@async_test
async def test_saved_ollama_snapshot_selects_native_provider_without_remote_fallback():
    token = runtime_profiles.set((profile(provider='ollama', baseUrl='http://host.docker.internal:11434', model='local-fixture'),))
    try:
        provider = create_model_provider()
        try:
            assert provider.provider_name == 'ollama'
            assert provider.model_name == 'local-fixture'
        finally:
            await provider.aclose()
    finally:
        runtime_profiles.reset(token)
