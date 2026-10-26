#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import io
import importlib.util
import importlib.machinery
import json
import pathlib
import tarfile
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("backup_key", ROOT / "scripts/backup-key.py")
assert SPEC and SPEC.loader
backup_key = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup_key)
OFFLINE_LOADER = importlib.machinery.SourceFileLoader(
    "offline_key_for_backup_key_tests", str(ROOT / "copper-key-offline")
)
OFFLINE_SPEC = importlib.util.spec_from_loader(OFFLINE_LOADER.name, OFFLINE_LOADER)
assert OFFLINE_SPEC and OFFLINE_SPEC.loader
offline_key = importlib.util.module_from_spec(OFFLINE_SPEC)
OFFLINE_SPEC.loader.exec_module(offline_key)

RECIPIENT_1 = "age1" + "a" * 58
RECIPIENT_2 = "age1" + "b" * 58


class BackupKeyLifecycleTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.registry = self.root / "keys" / "registry.json"
        self.env = self.root / "copper.env"
        self.env.write_text(f"COPPER_BACKUP_AGE_RECIPIENT={RECIPIENT_1}\n", encoding="utf-8")
        self.env.chmod(0o600)
        self.challenge_dir = self.root / "challenges"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def handover(recipient: str = RECIPIENT_1) -> argparse.Namespace:
        return argparse.Namespace(
            registry="",
            env_file="",
            site_id="customer-site",
            handover_sha256=hashlib.sha256((recipient + " handover").encode()).hexdigest(),
            customer_confirmed_by="Customer Owner",
            witness_confirmed_by="Independent Witness",
        )

    @staticmethod
    def fake_age_run(command: list[str], *, input: bytes = b"", **_kwargs: object) -> mock.Mock:
        if "--output" in command:
            pathlib.Path(command[command.index("--output") + 1]).write_bytes(input)
        return mock.Mock(returncode=0)

    def register(self) -> None:
        args = self.handover()
        args.registry = str(self.registry)
        args.env_file = str(self.env)
        with mock.patch.object(backup_key.subprocess, "run", side_effect=self.fake_age_run), mock.patch("builtins.print"):
            backup_key.command_register(args)

    def test_full_rotation_retains_history_and_requires_offline_proof(self) -> None:
        self.register()
        args = self.handover(RECIPIENT_2)
        args.registry = str(self.registry)
        args.env_file = str(self.env)
        args.new_recipient = RECIPIENT_2
        args.challenge_dir = str(self.challenge_dir)
        with mock.patch.object(backup_key.subprocess, "run", side_effect=self.fake_age_run), mock.patch("builtins.print"):
            backup_key.command_prepare_rotation(args)

        new_key_id = backup_key.key_id(RECIPIENT_2)
        challenge = self.challenge_dir / f"rotation-{new_key_id}.age"
        proof = challenge.read_text(encoding="ascii").strip().rsplit(":", 1)[1]
        proof_file = self.root / "proof.txt"
        proof_file.write_text("wrong-proof-value-with-enough-characters-000000000000\n", encoding="ascii")
        proof_file.chmod(0o600)
        activate = argparse.Namespace(
            registry=str(self.registry),
            env_file=str(self.env),
            site_id="customer-site",
            key_id=new_key_id,
            proof_file=str(proof_file),
            confirm=new_key_id,
        )
        with self.assertRaises(backup_key.KeyLifecycleError):
            backup_key.command_activate_rotation(activate)

        proof_file.write_text(proof + "\n", encoding="ascii")
        with mock.patch("builtins.print"):
            backup_key.command_activate_rotation(activate)

        document = json.loads(self.registry.read_text(encoding="utf-8"))
        keys = {item["keyId"]: item for item in document["keys"]}
        self.assertEqual(document["activeKeyId"], new_key_id)
        self.assertEqual(keys[new_key_id]["status"], "ACTIVE")
        self.assertEqual(keys[backup_key.key_id(RECIPIENT_1)]["status"], "RETIRED")
        self.assertIn(f"COPPER_BACKUP_AGE_RECIPIENT={RECIPIENT_2}", self.env.read_text(encoding="utf-8"))
        self.assertFalse(challenge.exists())

        receipt = self.root / "receipt.json"
        backup_dir = self.root / "backups"
        backup_dir.mkdir()
        backup_id = "a" * 32
        proof = "c" * 64
        proof_sha = hashlib.sha256(proof.encode("ascii")).hexdigest()
        archive = backup_dir / f"customer-site_20260714T120000Z_{backup_id}_manual.copperbak"
        envelope = json.dumps(
            {
                "formatVersion": "copper-backup/v1",
                "backupId": backup_id,
                "encryption": {
                    "format": "age",
                    "recipientFingerprint": new_key_id,
                },
                "offlineVerification": {
                    "format": "copper-offline-key-proof/v1",
                    "proofSha256": proof_sha,
                },
            }
        ).encode("utf-8")
        with tarfile.open(archive, "w:") as package:
            for name, content in (
                ("envelope.json", envelope),
                ("payload.tar.zst.age", b"encrypted"),
                ("checksums.sha256", b"test-only\n"),
                ("README.txt", b"test-only\n"),
            ):
                member = tarfile.TarInfo(name)
                member.size = len(content)
                member.mode = 0o600
                package.addfile(member, io.BytesIO(content))
        archive_sha = hashlib.sha256(archive.read_bytes()).hexdigest()
        archive.with_name(archive.name + ".sha256").write_text(
            f"{archive_sha}  {archive.name}\n", encoding="ascii"
        )
        receipt.write_text(
            json.dumps(
                {
                    "formatVersion": "copper-key-backup-verification/v1",
                    "keyId": new_key_id,
                    "backupId": backup_id,
                    "backupSha256": archive_sha,
                    "proof": proof,
                    "proofSha256": proof_sha,
                    "verifiedAt": "2026-07-14T12:00:00Z",
                    "result": "PASSED",
                }
            ),
            encoding="utf-8",
        )
        receipt.chmod(0o600)
        forged = json.loads(receipt.read_text(encoding="utf-8"))
        forged["proof"] = "d" * 64
        forged["proofSha256"] = hashlib.sha256(forged["proof"].encode("ascii")).hexdigest()
        receipt.write_text(json.dumps(forged), encoding="utf-8")
        with self.assertRaises(backup_key.KeyLifecycleError):
            backup_key.command_record_restore_test(
                argparse.Namespace(
                    registry=str(self.registry),
                    env_file=str(self.env),
                    site_id="customer-site",
                    receipt=str(receipt),
                    backup_dir=str(backup_dir),
                )
            )
        forged["proof"] = proof
        forged["proofSha256"] = proof_sha
        receipt.write_text(json.dumps(forged), encoding="utf-8")
        with mock.patch("builtins.print"):
            backup_key.command_record_restore_test(
                argparse.Namespace(
                    registry=str(self.registry),
                    env_file=str(self.env),
                    site_id="customer-site",
                    receipt=str(receipt),
                    backup_dir=str(backup_dir),
                )
            )
        backup_key.assert_registry_ready(
            backup_key.load_registry(self.registry), self.env, require_restore_test=True
        )

    def test_two_person_handover_and_permissions_are_enforced(self) -> None:
        args = self.handover()
        args.registry = str(self.registry)
        args.env_file = str(self.env)
        args.witness_confirmed_by = args.customer_confirmed_by.lower()
        with mock.patch.object(backup_key.subprocess, "run", side_effect=self.fake_age_run):
            with self.assertRaises(backup_key.KeyLifecycleError):
                backup_key.command_register(args)

        self.env.chmod(0o644)
        args.witness_confirmed_by = "Independent Witness"
        with self.assertRaises(backup_key.KeyLifecycleError):
            backup_key.command_register(args)

    def test_server_private_key_audit_records_failure_and_recovery(self) -> None:
        self.register()
        planted = self.root / "server-private-key.txt"
        planted.write_text("# AGE-SECRET-KEY-TEST-ONLY\n", encoding="utf-8")
        planted.chmod(0o600)
        args = argparse.Namespace(
            registry=str(self.registry),
            env_file=str(self.env),
            site_id="customer-site",
            scan_root=[str(self.root)],
        )
        with self.assertRaises(backup_key.KeyLifecycleError):
            backup_key.command_audit(args)
        document = json.loads(self.registry.read_text(encoding="utf-8"))
        self.assertEqual(document["lastServerPrivateKeyScan"]["result"], "FAILED")

        planted.unlink()
        with mock.patch("builtins.print"):
            backup_key.command_audit(args)
        document = json.loads(self.registry.read_text(encoding="utf-8"))
        self.assertEqual(document["lastServerPrivateKeyScan"]["result"], "PASSED")

    def test_offline_receipt_requires_all_exact_recovery_components(self) -> None:
        components = {
            "webImage": "sha256:" + "1" * 64,
            "backendImage": "sha256:" + "2" * 64,
            "mysqlImage": "sha256:" + "3" * 64,
        }
        self.assertEqual(offline_key.verified_components({"components": components}), components)
        with self.assertRaises(offline_key.OfflineKeyError):
            offline_key.verified_components({"components": {"webImage": components["webImage"]}})
        broken = dict(components)
        broken["mysqlImage"] = "latest"
        with self.assertRaises(offline_key.OfflineKeyError):
            offline_key.verified_components({"components": broken})


if __name__ == "__main__":
    unittest.main()
