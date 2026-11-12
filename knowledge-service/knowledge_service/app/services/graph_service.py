from __future__ import annotations

import os
import logging
from time import perf_counter
from pathlib import Path
from typing import Any

from dotenv import load_dotenv

from knowledge_service.app.repository.json_repo import JsonGraphRepository
from knowledge_service.app.repository.neo4j_repo import Neo4jGraphRepository
from knowledge_service.app.repository.course_scope import empty_course_graph, valid_course_id


LOGGER = logging.getLogger("uvicorn.error")


class GraphService:
    def __init__(self, json_repo: JsonGraphRepository, neo4j_repo: Neo4jGraphRepository | None = None):
        self.course_id = json_repo.course_id
        self.json_repo = json_repo
        self.neo4j_repo = neo4j_repo
        self.backend_preference = os.getenv("GRAPH_BACKEND", "auto").lower()
        self.slow_query_ms = max(1.0, float(os.getenv("GRAPH_SLOW_QUERY_MS", "250")))

    @classmethod
    def from_environment(cls, course_id: int = 1) -> "GraphService":
        course_id = valid_course_id(course_id)
        project_root = Path(__file__).resolve().parents[3]
        load_dotenv(project_root / ".env")
        graph_file = os.getenv("GRAPH_FILE", str(project_root / "knowledge_graph_v2.json"))
        if not Path(graph_file).is_absolute():
            graph_file = str(project_root / graph_file)
        include_pending = os.getenv("GRAPH_ALLOW_PENDING", "false").lower() == "true"
        json_repo = JsonGraphRepository(
            graph_file, include_pending=include_pending, course_id=course_id,
            graph_data=empty_course_graph(course_id) if course_id != 1 else None,
        )
        neo4j_repo = None
        backend = os.getenv("GRAPH_BACKEND", "auto").lower()
        neo4j_password = os.getenv("NEO4J_PASSWORD", "")
        if backend == "neo4j" and not neo4j_password:
            raise RuntimeError("GRAPH_BACKEND=neo4j 时必须设置 NEO4J_PASSWORD")
        if backend != "json" and neo4j_password:
            try:
                neo4j_repo = Neo4jGraphRepository(
                    os.getenv("NEO4J_URI", "bolt://localhost:7687"),
                    os.getenv("NEO4J_USER", "neo4j"),
                    neo4j_password,
                    include_pending=include_pending,
                    course_id=course_id,
                    query_timeout_seconds=float(
                        os.getenv("GRAPH_QUERY_TIMEOUT_SECONDS", "3")
                    ),
                    max_connection_pool_size=int(
                        os.getenv("GRAPH_NEO4J_POOL_SIZE", "20")
                    ),
                    connection_timeout_seconds=float(
                        os.getenv("GRAPH_NEO4J_CONNECTION_TIMEOUT_SECONDS", "5")
                    ),
                    candidate_limit=int(
                        os.getenv("GRAPH_QUERY_CANDIDATE_LIMIT", "500")
                    ),
                    recommendation_depth=int(
                        os.getenv("GRAPH_RECOMMEND_MAX_DEPTH", "10")
                    ),
                    slow_query_ms=float(os.getenv("GRAPH_SLOW_QUERY_MS", "250")),
                )
            except (RuntimeError, ValueError) as exc:
                if backend == "neo4j":
                    raise
                LOGGER.warning(
                    "graph_backend_initialization backend=auto selected=json reason=%s",
                    exc.__class__.__name__,
                )
                neo4j_repo = None
        return cls(json_repo, neo4j_repo)

    def close(self) -> None:
        if self.neo4j_repo:
            self.neo4j_repo.close()

    def activate_json_release(self, graph_file: str | Path) -> None:
        """Atomically replace the JSON read view with a verified release file.

        The caller must verify the immutable release before invoking this seam.
        The repository object is replaced rather than mutating the checked-in
        knowledge_graph_v2.json or an existing repository in place.
        """
        replacement = JsonGraphRepository(
            Path(graph_file).resolve(),
            include_pending=self.json_repo.include_pending,
            course_id=self.course_id,
        )
        self.json_repo = replacement

    def _call(self, method: str, *args: Any, **kwargs: Any) -> tuple[Any, str, list[str]]:
        started = perf_counter()
        warnings: list[str] = []
        if self.backend_preference == "neo4j" and self.neo4j_repo is None:
            raise RuntimeError(
                "GRAPH_BACKEND=neo4j 但 Neo4j Repository 未成功初始化"
            )
        if self.backend_preference != "json" and self.neo4j_repo is not None:
            # Backend selection may fall back only while constructing an auto
            # service. Once a Neo4j repository is selected, request failures
            # must remain fail-closed and must never be retried against JSON.
            result = getattr(self.neo4j_repo, method)(*args, **kwargs)
            LOGGER.info("graph_method=%s backend=neo4j", method)
            self._log_slow(method, "neo4j", started)
            return result, "neo4j", warnings
        result = getattr(self.json_repo, method)(*args, **kwargs)
        LOGGER.info("graph_method=%s backend=json", method)
        self._log_slow(method, "json", started)
        return result, "json", warnings

    def _log_slow(self, method: str, backend: str, started: float) -> None:
        duration_ms = (perf_counter() - started) * 1000
        if duration_ms >= self.slow_query_ms:
            LOGGER.warning(
                "graph_slow_query method=%s backend=%s duration_ms=%.2f threshold_ms=%.2f",
                method,
                backend,
                duration_ms,
                self.slow_query_ms,
            )

    def health(self) -> dict[str, Any]:
        result, backend, warnings = self._call("health")
        result["backend"] = backend
        result["warnings"] = result.get("warnings", []) + warnings
        return result

    def get_node(self, node_id: str) -> dict[str, Any] | None:
        result, _, _ = self._call("get_node", node_id)
        return result

    def search(self, query: str, limit: int = 20) -> list[dict[str, Any]]:
        result, _, _ = self._call("search", query, limit)
        return result

    def match(
        self,
        text: str,
        scene: str,
        current_node_id: str | None = None,
        limit: int = 5,
    ) -> list[dict[str, Any]]:
        result, _, _ = self._call(
            "match", text, scene, current_node_id, limit
        )
        return result

    def recommend_next(
        self,
        mastery: list[dict[str, Any]],
        limit: int = 5,
    ) -> dict[str, Any]:
        items, backend, _ = self._call("recommend", mastery, limit)
        repository = self.neo4j_repo if backend == "neo4j" else self.json_repo
        graph_version = str(repository.version().get("graph_version") or "unknown")
        return {"course_id": self.course_id, "items": items, "graph_version": graph_version}

    def context(self, node_id: str) -> dict[str, Any] | None:
        result, _, _ = self._call("context", node_id)
        return result

    def prerequisites(
        self,
        node_id: str,
        max_depth: int = 5,
    ) -> list[dict[str, Any]] | None:
        result, _, _ = self._call("prerequisites", node_id, max_depth)
        return result

    def sources(
        self,
        node_id: str,
        approved_only: bool = True,
    ) -> list[dict[str, Any]] | None:
        result, _, _ = self._call("sources", node_id, approved_only)
        return result

    def version(self) -> dict[str, Any]:
        result, backend, _ = self._call("version")
        result["backend"] = backend
        return result

    def rag_context(
        self,
        node_id: str,
        max_related: int = 8,
    ) -> dict[str, Any] | None:
        result, _, fallback_warnings = self._call(
            "rag_context", node_id, max_related
        )
        if result is not None and fallback_warnings:
            result["warnings"] = result.get("warnings", []) + fallback_warnings
        return result

    def subgraph(
        self,
        center_id: str | None,
        depth: int = 1,
        relations: set[str] | None = None,
    ) -> dict[str, Any] | None:
        if center_id is None:
            center_id, _, _ = self._call("course_root")
            if center_id is None:
                version = self.version()
                return {"course_id": self.course_id, "center_id": "", "nodes": [],
                        "edges": [], "graph_version": version["graph_version"]}
        result, _, _ = self._call("subgraph", center_id, depth, relations)
        return result
