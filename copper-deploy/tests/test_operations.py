#!/usr/bin/env python3
from __future__ import annotations

import datetime as dt
import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("copper_operations", ROOT / "scripts/operations.py")
OPERATIONS = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(OPERATIONS)
RESTORE_SPEC = importlib.util.spec_from_file_location("copper_restore_metadata", ROOT / "scripts/restore-metadata.py")
RESTORE = importlib.util.module_from_spec(RESTORE_SPEC)
assert RESTORE_SPEC.loader is not None
RESTORE_SPEC.loader.exec_module(RESTORE)
UPGRADE_SPEC = importlib.util.spec_from_file_location("copper_upgrade_run", ROOT / "scripts/upgrade-run.py")
UPGRADE = importlib.util.module_from_spec(UPGRADE_SPEC)
assert UPGRADE_SPEC.loader is not None
UPGRADE_SPEC.loader.exec_module(UPGRADE)


class OperationsTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.var = self.root / "var"
        self.backup = self.root / "backups"
        self.restore = self.root / "restores"
        for path in (self.var / "backup-jobs", self.var / "logs", self.backup, self.restore):
            path.mkdir(parents=True, exist_ok=True)
        self.env = self.root / "copper.env"
        self.env.write_text(
            "\n".join(
                [
                    f"COPPER_VAR_DIR={self.var}",
                    f"COPPER_BACKUP_DIR={self.backup}",
                    f"COPPER_RESTORE_ROOT={self.restore}",
                    "COPPER_BACKUP_TARGETS=local,nas",
                    "COPPER_DEPLOY_MODE=external-proxy",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        self.env.chmod(0o600)
        self.now = dt.datetime(2026, 7, 15, 6, 0, tzinfo=dt.timezone.utc)
        status = {
            "formatVersion": "copper-backup-job-status/v1",
            "current": {"result": "SUCCESS", "targets": ["local", "nas"]},
            "lastSuccess": {
                "backupId": "a" * 32,
                "sha256": "b" * 64,
                "bytes": 1024,
                "completedAt": "2026-07-15T04:00:00Z",
                "targets": ["local", "nas"],
            },
        }
        path = self.var / "backup-jobs" / "status.json"
        path.write_text(json.dumps(status), encoding="utf-8")
        path.chmod(0o600)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def policy(self) -> dict[str, object]:
        return OPERATIONS.load_policy(ROOT / "config" / "operations-policy.json")

    @mock.patch.object(OPERATIONS, "runtime_log_enforcement", return_value={"status": "PASS", "services": {}})
    @mock.patch.object(OPERATIONS, "static_enforcement", return_value={"status": "PASS", "checks": {}})
    @mock.patch.object(OPERATIONS.shutil, "disk_usage", return_value=mock.Mock(total=100 * 1024**3, free=80 * 1024**3))
    def test_fresh_off_host_backup_is_normal_without_secret_or_business_data(self, *_: object) -> None:
        drill = self.restore / "restore-a"
        drill.mkdir()
        state = {
            "formatVersion": "copper-restore-state/v1",
            "status": "SWITCHED",
            "createdAt": "2026-07-15T03:50:00Z",
            "validatedAt": "2026-07-15T03:57:00Z",
            "switchedAt": "2026-07-15T03:58:00Z",
        }
        (drill / "state.json").write_text(json.dumps(state), encoding="utf-8")
        (drill / "state.json").chmod(0o600)
        document = OPERATIONS.collect(ROOT, self.env, self.policy(), self.now)
        self.assertEqual(document["overallLevel"], "NORMAL")
        self.assertEqual(document["recovery"]["backup"]["localRecoveryPoint"], "TARGET_MET")
        self.assertEqual(document["recovery"]["backup"]["hostLossRecoveryPoint"], "TARGET_MET")
        encoded = json.dumps(document, ensure_ascii=False).lower()
        self.assertNotIn("password", encoded)
        self.assertFalse(document["privacy"]["businessDetailsCollected"])

    @mock.patch.object(OPERATIONS, "runtime_log_enforcement", return_value={"status": "PASS", "services": {}})
    @mock.patch.object(OPERATIONS, "static_enforcement", return_value={"status": "PASS", "checks": {}})
    @mock.patch.object(OPERATIONS.shutil, "disk_usage", return_value=mock.Mock(total=100 * 1024**3, free=512 * 1024**2))
    def test_blocked_capacity_and_log_overflow_are_not_reported_normal(self, *_: object) -> None:
        (self.var / "logs" / "oversize.log").write_bytes(b"x" * 16)
        policy = self.policy()
        policy["logClasses"]["applicationFile"]["maxTotalBytes"] = 8
        document = OPERATIONS.collect(ROOT, self.env, policy, self.now)
        self.assertEqual(document["overallLevel"], "BLOCKED")
        self.assertEqual(document["capacity"]["level"], "BLOCKED")

    def test_backup_age_distinguishes_target_grace_and_breach(self) -> None:
        policy = self.policy()
        target = OPERATIONS.backup_objective(self.var, {"COPPER_BACKUP_TARGETS": "local,nas"}, self.now, policy)
        self.assertEqual(target["localRecoveryPoint"], "TARGET_MET")
        self.assertTrue(target["offHostTargetConfigured"])
        self.assertTrue(target["offHostTargetEvidenced"])
        state_path = self.var / "backup-jobs" / "status.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["lastSuccess"]["completedAt"] = "2026-07-14T05:00:00Z"
        state_path.write_text(json.dumps(state), encoding="utf-8")
        target = OPERATIONS.backup_objective(self.var, {"COPPER_BACKUP_TARGETS": "local"}, self.now, policy)
        self.assertEqual(target["localRecoveryPoint"], "GRACE_WARNING")
        self.assertEqual(target["hostLossRecoveryPoint"], "GRACE_WARNING")

    def test_configured_off_host_target_does_not_claim_unverified_copy(self) -> None:
        state_path = self.var / "backup-jobs" / "status.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["lastSuccess"]["targets"] = ["local"]
        state_path.write_text(json.dumps(state), encoding="utf-8")
        target = OPERATIONS.backup_objective(
            self.var, {"COPPER_BACKUP_TARGETS": "local,nas"}, self.now, self.policy()
        )
        self.assertTrue(target["offHostTargetConfigured"])
        self.assertFalse(target["offHostTargetEvidenced"])
        self.assertEqual(target["hostLossRecoveryPoint"], "NOT_EVIDENCED")

    @mock.patch.object(OPERATIONS, "runtime_log_enforcement", return_value={"status": "PASS", "services": {}})
    @mock.patch.object(OPERATIONS, "static_enforcement", return_value={"status": "PASS", "checks": {}})
    @mock.patch.object(OPERATIONS.shutil, "disk_usage", return_value=mock.Mock(total=100 * 1024**3, free=80 * 1024**3))
    def test_configured_but_unverified_off_host_target_is_warning(self, *_: object) -> None:
        state_path = self.var / "backup-jobs" / "status.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["lastSuccess"]["targets"] = ["local"]
        state_path.write_text(json.dumps(state), encoding="utf-8")
        drill = self.restore / "restore-a"
        drill.mkdir()
        (drill / "state.json").write_text(
            json.dumps(
                {
                    "formatVersion": "copper-restore-state/v1",
                    "status": "SWITCHED",
                    "createdAt": "2026-07-15T03:50:00Z",
                    "validatedAt": "2026-07-15T03:57:00Z",
                    "switchedAt": "2026-07-15T03:58:00Z",
                }
            ),
            encoding="utf-8",
        )
        (drill / "state.json").chmod(0o600)
        document = OPERATIONS.collect(ROOT, self.env, self.policy(), self.now)
        self.assertEqual(document["overallLevel"], "WARNING")

    def test_restore_drill_without_business_validation_is_not_evidence(self) -> None:
        drill = self.restore / "restore-unvalidated"
        drill.mkdir()
        (drill / "state.json").write_text(
            json.dumps(
                {
                    "formatVersion": "copper-restore-state/v1",
                    "status": "SWITCHED",
                    "createdAt": "2026-07-15T03:50:00Z",
                    "switchedAt": "2026-07-15T03:58:00Z",
                }
            ),
            encoding="utf-8",
        )
        (drill / "state.json").chmod(0o600)
        self.assertEqual(OPERATIONS.latest_restore_drill(self.restore, self.now)["status"], "NO_FULL_DRILL")

    def test_all_six_incident_checklists_have_stop_recovery_and_escalation(self) -> None:
        policy = self.policy()
        for fault in ("database", "disk", "backup", "certificate", "time", "power"):
            result = OPERATIONS.checklist(policy, fault)
            self.assertTrue(result["stopRule"])
            self.assertTrue(result["commands"])
            self.assertTrue(result["recoveryGate"])
            self.assertTrue(result["escalation"])

    def test_policy_keeps_service_models_separate_and_no_sla_claim(self) -> None:
        policy = self.policy()
        models = policy["recoveryObjectives"]["serviceModels"]
        self.assertEqual([item["id"] for item in models], [
            "software-self-hosted", "standard-deployment", "annual-maintenance", "managed-operations"
        ])
        self.assertEqual(policy["recoveryObjectives"]["engineeringBaseline"]["claimBoundary"], "engineering-target-not-commercial-sla")

    def test_restore_and_upgrade_event_streams_rotate_at_five_mib(self) -> None:
        restore_log = self.root / "restore" / "events.jsonl"
        restore_log.parent.mkdir()
        restore_log.write_bytes(b"x" * (5 * 1024 * 1024 + 1))
        RESTORE.command_event(
            mock.Mock(log=str(restore_log), restore_id="restore-20260715", action="stage", result="passed", message="ok")
        )
        self.assertTrue((restore_log.parent / "events.jsonl.1").is_file())
        self.assertLess(restore_log.stat().st_size, 1024)

        run_dir = self.root / "upgrade"
        run_dir.mkdir()
        upgrade_log = run_dir / "events.jsonl"
        upgrade_log.write_bytes(b"x" * (5 * 1024 * 1024 + 1))
        UPGRADE.append_event(run_dir, "upgrade-20260715", "migrate", "passed", "ok")
        self.assertTrue((run_dir / "events.jsonl.1").is_file())
        self.assertLess(upgrade_log.stat().st_size, 1024)

    def test_status_cli_passes_loaded_policy_to_collection(self) -> None:
        status = {"overallLevel": "WARNING"}
        argv = ["operations.py", "--root", str(ROOT), "--env-file", str(self.env), "status", "--json"]
        with mock.patch.object(sys, "argv", argv), mock.patch.object(
            OPERATIONS, "collect", return_value=status
        ) as collect_call, mock.patch("builtins.print"):
            self.assertEqual(OPERATIONS.main(), 0)
        self.assertEqual(collect_call.call_args.args[2]["formatVersion"], "copper-operations-policy/v1")


if __name__ == "__main__":
    unittest.main()
