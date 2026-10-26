from __future__ import annotations

import importlib.machinery
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parent.parent
LOADER = importlib.machinery.SourceFileLoader(
    "copper_disaster_recovery_test_module", str(ROOT / "copper-recover-from-mac")
)
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
assert SPEC and SPEC.loader
recovery = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = recovery
SPEC.loader.exec_module(recovery)


class DisasterRecoveryTest(unittest.TestCase):
    def private_file(self, path: pathlib.Path, content: bytes) -> pathlib.Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        os.chmod(path, 0o600)
        return path

    def sidecar(self, path: pathlib.Path) -> pathlib.Path:
        return self.private_file(
            pathlib.Path(str(path) + ".sha256"),
            f"{recovery.manager.sha256_file(path)}  {path.name}\n".encode("ascii"),
        )

    def evidence_tree(self, root: pathlib.Path) -> pathlib.Path:
        site_id = "copper-test-03"
        stamp = "20260719-172218"
        primary = root / "primary" / site_id
        secondary = root / "secondary" / site_id
        identity = b"# created: test\nAGE-SECRET-KEY-TEST\n"
        self.private_file(primary / "copper-age-identity.txt", identity)
        self.private_file(secondary / "copper-age-identity.txt", identity)
        backup_id = "1" * 32
        archive = self.private_file(
            primary / "backups" / f"{site_id}_20260719T092130Z_{backup_id}_manual.copperbak",
            b"encrypted-backup",
        )
        self.sidecar(archive)
        secondary_archive = self.private_file(secondary / "backups" / archive.name, archive.read_bytes())
        self.sidecar(secondary_archive)
        verification = self.private_file(
            primary / "backups" / f"{archive.stem}-verification.json",
            (json.dumps({
                "formatVersion": "copper-key-backup-verification/v1",
                "result": "PASSED",
                "backupId": backup_id,
                "backupSha256": recovery.manager.sha256_file(archive),
            }) + "\n").encode(),
        )
        self.private_file(secondary / "backups" / verification.name, verification.read_bytes())
        receipt_document = {
            "formatVersion": recovery.manager.REMOVAL_RECEIPT_FORMAT,
            "status": "INTERNAL_REHEARSAL_ONLY",
            "result": "PASSED",
            "mode": "BACKUP_THEN_REMOVE",
            "siteId": site_id,
            "domain": "copper-test-03.example.org",
            "serverResidueCheck": "PASSED",
            "backup": {
                "backupId": backup_id,
                "fileName": archive.name,
                "sha256": recovery.manager.sha256_file(archive),
                "verificationReceipt": str(verification),
                "verificationReceiptSha256": recovery.manager.sha256_file(verification),
            },
        }
        receipt = self.private_file(
            primary / "uninstall-receipts" / f"{site_id}-{stamp}.json",
            (json.dumps(receipt_document) + "\n").encode(),
        )
        receipt_sidecar = self.sidecar(receipt)
        self.private_file(secondary / "uninstall-receipts" / receipt.name, receipt.read_bytes())
        self.private_file(
            secondary / "uninstall-receipts" / receipt_sidecar.name,
            receipt_sidecar.read_bytes(),
        )
        profile = self.private_file(
            root / "sites" / "retired" / f"{site_id}-{stamp}.json",
            (json.dumps({
                "format": recovery.manager.PROFILE_FORMAT,
                "siteId": site_id,
                "domain": "copper-test-03.example.org",
                "publicIp": "18.142.134.26",
                "sshHost": "18.142.134.26",
                "sshUser": "ubuntu",
                "sshKey": str(root / "key.pem"),
                "primaryKeyDirectory": str(primary),
                "backupKeyDirectory": str(secondary),
                "mediaRoot": str(root / "old-media"),
            }) + "\n").encode(),
        )
        self.private_file(root / "key.pem", b"ssh-key")
        return profile

    def test_exact_uninstall_receipt_binds_two_backups_and_two_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            profile = self.evidence_tree(pathlib.Path(directory))
            evidence = recovery.validate_evidence(profile)
            self.assertEqual(evidence.profile["siteId"], "copper-test-03")
            self.assertEqual(evidence.backup_id, "1" * 32)
            self.assertEqual(evidence.backup_sha256, recovery.manager.sha256_file(evidence.archive))

    def test_historical_duplicated_secondary_site_suffix_uses_verified_parent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            profile = self.evidence_tree(pathlib.Path(directory))
            document = json.loads(profile.read_text(encoding="utf-8"))
            secondary = pathlib.Path(document["backupKeyDirectory"])
            document["backupKeyDirectory"] = str(secondary / document["siteId"])
            self.private_file(profile, (json.dumps(document) + "\n").encode())

            evidence = recovery.validate_evidence(profile)

            self.assertEqual(evidence.secondary, secondary.resolve())

    def test_existing_duplicated_secondary_leaf_with_same_identity_uses_parent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            profile = self.evidence_tree(pathlib.Path(directory))
            document = json.loads(profile.read_text(encoding="utf-8"))
            secondary = pathlib.Path(document["backupKeyDirectory"])
            duplicated = secondary / document["siteId"]
            self.private_file(
                duplicated / "copper-age-identity.txt",
                (secondary / "copper-age-identity.txt").read_bytes(),
            )
            document["backupKeyDirectory"] = str(duplicated)
            self.private_file(profile, (json.dumps(document) + "\n").encode())

            evidence = recovery.validate_evidence(profile)

            self.assertEqual(evidence.secondary, secondary.resolve())

    def test_existing_duplicated_secondary_leaf_with_other_identity_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            profile = self.evidence_tree(pathlib.Path(directory))
            document = json.loads(profile.read_text(encoding="utf-8"))
            secondary = pathlib.Path(document["backupKeyDirectory"])
            duplicated = secondary / document["siteId"]
            self.private_file(duplicated / "copper-age-identity.txt", b"different-key")
            document["backupKeyDirectory"] = str(duplicated)
            self.private_file(profile, (json.dumps(document) + "\n").encode())

            with self.assertRaisesRegex(recovery.RecoveryError, "客户恢复私钥"):
                recovery.validate_evidence(profile)

    def test_changed_second_backup_is_rejected_before_install(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            profile = self.evidence_tree(pathlib.Path(directory))
            document = recovery.manager.secure_profile(profile)
            secondary = pathlib.Path(document["backupKeyDirectory"])
            archive = next((secondary / "backups").glob("*.copperbak"))
            archive.write_bytes(b"changed")
            with self.assertRaisesRegex(recovery.manager.ManagerError, "校验文件不一致"):
                recovery.validate_evidence(profile)

    def test_pending_recovery_is_bound_to_uninstall_receipt_and_backup(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = recovery.validate_evidence(self.evidence_tree(pathlib.Path(directory)))
            state = {
                "siteId": evidence.profile["siteId"],
                "domain": evidence.profile["domain"],
                "publicIp": "18.142.134.26",
                "sshHost": "18.142.134.26",
                "sshUser": "ubuntu",
                "sshKey": evidence.profile["sshKey"],
                "phase": "SELECTED",
            }
            recovery.write_state(evidence, state)
            self.assertEqual(recovery.load_state(evidence)["phase"], "SELECTED")
            pending = recovery.manager.secure_private_json(
                recovery.pending_path(evidence), "pending"
            )
            pending["backupSha256"] = "0" * 64
            recovery.manager.write_private_json(recovery.pending_path(evidence), pending)
            with self.assertRaisesRegex(recovery.RecoveryError, "不一致"):
                recovery.load_state(evidence)

    def test_install_reuses_final_key_directories_and_noninteractive_installer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = recovery.validate_evidence(self.evidence_tree(pathlib.Path(directory)))
            target = {
                "siteId": "copper-test-03",
                "domain": "copper-test-03.example.org",
                "publicIp": "18.142.134.26",
                "sshHost": "18.142.134.26",
                "sshUser": "ubuntu",
                "sshKey": evidence.profile["sshKey"],
            }
            with mock.patch.object(
                recovery.subprocess, "run", return_value=subprocess.CompletedProcess(["installer"], 0)
            ) as run:
                recovery.install_blank(evidence, target)
            command = run.call_args.args[0]
            self.assertIn("--yes", command)
            self.assertEqual(command[command.index("--primary-key-root") + 1], str(evidence.primary))
            self.assertEqual(command[command.index("--backup-key-root") + 1], str(evidence.secondary))

    def test_stage_failure_always_destroys_tmpfs_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = recovery.validate_evidence(self.evidence_tree(pathlib.Path(directory)))
            profile = {
                "siteId": "copper-test-03", "sshHost": "18.142.134.26",
                "sshUser": "ubuntu", "sshKey": evidence.profile["sshKey"],
            }
            calls: list[str] = []

            def fake_remote(_profile, script, **kwargs):
                calls.append(script)
                if "backup restore stage" in script:
                    raise subprocess.CalledProcessError(1, ["ssh"])
                return subprocess.CompletedProcess(["ssh"], 0, stdout=b"{}")

            with mock.patch.object(recovery.manager, "remote", side_effect=fake_remote):
                with self.assertRaises(subprocess.CalledProcessError):
                    recovery.stage_restore(profile, evidence, "operator-20260719-180000")
            self.assertTrue(any("sudo rm -f /run/copper-operator-20260719-180000-identity.txt" in call for call in calls))

    def test_recorded_offline_receipt_is_idempotently_detected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = recovery.validate_evidence(self.evidence_tree(pathlib.Path(directory)))
            registry = {
                "keys": [{
                    "restoreTests": [{
                        "backupId": evidence.backup_id,
                        "backupSha256": evidence.backup_sha256,
                        "receiptSha256": evidence.verification_sha256,
                    }]
                }]
            }
            result = subprocess.CompletedProcess(
                ["ssh"], 0, stdout=(json.dumps(registry) + "\n").encode()
            )
            with mock.patch.object(recovery.manager, "remote", return_value=result):
                self.assertTrue(recovery.restore_test_recorded({}, evidence))

    def test_interrupted_stage_reuses_same_restore_id_without_new_stage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = recovery.validate_evidence(self.evidence_tree(pathlib.Path(directory)))
            state = {
                "siteId": evidence.profile["siteId"],
                "domain": evidence.profile["domain"],
                "publicIp": "18.142.134.26",
                "sshHost": "18.142.134.26",
                "sshUser": "ubuntu",
                "sshKey": evidence.profile["sshKey"],
                "phase": "KEY_READY",
                "restoreId": "operator-20260719-180000",
            }
            with mock.patch.object(recovery, "active_profile", return_value={"siteId": "copper-test-03"}), \
                    mock.patch.object(recovery, "restore_status", side_effect=["VALIDATED", "VALIDATED"]), \
                    mock.patch.object(recovery, "stage_restore") as stage, \
                    mock.patch.object(recovery, "switch_restore"), \
                    mock.patch.object(recovery, "handle_switched", return_value=0), \
                    mock.patch.object(recovery, "write_state"):
                self.assertEqual(recovery.run(evidence, state), 0)
            stage.assert_not_called()
            self.assertEqual(state["restoreId"], "operator-20260719-180000")
            self.assertEqual(state["phase"], "SWITCHED")

    def test_production_switch_is_only_in_dedicated_recovery_entry(self) -> None:
        manager_source = (ROOT / "copper-manage-from-mac").read_text(encoding="utf-8")
        recovery_source = (ROOT / "copper-recover-from-mac").read_text(encoding="utf-8")
        self.assertNotIn("backup restore switch", manager_source)
        self.assertIn("backup restore switch", recovery_source)
        self.assertIn("备份时的管理员密码", recovery_source)
        self.assertIn("rollbackPoint", recovery_source)

    def test_backup_components_must_match_medium_before_server_changes(self) -> None:
        matching = {
            "webImage": "sha256:" + "1" * 64,
            "backendImage": "sha256:" + "2" * 64,
            "mysqlImage": "sha256:" + "3" * 64,
        }
        recovery.assert_component_compatibility(matching, dict(matching))
        different = dict(matching)
        different["backendImage"] = "sha256:" + "4" * 64
        with self.assertRaisesRegex(recovery.RecoveryError, "后台"):
            recovery.assert_component_compatibility(matching, different)

    def test_medium_components_read_the_verified_operator_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "OPERATOR_VALUES.env").write_text(
                f"COPPER_WEB_IMAGE=sha256:{'1' * 64}\n"
                f"COPPER_BACKEND_IMAGE=sha256:{'2' * 64}\n"
                f"COPPER_MYSQL_IMAGE=sha256:{'3' * 64}\n",
                encoding="utf-8",
            )
            with mock.patch.object(recovery, "ROOT", root):
                self.assertEqual(
                    recovery.medium_components(),
                    {
                        "webImage": "sha256:" + "1" * 64,
                        "backendImage": "sha256:" + "2" * 64,
                        "mysqlImage": "sha256:" + "3" * 64,
                    },
                )

    def test_switch_does_not_run_final_monitor_before_post_restore_backup(self) -> None:
        scripts: list[str] = []

        def fake_remote(_profile, script, **_kwargs):
            scripts.append(script)
            return subprocess.CompletedProcess(["ssh"], 0)

        with mock.patch.object(recovery.manager, "choose", return_value=1), mock.patch.object(
            recovery.manager, "remote", side_effect=fake_remote
        ):
            recovery.switch_restore({}, "operator-20260719-180000")
        self.assertEqual(len(scripts), 1)
        self.assertIn("backup restore switch", scripts[0])
        self.assertNotIn("monitor check", scripts[0])
        self.assertNotIn("doctor", scripts[0])

    def test_confirmed_switch_automatically_protects_and_finalizes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = recovery.validate_evidence(self.evidence_tree(pathlib.Path(directory)))
            state: dict[str, object] = {
                "siteId": evidence.profile["siteId"],
                "domain": evidence.profile["domain"],
                "publicIp": "18.142.134.26",
                "sshHost": "18.142.134.26",
                "sshUser": "ubuntu",
                "sshKey": evidence.profile["sshKey"],
                "phase": "SWITCHED",
                "restoreId": "operator-20260719-180000",
                "switchedAt": "2026-07-19T10:00:00Z",
            }
            protected = recovery.manager.ProtectionResult(
                evidence.archive, evidence.verification_receipt
            )
            with mock.patch.object(recovery.manager, "choose", return_value=1), mock.patch.object(
                recovery.manager, "create_download_verify", return_value=protected
            ) as protect, mock.patch.object(
                recovery.manager, "remote", return_value=subprocess.CompletedProcess(["ssh"], 0)
            ) as remote:
                self.assertEqual(recovery.handle_switched(evidence, evidence.profile, state), 0)
            protect.assert_called_once()
            self.assertEqual(state["phase"], "RECOVERED_ROLLBACK_RETAINED")
            self.assertEqual(state["postRestoreBackupId"], evidence.backup_id)
            self.assertTrue(any("monitor check" in call.args[1] for call in remote.call_args_list))
            receipt = pathlib.Path(str(state["recoveryReceipt"]))
            document = json.loads(receipt.read_text(encoding="utf-8"))
            self.assertTrue(document["postRestoreBackup"]["secondLocationVerified"])

    def test_post_restore_protected_resume_does_not_create_another_backup(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = recovery.validate_evidence(self.evidence_tree(pathlib.Path(directory)))
            state: dict[str, object] = {
                "siteId": evidence.profile["siteId"],
                "domain": evidence.profile["domain"],
                "phase": "POST_RESTORE_PROTECTED",
                "restoreId": "operator-20260719-180000",
                "postRestoreBackupId": evidence.backup_id,
                "postRestoreBackupSha256": evidence.backup_sha256,
                "postRestoreBackupFile": evidence.archive.name,
                "postRestoreVerificationReceipt": evidence.verification_receipt.name,
                "postRestoreVerificationReceiptSha256": evidence.verification_sha256,
            }
            with mock.patch.object(recovery.manager, "create_download_verify") as protect, mock.patch.object(
                recovery.manager, "remote", return_value=subprocess.CompletedProcess(["ssh"], 0)
            ):
                self.assertEqual(recovery.handle_switched(evidence, evidence.profile, state), 0)
            protect.assert_not_called()
            self.assertEqual(state["phase"], "RECOVERED_ROLLBACK_RETAINED")

    def test_failed_isolated_restore_can_be_cleaned_without_touching_site(self) -> None:
        with mock.patch.object(recovery.manager, "choose", return_value=1), mock.patch.object(
            recovery.manager, "remote", return_value=subprocess.CompletedProcess(["ssh"], 0)
        ) as remote:
            self.assertTrue(recovery.discard_failed_restore({}, "operator-20260719-180000"))
        script = remote.call_args.args[1]
        self.assertIn("backup restore discard", script)
        self.assertIn("DISCARD operator-20260719-180000", script)


if __name__ == "__main__":
    unittest.main()
