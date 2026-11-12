from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from knowledge_service.operations.neo4j_backup import backup_neo4j, restore_neo4j  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="Protected Neo4j dump and isolated restore (dry-run by default)")
    commands = parser.add_subparsers(dest="command", required=True)
    backup = commands.add_parser("backup")
    backup.add_argument("--compose-file", default=str(SERVICE_ROOT / "docker-compose.neo4j.yml"))
    backup.add_argument("--release-snapshot", required=True)
    backup.add_argument("--database", default="neo4j")
    backup.add_argument("--backup-root")
    backup.add_argument("--execute", action="store_true")
    restore = commands.add_parser("restore")
    restore.add_argument("--compose-file", default=str(SERVICE_ROOT / "docker-compose.neo4j.yml"))
    restore.add_argument("--backup-dir", required=True)
    restore.add_argument("--target-database", required=True)
    restore.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if args.command == "backup":
        kwargs = {
            "compose_file": args.compose_file,
            "release_snapshot": args.release_snapshot,
            "database": args.database,
            "execute": args.execute,
        }
        if args.backup_root:
            kwargs["backup_root"] = args.backup_root
        result = backup_neo4j(**kwargs)
    else:
        result = restore_neo4j(
            compose_file=args.compose_file,
            backup_dir=args.backup_dir,
            target_database=args.target_database,
            execute=args.execute,
        )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
