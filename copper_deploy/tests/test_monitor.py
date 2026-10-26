#!/usr/bin/env python3
from __future__ import annotations

import datetime as dt
import importlib.util
import json
import os
import pathlib
import stat
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("copper_monitor", ROOT / "scripts/monitor.py")
MONITOR = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MONITOR)


def timestamp(hours: float = 0) -> str:
    value = dt.datetime(2026, 7, 15, 4, 0, tzinfo=dt.timezone.utc) + dt.timedelta(hours=hours)
    return value.isoformat().replace("+00:00", "Z")


def normal_snapshot() -> dict[str, object]:
    return {
        "observedAt": timestamp(),
        "siteFingerprint": "a" * 16,
        "application": {
            "services": {
                name: {"exists": True, "running": True, "health": "healthy", "startedAt": timestamp(-24)}
                for name in ("mysql", "backend", "web")
            },
            "webLiveness": True,
            "backendReadiness": True,
        },
        "database": {
            "available": True,
            "version": "8.4.10",
            "flywayVersion": "4",
            "readOnly": False,
            "superReadOnly": False,
            "writeProbe": True,
        },
        "storage": {
            "dataFilesystem": {"capacityBytes": 100 * 1024**3, "availableBytes": 80 * 1024**3},
            "hostFilesystem": {"capacityBytes": 100 * 1024**3, "availableBytes": 70 * 1024**3},
            "businessStorageWritable": True,
            "usage": {
                "databaseBytes": 1024,
                "attachmentBytes": 2048,
                "exportBytes": 0,
                "backupBytes": 4096,
                "logBytes": 512,
            },
        },
        "backup": {
            "statePresent": True,
            "lastSuccessAt": timestamp(-2),
            "lastBackupId": "b" * 32,
            "consecutiveFailures": 0,
            "currentResult": "SUCCESS",
            "alertResult": "NOT_REQUIRED",
            "targets": ["local", "nas"],
            "packageVerified": True,
            "activeKeyRegistered": True,
            "keyHandoverProved": True,
            "lastRestoreTestAt": timestamp(-48),
        },
        "certificate": {
            "available": True,
            "domain": "copper.example.test",
            "managedBy": "COPPER",
            "issuer": "CN=Test CA",
            "notAfter": timestamp(24 * 90),
            "daysRemaining": 90,
        },
        "time": {
            "hostTimezone": "Etc/UTC",
            "applicationTimezone": "Asia/Shanghai",
            "ntpSynchronized": True,
            "offsetMs": 0.4,
        },
        "policy": {"certificateWarningDays": 30, "certificateCriticalDays": 7},
    }


class MonitorEvaluationTest(unittest.TestCase):
    def test_normal_snapshot_has_four_independent_normal_layers(self) -> None:
        status = MONITOR.evaluate(normal_snapshot())
        self.assertEqual(status["overallLevel"], "NORMAL")
        self.assertEqual(
            {name: layer["level"] for name, layer in status["layers"].items()},
            {"liveness": "NORMAL", "readiness": "NORMAL", "writable": "NORMAL", "recoverable": "NORMAL"},
        )
        self.assertFalse(status["privacy"]["businessDetailsCollected"])
        encoded = json.dumps(status, ensure_ascii=False)
        self.assertNotIn("customer", encoded.lower())
        self.assertNotIn("password", encoded.lower())

    def test_database_failure_blocks_readiness_and_writable(self) -> None:
        snapshot = normal_snapshot()
        snapshot["database"].update({"available": False, "writeProbe": False})
        status = MONITOR.evaluate(snapshot)
        self.assertEqual(status["overallLevel"], "BLOCKED")
        self.assertEqual(status["layers"]["readiness"]["level"], "BLOCKED")
        self.assertEqual(status["layers"]["writable"]["level"], "BLOCKED")

    def test_disk_thresholds_are_absolute_and_percentage_based(self) -> None:
        snapshot = normal_snapshot()
        snapshot["storage"]["dataFilesystem"]["availableBytes"] = 15 * 1024**3
        status = MONITOR.evaluate(snapshot)
        item = next(value for value in status["signals"] if value["id"] == "storage.dataFilesystem")
        self.assertEqual(item["level"], "WARNING")

        snapshot["storage"]["dataFilesystem"]["availableBytes"] = 512 * 1024**2
        status = MONITOR.evaluate(snapshot)
        item = next(value for value in status["signals"] if value["id"] == "storage.dataFilesystem")
        self.assertEqual(item["level"], "BLOCKED")

    def test_stale_backup_and_missing_key_proof_block_recoverability(self) -> None:
        snapshot = normal_snapshot()
        snapshot["backup"]["lastSuccessAt"] = timestamp(-60)
        snapshot["backup"]["lastRestoreTestAt"] = None
        status = MONITOR.evaluate(snapshot)
        self.assertEqual(status["layers"]["recoverable"]["level"], "BLOCKED")
        backup = next(value for value in status["signals"] if value["id"] == "backup.freshness")
        self.assertEqual(backup["level"], "CRITICAL")

    def test_certificate_and_time_have_warning_critical_blocked_states(self) -> None:
        snapshot = normal_snapshot()
        snapshot["certificate"]["daysRemaining"] = 20
        snapshot["time"]["offsetMs"] = 6000
        status = MONITOR.evaluate(snapshot)
        cert = next(value for value in status["signals"] if value["id"] == "tls.certificate")
        clock = next(value for value in status["signals"] if value["id"] == "time.synchronization")
        self.assertEqual(cert["level"], "WARNING")
        self.assertEqual(clock["level"], "CRITICAL")

        snapshot["time"]["offsetMs"] = 60001
        status = MONITOR.evaluate(snapshot)
        clock = next(value for value in status["signals"] if value["id"] == "time.synchronization")
        self.assertEqual(clock["level"], "BLOCKED")

        snapshot["time"].update({
            "offsetMs": 0.1,
            "safetyLevel": "BLOCKED",
            "clockDiscontinuityMs": 120000,
        })
        status = MONITOR.evaluate(snapshot)
        clock = next(value for value in status["signals"] if value["id"] == "time.synchronization")
        self.assertEqual(clock["level"], "BLOCKED")
        self.assertEqual(clock["details"]["clockDiscontinuityMs"], 120000)

    def test_growth_forecast_uses_at_least_six_hour_baseline(self) -> None:
        snapshot = normal_snapshot()
        snapshot["storage"]["dataFilesystem"] = {"capacityBytes": 100 * 1024**3, "availableBytes": 4 * 1024**3}
        snapshot["storage"]["usage"] = {"databaseBytes": 20 * 1024**3}
        history = [{"observedAt": timestamp(-24), "measuredUsageBytes": 10 * 1024**3}]
        status = MONITOR.evaluate(snapshot, history)
        growth = next(value for value in status["signals"] if value["id"] == "storage.growth")
        self.assertEqual(growth["level"], "CRITICAL")
        self.assertLessEqual(growth["details"]["daysToCritical"], 7)

    def test_human_output_contains_cause_and_action(self) -> None:
        snapshot = normal_snapshot()
        snapshot["database"].update({"available": False, "writeProbe": False})
        output = MONITOR.human_output(MONITOR.evaluate(snapshot))
        self.assertIn("总体：阻断", output)
        self.assertIn("原因：", output)
        self.assertIn("建议：", output)


class MonitorPersistenceAndAlertTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.state = self.root / "state"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_state_and_history_are_root_only(self) -> None:
        snapshot = normal_snapshot()
        status = MONITOR.evaluate(snapshot)
        MONITOR.atomic_json(self.state / "status.json", status)
        MONITOR.append_history(self.state, status, snapshot)
        self.assertEqual(stat.S_IMODE((self.state / "status.json").stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE((self.state / "history.jsonl").stat().st_mode), 0o600)
        self.assertEqual(MONITOR.history_documents(self.state)[0]["overallLevel"], "NORMAL")

    def test_notification_failure_is_local_evidence_without_secret_values(self) -> None:
        status = MONITOR.evaluate(normal_snapshot())
        status["overallLevel"] = "WARNING"
        status["overallLabel"] = "预警"
        url = self.root / "url"
        key = self.root / "key"
        url.write_text("http://127.0.0.1:9/hook\n", encoding="utf-8")
        key.write_text("x" * 32 + "\n", encoding="utf-8")
        os.chmod(url, 0o600)
        os.chmod(key, 0o600)
        env = {
            "COPPER_MONITOR_ALERT_MODE": "webhook",
            "COPPER_MONITOR_ALERT_WEBHOOK_URL_FILE": str(url),
            "COPPER_MONITOR_ALERT_HMAC_KEY_FILE": str(key),
            "COPPER_MONITOR_ALERT_TIMEOUT_SECONDS": "1",
        }
        with mock.patch.dict(os.environ, {"COPPER_TEST_MODE": "1", "COPPER_MONITOR_TEST_WEBHOOK_FAIL": "1"}, clear=False):
            MONITOR.send_notification(status, env, None)
        self.assertEqual(status["notification"]["result"], "FAILED")
        encoded = json.dumps(status, ensure_ascii=False)
        self.assertNotIn(str(url), encoded)
        self.assertNotIn("x" * 32, encoded)
        self.assertTrue(any(value["id"] == "notification.delivery" for value in status["signals"]))

    def test_insecure_history_permissions_are_rejected(self) -> None:
        self.state.mkdir(mode=0o700)
        history = self.state / "history.jsonl"
        history.write_text("{}\n", encoding="utf-8")
        os.chmod(history, 0o644)
        with self.assertRaises(MONITOR.MonitorError):
            MONITOR.history_documents(self.state)


class MonitorUtilityTest(unittest.TestCase):
    def test_time_offset_parser_supports_common_units(self) -> None:
        self.assertEqual(MONITOR.parse_offset_ms("Offset: +500us"), 0.5)
        self.assertEqual(MONITOR.parse_offset_ms("Offset: -1.5ms"), -1.5)
        self.assertEqual(MONITOR.parse_offset_ms("Offset: +2s"), 2000)
        self.assertEqual(MONITOR.parse_chrony_offset_ms("Last offset     : -0.000012345 seconds\n"), -0.012345)

    def test_fault_overrides_are_rejected_outside_test_mode(self) -> None:
        with mock.patch.dict(os.environ, {"COPPER_MONITOR_TEST_DB_UNAVAILABLE": "1", "COPPER_TEST_MODE": "0"}, clear=False):
            with self.assertRaises(MONITOR.MonitorError):
                MONITOR.test_overrides(normal_snapshot())

    def test_failed_notification_is_retried_on_next_check(self) -> None:
        current = MONITOR.evaluate(normal_snapshot())
        current["overallLevel"] = "WARNING"
        previous = {
            "overallLevel": "WARNING",
            "notification": {"result": "FAILED", "attemptedAt": timestamp(-1)},
        }
        self.assertTrue(MONITOR.should_notify(current, previous, 24))


if __name__ == "__main__":
    unittest.main()
