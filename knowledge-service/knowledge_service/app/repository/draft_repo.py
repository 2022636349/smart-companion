from __future__ import annotations

import hashlib
import json
import os
import threading
import uuid
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol
from knowledge_service.app.repository.course_scope import ensure_course_schema, scoped_statement, valid_course_id


class DraftBatchNotFound(KeyError):
    pass


class DraftIdempotencyConflict(RuntimeError):
    pass


class DraftRevisionConflict(RuntimeError):
    pass


class DraftPublishClaimConflict(RuntimeError):
    pass


class DraftReceiptCapacityExceeded(RuntimeError):
    pass


MAX_DECISION_RECEIPTS = 2048


class GraphDraftRepository(Protocol):
    def create(self, record: dict[str, Any]) -> tuple[dict[str, Any], bool]: ...

    def get(self, batch_id: str) -> dict[str, Any] | None: ...

    def list(
        self,
        *,
        status: str | None,
        limit: int,
        cursor: str | None,
    ) -> tuple[list[dict[str, Any]], str | None]: ...

    def compare_and_set(
        self,
        batch_id: str,
        expected_revision: int,
        record: dict[str, Any],
    ) -> dict[str, Any]: ...

    def save_decisions(
        self,
        batch_id: str,
        expected_revision: int,
        record: dict[str, Any],
        *,
        request_id: str,
        request_sha256: str,
        receipt: dict[str, Any],
    ) -> tuple[dict[str, Any], bool]: ...

    def claim_publish(
        self,
        batch_id: str,
        expected_revision: int,
        *,
        claim_id: str,
        target_graph_version: str,
        claimed_at: str,
        expires_at: str,
        allow_takeover: bool,
    ) -> tuple[dict[str, Any], bool]: ...

    def finalize_publish(
        self,
        batch_id: str,
        claim_id: str,
        *,
        publish_result: dict[str, Any],
        updated_at: str,
    ) -> dict[str, Any]: ...

    def release_publish_claim(
        self,
        batch_id: str,
        claim_id: str,
        *,
        updated_at: str,
    ) -> dict[str, Any]: ...

    def close(self) -> None: ...


def _clone(value: dict[str, Any]) -> dict[str, Any]:
    return deepcopy(value)


def _expired(value: str | None, now: str) -> bool:
    if not value:
        return True
    try:
        expiry = datetime.fromisoformat(value.replace("Z", "+00:00"))
        current = datetime.fromisoformat(now.replace("Z", "+00:00"))
    except ValueError:
        return True
    if expiry.tzinfo is None:
        expiry = expiry.replace(tzinfo=timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    return expiry <= current


def _claim_record(
    current: dict[str, Any],
    expected_revision: int,
    *,
    claim_id: str,
    target_graph_version: str,
    claimed_at: str,
    expires_at: str,
    allow_takeover: bool,
) -> tuple[dict[str, Any], bool]:
    if current.get("status") == "PUBLISHED":
        if current.get("published_graph_version") == target_graph_version:
            return current, True
        raise DraftPublishClaimConflict("batch already published")
    claim = current.get("publish_claim")
    if claim:
        if claim.get("target_graph_version") != target_graph_version:
            raise DraftPublishClaimConflict("batch is claimed for another target")
        if claim.get("claim_id") == claim_id:
            if claim.get("expires_at") == expires_at:
                return current, True
            updated = _clone(current)
            updated["publish_claim"]["expires_at"] = expires_at
            updated["revision"] = int(current["revision"]) + 1
            updated["updated_at"] = claimed_at
            return updated, True
        if not allow_takeover and not _expired(claim.get("expires_at"), claimed_at):
            raise DraftPublishClaimConflict("batch publish claim is active")
        updated = _clone(current)
        updated["publish_claim"] = {
            "claim_id": claim_id,
            "target_graph_version": target_graph_version,
            "claimed_at": claimed_at,
            "expires_at": expires_at,
        }
        updated["revision"] = int(current["revision"]) + 1
        updated["updated_at"] = claimed_at
        return updated, True
    if int(current["revision"]) != int(expected_revision):
        raise DraftRevisionConflict(str(current.get("batch_id") or ""))
    if current.get("status") != "READY_TO_PUBLISH":
        raise DraftPublishClaimConflict("batch is not ready to publish")
    updated = _clone(current)
    updated["publish_claim"] = {
        "claim_id": claim_id,
        "target_graph_version": target_graph_version,
        "claimed_at": claimed_at,
        "expires_at": expires_at,
    }
    updated["revision"] = int(current["revision"]) + 1
    updated["updated_at"] = claimed_at
    return updated, False


def _finalized_record(
    current: dict[str, Any],
    claim_id: str,
    publish_result: dict[str, Any],
    updated_at: str,
) -> dict[str, Any]:
    if current.get("status") == "PUBLISHED":
        if current.get("publish_result") == publish_result:
            return current
        raise DraftPublishClaimConflict("published result differs")
    claim = current.get("publish_claim") or {}
    if claim.get("claim_id") != claim_id:
        raise DraftPublishClaimConflict("publish claim owner changed")
    if claim.get("target_graph_version") != publish_result.get("graph_version"):
        raise DraftPublishClaimConflict("publish claim target changed")
    updated = _clone(current)
    updated["status"] = "PUBLISHED"
    updated["published_graph_version"] = publish_result["graph_version"]
    updated["publish_result"] = _clone(publish_result)
    updated["publish_claim"] = None
    updated["revision"] = int(current["revision"]) + 1
    updated["updated_at"] = updated_at
    return updated


def _decision_saved_record(
    current: dict[str, Any],
    expected_revision: int,
    record: dict[str, Any],
    *,
    request_id: str,
    request_sha256: str,
    receipt: dict[str, Any],
) -> tuple[dict[str, Any], bool]:
    receipts = current.get("decision_receipts") or {}
    existing = receipts.get(request_id)
    if existing is not None:
        if existing.get("request_sha256") != request_sha256:
            raise DraftIdempotencyConflict("decision request_id payload changed")
        return current, True
    if len(receipts) >= MAX_DECISION_RECEIPTS:
        raise DraftReceiptCapacityExceeded(
            f"decision receipt capacity {MAX_DECISION_RECEIPTS} reached"
        )
    if current.get("publish_claim"):
        raise DraftPublishClaimConflict("batch publication is in progress")
    if int(current["revision"]) != int(expected_revision):
        raise DraftRevisionConflict(str(current.get("batch_id") or ""))
    if int(record["revision"]) != int(expected_revision) + 1:
        raise ValueError("decision record revision must increment exactly once")
    updated = _clone(record)
    updated_receipts = deepcopy(receipts)
    updated_receipts[request_id] = {
        "request_id": request_id,
        "request_sha256": request_sha256,
        **deepcopy(receipt),
    }
    updated["decision_receipts"] = updated_receipts
    return updated, False


class InMemoryGraphDraftRepository:
    """Isolated repository for unit tests and explicitly local development."""

    def __init__(self) -> None:
        self._records: dict[str, dict[str, Any]] = {}
        self._lock = threading.RLock()

    def create(self, record: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        batch_id = str(record["batch_id"])
        with self._lock:
            current = self._records.get(batch_id)
            if current is not None:
                if current["payload_sha256"] != record["payload_sha256"]:
                    raise DraftIdempotencyConflict(batch_id)
                return _clone(current), True
            self._records[batch_id] = _clone(record)
            return _clone(record), False

    def get(self, batch_id: str) -> dict[str, Any] | None:
        with self._lock:
            value = self._records.get(str(batch_id))
            return _clone(value) if value is not None else None

    def list(
        self,
        *,
        status: str | None,
        limit: int,
        cursor: str | None,
    ) -> tuple[list[dict[str, Any]], str | None]:
        with self._lock:
            ids = sorted(
                batch_id
                for batch_id, record in self._records.items()
                if (status is None or record["status"] == status)
                and (cursor is None or batch_id > cursor)
            )
            selected = ids[: limit + 1]
            has_more = len(selected) > limit
            selected = selected[:limit]
            items = [_clone(self._records[batch_id]) for batch_id in selected]
            next_cursor = selected[-1] if has_more and selected else None
            return items, next_cursor

    def compare_and_set(
        self,
        batch_id: str,
        expected_revision: int,
        record: dict[str, Any],
    ) -> dict[str, Any]:
        with self._lock:
            current = self._records.get(str(batch_id))
            if current is None:
                raise DraftBatchNotFound(batch_id)
            if int(current["revision"]) != int(expected_revision):
                raise DraftRevisionConflict(batch_id)
            self._records[str(batch_id)] = _clone(record)
            return _clone(record)

    def save_decisions(
        self,
        batch_id: str,
        expected_revision: int,
        record: dict[str, Any],
        *,
        request_id: str,
        request_sha256: str,
        receipt: dict[str, Any],
    ) -> tuple[dict[str, Any], bool]:
        with self._lock:
            current = self._records.get(str(batch_id))
            if current is None:
                raise DraftBatchNotFound(batch_id)
            updated, replayed = _decision_saved_record(
                current,
                expected_revision,
                record,
                request_id=request_id,
                request_sha256=request_sha256,
                receipt=receipt,
            )
            self._records[str(batch_id)] = _clone(updated)
            return _clone(updated), replayed

    def claim_publish(
        self,
        batch_id: str,
        expected_revision: int,
        *,
        claim_id: str,
        target_graph_version: str,
        claimed_at: str,
        expires_at: str,
        allow_takeover: bool,
    ) -> tuple[dict[str, Any], bool]:
        with self._lock:
            current = self._records.get(str(batch_id))
            if current is None:
                raise DraftBatchNotFound(batch_id)
            updated, resumed = _claim_record(
                current,
                expected_revision,
                claim_id=claim_id,
                target_graph_version=target_graph_version,
                claimed_at=claimed_at,
                expires_at=expires_at,
                allow_takeover=allow_takeover,
            )
            self._records[str(batch_id)] = _clone(updated)
            return _clone(updated), resumed

    def finalize_publish(
        self,
        batch_id: str,
        claim_id: str,
        *,
        publish_result: dict[str, Any],
        updated_at: str,
    ) -> dict[str, Any]:
        with self._lock:
            current = self._records.get(str(batch_id))
            if current is None:
                raise DraftBatchNotFound(batch_id)
            updated = _finalized_record(current, claim_id, publish_result, updated_at)
            self._records[str(batch_id)] = _clone(updated)
            return _clone(updated)

    def release_publish_claim(
        self,
        batch_id: str,
        claim_id: str,
        *,
        updated_at: str,
    ) -> dict[str, Any]:
        with self._lock:
            current = self._records.get(str(batch_id))
            if current is None:
                raise DraftBatchNotFound(batch_id)
            if current.get("status") == "PUBLISHED":
                return _clone(current)
            claim = current.get("publish_claim") or {}
            if claim.get("claim_id") != claim_id:
                raise DraftPublishClaimConflict("publish claim owner changed")
            updated = _clone(current)
            updated["publish_claim"] = None
            updated["revision"] = int(current["revision"]) + 1
            updated["updated_at"] = updated_at
            self._records[str(batch_id)] = _clone(updated)
            return _clone(updated)

    def close(self) -> None:
        return None


class FileGraphDraftRepository:
    """D-disk local repository; production deployments use Neo4j instead."""

    def __init__(self, root: str | Path) -> None:
        self.root = Path(root).resolve()
        self._lock = threading.RLock()

    def _ensure_root(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, batch_id: str) -> Path:
        digest = hashlib.sha256(str(batch_id).encode("utf-8")).hexdigest()
        return self.root / f"{digest}.json"

    @staticmethod
    def _read(path: Path) -> dict[str, Any]:
        return json.loads(path.read_text(encoding="utf-8"))

    def _atomic_write(self, path: Path, record: dict[str, Any]) -> None:
        temporary = self.root / f".{path.stem}-{uuid.uuid4().hex}.tmp"
        try:
            with temporary.open("x", encoding="utf-8", newline="\n") as handle:
                json.dump(record, handle, ensure_ascii=False, sort_keys=True)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
        finally:
            if temporary.exists():
                temporary.unlink()

    def create(self, record: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        batch_id = str(record["batch_id"])
        with self._lock:
            self._ensure_root()
            path = self._path(batch_id)
            if path.exists():
                current = self._read(path)
                if current.get("batch_id") != batch_id:
                    raise RuntimeError("draft repository hash collision")
                if current["payload_sha256"] != record["payload_sha256"]:
                    raise DraftIdempotencyConflict(batch_id)
                return current, True
            self._atomic_write(path, record)
            return _clone(record), False

    def get(self, batch_id: str) -> dict[str, Any] | None:
        with self._lock:
            path = self._path(str(batch_id))
            if not path.is_file():
                return None
            value = self._read(path)
            return value if value.get("batch_id") == str(batch_id) else None

    def list(
        self,
        *,
        status: str | None,
        limit: int,
        cursor: str | None,
    ) -> tuple[list[dict[str, Any]], str | None]:
        with self._lock:
            if not self.root.exists():
                return [], None
            values = []
            for path in self.root.glob("*.json"):
                try:
                    value = self._read(path)
                except (OSError, ValueError):
                    continue
                batch_id = str(value.get("batch_id") or "")
                if not batch_id or (status is not None and value.get("status") != status):
                    continue
                if cursor is not None and batch_id <= cursor:
                    continue
                values.append(value)
            values.sort(key=lambda item: str(item["batch_id"]))
            selected = values[: limit + 1]
            has_more = len(selected) > limit
            selected = selected[:limit]
            next_cursor = str(selected[-1]["batch_id"]) if has_more and selected else None
            return selected, next_cursor

    def compare_and_set(
        self,
        batch_id: str,
        expected_revision: int,
        record: dict[str, Any],
    ) -> dict[str, Any]:
        with self._lock:
            path = self._path(str(batch_id))
            if not path.is_file():
                raise DraftBatchNotFound(batch_id)
            current = self._read(path)
            if int(current["revision"]) != int(expected_revision):
                raise DraftRevisionConflict(batch_id)
            self._atomic_write(path, record)
            return _clone(record)

    def save_decisions(
        self,
        batch_id: str,
        expected_revision: int,
        record: dict[str, Any],
        *,
        request_id: str,
        request_sha256: str,
        receipt: dict[str, Any],
    ) -> tuple[dict[str, Any], bool]:
        with self._lock:
            path = self._path(str(batch_id))
            if not path.is_file():
                raise DraftBatchNotFound(batch_id)
            current = self._read(path)
            updated, replayed = _decision_saved_record(
                current,
                expected_revision,
                record,
                request_id=request_id,
                request_sha256=request_sha256,
                receipt=receipt,
            )
            self._atomic_write(path, updated)
            return _clone(updated), replayed

    def claim_publish(
        self,
        batch_id: str,
        expected_revision: int,
        *,
        claim_id: str,
        target_graph_version: str,
        claimed_at: str,
        expires_at: str,
        allow_takeover: bool,
    ) -> tuple[dict[str, Any], bool]:
        with self._lock:
            path = self._path(str(batch_id))
            if not path.is_file():
                raise DraftBatchNotFound(batch_id)
            current = self._read(path)
            updated, resumed = _claim_record(
                current,
                expected_revision,
                claim_id=claim_id,
                target_graph_version=target_graph_version,
                claimed_at=claimed_at,
                expires_at=expires_at,
                allow_takeover=allow_takeover,
            )
            self._atomic_write(path, updated)
            return _clone(updated), resumed

    def finalize_publish(
        self,
        batch_id: str,
        claim_id: str,
        *,
        publish_result: dict[str, Any],
        updated_at: str,
    ) -> dict[str, Any]:
        with self._lock:
            path = self._path(str(batch_id))
            if not path.is_file():
                raise DraftBatchNotFound(batch_id)
            current = self._read(path)
            updated = _finalized_record(current, claim_id, publish_result, updated_at)
            self._atomic_write(path, updated)
            return _clone(updated)

    def release_publish_claim(
        self,
        batch_id: str,
        claim_id: str,
        *,
        updated_at: str,
    ) -> dict[str, Any]:
        with self._lock:
            path = self._path(str(batch_id))
            if not path.is_file():
                raise DraftBatchNotFound(batch_id)
            current = self._read(path)
            if current.get("status") == "PUBLISHED":
                return current
            claim = current.get("publish_claim") or {}
            if claim.get("claim_id") != claim_id:
                raise DraftPublishClaimConflict("publish claim owner changed")
            updated = _clone(current)
            updated["publish_claim"] = None
            updated["revision"] = int(current["revision"]) + 1
            updated["updated_at"] = updated_at
            self._atomic_write(path, updated)
            return _clone(updated)

    def close(self) -> None:
        return None


class Neo4jGraphDraftRepository:
    """Production candidate store isolated under the GraphDraftBatch label.

    Candidate payloads are deliberately serialized on the batch node. They never
    receive KnowledgeNode or GraphSource labels, so the existing production query
    repository cannot match a PENDING draft even if its local pending switch is on.
    """

    def __init__(
        self,
        uri: str,
        user: str,
        password: str,
        *,
        database: str | None = None,
        query_timeout_seconds: float = 3.0,
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

    def _run(self, session, statement: str, **parameters: Any):
        statement = scoped_statement(statement)
        parameters["course_id"] = self.course_id
        if isinstance(session, self._transaction_type):
            return session.run(statement, **parameters)
        query = self._query_type(statement, timeout=self.query_timeout_seconds)
        return session.run(query, **parameters)

    def _ensure_schema(self) -> None:
        with self._session() as session:
            ensure_course_schema(session)

    @staticmethod
    def _record_json(record: dict[str, Any]) -> str:
        return json.dumps(
            record, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        )

    @staticmethod
    def _from_record(record: Any) -> dict[str, Any]:
        return json.loads(str(record["record_json"]))

    def create(self, record: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        creation_marker = uuid.uuid4().hex
        with self._session() as session:
            result = self._run(
                session,
                """
                MERGE (b:GraphDraftBatch {batch_id: $batch_id})
                ON CREATE SET b.payload_sha256 = $payload_sha256,
                              b.status = $status,
                              b.revision = $revision,
                              b.created_at = $created_at,
                              b.updated_at = $updated_at,
                              b.record_json = $record_json,
                              b.creation_marker = $creation_marker
                RETURN b.payload_sha256 AS payload_sha256,
                       b.record_json AS record_json,
                       b.creation_marker AS creation_marker
                """,
                batch_id=record["batch_id"],
                payload_sha256=record["payload_sha256"],
                status=record["status"],
                revision=record["revision"],
                created_at=record["created_at"],
                updated_at=record["updated_at"],
                record_json=self._record_json(record),
                creation_marker=creation_marker,
            ).single()
        if result is None:
            raise RuntimeError("Neo4j 未返回草稿批次")
        if str(result["payload_sha256"]) != str(record["payload_sha256"]):
            raise DraftIdempotencyConflict(str(record["batch_id"]))
        current = self._from_record(result)
        return current, str(result["creation_marker"]) != creation_marker

    def get(self, batch_id: str) -> dict[str, Any] | None:
        with self._session() as session:
            result = self._run(
                session,
                "MATCH (b:GraphDraftBatch {batch_id: $batch_id}) "
                "RETURN b.record_json AS record_json",
                batch_id=str(batch_id),
            ).single()
        return self._from_record(result) if result is not None else None

    def list(
        self,
        *,
        status: str | None,
        limit: int,
        cursor: str | None,
    ) -> tuple[list[dict[str, Any]], str | None]:
        with self._session() as session:
            records = list(
                self._run(
                    session,
                    """
                    MATCH (b:GraphDraftBatch)
                    WHERE ($status IS NULL OR b.status = $status)
                      AND ($cursor IS NULL OR b.batch_id > $cursor)
                    RETURN b.batch_id AS batch_id, b.record_json AS record_json
                    ORDER BY b.batch_id
                    LIMIT $fetch_limit
                    """,
                    status=status,
                    cursor=cursor,
                    fetch_limit=limit + 1,
                )
            )
        has_more = len(records) > limit
        records = records[:limit]
        items = [self._from_record(record) for record in records]
        next_cursor = str(records[-1]["batch_id"]) if has_more and records else None
        return items, next_cursor

    def compare_and_set(
        self,
        batch_id: str,
        expected_revision: int,
        record: dict[str, Any],
    ) -> dict[str, Any]:
        with self._session() as session:
            result = self._run(
                session,
                """
                MATCH (b:GraphDraftBatch {batch_id: $batch_id})
                WHERE b.revision = $expected_revision
                SET b.status = $status,
                    b.revision = $revision,
                    b.updated_at = $updated_at,
                    b.record_json = $record_json
                RETURN b.record_json AS record_json
                """,
                batch_id=str(batch_id),
                expected_revision=int(expected_revision),
                status=record["status"],
                revision=record["revision"],
                updated_at=record["updated_at"],
                record_json=self._record_json(record),
            ).single()
        if result is not None:
            return self._from_record(result)
        current = self.get(batch_id)
        if current is None:
            raise DraftBatchNotFound(batch_id)
        raise DraftRevisionConflict(batch_id)

    def _locked_current(self, transaction, batch_id: str) -> dict[str, Any]:
        locked = self._run(
            transaction,
            "MATCH (b:GraphDraftBatch {batch_id: $batch_id}) "
            "SET b.cas_nonce = $cas_nonce RETURN b.batch_id AS batch_id",
            batch_id=str(batch_id),
            cas_nonce=uuid.uuid4().hex,
        ).single()
        if locked is None:
            raise DraftBatchNotFound(batch_id)
        result = self._run(
            transaction,
            "MATCH (b:GraphDraftBatch {batch_id: $batch_id}) "
            "RETURN b.record_json AS record_json",
            batch_id=str(batch_id),
        ).single()
        if result is None:
            raise DraftBatchNotFound(batch_id)
        return self._from_record(result)

    def _write_locked(self, transaction, record: dict[str, Any]) -> None:
        self._run(
            transaction,
            """
            MATCH (b:GraphDraftBatch {batch_id: $batch_id})
            SET b.status = $status,
                b.revision = $revision,
                b.updated_at = $updated_at,
                b.record_json = $record_json
            """,
            batch_id=str(record["batch_id"]),
            status=record["status"],
            revision=int(record["revision"]),
            updated_at=record["updated_at"],
            record_json=self._record_json(record),
        ).consume()

    def save_decisions(
        self,
        batch_id: str,
        expected_revision: int,
        record: dict[str, Any],
        *,
        request_id: str,
        request_sha256: str,
        receipt: dict[str, Any],
    ) -> tuple[dict[str, Any], bool]:
        def save_transaction(transaction) -> tuple[dict[str, Any], bool]:
            current = self._locked_current(transaction, batch_id)
            updated, replayed = _decision_saved_record(
                current,
                expected_revision,
                record,
                request_id=request_id,
                request_sha256=request_sha256,
                receipt=receipt,
            )
            if updated is not current:
                self._write_locked(transaction, updated)
            return _clone(updated), replayed

        with self._session() as session:
            return session.execute_write(save_transaction)

    def claim_publish(
        self,
        batch_id: str,
        expected_revision: int,
        *,
        claim_id: str,
        target_graph_version: str,
        claimed_at: str,
        expires_at: str,
        allow_takeover: bool,
    ) -> tuple[dict[str, Any], bool]:
        def claim_transaction(transaction) -> tuple[dict[str, Any], bool]:
            current = self._locked_current(transaction, batch_id)
            updated, resumed = _claim_record(
                current,
                expected_revision,
                claim_id=claim_id,
                target_graph_version=target_graph_version,
                claimed_at=claimed_at,
                expires_at=expires_at,
                allow_takeover=allow_takeover,
            )
            if updated is not current:
                self._write_locked(transaction, updated)
            return _clone(updated), resumed

        with self._session() as session:
            return session.execute_write(claim_transaction)

    def finalize_publish(
        self,
        batch_id: str,
        claim_id: str,
        *,
        publish_result: dict[str, Any],
        updated_at: str,
    ) -> dict[str, Any]:
        def finalize_transaction(transaction) -> dict[str, Any]:
            current = self._locked_current(transaction, batch_id)
            updated = _finalized_record(current, claim_id, publish_result, updated_at)
            if updated is not current:
                self._write_locked(transaction, updated)
            return _clone(updated)

        with self._session() as session:
            return session.execute_write(finalize_transaction)

    def release_publish_claim(
        self,
        batch_id: str,
        claim_id: str,
        *,
        updated_at: str,
    ) -> dict[str, Any]:
        def release_transaction(transaction) -> dict[str, Any]:
            current = self._locked_current(transaction, batch_id)
            if current.get("status") == "PUBLISHED":
                return _clone(current)
            claim = current.get("publish_claim") or {}
            if claim.get("claim_id") != claim_id:
                raise DraftPublishClaimConflict("publish claim owner changed")
            updated = _clone(current)
            updated["publish_claim"] = None
            updated["revision"] = int(current["revision"]) + 1
            updated["updated_at"] = updated_at
            self._write_locked(transaction, updated)
            return _clone(updated)

        with self._session() as session:
            return session.execute_write(release_transaction)

    def delete_for_test(self, batch_id: str) -> None:
        with self._session() as session:
            self._run(
                session,
                "MATCH (b:GraphDraftBatch {batch_id: $batch_id}) DETACH DELETE b",
                batch_id=str(batch_id),
            ).consume()

    def close(self) -> None:
        self.driver.close()
