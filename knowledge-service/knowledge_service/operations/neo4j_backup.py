from __future__ import annotations

import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from knowledge_service.operations.quality import sha256_file
from knowledge_service.operations.releases import (
    DEFAULT_BACKUP_ROOT,
    require_d_workspace_path,
    verify_snapshot,
)


ISOLATED_DATABASE = re.compile(r"^education-graph-isolated-[a-z0-9][a-z0-9-]{0,48}$")


def _compose_prefix(compose_file: str | Path) -> list[str]:
    return [
        "docker",
        "compose",
        "-f",
        str(Path(compose_file).resolve()),
        "run",
        "--rm",
        "--no-deps",
        "neo4j",
    ]


def backup_command(
    *, compose_file: str | Path, database: str, container_path: str
) -> list[str]:
    if not re.fullmatch(r"[A-Za-z0-9.-]+", database):
        raise ValueError("unsafe Neo4j database name")
    return _compose_prefix(compose_file) + [
        "neo4j-admin",
        "database",
        "dump",
        database,
        f"--to-path={container_path}",
        "--overwrite-destination=false",
    ]


def restore_command(
    *, compose_file: str | Path, target_database: str, container_path: str
) -> list[str]:
    if not ISOLATED_DATABASE.fullmatch(target_database):
        raise ValueError(
            "restore database must use education-graph-isolated-* and cannot be the live database"
        )
    return _compose_prefix(compose_file) + [
        "neo4j-admin",
        "database",
        "load",
        target_database,
        f"--from-path={container_path}",
        "--overwrite-destination=false",
    ]


def backup_neo4j(
    *,
    compose_file: str | Path,
    release_snapshot: str | Path,
    database: str = "neo4j",
    backup_root: str | Path = DEFAULT_BACKUP_ROOT / "neo4j",
    execute: bool = False,
) -> dict[str, Any]:
    release = verify_snapshot(release_snapshot)
    version = release["manifest"]["graph_version"]
    root = require_d_workspace_path(backup_root)
    target = require_d_workspace_path(root / version)
    container_path = f"/backups/neo4j/{version}"
    command = backup_command(
        compose_file=compose_file, database=database, container_path=container_path
    )
    if not execute:
        return {"dry_run": True, "command": command, "target": str(target)}
    if target.exists():
        raise FileExistsError(f"immutable Neo4j backup already exists: {target}")
    target.mkdir(parents=True)
    try:
        subprocess.run(command, check=True)
        dumps = sorted(target.glob("*.dump"))
        if len(dumps) != 1:
            raise RuntimeError("Neo4j backup must produce exactly one .dump file")
        dump = dumps[0]
        manifest = {
            "schema_version": "neo4j-backup-manifest-v1",
            "immutable": True,
            "graph_version": version,
            "database": database,
            "created_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "release_snapshot": release["snapshot_dir"],
            "dump": {
                "name": dump.name,
                "size": dump.stat().st_size,
                "sha256": sha256_file(dump),
            },
        }
        with (target / "manifest.json").open("x", encoding="utf-8", newline="\n") as handle:
            json.dump(manifest, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
        return {"dry_run": False, "command": command, "target": str(target), "manifest": manifest}
    except Exception:
        # A failed dump is deliberately left in place for operator inspection;
        # the absence of a valid manifest prevents restore or rollback.
        raise


def verify_neo4j_backup(backup_dir: str | Path) -> dict[str, Any]:
    backup = Path(backup_dir).resolve()
    manifest = json.loads((backup / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("immutable") is not True:
        raise ValueError("Neo4j backup manifest is not immutable")
    release = verify_snapshot(manifest["release_snapshot"])
    if release["manifest"]["graph_version"] != manifest.get("graph_version"):
        raise ValueError("Neo4j backup and release snapshot versions differ")
    dump = backup / manifest["dump"]["name"]
    if dump.stat().st_size != manifest["dump"]["size"]:
        raise ValueError("Neo4j dump size mismatch")
    if sha256_file(dump) != manifest["dump"]["sha256"]:
        raise ValueError("Neo4j dump checksum mismatch")
    return {"backup_dir": str(backup), "manifest": manifest, "release": release}


def restore_neo4j(
    *,
    compose_file: str | Path,
    backup_dir: str | Path,
    target_database: str,
    execute: bool = False,
) -> dict[str, Any]:
    verified = verify_neo4j_backup(backup_dir)
    version = verified["manifest"]["graph_version"]
    command = restore_command(
        compose_file=compose_file,
        target_database=target_database,
        container_path=f"/backups/neo4j/{version}",
    )
    if execute:
        subprocess.run(command, check=True)
    return {
        "dry_run": not execute,
        "command": command,
        "target_database": target_database,
        "verified_graph_version": version,
        "quality_decision": verified["release"]["quality"]["release_decision"],
    }
