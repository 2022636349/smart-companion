"""Approved fixed learning content, independent of mutable ingestion graphs."""
from __future__ import annotations

import json
import os
from copy import deepcopy
from pathlib import Path


class LearningContentService:
    def __init__(self, catalog_path: Path | None = None):
        self.catalog_path = catalog_path or Path(os.environ.get(
            "GRAPH_LEARNING_CONTENT_FILE",
            str(Path(__file__).resolve().parents[3] / "data/smart-companion/learning-content-v1.json"),
        ))

    def get_card(self, indicator_id: str, course_id: int) -> dict | None:
        # Missing fixed baseline is an explicit unavailable result, never generated content.
        if not self.catalog_path.is_file():
            return None
        catalog = json.loads(self.catalog_path.read_text(encoding="utf-8"))
        if catalog.get("courseId") != course_id or catalog.get("reviewStatus") != "APPROVED":
            return None
        for card in catalog.get("cards", []):
            if card.get("id") == indicator_id and card.get("reviewStatus") == "APPROVED":
                return deepcopy(card)
        return None

