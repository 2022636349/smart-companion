from __future__ import annotations

import base64
import hashlib
import json
import os
import threading
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from jsonschema import Draft7Validator

from knowledge_service.app.domain.ingestion_dto import (
    BatchStatusFilter,
    CandidateTypeFilter,
    DecisionStatusFilter,
    GraphCandidateDecisionRequest,
    GraphDraftEdge,
    GraphDraftNode,
    GraphDraftSource,
    GraphIngestionBatchDetail,
    GraphIngestionBatchSummary,
    GraphIngestionBatchUpsertRequest,
    GraphPublishRequest,
    GraphRollbackRequest,
    GraphThresholdProfile,
    ReviewLaneFilter,
    candidate_payload,
)
from knowledge_service.app.repository.draft_repo import (
    DraftBatchNotFound,
    DraftIdempotencyConflict,
    DraftPublishClaimConflict,
    DraftReceiptCapacityExceeded,
    DraftRevisionConflict,
    FileGraphDraftRepository,
    GraphDraftRepository,
    InMemoryGraphDraftRepository,
    Neo4jGraphDraftRepository,
)
from knowledge_service.app.services.graph_service import GraphService
from knowledge_service.operations.ingestion_releases import (
    GraphActivationBusy,
    GraphActivationConflict,
    GraphOperationIdempotencyConflict,
    GraphReleaseConflict,
    GraphReleaseNotFound,
    ImmutableGraphReleaseStore,
    Neo4jGraphReleaseRepository,
    default_release_root,
)
from knowledge_service.operations.ingestion_validation import (
    MAX_BATCH_BYTES,
    canonical_payload_bytes,
    canonical_payload_sha256,
    candidate_review_lane,
    validate_draft_candidates,
)
from knowledge_service.operations.quality import quality_report
from knowledge_service.operations.root_reuse import reusable_course_root


class DraftPayloadHashMismatch(ValueError):
    pass


class DraftBudgetExceeded(ValueError):
    pass


class DraftValidationFailed(ValueError):
    def __init__(self, issues: list[dict[str, Any]]):
        super().__init__("graph draft validation failed")
        self.issues = issues


class DraftDecisionInvalid(ValueError):
    pass


class DraftNotPublishable(RuntimeError):
    pass


class DraftBaseVersionConflict(RuntimeError):
    pass


class GraphVersionConflict(RuntimeError):
    pass


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _request_sha256(request: Any, *, path_value: str | None = None) -> str:
    value: dict[str, Any] = request.model_dump(mode="json")
    if path_value is not None:
        value = {"path_value": path_value, "body": value}
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


class GraphIngestionService:
    def __init__(
        self,
        graph_service: GraphService,
        draft_repository: GraphDraftRepository,
        release_store: ImmutableGraphReleaseStore,
        neo4j_releases: Neo4jGraphReleaseRepository | None = None,
        *,
        publish_claim_ttl_seconds: int = 300,
    ) -> None:
        self.graph_service = graph_service
        self.course_id = graph_service.course_id
        self.draft_repository = draft_repository
        self.release_store = release_store
        self.neo4j_releases = neo4j_releases
        self.publish_claim_ttl_seconds = max(30, int(publish_claim_ttl_seconds))
        self._mutation_lock = threading.RLock()

    @classmethod
    def from_environment(cls, graph_service: GraphService) -> "GraphIngestionService":
        root = Path(os.getenv("GRAPH_INGESTION_ROOT", str(default_release_root())))
        if graph_service.course_id != 1:
            root = root / "courses" / str(graph_service.course_id)
        release_store = ImmutableGraphReleaseStore(root)
        draft_backend = os.getenv(
            "GRAPH_DRAFT_BACKEND",
            "neo4j" if graph_service.neo4j_repo is not None else "file",
        ).lower()
        if draft_backend == "memory":
            if os.getenv("GRAPH_ALLOW_IN_MEMORY_DRAFTS", "false").lower() != "true":
                raise RuntimeError("内存草稿仓库仅允许测试显式启用")
            draft_repository: GraphDraftRepository = InMemoryGraphDraftRepository()
        elif draft_backend == "file":
            draft_repository = FileGraphDraftRepository(root / "drafts")
        elif draft_backend == "neo4j":
            password = os.getenv("NEO4J_PASSWORD", "")
            if not password:
                raise RuntimeError("GRAPH_DRAFT_BACKEND=neo4j 时必须设置 NEO4J_PASSWORD")
            draft_repository = Neo4jGraphDraftRepository(
                os.getenv("NEO4J_URI", "bolt://localhost:7687"),
                os.getenv("NEO4J_USER", "neo4j"),
                password,
                course_id=graph_service.course_id,
                database=os.getenv("NEO4J_DATABASE") or None,
                query_timeout_seconds=float(
                    os.getenv("GRAPH_QUERY_TIMEOUT_SECONDS", "3")
                ),
            )
        else:
            raise ValueError(f"unsupported GRAPH_DRAFT_BACKEND: {draft_backend}")

        neo4j_releases = None
        if graph_service.neo4j_repo is not None:
            password = os.getenv("NEO4J_PASSWORD", "")
            neo4j_releases = Neo4jGraphReleaseRepository(
                os.getenv("NEO4J_URI", "bolt://localhost:7687"),
                os.getenv("NEO4J_USER", "neo4j"),
                password,
                course_id=graph_service.course_id,
                database=os.getenv("NEO4J_DATABASE") or None,
                query_timeout_seconds=float(
                    os.getenv("GRAPH_RELEASE_QUERY_TIMEOUT_SECONDS", "15")
                ),
            )
        service = cls(
            graph_service,
            draft_repository,
            release_store,
            neo4j_releases,
            publish_claim_ttl_seconds=int(
                os.getenv("GRAPH_PUBLISH_CLAIM_TTL_SECONDS", "300")
            ),
        )
        current = release_store.current()
        if current is not None:
            graph_service.activate_json_release(current["graph_file"])
        return service

    def close(self) -> None:
        self.draft_repository.close()
        if self.neo4j_releases is not None:
            self.neo4j_releases.close()

    def _base_graph(self) -> dict[str, Any]:
        graph = deepcopy(self.graph_service.json_repo.graph)
        json_version = str((graph.get("metadata") or {}).get("version") or "unknown")
        active_version = self._current_version()
        if json_version != active_version:
            raise DraftBaseVersionConflict(
                f"active={active_version}, immutable-json={json_version}"
            )
        return graph

    def _current_version(self) -> str:
        return str(self.graph_service.version().get("graph_version") or "unknown")

    def _durable_current_version(self) -> str:
        if self.neo4j_releases is not None:
            version = self.neo4j_releases.current_version()
            if version is not None:
                return version
        pointer = self.release_store.current()
        if pointer is not None:
            return str(pointer["graph_version"])
        return self._current_version()

    def _activation_coordinator(self):
        return self.neo4j_releases or self.release_store

    def _claim_window(self) -> tuple[str, str]:
        now_value = datetime.now(timezone.utc)
        expires = now_value + timedelta(seconds=self.publish_claim_ttl_seconds)
        return (
            now_value.isoformat().replace("+00:00", "Z"),
            expires.isoformat().replace("+00:00", "Z"),
        )

    def _best_effort_release_activation(self, owner_id: str) -> None:
        try:
            self._activation_coordinator().release_activation_lease(owner_id)
        except Exception:
            # A lease has a bounded expiry. Publication is already durable when
            # this cleanup runs, so cleanup failure must not turn success into 503.
            return

    @staticmethod
    def _counts(record: dict[str, Any]) -> dict[str, int]:
        candidates = [
            *record.get("nodes", []),
            *record.get("edges", []),
            *record.get("sources", []),
        ]
        issues = record.get("validation_issues", [])
        return {
            "candidate_count": len(candidates),
            "pending_count": sum(
                item.get("decision_status") == "PENDING" for item in candidates
            ),
            "approved_count": sum(
                item.get("decision_status") == "APPROVED" for item in candidates
            ),
            "rejected_count": sum(
                item.get("decision_status") == "REJECTED" for item in candidates
            ),
            "error_count": sum(item.get("severity") == "ERROR" for item in issues),
            "warning_count": sum(
                item.get("severity") == "WARNING" for item in issues
            ),
        }

    @classmethod
    def _summary(cls, record: dict[str, Any]) -> dict[str, Any]:
        return {
            "course_id": record.get("course_id", 1),
            "batch_id": record["batch_id"],
            "base_graph_version": record["base_graph_version"],
            "payload_sha256": record["payload_sha256"],
            "status": record["status"],
            "revision": record["revision"],
            **cls._counts(record),
            "created_at": record["created_at"],
            "updated_at": record["updated_at"],
            "published_graph_version": record.get("published_graph_version"),
        }

    @classmethod
    def _detail(cls, record: dict[str, Any]) -> dict[str, Any]:
        return {
            **cls._summary(record),
            "source": record["source"],
            "threshold_profile": record["threshold_profile"],
            "models": record["models"],
            "prompts": record["prompts"],
            "nodes": record.get("nodes", []),
            "edges": record.get("edges", []),
            "sources": record.get("sources", []),
            "validation_issues": record.get("validation_issues", []),
        }

    def _revalidate(self, record: dict[str, Any]) -> None:
        nodes = [
            item
            for item in record.get("nodes", [])
            if item.get("decision_status") != "REJECTED"
        ]
        edges = [
            item
            for item in record.get("edges", [])
            if item.get("decision_status") != "REJECTED"
        ]
        sources = [
            item
            for item in record.get("sources", [])
            if item.get("decision_status") != "REJECTED"
        ]
        record["payload_size"] = len(
            json.dumps(
                {
                    "base_graph_version": record["base_graph_version"],
                    "source": record["source"],
                    "threshold_profile": record["threshold_profile"],
                    "models": record["models"],
                    "prompts": record["prompts"],
                    "nodes": record.get("nodes", []),
                    "edges": record.get("edges", []),
                    "sources": record.get("sources", []),
                },
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        )
        issues = validate_draft_candidates(
            self._base_graph(),
            nodes,
            edges,
            sources,
            payload_size=record.get("payload_size"),
        )
        record["validation_issues"] = issues
        error_candidates = {
            (item["candidate_type"], item.get("candidate_id"))
            for item in issues
            if item["severity"] == "ERROR"
        }
        profile = record["threshold_profile"]
        profile_model = GraphThresholdProfile.model_validate(profile)
        for kind, model_type, key in (
            ("NODE", GraphDraftNode, "nodes"),
            ("EDGE", GraphDraftEdge, "edges"),
            ("SOURCE", GraphDraftSource, "sources"),
        ):
            for index, item in enumerate(record.get(key, [])):
                if item.get("decision_status") == "REJECTED":
                    continue
                model = model_type.model_validate(item)
                lane = candidate_review_lane(
                    model,
                    profile_model,
                    has_error=(kind, item.get("candidate_id")) in error_candidates,
                )
                record[key][index]["review_lane"] = lane
        counts = self._counts(record)
        if record.get("status") != "PUBLISHED":
            record["status"] = (
                "READY_TO_PUBLISH"
                if counts["pending_count"] == 0 and counts["error_count"] == 0
                else "REVIEW_REQUIRED"
            )

    def upsert(
        self, request: GraphIngestionBatchUpsertRequest
    ) -> tuple[dict[str, Any], bool]:
        digest = canonical_payload_sha256(request)
        if digest != request.payload_sha256:
            raise DraftPayloadHashMismatch(
                f"expected canonical payload_sha256={digest}"
            )
        payload_size = len(canonical_payload_bytes(request))
        if payload_size > MAX_BATCH_BYTES:
            raise DraftBudgetExceeded(
                f"canonical payload {payload_size} bytes exceeds {MAX_BATCH_BYTES} bytes"
            )
        existing = self.draft_repository.get(request.batch_id)
        if existing is not None:
            if existing["payload_sha256"] != request.payload_sha256:
                raise DraftIdempotencyConflict(request.batch_id)
            return self._detail(existing), True
        if request.base_graph_version != self._current_version():
            raise DraftBaseVersionConflict(request.base_graph_version)
        now = _utc_now()
        value = request.model_dump(mode="json")
        record = {
            "course_id": self.course_id,
            "batch_id": request.batch_id,
            "base_graph_version": request.base_graph_version,
            "payload_sha256": request.payload_sha256,
            "payload_size": payload_size,
            "source": value["source"],
            "threshold_profile": value["threshold_profile"],
            "models": value["models"],
            "prompts": value["prompts"],
            "nodes": value["nodes"],
            "edges": value["edges"],
            "sources": value["sources"],
            "validation_issues": [],
            "decision_receipts": {},
            "publish_claim": None,
            "status": "REVIEW_REQUIRED",
            "revision": 1,
            "created_at": now,
            "updated_at": now,
            "published_graph_version": None,
        }
        self._revalidate(record)
        stored, duplicate = self.draft_repository.create(record)
        return self._detail(stored), duplicate

    def get(self, batch_id: str) -> dict[str, Any]:
        record = self.draft_repository.get(batch_id)
        if record is None:
            raise DraftBatchNotFound(batch_id)
        return self._detail(record)

    def list(
        self,
        *,
        status: BatchStatusFilter | None,
        limit: int,
        cursor: str | None,
    ) -> dict[str, Any]:
        records, next_cursor = self.draft_repository.list(
            status=status, limit=limit, cursor=self._decode_course_cursor(cursor)
        )
        return {
            "items": [self._summary(record) for record in records],
            "next_cursor": self._encode_course_cursor(next_cursor) if next_cursor else None,
        }

    def _encode_course_cursor(self, value: Any) -> str:
        raw = json.dumps([self.course_id, value], separators=(",", ":")).encode("utf-8")
        return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")

    def _decode_course_cursor(self, cursor: str | None) -> Any:
        if cursor is None:
            return None
        try:
            raw = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4))
            value = json.loads(raw.decode("utf-8"))
            if not isinstance(value, list) or len(value) != 2 or value[0] != self.course_id:
                raise ValueError
            return value[1]
        except (ValueError, UnicodeError, json.JSONDecodeError) as exc:
            raise DraftDecisionInvalid("invalid cursor or course scope") from exc

    def _encode_candidate_cursor(self, kind: str, candidate_id: str) -> str:
        return self._encode_course_cursor([kind, candidate_id])

    def _decode_candidate_cursor(self, cursor: str | None) -> tuple[str, str] | None:
        value = self._decode_course_cursor(cursor)
        if value is None:
            return None
        try:
            if (
                not isinstance(value, list)
                or len(value) != 2
                or value[0] not in {"NODE", "EDGE", "SOURCE"}
            ):
                raise ValueError
            return str(value[0]), str(value[1])
        except (ValueError, UnicodeError, json.JSONDecodeError) as exc:
            raise DraftDecisionInvalid("invalid candidate cursor") from exc

    def candidates(
        self,
        batch_id: str,
        *,
        candidate_type: CandidateTypeFilter | None,
        decision_status: DecisionStatusFilter | None,
        review_lane: ReviewLaneFilter | None,
        limit: int,
        cursor: str | None,
    ) -> dict[str, Any]:
        record = self.draft_repository.get(batch_id)
        if record is None:
            raise DraftBatchNotFound(batch_id)
        rows: list[tuple[str, str, dict[str, Any]]] = []
        for kind, key, payload_key in (
            ("NODE", "nodes", "node"),
            ("EDGE", "edges", "edge"),
            ("SOURCE", "sources", "source"),
        ):
            for item in record.get(key, []):
                if candidate_type is not None and kind != candidate_type:
                    continue
                if (
                    decision_status is not None
                    and item.get("decision_status") != decision_status
                ):
                    continue
                if review_lane is not None and item.get("review_lane") != review_lane:
                    continue
                rows.append(
                    (
                        kind,
                        str(item["candidate_id"]),
                        {
                            "candidate_type": kind,
                            "candidate_id": item["candidate_id"],
                            "decision_status": item["decision_status"],
                            "review_lane": item["review_lane"],
                            payload_key: item,
                        },
                    )
                )
        rows.sort(key=lambda value: ({"NODE": 0, "EDGE": 1, "SOURCE": 2}[value[0]], value[1]))
        decoded_cursor = self._decode_candidate_cursor(cursor)
        if decoded_cursor is not None:
            cursor_key = ({"NODE": 0, "EDGE": 1, "SOURCE": 2}[decoded_cursor[0]], decoded_cursor[1])
            rows = [
                row
                for row in rows
                if ({"NODE": 0, "EDGE": 1, "SOURCE": 2}[row[0]], row[1])
                > cursor_key
            ]
        selected = rows[: limit + 1]
        has_more = len(selected) > limit
        selected = selected[:limit]
        next_cursor = (
            self._encode_candidate_cursor(selected[-1][0], selected[-1][1])
            if has_more and selected
            else None
        )
        return {"items": [item[2] for item in selected], "next_cursor": next_cursor}

    def decide(
        self,
        batch_id: str,
        request: GraphCandidateDecisionRequest,
    ) -> dict[str, Any]:
        with self._mutation_lock:
            request_digest = _request_sha256(request)
            record = self.draft_repository.get(batch_id)
            if record is None:
                raise DraftBatchNotFound(batch_id)
            existing_receipt = (record.get("decision_receipts") or {}).get(
                request.request_id
            )
            if existing_receipt is not None:
                if existing_receipt.get("request_sha256") != request_digest:
                    raise DraftIdempotencyConflict(
                        "decision request_id payload changed"
                    )
                return self._detail(record)
            if record.get("status") == "PUBLISHED":
                raise DraftDecisionInvalid("published batch is immutable")
            if int(record["revision"]) != request.expected_revision:
                raise DraftRevisionConflict(batch_id)
            now = _utc_now()
            indexes: dict[tuple[str, str], tuple[str, int]] = {}
            for kind, key in (("NODE", "nodes"), ("EDGE", "edges"), ("SOURCE", "sources")):
                for index, item in enumerate(record.get(key, [])):
                    indexes[(kind, str(item["candidate_id"]))] = (key, index)
            for decision in request.decisions:
                location = indexes.get((decision.candidate_type, decision.candidate_id))
                if location is None:
                    raise DraftDecisionInvalid(
                        f"candidate not found: {decision.candidate_type}/{decision.candidate_id}"
                    )
                key, index = location
                edited = candidate_payload(decision)
                item = (
                    edited.model_dump(mode="json")
                    if edited is not None
                    else dict(record[key][index])
                )
                item["review_status"] = "PENDING"
                item["decision_status"] = (
                    "APPROVED" if decision.decision == "APPROVE" else "REJECTED"
                )
                item["decision_comment"] = decision.comment
                item["decided_by"] = request.reviewer_id
                item["decided_at"] = now
                record[key][index] = item
            record["revision"] = int(record["revision"]) + 1
            record["updated_at"] = now
            self._revalidate(record)
            result_summary = self._summary(record)
            saved, _ = self.draft_repository.save_decisions(
                batch_id,
                request.expected_revision,
                record,
                request_id=request.request_id,
                request_sha256=request_digest,
                receipt={
                    "resulting_revision": int(record["revision"]),
                    "result_summary": result_summary,
                    "committed_at": now,
                },
            )
            return self._detail(saved)

    @staticmethod
    def _published_node(item: dict[str, Any], published_at: str) -> dict[str, Any]:
        keep = {
            "id",
            "name",
            "level",
            "node_type",
            "parent_id",
            "description",
            "aliases",
            "keywords",
            "difficulty",
            "importance",
            "estimated_minutes",
            "learning_objectives",
            "common_misconceptions",
            "example_code",
            "rights_status",
        }
        result = {key: deepcopy(value) for key, value in item.items() if key in keep}
        result.update(
            {
                "review_status": "APPROVED",
                "content_version": 1,
                "reviewed_by": item.get("decided_by"),
                "reviewed_at": published_at,
            }
        )
        return result

    @staticmethod
    def _release_content_timestamp(record: dict[str, Any]) -> str:
        """Stable timestamp so a retried immutable version has identical bytes."""
        decided_at = [
            str(item["decided_at"])
            for item in [
                *record.get("nodes", []),
                *record.get("edges", []),
                *record.get("sources", []),
            ]
            if item.get("decision_status") == "APPROVED" and item.get("decided_at")
        ]
        return max(decided_at) if decided_at else str(record["created_at"])

    @staticmethod
    def _published_edge(item: dict[str, Any]) -> dict[str, Any]:
        return {
            "source": item["source_id"],
            "target": item["target_id"],
            "relation": item["relation"],
            "review_status": "APPROVED",
            "evidence": deepcopy(item["evidence"]),
            "confidence": min(
                float(item["extractor_confidence"]),
                float(item["reviewer_confidence"]),
            ),
        }

    @staticmethod
    def _published_source(item: dict[str, Any]) -> dict[str, Any]:
        keep = {
            "id",
            "node_id",
            "source_type",
            "title",
            "resource_id",
            "file_id",
            "chapter",
            "page_from",
            "page_to",
            "locator",
            "excerpt",
            "rights_status",
        }
        result = {key: deepcopy(value) for key, value in item.items() if key in keep}
        result["review_status"] = "APPROVED"
        result["confidence"] = min(
            float(item["extractor_confidence"]),
            float(item["reviewer_confidence"]),
        )
        return result

    def _build_release(
        self,
        record: dict[str, Any],
        target_version: str,
        published_at: str,
    ) -> dict[str, Any]:
        rights_blocked: list[tuple[str, dict[str, Any]]] = [
            ("NODE", item)
            for item in record.get("nodes", [])
            if item.get("decision_status") == "APPROVED"
            and item.get("rights_status") != "ALLOWED"
        ]
        rights_blocked.extend(
            ("SOURCE", item)
            for item in record.get("sources", [])
            if item.get("decision_status") != "REJECTED"
            and item.get("rights_status") != "ALLOWED"
        )
        if rights_blocked:
            raise DraftValidationFailed(
                [
                    {
                        "code": "GRAPH_RIGHTS_NOT_ALLOWED",
                        "severity": "ERROR",
                        "candidate_type": candidate_type,
                        "candidate_id": item.get("candidate_id"),
                        "message": (
                            f"候选{'节点' if candidate_type == 'NODE' else '来源'}"
                            "版权状态不是 ALLOWED，禁止进入正式发布物"
                        ),
                    }
                    for candidate_type, item in rights_blocked
                ]
            )
        graph = self._base_graph()
        if int(record.get('course_id', self.course_id)) != self.course_id:
            raise DraftBaseVersionConflict('draft belongs to another course')
        incoming_roots = [item for item in record.get('nodes', [])
                          if item.get('node_type') == 'course' and item.get('decision_status') != 'REJECTED']
        if incoming_roots and (len(incoming_roots) != 1
                               or incoming_roots[0].get('decision_status') != 'APPROVED'):
            raise DraftValidationFailed([{
                'code': 'GRAPH_COURSE_ROOT_REQUIRED', 'severity': 'ERROR',
                'candidate_type': 'BATCH', 'candidate_id': None,
                'message': '教材批次必须只有一个已审核课程根节点',
            }])
        approved_nodes = [
            self._published_node(item, published_at)
            for item in record.get("nodes", [])
            if item.get("decision_status") == "APPROVED"
        ]
        approved_edges = [
            self._published_edge(item)
            for item in record.get("edges", [])
            if item.get("decision_status") == "APPROVED"
        ]
        approved_sources = [
            self._published_source(item)
            for item in record.get("sources", [])
            if item.get("decision_status") == "APPROVED"
        ]
        if int((graph.get('metadata') or {}).get('course_id', 1)) != self.course_id:
            raise DraftBaseVersionConflict('base graph belongs to another course')
        root_reuse = reusable_course_root(graph, approved_nodes)
        if root_reuse is not None:
            incoming_root, canonical_root = root_reuse
            incoming_id, canonical_id = incoming_root['id'], canonical_root['id']
            approved_nodes = [node for node in approved_nodes if node is not incoming_root]
            for node in approved_nodes:
                if node.get('parent_id') == incoming_id:
                    node['parent_id'] = canonical_id
            for source in approved_sources:
                if source.get('node_id') == incoming_id:
                    source['node_id'] = canonical_id
            for edge in approved_edges:
                for key in ('source', 'target'):
                    if edge.get(key) == incoming_id:
                        edge[key] = canonical_id
            # Snapshot metadata is extensible. Preserve raw draft identities,
            # hashes and review receipts; record only final-release projection.
            metadata = graph.setdefault('metadata', {})
            metadata.setdefault('course_root_reuse', []).append({
                'batch_id': record['batch_id'], 'candidate_root_id': incoming_id,
                'canonical_root_id': canonical_id,
            })
        graph.setdefault("nodes", []).extend(approved_nodes)
        graph.setdefault("edges", []).extend(approved_edges)
        graph.setdefault("source_sections", []).extend(approved_sources)
        metadata = dict(graph.get("metadata") or {})
        metadata.update(
            {
                "version": target_version,
                "node_count": len(graph["nodes"]),
                "edge_count": len(graph["edges"]),
                "approved_node_count": sum(
                    item.get("review_status") == "APPROVED" for item in graph["nodes"]
                ),
                "approved_edge_count": sum(
                    item.get("review_status") == "APPROVED" for item in graph["edges"]
                ),
                "source_count": len(graph.get("source_sections", [])),
                "published_batch": record["batch_id"],
                "updated_at": published_at,
            }
        )
        if self.course_id != 1:
            metadata["course_id"] = self.course_id
            course_roots = [node for node in graph["nodes"] if node.get("node_type") == "course"]
            if len(course_roots) != 1:
                raise DraftValidationFailed([{
                    "code": "GRAPH_COURSE_ROOT_REQUIRED", "severity": "ERROR",
                    "candidate_type": "BATCH", "candidate_id": None,
                    "message": "课程图谱必须恰好包含一个经人工审核的课程根节点",
                }])
            metadata["name"] = course_roots[0]["name"]
        graph["metadata"] = metadata
        schema_path = Path(__file__).resolve().parents[3] / "knowledge_graph.schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        errors = sorted(Draft7Validator(schema).iter_errors(graph), key=lambda error: list(error.path))
        if errors:
            issues = [
                {
                    "code": "GRAPH_DRAFT_SCHEMA_INVALID",
                    "severity": "ERROR",
                    "candidate_type": "BATCH",
                    "candidate_id": None,
                    "message": f"{'/'.join(map(str, error.path))}: {error.message}"[:1000],
                }
                for error in errors[:20]
            ]
            raise DraftValidationFailed(issues)
        return graph

    def publish(self, batch_id: str, request: GraphPublishRequest) -> dict[str, Any]:
        with self._mutation_lock:
            activation_owner = (
                f"publish:{batch_id}:{request.request_id}"
            )
            record = self.draft_repository.get(batch_id)
            if record is None:
                raise DraftBatchNotFound(batch_id)
            if record.get("status") == "PUBLISHED":
                if record.get("published_graph_version") == request.target_graph_version:
                    self._best_effort_release_activation(activation_owner)
                    return {**record["publish_result"], "course_id": self.course_id}
                raise GraphVersionConflict(request.target_graph_version)
            current_version = self._durable_current_version()
            if current_version not in {
                record["base_graph_version"],
                request.target_graph_version,
            }:
                raise DraftBaseVersionConflict(record["base_graph_version"])
            if request.target_graph_version == record["base_graph_version"]:
                raise GraphVersionConflict(request.target_graph_version)
            activated_recovery = current_version == request.target_graph_version
            if activated_recovery and not record.get("publish_claim"):
                raise GraphVersionConflict(request.target_graph_version)
            if not activated_recovery:
                if not record.get('publish_claim') and int(record['revision']) != request.expected_revision:
                    raise DraftRevisionConflict(batch_id)
                self._revalidate(record)
            counts = self._counts(record)
            if counts["pending_count"] or counts["error_count"]:
                raise DraftNotPublishable(batch_id)
            if not activated_recovery:
                # Validate the exact merged release before a claim increments
                # revision. Content failures must not look like lost CAS races.
                preview = self._build_release(record, request.target_graph_version,
                                              self._release_content_timestamp(record))
                report = quality_report(preview)
                if not report['passed']:
                    raise DraftValidationFailed([{
                        'code': 'GRAPH_DRAFT_SCHEMA_INVALID', 'severity': 'ERROR',
                        'candidate_type': 'BATCH', 'candidate_id': None,
                        'message': '发布质量校验未通过: ' + ', '.join(
                            name for name, passed in report['checks'].items() if not passed),
                    }])
            claimed_at, claim_expires_at = self._claim_window()
            claimed, _ = self.draft_repository.claim_publish(
                batch_id,
                request.expected_revision,
                claim_id=request.request_id,
                target_graph_version=request.target_graph_version,
                claimed_at=claimed_at,
                expires_at=claim_expires_at,
                allow_takeover=current_version == request.target_graph_version,
            )
            if claimed.get("status") == "PUBLISHED":
                self._best_effort_release_activation(activation_owner)
                return {**claimed["publish_result"], "course_id": self.course_id}
            if not activated_recovery:
                self._revalidate(claimed)
            counts = self._counts(claimed)
            if counts["pending_count"] or counts["error_count"]:
                self.draft_repository.release_publish_claim(
                    batch_id, request.request_id, updated_at=_utc_now()
                )
                raise DraftNotPublishable(batch_id)

            claim = claimed.get("publish_claim") or {}
            published_at = str(claim.get("claimed_at") or claimed_at)
            release_content_timestamp = self._release_content_timestamp(claimed)
            activation_acquired = False
            try:
                durable_version = self._durable_current_version()
                if durable_version == claimed["base_graph_version"]:
                    base_graph = self._base_graph()
                    target_graph = self._build_release(
                        claimed,
                        request.target_graph_version,
                        release_content_timestamp,
                    )
                    base_release = self.release_store.publish(base_graph)
                    target_release = self.release_store.publish(target_graph)
                    if self.neo4j_releases is not None:
                        self.neo4j_releases.stage(base_graph, base_release["sha256"])
                        self.neo4j_releases.stage(
                            target_graph, target_release["sha256"]
                        )
                elif durable_version == request.target_graph_version:
                    target_release = self.release_store.get(
                        request.target_graph_version
                    )
                else:
                    raise DraftBaseVersionConflict(claimed["base_graph_version"])

                # Refresh the claim after potentially expensive validation/staging.
                lease_now, lease_expires_at = self._claim_window()
                claimed, _ = self.draft_repository.claim_publish(
                    batch_id,
                    int(claimed["revision"]),
                    claim_id=request.request_id,
                    target_graph_version=request.target_graph_version,
                    claimed_at=lease_now,
                    expires_at=lease_expires_at,
                    allow_takeover=durable_version == request.target_graph_version,
                )
                durable_version = self._durable_current_version()
                if durable_version not in {
                    claimed["base_graph_version"],
                    request.target_graph_version,
                }:
                    raise DraftBaseVersionConflict(claimed["base_graph_version"])
                coordinator = self._activation_coordinator()
                coordinator.acquire_activation_lease(
                    owner_id=activation_owner,
                    expected_current_version=durable_version,
                    target_graph_version=request.target_graph_version,
                    now=lease_now,
                    expires_at=lease_expires_at,
                    allow_target_recovery=True,
                )
                activation_acquired = True

                durable_version = self._durable_current_version()
                if durable_version == claimed["base_graph_version"]:
                    if self.neo4j_releases is not None:
                        self.neo4j_releases.activate(
                            request.target_graph_version,
                            expected_current_version=claimed["base_graph_version"],
                            lease_owner=activation_owner,
                        )
                elif durable_version != request.target_graph_version:
                    raise DraftBaseVersionConflict(claimed["base_graph_version"])

                pointer = self.release_store.activate(
                    request.target_graph_version,
                    expected_current_version=claimed["base_graph_version"],
                    lease_owner=(
                        activation_owner if self.neo4j_releases is None else None
                    ),
                )
                self.graph_service.activate_json_release(pointer["graph_file"])
                result = {
                    "batch_id": batch_id,
                    "course_id": self.course_id,
                    "previous_graph_version": claimed["base_graph_version"],
                    "graph_version": request.target_graph_version,
                    "release_sha256": target_release["sha256"],
                    "published_at": published_at,
                }
                self.draft_repository.finalize_publish(
                    batch_id,
                    request.request_id,
                    publish_result=result,
                    updated_at=_utc_now(),
                )
            except Exception:
                try:
                    activated = (
                        self._durable_current_version()
                        == request.target_graph_version
                    )
                except Exception:
                    activated = True
                if not activated:
                    if activation_acquired:
                        self._best_effort_release_activation(activation_owner)
                    try:
                        self.draft_repository.release_publish_claim(
                            batch_id, request.request_id, updated_at=_utc_now()
                        )
                    except (DraftPublishClaimConflict, DraftBatchNotFound):
                        pass
                # If activation already committed, retain both claims. A retry
                # with the same request_id observes the target and only finalizes.
                raise
            self._best_effort_release_activation(activation_owner)
            return {**result, "course_id": self.course_id}

    def rollback(
        self, version: str, request: GraphRollbackRequest
    ) -> dict[str, Any]:
        with self._mutation_lock:
            target_version = str(version)
            activation_owner = f"rollback:{target_version}:{request.request_id}"
            request_digest = _request_sha256(request, path_value=target_version)
            coordinator = self._activation_coordinator()
            receipt = coordinator.get_operation_receipt(
                "ROLLBACK", request.request_id, request_digest
            )
            if receipt is not None:
                self._best_effort_release_activation(activation_owner)
                return {**receipt, "course_id": self.course_id}
            current_version = self._durable_current_version()
            release = self.release_store.get(str(version))
            if current_version not in {
                request.expected_current_version,
                target_version,
            }:
                raise DraftBaseVersionConflict(request.expected_current_version)
            lease_now, lease_expires_at = self._claim_window()
            activation_acquired = False
            try:
                coordinator.acquire_activation_lease(
                    owner_id=activation_owner,
                    expected_current_version=request.expected_current_version,
                    target_graph_version=target_version,
                    now=lease_now,
                    expires_at=lease_expires_at,
                )
                activation_acquired = True
                durable_version = self._durable_current_version()
                if durable_version == request.expected_current_version:
                    if self.neo4j_releases is not None:
                        self.neo4j_releases.activate(
                            target_version,
                            expected_current_version=request.expected_current_version,
                            lease_owner=activation_owner,
                        )
                elif durable_version != target_version:
                    raise DraftBaseVersionConflict(request.expected_current_version)
                pointer = self.release_store.activate(
                    target_version,
                    expected_current_version=request.expected_current_version,
                    lease_owner=(
                        activation_owner if self.neo4j_releases is None else None
                    ),
                )
                self.graph_service.activate_json_release(pointer["graph_file"])
                result = {
                    "previous_graph_version": request.expected_current_version,
                    "graph_version": target_version,
                    "release_sha256": release["sha256"],
                    "activated_at": pointer["switched_at"],
                }
                result, _ = coordinator.save_operation_receipt(
                    "ROLLBACK",
                    request.request_id,
                    request_digest,
                    result,
                )
            except Exception:
                try:
                    activated = self._durable_current_version() == target_version
                except Exception:
                    activated = True
                if not activated and activation_acquired:
                    self._best_effort_release_activation(activation_owner)
                # A committed target is intentionally not compensated back: the
                # retained lease lets the same request finish its mirror update.
                raise
            self._best_effort_release_activation(activation_owner)
            return {**result, "course_id": self.course_id}


__all__ = [
    "DraftBaseVersionConflict",
    "DraftBatchNotFound",
    "DraftBudgetExceeded",
    "DraftDecisionInvalid",
    "DraftIdempotencyConflict",
    "DraftNotPublishable",
    "DraftPayloadHashMismatch",
    "DraftPublishClaimConflict",
    "DraftReceiptCapacityExceeded",
    "DraftRevisionConflict",
    "DraftValidationFailed",
    "GraphIngestionService",
    "GraphActivationBusy",
    "GraphActivationConflict",
    "GraphOperationIdempotencyConflict",
    "GraphReleaseConflict",
    "GraphReleaseNotFound",
    "GraphVersionConflict",
]
