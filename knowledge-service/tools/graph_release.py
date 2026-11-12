from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from knowledge_service.operations.quality import report_for_file  # noqa: E402
from knowledge_service.operations.releases import (  # noqa: E402
    DEFAULT_BACKUP_ROOT,
    create_snapshot,
    restore_snapshot,
    rollback_to_snapshot,
    require_d_workspace_path,
    verify_snapshot,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Graph quality, immutable snapshot, restore, and rollback")
    commands = parser.add_subparsers(dest="command", required=True)

    quality = commands.add_parser("quality")
    quality.add_argument("graph_file")
    quality.add_argument("--output")

    snapshot = commands.add_parser("snapshot")
    snapshot.add_argument("graph_file")
    snapshot.add_argument("--backup-root", default=str(DEFAULT_BACKUP_ROOT))

    verify = commands.add_parser("verify")
    verify.add_argument("snapshot_dir")

    restore = commands.add_parser("restore")
    restore.add_argument("snapshot_dir")
    restore.add_argument("--isolated-target", required=True)

    rollback = commands.add_parser("rollback")
    rollback.add_argument("snapshot_dir")
    rollback.add_argument("--isolated-target", required=True)
    rollback.add_argument("--state-dir", required=True)

    args = parser.parse_args()
    if args.command == "quality":
        result = report_for_file(args.graph_file)
        if args.output:
            output = require_d_workspace_path(args.output)
            output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result["passed"] else 1
    if args.command == "snapshot":
        result = {"snapshot_dir": str(create_snapshot(args.graph_file, backup_root=args.backup_root))}
    elif args.command == "verify":
        result = verify_snapshot(args.snapshot_dir)
    elif args.command == "restore":
        result = {"restored_to": str(restore_snapshot(args.snapshot_dir, isolated_target=args.isolated_target))}
    else:
        result = {
            "active_release_pointer": str(
                rollback_to_snapshot(
                    args.snapshot_dir,
                    isolated_target=args.isolated_target,
                    state_dir=args.state_dir,
                )
            )
        }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
