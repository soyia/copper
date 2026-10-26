#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
RUN_TOOL = ROOT / "scripts" / "upgrade-run.py"
ENV_TOOL = ROOT / "scripts" / "upgrade-env.py"
RESTORE_TOOL = ROOT / "scripts" / "restore-metadata.py"


def write_json(path: pathlib.Path, value: object, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value) + "\n", encoding="utf-8")
    path.chmod(mode)


class UpgradeRunTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="copper-m3-03-")
        self.root = pathlib.Path(self.temporary.name)
        self.upgrades = self.root / "upgrades"
        self.prepared = self.upgrades / "prepared.json"
        write_json(self.prepared, {
            "formatVersion": "copper-upgrade-preparation/v1",
            "status": "PREPARED",
            "currentVersion": "0.1.0-m2-rc.2",
            "currentSchema": "3",
            "targetVersion": "0.2.0-rc.1",
            "targetSchema": "4",
            "backup": {"backupId": "a" * 32},
        }, 0o400)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_tool(self, tool: pathlib.Path, *args: str, ok: bool = True, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [sys.executable, str(tool), *args], text=True, capture_output=True, check=False, env=env,
        )
        if ok and result.returncode != 0:
            self.fail(result.stderr)
        if not ok and result.returncode == 0:
            self.fail("command unexpectedly passed")
        return result

    def test_prepared_is_claimed_once_and_transitions_are_strict(self) -> None:
        upgrade_id = "upgrade-20260714"
        self.run_tool(RUN_TOOL, "claim", "--prepared", str(self.prepared), "--upgrade-root", str(self.upgrades), "--upgrade-id", upgrade_id)
        state = self.upgrades / "runs" / upgrade_id / "state.json"
        document = json.loads(state.read_text(encoding="utf-8"))
        self.assertEqual(document["status"], "CLAIMED")
        self.assertEqual(document["preparedSha256"], hashlib.sha256(self.prepared.read_bytes()).hexdigest())
        self.assertEqual(state.stat().st_mode & 0o777, 0o400)
        self.run_tool(RUN_TOOL, "claim", "--prepared", str(self.prepared), "--upgrade-root", str(self.upgrades), "--upgrade-id", "upgrade-second", ok=False)
        self.run_tool(RUN_TOOL, "transition", "--state", str(state), "--status", "RESTORING", "--phase", "restore", "--action", "restore", "--result", "started", "--message", "start")
        self.run_tool(RUN_TOOL, "transition", "--state", str(state), "--status", "SWITCHED", "--phase", "bad", "--action", "switch", "--result", "passed", "--message", "bad", ok=False)
        self.run_tool(RUN_TOOL, "transition", "--state", str(state), "--status", "MIGRATION_FAILED", "--phase", "failed", "--action", "migrate", "--result", "failed", "--message", "failed")
        self.run_tool(RUN_TOOL, "transition", "--state", str(state), "--status", "ABORTED", "--phase", "abort", "--action", "discard", "--result", "passed", "--message", "abort")
        self.run_tool(RUN_TOOL, "finalize", "--state", str(state))
        self.assertFalse(self.prepared.exists())
        self.assertFalse((self.upgrades / "prepared.claim.json").exists())
        self.assertTrue((state.parent / "prepared.json").is_file())
        self.run_tool(RUN_TOOL, "status", "--state", str(state))

    def test_switched_upgrade_can_complete_only_through_acceptance_transition(self) -> None:
        upgrade_id = "upgrade-accept-20260720"
        self.run_tool(RUN_TOOL, "claim", "--prepared", str(self.prepared), "--upgrade-root", str(self.upgrades), "--upgrade-id", upgrade_id)
        state = self.upgrades / "runs" / upgrade_id / "state.json"
        for status, phase, action in (
            ("RESTORING", "restore", "restore"),
            ("MIGRATING", "migrate", "migrate"),
            ("MIGRATION_VALIDATED", "ready", "migrate"),
            ("SWITCHING", "switch", "switch"),
            ("SWITCHED", "live", "switch"),
            ("COMPLETED", "accepted", "accept"),
        ):
            self.run_tool(
                RUN_TOOL, "transition", "--state", str(state), "--status", status,
                "--phase", phase, "--action", action, "--result", "passed", "--message", status,
            )
        self.run_tool(RUN_TOOL, "finalize", "--state", str(state))
        self.assertEqual(json.loads(state.read_text(encoding="utf-8"))["status"], "COMPLETED")
        self.assertFalse((self.upgrades / "prepared.claim.json").exists())

    def test_target_env_comes_from_manifest_and_overrides_are_test_only(self) -> None:
        source = self.root / "source.env"
        manifest = self.root / "release.env"
        output = self.root / "target.env"
        source.write_text(
            "COPPER_RELEASE_VERSION=0.1.0-m2-rc.2\nCOPPER_IMAGE_DELIVERY=offline\nCOPPER_WEB_IMAGE=old-web\n"
            "COPPER_BACKEND_IMAGE=old-backend\nCOPPER_MYSQL_IMAGE=old-mysql\nCOPPER_SITE_ID=site-a\n",
            encoding="utf-8",
        )
        manifest.write_text(
            "COPPER_RELEASE_VERSION=0.2.0-rc.1\nCOPPER_WEB_IMAGE=new-web\n"
            "COPPER_BACKEND_IMAGE=new-backend\nCOPPER_MYSQL_IMAGE=new-mysql\n",
            encoding="utf-8",
        )
        self.run_tool(ENV_TOOL, "--source", str(source), "--manifest", str(manifest), "--output", str(output))
        content = output.read_text(encoding="utf-8")
        self.assertIn("COPPER_RELEASE_VERSION=0.2.0-rc.1", content)
        self.assertIn("COPPER_IMAGE_DELIVERY=registry", content)
        self.assertIn("COPPER_SITE_ID=site-a", content)
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)
        self.run_tool(ENV_TOOL, "--source", str(source), "--manifest", str(manifest), "--output", str(output), "--test-web-image", "sha256:test", ok=False)
        test_env = dict(os.environ)
        test_env["COPPER_TEST_MODE"] = "1"
        self.run_tool(ENV_TOOL, "--source", str(source), "--manifest", str(manifest), "--output", str(output), "--test-web-image", "sha256:test", env=test_env, ok=False)

        local_ids = {
            "web": f"sha256:{'4' * 64}",
            "backend": f"sha256:{'5' * 64}",
            "mysql": f"sha256:{'6' * 64}",
        }
        self.run_tool(
            ENV_TOOL,
            "--source", str(source),
            "--manifest", str(manifest),
            "--output", str(output),
            "--test-web-image", local_ids["web"],
            "--test-backend-image", local_ids["backend"],
            "--test-mysql-image", local_ids["mysql"],
            env=test_env,
        )
        local_content = output.read_text(encoding="utf-8")
        self.assertIn("COPPER_IMAGE_DELIVERY=offline", local_content)
        self.assertIn(f"COPPER_WEB_IMAGE={local_ids['web']}", local_content)

        self.run_tool(
            ENV_TOOL,
            "--source", str(source),
            "--manifest", str(manifest),
            "--output", str(output),
            "--test-web-image", local_ids["web"],
            env=test_env,
            ok=False,
        )

        self.run_tool(
            ENV_TOOL,
            "--source", str(source),
            "--manifest", str(manifest),
            "--output", str(output),
            "--test-web-image", "web:test",
            "--test-backend-image", "backend:test",
            "--test-mysql-image", "mysql:test",
            env=test_env,
            ok=False,
        )

        manifest_sha = hashlib.sha256(manifest.read_bytes()).hexdigest()
        ready = self.root / "ready.json"
        ready.write_text(json.dumps({
            "format": "copper-delivery-ready/v1", "status": "READY", "version": "0.2.0-rc.1",
            "mode": "offline", "deliveredAt": "2026-07-14T00:00:00Z",
            "indexFile": "copper-delivery-offline-0.2.0-rc.1.json", "indexSha256": "a" * 64,
            "manifestSha256": manifest_sha,
            "images": [
                {"role": "web", "reference": "new-web", "localId": f"sha256:{'1' * 64}", "architecture": "amd64"},
                {"role": "backend", "reference": "new-backend", "localId": f"sha256:{'2' * 64}", "architecture": "amd64"},
                {"role": "mysql", "reference": "new-mysql", "localId": f"sha256:{'3' * 64}", "architecture": "amd64"},
            ],
        }), encoding="utf-8")
        self.run_tool(ENV_TOOL, "--source", str(source), "--manifest", str(manifest), "--ready", str(ready), "--output", str(output))
        offline_content = output.read_text(encoding="utf-8")
        self.assertIn("COPPER_IMAGE_DELIVERY=offline", offline_content)
        self.assertIn(f"COPPER_BACKEND_IMAGE=sha256:{'2' * 64}", offline_content)

        ready_document = json.loads(ready.read_text(encoding="utf-8"))
        ready_document["mode"] = "offline-delta"
        ready.write_text(json.dumps(ready_document), encoding="utf-8")
        self.run_tool(
            ENV_TOOL, "--source", str(source), "--manifest", str(manifest),
            "--ready", str(ready), "--output", str(output),
        )
        delta_content = output.read_text(encoding="utf-8")
        self.assertIn("COPPER_IMAGE_DELIVERY=offline", delta_content)
        self.assertIn(f"COPPER_MYSQL_IMAGE=sha256:{'3' * 64}", delta_content)

    def make_verification(self, path: pathlib.Path, schema: str, flyway: list[str], include_source_table: bool = True) -> None:
        write_json(path / "schema.json", {
            "databaseProduct": "MySQL", "databaseVersion": "8.4.10", "schemaVersion": schema,
            "tableCount": 2, "tables": ["business", "flyway_schema_history"],
        })
        tables = [{"name": "flyway_schema_history", "rows": len(flyway)}]
        if include_source_table:
            tables.append({"name": "business", "rows": 2})
        write_json(path / "record-counts.json", {"tableCount": len(tables), "totalRows": sum(item["rows"] for item in tables), "tables": tables})
        write_json(path / "business-totals.json", {"metrics": [{"metric": "stock.quantity_available", "value": "10.000000"}]})
        write_json(path / "attachments.json", {"fileCount": 0, "totalBytes": 0, "databaseReferenceCount": 0, "orphanFileCount": 0, "files": []})
        (path / "foreign-keys.tsv").write_text("__none__\t0\n", encoding="utf-8")
        (path / "flyway-history.tsv").write_text("\n".join(flyway) + "\n", encoding="utf-8")

    def test_upgrade_verification_preserves_flyway_history_and_business_contract(self) -> None:
        source = self.root / "source"
        target = self.root / "target"
        row3 = "3\t3\tbaseline\tSQL\tV3__baseline.sql\t123\t1"
        row4 = "4\t4\tadditive\tSQL\tV4__additive.sql\t456\t1"
        self.make_verification(source, "3", [row3])
        self.make_verification(target, "4", [row3, row4])
        result = self.root / "result.json"
        self.run_tool(RESTORE_TOOL, "compare-upgrade-verification", str(source), str(target), str(target / "foreign-keys.tsv"), str(result), "--source-schema", "3", "--target-schema", "4")
        self.assertEqual(json.loads(result.read_text(encoding="utf-8"))["result"], "passed")
        self.make_verification(target, "4", ["3\t3\ttampered\tSQL\tV3__baseline.sql\t123\t1", row4])
        self.run_tool(RESTORE_TOOL, "compare-upgrade-verification", str(source), str(target), str(target / "foreign-keys.tsv"), str(result), "--source-schema", "3", "--target-schema", "4", ok=False)


if __name__ == "__main__":
    unittest.main()
