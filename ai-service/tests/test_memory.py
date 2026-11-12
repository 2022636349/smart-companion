from __future__ import annotations

import gc
import hashlib
import json
import os
import shutil
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.contracts.memory import (
    DeleteIndexResponse,
    MemorySearchResponse,
    MemorySearchRequest,
    MemoryUpsertResponse,
    MemoryUpsertRequest,
)
from app.core.config import MemoryConfigurationError, MemorySettings
from app.main import app
from app.memory.store import LearningMemoryStore, MemoryStoreConflictError
from app.providers.base import EmbeddingTimeoutError, EmbeddingUnavailableError
from app.services.memory import MemoryService
from app.services.memory import close_memory_service, create_memory_service


def digest(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def upsert_request(
    memory_id: int,
    student_id: int,
    content: str,
    *,
    course_id: int = 1,
    version: int = 1,
    generation: int = 1,
):
    return MemoryUpsertRequest(
        course_id=course_id,
        request_id="memory-request-0001",
        memory_id=memory_id,
        student_id=student_id,
        memory_type="MISCONCEPTION",
        knowledge_point_id="kp-python-functions",
        content=content,
        content_hash=digest(content),
        status="ACTIVE",
        version=version,
        generation=generation,
    )


class FakeEmbeddingProvider:
    provider_name = "fake"
    model_name = "fake-memory-v1"

    def __init__(self, error: Exception | None = None) -> None:
        self.error = error
        self.closed = False

    async def embed(self, texts):
        if self.error:
            raise self.error
        return [[1.0, 0.0, 0.0] for _ in texts]

    async def aclose(self):
        self.closed = True


class RealChromaMemoryTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        root = Path("D:/CodexEnvs/EducationAgent/test-temp")
        root.mkdir(parents=True, exist_ok=True)
        self.path = Path(tempfile.mkdtemp(prefix="memory-chroma-", dir=root))
        self.stores: list[LearningMemoryStore] = []
        self.settings = MemorySettings(
            embedding_provider="ollama",
            embedding_model="fake-memory-v1",
            ollama_base_url="http://localhost:11434",
            embedding_timeout_seconds=1,
            total_timeout_seconds=5,
            chroma_persist_dir=os.fspath(self.path),
        )

    def memory_store(
        self,
        *,
        embedding_provider: str = "ollama",
        embedding_model: str = "fake-memory-v1",
    ) -> LearningMemoryStore:
        return LearningMemoryStore(
            os.fspath(self.path),
            embedding_provider=embedding_provider,
            embedding_model=embedding_model,
        )

    def tearDown(self) -> None:
        for store in self.stores:
            store.close()
        gc.collect()
        shutil.rmtree(self.path, ignore_errors=True)
        for item in self.path.parent.glob(f"{self.path.name}.corrupt-*"):
            shutil.rmtree(item, ignore_errors=True)
        self.path.with_name(
            f"{self.path.name}.learning-memory-index-state.json"
        ).unlink(missing_ok=True)

    async def test_student_isolation_delete_and_terminal_version_guard(self) -> None:
        store = self.memory_store()
        self.stores.append(store)
        service = MemoryService(
            store,
            settings=self.settings,
            provider_factory=FakeEmbeddingProvider,
        )
        first = await service.upsert(upsert_request(11, 101, "函数参数顺序容易混淆"), trace_request_id="memory-request-0001")
        self.assertFalse(first.memory_rebuild_required)
        self.assertEqual(len(first.memory_index_epoch), 32)
        await service.upsert(upsert_request(12, 202, "另一个学生的函数困难"), trace_request_id="memory-request-0001")
        search = MemorySearchRequest(
            course_id=1,
            request_id="memory-request-0001",
            student_id=101,
            query="函数",
            knowledge_point_id="kp-python-functions",
            min_score=0,
        )
        result = await service.search(search, trace_request_id="memory-request-0001")
        self.assertEqual([item.memory_id for item in result.items], [11])
        self.assertEqual(result.memory_index_epoch, first.memory_index_epoch)
        self.assertFalse(result.memory_rebuild_required)
        deleted = await service.delete(
            11,
            "TERMINAL_DELETE",
            2,
            trace_request_id="memory-request-0001",
        )
        self.assertEqual(deleted.deleted_count, 1)
        self.assertEqual(deleted.request_id, "memory-request-0001")
        self.assertEqual(deleted.mode, "TERMINAL_DELETE")
        self.assertEqual(deleted.generation, 2)
        self.assertEqual(deleted.memory_index_epoch, first.memory_index_epoch)
        self.assertFalse(deleted.memory_rebuild_required)
        self.assertEqual((await service.search(search, trace_request_id="memory-request-0001")).items, [])
        with self.assertRaises(MemoryStoreConflictError):
            await service.upsert(upsert_request(11, 101, "函数参数顺序容易混淆"), trace_request_id="memory-request-0001")
        with self.assertRaises(MemoryStoreConflictError):
            await service.upsert(
                upsert_request(11, 101, "迟到的更高版本也不能复活", version=2),
                trace_request_id="memory-request-0001",
            )
        self.assertEqual((await service.search(search, trace_request_id="memory-request-0001")).items, [])

    async def test_content_hash_is_strict(self) -> None:
        with self.assertRaises(ValueError):
            MemoryUpsertRequest(
                **{
                    **upsert_request(1, 1, "content").model_dump(),
                    "content_hash": "0" * 64,
                }
            )

    async def test_same_student_same_knowledge_point_isolated_by_course(self) -> None:
        store = self.memory_store()
        self.stores.append(store)
        service = MemoryService(
            store,
            settings=self.settings,
            provider_factory=FakeEmbeddingProvider,
        )
        for memory_id, course_id in ((81, 1), (82, 2)):
            await service.upsert(
                upsert_request(
                    memory_id,
                    101,
                    f"课程 {course_id} 的函数学习证据",
                    course_id=course_id,
                ),
                trace_request_id="memory-request-0001",
            )
        for course_id, expected_id in ((1, 81), (2, 82)):
            result = await service.search(
                MemorySearchRequest(
                    course_id=course_id,
                    request_id="memory-request-0001",
                    student_id=101,
                    query="函数",
                    knowledge_point_id="kp-python-functions",
                    min_score=0,
                ),
                trace_request_id="memory-request-0001",
            )
            self.assertEqual(result.course_id, course_id)
            self.assertEqual(
                [(hit.memory_id, hit.course_id) for hit in result.items],
                [(expected_id, course_id)],
            )
        await service.delete(81, "TERMINAL_DELETE", 2, trace_request_id="memory-request-0001")
        result = await service.search(
            MemorySearchRequest(
                course_id=2,
                request_id="memory-request-0001",
                student_id=101,
                query="函数",
                min_score=0,
            ),
            trace_request_id="memory-request-0001",
        )
        self.assertEqual([hit.memory_id for hit in result.items], [82])

    async def test_existing_memory_cannot_move_to_another_course(self) -> None:
        store = self.memory_store()
        self.stores.append(store)
        store.upsert(upsert_request(91, 101, "函数", course_id=1), [1, 0, 0])
        with self.assertRaises(MemoryStoreConflictError):
            store.upsert(upsert_request(91, 101, "函数", course_id=2, generation=2), [1, 0, 0])
        store.delete(91, "DEINDEX", 2, [1, 0, 0])
        with self.assertRaises(MemoryStoreConflictError):
            store.upsert(upsert_request(91, 101, "函数", course_id=2, generation=3), [1, 0, 0])

    async def test_precourse_index_format_rotates_epoch_then_replays_both_courses(self) -> None:
        initial = self.memory_store()
        _, old_state = initial.upsert(upsert_request(95, 101, "旧课程记录"), [1, 0, 0])
        initial.close()
        state_path = self.path.with_name(
            f"{self.path.name}.learning-memory-index-state.json"
        )
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["format"] = 2
        state_path.write_text(json.dumps(state), encoding="utf-8")
        store = self.memory_store()
        self.stores.append(store)
        query = MemorySearchRequest(
            course_id=1,
            request_id="memory-request-0001",
            student_id=101,
            query="函数",
            min_score=0,
        )
        hits, new_state = store.search(query, [1, 0, 0])
        self.assertEqual(hits, [])
        self.assertTrue(new_state.rebuild_required)
        self.assertNotEqual(old_state.epoch, new_state.epoch)
        for memory_id, course_id in ((95, 1), (96, 2)):
            store.upsert(
                upsert_request(
                    memory_id,
                    101,
                    "迁移后的课程记忆",
                    course_id=course_id,
                ),
                [1, 0, 0],
            )
        for course_id, expected in ((1, 95), (2, 96)):
            hits, _ = store.search(query.model_copy(update={"course_id": course_id}), [1, 0, 0])
            self.assertEqual([hit.memory_id for hit in hits], [expected])

    async def test_corrupt_persistent_index_is_quarantined_then_rebuildable(self) -> None:
        initial_store = self.memory_store()
        initial_service = MemoryService(
            initial_store,
            settings=self.settings,
            provider_factory=FakeEmbeddingProvider,
        )
        initial = await initial_service.upsert(
            upsert_request(30, 301, "损坏前的派生索引"),
            trace_request_id="memory-request-0001",
        )
        initial_store.close()
        (self.path / "chroma.sqlite3").write_bytes(b"not-a-sqlite-database")
        store = self.memory_store()
        self.stores.append(store)
        service = MemoryService(store, settings=self.settings, provider_factory=FakeEmbeddingProvider)
        rebuilt = await service.upsert(upsert_request(31, 301, "可从 MySQL 重放的记忆"), trace_request_id="memory-request-0001")
        self.assertTrue(rebuilt.memory_rebuild_required)
        self.assertNotEqual(rebuilt.memory_index_epoch, initial.memory_index_epoch)
        response = await service.search(
            MemorySearchRequest(course_id=1, request_id="memory-request-0001", student_id=301, query="重放", min_score=0),
            trace_request_id="memory-request-0001",
        )
        self.assertEqual([item.memory_id for item in response.items], [31])
        self.assertEqual(response.memory_index_epoch, rebuilt.memory_index_epoch)
        self.assertTrue(response.memory_rebuild_required)
        self.assertTrue(list(self.path.parent.glob(f"{self.path.name}.corrupt-*")))

    async def test_legacy_index_without_state_is_marked_for_rebuild(self) -> None:
        initial_store = self.memory_store()
        initial_service = MemoryService(
            initial_store,
            settings=self.settings,
            provider_factory=FakeEmbeddingProvider,
        )
        initial = await initial_service.upsert(
            upsert_request(51, 501, "旧索引中的记忆"),
            trace_request_id="memory-request-0001",
        )
        initial_store.close()
        self.path.with_name(
            f"{self.path.name}.learning-memory-index-state.json"
        ).unlink()

        legacy_store = self.memory_store()
        self.stores.append(legacy_store)
        legacy_service = MemoryService(
            legacy_store,
            settings=self.settings,
            provider_factory=FakeEmbeddingProvider,
        )
        response = await legacy_service.search(
            MemorySearchRequest(
                course_id=1,
                request_id="memory-request-0001",
                student_id=501,
                query="旧索引",
                min_score=0,
            ),
            trace_request_id="memory-request-0001",
        )
        self.assertEqual(response.items, [])
        self.assertTrue(response.memory_rebuild_required)
        self.assertNotEqual(response.memory_index_epoch, initial.memory_index_epoch)
        self.assertTrue(list(self.path.parent.glob(f"{self.path.name}.corrupt-*")))

    async def test_embedding_model_change_rotates_epoch_and_requires_full_rebuild(self) -> None:
        initial_store = self.memory_store(embedding_model="fake-memory-v1")
        initial_service = MemoryService(
            initial_store,
            settings=self.settings,
            provider_factory=FakeEmbeddingProvider,
        )
        initial = await initial_service.upsert(
            upsert_request(52, 502, "旧向量空间中的记忆"),
            trace_request_id="memory-request-0001",
        )
        initial_store.close()

        changed_store = self.memory_store(embedding_model="fake-memory-v2")
        self.stores.append(changed_store)
        changed_service = MemoryService(
            changed_store,
            settings=self.settings,
            provider_factory=FakeEmbeddingProvider,
        )
        response = await changed_service.search(
            MemorySearchRequest(
                course_id=1,
                request_id="memory-request-0001",
                student_id=502,
                query="旧向量空间",
                min_score=0,
            ),
            trace_request_id="memory-request-0001",
        )

        self.assertEqual(response.items, [])
        self.assertTrue(response.memory_rebuild_required)
        self.assertNotEqual(response.memory_index_epoch, initial.memory_index_epoch)
        state = json.loads(
            self.path.with_name(
                f"{self.path.name}.learning-memory-index-state.json"
            ).read_text(encoding="utf-8")
        )
        self.assertEqual(state["embedding_provider"], "ollama")
        self.assertEqual(state["embedding_model"], "fake-memory-v2")
        self.assertTrue(list(self.path.parent.glob(f"{self.path.name}.corrupt-*")))

    async def test_concurrent_operations_share_one_atomic_healthy_epoch(self) -> None:
        store = self.memory_store()
        self.stores.append(store)

        def write(memory_id: int):
            return store.upsert(
                upsert_request(memory_id, 701, f"并发记忆 {memory_id}"),
                [1.0, 0.0, 0.0],
            )

        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(write, range(71, 87)))

        self.assertTrue(all(indexed for indexed, _ in results))
        self.assertEqual(len({state.epoch for _, state in results}), 1)
        self.assertFalse(any(state.rebuild_required for _, state in results))

    async def test_runtime_corruption_is_single_event_visible_persistent_and_rotates_again(self) -> None:
        store = self.memory_store()
        self.stores.append(store)
        service = MemoryService(store, settings=self.settings, provider_factory=FakeEmbeddingProvider)
        seeded = await service.upsert(
            upsert_request(61, 601, "预置记忆一"),
            trace_request_id="memory-request-0001",
        )
        await service.upsert(
            upsert_request(62, 601, "预置记忆二"),
            trace_request_id="memory-request-0001",
        )

        query = MemorySearchRequest(
            course_id=1,
            request_id="memory-request-0001",
            student_id=601,
            query="预置",
            min_score=0,
        )
        with patch.object(store._collection, "query", side_effect=RuntimeError("corrupt index")):
            reset = await service.search(query, trace_request_id="memory-request-0001")
        self.assertEqual(reset.items, [])
        self.assertTrue(reset.memory_rebuild_required)
        self.assertNotEqual(reset.memory_index_epoch, seeded.memory_index_epoch)

        store.close()
        restarted = self.memory_store()
        self.stores.append(restarted)
        restarted_service = MemoryService(
            restarted,
            settings=self.settings,
            provider_factory=FakeEmbeddingProvider,
        )
        after_restart = await restarted_service.search(
            query, trace_request_id="memory-request-0001"
        )
        self.assertEqual(after_restart.memory_index_epoch, reset.memory_index_epoch)
        self.assertTrue(after_restart.memory_rebuild_required)

        single_replay = await restarted_service.upsert(
            upsert_request(61, 601, "预置记忆一"),
            trace_request_id="memory-request-0001",
        )
        self.assertEqual(single_replay.memory_index_epoch, reset.memory_index_epoch)
        self.assertTrue(single_replay.memory_rebuild_required)
        terminal = await restarted_service.delete(
            62,
            "TERMINAL_DELETE",
            2,
            trace_request_id="memory-request-0001",
        )
        self.assertTrue(terminal.memory_rebuild_required)
        with self.assertRaises(MemoryStoreConflictError):
            await restarted_service.upsert(
                upsert_request(62, 601, "迟到事件不得复活终态删除", generation=3),
                trace_request_id="memory-request-0001",
            )

        prior_epoch = reset.memory_index_epoch
        with patch.object(restarted._collection, "query", side_effect=RuntimeError("second corruption")):
            second_reset = await restarted_service.search(
                query, trace_request_id="memory-request-0001"
            )
        self.assertTrue(second_reset.memory_rebuild_required)
        self.assertNotEqual(second_reset.memory_index_epoch, prior_epoch)

    async def test_deindex_allows_same_version_rebuild_but_terminal_never_does(self) -> None:
        store = self.memory_store()
        self.stores.append(store)
        service = MemoryService(store, settings=self.settings, provider_factory=FakeEmbeddingProvider)
        request = upsert_request(41, 401, "可逆偏好关闭的记忆")
        await service.upsert(request, trace_request_id="memory-request-0001")
        self.assertEqual(
            (
                await service.delete(
                    41,
                    "DEINDEX",
                    2,
                    trace_request_id="memory-request-0001",
                )
            ).deleted_count,
            1,
        )
        with self.assertRaises(MemoryStoreConflictError):
            await service.upsert(request, trace_request_id="memory-request-0001")
        rebuilt = await service.upsert(
            upsert_request(41, 401, "可逆偏好关闭的记忆", generation=3),
            trace_request_id="memory-request-0001",
        )
        self.assertTrue(rebuilt.indexed)
        with self.assertRaises(MemoryStoreConflictError):
            await service.delete(
                41,
                "DEINDEX",
                2,
                trace_request_id="memory-request-0001",
            )
        visible = await service.search(
            MemorySearchRequest(
                course_id=1,
                request_id="memory-request-0001",
                student_id=401,
                query="偏好",
                min_score=0,
            ),
            trace_request_id="memory-request-0001",
        )
        self.assertEqual([(item.memory_id, item.generation) for item in visible.items], [(41, 3)])

        self.assertEqual(
            (
                await service.delete(
                    42,
                    "TERMINAL_DELETE",
                    2,
                    trace_request_id="memory-request-0001",
                )
            ).deleted_count,
            0,
        )
        with self.assertRaises(MemoryStoreConflictError):
            await service.upsert(
                upsert_request(42, 401, "删除先于迟到写入", generation=3),
                trace_request_id="memory-request-0001",
            )


class FakeMemoryService:
    def __init__(self, error: Exception) -> None:
        self.error = error

    async def upsert(self, *args, **kwargs):
        raise self.error

    async def search(self, *args, **kwargs):
        raise self.error

    async def delete(self, *args, **kwargs):
        raise self.error


class FakeDeleteMemoryService:
    def __init__(self) -> None:
        self.calls = []

    async def delete(self, memory_id, mode, generation, *, trace_request_id):
        self.calls.append((memory_id, mode, generation, trace_request_id))
        return DeleteIndexResponse(
            request_id=trace_request_id,
            collection="learning_memory",
            memory_id=memory_id,
            mode=mode,
            generation=generation,
            deleted_count=1,
            memory_index_epoch="0123456789abcdef0123456789abcdef",
            memory_rebuild_required=True,
        )


class FakeSuccessfulMemoryService:
    async def upsert(self, request, *, trace_request_id):
        return MemoryUpsertResponse(
            request_id=trace_request_id,
            collection="learning_memory",
            memory_id=request.memory_id,
            generation=request.generation,
            indexed=True,
            embedding_model="fake-memory-v1",
            memory_index_epoch="fedcba9876543210fedcba9876543210",
            memory_rebuild_required=True,
        )

    async def search(self, request, *, trace_request_id):
        return MemorySearchResponse(
            course_id=1,
            request_id=trace_request_id,
            student_id=request.student_id,
            items=[],
            collection="learning_memory",
            memory_index_epoch="fedcba9876543210fedcba9876543210",
            memory_rebuild_required=True,
        )


class MemoryApiTypedErrorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        os.environ["INTERNAL_SERVICE_TOKEN"] = "test-internal-token"
        cls.client = TestClient(app)

    @staticmethod
    def headers():
        return {"X-Internal-Token": "test-internal-token", "X-Request-Id": "memory-request-0001"}

    def test_unavailable_is_503(self) -> None:
        payload = upsert_request(1, 1, "content").model_dump(mode="json")
        with patch("app.routers.memory.create_memory_service", return_value=FakeMemoryService(EmbeddingUnavailableError("down"))):
            response = self.client.post("/internal/memory/upsert", headers=self.headers(), json=payload)
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()["detail"]["code"], "MEMORY_INDEX_UNAVAILABLE")

    def test_timeout_is_504(self) -> None:
        payload = upsert_request(1, 1, "content").model_dump(mode="json")
        with patch("app.routers.memory.create_memory_service", return_value=FakeMemoryService(EmbeddingTimeoutError("late"))):
            response = self.client.post("/internal/memory/upsert", headers=self.headers(), json=payload)
        self.assertEqual(response.status_code, 504)

    def test_hash_mismatch_is_422_before_service(self) -> None:
        payload = upsert_request(1, 1, "content").model_dump(mode="json")
        payload["content_hash"] = "0" * 64
        response = self.client.post("/internal/memory/upsert", headers=self.headers(), json=payload)
        self.assertEqual(response.status_code, 422)

    def test_upsert_and_search_echo_recovery_protocol(self) -> None:
        service = FakeSuccessfulMemoryService()
        upsert_payload = upsert_request(1, 1, "content").model_dump(mode="json")
        search_payload = MemorySearchRequest(
            course_id=1,
            request_id="memory-request-0001",
            student_id=1,
            query="content",
        ).model_dump(mode="json")
        with patch("app.routers.memory.create_memory_service", return_value=service):
            upsert = self.client.post(
                "/internal/memory/upsert", headers=self.headers(), json=upsert_payload
            )
            search = self.client.post(
                "/internal/memory/search", headers=self.headers(), json=search_payload
            )
        self.assertEqual(upsert.status_code, 200)
        self.assertEqual(search.status_code, 200)
        self.assertEqual(search.json()["course_id"], 1)
        for body in (upsert.json(), search.json()):
            self.assertEqual(
                body["memory_index_epoch"],
                "fedcba9876543210fedcba9876543210",
            )
            self.assertTrue(body["memory_rebuild_required"])

    def test_delete_requires_mode_and_generation_and_echoes_contract(self) -> None:
        service = FakeDeleteMemoryService()
        with patch("app.routers.memory.create_memory_service", return_value=service):
            missing = self.client.delete("/internal/memory/1", headers=self.headers())
            response = self.client.delete(
                "/internal/memory/1?mode=DEINDEX&generation=7",
                headers=self.headers(),
            )
        self.assertEqual(missing.status_code, 422)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            response.json(),
            {
                "request_id": "memory-request-0001",
                "collection": "learning_memory",
                "memory_id": 1,
                "mode": "DEINDEX",
                "generation": 7,
                "deleted_count": 1,
                "memory_index_epoch": "0123456789abcdef0123456789abcdef",
                "memory_rebuild_required": True,
            },
        )
        self.assertEqual(service.calls, [(1, "DEINDEX", 7, "memory-request-0001")])

    def test_stale_delete_is_typed_422(self) -> None:
        with patch(
            "app.routers.memory.create_memory_service",
            return_value=FakeMemoryService(MemoryStoreConflictError("stale")),
        ):
            response = self.client.delete(
                "/internal/memory/1?mode=TERMINAL_DELETE&generation=2",
                headers=self.headers(),
            )
        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["detail"]["code"], "MEMORY_INDEX_CONFLICT")

    def test_runtime_openapi_requires_index_recovery_fields(self) -> None:
        schemas = app.openapi()["components"]["schemas"]
        for name in ("MemoryUpsertResponse", "MemorySearchResponse", "DeleteIndexResponse"):
            required = set(schemas[name]["required"])
            self.assertIn("memory_index_epoch", required)
            self.assertIn("memory_rebuild_required", required)


class MemoryServiceLifecycleTest(unittest.TestCase):
    def tearDown(self) -> None:
        close_memory_service()

    def test_concurrent_factory_initializes_one_process_store(self) -> None:
        close_memory_service()
        settings = MemorySettings(
            embedding_provider="ollama",
            embedding_model="fake",
            ollama_base_url="http://localhost:11434",
            embedding_timeout_seconds=1,
            total_timeout_seconds=5,
            chroma_persist_dir="D:\\CodexEnvs\\EducationAgent\\singleton-test",
        )

        class FakeStore:
            created = 0

            def __init__(self, _path, **_kwargs):
                type(self).created += 1
                self.closed = False

            def close(self):
                self.closed = True

        with (
            patch("app.services.memory.MemorySettings.from_env", return_value=settings),
            patch("app.services.memory.LearningMemoryStore", FakeStore),
            ThreadPoolExecutor(max_workers=8) as executor,
        ):
            services = list(executor.map(lambda _: create_memory_service(), range(32)))

        self.assertEqual(FakeStore.created, 1)
        self.assertEqual(len({id(service) for service in services}), 1)

    def test_only_learning_memory_collection_is_allowed(self) -> None:
        with self.assertRaises(ValueError):
            LearningMemoryStore(
                "D:\\CodexEnvs\\EducationAgent\\forbidden-collection-test",
                embedding_provider="ollama",
                embedding_model="fake-memory-v1",
                collection_name="course_resources",
            )

    def test_windows_persistence_configuration_rejects_c_drive(self) -> None:
        with patch.dict(
            os.environ,
            {
                "MEMORY_EMBEDDING_PROVIDER": "disabled",
                "CHROMA_PERSIST_DIR": "C:\\temp\\chroma",
            },
            clear=False,
        ):
            with self.assertRaises(MemoryConfigurationError):
                MemorySettings.from_env()


if __name__ == "__main__":
    unittest.main()
