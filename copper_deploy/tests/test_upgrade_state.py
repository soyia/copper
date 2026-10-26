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


ROOT = pathlib.Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "upgrade-state.py"


class UpgradeStateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="copper-upgrade-state-")
        self.root = pathlib.Path(self.temporary.name)
        self.backup_dir = self.root / "backups"
        self.backup_dir.mkdir()
        self.archive = self.backup_dir / f"site_20260714T000000Z_{'a' * 32}_manual.copperbak"
        self.archive.write_bytes(b"encrypted-backup")
        digest = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        (self.archive.parent / f"{self.archive.name}.sha256").write_text(
            f"{digest}  {self.archive.name}\n", encoding="ascii"
        )
        self.last_success = self.backup_dir / "last-success.json"
        self.last_success.write_text(json.dumps({
            "formatVersion": "copper-backup/v1",
            "backupId": "a" * 32,
            "completedAt": "2026-07-14T00:01:00Z",
            "archive": self.archive.name,
            "size": self.archive.stat().st_size,
            "sha256": digest,
            "recipientFingerprint": "b" * 16,
        }), encoding="utf-8")
        self.contract = self.root / "contract.json"
        self.manifest = self.root / "release.env"
        self.ready = self.root / "ready.json"
        self.matrix = self.root / "matrix.json"
        self.manifest.write_text("COPPER_RELEASE_MANIFEST_FORMAT=copper-release/v1\n", encoding="utf-8")
        self.ready.write_text('{"formatVersion":"copper-delivery-ready/v1"}\n', encoding="utf-8")
        self.matrix.write_text('{"format":"copper-upgrade-matrix/v1"}\n', encoding="utf-8")
        self.deploy_archive = self.root / "deploy.tar.gz"
        self.release_notes = self.root / "notes.md"
        self.acme_archive = self.root / "acme.tar.gz"
        self.deploy_archive.write_bytes(b"deploy")
        self.release_notes.write_bytes(b"notes")
        self.acme_archive.write_bytes(b"acme")
        manifest_sha = hashlib.sha256(self.manifest.read_bytes()).hexdigest()
        matrix_sha = hashlib.sha256(self.matrix.read_bytes()).hexdigest()
        self.contract.write_text(json.dumps({
            "manifest": str(self.manifest),
            "manifestSha256": manifest_sha,
            "matrix": str(self.matrix),
            "matrixSha256": matrix_sha,
            "targetVersion": "0.2.0-rc.1",
            "targetSchema": "4",
            "minimumCpu": 2,
            "minimumMemoryGiB": 4,
            "minimumFreeDiskGiB": 20,
            "mysqlVersion": "8.4.10",
            "supportedOs": "ubuntu-24.04",
            "supportedArch": "amd64",
            "artifacts": {
                "deployArchive": {"path": str(self.deploy_archive), "sha256": hashlib.sha256(self.deploy_archive.read_bytes()).hexdigest()},
                "releaseNotes": {"path": str(self.release_notes), "sha256": hashlib.sha256(self.release_notes.read_bytes()).hexdigest()},
                "acmeArchive": {"path": str(self.acme_archive), "sha256": hashlib.sha256(self.acme_archive.read_bytes()).hexdigest()},
            },
            "source": {
                "version": "0.1.0-m2-rc.2", "schema": "3", "mode": "retained-sidecar",
                "downtimeClass": "maintenance-window", "rollbackMode": "verified-backup",
            },
        }), encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_tool(self, *arguments: str, ok: bool = True) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [sys.executable, str(TOOL), *arguments], text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
        )
        if ok and result.returncode != 0:
            self.fail(result.stderr)
        if not ok and result.returncode == 0:
            self.fail("state tool unexpectedly passed")
        return result

    def common(self) -> list[str]:
        return [
            "--contract-json", str(self.contract),
            "--current-version", "0.1.0-m2-rc.2",
            "--current-schema", "3",
            "--available-bytes", str(30 * 1024**3),
            "--required-bytes", str(20 * 1024**3),
            "--checked-at", "2026-07-14T00:02:00Z",
        ]

    def test_prepared_record_binds_contract_and_verified_backup(self) -> None:
        output = self.root / "upgrades" / "prepared.json"
        result = self.run_tool(
            "prepare", *self.common(),
            "--last-success", str(self.last_success),
            "--prepared-at", "2026-07-14T00:03:00Z",
            "--verified-at", "2026-07-14T00:03:00Z",
            "--output", str(output),
        )
        document = json.loads(result.stdout)
        self.assertEqual(document["status"], "PREPARED")
        self.assertEqual(document["backup"]["backupId"], "a" * 32)
        self.assertEqual(document["manifestSha256"], hashlib.sha256(self.manifest.read_bytes()).hexdigest())
        self.assertTrue(pathlib.Path(document["releaseContract"]["manifest"]).is_file())
        self.assertEqual(
            document["releaseContract"]["readySha256"],
            hashlib.sha256(self.ready.read_bytes()).hexdigest(),
        )
        self.assertEqual(pathlib.Path(document["releaseContract"]["ready"]).name, "ready.json")
        self.assertEqual(os.stat(output).st_mode & 0o777, 0o400)
        status = self.run_tool("status", "--state", str(output), "--json")
        self.assertEqual(json.loads(status.stdout)["status"], "PREPARED")
        pathlib.Path(document["releaseContract"]["matrix"]).chmod(0o600)
        pathlib.Path(document["releaseContract"]["matrix"]).write_text("tampered", encoding="utf-8")
        self.run_tool("status", "--state", str(output), "--json", ok=False)
        self.run_tool(
            "prepare", *self.common(),
            "--last-success", str(self.last_success),
            "--prepared-at", "2026-07-14T00:04:00Z",
            "--verified-at", "2026-07-14T00:04:00Z",
            "--output", str(output),
            ok=False,
        )

    def test_missing_ready_receipt_is_rejected_before_prepared(self) -> None:
        self.ready.unlink()
        output = self.root / "upgrades" / "prepared.json"
        self.run_tool(
            "prepare", *self.common(),
            "--last-success", str(self.last_success),
            "--prepared-at", "2026-07-14T00:03:00Z",
            "--verified-at", "2026-07-14T00:03:00Z",
            "--output", str(output),
            ok=False,
        )
        self.assertFalse(output.exists())
        self.assertFalse((output.parent / "prepared-contract").exists())

    def test_tampered_archive_is_rejected(self) -> None:
        self.archive.write_bytes(b"tampered")
        self.run_tool(
            "prepare", *self.common(),
            "--last-success", str(self.last_success),
            "--prepared-at", "2026-07-14T00:03:00Z",
            "--verified-at", "2026-07-14T00:03:00Z",
            "--output", str(self.root / "prepared.json"),
            ok=False,
        )

    def test_backup_readiness_requires_matching_success_state_and_sidecar(self) -> None:
        digest = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        status = self.root / "status.json"
        status.write_text(json.dumps({
            "formatVersion": "copper-backup-job-status/v1",
            "consecutiveFailures": 0,
            "current": {"result": "SUCCESS"},
            "lastSuccess": {"backupId": "a" * 32, "sha256": digest},
        }), encoding="utf-8")
        status.chmod(0o600)
        self.last_success.chmod(0o600)
        self.run_tool(
            "check-backup-readiness",
            "--job-status", str(status),
            "--last-success", str(self.last_success),
        )
        document = json.loads(status.read_text(encoding="utf-8"))
        document["consecutiveFailures"] = 1
        status.write_text(json.dumps(document), encoding="utf-8")
        status.chmod(0o600)
        self.run_tool(
            "check-backup-readiness",
            "--job-status", str(status),
            "--last-success", str(self.last_success),
            ok=False,
        )


if __name__ == "__main__":
    unittest.main()
