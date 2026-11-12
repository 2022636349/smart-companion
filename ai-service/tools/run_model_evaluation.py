from __future__ import annotations

import json
from pathlib import Path

from app.evaluation.runner import run_fixture_evaluation


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    print(json.dumps(run_fixture_evaluation(root / "evaluation" / "fixtures.json"), ensure_ascii=False, indent=2))
