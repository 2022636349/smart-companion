from __future__ import annotations

import os
import unittest
from unittest.mock import patch

from app.core.config import GraphIngestionConfigurationError, GraphIngestionSettings


class GraphIngestionSettingsTest(unittest.TestCase):
    def test_default_deadline_allows_both_model_stages(self) -> None:
        with patch.dict(
            os.environ,
            {"GRAPH_INGESTION_TOTAL_TIMEOUT_SECONDS": ""},
            clear=False,
        ):
            self.assertEqual(
                GraphIngestionSettings.from_env().total_timeout_seconds,
                220.0,
            )

    def test_deadline_has_explicit_hard_upper_bound(self) -> None:
        with patch.dict(
            os.environ,
            {"GRAPH_INGESTION_TOTAL_TIMEOUT_SECONDS": "240"},
            clear=False,
        ):
            self.assertEqual(
                GraphIngestionSettings.from_env().total_timeout_seconds,
                240.0,
            )

        with patch.dict(
            os.environ,
            {"GRAPH_INGESTION_TOTAL_TIMEOUT_SECONDS": "240.1"},
            clear=False,
        ):
            with self.assertRaises(GraphIngestionConfigurationError):
                GraphIngestionSettings.from_env()


if __name__ == "__main__":
    unittest.main()
