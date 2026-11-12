import asyncio
import json
import os
from unittest.mock import AsyncMock, Mock, patch

import httpx2
import pytest

from app.agents.tutor import TutorAgent
from app.contracts.tutor import TutorAnimationActivityDraft, TutorModelOutput, TutorQuizActivityDraft
from app.core.animation_config import AnimationModelSettings, animation_provider_mode
from app.providers.base import ModelAuthenticationError, ModelConfigurationError, ModelMessage, ModelRequest, ModelResponseError
from app.providers.factory import create_animation_model_provider
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.tools import animation_check
from tests.test_deepseek_provider import StructuredAnswer, completion_response
from tests.test_tutor_agent import FakeKnowledgeClient
from tests import test_tutor_multimodal as tutor_fixtures
from tests.test_tutor_multimodal import AdaptiveProviderFactory, FailingProvider


ENV = {
    'TUTOR_ANIMATION_MODEL_PROVIDER': 'openai_compatible',
    'TUTOR_ANIMATION_BASE_URL': 'https://animation.test/v1/',
    'TUTOR_ANIMATION_API_KEY': 'animation-test-key-not-a-real-secret',
    'TUTOR_ANIMATION_MODEL': 'animation-test-model',
    'TUTOR_ANIMATION_MAX_RETRIES': '0',
}


@pytest.mark.parametrize('env,args,status,marker', [
    ({}, [], 0, 'INHERIT:'),
    ({}, ['--generate'], 2, 'INHERIT:'),
    (ENV, [], 0, 'CONFIG_OK'),
    ({**ENV, 'TUTOR_ANIMATION_API_KEY': ''}, [], 1, 'CHECK_FAILED'),
])
def test_check_without_explicit_enabled_generation_never_calls_api(env, args, status, marker, capsys):
    with patch.dict(os.environ, env, clear=True), patch('sys.argv', ['animation_check', *args]), \
            patch.object(animation_check, 'OpenAICompatibleProvider') as provider:
        assert animation_check.main() == status
        provider.assert_not_called()
    output = capsys.readouterr().out
    assert marker in output
    assert ENV['TUTOR_ANIMATION_API_KEY'] not in output


def test_check_generate_is_explicit_and_reports_provider_failure(capsys):
    generate = AsyncMock(side_effect=ModelAuthenticationError('Animation API rejected authentication.'))
    with patch.dict(os.environ, ENV, clear=True), patch('sys.argv', ['animation_check', '--generate']), \
            patch.object(animation_check, 'generate_sample', generate):
        assert animation_check.main() == 1
    generate.assert_awaited_once()
    assert 'CHECK_FAILED' in capsys.readouterr().out


@pytest.mark.parametrize('valid', [True, False])
def test_sample_validates_animation_identity_and_closes_provider(valid, capsys):
    activity = tutor_fixtures._animation('animation_self_check')
    activity['knowledge_point_id'] = 'animation-self-check' if valid else 'unexpected-kp'
    provider = Mock()
    async def generate(request, *, output_model):
        assert output_model is TutorAnimationActivityDraft
        assert request.capability == 'ANIMATION_CONNECTION_CHECK'
        assert 'CONCEPT' in request.messages[1].content
        return tutor_fixtures._response(request, activity)
    provider.generate = AsyncMock(side_effect=generate)
    provider.aclose = AsyncMock()
    with patch.dict(os.environ, ENV, clear=True), \
            patch.object(animation_check, 'OpenAICompatibleProvider', return_value=provider):
        if valid:
            asyncio.run(animation_check.generate_sample(AnimationModelSettings.from_env()))
        else:
            with pytest.raises(animation_check.TutorActivityVerificationError):
                asyncio.run(animation_check.generate_sample(AnimationModelSettings.from_env()))
    provider.aclose.assert_awaited_once()
    assert ('SAMPLE_OK' in capsys.readouterr().out) is valid


@pytest.mark.parametrize('field', ['TUTOR_ANIMATION_BASE_URL', 'TUTOR_ANIMATION_API_KEY', 'TUTOR_ANIMATION_MODEL'])
def test_override_requires_its_own_settings_without_borrowing_default(field):
    env = {**ENV, field: '', 'DEEPSEEK_API_KEY': 'default-key', 'DEEPSEEK_BASE_URL': 'https://default.test', 'DEEPSEEK_CHAT_MODEL': 'default'}
    default = Mock()
    with patch.dict(os.environ, env, clear=True), pytest.raises(ModelConfigurationError, match=field):
        create_animation_model_provider(default_factory=default)
    default.assert_not_called()


def test_unconfigured_or_inherited_animation_uses_injected_default():
    for env in ({}, {**ENV, 'TUTOR_ANIMATION_MODEL_PROVIDER': 'inherit'}):
        default = Mock(return_value=object())
        with patch.dict(os.environ, env, clear=True):
            assert animation_provider_mode() == 'inherit'
            assert create_animation_model_provider(default_factory=default) is default.return_value
        default.assert_called_once_with()


@pytest.mark.parametrize('values', [
    {'TUTOR_ANIMATION_MODEL_PROVIDER': 'typo'},
    {'TUTOR_ANIMATION_BASE_URL': 'http://remote.test/v1'},
    {'TUTOR_ANIMATION_BASE_URL': 'https://user:secret@remote.test/v1'},
    {'TUTOR_ANIMATION_BASE_URL': 'https://remote.test/v1?key=secret'},
    {'TUTOR_ANIMATION_BASE_URL': 'https://remote.test/v1/chat/completions'},
    {'TUTOR_ANIMATION_MODEL': 'x' * 101},
    {'TUTOR_ANIMATION_RESPONSE_FORMAT': 'broken'},
    {'TUTOR_ANIMATION_TOKEN_LIMIT_FIELD': 'broken'},
    {'TUTOR_ANIMATION_SEND_TEMPERATURE': 'broken'},
    {'TUTOR_ANIMATION_TIMEOUT_SECONDS': '61'},
    {'TUTOR_ANIMATION_MAX_RETRIES': '-1'},
])
def test_invalid_settings_are_actionable_and_do_not_echo_secrets(values):
    with patch.dict(os.environ, {**ENV, **values}, clear=True):
        with pytest.raises(ModelConfigurationError) as error:
            create_animation_model_provider(default_factory=Mock())
        assert 'TUTOR_ANIMATION_' in str(error.value)
        assert 'secret' not in str(error.value)
        assert ENV['TUTOR_ANIMATION_API_KEY'] not in str(error.value)


@pytest.mark.parametrize('response_format', ['json_object', 'json_schema', 'text'])
@pytest.mark.parametrize('token_field', ['max_tokens', 'max_completion_tokens'])
def test_compatible_http_payload_and_validated_response(response_format, token_field):
    seen = []
    async def run():
        def handler(request):
            seen.append(request)
            assert str(request.url) == 'https://animation.test/v1/chat/completions'
            assert request.headers['Authorization'] == 'Bearer ' + ENV['TUTOR_ANIMATION_API_KEY']
            body = json.loads(request.content)
            assert body['model'] == ENV['TUTOR_ANIMATION_MODEL']
            assert body[token_field] == 8000
            assert ('max_completion_tokens' if token_field == 'max_tokens' else 'max_tokens') not in body
            assert 'temperature' not in body and 'user_id' not in body
            assert body['stream'] is False
            assert 'JSON Schema' in body['messages'][0]['content']
            if response_format == 'text':
                assert 'response_format' not in body
            else:
                assert body['response_format']['type'] == response_format
            result = completion_response(json.dumps({'answer': '按步骤展示变化', 'confidence': .95}))
            result['model'] = 'animation-resolved-model'
            return httpx2.Response(200, json=result)
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as client:
            provider = OpenAICompatibleProvider(AnimationModelSettings.from_env(), client=client)
            result = await provider.generate(ModelRequest(messages=(ModelMessage('user', '展示函数调用动画'),),
                prompt_version='animation-test-v1', max_tokens=8000, temperature=.2), output_model=StructuredAnswer)
            assert result.structured_content['confidence'] == .95
            assert result.metadata.provider == 'openai_compatible'
            assert result.metadata.model == 'animation-resolved-model'
    with patch.dict(os.environ, {**ENV, 'TUTOR_ANIMATION_RESPONSE_FORMAT': response_format, 'TUTOR_ANIMATION_TOKEN_LIMIT_FIELD': token_field}, clear=True):
        asyncio.run(run())
    assert len(seen) == 1


@pytest.mark.parametrize('code,body,error', [
    (401, {'error': 'private upstream response'}, ModelAuthenticationError),
    (200, completion_response('not JSON'), ModelResponseError),
])
def test_provider_failures_are_not_labeled_deepseek_or_accepted(code, body, error):
    async def run():
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(lambda _: httpx2.Response(code, json=body))) as client:
            provider = OpenAICompatibleProvider(AnimationModelSettings.from_env(), client=client)
            with pytest.raises(error) as caught:
                await provider.generate(ModelRequest(messages=(ModelMessage('user', 'JSON'),), prompt_version='test'), output_model=StructuredAnswer)
            assert 'DeepSeek' not in str(caught.value)
            assert 'private upstream response' not in str(caught.value)
    with patch.dict(os.environ, ENV, clear=True):
        asyncio.run(run())


@pytest.mark.parametrize('status,body,expected', [
    (400, {'error': {'type': 'routing_strategy_resource_unavailable',
                     'message': 'private-route-detail'}}, 'MODEL_ROUTING_UNAVAILABLE'),
    (400, {'error': {'type': 'invalid_parameter', 'message': 'private-route-detail'}}, 'MODEL_REQUEST_ERROR'),
    (401, {'error': {'type': 'routing_strategy_resource_unavailable'}}, 'MODEL_AUTHENTICATION_ERROR'),
    (400, {'error': ['private-route-detail']}, 'MODEL_REQUEST_ERROR'),
])
def test_gateway_routing_failure_is_distinct_and_does_not_retry_or_echo(status, body, expected):
    async def run():
        requests = []
        def handler(request):
            requests.append(request)
            return httpx2.Response(status, json=body)
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as client:
            provider = OpenAICompatibleProvider(AnimationModelSettings.from_env(), client=client)
            from app.providers.base import ModelProviderError
            with pytest.raises(ModelProviderError) as caught:
                await provider.generate(ModelRequest(messages=(ModelMessage('user', 'JSON'),), prompt_version='test'), output_model=StructuredAnswer)
            assert caught.value.code == expected
            assert caught.value.attempts == 1
            assert len(requests) == 1
            assert 'private-route-detail' not in str(caught.value)
    with patch.dict(os.environ, {**ENV, 'TUTOR_ANIMATION_MAX_RETRIES': '2'}, clear=True):
        asyncio.run(run())


def test_animation_override_keeps_explanation_and_quiz_on_default_model():
    async def run():
        default = AdaptiveProviderFactory()
        animation = AdaptiveProviderFactory()
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=default, animation_provider_factory=animation)
        try:
            result = await agent.chat(tutor_fixtures.TutorMultimodalTest.request('MIXED'), trace_request_id='cycle8-request-0001')
        finally:
            await agent.aclose()
        assert TutorAnimationActivityDraft not in default.output_models
        assert TutorModelOutput in default.output_models
        assert TutorQuizActivityDraft in default.output_models
        assert animation.output_models == [TutorAnimationActivityDraft]
        assert all(p.closed for p in default.providers + animation.providers)
        assert next(t for t in result.specialist_trace if t.agent == 'ANIMATION').status == 'SUCCEEDED'
    asyncio.run(run())


def test_environment_override_is_resolved_only_for_animation():
    async def run():
        default = AdaptiveProviderFactory()
        animation = AdaptiveProviderFactory()
        with patch('app.providers.factory.OpenAICompatibleProvider', side_effect=lambda _: animation()) as provider:
            agent = TutorAgent(FakeKnowledgeClient(), provider_factory=default)
            try:
                await agent.chat(tutor_fixtures.TutorMultimodalTest.request('QUIZ'), trace_request_id='cycle8-request-0001')
                provider.assert_not_called()
                result = await agent.chat(tutor_fixtures.TutorMultimodalTest.request('VISUAL'), trace_request_id='cycle8-request-0001')
                provider.assert_called_once()
            finally:
                await agent.aclose()
        assert TutorAnimationActivityDraft not in default.output_models
        assert animation.output_models == [TutorAnimationActivityDraft]
        assert next(t for t in result.specialist_trace if t.agent == 'ANIMATION').status == 'SUCCEEDED'
    with patch.dict(os.environ, ENV, clear=True):
        asyncio.run(run())


def test_override_failure_keeps_verified_local_fallback_but_does_not_call_default_for_animation():
    async def run():
        default = AdaptiveProviderFactory()
        agent = TutorAgent(FakeKnowledgeClient(), provider_factory=default, animation_provider_factory=FailingProvider)
        try:
            result = await agent.chat(tutor_fixtures.TutorMultimodalTest.request('VISUAL'), trace_request_id='cycle8-request-0001')
        finally:
            await agent.aclose()
        assert TutorAnimationActivityDraft not in default.output_models
        trace = next(t for t in result.specialist_trace if t.agent == 'ANIMATION')
        assert trace.status == 'FAILED' and trace.error_code == 'ANIMATION_FALLBACK_USED'
    asyncio.run(run())
