from __future__ import annotations

import os
import unittest
from unittest.mock import patch

from app.core.config import (
    AssessmentParseConfigurationError,
    AssessmentParseSettings,
    AssessmentSourceSettings,
)


class AssessmentParseSettingsTest(unittest.TestCase):
    def test_assessment_source_defaults_to_contract_file_limit(self) -> None:
        with patch.dict(
            os.environ,
            {
                "ASSESSMENT_SOURCE_MAX_FILE_BYTES": "",
                "INTERNAL_SERVICE_TOKEN": "test-internal-token",
            },
            clear=False,
        ):
            self.assertEqual(
                AssessmentSourceSettings.from_env().max_file_bytes,
                50 * 1024 * 1024,
            )

    def test_default_and_hard_upper_deadline(self) -> None:
        with patch.dict(
            os.environ, {"ASSESSMENT_PARSE_TOTAL_TIMEOUT_SECONDS": ""}, clear=False
        ):
            self.assertEqual(
                AssessmentParseSettings.from_env().total_timeout_seconds, 75.0
            )

        with patch.dict(
            os.environ, {"ASSESSMENT_PARSE_TOTAL_TIMEOUT_SECONDS": "80"}, clear=False
        ):
            self.assertEqual(
                AssessmentParseSettings.from_env().total_timeout_seconds, 80.0
            )

        with patch.dict(
            os.environ, {"ASSESSMENT_PARSE_TOTAL_TIMEOUT_SECONDS": "80.1"}, clear=False
        ):
            with self.assertRaises(AssessmentParseConfigurationError):
                AssessmentParseSettings.from_env()


if __name__ == "__main__":
    unittest.main()
