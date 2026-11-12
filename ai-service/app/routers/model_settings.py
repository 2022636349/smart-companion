from __future__ import annotations

import asyncio
import time

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from app.core.runtime_models import RuntimeProfile, deployment_profile, resolve_profile, provider_from_profile
from app.core.security import require_internal_headers
from app.providers.base import ModelConfigurationError, ModelProviderError, ModelMessage, ModelRequest
from app.providers.ollama_catalog import CatalogRequest, CatalogResult, discover_models

router = APIRouter(tags=['Models'], dependencies=[Depends(require_internal_headers)])


class ConnectionProbe(BaseModel):
    ok: bool


@router.post('/internal/models/settings/models', operation_id='internalListOllamaModels', response_model=CatalogResult)
async def models(request: CatalogRequest):
    try:
        return await discover_models(request.baseUrl)
    except ModelConfigurationError:
        raise HTTPException(422, detail={'code': 'MODEL_CONFIGURATION_ERROR', 'message': '请填写 Ollama 服务地址，不要附加具体 API 路径。'}) from None


def result(success, code, message, latency=0):
    return {'success': success, 'code': code, 'message': message, 'latencyMs': latency}


@router.get('/internal/models/settings/deployment', operation_id='internalModelDeploymentSettings')
def deployment():
    return [deployment_profile('DEFAULT'), deployment_profile('ANIMATION')]


@router.post('/internal/models/settings/validate', operation_id='internalValidateModelSettings')
def validate(profile: RuntimeProfile):
    try:
        resolve_profile(profile)
    except ModelConfigurationError:
        raise HTTPException(422, detail={'code': 'MODEL_CONFIGURATION_ERROR', 'message': '请检查模型、地址与密钥；更换服务地址后需重新输入密钥。'}) from None
    return result(True, 'VALID', '配置格式正确。')


@router.post('/internal/models/settings/test', operation_id='internalTestModelSettings')
async def test(profile: RuntimeProfile, request_id: str = Depends(require_internal_headers)):
    validate(profile)
    if profile.settings.provider == 'inherit':
        return result(False, 'INHERITED', '动画跟随通用模型，请在通用模型中测试连接。')
    started = time.perf_counter()
    provider = None
    try:
        provider = provider_from_profile(profile)
        async with asyncio.timeout(45 if profile.settings.provider == 'ollama' else 25):
            response = await provider.generate(ModelRequest(
                messages=(ModelMessage(role='user', content='Return exactly this JSON object: {"ok":true}'),),
                prompt_version='model-connection-probe-v1', request_id=request_id,
                capability='MODEL_CONNECTION_TEST', max_tokens=256,
            ), output_model=ConnectionProbe)
            if not response.structured_content or response.structured_content.get('ok') is not True:
                return result(False, 'INVALID_OUTPUT', '连接成功，但模型没有返回有效的结构化回答。', int((time.perf_counter()-started)*1000))
        return result(True, 'CONNECTED', '连接成功，模型已返回有效回答。', int((time.perf_counter()-started)*1000))
    except (ModelProviderError, TimeoutError) as exc:
        code = getattr(exc, 'code', 'MODEL_TIMEOUT_ERROR')
        messages = {
            'MODEL_AUTHENTICATION_ERROR': '密钥无效或没有该模型的访问权限。',
            'MODEL_RATE_LIMIT_ERROR': '服务额度不足或请求过于频繁，请检查供应商账户。',
            'MODEL_TIMEOUT_ERROR': '连接超时，请检查地址或稍后重试。',
            'MODEL_REQUEST_ERROR': '服务不接受当前模型或参数，请检查模型名和兼容选项。',
            'MODEL_ROUTING_UNAVAILABLE': '中转站当前路由没有可用渠道，请在中转站检查路由设置，或选择可用模型后重试。',
        }
        if profile.settings.provider == 'ollama':
            messages.update({
                'MODEL_TRANSPORT_ERROR': '无法连接 Ollama。请检查服务地址；Docker 内连接本机请使用 host.docker.internal。',
                'MODEL_TIMEOUT_ERROR': 'Ollama 响应超时，模型首次加载可能较慢。请在 Ollama 中预热模型后重试。',
                'MODEL_REQUEST_ERROR': 'Ollama 未接受请求，请确认所选模型已安装且支持对话。',
            })
        return result(False, code, messages.get(code, '模型连接失败，请检查地址、模型和兼容选项。'), int((time.perf_counter()-started)*1000))
    finally:
        if provider is not None:
            await provider.aclose()
