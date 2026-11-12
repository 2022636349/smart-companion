"""Explicit, one-shot connection probe for the deployment default. No settings are saved."""
import asyncio
import uuid

from app.core.runtime_models import RuntimeProfile, ConnectionSettings, deployment_profile
from app.routers.model_settings import test

def main():
    deployment = deployment_profile('DEFAULT')
    profile = RuntimeProfile(slot='DEFAULT', version=0,
        settings=ConnectionSettings.model_validate(deployment['settings']), useEnvironmentKey=True)
    result = asyncio.run(test(profile, request_id='req-' + uuid.uuid4().hex))
    print(f"success={result['success']} code={result['code']} latencyMs={result['latencyMs']}")
    return 0 if result['success'] else 1

if __name__ == '__main__':
    raise SystemExit(main())
