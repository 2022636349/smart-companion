from __future__ import annotations

import json
import math
import os
import shutil
import threading
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import chromadb
from chromadb.config import Settings
from chromadb.api.shared_system_client import SharedSystemClient

from app.contracts.memory import (
    DeleteMode,
    MemorySearchHit,
    MemorySearchRequest,
    MemoryUpsertRequest,
)


COLLECTION_NAME = "learning_memory"
TERMINAL_VERSION = 9_223_372_036_854_775_807
INDEX_STATE_FORMAT = 3


class MemoryStoreError(RuntimeError):
    code = "MEMORY_STORE_ERROR"


class MemoryStoreUnavailableError(MemoryStoreError):
    code = "MEMORY_STORE_UNAVAILABLE"


class MemoryStoreConflictError(MemoryStoreError):
    code = "MEMORY_STALE_VERSION"


@dataclass(frozen=True)
class MemoryIndexState:
    epoch: str
    rebuild_required: bool


class LearningMemoryStore:
    """Persistent, rebuildable Chroma index for learning memory only."""

    def __init__(
        self,
        persist_dir: str,
        *,
        embedding_provider: str,
        embedding_model: str,
        collection_name: str = COLLECTION_NAME,
    ) -> None:
        if collection_name != COLLECTION_NAME:
            raise ValueError("Only the learning_memory Chroma collection is allowed.")
        normalized_provider = embedding_provider.strip().lower()
        normalized_model = embedding_model.strip()
        if not normalized_provider or len(normalized_provider) > 100:
            raise ValueError("embedding_provider must be a bounded non-empty value.")
        if not normalized_model or len(normalized_model) > 200:
            raise ValueError("embedding_model must be a bounded non-empty value.")
        self._path = Path(persist_dir)
        self._state_path = self._path.with_name(
            f"{self._path.name}.learning-memory-index-state.json"
        )
        self._embedding_provider = normalized_provider
        self._embedding_model = normalized_model
        self._startup_rebuild_required = False
        self._lock = threading.RLock()
        self._client: Any = None
        self._collection: Any = None
        self._state = self._load_or_initialize_state()
        self._open(
            recover_corruption=True,
            force_rebuild=self._startup_rebuild_required,
        )

    @property
    def collection_name(self) -> str:
        return COLLECTION_NAME

    def heartbeat(self) -> int:
        with self._lock:
            try:
                return int(self._client.heartbeat())
            except Exception as exc:
                raise MemoryStoreUnavailableError("The memory index is unavailable.") from exc

    def close(self) -> None:
        with self._lock:
            self._release_client()

    def upsert(
        self, request: MemoryUpsertRequest, embedding: list[float]
    ) -> tuple[bool, MemoryIndexState]:
        with self._lock:
            try:
                return self._upsert(request, embedding), self._state
            except MemoryStoreConflictError:
                raise
            except Exception:
                # A corrupted derivative index is quarantined and recreated empty;
                # Spring can replay ACTIVE MySQL rows through the same endpoint.
                self._open(recover_corruption=True, force_rebuild=True)
                try:
                    return self._upsert(request, embedding), self._state
                except MemoryStoreConflictError:
                    raise
                except Exception as exc:
                    raise MemoryStoreUnavailableError(
                        "The memory index could not be rebuilt."
                    ) from exc

    def _upsert(self, request: MemoryUpsertRequest, embedding: list[float]) -> bool:
        current = self._collection.get(ids=[str(request.memory_id)], include=["metadatas"])
        metadatas = current.get("metadatas") or []
        if metadatas:
            existing = metadatas[0] or {}
            existing_course = int(existing.get("course_id", -1))
            if existing_course > 0 and existing_course != request.course_id:
                raise MemoryStoreConflictError("memory_id belongs to another course.")
            existing_status = str(existing.get("status") or "")
            existing_generation = int(existing.get("generation", 0))
            if existing_status == "DELETED":
                raise MemoryStoreConflictError("A terminal memory index cannot be revived.")
            if request.generation < existing_generation:
                raise MemoryStoreConflictError("An older sync generation cannot replace the index.")
            existing_version = int(existing.get("version", 0))
            if request.version < existing_version:
                raise MemoryStoreConflictError("An older memory version cannot replace the index.")
            if request.generation == existing_generation:
                if (
                    existing_status == "ACTIVE"
                    and int(existing.get("student_id")) == request.student_id
                    and existing_course == request.course_id
                    and request.version == existing_version
                    and existing.get("content_hash") == request.content_hash
                    and existing.get("memory_type") == request.memory_type
                    and (existing.get("knowledge_point_id") or "")
                    == (request.knowledge_point_id or "")
                    and (existing.get("source_type") or "")
                    == (request.source_type or "")
                    and int(existing.get("source_id", -1))
                    == (request.source_id if request.source_id is not None else -1)
                ):
                    return True
                raise MemoryStoreConflictError(
                    "The same sync generation must be exactly idempotent."
                )
            if existing_status == "ACTIVE" and int(existing.get("student_id")) != request.student_id:
                raise MemoryStoreConflictError("memory_id belongs to another student.")
        metadata: dict[str, Any] = {
            "memory_id": request.memory_id,
            "student_id": request.student_id,
            "course_id": request.course_id,
            "memory_type": request.memory_type,
            "knowledge_point_id": request.knowledge_point_id or "",
            "status": "ACTIVE",
            "version": request.version,
            "version_no": request.version,
            "generation": request.generation,
            "content_hash": request.content_hash,
            "source_type": request.source_type or "",
            "source_id": request.source_id if request.source_id is not None else -1,
        }
        self._collection.upsert(
            ids=[str(request.memory_id)],
            embeddings=[embedding],
            documents=[request.content],
            metadatas=[metadata],
        )
        return True

    def search(
        self,
        request: MemorySearchRequest,
        embedding: list[float],
    ) -> tuple[list[MemorySearchHit], MemoryIndexState]:
        with self._lock:
            try:
                return self._search(request, embedding), self._state
            except Exception:
                # Do not retry against the freshly empty collection: returning an
                # ordinary empty result would disguise incomplete personalization.
                self._open(recover_corruption=True, force_rebuild=True)
                return [], self._state

    def _search(
        self,
        request: MemorySearchRequest,
        embedding: list[float],
    ) -> list[MemorySearchHit]:
        conditions: list[dict[str, Any]] = [
            {"student_id": {"$eq": request.student_id}},
            {"course_id": {"$eq": request.course_id}},
            {"status": {"$eq": "ACTIVE"}},
        ]
        if request.knowledge_point_id is not None:
            conditions.append({"knowledge_point_id": {"$eq": request.knowledge_point_id}})
        if request.memory_types:
            conditions.append({"memory_type": {"$in": request.memory_types}})
        where: dict[str, Any] = {"$and": conditions}
        result = self._collection.query(
            query_embeddings=[embedding],
            n_results=request.limit,
            where=where,
            include=["documents", "metadatas", "distances"],
        )
        documents = (result.get("documents") or [[]])[0]
        metadatas = (result.get("metadatas") or [[]])[0]
        distances = (result.get("distances") or [[]])[0]
        hits: list[MemorySearchHit] = []
        for document, metadata, distance in zip(documents, metadatas, distances, strict=True):
            if not metadata or metadata.get("status") != "ACTIVE":
                continue
            if int(metadata.get("student_id")) != request.student_id:
                raise MemoryStoreUnavailableError("The memory index violated student isolation.")
            if int(metadata.get("course_id", -1)) != request.course_id:
                raise MemoryStoreUnavailableError("The memory index violated course isolation.")
            numeric_distance = float(distance)
            if not math.isfinite(numeric_distance):
                raise MemoryStoreUnavailableError("The memory index returned an invalid distance.")
            score = max(0.0, min(1.0, 1.0 - numeric_distance))
            if score < request.min_score:
                continue
            hits.append(
                MemorySearchHit(
                    course_id=int(metadata["course_id"]),
                    memory_id=int(metadata["memory_id"]),
                    student_id=int(metadata["student_id"]),
                    memory_type=str(metadata["memory_type"]),
                    knowledge_point_id=str(metadata.get("knowledge_point_id") or "") or None,
                    content=str(document),
                    score=score,
                    version=int(metadata.get("version", metadata.get("version_no"))),
                    generation=int(metadata["generation"]),
                    source_type=str(metadata.get("source_type") or "") or None,
                    source_id=None
                    if int(metadata.get("source_id", -1)) < 0
                    else int(metadata["source_id"]),
                )
            )
        return hits

    def delete(
        self,
        memory_id: int,
        mode: DeleteMode,
        generation: int,
        tombstone_embedding: list[float],
    ) -> tuple[int, MemoryIndexState]:
        with self._lock:
            try:
                return (
                    self._delete(memory_id, mode, generation, tombstone_embedding),
                    self._state,
                )
            except MemoryStoreConflictError:
                raise
            except Exception:
                self._open(recover_corruption=True, force_rebuild=True)
                try:
                    return (
                        self._delete(memory_id, mode, generation, tombstone_embedding),
                        self._state,
                    )
                except MemoryStoreConflictError:
                    raise
                except Exception as exc:
                    raise MemoryStoreUnavailableError("The memory index is unavailable.") from exc

    def _delete(
        self,
        memory_id: int,
        mode: DeleteMode,
        generation: int,
        tombstone_embedding: list[float],
    ) -> int:
        current = self._collection.get(
                    ids=[str(memory_id)],
                    include=["metadatas", "embeddings"],
                )
        metadatas = current.get("metadatas") or []
        embeddings = current.get("embeddings")
        if metadatas:
            metadata = dict(metadatas[0] or {})
            existing_generation = int(metadata.get("generation", 0))
            existing_status = str(metadata.get("status") or "")
            if generation < existing_generation:
                raise MemoryStoreConflictError("An older delete generation is stale.")
            if generation == existing_generation:
                expected_status = (
                    "DELETED" if mode == "TERMINAL_DELETE" else "DEINDEXED"
                )
                if existing_status == expected_status:
                    return 0
                raise MemoryStoreConflictError(
                    "The same sync generation cannot change index state."
                )
            if existing_status == "DELETED" and mode == "DEINDEX":
                raise MemoryStoreConflictError("A terminal memory index cannot be deindexed.")
            vector = (
                [float(value) for value in embeddings[0]]
                if embeddings is not None and len(embeddings) > 0
                else tombstone_embedding
            )
            deleted_count = 1 if existing_status == "ACTIVE" else 0
        else:
            metadata = {
                "memory_id": memory_id,
                "student_id": -1,
                "memory_type": "SESSION_SUMMARY",
                "knowledge_point_id": "",
                "source_type": "",
                "source_id": -1,
            }
            vector = tombstone_embedding
            deleted_count = 0
        metadata["status"] = "DELETED" if mode == "TERMINAL_DELETE" else "DEINDEXED"
        metadata["content_hash"] = "0" * 64
        metadata["generation"] = generation
        if mode == "TERMINAL_DELETE":
            metadata["version"] = TERMINAL_VERSION
            metadata["version_no"] = TERMINAL_VERSION
        else:
            metadata["version"] = int(metadata.get("version", 1))
            metadata["version_no"] = int(metadata.get("version_no", metadata["version"]))
        self._collection.upsert(
            ids=[str(memory_id)],
            embeddings=[vector],
            documents=["[deleted]"],
            metadatas=[metadata],
        )
        return deleted_count

    def _open(self, *, recover_corruption: bool, force_rebuild: bool = False) -> None:
        with self._lock:
            if force_rebuild:
                try:
                    self._quarantine_existing()
                    self._rotate_rebuild_epoch()
                    self._create_client()
                    return
                except Exception as exc:
                    raise MemoryStoreUnavailableError(
                        "The memory index could not be initialized."
                    ) from exc
            try:
                self._create_client()
            except Exception as exc:
                if not recover_corruption:
                    raise MemoryStoreUnavailableError("The memory index is unavailable.") from exc
                try:
                    self._quarantine_existing()
                    self._rotate_rebuild_epoch()
                    self._create_client()
                except Exception as rebuild_exc:
                    raise MemoryStoreUnavailableError(
                        "The memory index could not be initialized."
                    ) from rebuild_exc

    def _create_client(self) -> None:
        self._path.mkdir(parents=True, exist_ok=True)
        self._client = chromadb.PersistentClient(
            path=os.fspath(self._path),
            settings=Settings(anonymized_telemetry=False),
        )
        self._collection = self._client.get_or_create_collection(
            COLLECTION_NAME,
            metadata={"hnsw:space": "cosine", "purpose": "learning_memory"},
            embedding_function=None,
        )
        self._client.heartbeat()

    def _load_or_initialize_state(self) -> MemoryIndexState:
        state_file_exists = self._state_path.exists()
        existing_index = self._has_index_data()
        if state_file_exists:
            try:
                payload = json.loads(self._state_path.read_text(encoding="utf-8"))
                if payload.get("format") != INDEX_STATE_FORMAT:
                    raise ValueError("unsupported memory index state format")
                if payload.get("collection") != COLLECTION_NAME:
                    raise ValueError("memory index state collection mismatch")
                epoch = str(payload["memory_index_epoch"])
                if len(epoch) != 32 or uuid.UUID(hex=epoch).hex != epoch:
                    raise ValueError("invalid memory index epoch")
                rebuild_required = payload["memory_rebuild_required"]
                if not isinstance(rebuild_required, bool):
                    raise ValueError("invalid memory rebuild flag")
                if payload.get("embedding_provider") != self._embedding_provider:
                    raise ValueError("memory embedding provider changed")
                if payload.get("embedding_model") != self._embedding_model:
                    raise ValueError("memory embedding model changed")
                state = MemoryIndexState(epoch=epoch, rebuild_required=rebuild_required)
                # A persisted state without its corresponding Chroma database is
                # itself a derivative-index loss event.
                if existing_index:
                    return state
            except (OSError, UnicodeError, ValueError, KeyError, TypeError, json.JSONDecodeError):
                pass
            self._startup_rebuild_required = existing_index
            state = MemoryIndexState(epoch=uuid.uuid4().hex, rebuild_required=True)
        else:
            # A truly new, empty location is healthy. A non-empty pre-protocol
            # location has an unknown embedding identity and must be rebuilt.
            self._startup_rebuild_required = existing_index
            state = MemoryIndexState(
                epoch=uuid.uuid4().hex,
                rebuild_required=existing_index,
            )
        self._persist_state(state)
        return state

    def _has_index_data(self) -> bool:
        try:
            return self._path.exists() and any(self._path.iterdir())
        except OSError:
            return True

    def _rotate_rebuild_epoch(self) -> None:
        state = MemoryIndexState(epoch=uuid.uuid4().hex, rebuild_required=True)
        self._persist_state(state)
        self._state = state

    def _persist_state(self, state: MemoryIndexState) -> None:
        self._state_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self._state_path.with_name(
            f".{self._state_path.name}.{uuid.uuid4().hex}.tmp"
        )
        payload = {
            "format": INDEX_STATE_FORMAT,
            "collection": COLLECTION_NAME,
            "embedding_provider": self._embedding_provider,
            "embedding_model": self._embedding_model,
            "memory_index_epoch": state.epoch,
            "memory_rebuild_required": state.rebuild_required,
        }
        try:
            with temporary.open("w", encoding="utf-8", newline="\n") as stream:
                json.dump(payload, stream, ensure_ascii=True, separators=(",", ":"))
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self._state_path)
        finally:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass

    def _quarantine_existing(self) -> None:
        self._release_client()
        if not self._path.exists():
            return
        quarantine = self._path.with_name(
            f"{self._path.name}.corrupt-{uuid.uuid4().hex}"
        )
        try:
            self._path.replace(quarantine)
        except OSError:
            # No authoritative data lives here; retain a best-effort copy before reset.
            shutil.copytree(self._path, quarantine, dirs_exist_ok=False)
            shutil.rmtree(self._path)

    def _release_client(self) -> None:
        client = self._client
        self._client = None
        self._collection = None
        try:
            system = getattr(client, "_system", None)
        except (KeyError, RuntimeError):
            system = None
        stop = getattr(system, "stop", None)
        if callable(stop):
            try:
                stop()
            except Exception:
                # The derivative index may already be corrupt; cache reset and
                # filesystem quarantine remain the recovery boundary.
                pass
        SharedSystemClient.clear_system_cache()
