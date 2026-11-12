from __future__ import annotations

import math
from time import perf_counter
from typing import Any, Callable


def _measure(action: Callable[[], Any], iterations: int) -> dict[str, Any]:
    durations: list[float] = []
    result_size = 0
    for _ in range(iterations):
        started = perf_counter()
        result = action()
        durations.append((perf_counter() - started) * 1000)
        if isinstance(result, list):
            result_size = len(result)
        elif isinstance(result, dict):
            result_size = len(result.get("items", result.get("nodes", [])))
    ordered = sorted(durations)
    percentile_index = max(0, math.ceil(len(ordered) * 0.95) - 1)
    return {
        "iterations": iterations,
        "result_size": result_size,
        "p50_ms": round(ordered[len(ordered) // 2], 3),
        "p95_ms": round(ordered[percentile_index], 3),
        "max_ms": round(ordered[-1], 3),
    }


def run_json_baseline(
    repository: Any,
    *,
    iterations: int = 50,
    p95_threshold_ms: float = 100.0,
) -> dict[str, Any]:
    if iterations < 2 or iterations > 1000:
        raise ValueError("iterations must be between 2 and 1000")
    mastery = [
        {"knowledge_point_id": "3.1.1", "score": 90.0, "confidence": 0.9},
        {"knowledge_point_id": "3.1.2", "score": 55.0, "confidence": 0.8},
    ]
    cases = {
        "match": _measure(
            lambda: repository.match(
                "请使用for循环和range完成条件判断", "QUESTION", limit=5
            ),
            iterations,
        ),
        "recommend": _measure(
            lambda: repository.recommend(mastery, limit=5), iterations
        ),
        "rag_context": _measure(
            lambda: repository.rag_context("3.1.2", max_related=8), iterations
        ),
    }
    passed = all(item["p95_ms"] <= p95_threshold_ms for item in cases.values())
    return {
        "schema_version": "graph-performance-baseline-v1",
        "graph_version": repository.version()["graph_version"],
        "backend": repository.version()["backend"],
        "p95_threshold_ms": p95_threshold_ms,
        "cases": cases,
        "passed": passed,
    }
