from pathlib import Path

from app.evaluation.runner import EvaluationThresholds, run_fixture_evaluation


def test_fixed_deidentified_fixture_evaluation_passes_default_thresholds():
    fixture = Path(__file__).resolve().parents[1] / "evaluation" / "fixtures.json"
    report = run_fixture_evaluation(fixture)
    assert report["mode"] == "OFFLINE_FIXTURE_ONLY"
    assert report["case_count"] == 4
    assert report["passed"] is True
    assert "@" not in fixture.read_text(encoding="utf-8")


def test_fixture_report_fails_a_stricter_quality_gate():
    fixture = Path(__file__).resolve().parents[1] / "evaluation" / "fixtures.json"
    assert run_fixture_evaluation(fixture, EvaluationThresholds(min_quality=0.99))["passed"] is False
