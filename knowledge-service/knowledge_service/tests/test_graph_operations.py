from __future__ import annotations

import json
import os
import shutil
import stat
import tempfile
from pathlib import Path

import pytest

from knowledge_service.app.repository.json_repo import JsonGraphRepository
from knowledge_service.operations.neo4j_backup import (
    backup_command,
    restore_command,
    restore_neo4j,
)
from knowledge_service.operations.performance import run_json_baseline
from knowledge_service.operations.quality import report_for_file, sha256_file
from knowledge_service.operations.releases import (
    D_WORKSPACE_ROOT,
    create_snapshot,
    restore_snapshot,
    rollback_to_snapshot,
    verify_snapshot,
)


ROOT = Path(__file__).resolve().parents[2]
GRAPH_FILE = ROOT / "knowledge_graph_v2.json"


def _make_d_temp() -> Path:
    test_root = D_WORKSPACE_ROOT / "tests" / "graph-operations"
    test_root.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix="isolated-", dir=test_root))


def _remove_tree(path: Path) -> None:
    if not path.exists():
        return
    for item in path.rglob("*"):
        try:
            item.chmod(stat.S_IWRITE | stat.S_IREAD)
        except OSError:
            pass
    shutil.rmtree(path)


@pytest.fixture
def d_temp():
    path = _make_d_temp()
    try:
        yield path
    finally:
        _remove_tree(path)


def test_quality_report_has_release_thresholds_coverage_and_review_boundaries():
    report = report_for_file(GRAPH_FILE)

    assert report["graph_version"] == "2.4.0-c5-gc.1"
    assert report["graph_sha256"] == sha256_file(GRAPH_FILE)
    assert report["counts"]["nodes"] == 150
    assert report["counts"]["edges"] == 119
    assert report["counts"]["sources"] == 147
    assert report["counts"]["node_review_statuses"] == {"APPROVED": 147, "PENDING": 3}
    assert report["counts"]["edge_review_statuses"] == {
        "APPROVED": 106,
        "PENDING": 3,
        "REJECTED": 10,
    }
    assert report["coverage"]["approved_source_coverage"] == 1.0
    assert report["thresholds"]["max_dangling_references"] == 0
    assert set(report["issues"]) >= {
        "isolated_from_approved_requires",
        "missing_edge_endpoints",
        "duplicate_edge_count",
        "requires_cycles",
    }
    assert report["release_decision"] == "PUBLISH"


def test_snapshot_manifest_is_immutable_verified_and_never_overwritten(d_temp: Path):
    backup_root = d_temp / "backups"
    snapshot = create_snapshot(GRAPH_FILE, backup_root=backup_root, created_at="2026-08-23T00:00:00Z")
    verified = verify_snapshot(snapshot)

    assert snapshot.parent == backup_root
    assert verified["manifest"]["immutable"] is True
    assert verified["manifest"]["graph_version"] == "2.4.0-c5-gc.1"
    assert verified["manifest"]["files"]["graph.json"]["sha256"] == sha256_file(GRAPH_FILE)
    assert verified["quality"]["release_decision"] == "PUBLISH"
    with pytest.raises(FileExistsError):
        create_snapshot(GRAPH_FILE, backup_root=backup_root)


def test_restore_requires_explicit_d_isolation_checksum_and_no_overwrite(d_temp: Path):
    snapshot = create_snapshot(GRAPH_FILE, backup_root=d_temp / "backups")
    with pytest.raises(ValueError, match="under"):
        restore_snapshot(snapshot, isolated_target=Path("C:/tmp/isolated-graph"))
    with pytest.raises(ValueError, match="isolated"):
        restore_snapshot(snapshot, isolated_target=d_temp / "live")

    target = restore_snapshot(snapshot, isolated_target=d_temp / "isolated-restore")
    assert sha256_file(target / "graph.json") == sha256_file(GRAPH_FILE)
    with pytest.raises(FileExistsError):
        restore_snapshot(snapshot, isolated_target=target)


def test_rollback_switches_only_to_a_verified_snapshot(d_temp: Path):
    snapshot = create_snapshot(GRAPH_FILE, backup_root=d_temp / "backups")
    pointer = rollback_to_snapshot(
        snapshot,
        isolated_target=d_temp / "isolated-rollback",
        state_dir=d_temp / "state",
    )
    active = json.loads(pointer.read_text(encoding="utf-8"))
    assert active["graph_version"] == "2.4.0-c5-gc.1"
    assert active["verified_snapshot"] == str(snapshot.resolve())

    corrupt = d_temp / "corrupt"
    shutil.copytree(snapshot, corrupt)
    (corrupt / "graph.json").chmod(stat.S_IWRITE | stat.S_IREAD)
    with (corrupt / "graph.json").open("ab") as handle:
        handle.write(b"corrupt")
    with pytest.raises(ValueError, match="mismatch"):
        rollback_to_snapshot(
            corrupt,
            isolated_target=d_temp / "isolated-corrupt",
            state_dir=d_temp / "other-state",
        )
    assert not (d_temp / "other-state" / "active-release.json").exists()


def test_neo4j_backup_restore_commands_are_dry_run_safe_and_target_protected(d_temp: Path):
    compose = ROOT / "docker-compose.neo4j.yml"
    backup = backup_command(compose_file=compose, database="neo4j", container_path="/backups/neo4j/v1")
    assert "dump" in backup
    assert "--overwrite-destination=false" in backup
    assert not any("clear-first" in item for item in backup)
    with pytest.raises(ValueError, match="isolated"):
        restore_command(compose_file=compose, target_database="neo4j", container_path="/backups/v1")

    snapshot = create_snapshot(GRAPH_FILE, backup_root=d_temp / "releases")
    dump_dir = d_temp / "neo4j" / "2.4.0-c5-gc.1"
    dump_dir.mkdir(parents=True)
    dump = dump_dir / "neo4j.dump"
    dump.write_bytes(b"test-only-dump")
    manifest = {
        "schema_version": "neo4j-backup-manifest-v1",
        "immutable": True,
        "graph_version": "2.4.0-c5-gc.1",
        "release_snapshot": str(snapshot),
        "dump": {"name": dump.name, "size": dump.stat().st_size, "sha256": sha256_file(dump)},
    }
    (dump_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    result = restore_neo4j(
        compose_file=compose,
        backup_dir=dump_dir,
        target_database="education-graph-isolated-cycle6",
    )
    assert result["dry_run"] is True
    assert result["quality_decision"] == "PUBLISH"
    assert "--overwrite-destination=false" in result["command"]


def test_json_performance_baseline_has_deterministic_p95_boundary():
    report = run_json_baseline(
        JsonGraphRepository(GRAPH_FILE), iterations=20, p95_threshold_ms=250.0
    )
    assert report["graph_version"] == "2.4.0-c5-gc.1"
    assert report["passed"] is True
    assert set(report["cases"]) == {"match", "recommend", "rag_context"}
