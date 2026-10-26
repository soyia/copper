from __future__ import annotations

import importlib.machinery
import importlib.util
import json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parent.parent
LOADER = importlib.machinery.SourceFileLoader("copper_mac_manager", str(ROOT / "copper-manage-from-mac"))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
assert SPEC and SPEC.loader
manager = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = manager
SPEC.loader.exec_module(manager)

BUILDER_LOADER = importlib.machinery.SourceFileLoader(
    "copper_guided_installer_builder_operator", str(ROOT / "scripts/build-guided-installer-medium.py")
)
BUILDER_SPEC = importlib.util.spec_from_loader(BUILDER_LOADER.name, BUILDER_LOADER)
assert BUILDER_SPEC and BUILDER_SPEC.loader
builder = importlib.util.module_from_spec(BUILDER_SPEC)
sys.modules[BUILDER_SPEC.name] = builder
BUILDER_SPEC.loader.exec_module(builder)


class OperatorToolsTest(unittest.TestCase):
    def write_profile(
        self,
        path: pathlib.Path,
        site_id: str,
        domain: str,
    ) -> pathlib.Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(
                {
                    "format": manager.PROFILE_FORMAT,
                    "siteId": site_id,
                    "domain": domain,
                    "sshHost": "18.142.134.26",
                    "sshUser": "ubuntu",
                    "sshKey": "/private/key.pem",
                    "primaryKeyDirectory": "/private/primary",
                    "backupKeyDirectory": "/Volumes/USB/backup",
                    "mediaRoot": "/private/media",
                }
            ),
            encoding="utf-8",
        )
        os.chmod(path, 0o600)
        return path

    def test_home_menu_shows_deleted_site_alongside_active_sites(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            home = pathlib.Path(directory)
            sites = home / "Library/Application Support/Copper Installer/sites"
            self.write_profile(sites / "copper-test-01.json", "copper-test-01", "one.example.org")
            self.write_profile(sites / "copper-test-02.json", "copper-test-02", "two.example.org")
            self.write_profile(
                sites / "retired/copper-test-03-20260719-172218.json",
                "copper-test-03",
                "three.example.org",
            )
            observed: list[str] = []

            def choose_deleted(_question: str, options: list[str]) -> int:
                observed.extend(options)
                return 3

            with mock.patch.object(manager.pathlib.Path, "home", return_value=home), \
                    mock.patch.object(manager, "choose", side_effect=choose_deleted), \
                    mock.patch.object(manager, "recover_deleted_site", return_value=0) as recover:
                self.assertEqual(manager.home_menu(), 0)

            self.assertEqual(
                observed,
                [
                    "正在使用：copper-test-01 · https://one.example.org",
                    "正在使用：copper-test-02 · https://two.example.org",
                    "已删除，可从整站备份恢复：copper-test-03",
                    "退出",
                ],
            )
            recover.assert_called_once_with("copper-test-03")

    def test_home_menu_lists_deleted_site_only_once_when_history_has_multiple_records(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            home = pathlib.Path(directory)
            retired = home / "Library/Application Support/Copper Installer/sites/retired"
            self.write_profile(
                retired / "copper-test-03-20260719-172218.json",
                "copper-test-03",
                "three.example.org",
            )
            self.write_profile(
                retired / "copper-test-03-20260718-120000.json",
                "copper-test-03",
                "three.example.org",
            )
            observed: list[str] = []

            def choose_exit(_question: str, options: list[str]) -> int:
                observed.extend(options)
                return 2

            with mock.patch.object(manager.pathlib.Path, "home", return_value=home), \
                    mock.patch.object(manager, "choose", side_effect=choose_exit):
                self.assertEqual(manager.home_menu(), 0)

            self.assertEqual(
                observed,
                ["已删除，可从整站备份恢复：copper-test-03", "退出"],
            )

    def test_backup_sidecar_requires_full_matching_sha256_and_filename(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            archive = root / f"copper-test_20260717T000000Z_{'a' * 32}_manual.copperbak"
            archive.write_bytes(b"encrypted backup")
            sidecar = pathlib.Path(str(archive) + ".sha256")
            sidecar.write_text(f"{manager.sha256_file(archive)}  {archive.name}\n", encoding="ascii")
            manager.verify_sidecar(archive)
            sidecar.write_text(f"{'0' * 64}  {archive.name}\n", encoding="ascii")
            with self.assertRaisesRegex(manager.ManagerError, "不一致"):
                manager.verify_sidecar(archive)

    def test_site_profile_rejects_shared_permissions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "copper-test.json"
            path.write_text(
                json.dumps(
                    {
                        "format": manager.PROFILE_FORMAT,
                        "siteId": "copper-test",
                        "domain": "copper.example.org",
                        "sshHost": "18.142.134.26",
                        "sshUser": "ubuntu",
                        "sshKey": "/private/key.pem",
                        "primaryKeyDirectory": "/private/primary",
                        "backupKeyDirectory": "/Volumes/USB/backup",
                        "mediaRoot": "/private/media",
                    }
                ),
                encoding="utf-8",
            )
            os.chmod(path, 0o600)
            self.assertEqual(manager.secure_profile(path)["siteId"], "copper-test")
            os.chmod(path, 0o644)
            with self.assertRaisesRegex(manager.ManagerError, "只允许当前用户"):
                manager.secure_profile(path)

    def test_restore_menu_only_stages_and_discards_never_switches_production(self) -> None:
        source = (ROOT / "copper-manage-from-mac").read_text(encoding="utf-8")
        self.assertIn("backup restore stage", source)
        self.assertIn("backup restore discard", source)
        self.assertIn("--confirm", source)
        self.assertNotIn("printf '%s\\\\n' 'DISCARD", source)
        self.assertNotIn("backup restore switch", source)
        self.assertIn("/run/copper-", source)

    def test_rehearsal_discard_is_bound_to_exact_action_and_restore_id(self) -> None:
        restore_source = (ROOT / "scripts/restore.sh").read_text(encoding="utf-8")
        manager_source = (ROOT / "copper-manage-from-mac").read_text(encoding="utf-8")
        self.assertIn('supplied_confirmation" == "DISCARD $restore_id"', restore_source)
        self.assertIn("shlex.quote('DISCARD ' + restore_id)", manager_source)
        self.assertIn('confirm_action "SWITCH $site_id $restore_id"', restore_source)
        self.assertIn('confirm_action "ROLLBACK $site_id $restore_id"', restore_source)

    def test_backup_waits_for_stable_health_and_stop_is_supported(self) -> None:
        backup_source = (ROOT / "scripts/backup.sh").read_text(encoding="utf-8")
        ctl_source = (ROOT / "copperctl").read_text(encoding="utf-8")
        self.assertIn("wait_for_stable_site_health", backup_source)
        self.assertIn("三层服务已连续通过稳定健康检查", backup_source)
        self.assertIn("stop                       停止三层服务，保留全部客户数据", ctl_source)
        self.assertIn('exec "$ROOT_DIR/scripts/compose.sh" stop', ctl_source)

    def test_restore_failure_still_removes_volatile_identity_and_uploaded_archive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            primary = pathlib.Path(directory)
            backups = primary / "backups"
            backups.mkdir()
            archive = backups / f"copper-test_20260717T000000Z_{'a' * 32}_manual.copperbak"
            archive.write_bytes(b"encrypted backup")
            pathlib.Path(str(archive) + ".sha256").write_text(
                f"{manager.sha256_file(archive)}  {archive.name}\n", encoding="ascii"
            )
            (primary / "copper-age-identity.txt").write_text("test identity\n", encoding="ascii")
            calls: list[str] = []

            def fake_remote(_profile: dict[str, str], script: str, **_kwargs: object) -> subprocess.CompletedProcess:
                calls.append(script)
                if "backup restore stage" in script:
                    raise subprocess.CalledProcessError(1, ["ssh"])
                return subprocess.CompletedProcess(["ssh"], 0)

            profile = {
                "siteId": "copper-test", "sshUser": "ubuntu", "sshHost": "18.142.134.26", "sshKey": "/key.pem"
            }
            with mock.patch.object(manager, "remote", side_effect=fake_remote), mock.patch.object(
                manager.subprocess, "run", return_value=subprocess.CompletedProcess(["scp"], 0)
            ):
                with self.assertRaises(subprocess.CalledProcessError):
                    manager.restore_rehearsal(profile, primary)
            self.assertTrue(any("sudo rm -f /run/copper-operator-" in script for script in calls))
            self.assertTrue(any(".copper-restore-input/copper-test" in script for script in calls))

    def test_interrupted_restore_cleanup_is_resumed_from_private_checkpoint(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            primary = pathlib.Path(directory)
            pending = manager.pending_restore_path(primary, "copper-test")
            manager.write_private_json(
                pending,
                {
                    "formatVersion": manager.PENDING_RESTORE_FORMAT,
                    "status": "INTERNAL_REHEARSAL_ONLY",
                    "siteId": "copper-test",
                    "restoreId": "operator-20260719-140356",
                    "archive": "/private/backup.copperbak",
                },
            )
            calls: list[str] = []

            def fake_remote(_profile: dict[str, str], script: str, **_kwargs: object) -> subprocess.CompletedProcess:
                calls.append(script)
                return subprocess.CompletedProcess(["ssh"], 0)

            profile = {
                "siteId": "copper-test", "sshUser": "ubuntu", "sshHost": "18.142.134.26", "sshKey": "/key.pem"
            }
            with mock.patch.object(manager, "remote", side_effect=fake_remote), mock.patch.object(
                manager, "choose", return_value=1
            ):
                self.assertTrue(manager.reconcile_pending_rehearsal(profile, primary))

            self.assertFalse(pending.exists())
            self.assertTrue(any("backup restore status operator-20260719-140356" in script for script in calls))
            self.assertTrue(any("--confirm 'DISCARD operator-20260719-140356'" in script for script in calls))

    def test_restore_checkpoint_is_not_deleted_when_ssh_is_unreachable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            primary = pathlib.Path(directory)
            pending = manager.pending_restore_path(primary, "copper-test")
            manager.write_private_json(
                pending,
                {
                    "formatVersion": manager.PENDING_RESTORE_FORMAT,
                    "status": "INTERNAL_REHEARSAL_ONLY",
                    "siteId": "copper-test",
                    "restoreId": "operator-20260719-140356",
                },
            )
            profile = {
                "siteId": "copper-test", "sshUser": "ubuntu", "sshHost": "18.142.134.26", "sshKey": "/key.pem"
            }
            with mock.patch.object(
                manager,
                "remote",
                return_value=subprocess.CompletedProcess(["ssh"], 255),
            ):
                with self.assertRaisesRegex(manager.ManagerError, "检查网络"):
                    manager.reconcile_pending_rehearsal(profile, primary)
            self.assertTrue(pending.is_file())

    def test_server_menu_and_start_here_use_fixed_plain_language_entries(self) -> None:
        server = (ROOT / "copper-maintain").read_text(encoding="utf-8")
        guide = builder.start_here("0.4.0-rc.3")
        self.assertIn("首次管理员登录", server)
        self.assertIn("完成数据保护", server)
        self.assertIn("sudo copper-maintain", guide)
        self.assertIn("./copper-manage-from-mac", guide)
        self.assertIn("第 0-5 分钟", guide)
        self.assertIn("第 25-40 分钟", guide)
        self.assertIn("正式双人交接", guide)

    def test_mac_remote_access_reuses_server_guided_menu(self) -> None:
        profile = {
            "siteId": "copper-test",
            "domain": "copper.example.org",
            "sshHost": "18.142.134.26",
            "sshUser": "ubuntu",
            "sshKey": "/private/key.pem",
        }
        with mock.patch.object(manager, "remote") as run_remote:
            manager.manage_remote_access(profile)
        run_remote.assert_called_once_with(
            profile,
            "sudo copper-maintain remote-access",
            tty=True,
        )
        source = (ROOT / "copper-manage-from-mac").read_text(encoding="utf-8")
        self.assertIn("互联网远程访问（Cloudflare，可选）", source)
        self.assertNotIn("sudo copperctl remote-access cloudflare", source)

    def test_manager_does_not_embed_private_key_content_or_password(self) -> None:
        source = (ROOT / "copper-manage-from-mac").read_text(encoding="utf-8")
        self.assertNotIn("AGE-SECRET-KEY-1", source)
        self.assertNotIn("initial-admin.txt", source)

    def test_uninstall_receipt_uses_exact_verified_backup_and_two_locations(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            primary = root / "primary"
            secondary = root / "secondary"
            media = root / "media"
            archive = primary / "backups" / f"copper-test_20260719T000000Z_{'a' * 32}_manual.copperbak"
            archive.parent.mkdir(parents=True)
            archive.write_bytes(b"encrypted whole-site backup")
            pathlib.Path(str(archive) + ".sha256").write_text(
                f"{manager.sha256_file(archive)}  {archive.name}\n", encoding="ascii"
            )
            secondary_archive = secondary / "backups" / archive.name
            secondary_archive.parent.mkdir(parents=True)
            secondary_archive.write_bytes(archive.read_bytes())
            receipt = primary / "backups" / "verification.json"
            receipt.write_text(
                json.dumps(
                    {
                        "formatVersion": "copper-key-backup-verification/v1",
                        "result": "PASSED",
                        "backupId": "a" * 32,
                        "backupSha256": manager.sha256_file(archive),
                    }
                ),
                encoding="utf-8",
            )
            os.chmod(receipt, 0o600)
            profile_path = root / "sites/copper-test.json"
            profile_path.parent.mkdir()
            profile_path.write_text("{}\n", encoding="utf-8")
            os.chmod(profile_path, 0o600)
            profile = {
                "siteId": "copper-test",
                "domain": "copper.example.org",
                "sshUser": "ubuntu",
                "sshHost": "18.142.134.26",
                "sshKey": "/private/key.pem",
            }
            protected = manager.ProtectionResult(archive, receipt)
            remote_calls: list[str] = []

            def fake_remote(_profile: dict[str, str], script: str, **_kwargs: object) -> subprocess.CompletedProcess:
                remote_calls.append(script)
                return subprocess.CompletedProcess(["ssh"], 0, stdout=b"")

            with mock.patch.object(manager, "choose", return_value=1), mock.patch.object(
                manager, "create_download_verify", return_value=protected
            ), mock.patch.object(manager, "remote", side_effect=fake_remote), mock.patch.object(
                manager, "cleanup_remote_operator_files"
            ), mock.patch.object(manager, "assert_remote_site_removed"):
                uninstall_receipt = manager.backup_then_remove_site(
                    profile_path, profile, primary, secondary, media
                )

            self.assertIsNotNone(uninstall_receipt)
            assert uninstall_receipt is not None
            self.assertTrue(uninstall_receipt.is_file())
            self.assertTrue(pathlib.Path(str(uninstall_receipt) + ".sha256").is_file())
            self.assertTrue((secondary / "uninstall-receipts" / uninstall_receipt.name).is_file())
            self.assertFalse(profile_path.exists())
            self.assertEqual(len(list((profile_path.parent / "retired").glob("*.json"))), 1)
            self.assertTrue(any("copper-install remove-site" in script for script in remote_calls))

    def test_interrupted_permanent_removal_keeps_proof_and_resumes_without_new_backup(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            primary = root / "primary"
            secondary = root / "secondary"
            archive = primary / "backups" / f"copper-test_20260719T000000Z_{'a' * 32}_manual.copperbak"
            archive.parent.mkdir(parents=True)
            archive.write_bytes(b"encrypted whole-site backup")
            pathlib.Path(str(archive) + ".sha256").write_text(
                f"{manager.sha256_file(archive)}  {archive.name}\n", encoding="ascii"
            )
            secondary_archive = secondary / "backups" / archive.name
            secondary_archive.parent.mkdir(parents=True)
            secondary_archive.write_bytes(archive.read_bytes())
            pathlib.Path(str(secondary_archive) + ".sha256").write_text(
                f"{manager.sha256_file(archive)}  {archive.name}\n", encoding="ascii"
            )
            receipt = primary / "backups/verification.json"
            receipt.write_text(
                json.dumps(
                    {
                        "formatVersion": "copper-key-backup-verification/v1",
                        "result": "PASSED",
                        "backupId": "a" * 32,
                        "backupSha256": manager.sha256_file(archive),
                    }
                ),
                encoding="utf-8",
            )
            profile_path = root / "sites/copper-test.json"
            profile_path.parent.mkdir()
            profile_path.write_text("{}\n", encoding="utf-8")
            calls: list[str] = []

            def failing_remote(_profile: dict[str, str], script: str, **_kwargs: object) -> subprocess.CompletedProcess:
                calls.append(script)
                if "copper-install remove-site" in script:
                    raise subprocess.CalledProcessError(1, ["ssh"])
                return subprocess.CompletedProcess(["ssh"], 0, stdout=b"")

            with mock.patch.object(manager, "choose", return_value=1), mock.patch.object(
                manager,
                "create_download_verify",
                return_value=manager.ProtectionResult(archive, receipt),
            ), mock.patch.object(manager, "remote", side_effect=failing_remote):
                with self.assertRaisesRegex(manager.ManagerError, "连接在删除确认后中断"):
                    manager.backup_then_remove_site(
                        profile_path,
                        {
                            "siteId": "copper-test",
                            "domain": "copper.example.org",
                            "sshUser": "ubuntu",
                            "sshHost": "18.142.134.26",
                        },
                        primary,
                        secondary,
                        root / "media",
                    )

            self.assertTrue(profile_path.exists())
            pending = manager.pending_removal_path(primary, "copper-test")
            self.assertTrue(pending.is_file())
            self.assertFalse(any("copperctl up" in script for script in calls))

            create_again = mock.Mock()
            with mock.patch.object(manager, "choose", return_value=1), mock.patch.object(
                manager, "create_download_verify", create_again
            ), mock.patch.object(
                manager,
                "remote",
                return_value=subprocess.CompletedProcess(["ssh"], 0, stdout=b""),
            ), mock.patch.object(manager, "cleanup_remote_operator_files"), mock.patch.object(
                manager, "assert_remote_site_removed"
            ):
                result = manager.backup_then_remove_site(
                    profile_path,
                    {
                        "siteId": "copper-test",
                        "domain": "copper.example.org",
                        "sshUser": "ubuntu",
                        "sshHost": "18.142.134.26",
                    },
                    primary,
                    secondary,
                    root / "media",
                )

            create_again.assert_not_called()
            self.assertIsNotNone(result)
            self.assertFalse(pending.exists())
            self.assertFalse(profile_path.exists())

    def test_failed_decommission_preparation_restarts_the_site(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            primary = root / "primary"
            secondary = root / "secondary"
            media = root / "media"
            profile_path = root / "sites/copper-test.json"
            profile_path.parent.mkdir(parents=True)
            profile_path.write_text("{}\n", encoding="utf-8")
            calls: list[str] = []

            def fake_remote(_profile: dict[str, str], script: str, **_kwargs: object) -> subprocess.CompletedProcess:
                calls.append(script)
                return subprocess.CompletedProcess(["ssh"], 0)

            with mock.patch.object(manager, "choose", return_value=1), mock.patch.object(
                manager, "create_download_verify", side_effect=manager.ManagerError("offline verify failed")
            ), mock.patch.object(manager, "remote", side_effect=fake_remote):
                with self.assertRaisesRegex(manager.ManagerError, "offline verify failed"):
                    manager.backup_then_remove_site(
                        profile_path,
                        {
                            "siteId": "copper-test",
                            "domain": "copper.example.org",
                            "sshUser": "ubuntu",
                            "sshHost": "18.142.134.26",
                        },
                        primary,
                        secondary,
                        media,
                    )

            self.assertTrue(any("sudo copperctl up" in script for script in calls))
            self.assertTrue(profile_path.exists())
            self.assertFalse(manager.pending_removal_path(primary, "copper-test").exists())


if __name__ == "__main__":
    unittest.main()
