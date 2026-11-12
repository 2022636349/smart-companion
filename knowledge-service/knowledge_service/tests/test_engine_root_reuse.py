from copy import deepcopy

import pytest

from knowledge_service.app.domain.ingestion_dto import GraphIngestionBatchUpsertRequest, GraphPublishRequest
from knowledge_service.app.services.ingestion_service import DraftValidationFailed
from knowledge_service.operations.ingestion_validation import canonical_payload_sha256
from knowledge_service.operations.quality import quality_report
from knowledge_service.tests.test_course_graph_cycle9 import course_services, _new_course_request, _publish
from knowledge_service.tests.test_graph_ingestion_cycle7 import d_temp, _approve_all, _candidate_edge


def next_document():
    value = _new_course_request(2, 'engine-second-document-0001').model_dump(mode='json')
    value['base_graph_version'] = '1.0.0'
    root, point = value['nodes']
    root['id'] = 'root-second-pdf'
    root['description'] = '第二份教材的课程描述不能覆盖现行根。'
    point.update(id='kp-second', name='第二教材独立知识', parent_id=root['id'])
    for index, source in enumerate(value['sources']):
        source.update(id=f'second-doc-source-{index}', node_id=root['id'] if index == 0 else point['id'],
                      page_from=7, page_to=8, excerpt='第二份教材证据原文')
    value['edges'] = [_candidate_edge(source_id=root['id'], target_id=point['id'])]
    value['edges'][0]['relation'] = 'RELATED'
    provisional = GraphIngestionBatchUpsertRequest.model_validate(value)
    value['payload_sha256'] = canonical_payload_sha256(provisional)
    return GraphIngestionBatchUpsertRequest.model_validate(value)


def test_two_documents_reuse_only_reviewed_course_root_and_preserve_sources_receipts(course_services):
    graph, service = course_services(2)
    _publish(service, _new_course_request(2))
    canonical_before = deepcopy(graph.json_repo.graph['nodes'][0])
    request = next_document()
    service.upsert(request)
    ready = _approve_all(service, request.batch_id)
    raw_before = service.draft_repository.get(request.batch_id)
    assert ready['status'] == 'READY_TO_PUBLISH'
    publish = GraphPublishRequest(request_id='engine-second-publish-0001', expected_revision=ready['revision'],
                                  target_graph_version='2.0.0')
    result = service.publish(request.batch_id, publish)
    assert service.publish(request.batch_id, publish) == result
    merged = graph.json_repo.graph
    roots = [n for n in merged['nodes'] if n['node_type'] == 'course']
    assert roots == [canonical_before]
    assert {n['id'] for n in merged['nodes']} == {'root-local', '1.1', 'kp-second'}
    assert next(n for n in merged['nodes'] if n['id'] == 'kp-second')['parent_id'] == 'root-local'
    assert merged['edges'][-1]['source'] == 'root-local'
    source = next(s for s in merged['source_sections'] if s['id'] == 'second-doc-source-0')
    assert source['node_id'] == 'root-local'
    assert (source['page_from'], source['page_to'], source['excerpt']) == (7, 8, '第二份教材证据原文')
    assert merged['metadata']['course_root_reuse'][-1] == {
        'batch_id': request.batch_id, 'candidate_root_id': 'root-second-pdf', 'canonical_root_id': 'root-local'}
    assert quality_report(merged)['warnings']['course_root_reuse']
    raw_after = service.draft_repository.get(request.batch_id)
    for field in ('nodes', 'sources', 'edges', 'payload_sha256', 'decision_receipts'):
        assert raw_after.get(field) == raw_before.get(field)


def test_duplicate_concepts_are_not_automatically_merged(course_services):
    _, service = course_services(2)
    _publish(service, _new_course_request(2))
    value = next_document().model_dump(mode='json')
    value['nodes'][1]['name'] = '课程2函数'
    value['payload_sha256'] = canonical_payload_sha256(GraphIngestionBatchUpsertRequest.model_validate(value))
    result, _ = service.upsert(GraphIngestionBatchUpsertRequest.model_validate(value))
    assert any(i['code'] == 'GRAPH_DRAFT_EXISTING_NODE_NAME' for i in result['validation_issues'])


@pytest.mark.parametrize('kind', ['two_roots', 'bad_parent', 'unapproved_root', 'cross_course', 'unreviewed_batch_root', 'cross_course_draft'])
def test_invalid_root_projection_is_hard_rejected(course_services, kind):
    graph, service = course_services(2)
    _publish(service, _new_course_request(2))
    request = next_document()
    service.upsert(request)
    _approve_all(service, request.batch_id)
    record = service.draft_repository.get(request.batch_id)
    if kind == 'two_roots':
        record['nodes'].append(dict(record['nodes'][0], id='another-root', candidate_id='another-root'))
    elif kind == 'bad_parent':
        record['nodes'][0]['parent_id'] = 'kp-second'
    elif kind == 'unapproved_root':
        graph.json_repo.graph['nodes'][0]['review_status'] = 'PENDING'
    elif kind == 'cross_course':
        graph.json_repo.graph['metadata']['course_id'] = 3
    elif kind == 'unreviewed_batch_root':
        record['nodes'][0]['decision_status'] = 'PENDING'
    elif kind == 'cross_course_draft':
        record['course_id'] = 3
    from knowledge_service.app.services.ingestion_service import DraftBaseVersionConflict
    with pytest.raises((DraftValidationFailed, DraftBaseVersionConflict)):
        release = service._build_release(record, '2.0.0', service._release_content_timestamp(record))
        if not quality_report(release)['passed']:
            raise DraftValidationFailed([])
