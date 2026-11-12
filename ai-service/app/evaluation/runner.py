from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True, slots=True)
class EvaluationThresholds:
    min_quality: float = 0.80
    max_latency_ms: int = 5_000
    max_estimated_cost_microunits: int = 100_000


def run_fixture_evaluation(
    fixture_path: str | Path, thresholds: EvaluationThresholds = EvaluationThresholds()
) -> dict[str, Any]:
    """Evaluate checked-in de-identified fixtures; this never calls a provider."""
    cases = json.loads(Path(fixture_path).read_text(encoding="utf-8"))["cases"]
    results = []
    for case in cases:
        quality = float(case["quality_score"])
        latency_ms = int(case["latency_ms"])
        cost = int(case["estimated_cost_microunits"])
        structure_ok = bool(case["structure_ok"])
        source_ok = bool(case["source_compliant"])
        passed = (
            structure_ok
            and source_ok
            and quality >= thresholds.min_quality
            and latency_ms <= thresholds.max_latency_ms
            and cost <= thresholds.max_estimated_cost_microunits
        )
        results.append({
            "case_id": case["case_id"], "capability": case["capability"],
            "structure_ok": structure_ok, "source_compliant": source_ok,
            "quality_score": quality, "latency_ms": latency_ms,
            "input_units": int(case["input_units"]), "output_units": int(case["output_units"]),
            "estimated_cost_microunits": cost, "passed": passed,
        })
    return {
        "mode": "OFFLINE_FIXTURE_ONLY",
        "case_count": len(results),
        "passed": all(item["passed"] for item in results),
        "thresholds": asdict(thresholds), "results": results,
    }
