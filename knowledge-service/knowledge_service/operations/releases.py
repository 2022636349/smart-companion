from __future__ import annotations

import json
import os
import re
import shutil
import stat
import uuid
import hashlib
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from knowledge_service.operations.quality import load_graph, quality_report, sha256_file
from knowledge_service.app.domain.graph_dto import (
    GraphRagContextResponse,
    GraphRecommendationResponse,
)
from knowledge_service.app.repository.json_repo import JsonGraphRepository
from knowledge_service.app.repository.course_scope import is_empty_course_graph


D_WORKSPACE_ROOT = Path(r"D:\CodexEnvs\EducationAgent")
DEFAULT_BACKUP_ROOT = D_WORKSPACE_ROOT / "backups" / "graph"
VERSION_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")


def _inside(path: Path, root: Path) -> bool:
    try:
        return os.path.commonpath((str(path.resolve()), str(root.resolve()))).casefold() == str(
            root.resolve()
        ).casefold()
    except ValueError:
        return False


def require_d_workspace_path(path: str | Path, *, root: Path = D_WORKSPACE_ROOT) -> Path:
    resolved = Path(path).resolve()
    if not _inside(resolved, root):
        raise ValueError(f"write target must stay under {root.resolve()}: {resolved}")
    return resolved


def _write_json_exclusive(path: Path, value: dict[str, Any]) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")


def _immutable(path: Path) -> None:
    path.chmod(stat.S_IREAD | stat.S_IRGRP | stat.S_IROTH)


def _dto_digest(value: dict[str, Any]) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _semantic_probe(graph_file: Path) -> dict[str, Any]:
    graph = load_graph(graph_file)
    course_id = (graph.get("metadata") or {}).get("course_id", 1)
    repository = JsonGraphRepository(graph_file, include_pending=False, course_id=course_id)
    if is_empty_course_graph(graph):
        return {"bootstrap_empty": True, "course_id": course_id}
    rag_value = None
    rag_node_id = None
    for node_id in sorted(repository.nodes):
        node = repository.nodes[node_id]
        if node.get("node_type") != "knowledge_point" or node.get("review_status") != "APPROVED":
            continue
        candidate = repository.rag_context(node_id, max_related=8)
        if candidate and candidate.get("context_status") == "READY":
            rag_node_id = node_id
            rag_value = GraphRagContextResponse.model_validate(candidate).model_dump(mode="json")
            break
    if rag_value is None or rag_node_id is None:
        raise ValueError("snapshot has no READY approved Graph-RAG probe")
    recommendation_value = GraphRecommendationResponse.model_validate(
        {
            "items": repository.recommend([], limit=5),
            "course_id": course_id,
            "graph_version": repository.version()["graph_version"],
        }
    ).model_dump(mode="json")
    if not recommendation_value["items"]:
        raise ValueError("snapshot has no approved recommendation probe")
    return {
        "rag_context": {"node_id": rag_node_id, "dto_sha256": _dto_digest(rag_value)},
        "recommend_empty_mastery": {
            "item_count": len(recommendation_value["items"]),
            "dto_sha256": _dto_digest(recommendation_value),
        },
    }


def create_snapshot(
    graph_file: str | Path,
    *,
    backup_root: str | Path = DEFAULT_BACKUP_ROOT,
    created_at: str | None = None,
) -> Path:
    source = Path(graph_file).resolve()
    graph = load_graph(source)
    version = str((graph.get("metadata") or {}).get("version") or "")
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError("graph metadata.version is missing or unsafe for a release path")
    root = require_d_workspace_path(backup_root)
    root.mkdir(parents=True, exist_ok=True)
    target = require_d_workspace_path(root / version)
    if target.exists():
        raise FileExistsError(f"immutable snapshot already exists: {target}")

    staging = require_d_workspace_path(root / f".tmp-{version}-{uuid.uuid4().hex}")
    staging.mkdir()
    try:
        snapshot_graph = staging / "graph.json"
        shutil.copyfile(source, snapshot_graph)
        digest = sha256_file(snapshot_graph)
        timestamp = created_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        report = quality_report(
            graph, graph_sha256=digest, generated_at=timestamp
        )
        if not report["passed"]:
            raise ValueError("graph quality gate blocked snapshot publication")
        quality_path = staging / "quality-report.json"
        _write_json_exclusive(quality_path, report)
        manifest = {
            "schema_version": "graph-release-manifest-v1",
            "immutable": True,
            "graph_version": version,
            "created_at": timestamp,
            "counts": report["counts"],
            "quality_decision": report["release_decision"],
            "files": {
                "graph.json": {
                    "sha256": digest,
                    "size": snapshot_graph.stat().st_size,
                },
                "quality-report.json": {
                    "sha256": sha256_file(quality_path),
                    "size": quality_path.stat().st_size,
                },
            },
        }
        manifest_path = staging / "manifest.json"
        _write_json_exclusive(manifest_path, manifest)
        for item in (snapshot_graph, quality_path, manifest_path):
            _immutable(item)
        staging.rename(target)
        return target
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def verify_snapshot(snapshot_dir: str | Path) -> dict[str, Any]:
    snapshot = Path(snapshot_dir).resolve()
    manifest_path = snapshot / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("immutable") is not True:
        raise ValueError("release manifest is not immutable")
    for name in ("graph.json", "quality-report.json"):
        item = snapshot / name
        expected = manifest.get("files", {}).get(name, {})
        if not item.is_file() or item.stat().st_size != expected.get("size"):
            raise ValueError(f"snapshot file size mismatch: {name}")
        if sha256_file(item) != expected.get("sha256"):
            raise ValueError(f"snapshot checksum mismatch: {name}")
    graph = load_graph(snapshot / "graph.json")
    version = str((graph.get("metadata") or {}).get("version") or "")
    if version != manifest.get("graph_version"):
        raise ValueError("snapshot graph version does not match manifest")
    fresh_quality = quality_report(
        graph, graph_sha256=manifest["files"]["graph.json"]["sha256"]
    )
    stored_quality = json.loads((snapshot / "quality-report.json").read_text(encoding="utf-8"))
    if stored_quality.get("release_decision") != "PUBLISH" or not fresh_quality["passed"]:
        raise ValueError("snapshot fails the graph quality gate")
    semantic_checks = _semantic_probe(snapshot / "graph.json")
    return {
        "manifest": manifest,
        "quality": fresh_quality,
        "semantic_checks": semantic_checks,
        "snapshot_dir": str(snapshot),
    }


def restore_snapshot(
    snapshot_dir: str | Path,
    *,
    isolated_target: str | Path,
) -> Path:
    verified = verify_snapshot(snapshot_dir)
    target = require_d_workspace_path(isolated_target)
    if "isolated" not in target.name.casefold():
        raise ValueError("restore target must be explicitly named as isolated")
    if target.exists():
        raise FileExistsError(f"restore target already exists; overwrite is disabled: {target}")
    target.mkdir(parents=True)
    source = Path(verified["snapshot_dir"])
    try:
        shutil.copyfile(source / "graph.json", target / "graph.json")
        shutil.copyfile(source / "manifest.json", target / "manifest.json")
        shutil.copyfile(source / "quality-report.json", target / "quality-report.json")
        if sha256_file(target / "graph.json") != verified["manifest"]["files"]["graph.json"]["sha256"]:
            raise ValueError("restored graph checksum mismatch")
        if _semantic_probe(target / "graph.json") != verified["semantic_checks"]:
            raise ValueError("restored Graph-RAG/recommend DTO consistency check failed")
        return target
    except Exception:
        shutil.rmtree(target, ignore_errors=True)
        raise


def rollback_to_snapshot(
    snapshot_dir: str | Path,
    *,
    isolated_target: str | Path,
    state_dir: str | Path,
) -> Path:
    restored = restore_snapshot(snapshot_dir, isolated_target=isolated_target)
    verified = verify_snapshot(snapshot_dir)
    state = require_d_workspace_path(state_dir)
    state.mkdir(parents=True, exist_ok=True)
    pointer = state / "active-release.json"
    temporary = state / f".active-release-{uuid.uuid4().hex}.json"
    temporary.write_text(
        json.dumps(
            {
                "graph_version": verified["manifest"]["graph_version"],
                "verified_snapshot": verified["snapshot_dir"],
                "isolated_target": str(restored),
                "switched_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            },
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, pointer)
    return pointer
