"""Opt-in configuration/sample check without student data or business persistence."""
import argparse
import asyncio

from app.agents.teaching import TutorActivityVerificationError, TutorActivityVerifier
from app.contracts.tutor import TutorAnimationActivityDraft
from app.core.animation_config import AnimationModelSettings, animation_provider_mode
from app.providers.base import ModelMessage, ModelProviderError, ModelRequest
from app.providers.openai_compatible import OpenAICompatibleProvider


async def generate_sample(settings: AnimationModelSettings) -> None:
    provider = OpenAICompatibleProvider(settings)
    try:
        response = await provider.generate(ModelRequest(
            messages=(ModelMessage('system', '只返回规定 JSON，不生成视频、HTML 或 JavaScript。'),
                ModelMessage('user', '生成一个变量赋值示意动画。测试事实只有：变量 x 先绑定整数 1，随后绑定整数 2。'
                    'activity_ref 必须为 animation_self_check；knowledge_point_id 为 animation-self-check；'
                    'activity_type 为 ANIMATION；scene_type 用 CONCEPT。'
                    '使用不超过 3 个实体和 3 步，先高亮变量，再通过 SET_VALUE 展示值从 1 变成 2。')),
            prompt_version='animation-connection-check-v1', max_tokens=2000,
            capability='ANIMATION_CONNECTION_CHECK', request_id='animation-connection-check-0001',
        ), output_model=TutorAnimationActivityDraft)
        activity = TutorAnimationActivityDraft.model_validate(response.structured_content)
        TutorActivityVerifier.verify(activity, expected_activity_ref='animation_self_check',
                                     allowed_knowledge_point_ids={'animation-self-check'})
        print(f'SAMPLE_OK model={response.metadata.model} entities={len(activity.scene.entities)} steps={len(activity.scene.steps)}')
    finally:
        await provider.aclose()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--generate', action='store_true', help='Make a real, potentially billable model call with synthetic data')
    args = parser.parse_args()
    try:
        mode = animation_provider_mode()
        if mode == 'inherit':
            print('INHERIT: animation currently uses the default model; no independent API is enabled.')
            return 2 if args.generate else 0
        settings = AnimationModelSettings.from_env()
        print(f'CONFIG_OK provider=openai_compatible model={settings.chat_model} response_format={settings.response_format}')
        if args.generate:
            asyncio.run(generate_sample(settings))
        else:
            print('No network request was made. Use -GenerateSample to verify an actual animation response.')
        return 0
    except ModelProviderError as exc:
        print(f'CHECK_FAILED {exc.code}: {exc}')
        return 1
    except (TutorActivityVerificationError, ValueError):
        print('CHECK_FAILED: model output did not satisfy the animation contract.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
