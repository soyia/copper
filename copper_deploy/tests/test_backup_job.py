#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import hmac
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("backup_job", ROOT / "scripts/backup-job.py")
assert SPEC and SPEC.loader
backup_job = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup_job)


class BackupJobTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.state = self.root / "state"
        self.job_index = 0

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def next_job(self) -> str:
        self.job_index += 1
        return f"20260714T1200{self.job_index:02d}Z-{self.job_index:012x}"

    def start(self, purpose: str = "scheduled") -> str:
        job_id = self.next_job()
        backup_job.command_start(
            argparse.Namespace(
                state_dir=str(self.state),
                job_id=job_id,
                purpose=purpose,
                targets="local,external,nas,s3",
                started_at="2026-07-14T12:00:00Z",
            )
        )
        return job_id

    def fail(self, error_code: str = "SPACE_INSUFFICIENT") -> str:
        job_id = self.start()
        with mock.patch("builtins.print"):
            backup_job.command_finish(
                argparse.Namespace(
                    state_dir=str(self.state),
                    job_id=job_id,
                    result="FAILED",
                    error_code=error_code,
                    finished_at="2026-07-14T12:01:00Z",
                    last_success="",
                )
            )
        return job_id

    def status(self) -> dict[str, object]:
        return json.loads((self.state / "status.json").read_text(encoding="utf-8"))

    def test_consecutive_failures_and_recovery_are_durable(self) -> None:
        self.fail("SPACE_INSUFFICIENT")
        self.fail("TARGET_TIMEOUT")
        self.assertEqual(self.status()["consecutiveFailures"], 2)

        last_success = self.root / "last-success.json"
        last_success.write_text(
            json.dumps(
                {
                    "formatVersion": "copper-backup/v1",
                    "backupId": "a" * 32,
                    "sha256": "b" * 64,
                    "size": 1234,
                    "completedAt": "2026-07-14T12:02:00Z",
                }
            ),
            encoding="utf-8",
        )
        job_id = self.start()
        with mock.patch("builtins.print") as output:
            backup_job.command_finish(
                argparse.Namespace(
                    state_dir=str(self.state),
                    job_id=job_id,
                    result="SUCCESS",
                    error_code="",
                    finished_at="2026-07-14T12:02:00Z",
                    last_success=str(last_success),
                )
            )
        self.assertEqual(output.call_args.args[0], "BACKUP_RECOVERED")
        self.assertEqual(self.status()["consecutiveFailures"], 0)
        self.assertEqual(self.status()["current"]["notificationEvent"], "BACKUP_RECOVERED")
        self.assertEqual(self.status()["lastSuccess"]["targets"], ["local", "external", "nas", "s3"])

    def test_error_classification_is_stable(self) -> None:
        cases = {
            "磁盘空间不足": "SPACE_INSUFFICIENT",
            "request timed out": "TARGET_TIMEOUT",
            "Permission denied": "PERMISSION_DENIED",
            "age 接收公钥错误": "KEY_CONFIGURATION_ERROR",
            "SHA-256 校验失败": "VERIFICATION_FAILED",
            "服务健康检查失败": "SERVICE_RECOVERY_FAILED",
        }
        for message, expected in cases.items():
            with self.subTest(message=message):
                self.assertEqual(backup_job.classify(message, 1), expected)
        self.assertEqual(backup_job.classify("", 143), "INTERRUPTED")

    def test_webhook_payload_is_minimal_and_hmac_signed(self) -> None:
        self.fail("TARGET_TIMEOUT")
        signing_key = b"k" * 48
        captured: dict[str, object] = {}

        def fake_urlopen(request: object, timeout: int) -> mock.MagicMock:
            captured["body"] = request.data
            captured["signature"] = request.headers["X-copper-signature"]
            captured["timeout"] = timeout
            response = mock.MagicMock()
            response.__enter__.return_value.status = 204
            return response

        url_file = self.root / "url"
        key_file = self.root / "hmac"
        url_file.write_text("http://127.0.0.1:18106/alerts\n", encoding="utf-8")
        key_file.write_bytes(signing_key)
        url_file.chmod(0o600)
        key_file.chmod(0o600)
        with mock.patch.object(backup_job.urllib.request, "urlopen", side_effect=fake_urlopen), mock.patch(
            "builtins.print"
        ):
            backup_job.command_send(
                argparse.Namespace(
                    state_dir=str(self.state),
                    site_id="customer-production-site",
                    url_file=str(url_file),
                    hmac_key_file=str(key_file),
                    timeout=2,
                    test_mode=True,
                )
            )

        body = captured["body"]
        assert isinstance(body, bytes)
        payload = json.loads(body)
        self.assertEqual(payload["formatVersion"], "copper-backup-alert/v1")
        self.assertEqual(payload["event"], "BACKUP_FAILED")
        self.assertEqual(payload["errorCode"], "TARGET_TIMEOUT")
        self.assertNotIn("customer-production-site", body.decode("utf-8"))
        for forbidden in ("archive", "filename", "recipient", "key", "business", "content"):
            self.assertNotIn(forbidden, body.decode("utf-8").lower())
        expected = "sha256=" + hmac.new(signing_key, body, hashlib.sha256).hexdigest()
        self.assertEqual(captured["signature"], expected)

    def test_alert_failure_and_credential_permissions_do_not_mutate_job_result(self) -> None:
        self.fail("BACKUP_FAILED")
        url_file = self.root / "url"
        key_file = self.root / "hmac"
        url_file.write_text("https://alerts.invalid.test/copper\n", encoding="utf-8")
        key_file.write_bytes(b"x" * 48)
        url_file.chmod(0o600)
        key_file.chmod(0o600)
        with mock.patch.object(backup_job.urllib.request, "urlopen", side_effect=OSError("offline")), mock.patch.object(
            backup_job.time, "sleep"
        ):
            with self.assertRaises(backup_job.BackupJobError):
                backup_job.command_send(
                    argparse.Namespace(
                        state_dir=str(self.state),
                        site_id="customer-site",
                        url_file=str(url_file),
                        hmac_key_file=str(key_file),
                        timeout=1,
                        test_mode=False,
                    )
                )
        self.assertEqual(self.status()["current"]["result"], "FAILED")

        key_file.chmod(0o644)
        with self.assertRaises(backup_job.BackupJobError):
            backup_job.command_send(
                argparse.Namespace(
                    state_dir=str(self.state),
                    site_id="customer-site",
                    url_file=str(url_file),
                    hmac_key_file=str(key_file),
                    timeout=1,
                    test_mode=False,
                )
            )


if __name__ == "__main__":
    unittest.main()
