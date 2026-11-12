from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from knowledge_service.app.repository.json_repo import JsonGraphRepository  # noqa: E402
from knowledge_service.operations.performance import run_json_baseline  # noqa: E402
from knowledge_service.operations.releases import require_d_workspace_path  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="Deterministic graph query performance baseline")
    parser.add_argument("graph_file", nargs="?", default=str(SERVICE_ROOT / "knowledge_graph_v2.json"))
    parser.add_argument("--iterations", type=int, default=50)
    parser.add_argument("--p95-threshold-ms", type=float, default=100.0)
    parser.add_argument("--output")
    args = parser.parse_args()
    result = run_json_baseline(
        JsonGraphRepository(args.graph_file),
        iterations=args.iterations,
        p95_threshold_ms=args.p95_threshold_ms,
    )
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        require_d_workspace_path(args.output).write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
