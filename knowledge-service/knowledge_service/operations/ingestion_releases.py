from __future__ import annotations

import hashlib
import json
import os
import threading
import time
import uuid
from contextlib import contextmanager
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from knowledge_service.app.domain.graph_dto import node_type_for_level, normalize_source
from knowledge_service.operations.quality import sha256_file
from knowledge_service.app.repository.course_scope import ensure_course_schema, scoped_statement, valid_course_id
from knowledge_service.operations.releases import (
    D_WORKSPACE_ROOT,
    VERSION_PATTERN,
    create_snapshot,
    require_d_workspace_path,
    verify_snapshot,
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class GraphReleaseConflict(RuntimeError):
    pass


class GraphReleaseNotFound(KeyError):
    pass


class GraphActivationBusy(RuntimeError):
    pass


class GraphActivationConflict(RuntimeError):
    pass


class GraphOperationIdempotencyConflict(RuntimeError):
    pass


class GraphOperationReceiptInvalid(RuntimeError):
    pass


MAX_OPERATION_RECEIPT_BYTES = 4096


def _operation_result_json(result: dict[str, Any]) -> str:
    required = {
        "previous_graph_version",
        "graph_version",
        "release_sha256",
        "activated_at",
    }
    if set(result) != required or any(
        not isinstance(result[key], str) for key in required
    ):
        raise GraphOperationReceiptInvalid(
            "operation receipt result must contain only rollback scalar fields"
        )
    payload = json.dumps(
        result, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )
    if len(payload.encode("utf-8")) > MAX_OPERATION_RECEIPT_BYTES:
        raise GraphOperationReceiptInvalid(
            f"operation receipt exceeds {MAX_OPERATION_RECEIPT_BYTES} bytes"
        )
    return payload


def _is_expired(value: str | None, now: str) -> bool:
    if not value:
        return True
    try:
        expiry = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        current = datetime.fromisoformat(str(now).replace("Z", "+00:00"))
    except ValueError:
        return True
    if expiry.tzinfo is None:
        expiry = expiry.replace(tzinfo=timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    return expiry <= current


class ImmutableGraphReleaseStore:
    """Creates and activates verified immutable graph JSON releases on D:."""

    def __init__(self, root: str | Path) -> None:
        self.root = require_d_workspace_path(root)
        self.releases_root = require_d_workspace_path(self.root / "releases")
        self.state_root = require_d_workspace_path(self.root / "state")
        self._state_lock = threading.RLock()

    @contextmanager
    def _exclusive_state_guard(self):
        """Small cross-process mutex for the explicitly local file backend."""
        self.state_root.mkdir(parents=True, exist_ok=True)
        guard = self.state_root / ".activation-state.lock"
        deadline = time.monotonic() + 2.0
        descriptor: int | None = None
        while descriptor is None:
            try:
                descriptor = os.open(
                    guard,
                    os.O_CREAT | os.O_EXCL | os.O_WRONLY,
                    0o600,
                )
                os.write(descriptor, f"{os.getpid()}\n".encode("ascii"))
            except FileExistsError as exc:
                try:
                    stale = time.time() - guard.stat().st_mtime > 30.0
                except FileNotFoundError:
                    continue
                if stale:
                    try:
                        guard.unlink()
                    except FileNotFoundError:
                        pass
                    continue
                if time.monotonic() >= deadline:
                    raise GraphActivationBusy("file activation state is busy") from exc
                time.sleep(0.01)
        try:
            yield
        finally:
            os.close(descriptor)
            try:
                guard.unlink()
            except FileNotFoundError:
                pass

    @staticmethod
    def _atomic_json(path: Path, value: dict[str, Any]) -> None:
        temporary = path.parent / f".{path.stem}-{uuid.uuid4().hex}.tmp"
        try:
            with temporary.open("x", encoding="utf-8", newline="\n") as handle:
                json.dump(value, handle, ensure_ascii=False, indent=2, sort_keys=True)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
        finally:
            if temporary.exists():
                temporary.unlink()

    @staticmethod
    def _canonical_graph_bytes(graph: dict[str, Any]) -> bytes:
        return (
            json.dumps(
                graph,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
            + "\n"
        ).encode("utf-8")

    def publish(self, graph: dict[str, Any]) -> dict[str, Any]:
        version = str((graph.get("metadata") or {}).get("version") or "")
        if not VERSION_PATTERN.fullmatch(version):
            raise ValueError("target graph version is missing or unsafe")
        self.root.mkdir(parents=True, exist_ok=True)
        self.releases_root.mkdir(parents=True, exist_ok=True)
        temporary = require_d_workspace_path(
            self.root / f".graph-{version}-{uuid.uuid4().hex}.json"
        )
        payload = self._canonical_graph_bytes(graph)
        expected_digest = hashlib.sha256(payload).hexdigest()
        try:
            with temporary.open("xb") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            target = self.releases_root / version
            if target.exists():
                verified = verify_snapshot(target)
                actual_digest = verified["manifest"]["files"]["graph.json"]["sha256"]
                if actual_digest != expected_digest:
                    raise GraphReleaseConflict(version)
            else:
                target = create_snapshot(temporary, backup_root=self.releases_root)
                verified = verify_snapshot(target)
            return {
                "version": version,
                "sha256": verified["manifest"]["files"]["graph.json"]["sha256"],
                "graph_file": str((Path(target) / "graph.json").resolve()),
                "snapshot_dir": str(Path(target).resolve()),
            }
        finally:
            if temporary.exists():
                temporary.unlink()

    def get(self, version: str) -> dict[str, Any]:
        if not VERSION_PATTERN.fullmatch(str(version)):
            raise GraphReleaseNotFound(version)
        target = self.releases_root / str(version)
        if not target.is_dir():
            raise GraphReleaseNotFound(version)
        verified = verify_snapshot(target)
        return {
            "version": str(version),
            "sha256": verified["manifest"]["files"]["graph.json"]["sha256"],
            "graph_file": str((target / "graph.json").resolve()),
            "snapshot_dir": str(target.resolve()),
        }

    def acquire_activation_lease(
        self,
        *,
        owner_id: str,
        expected_current_version: str,
        target_graph_version: str,
        now: str,
        expires_at: str,
        allow_target_recovery: bool = False,
    ) -> dict[str, Any]:
        self.get(target_graph_version)
        with self._state_lock, self._exclusive_state_guard():
            lease_path = self.state_root / "activation-lease.json"
            lease = (
                json.loads(lease_path.read_text(encoding="utf-8"))
                if lease_path.is_file()
                else None
            )
            pointer_path = self.state_root / "active-release.json"
            pointer = (
                json.loads(pointer_path.read_text(encoding="utf-8"))
                if pointer_path.is_file()
                else None
            )
            current = str(pointer.get("graph_version")) if pointer else None
            if lease and lease.get("owner_id") == owner_id:
                if lease.get("target_graph_version") != target_graph_version:
                    raise GraphActivationConflict("activation owner target changed")
                if current not in {None, expected_current_version, target_graph_version}:
                    raise GraphActivationConflict("active graph version changed")
                lease["expires_at"] = expires_at
                lease["updated_at"] = now
                self._atomic_json(lease_path, lease)
                return {**lease, "current_graph_version": current, "resumed": True}
            if (
                lease
                and not _is_expired(lease.get("expires_at"), now)
                and not (
                    allow_target_recovery and current == target_graph_version
                )
            ):
                raise GraphActivationBusy("another graph activation is in progress")
            allowed_current = {expected_current_version}
            if allow_target_recovery:
                allowed_current.add(target_graph_version)
            if current is not None and current not in allowed_current:
                raise GraphActivationConflict("active graph version changed")
            value = {
                "owner_id": owner_id,
                "expected_current_version": expected_current_version,
                "target_graph_version": target_graph_version,
                "acquired_at": now,
                "updated_at": now,
                "expires_at": expires_at,
            }
            self._atomic_json(lease_path, value)
            return {**value, "current_graph_version": current, "resumed": False}

    def _operation_receipt_path(self, operation: str, request_id: str) -> Path:
        operation_key = str(operation).upper()
        if operation_key not in {"ROLLBACK"}:
            raise ValueError("unsupported graph operation receipt")
        digest = hashlib.sha256(str(request_id).encode("utf-8")).hexdigest()
        root = require_d_workspace_path(self.state_root / "operation-receipts")
        return root / f"{operation_key.lower()}-{digest}.json"

    def get_operation_receipt(
        self,
        operation: str,
        request_id: str,
        request_sha256: str,
    ) -> dict[str, Any] | None:
        with self._state_lock, self._exclusive_state_guard():
            path = self._operation_receipt_path(operation, request_id)
            if not path.is_file():
                return None
            receipt = json.loads(path.read_text(encoding="utf-8"))
            if receipt.get("request_id") != request_id:
                raise GraphOperationIdempotencyConflict("receipt identity changed")
            if receipt.get("request_sha256") != request_sha256:
                raise GraphOperationIdempotencyConflict(
                    "operation request_id payload changed"
                )
            result = deepcopy(receipt["result"])
            _operation_result_json(result)
            return result

    def save_operation_receipt(
        self,
        operation: str,
        request_id: str,
        request_sha256: str,
        result: dict[str, Any],
    ) -> tuple[dict[str, Any], bool]:
        _operation_result_json(result)
        with self._state_lock, self._exclusive_state_guard():
            path = self._operation_receipt_path(operation, request_id)
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.is_file():
                receipt = json.loads(path.read_text(encoding="utf-8"))
                if receipt.get("request_sha256") != request_sha256:
                    raise GraphOperationIdempotencyConflict(
                        "operation request_id payload changed"
                    )
                return deepcopy(receipt["result"]), True
            receipt = {
                "operation": str(operation).upper(),
                "request_id": request_id,
                "request_sha256": request_sha256,
                "result": deepcopy(result),
                "created_at": utc_now(),
            }
            self._atomic_json(path, receipt)
            return deepcopy(result), False

    def release_activation_lease(self, owner_id: str) -> None:
        with self._state_lock, self._exclusive_state_guard():
            lease_path = self.state_root / "activation-lease.json"
            if not lease_path.is_file():
                return
            lease = json.loads(lease_path.read_text(encoding="utf-8"))
            if lease.get("owner_id") != owner_id:
                raise GraphActivationConflict("activation lease owner changed")
            lease_path.unlink()

    def activate(
        self,
        version: str,
        *,
        expected_current_version: str | None = None,
        lease_owner: str | None = None,
    ) -> dict[str, Any]:
        release = self.get(version)
        self.state_root.mkdir(parents=True, exist_ok=True)
        with self._state_lock, self._exclusive_state_guard():
            pointer = self.state_root / "active-release.json"
            current = (
                json.loads(pointer.read_text(encoding="utf-8"))
                if pointer.is_file()
                else None
            )
            if lease_owner is not None:
                lease_path = self.state_root / "activation-lease.json"
                if not lease_path.is_file():
                    raise GraphActivationConflict("activation lease is missing")
                lease = json.loads(lease_path.read_text(encoding="utf-8"))
                now = utc_now()
                if lease.get("owner_id") != lease_owner:
                    raise GraphActivationConflict("activation lease owner changed")
                if lease.get("target_graph_version") != str(version):
                    raise GraphActivationConflict("activation lease target changed")
                if _is_expired(lease.get("expires_at"), now):
                    raise GraphActivationBusy("activation lease expired")
            current_version = str(current.get("graph_version")) if current else None
            if current_version == str(version):
                return current
            if (
                expected_current_version is not None
                and current_version is not None
                and current_version != expected_current_version
            ):
                raise GraphActivationConflict("active graph version changed")
            value = {
                "graph_version": release["version"],
                "graph_sha256": release["sha256"],
                "verified_snapshot": release["snapshot_dir"],
                "graph_file": release["graph_file"],
                "switched_at": utc_now(),
            }
            self._atomic_json(pointer, value)
            return value

    def current(self) -> dict[str, Any] | None:
        pointer = self.state_root / "active-release.json"
        if not pointer.is_file():
            return None
        value = json.loads(pointer.read_text(encoding="utf-8"))
        release = self.get(str(value.get("graph_version") or ""))
        if release["sha256"] != value.get("graph_sha256"):
            raise GraphReleaseConflict(str(value.get("graph_version") or ""))
        return value


class Neo4jGraphReleaseRepository:
    """Stages full immutable releases and atomically switches active labels.

    Staged nodes use GraphReleaseNode/GraphReleaseSource labels only. Production
    reads still match KnowledgeNode/GraphSource, so a staged release is invisible
    until one transaction moves those active labels to the selected version.
    """

    def __init__(
        self,
        uri: str,
        user: str,
        password: str,
        *,
        database: str | None = None,
        query_timeout_seconds: float = 10.0,
        course_id: int = 1,
    ) -> None:
        try:
            from neo4j import GraphDatabase, ManagedTransaction, Query
        except ImportError as exc:
            raise RuntimeError("未安装 neo4j Python 驱动") from exc
        self.course_id = valid_course_id(course_id)
        self.driver = GraphDatabase.driver(uri, auth=(user, password))
        self.database = database
        self.query_timeout_seconds = max(0.1, float(query_timeout_seconds))
        self._query_type = Query
        self._transaction_type = ManagedTransaction
        self._ensure_schema()

    def _session(self):
        return self.driver.session(database=self.database) if self.database else self.driver.session()

    def _run(self, target, statement: str, **parameters: Any):
        statement = scoped_statement(statement)
        parameters["course_id"] = self.course_id
        if isinstance(target, self._transaction_type):
            return target.run(statement, **parameters)
        query = self._query_type(statement, timeout=self.query_timeout_seconds)
        return target.run(query, **parameters)

    def _ensure_schema(self) -> None:
        with self._session() as session:
            ensure_course_schema(session)

    def _node_rows(self, graph: dict[str, Any], version: str) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        for source in graph.get("nodes", []):
            row = dict(source)
            row["id"] = str(row["id"])
            row["level"] = int(row.get("level", 3))
            row["node_type"] = row.get("node_type") or node_type_for_level(row["level"])
            row["review_status"] = row.get("review_status") or "PENDING"
            row["content_version"] = int(row.get("content_version") or 1)
            row["release_version"] = version
            row["release_key"] = f"{version}\x1f{row['id']}"
            row["course_id"] = self.course_id
            rows.append(row)
        return rows

    def _edge_rows(self, graph: dict[str, Any], version: str) -> list[dict[str, Any]]:
        rows = []
        for edge in graph.get("edges", []):
            relation = str(edge.get("relation") or "REQUIRES").upper()
            if relation not in {"REQUIRES", "RELATED", "SUPPORTED_BY"}:
                continue
            rows.append(
                {
                    "source_key": f"{version}\x1f{edge['source']}",
                    "target_key": f"{version}\x1f{edge['target']}",
                    "relation": relation,
                    "review_status": edge.get("review_status") or "PENDING",
                    "evidence": edge.get("evidence") or [],
                    "confidence": edge.get("confidence"),
                    "course_id": self.course_id,
                }
            )
        return rows

    def _source_rows(self, graph: dict[str, Any], version: str) -> list[dict[str, Any]]:
        rows = []
        for source in graph.get("source_sections", graph.get("sources", [])):
            row = normalize_source(source)
            row["release_version"] = version
            row["release_key"] = f"{version}\x1f{row['id']}"
            row["node_release_key"] = f"{version}\x1f{row['node_id']}"
            row["course_id"] = self.course_id
            rows.append(row)
        return rows

    def stage(self, graph: dict[str, Any], graph_sha256: str) -> None:
        metadata = dict(graph.get("metadata") or {})
        metadata["course_id"] = self.course_id
        version = str(metadata.get("version") or "")
        if not VERSION_PATTERN.fullmatch(version):
            raise ValueError("release graph version is unsafe")
        nodes = self._node_rows(graph, version)
        edges = self._edge_rows(graph, version)
        sources = self._source_rows(graph, version)
        metadata_json = json.dumps(metadata, ensure_ascii=False, sort_keys=True)

        def stage_transaction(transaction) -> None:
            existing = self._run(
                transaction,
                "MATCH (r:GraphRelease {version: $version}) "
                "RETURN r.graph_sha256 AS graph_sha256, r.staged AS staged",
                version=version,
            ).single()
            if existing is not None:
                if str(existing["graph_sha256"]) != graph_sha256:
                    raise GraphReleaseConflict(version)
                if bool(existing["staged"]):
                    return
            self._run(
                transaction,
                """
                MERGE (r:GraphRelease {version: $version})
                SET r.graph_sha256 = $graph_sha256,
                    r.metadata_json = $metadata_json,
                    r.immutable = true,
                    r.staged = false,
                    r.created_at = coalesce(r.created_at, $created_at)
                """,
                version=version,
                graph_sha256=graph_sha256,
                metadata_json=metadata_json,
                created_at=utc_now(),
            ).consume()
            self._run(
                transaction,
                """
                UNWIND $rows AS row
                MERGE (n:GraphReleaseNode {release_key: row.release_key})
                SET n += row
                """,
                rows=nodes,
            ).consume()
            for relation in ("REQUIRES", "RELATED", "SUPPORTED_BY"):
                relation_rows = [row for row in edges if row["relation"] == relation]
                if not relation_rows:
                    continue
                self._run(
                    transaction,
                    f"""
                    UNWIND $rows AS row
                    MATCH (source:GraphReleaseNode {{release_key: row.source_key}})
                    MATCH (target:GraphReleaseNode {{release_key: row.target_key}})
                    MERGE (source)-[rel:{relation}]->(target)
                    SET rel.review_status = row.review_status,
                        rel.evidence = row.evidence,
                        rel.confidence = row.confidence,
                        rel.course_id = $course_id,
                        rel.release_version = $version
                    """,
                    rows=relation_rows,
                    version=version,
                ).consume()
            hierarchy_rows = [
                {
                    "parent_key": f"{version}\x1f{node['parent_id']}",
                    "child_key": node["release_key"],
                    "review_status": node["review_status"],
                }
                for node in nodes
                if node.get("parent_id") is not None
            ]
            if hierarchy_rows:
                self._run(
                    transaction,
                    """
                    UNWIND $rows AS row
                    MATCH (parent:GraphReleaseNode {release_key: row.parent_key})
                    MATCH (child:GraphReleaseNode {release_key: row.child_key})
                    MERGE (parent)-[rel:CONTAINS]->(child)
                    SET rel.review_status = row.review_status,
                        rel.course_id = $course_id,
                        rel.release_version = $version
                    """,
                    rows=hierarchy_rows,
                    version=version,
                ).consume()
            if sources:
                self._run(
                    transaction,
                    """
                    UNWIND $rows AS row
                    MERGE (source:GraphReleaseSource {release_key: row.release_key})
                    SET source += row
                    WITH source, row
                    MATCH (node:GraphReleaseNode {release_key: row.node_release_key})
                    MERGE (node)-[rel:SUPPORTED_BY]->(source)
                    SET rel.review_status = row.review_status,
                        rel.course_id = $course_id,
                        rel.release_version = $version
                    """,
                    rows=sources,
                    version=version,
                ).consume()
            counts = self._run(
                transaction,
                """
                MATCH (r:GraphRelease {version: $version})
                OPTIONAL MATCH (n:GraphReleaseNode {release_version: $version})
                WITH r, count(n) AS node_count
                OPTIONAL MATCH (s:GraphReleaseSource {release_version: $version})
                RETURN r, node_count, count(s) AS source_count
                """,
                version=version,
            ).single()
            if counts is None or int(counts["node_count"]) != len(nodes):
                raise RuntimeError("staged Neo4j release node count mismatch")
            if int(counts["source_count"]) != len(sources):
                raise RuntimeError("staged Neo4j release source count mismatch")
            self._run(
                transaction,
                "MATCH (r:GraphRelease {version: $version}) "
                "SET r.staged = true, r.node_count = $node_count, "
                "r.edge_count = $edge_count, r.source_count = $source_count",
                version=version,
                node_count=len(nodes),
                edge_count=len(edges),
                source_count=len(sources),
            ).consume()

        with self._session() as session:
            session.execute_write(stage_transaction)

    def current_version(self) -> str | None:
        with self._session() as session:
            result = self._run(
                session,
                "MATCH (m:GraphMetadata {key: 'current'}) RETURN m.version AS version",
            ).single()
        if result is None or result["version"] is None:
            return None
        return str(result["version"])

    def acquire_activation_lease(
        self,
        *,
        owner_id: str,
        expected_current_version: str,
        target_graph_version: str,
        now: str,
        expires_at: str,
        allow_target_recovery: bool = False,
    ) -> dict[str, Any]:
        def acquire_transaction(transaction) -> dict[str, Any]:
            release = self._run(
                transaction,
                "MATCH (r:GraphRelease {version: $version, staged: true}) "
                "RETURN r.version AS version",
                version=str(target_graph_version),
            ).single()
            if release is None:
                raise GraphReleaseNotFound(target_graph_version)
            lease = self._run(
                transaction,
                """
                MERGE (l:GraphActivationLease {key: 'current'})
                SET l.lock_nonce = $lock_nonce
                RETURN l.owner_id AS owner_id,
                       l.target_graph_version AS target_graph_version,
                       l.expires_at AS expires_at
                """,
                lock_nonce=uuid.uuid4().hex,
            ).single()
            metadata = self._run(
                transaction,
                "MATCH (m:GraphMetadata {key: 'current'}) RETURN m.version AS version",
            ).single()
            current = (
                str(metadata["version"])
                if metadata is not None and metadata["version"] is not None
                else None
            )
            existing_owner = str(lease["owner_id"]) if lease["owner_id"] else None
            existing_target = (
                str(lease["target_graph_version"])
                if lease["target_graph_version"]
                else None
            )
            if existing_owner == owner_id:
                if existing_target != target_graph_version:
                    raise GraphActivationConflict("activation owner target changed")
                if current not in {expected_current_version, target_graph_version}:
                    raise GraphActivationConflict("active graph version changed")
                self._run(
                    transaction,
                    "MATCH (l:GraphActivationLease {key: 'current'}) "
                    "SET l.expires_at = $expires_at, l.updated_at = $now",
                    expires_at=expires_at,
                    now=now,
                ).consume()
                return {
                    "owner_id": owner_id,
                    "target_graph_version": target_graph_version,
                    "current_graph_version": current,
                    "expires_at": expires_at,
                    "resumed": True,
                }
            if (
                existing_owner
                and not _is_expired(lease["expires_at"], now)
                and not (
                    allow_target_recovery and current == target_graph_version
                )
            ):
                raise GraphActivationBusy("another graph activation is in progress")
            allowed_current = {expected_current_version}
            if allow_target_recovery:
                allowed_current.add(target_graph_version)
            if current not in allowed_current:
                raise GraphActivationConflict("active graph version changed")
            self._run(
                transaction,
                """
                MATCH (l:GraphActivationLease {key: 'current'})
                SET l.owner_id = $owner_id,
                    l.expected_current_version = $expected_current_version,
                    l.target_graph_version = $target_graph_version,
                    l.acquired_at = $now,
                    l.updated_at = $now,
                    l.expires_at = $expires_at
                """,
                owner_id=owner_id,
                expected_current_version=expected_current_version,
                target_graph_version=target_graph_version,
                now=now,
                expires_at=expires_at,
            ).consume()
            return {
                "owner_id": owner_id,
                "target_graph_version": target_graph_version,
                "current_graph_version": current,
                "expires_at": expires_at,
                "resumed": False,
            }

        with self._session() as session:
            return session.execute_write(acquire_transaction)

    @staticmethod
    def _receipt_key(operation: str, request_id: str) -> str:
        operation_key = str(operation).upper()
        if operation_key not in {"ROLLBACK"}:
            raise ValueError("unsupported graph operation receipt")
        return f"{operation_key}\x1f{request_id}"

    def get_operation_receipt(
        self,
        operation: str,
        request_id: str,
        request_sha256: str,
    ) -> dict[str, Any] | None:
        receipt_key = self._receipt_key(operation, request_id)
        with self._session() as session:
            record = self._run(
                session,
                "MATCH (r:GraphOperationReceipt {receipt_key: $receipt_key}) "
                "RETURN r.request_sha256 AS request_sha256, "
                "r.result_json AS result_json",
                receipt_key=receipt_key,
            ).single()
        if record is None:
            return None
        if str(record["request_sha256"]) != request_sha256:
            raise GraphOperationIdempotencyConflict(
                "operation request_id payload changed"
            )
        result = json.loads(str(record["result_json"]))
        _operation_result_json(result)
        return result

    def save_operation_receipt(
        self,
        operation: str,
        request_id: str,
        request_sha256: str,
        result: dict[str, Any],
    ) -> tuple[dict[str, Any], bool]:
        receipt_key = self._receipt_key(operation, request_id)
        marker = uuid.uuid4().hex
        result_json = _operation_result_json(result)
        with self._session() as session:
            record = self._run(
                session,
                """
                MERGE (r:GraphOperationReceipt {receipt_key: $receipt_key})
                ON CREATE SET r.operation = $operation,
                              r.request_id = $request_id,
                              r.request_sha256 = $request_sha256,
                              r.result_json = $result_json,
                              r.created_at = $created_at,
                              r.creation_marker = $creation_marker
                RETURN r.request_sha256 AS request_sha256,
                       r.result_json AS result_json,
                       r.creation_marker AS creation_marker
                """,
                receipt_key=receipt_key,
                operation=str(operation).upper(),
                request_id=request_id,
                request_sha256=request_sha256,
                result_json=result_json,
                created_at=utc_now(),
                creation_marker=marker,
            ).single()
        if record is None:
            raise RuntimeError("Neo4j did not return operation receipt")
        if str(record["request_sha256"]) != request_sha256:
            raise GraphOperationIdempotencyConflict(
                "operation request_id payload changed"
            )
        return (
            json.loads(str(record["result_json"])),
            str(record["creation_marker"]) != marker,
        )

    def release_activation_lease(self, owner_id: str) -> None:
        def release_transaction(transaction) -> None:
            lease = self._run(
                transaction,
                "MATCH (l:GraphActivationLease {key: 'current'}) "
                "SET l.lock_nonce = $lock_nonce RETURN l.owner_id AS owner_id",
                lock_nonce=uuid.uuid4().hex,
            ).single()
            if lease is None or lease["owner_id"] is None:
                return
            if str(lease["owner_id"]) != owner_id:
                raise GraphActivationConflict("activation lease owner changed")
            self._run(
                transaction,
                """
                MATCH (l:GraphActivationLease {key: 'current'})
                SET l.owner_id = null,
                    l.expected_current_version = null,
                    l.target_graph_version = null,
                    l.expires_at = null,
                    l.updated_at = $now
                """,
                now=utc_now(),
            ).consume()

        with self._session() as session:
            session.execute_write(release_transaction)

    def activate(
        self,
        version: str,
        *,
        expected_current_version: str | None = None,
        lease_owner: str | None = None,
    ) -> dict[str, Any]:
        def activate_transaction(transaction) -> dict[str, Any]:
            release = self._run(
                transaction,
                "MATCH (r:GraphRelease {version: $version, staged: true}) "
                "RETURN r.graph_sha256 AS graph_sha256, "
                "r.metadata_json AS metadata_json",
                version=str(version),
            ).single()
            if release is None:
                raise GraphReleaseNotFound(version)
            if lease_owner is not None:
                lease = self._run(
                    transaction,
                    "MATCH (l:GraphActivationLease {key: 'current'}) "
                    "SET l.lock_nonce = $lock_nonce "
                    "RETURN l.owner_id AS owner_id, "
                    "l.target_graph_version AS target_graph_version, "
                    "l.expires_at AS expires_at",
                    lock_nonce=uuid.uuid4().hex,
                ).single()
                if lease is None or str(lease["owner_id"] or "") != lease_owner:
                    raise GraphActivationConflict("activation lease owner changed")
                if str(lease["target_graph_version"] or "") != str(version):
                    raise GraphActivationConflict("activation lease target changed")
                if _is_expired(lease["expires_at"], utc_now()):
                    raise GraphActivationBusy("activation lease expired")
            previous = self._run(
                transaction,
                "MERGE (m:GraphMetadata {key: 'current'}) "
                "SET m.activation_nonce = $activation_nonce "
                "RETURN m.version AS version",
                activation_nonce=uuid.uuid4().hex,
            ).single()
            previous_version = (
                str(previous["version"])
                if previous is not None and previous["version"] is not None
                else "unknown"
            )
            if (
                expected_current_version is not None
                and previous_version != str(version)
                and previous_version != expected_current_version
            ):
                raise GraphActivationConflict("active graph version changed")
            metadata = json.loads(str(release["metadata_json"]))
            metadata["key"] = "current"
            metadata["course_id"] = self.course_id
            switched_at = utc_now()
            self._run(
                transaction,
                "MATCH (n:KnowledgeNode) REMOVE n:KnowledgeNode SET n:InactiveKnowledgeNode",
            ).consume()
            self._run(
                transaction,
                "MATCH (s:GraphSource) REMOVE s:GraphSource SET s:InactiveGraphSource",
            ).consume()
            self._run(
                transaction,
                "MATCH (n:GraphReleaseNode {release_version: $version}) "
                "SET n:KnowledgeNode REMOVE n:InactiveKnowledgeNode",
                version=str(version),
            ).consume()
            self._run(
                transaction,
                "MATCH (s:GraphReleaseSource {release_version: $version}) "
                "SET s:GraphSource REMOVE s:InactiveGraphSource",
                version=str(version),
            ).consume()
            self._run(
                transaction,
                "MERGE (m:GraphMetadata {key: 'current'}) SET m += $metadata",
                metadata=metadata,
            ).consume()
            self._run(
                transaction,
                "MATCH (r:GraphRelease) SET r.active = (r.version = $version), "
                "r.activated_at = CASE WHEN r.version = $version THEN $activated_at "
                "ELSE r.activated_at END",
                version=str(version),
                activated_at=switched_at,
            ).consume()
            if lease_owner is not None:
                self._run(
                    transaction,
                    """
                    MATCH (l:GraphActivationLease {key: 'current'})
                    SET l.previous_graph_version = $previous_graph_version,
                        l.activated_at = $activated_at
                    """,
                    previous_graph_version=previous_version,
                    activated_at=switched_at,
                ).consume()
            return {
                "previous_graph_version": previous_version,
                "graph_version": str(version),
                "release_sha256": str(release["graph_sha256"]),
                "activated_at": switched_at,
            }

        with self._session() as session:
            return session.execute_write(activate_transaction)

    def close(self) -> None:
        self.driver.close()


def default_release_root() -> Path:
    return D_WORKSPACE_ROOT / "graph-ingestion"


def graph_file_sha256(path: str | Path) -> str:
    return sha256_file(Path(path))
