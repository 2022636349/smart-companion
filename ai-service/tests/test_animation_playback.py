import pytest
from pydantic import ValidationError

from app.agents.teaching import TutorActivityVerifier, TutorActivityVerificationError
from app.contracts.tutor import TutorAnimationActivityDraft
from app.prompts.teaching import ANIMATION_SYSTEM_PROMPT, TUTOR_ANIMATION_SPECIALIST_PROMPT_VERSION


def draft(steps):
    return TutorAnimationActivityDraft.model_validate({
        "activity_ref": "animation_test", "activity_type": "ANIMATION", "title": "列表访问",
        "knowledge_point_id": "kp-test", "scene": {"scene_type": "ARRAY", "entities": [
            {"id": "a", "kind": "ARRAY", "label": "values", "values": ["10", "20", "30"]},
            {"id": "p", "kind": "POINTER", "label": "index"},
            {"id": "r", "kind": "TEXT", "label": "result"},
        ], "steps": [{"order": i + 1, "caption": "观察变化", "duration_ms": 1800, **step} for i, step in enumerate(steps)]}
    })


def verify(activity):
    TutorActivityVerifier.verify(activity, expected_activity_ref="animation_test", allowed_knowledge_point_ids={"kp-test"})


def test_precise_actions_and_legacy_steps():
    verify(draft([
        {"action": "SHOW", "target_ids": ["a", "p"]},
        {"action": "MOVE_POINTER", "target_ids": ["p", "a"], "indices": [-1]},
        {"action": "HIGHLIGHT", "target_ids": ["a"], "indices": [0, 1]},
        {"action": "SET_VALUE", "target_ids": ["a"], "indices": [1], "value": "40"},
        {"action": "SET_VALUE", "target_ids": ["r"], "value": "40"},
        {"action": "COMPARE", "target_ids": ["a"], "indices": [0, 2]},
    ]))


@pytest.mark.parametrize("indices", [[3], [-4], [0, 0], [30], [1.5], [True]])
def test_rejects_bad_indices(indices):
    with pytest.raises((ValidationError, TutorActivityVerificationError)):
        verify(draft([{"action": "HIGHLIGHT", "target_ids": ["a"], "indices": indices}]))


@pytest.mark.parametrize("step", [
    {"action": "MOVE_POINTER", "target_ids": ["a"], "indices": [0]},
    {"action": "MOVE_POINTER", "target_ids": ["p", "a"], "indices": [0, 1]},
    {"action": "HIGHLIGHT", "target_ids": ["r"], "indices": [0]},
    {"action": "HIDE", "target_ids": ["a"], "indices": [0]},
    {"action": "SET_VALUE", "target_ids": ["a", "r"], "indices": [0], "value": "x"},
    {"action": "SET_VALUE", "target_ids": ["a"], "indices": [0], "value": "x" * 101},
    {"action": "SET_VALUE", "target_ids": ["a"], "value": ",".join(["x"] * 31)},
])
def test_rejects_ambiguous_or_invalid_operation(step):
    with pytest.raises(TutorActivityVerificationError):
        verify(draft([step]))


def test_bounds_use_updated_array_length():
    first = {"action": "SET_VALUE", "target_ids": ["a"], "value": "10,20"}
    verify(draft([first, {"action": "HIGHLIGHT", "target_ids": ["a"], "indices": [-1]}]))
    with pytest.raises(TutorActivityVerificationError):
        verify(draft([first, {"action": "HIGHLIGHT", "target_ids": ["a"], "indices": [2]}]))


def test_prompt_requests_actual_actions_instead_of_narration_only():
    assert TUTOR_ANIMATION_SPECIALIST_PROMPT_VERSION == "tutor-animation-specialist-v2"
    assert "indices" in ANIMATION_SYSTEM_PROMPT
    assert "SET_VALUE 显示读取结果" in ANIMATION_SYSTEM_PROMPT
