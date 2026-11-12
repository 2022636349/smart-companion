from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

import knowledge_service.app.services.graph_service as graph_service_module
from knowledge_service.app.services.graph_service import GraphService


ROOT = Path(__file__).resolve().parents[2]
GRAPH_FILE = ROOT / "knowledge_graph_v2.json"


def _configure_backend(monkeypatch: pytest.MonkeyPatch, backend: str) -> None:
    monkeypatch.setenv("GRAPH_BACKEND", backend)
    monkeypatch.setenv("GRAPH_FILE", str(GRAPH_FILE))
    monkeypatch.setenv("NEO4J_PASSWORD", "test-password")


def test_explicit_neo4j_initialization_failure_is_rethrown(
    monkeypatch: pytest.MonkeyPatch,
):
    _configure_backend(monkeypatch, "neo4j")
    failure = ValueError("invalid Neo4j configuration")

    def fail_initialization(*_args: Any, **_kwargs: Any):
        raise failure

    monkeypatch.setattr(
        graph_service_module,
        "Neo4jGraphRepository",
        fail_initialization,
    )

    with pytest.raises(ValueError, match="invalid Neo4j configuration") as exc_info:
        GraphService.from_environment()
    assert exc_info.value is failure


def test_auto_initialization_failure_selects_json(
    monkeypatch: pytest.MonkeyPatch,
):
    _configure_backend(monkeypatch, "auto")

    def fail_initialization(*_args: Any, **_kwargs: Any):
        raise RuntimeError("Neo4j driver unavailable")

    monkeypatch.setattr(
        graph_service_module,
        "Neo4jGraphRepository",
        fail_initialization,
    )

    service = GraphService.from_environment()

    assert service.backend_preference == "auto"
    assert service.neo4j_repo is None
    assert service.version()["backend"] == "json"


@pytest.mark.parametrize("backend", ["auto", "neo4j"])
def test_selected_neo4j_runtime_failure_never_calls_json(
    monkeypatch: pytest.MonkeyPatch,
    backend: str,
):
    class TrackingJsonRepository:
        course_id = 1
        called = False

        def search(self, *_args: Any, **_kwargs: Any):
            self.called = True
            return [{"unsafe": "fallback"}]

    class FailingNeo4jRepository:
        def search(self, *_args: Any, **_kwargs: Any):
            raise ConnectionError("Neo4j request failed")

    monkeypatch.setenv("GRAPH_BACKEND", backend)
    json_repository = TrackingJsonRepository()
    service = GraphService(  # type: ignore[arg-type]
        json_repository,
        FailingNeo4jRepository(),
    )

    with pytest.raises(ConnectionError, match="Neo4j request failed"):
        service.search("bounded query")
    assert json_repository.called is False


def test_explicit_neo4j_without_repository_never_calls_json(
    monkeypatch: pytest.MonkeyPatch,
):
    class TrackingJsonRepository:
        course_id = 1
        called = False

        def search(self, *_args: Any, **_kwargs: Any):
            self.called = True
            return [{"unsafe": "fallback"}]

    monkeypatch.setenv("GRAPH_BACKEND", "neo4j")
    json_repository = TrackingJsonRepository()
    service = GraphService(json_repository)  # type: ignore[arg-type]

    with pytest.raises(RuntimeError, match="未成功初始化"):
        service.search("bounded query")
    assert json_repository.called is False
