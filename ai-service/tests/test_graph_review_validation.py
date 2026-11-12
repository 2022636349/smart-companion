"""Coverage failures must remain closed, diagnosable, and safe to retry."""
import asyncio
import copy
import json
import logging

import pytest
from pydantic import ValidationError

from app.agents.graph_ingestion import (
    GraphIngestionAgent, GraphReviewerOutputError, _log_validation_failure,
    _reviewer_retry_feedback, _validation_reason_codes,
)
from app.contracts.graph_ingestion import GraphExtractorOutput, GraphReviewerOutput
from app.prompts.graph_ingestion import (
    GRAPH_REVIEWER_PROMPT_VERSION, build_graph_reviewer_messages,
)
from app.providers.base import StructuredOutputError
from tests.test_graph_ingestion_agent import (
    SequenceProvider, extractor_output,
    model_response, request, reviewer_output,
)


def extracted():
    output = extractor_output()
    output['sources'][0].update(source_file_id=42, locator='lines 1', source_hash='a' * 64)
    return GraphExtractorOutput.model_validate(output)


@pytest.mark.parametrize('change,code', [
    ('missing', 'REVIEW_INCOMPLETE_COVERAGE'),
    ('duplicate', 'REVIEW_DUPLICATE_ID'),
    ('unknown', 'REVIEW_ID_KIND_MISMATCH'),
    ('kind', 'REVIEW_ID_KIND_MISMATCH'),
    ('duplicate_kind', 'REVIEW_DUPLICATE_KIND_MISMATCH'),
])
def test_coverage_failures_have_fixed_reasons(change, code):
    output = reviewer_output()
    if change == 'missing':
        output['reviews'].pop()
    elif change == 'duplicate':
        output['reviews'].append(copy.deepcopy(output['reviews'][0]))
    elif change == 'unknown':
        output['reviews'][0]['candidate_id'] = 'unknown-model-id'
    elif change == 'kind':
        output['reviews'][0]['candidate_kind'] = 'SOURCE'
    else:
        output['reviews'][0].update(duplicate_of='evidence-1', recommended_action='HUMAN_REVIEW')
    with pytest.raises(ValueError) as failure:
        GraphIngestionAgent._validate_review_coverage(extracted(), GraphReviewerOutput.model_validate(output))
    assert _validation_reason_codes(failure.value) == (code,)
    assert code in _reviewer_retry_feedback(failure.value)


@pytest.mark.parametrize('update,code', [
    ({'supported': False}, 'REVIEW_UNSUPPORTED_ACTION'),
    ({'duplicate_of': 'node-course', 'recommended_action': 'REJECT'}, 'REVIEW_SELF_DUPLICATE'),
    ({'duplicate_of': 'node-module'}, 'REVIEW_DUPLICATE_ACCEPTED'),
])
def test_provider_wrapped_decision_validators_keep_reason_without_values(update, code, caplog):
    output = reviewer_output()
    output['reviews'][0].update(update, issues=['private-model-output-sentinel'])
    with pytest.raises(ValidationError) as failure:
        GraphReviewerOutput.model_validate(output)
    wrapped = StructuredOutputError('private-provider-text-sentinel')
    wrapped.__cause__ = failure.value
    with caplog.at_level(logging.WARNING):
        _log_validation_failure('reviewer', 1, 'safe-request-id', wrapped)
    assert _validation_reason_codes(wrapped) == (code,)
    assert code in caplog.text
    assert 'sentinel' not in caplog.text
    assert 'sentinel' not in _reviewer_retry_feedback(wrapped)


def test_review_targets_include_every_node_edge_source_and_no_private_metadata():
    output = extracted()
    messages = build_graph_reviewer_messages(request(), ('public source',), output)
    payload = json.loads('{' + messages[-1].content.split('{', 1)[1])
    assert payload['review_targets'] == [
        *({'candidate_id': node.candidate_id, 'candidate_kind': 'NODE'} for node in output.nodes),
        *({'candidate_id': edge.candidate_id, 'candidate_kind': 'EDGE'} for edge in output.edges),
        *({'candidate_id': source.evidence_id, 'candidate_kind': 'SOURCE'} for source in output.sources),
    ]
    assert 'source_file_id' not in messages[-1].content
    assert 'source_hash' not in messages[-1].content


def test_missing_source_retries_with_coverage_reason_and_never_passes_unreviewed():
    asyncio.run(_exercise_source_retry())


async def _exercise_source_retry():
    from tests.test_graph_ingestion_agent import GraphIngestionAgentTest
    incomplete = reviewer_output()
    incomplete['reviews'].pop()
    helper = GraphIngestionAgentTest()
    agent, _, _, _ = helper.make_agent(extractor_output(), incomplete)
    provider = SequenceProvider([
        model_response(incomplete, prompt_version=GRAPH_REVIEWER_PROMPT_VERSION),
        model_response(reviewer_output(), prompt_version=GRAPH_REVIEWER_PROMPT_VERSION),
    ])
    agent._reviewer_provider_factory = lambda: provider
    result = await agent.extract_and_review(request(), trace_request_id='graph-request-0001')
    assert result.sources[0].reviewer_confidence == 0.99
    assert len(provider.requests) == 2
    assert 'REVIEW_INCOMPLETE_COVERAGE' in provider.requests[1].messages[-1].content
    # Two incomplete responses must fail the entire batch, never fill in ACCEPT.
    agent, _, _, provider = helper.make_agent(extractor_output(), incomplete)
    with pytest.raises(GraphReviewerOutputError):
        await agent.extract_and_review(request(), trace_request_id='graph-request-0001')
    assert len(provider.requests) == 2
