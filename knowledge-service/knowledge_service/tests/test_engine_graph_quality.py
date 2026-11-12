from copy import deepcopy

import pytest

from knowledge_service.operations.quality import quality_report
from knowledge_service.app.domain.ingestion_dto import GraphPublishRequest
from knowledge_service.app.services.ingestion_service import DraftValidationFailed
from knowledge_service.tests.test_graph_ingestion_cycle7 import _service, _request, _approve_all, d_temp


def graph():
    nodes = [dict(id='root', name='课程', node_type='course', level=0, parent_id=None, review_status='APPROVED'),
             dict(id='module', name='模块', node_type='module', level=1, parent_id='root', review_status='APPROVED'),
             dict(id='chapter', name='章', node_type='chapter', level=2, parent_id='module', review_status='APPROVED')]
    nodes += [dict(id=f'kp{i}', name=f'概念{i}', node_type='knowledge_point', level=3,
                   parent_id='chapter', review_status='APPROVED') for i in range(8)]
    return dict(metadata=dict(version='1.0.0', node_count=len(nodes), edge_count=0), nodes=nodes, edges=[],
                source_sections=[dict(id=f's{i}', node_id=n['id'], review_status='APPROVED') for i,n in enumerate(nodes)])


def test_hierarchy_and_source_supported_concepts_without_semantic_edges_are_warnings():
    value = graph()
    before = deepcopy(value)
    report = quality_report(value)
    assert report['passed']
    assert len(report['warnings']['knowledge_points_without_semantic_edges']) == 8
    assert report['issues']['truly_isolated_approved_knowledge_points'] == []
    assert value == before  # no invented prerequisites or other graph mutation


@pytest.mark.parametrize('kind', ['missing_parent', 'pending_parent', 'parent_cycle', 'missing_source', 'duplicate_root', 'requires_cycle'])
def test_hard_integrity_gates_still_block(kind):
    value = graph()
    if kind == 'missing_parent':
        value['nodes'][-1]['parent_id'] = 'absent'
    elif kind == 'pending_parent':
        value['nodes'][1]['review_status'] = 'PENDING'
    elif kind == 'parent_cycle':
        value['nodes'][1]['parent_id'] = 'chapter'
    elif kind == 'missing_source':
        value['source_sections'].pop()
    elif kind == 'duplicate_root':
        value['nodes'].append(dict(value['nodes'][0], id='root2'))
        value['source_sections'].append(dict(id='s-extra', node_id='root2', review_status='APPROVED'))
        value['metadata']['node_count'] += 1
    elif kind == 'requires_cycle':
        value['edges'] = [dict(source='kp0', target='kp1', relation='requires', review_status='APPROVED'),
                          dict(source='kp1', target='kp0', relation='requires', review_status='APPROVED')]
        value['metadata']['edge_count'] = 2
    report = quality_report(value)
    assert not report['passed']


def test_release_quality_failure_precedes_claim_and_keeps_cas_revision(d_temp, monkeypatch):
    from knowledge_service.app.services import ingestion_service
    service = _service(d_temp)
    request = _request()
    service.upsert(request)
    ready = _approve_all(service, request.batch_id)
    before = service.draft_repository.get(request.batch_id)
    real_quality = quality_report

    def fail_quality(value):
        result = real_quality(value)
        result['checks']['approved_parent_chains'] = False
        result['passed'] = False
        return result

    monkeypatch.setattr(ingestion_service, 'quality_report', fail_quality)
    publish = GraphPublishRequest(request_id='engine-quality-cas-0001', expected_revision=ready['revision'],
                                  target_graph_version='engine-quality-test-1')
    for _ in range(2):
        with pytest.raises(DraftValidationFailed) as error:
            service.publish(request.batch_id, publish)
        assert 'approved_parent_chains' in error.value.issues[0]['message']
        assert service.draft_repository.get(request.batch_id) == before
        assert not list(d_temp.glob('releases/*'))
