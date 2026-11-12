from app.prompts.graph_ingestion import (
    EXTRACTOR_SYSTEM_PROMPT, REVIEWER_SYSTEM_PROMPT, REQUIRES_DIRECTION_RULE,
    GRAPH_EXTRACTOR_PROMPT_VERSION, GRAPH_REVIEWER_PROMPT_VERSION,
    GRAPH_EXTRACTOR_PROMPT_SHA256, GRAPH_REVIEWER_PROMPT_SHA256,
    build_graph_extractor_messages, build_graph_reviewer_messages,
)
from app.contracts.graph_ingestion import GraphExtractorOutput
from tests.test_graph_ingestion_agent import request, extractor_output


def test_extractor_and_reviewer_share_frozen_prerequisite_direction():
    for prompt in (EXTRACTOR_SYSTEM_PROMPT, REVIEWER_SYSTEM_PROMPT):
        assert REQUIRES_DIRECTION_RULE in prompt
        assert 'source_is_prerequisite_of_target' in prompt
        assert 'source 是应先掌握的前置知识' in prompt
        assert 'target 是依赖该前置知识的后续知识' in prompt
        assert 'source=A、target=B' in prompt
        assert '不能从章节顺序、父子层级、名称或常识推断 REQUIRES' in prompt
    assert 'REQUIRES_DIRECTION_REVERSED' in REVIEWER_SYSTEM_PROMPT
    assert 'supported=false 且 recommended_action=REJECT' in REVIEWER_SYSTEM_PROMPT
    assert GRAPH_EXTRACTOR_PROMPT_VERSION == 'graph-extractor-v10'
    assert GRAPH_REVIEWER_PROMPT_VERSION == 'graph-reviewer-v4'
    assert len(GRAPH_EXTRACTOR_PROMPT_SHA256) == len(GRAPH_REVIEWER_PROMPT_SHA256) == 64


def test_direction_rules_reach_actual_messages_and_candidates_are_not_rewritten():
    lines = ('学习后续知识B需先掌握前置知识A。',)
    extraction_messages = build_graph_extractor_messages(request(), lines)
    assert REQUIRES_DIRECTION_RULE in extraction_messages[0].content
    # Reviewer must see the original candidate endpoints and reject a wrong
    # direction itself; the prompt builder cannot silently invert graph data.
    output = extractor_output(excerpt=lines[0])
    output['sources'][0].update(source_file_id=42, locator='lines 1', source_hash='a' * 64)
    extracted = GraphExtractorOutput.model_validate(output)
    before = extracted.model_dump()
    messages = build_graph_reviewer_messages(request(), lines, extracted)
    assert REQUIRES_DIRECTION_RULE in messages[0].content
    assert 'REQUIRES_DIRECTION_REVERSED' in messages[0].content
    assert extracted.model_dump() == before
