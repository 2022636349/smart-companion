"""Read Ollama's installed models; never pull a model or generate content."""
from __future__ import annotations

import asyncio
import time

import httpx2
from pydantic import BaseModel, ConfigDict, Field

from app.core.runtime_models import validate_admin_ollama_url


class CatalogRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    baseUrl: str = Field(min_length=1, max_length=2000)


class InstalledModel(BaseModel):
    model_config = ConfigDict(extra='forbid')
    name: str = Field(min_length=1, max_length=100)
    sizeBytes: int = Field(ge=0, strict=True)
    parameterSize: str = Field(max_length=100)
    quantization: str = Field(max_length=100)


class CatalogResult(BaseModel):
    success: bool
    code: str
    message: str
    models: list[InstalledModel] = Field(default_factory=list, max_length=200)
    latencyMs: int = Field(ge=0)


async def discover_models(base_url: str) -> CatalogResult:
    base = validate_admin_ollama_url(base_url)
    started = time.perf_counter()
    def result(success, code, message, models=None):
        return CatalogResult(success=success, code=code, message=message, models=models or [],
                             latencyMs=int((time.perf_counter()-started)*1000))
    try:
        async with asyncio.timeout(12), httpx2.AsyncClient(timeout=10, trust_env=False) as client:
            response = await client.get(base + '/api/tags')
            if response.status_code == 401 or response.status_code == 403:
                return result(False, 'OLLAMA_ACCESS_DENIED', 'Ollama 服务拒绝访问，请检查该服务的访问配置。')
            if response.status_code != 200:
                return result(False, 'OLLAMA_ENDPOINT_ERROR', '未能读取 Ollama 模型列表，请确认地址指向 Ollama 原生服务。')
            if len(response.content) > 2_000_000:
                raise ValueError('Response too large')
            payload = response.json()
            models = payload.get('models') if isinstance(payload, dict) else None
            if not isinstance(models, list) or len(models) > 200:
                raise ValueError('Invalid model list')
            installed = {}
            for item in models:
                if not isinstance(item, dict):
                    raise ValueError('Invalid model')
                details = item.get('details') or {}
                if not isinstance(details, dict):
                    raise ValueError('Invalid details')
                model = InstalledModel(name=item.get('name') or item.get('model'), sizeBytes=item.get('size', 0),
                    parameterSize=details.get('parameter_size', ''), quantization=details.get('quantization_level', ''))
                installed[model.name] = model
            if not installed:
                return result(False, 'OLLAMA_NO_MODELS', '服务已连接，但尚未安装模型。请先在 Ollama 中下载一个对话模型。')
            values = sorted(installed.values(), key=lambda m: m.name.casefold())
            return result(True, 'MODELS_AVAILABLE', f'已读取 {len(values)} 个已安装模型。', values)
    except (httpx2.TimeoutException, TimeoutError):
        return result(False, 'OLLAMA_TIMEOUT', '读取超时，请检查 Ollama 是否运行及地址是否可达。')
    except httpx2.TransportError:
        return result(False, 'OLLAMA_UNREACHABLE', '无法连接 Ollama。请确认服务已启动；Docker 内连接本机请使用 host.docker.internal。')
    except (ValueError, TypeError):
        return result(False, 'OLLAMA_INVALID_RESPONSE', '服务返回的模型列表无效，请确认地址指向 Ollama 原生服务。')
