from __future__ import annotations

import json
import os
import shutil
import stat
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from knowledge_service.app.api import graph_routes
from knowledge_service.app.domain.ingestion_dto import (
    GraphCandidateDecisionRequest,
    GraphIngestionBatchUpsertRequest,
    GraphPublishRequest,
    GraphRollbackRequest,
)
from knowledge_service.app.repository.draft_repo import (
    DraftIdempotencyConflict,
    DraftPublishClaimConflict,
    DraftReceiptCapacityExceeded,
    DraftRevisionConflict,
    FileGraphDraftRepository,
    InMemoryGraphDraftRepository,
    Neo4jGraphDraftRepository,
    MAX_DECISION_RECEIPTS,
)
from knowledge_service.app.repository.json_repo import JsonGraphRepository
from knowledge_service.app.repository.neo4j_repo import Neo4jGraphRepository
from knowledge_service.app.services.graph_service import GraphService
from knowledge_service.app.services.ingestion_service import (
    DraftNotPublishable,
    DraftValidationFailed,
    GraphIngestionService,
)
from knowledge_service.main import app
from knowledge_service.operations.ingestion_releases import (
    GraphActivationBusy,
    GraphActivationConflict,
    GraphOperationIdempotencyConflict,
    GraphOperationReceiptInvalid,
    ImmutableGraphReleaseStore,
    Neo4jGraphReleaseRepository,
)
from knowledge_service.operations.ingestion_validation import (
    canonical_payload_sha256,
    validate_draft_candidates,
)
from knowledge_service.operations.releases import D_WORKSPACE_ROOT


ROOT = Path(__file__).resolve().parents[2]
GRAPH_FILE = ROOT / "knowledge_graph_v2.json"
INTERNAL_HEADERS = {
    "X-Internal-Token": "test-internal-token",
    "X-Request-Id": "cycle7-request-0001",
}


def _remove_tree(path: Path) -> None:
    if not path.exists():
        return
    for item in path.rglob("*"):
        try:
            item.chmod(stat.S_IWRITE | stat.S_IREAD)
        except OSError:
            pass
    shutil.rmtree(path)


@pytest.fixture
def d_temp():
    root = D_WORKSPACE_ROOT / "tests" / "graph-ingestion-cycle7"
    root.mkdir(parents=True, exist_ok=True)
    path = Path(tempfile.mkdtemp(prefix="isolated-", dir=root))
    try:
        yield path
    finally:
        _remove_tree(path)


def _candidate_node(
    *,
    candidate_id: str = "candidate-node-c7-001",
    node_id: str = "c7.auto.knowledge.1",
    name: str = "自动建图候选知识点",
) -> dict:
    return {
        "candidate_id": candidate_id,
        "id": node_id,
        "name": name,
        "level": 3,
        "node_type": "knowledge_point",
        "parent_id": "5.1",
        "description": "用于验证周期七自动知识图谱审核闭环的候选知识点。",
        "aliases": ["周期七建图候选"],
        "keywords": ["图谱候选", "人工审核"],
        "difficulty": "BASIC",
        "importance": 3,
        "estimated_minutes": 15,
        "learning_objectives": ["理解候选内容必须经过人工决策后才能发布。"],
        "common_misconceptions": ["误以为高置信候选可以绕过人工审核直接发布。"],
        "example_code": ["candidate_status = 'PENDING'"],
        "rights_status": "ALLOWED",
        "evidence": ["来源目录与短证据已由 Reviewer 交叉确认。"],
        "extractor_confidence": 0.97,
        "reviewer_confidence": 0.96,
        "review_status": "PENDING",
        "decision_status": "PENDING",
        "review_lane": "STANDARD",
    }


def _candidate_edge(
    *,
    candidate_id: str = "candidate-edge-c7-001",
    source_id: str = "5.1.2",
    target_id: str = "c7.auto.knowledge.1",
) -> dict:
    return {
        "candidate_id": candidate_id,
        "source_id": source_id,
        "target_id": target_id,
        "relation": "REQUIRES",
        "evidence": ["已审核函数调用知识是该候选的直接前置。"],
        "extractor_confidence": 0.98,
        "reviewer_confidence": 0.97,
        "review_status": "PENDING",
        "decision_status": "PENDING",
        "review_lane": "STANDARD",
    }


def _candidate_source(
    *,
    candidate_id: str = "candidate-source-c7-001",
    source_id: str = "source-cycle7-auto-001",
    node_id: str = "c7.auto.knowledge.1",
) -> dict:
    return {
        "candidate_id": candidate_id,
        "id": source_id,
        "node_id": node_id,
        "source_type": "TEXTBOOK",
        "title": "Python程序设计课程审核资料",
        "chapter": "周期七候选构建",
        "page_from": 1,
        "page_to": 1,
        "locator": "周期七候选构建/人工审核闭环",
        "excerpt": None,
        "rights_status": "ALLOWED",
        "extractor_confidence": 0.99,
        "reviewer_confidence": 0.98,
        "review_status": "PENDING",
        "decision_status": "PENDING",
        "review_lane": "STANDARD",
    }


def _request(
    *,
    batch_id: str = "cycle7-batch-000001",
    nodes: list[dict] | None = None,
    edges: list[dict] | None = None,
    sources: list[dict] | None = None,
) -> GraphIngestionBatchUpsertRequest:
    value = {
        "request_id": "cycle7-request-0001",
        "batch_id": batch_id,
        "base_graph_version": "2.4.0-c5-gc.1",
        "payload_sha256": "0" * 64,
        "source": {
            "source_type": "TEXTBOOK",
            "source_id": "spring-file-9001",
            "title": "周期七受控来源",
            "file_sha256": "1" * 64,
            "locator": "第1页",
        },
        "threshold_profile": {
            "profile_version": "graph-threshold-v1",
            "node_fast_track_min": 0.90,
            "edge_fast_track_min": 0.94,
            "source_fast_track_min": 0.95,
            "reject_below": 0.65,
        },
        "models": {
            "extractor": {"provider": "ollama", "model": "local-test"},
            "reviewer": {"provider": "deepseek", "model": "cloud-test"},
        },
        "prompts": {
            "extractor": {"version": "extract-v1", "sha256": "2" * 64},
            "reviewer": {"version": "review-v1", "sha256": "3" * 64},
        },
        "nodes": nodes if nodes is not None else [_candidate_node()],
        "edges": edges if edges is not None else [_candidate_edge()],
        "sources": sources if sources is not None else [_candidate_source()],
    }
    provisional = GraphIngestionBatchUpsertRequest.model_validate(value)
    value["payload_sha256"] = canonical_payload_sha256(provisional)
    return GraphIngestionBatchUpsertRequest.model_validate(value)


def _service(
    d_temp: Path,
    draft_repository: InMemoryGraphDraftRepository | None = None,
) -> GraphIngestionService:
    graph_service = GraphService(JsonGraphRepository(GRAPH_FILE, include_pending=False))
    return GraphIngestionService(
        graph_service,
        draft_repository or InMemoryGraphDraftRepository(),
        ImmutableGraphReleaseStore(d_temp),
    )


def _approve_all(service: GraphIngestionService, batch_id: str, revision: int = 1):
    detail = service.get(batch_id)
    decisions = []
    for candidate_type, key in (
        ("NODE", "nodes"),
        ("EDGE", "edges"),
        ("SOURCE", "sources"),
    ):
        decisions.extend(
            {
                "candidate_type": candidate_type,
                "candidate_id": item["candidate_id"],
                "decision": "APPROVE",
                "comment": "人工复核来源、关系方向和事实后批准。",
            }
            for item in detail[key]
        )
    return service.decide(
        batch_id,
        GraphCandidateDecisionRequest.model_validate(
            {
                "request_id": "cycle7-approve-all-0001",
                "expected_revision": revision,
                "reviewer_id": "teacher-graph-reviewer",
                "decisions": decisions,
            }
        ),
    )


def test_batch_upsert_is_content_hash_idempotent_and_assigns_review_lanes(d_temp: Path):
    service = _service(d_temp)
    request = _request()

    first, duplicate = service.upsert(request)
    second, second_duplicate = service.upsert(request)

    assert duplicate is False
    assert second_duplicate is True
    assert second == first
    assert first["revision"] == 1
    assert first["status"] == "REVIEW_REQUIRED"
    assert {first["nodes"][0]["review_lane"], first["edges"][0]["review_lane"]} == {
        "FAST_TRACK"
    }
    assert first["sources"][0]["review_lane"] == "FAST_TRACK"

    changed = _request(nodes=[_candidate_node(name="不同载荷")])
    with pytest.raises(DraftIdempotencyConflict):
        service.upsert(changed)


def test_knowledge_point_without_graph_rag_facts_cannot_publish_even_after_approval(
    d_temp: Path,
):
    service = _service(d_temp)
    node = _candidate_node()
    node.update(
        description="",
        learning_objectives=[],
        common_misconceptions=[],
        example_code=[],
    )
    request = _request(batch_id="cycle7-batch-fact-required", nodes=[node])

    created, _ = service.upsert(request)
    assert created["status"] == "REVIEW_REQUIRED"
    assert any(
        issue["code"] == "GRAPH_DRAFT_KNOWLEDGE_FACT_REQUIRED"
        and issue["candidate_type"] == "NODE"
        and issue["candidate_id"] == node["candidate_id"]
        for issue in created["validation_issues"]
    )

    approved = _approve_all(service, request.batch_id)
    assert approved["status"] == "REVIEW_REQUIRED"
    with pytest.raises(DraftNotPublishable):
        service.publish(
            request.batch_id,
            GraphPublishRequest.model_validate(
                {
                    "request_id": "cycle7-fact-required-publish-blocked",
                    "expected_revision": approved["revision"],
                    "target_graph_version": "2.6.0-c7-fact-required-blocked",
                }
            ),
        )


def test_manual_edit_adds_graph_rag_fact_and_allows_ready_publish(d_temp: Path):
    service = _service(d_temp)
    node = _candidate_node()
    node.update(
        description="",
        learning_objectives=[],
        common_misconceptions=[],
        example_code=[],
    )
    request = _request(batch_id="cycle7-batch-fact-edit", nodes=[node])
    service.upsert(request)
    reviewed = _approve_all(service, request.batch_id)
    edited_node = deepcopy(reviewed["nodes"][0])
    edited_node["description"] = "集合交运算由同时属于两个集合的元素组成。"

    ready = service.decide(
        request.batch_id,
        GraphCandidateDecisionRequest.model_validate(
            {
                "request_id": "cycle7-fact-edit-decision",
                "expected_revision": reviewed["revision"],
                "reviewer_id": "teacher-graph-reviewer",
                "decisions": [
                    {
                        "candidate_type": "NODE",
                        "candidate_id": edited_node["candidate_id"],
                        "decision": "APPROVE",
                        "comment": "已根据授权原文补充并核验知识点定义。",
                        "edited_node": edited_node,
                    }
                ],
            }
        ),
    )
    assert ready["status"] == "READY_TO_PUBLISH"
    assert not any(
        issue["code"] == "GRAPH_DRAFT_KNOWLEDGE_FACT_REQUIRED"
        for issue in ready["validation_issues"]
    )

    published = service.publish(
        request.batch_id,
        GraphPublishRequest.model_validate(
            {
                "request_id": "cycle7-fact-edit-publish",
                "expected_revision": ready["revision"],
                "target_graph_version": "2.6.0-c7-fact-edit-ready",
            }
        ),
    )
    assert published["graph_version"] == "2.6.0-c7-fact-edit-ready"
    rag = service.graph_service.rag_context(node["id"], max_related=8)
    assert rag["context_status"] == "READY"
    assert rag["knowledge_point"]["description"] == edited_node["description"]


def test_deterministic_rules_cover_duplicates_endpoints_self_loop_cycle_sources_and_budget():
    base = {
        "nodes": [
            {"id": "a", "name": "A", "review_status": "APPROVED"},
            {"id": "b", "name": "B", "review_status": "APPROVED"},
        ],
        "edges": [
            {
                "source": "a",
                "target": "b",
                "relation": "REQUIRES",
                "review_status": "APPROVED",
            }
        ],
        "source_sections": [],
    }
    nodes = [
        {"candidate_id": "n1", "id": "new", "name": "New", "parent_id": "missing"},
        {"candidate_id": "n2", "id": "new", "name": "New", "parent_id": "a"},
    ]
    edges = [
        {
            "candidate_id": "e1",
            "source_id": "b",
            "target_id": "a",
            "relation": "REQUIRES",
        },
        {
            "candidate_id": "e2",
            "source_id": "ghost",
            "target_id": "ghost",
            "relation": "RELATED",
        },
    ]
    issues = validate_draft_candidates(base, nodes, edges, [], payload_size=2 * 1024 * 1024 + 1)
    codes = {item["code"] for item in issues}

    assert {
        "GRAPH_DRAFT_BUDGET_EXCEEDED",
        "GRAPH_DRAFT_DUPLICATE_NODE_ID",
        "GRAPH_DRAFT_DUPLICATE_NODE_NAME",
        "GRAPH_DRAFT_PARENT_NOT_FOUND",
        "GRAPH_DRAFT_SELF_LOOP",
        "GRAPH_DRAFT_EDGE_ENDPOINT_NOT_FOUND",
        "GRAPH_DRAFT_REQUIRES_CYCLE",
        "GRAPH_DRAFT_NODE_SOURCE_REQUIRED",
    } <= codes


def test_manual_decisions_are_atomic_revisioned_filterable_and_stale_safe(d_temp: Path):
    service = _service(d_temp)
    request = _request()
    service.upsert(request)

    node_only = service.decide(
        request.batch_id,
        GraphCandidateDecisionRequest.model_validate(
            {
                "request_id": "cycle7-request-0002",
                "expected_revision": 1,
                "reviewer_id": "teacher-1",
                "decisions": [
                    {
                        "candidate_type": "NODE",
                        "candidate_id": "candidate-node-c7-001",
                        "decision": "APPROVE",
                        "comment": "已人工核对节点事实。",
                    }
                ],
            }
        ),
    )
    assert node_only["revision"] == 2
    assert node_only["pending_count"] == 2
    assert node_only["status"] == "REVIEW_REQUIRED"

    with pytest.raises(DraftRevisionConflict):
        service.decide(
            request.batch_id,
            GraphCandidateDecisionRequest.model_validate(
                {
                    "request_id": "cycle7-request-0003",
                    "expected_revision": 1,
                    "reviewer_id": "teacher-1",
                    "decisions": [
                        {
                            "candidate_type": "EDGE",
                            "candidate_id": "candidate-edge-c7-001",
                            "decision": "APPROVE",
                            "comment": "过期版本不得覆盖。",
                        }
                    ],
                }
            ),
        )

    page = service.candidates(
        request.batch_id,
        candidate_type=None,
        decision_status="PENDING",
        review_lane=None,
        limit=1,
        cursor=None,
    )
    assert len(page["items"]) == 1
    assert page["next_cursor"]
    next_page = service.candidates(
        request.batch_id,
        candidate_type=None,
        decision_status="PENDING",
        review_lane=None,
        limit=10,
        cursor=page["next_cursor"],
    )
    assert len(next_page["items"]) == 1

    final = _approve_all(service, request.batch_id, revision=2)
    assert final["revision"] == 3
    assert final["pending_count"] == 0
    assert final["approved_count"] == 3
    assert final["error_count"] == 0
    assert final["status"] == "READY_TO_PUBLISH"


def test_decision_request_receipt_replays_response_loss_and_rejects_key_reuse(
    d_temp: Path,
):
    repository = InMemoryGraphDraftRepository()
    service = _service(d_temp, repository)
    request = _request(batch_id="cycle7-batch-decision-idem")
    created, _ = service.upsert(request)
    decision_value = {
        "request_id": "cycle7-decision-idem-0001",
        "expected_revision": created["revision"],
        "reviewer_id": "teacher-idempotency",
        "decisions": [
            {
                "candidate_type": "NODE",
                "candidate_id": created["nodes"][0]["candidate_id"],
                "decision": "APPROVE",
                "comment": "版权与事实均已复核。",
            }
        ],
    }
    decision = GraphCandidateDecisionRequest.model_validate(decision_value)
    first = service.decide(request.batch_id, decision)
    replayed = service.decide(request.batch_id, decision)
    assert replayed == first
    raw = repository.get(request.batch_id)
    assert raw is not None
    receipts = raw["decision_receipts"]
    assert list(receipts) == [decision.request_id]
    assert receipts[decision.request_id]["resulting_revision"] == first["revision"]
    receipt_json = json.dumps(receipts, ensure_ascii=False)
    assert created["nodes"][0]["name"] not in receipt_json
    assert "nodes" not in receipts[decision.request_id]

    changed = deepcopy(decision_value)
    changed["decisions"][0]["comment"] = "同一键不得替换为不同审核内容。"
    with pytest.raises(DraftIdempotencyConflict):
        service.decide(
            request.batch_id,
            GraphCandidateDecisionRequest.model_validate(changed),
        )


def test_concurrent_identical_decisions_commit_once_and_file_receipt_replays(
    d_temp: Path,
):
    repository = InMemoryGraphDraftRepository()
    first_service = _service(d_temp / "memory-service", repository)
    second_service = GraphIngestionService(
        first_service.graph_service,
        repository,
        first_service.release_store,
    )
    request = _request(batch_id="cycle7-batch-decision-race")
    created, _ = first_service.upsert(request)
    decision = GraphCandidateDecisionRequest.model_validate(
        {
            "request_id": "cycle7-decision-concurrent-0001",
            "expected_revision": created["revision"],
            "reviewer_id": "teacher-concurrent",
            "decisions": [
                {
                    "candidate_type": "NODE",
                    "candidate_id": created["nodes"][0]["candidate_id"],
                    "decision": "APPROVE",
                    "comment": "并发重放只能提交一次。",
                }
            ],
        }
    )
    barrier = threading.Barrier(2)

    def submit(instance: GraphIngestionService):
        barrier.wait()
        return instance.decide(request.batch_id, decision)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(submit, (first_service, second_service)))
    assert results[0] == results[1]
    assert results[0]["revision"] == 2
    assert len(repository.get(request.batch_id)["decision_receipts"]) == 1

    file_repository = FileGraphDraftRepository(d_temp / "file-drafts")
    file_record = {
        "batch_id": "cycle7-file-receipt-0001",
        "payload_sha256": "f" * 64,
        "status": "READY_TO_PUBLISH",
        "revision": 1,
        "created_at": "2026-08-25T00:00:00Z",
        "updated_at": "2026-08-25T00:00:00Z",
    }
    file_repository.create(file_record)
    updated = deepcopy(file_record)
    updated["revision"] = 2
    updated["updated_at"] = "2026-08-25T00:01:00Z"
    saved, replayed = file_repository.save_decisions(
        file_record["batch_id"],
        1,
        updated,
        request_id="cycle7-file-decision-0001",
        request_sha256="c" * 64,
        receipt={"resulting_revision": 2},
    )
    assert replayed is False
    same, replayed = file_repository.save_decisions(
        file_record["batch_id"],
        1,
        updated,
        request_id="cycle7-file-decision-0001",
        request_sha256="c" * 64,
        receipt={"resulting_revision": 2},
    )
    assert replayed is True
    assert same == saved
    with pytest.raises(DraftIdempotencyConflict):
        file_repository.save_decisions(
            file_record["batch_id"],
            1,
            updated,
            request_id="cycle7-file-decision-0001",
            request_sha256="d" * 64,
            receipt={"resulting_revision": 2},
        )


def test_decision_receipt_capacity_is_bounded_but_existing_keys_still_replay():
    repository = InMemoryGraphDraftRepository()
    receipts = {
        f"receipt-{index:04d}": {
            "request_id": f"receipt-{index:04d}",
            "request_sha256": f"{index:064x}",
            "resulting_revision": 1,
        }
        for index in range(MAX_DECISION_RECEIPTS)
    }
    record = {
        "batch_id": "cycle7-receipt-cap-0001",
        "payload_sha256": "a" * 64,
        "status": "READY_TO_PUBLISH",
        "revision": 1,
        "created_at": "2026-08-25T00:00:00Z",
        "updated_at": "2026-08-25T00:00:00Z",
        "decision_receipts": receipts,
    }
    repository.create(record)
    updated = deepcopy(record)
    updated["revision"] = 2
    replayed_record, replayed = repository.save_decisions(
        record["batch_id"],
        1,
        updated,
        request_id="receipt-0000",
        request_sha256=f"{0:064x}",
        receipt={"resulting_revision": 1},
    )
    assert replayed is True
    assert replayed_record["revision"] == 1
    with pytest.raises(DraftReceiptCapacityExceeded):
        repository.save_decisions(
            record["batch_id"],
            1,
            updated,
            request_id="receipt-over-cap",
            request_sha256="f" * 64,
            receipt={"resulting_revision": 2},
        )
    assert len(repository.get(record["batch_id"])["decision_receipts"]) == 2048


def test_pending_never_leaks_publish_creates_immutable_release_and_rollback_switches_pointer(
    d_temp: Path,
):
    original_bytes = GRAPH_FILE.read_bytes()
    service = _service(d_temp)
    request = _request()
    service.upsert(request)
    assert service.graph_service.get_node("c7.auto.knowledge.1") is None
    ready = _approve_all(service, request.batch_id)

    result = service.publish(
        request.batch_id,
        GraphPublishRequest.model_validate(
            {
                "request_id": "cycle7-request-0003",
                "expected_revision": ready["revision"],
                "target_graph_version": "2.6.0-c7-test.1",
            }
        ),
    )

    assert result["previous_graph_version"] == "2.4.0-c5-gc.1"
    assert result["graph_version"] == "2.6.0-c7-test.1"
    published = service.graph_service.get_node("c7.auto.knowledge.1")
    assert published is not None
    assert published["review_status"] == "APPROVED"
    assert GRAPH_FILE.read_bytes() == original_bytes
    verified = service.release_store.get("2.6.0-c7-test.1")
    assert Path(verified["graph_file"]).is_file()
    assert Path(verified["graph_file"]).resolve() != GRAPH_FILE.resolve()

    repeated = service.publish(
        request.batch_id,
        GraphPublishRequest.model_validate(
            {
                "request_id": "cycle7-request-0004",
                "expected_revision": ready["revision"],
                "target_graph_version": "2.6.0-c7-test.1",
            }
        ),
    )
    assert repeated == result

    rollback_request = GraphRollbackRequest.model_validate(
        {
            "request_id": "cycle7-request-0005",
            "expected_current_version": "2.6.0-c7-test.1",
        }
    )
    rolled_back = service.rollback(
        "2.4.0-c5-gc.1",
        rollback_request,
    )
    assert rolled_back["graph_version"] == "2.4.0-c5-gc.1"
    assert service.graph_service.get_node("c7.auto.knowledge.1") is None
    assert GRAPH_FILE.read_bytes() == original_bytes
    assert service.rollback("2.4.0-c5-gc.1", rollback_request) == rolled_back
    with pytest.raises(GraphOperationIdempotencyConflict):
        service.rollback("2.6.0-c7-test.1", rollback_request)
    receipt_files = list((d_temp / "state" / "operation-receipts").glob("*.json"))
    assert len(receipt_files) == 1
    receipt_text = receipt_files[0].read_text(encoding="utf-8")
    assert "c7.auto.knowledge.1" not in receipt_text
    assert receipt_files[0].stat().st_size < 8192
    with pytest.raises(GraphOperationReceiptInvalid):
        service.release_store.save_operation_receipt(
            "ROLLBACK",
            "cycle7-invalid-operation-receipt",
            "a" * 64,
            {**rolled_back, "candidate_text": "禁止写入回执"},
        )


def test_publish_claim_and_decision_are_atomic_under_concurrency(d_temp: Path):
    repository = InMemoryGraphDraftRepository()
    service = _service(d_temp, repository)
    request = _request(batch_id="cycle7-batch-race001")
    service.upsert(request)
    ready = _approve_all(service, request.batch_id)
    current = repository.get(request.batch_id)
    assert current is not None
    decision_record = deepcopy(current)
    decision_record["revision"] = int(current["revision"]) + 1
    barrier = threading.Barrier(2)

    def claim():
        barrier.wait()
        return repository.claim_publish(
            request.batch_id,
            ready["revision"],
            claim_id="cycle7-publish-race-0001",
            target_graph_version="2.6.0-c7-race.1",
            claimed_at="2026-08-25T08:00:00Z",
            expires_at="2099-08-25T08:05:00Z",
            allow_takeover=False,
        )

    def decide():
        barrier.wait()
        return repository.save_decisions(
            request.batch_id,
            ready["revision"],
            decision_record,
            request_id="cycle7-decision-race-0001",
            request_sha256="a" * 64,
            receipt={"resulting_revision": decision_record["revision"]},
        )

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(claim), pool.submit(decide)]
        outcomes = []
        for future in futures:
            try:
                outcomes.append(("success", future.result()))
            except Exception as exc:  # exact losing type asserted below
                outcomes.append(("error", exc))
    assert sum(kind == "success" for kind, _ in outcomes) == 1
    losing = next(value for kind, value in outcomes if kind == "error")
    assert isinstance(losing, (DraftPublishClaimConflict, DraftRevisionConflict))

    final = repository.get(request.batch_id)
    assert final is not None
    if final.get("publish_claim"):
        blocked = deepcopy(final)
        blocked["revision"] = int(final["revision"]) + 1
        with pytest.raises(DraftPublishClaimConflict):
            repository.save_decisions(
                request.batch_id,
                int(final["revision"]),
                blocked,
                request_id="cycle7-decision-blocked-0002",
                request_sha256="b" * 64,
                receipt={"resulting_revision": blocked["revision"]},
            )


class _FinalizeFailsOnceRepository(InMemoryGraphDraftRepository):
    def __init__(self) -> None:
        super().__init__()
        self.failures_remaining = 1

    def finalize_publish(self, *args, **kwargs):
        if self.failures_remaining:
            self.failures_remaining -= 1
            raise RuntimeError("injected finalize failure")
        return super().finalize_publish(*args, **kwargs)


def test_publish_recovers_after_activation_before_draft_finalization(d_temp: Path):
    repository = _FinalizeFailsOnceRepository()
    service = _service(d_temp, repository)
    request = _request(batch_id="cycle7-batch-recover1")
    service.upsert(request)
    ready = _approve_all(service, request.batch_id)
    publish_request = GraphPublishRequest.model_validate(
        {
            "request_id": "cycle7-publish-recover-0001",
            "expected_revision": ready["revision"],
            "target_graph_version": "2.6.0-c7-recover.1",
        }
    )

    with pytest.raises(RuntimeError, match="injected finalize failure"):
        service.publish(request.batch_id, publish_request)
    claimed = repository.get(request.batch_id)
    assert claimed is not None
    assert claimed["status"] == "READY_TO_PUBLISH"
    assert claimed["publish_claim"]["claim_id"] == publish_request.request_id
    assert service.release_store.current()["graph_version"] == "2.6.0-c7-recover.1"

    with pytest.raises(DraftPublishClaimConflict):
        service.decide(
            request.batch_id,
            GraphCandidateDecisionRequest.model_validate(
                {
                    "request_id": "cycle7-decision-blocked-0001",
                    "expected_revision": claimed["revision"],
                    "reviewer_id": "teacher-race",
                    "decisions": [
                        {
                            "candidate_type": "NODE",
                            "candidate_id": claimed["nodes"][0]["candidate_id"],
                            "decision": "REJECT",
                            "comment": "发布认领后不得改变人工决策。",
                        }
                    ],
                }
            ),
        )

    recovered = service.publish(request.batch_id, publish_request)
    assert recovered["graph_version"] == "2.6.0-c7-recover.1"
    finalized = repository.get(request.batch_id)
    assert finalized is not None
    assert finalized["status"] == "PUBLISHED"
    assert finalized["publish_claim"] is None
    assert not (d_temp / "state" / "activation-lease.json").exists()


def test_activation_failure_releases_claim_and_retry_can_publish(
    d_temp: Path, monkeypatch: pytest.MonkeyPatch
):
    service = _service(d_temp)
    request = _request(batch_id="cycle7-batch-retry001")
    service.upsert(request)
    ready = _approve_all(service, request.batch_id)
    original_activate = service.release_store.activate
    failures = 1

    def fail_before_switch(*args, **kwargs):
        nonlocal failures
        if failures:
            failures -= 1
            raise OSError("injected activation failure")
        return original_activate(*args, **kwargs)

    monkeypatch.setattr(service.release_store, "activate", fail_before_switch)
    first_request = GraphPublishRequest.model_validate(
        {
            "request_id": "cycle7-publish-retry-0001",
            "expected_revision": ready["revision"],
            "target_graph_version": "2.6.0-c7-retry.1",
        }
    )
    with pytest.raises(OSError, match="injected activation failure"):
        service.publish(request.batch_id, first_request)
    released = service.get(request.batch_id)
    raw = service.draft_repository.get(request.batch_id)
    assert raw is not None and raw.get("publish_claim") is None
    assert service._durable_current_version() == request.base_graph_version
    assert not (d_temp / "state" / "activation-lease.json").exists()

    retry = service.publish(
        request.batch_id,
        GraphPublishRequest.model_validate(
            {
                "request_id": "cycle7-publish-retry-0002",
                "expected_revision": released["revision"],
                "target_graph_version": "2.6.0-c7-retry.1",
            }
        ),
    )
    assert retry["graph_version"] == "2.6.0-c7-retry.1"


def test_rollback_expected_version_is_atomic_across_service_instances(d_temp: Path):
    service = _service(d_temp)
    request = _request(batch_id="cycle7-batch-rollback1")
    service.upsert(request)
    ready = _approve_all(service, request.batch_id)
    service.publish(
        request.batch_id,
        GraphPublishRequest.model_validate(
            {
                "request_id": "cycle7-publish-rollback-0001",
                "expected_revision": ready["revision"],
                "target_graph_version": "2.6.0-c7-rollback.1",
            }
        ),
    )
    peer = GraphIngestionService(
        GraphService(JsonGraphRepository(GRAPH_FILE, include_pending=False)),
        InMemoryGraphDraftRepository(),
        ImmutableGraphReleaseStore(d_temp),
    )
    barrier = threading.Barrier(2)

    def rollback(instance: GraphIngestionService, request_id: str):
        barrier.wait()
        return instance.rollback(
            request.base_graph_version,
            GraphRollbackRequest.model_validate(
                {
                    "request_id": request_id,
                    "expected_current_version": "2.6.0-c7-rollback.1",
                }
            ),
        )

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [
            pool.submit(rollback, service, "cycle7-rollback-race-0001"),
            pool.submit(rollback, peer, "cycle7-rollback-race-0002"),
        ]
        outcomes = []
        for future in futures:
            try:
                outcomes.append(("success", future.result()))
            except Exception as exc:
                outcomes.append(("error", exc))
    assert sum(kind == "success" for kind, _ in outcomes) == 1
    losing = next(value for kind, value in outcomes if kind == "error")
    assert isinstance(losing, (GraphActivationBusy, GraphActivationConflict))
    assert service.release_store.current()["graph_version"] == request.base_graph_version


def test_rollback_recovers_when_receipt_write_fails_after_activation(
    d_temp: Path, monkeypatch: pytest.MonkeyPatch
):
    service = _service(d_temp)
    request = _request(batch_id="cycle7-batch-rollback-recover")
    service.upsert(request)
    ready = _approve_all(service, request.batch_id)
    target_version = "2.6.0-c7-rollback-recover.1"
    service.publish(
        request.batch_id,
        GraphPublishRequest.model_validate(
            {
                "request_id": "cycle7-publish-rollback-recover",
                "expected_revision": ready["revision"],
                "target_graph_version": target_version,
            }
        ),
    )
    original_save = service.release_store.save_operation_receipt
    failures = 1

    def fail_receipt_once(*args, **kwargs):
        nonlocal failures
        if failures:
            failures -= 1
            raise RuntimeError("injected rollback receipt failure")
        return original_save(*args, **kwargs)

    monkeypatch.setattr(
        service.release_store, "save_operation_receipt", fail_receipt_once
    )
    rollback_request = GraphRollbackRequest.model_validate(
        {
            "request_id": "cycle7-rollback-recover-0001",
            "expected_current_version": target_version,
        }
    )
    with pytest.raises(RuntimeError, match="injected rollback receipt failure"):
        service.rollback(request.base_graph_version, rollback_request)
    assert service._durable_current_version() == request.base_graph_version
    assert (d_temp / "state" / "activation-lease.json").is_file()

    recovered = service.rollback(request.base_graph_version, rollback_request)
    assert recovered["previous_graph_version"] == target_version
    assert recovered["graph_version"] == request.base_graph_version
    assert not (d_temp / "state" / "activation-lease.json").exists()
    assert service.rollback(request.base_graph_version, rollback_request) == recovered


def test_rejected_invalid_candidates_are_excluded_before_release_validation(d_temp: Path):
    service = _service(d_temp)
    duplicate_node = _candidate_node(
        candidate_id="candidate-node-c7-bad",
        node_id="5.1.2",
        name="函数调用",
    )
    duplicate_source = _candidate_source(
        candidate_id="candidate-source-c7-bad",
        source_id="source-cycle7-bad",
        node_id="5.1.2",
    )
    request = _request(
        batch_id="cycle7-batch-reject1",
        nodes=[_candidate_node(), duplicate_node],
        edges=[_candidate_edge()],
        sources=[_candidate_source(), duplicate_source],
    )
    created, _ = service.upsert(request)
    assert created["error_count"] > 0

    decisions = []
    for kind, key in (("NODE", "nodes"), ("EDGE", "edges"), ("SOURCE", "sources")):
        for item in created[key]:
            reject = item["candidate_id"].endswith("-bad")
            decisions.append(
                {
                    "candidate_type": kind,
                    "candidate_id": item["candidate_id"],
                    "decision": "REJECT" if reject else "APPROVE",
                    "comment": "拒绝重复候选。" if reject else "人工审核批准。",
                }
            )
    reviewed = service.decide(
        request.batch_id,
        GraphCandidateDecisionRequest.model_validate(
            {
                "request_id": "cycle7-request-0006",
                "expected_revision": 1,
                "reviewer_id": "teacher-2",
                "decisions": decisions,
            }
        ),
    )
    assert reviewed["rejected_count"] == 2
    assert reviewed["error_count"] == 0
    assert reviewed["status"] == "READY_TO_PUBLISH"


def test_restricted_rights_cannot_publish_until_human_edit_marks_allowed(d_temp: Path):
    service = _service(d_temp)
    restricted_node = _candidate_node()
    restricted_node["rights_status"] = "RESTRICTED"
    request = _request(
        batch_id="cycle7-batch-rights001",
        nodes=[restricted_node],
    )
    created, _ = service.upsert(request)
    assert any(
        issue["code"] == "GRAPH_RIGHTS_NOT_ALLOWED"
        and issue["severity"] == "ERROR"
        for issue in created["validation_issues"]
    )
    reviewed = _approve_all(service, request.batch_id)
    assert reviewed["status"] == "REVIEW_REQUIRED"
    with pytest.raises(DraftNotPublishable):
        service.publish(
            request.batch_id,
            GraphPublishRequest.model_validate(
                {
                    "request_id": "cycle7-rights-publish-0001",
                    "expected_revision": reviewed["revision"],
                    "target_graph_version": "2.6.0-c7-rights.1",
                }
            ),
        )

    edited_node = deepcopy(reviewed["nodes"][0])
    edited_node["rights_status"] = "ALLOWED"
    allowed = service.decide(
        request.batch_id,
        GraphCandidateDecisionRequest.model_validate(
            {
                "request_id": "cycle7-rights-decision-0001",
                "expected_revision": reviewed["revision"],
                "reviewer_id": "teacher-rights-reviewer",
                "decisions": [
                    {
                        "candidate_type": "NODE",
                        "candidate_id": edited_node["candidate_id"],
                        "decision": "APPROVE",
                        "comment": "已核验授权证明，允许进入正式知识图谱。",
                        "edited_node": edited_node,
                    }
                ],
            }
        ),
    )
    assert allowed["status"] == "READY_TO_PUBLISH"
    assert not any(
        issue["code"] == "GRAPH_RIGHTS_NOT_ALLOWED"
        for issue in allowed["validation_issues"]
    )
    published = service.publish(
        request.batch_id,
        GraphPublishRequest.model_validate(
            {
                "request_id": "cycle7-rights-publish-0002",
                "expected_revision": allowed["revision"],
                "target_graph_version": "2.6.0-c7-rights.1",
            }
        ),
    )
    assert published["graph_version"] == "2.6.0-c7-rights.1"
    assert service.graph_service.get_node(restricted_node["id"])["rights_status"] == "ALLOWED"


def test_source_rights_are_mandatory_and_pending_is_not_a_valid_draft_state():
    missing = _request().model_dump(mode="json")
    missing["sources"][0].pop("rights_status")
    with pytest.raises(ValueError):
        GraphIngestionBatchUpsertRequest.model_validate(missing)

    pending = _request().model_dump(mode="json")
    pending["sources"][0]["rights_status"] = "PENDING"
    with pytest.raises(ValueError):
        GraphIngestionBatchUpsertRequest.model_validate(pending)

    edited_source = _candidate_source()
    edited_source.pop("rights_status")
    with pytest.raises(ValueError):
        GraphCandidateDecisionRequest.model_validate(
            {
                "request_id": "cycle7-source-rights-shape-0001",
                "expected_revision": 1,
                "reviewer_id": "teacher-rights-reviewer",
                "decisions": [
                    {
                        "candidate_type": "SOURCE",
                        "candidate_id": edited_source["candidate_id"],
                        "decision": "APPROVE",
                        "comment": "来源授权状态必须由明确编辑提交。",
                        "edited_source": edited_source,
                    }
                ],
            }
        )


def test_restricted_source_for_existing_node_requires_explicit_edit_and_release_rechecks(
    d_temp: Path,
):
    service = _service(d_temp)
    restricted_source = _candidate_source(
        candidate_id="candidate-source-existing-rights",
        source_id="source-existing-rights",
        node_id="5.1.2",
    )
    restricted_source["rights_status"] = "RESTRICTED"
    request = _request(
        batch_id="cycle7-source-rights-0001",
        nodes=[],
        edges=[],
        sources=[restricted_source],
    )
    created, _ = service.upsert(request)
    assert any(
        issue["code"] == "GRAPH_RIGHTS_NOT_ALLOWED"
        and issue["candidate_type"] == "SOURCE"
        and issue["candidate_id"] == restricted_source["candidate_id"]
        for issue in created["validation_issues"]
    )

    reviewed = _approve_all(service, request.batch_id)
    assert reviewed["status"] == "REVIEW_REQUIRED"
    with pytest.raises(DraftNotPublishable):
        service.publish(
            request.batch_id,
            GraphPublishRequest.model_validate(
                {
                    "request_id": "cycle7-source-publish-block-0001",
                    "expected_revision": reviewed["revision"],
                    "target_graph_version": "2.6.0-c7-source-rights.1",
                }
            ),
        )

    # Release construction has an independent fail-closed check, so a stale or
    # accidentally cleared validation report cannot turn a restricted source
    # into an official source for an already-existing graph node.
    bypass_attempt = deepcopy(reviewed)
    bypass_attempt["validation_issues"] = []
    with pytest.raises(DraftValidationFailed) as release_error:
        service._build_release(
            bypass_attempt,
            "2.6.0-c7-source-rights-bypass",
            "2026-08-25T00:00:00Z",
        )
    assert release_error.value.issues[0]["code"] == "GRAPH_RIGHTS_NOT_ALLOWED"
    assert release_error.value.issues[0]["candidate_type"] == "SOURCE"

    edited_source = deepcopy(reviewed["sources"][0])
    edited_source["rights_status"] = "ALLOWED"
    allowed = service.decide(
        request.batch_id,
        GraphCandidateDecisionRequest.model_validate(
            {
                "request_id": "cycle7-source-rights-edit-0001",
                "expected_revision": reviewed["revision"],
                "reviewer_id": "teacher-rights-reviewer",
                "decisions": [
                    {
                        "candidate_type": "SOURCE",
                        "candidate_id": edited_source["candidate_id"],
                        "decision": "APPROVE",
                        "comment": "已核验该来源的授权证明，明确允许用于知识图谱发布。",
                        "edited_source": edited_source,
                    }
                ],
            }
        ),
    )
    assert allowed["status"] == "READY_TO_PUBLISH"
    assert not any(
        issue["code"] == "GRAPH_RIGHTS_NOT_ALLOWED"
        for issue in allowed["validation_issues"]
    )
    published = service.publish(
        request.batch_id,
        GraphPublishRequest.model_validate(
            {
                "request_id": "cycle7-source-publish-allow-0001",
                "expected_revision": allowed["revision"],
                "target_graph_version": "2.6.0-c7-source-rights.1",
            }
        ),
    )
    assert published["graph_version"] == "2.6.0-c7-source-rights.1"
    released_source = next(
        item
        for item in service.graph_service.sources("5.1.2", approved_only=True)
        if item["id"] == restricted_source["id"]
    )
    assert released_source["rights_status"] == "ALLOWED"


def test_internal_cycle7_routes_require_auth_request_id_and_proxy_pagination(
    d_temp: Path, monkeypatch: pytest.MonkeyPatch
):
    monkeypatch.setenv("INTERNAL_SERVICE_TOKEN", "test-internal-token")
    service = _service(d_temp)
    monkeypatch.setattr(graph_routes, "graph_ingestion_service", service)
    client = TestClient(app)
    client.params = {"course_id": 1}
    request = _request()
    payload = request.model_dump(mode="json")

    assert (
        client.post(
            "/internal/graph/ingestion-batches",
            headers={"X-Request-Id": "cycle7-request-0001"},
            json=payload,
        ).status_code
        == 401
    )
    mismatched = dict(INTERNAL_HEADERS)
    mismatched["X-Request-Id"] = "cycle7-request-other"
    response = client.post(
        "/internal/graph/ingestion-batches", headers=mismatched, json=payload
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VALIDATION_ERROR"

    missing_source_rights = deepcopy(payload)
    missing_source_rights["sources"][0].pop("rights_status")
    response = client.post(
        "/internal/graph/ingestion-batches",
        headers=INTERNAL_HEADERS,
        json=missing_source_rights,
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VALIDATION_ERROR"

    response = client.post(
        "/internal/graph/ingestion-batches", headers=INTERNAL_HEADERS, json=payload
    )
    assert response.status_code == 200
    assert response.json()["duplicate"] is False

    response = client.get(
        f"/internal/graph/ingestion-batches/{request.batch_id}/candidates",
        headers=INTERNAL_HEADERS,
        params={"type": "NODE", "decision_status": "PENDING", "limit": 100},
    )
    assert response.status_code == 200
    assert [item["candidate_type"] for item in response.json()["items"]] == ["NODE"]

    response = client.get(
        "/internal/graph/ingestion-batches",
        headers=INTERNAL_HEADERS,
        params={"status": "REVIEW_REQUIRED"},
    )
    assert response.status_code == 200
    assert response.json()["items"][0]["batch_id"] == request.batch_id


@pytest.mark.skipif(
    os.getenv("RUN_CYCLE7_NEO4J_INTEGRATION") != "1",
    reason="requires an explicitly isolated Neo4j database",
)
def test_cycle7_neo4j_draft_and_release_live_gate():
    if os.getenv("CYCLE7_NEO4J_ISOLATED") != "1":
        pytest.skip("CYCLE7_NEO4J_ISOLATED=1 is required")
    database = os.getenv("CYCLE7_NEO4J_DATABASE") or None
    uri = os.getenv("NEO4J_URI", "bolt://localhost:7687")
    user = os.getenv("NEO4J_USER", "neo4j")
    password = os.getenv("NEO4J_PASSWORD", "")
    if not password:
        pytest.skip("NEO4J_PASSWORD is not configured")
    draft_repo = Neo4jGraphDraftRepository(
        uri, user, password, database=database, query_timeout_seconds=5
    )
    release_repo = Neo4jGraphReleaseRepository(
        uri, user, password, database=database, query_timeout_seconds=15
    )
    batch_id = "cycle7-live-batch-0001"
    record = {
        "batch_id": batch_id,
        "payload_sha256": "a" * 64,
        "status": "REVIEW_REQUIRED",
        "revision": 1,
        "created_at": "2026-08-25T00:00:00Z",
        "updated_at": "2026-08-25T00:00:00Z",
    }
    try:
        draft_repo.delete_for_test(batch_id)
        created, duplicate = draft_repo.create(record)
        assert duplicate is False
        assert created == record
        record["revision"] = 2
        record["status"] = "READY_TO_PUBLISH"
        updated = draft_repo.compare_and_set(batch_id, 1, record)
        assert updated["revision"] == 2
        decision_record = deepcopy(updated)
        decision_record["revision"] = 3
        decision_record["updated_at"] = "2026-08-25T00:00:30Z"
        saved, replayed = draft_repo.save_decisions(
            batch_id,
            2,
            decision_record,
            request_id="cycle7-live-decision-0001",
            request_sha256="b" * 64,
            receipt={"resulting_revision": 3},
        )
        assert replayed is False
        same, replayed = draft_repo.save_decisions(
            batch_id,
            2,
            decision_record,
            request_id="cycle7-live-decision-0001",
            request_sha256="b" * 64,
            receipt={"resulting_revision": 3},
        )
        assert replayed is True and same == saved
        with pytest.raises(DraftIdempotencyConflict):
            draft_repo.save_decisions(
                batch_id,
                2,
                decision_record,
                request_id="cycle7-live-decision-0001",
                request_sha256="c" * 64,
                receipt={"resulting_revision": 3},
            )
        claimed, resumed = draft_repo.claim_publish(
            batch_id,
            3,
            claim_id="cycle7-live-claim-0001",
            target_graph_version="2.4.0-c5-gc.1-cycle7-live",
            claimed_at="2026-08-25T00:01:00Z",
            expires_at="2099-08-25T00:06:00Z",
            allow_takeover=False,
        )
        assert resumed is False
        blocked = deepcopy(claimed)
        blocked["revision"] += 1
        with pytest.raises(DraftPublishClaimConflict):
            draft_repo.save_decisions(
                batch_id,
                claimed["revision"],
                blocked,
                request_id="cycle7-live-decision-blocked",
                request_sha256="d" * 64,
                receipt={"resulting_revision": blocked["revision"]},
            )
        draft_repo.release_publish_claim(
            batch_id,
            "cycle7-live-claim-0001",
            updated_at="2026-08-25T00:02:00Z",
        )

        graph = json.loads(GRAPH_FILE.read_text(encoding="utf-8"))
        digest = __import__("hashlib").sha256(GRAPH_FILE.read_bytes()).hexdigest()
        release_repo.stage(graph, digest)
        first_version = graph["metadata"]["version"]
        release_repo.acquire_activation_lease(
            owner_id="cycle7-live-activation-0001",
            expected_current_version=first_version,
            target_graph_version=first_version,
            now="2026-08-25T00:03:00Z",
            expires_at="2099-08-25T00:08:00Z",
        )
        activation = release_repo.activate(
            first_version,
            expected_current_version=first_version,
            lease_owner="cycle7-live-activation-0001",
        )
        release_repo.release_activation_lease("cycle7-live-activation-0001")
        assert activation["graph_version"] == graph["metadata"]["version"]
        with release_repo._session() as session:
            counts = session.run(
                "MATCH (n:KnowledgeNode) WITH count(n) AS nodes "
                "MATCH (s:GraphSource) RETURN nodes, count(s) AS sources"
            ).single()
            assert counts["nodes"] == 150
            assert counts["sources"] == 147
            assert session.run(
                "MATCH (b:GraphDraftBatch:KnowledgeNode) RETURN count(b) AS leaked"
            ).single()["leaked"] == 0
        second = deepcopy(graph)
        second["metadata"]["version"] = "2.4.0-c5-gc.1-cycle7-live"
        second_digest = __import__("hashlib").sha256(
            json.dumps(second, ensure_ascii=False, sort_keys=True).encode("utf-8")
        ).hexdigest()
        release_repo.stage(second, second_digest)
        second_version = second["metadata"]["version"]
        release_repo.acquire_activation_lease(
            owner_id="cycle7-live-activation-0002",
            expected_current_version=first_version,
            target_graph_version=second_version,
            now="2026-08-25T00:04:00Z",
            expires_at="2099-08-25T00:09:00Z",
        )
        with pytest.raises(GraphActivationBusy):
            release_repo.acquire_activation_lease(
                owner_id="cycle7-live-activation-racer",
                expected_current_version=first_version,
                target_graph_version=second_version,
                now="2026-08-25T00:04:01Z",
                expires_at="2099-08-25T00:09:01Z",
            )
        switched = release_repo.activate(
            second_version,
            expected_current_version=first_version,
            lease_owner="cycle7-live-activation-0002",
        )
        release_repo.release_activation_lease("cycle7-live-activation-0002")
        assert switched["previous_graph_version"] == graph["metadata"]["version"]
        release_repo.acquire_activation_lease(
            owner_id="cycle7-live-activation-0003",
            expected_current_version=second_version,
            target_graph_version=first_version,
            now="2026-08-25T00:05:00Z",
            expires_at="2099-08-25T00:10:00Z",
        )
        rolled_back = release_repo.activate(
            first_version,
            expected_current_version=second_version,
            lease_owner="cycle7-live-activation-0003",
        )
        release_repo.release_activation_lease("cycle7-live-activation-0003")
        assert rolled_back["previous_graph_version"] == second["metadata"]["version"]
        receipt_result = {
            "previous_graph_version": second_version,
            "graph_version": first_version,
            "release_sha256": digest,
            "activated_at": rolled_back["activated_at"],
        }
        stored_receipt, replayed = release_repo.save_operation_receipt(
            "ROLLBACK",
            "cycle7-live-rollback-receipt",
            "e" * 64,
            receipt_result,
        )
        assert replayed is False and stored_receipt == receipt_result
        assert (
            release_repo.get_operation_receipt(
                "ROLLBACK", "cycle7-live-rollback-receipt", "e" * 64
            )
            == receipt_result
        )
        with pytest.raises(GraphOperationIdempotencyConflict):
            release_repo.get_operation_receipt(
                "ROLLBACK", "cycle7-live-rollback-receipt", "f" * 64
            )
        query_repo = Neo4jGraphRepository(
            uri,
            user,
            password,
            include_pending=False,
            query_timeout_seconds=5,
        )
        try:
            assert query_repo.version()["graph_version"] == graph["metadata"]["version"]
            rag = query_repo.rag_context("3.1.2", max_related=8)
            assert rag is not None
            assert rag["context_status"] == "READY"
            assert rag["knowledge_point"]["review_status"] == "APPROVED"
        finally:
            query_repo.close()
    finally:
        draft_repo.delete_for_test(batch_id)
        draft_repo.close()
        release_repo.close()
