from __future__ import annotations

import importlib.machinery
import importlib.util
import io
import json
import os
import pathlib
import socket
import stat
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parent.parent
LOADER = importlib.machinery.SourceFileLoader(
    "copper_mac_remote_installer", str(ROOT / "copper-install-from-mac")
)
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
assert SPEC and SPEC.loader
mac_installer = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mac_installer
SPEC.loader.exec_module(mac_installer)


RECIPIENT = "age1" + "a" * 58


def make_media(root: pathlib.Path) -> list[pathlib.Path]:
    relatives = [
        "copper-install",
        "copper-install-from-mac",
        "copper-manage-from-mac",
        "copper-upgrade-from-mac",
        "copper-recover-from-mac",
        "OPERATOR_VALUES.env",
        "RELEASE-MANIFEST.json",
        "tools/copper-key-offline",
        "images/copper-web-amd64.tar.gz",
        "images/copper-backend-amd64.tar.gz",
        "images/copper-mysql-amd64.tar.gz",
    ]
    paths: list[pathlib.Path] = []
    for relative in relatives:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        content = relative + "\n"
        if relative == "OPERATOR_VALUES.env":
            content = (
                "COPPER_RELEASE_VERSION=0.4.0-rc.5\n"
                "COPPER_WEB_ARCHIVE=images/copper-web-amd64.tar.gz\n"
                f"COPPER_WEB_IMAGE=sha256:{'1' * 64}\n"
                "COPPER_BACKEND_ARCHIVE=images/copper-backend-amd64.tar.gz\n"
                f"COPPER_BACKEND_IMAGE=sha256:{'2' * 64}\n"
                "COPPER_MYSQL_ARCHIVE=images/copper-mysql-amd64.tar.gz\n"
                f"COPPER_MYSQL_IMAGE=sha256:{'3' * 64}\n"
            )
        path.write_text(content, encoding="utf-8")
        paths.append(path)
    (root / "SHA256SUMS").write_text(
        "".join(f"{mac_installer.sha256_file(path)}  ./{path.relative_to(root).as_posix()}\n" for path in paths),
        encoding="utf-8",
    )
    return paths


def make_key_directory(root: pathlib.Path) -> None:
    root.mkdir(parents=True, mode=0o700)
    identity = root / "copper-age-identity.txt"
    identity.write_text("AGE-SECRET-KEY-TEST\n", encoding="ascii")
    os.chmod(identity, 0o600)
    (root / "recipient.txt").write_text(RECIPIENT + "\n", encoding="ascii")
    (root / "handover-record.json").write_text("{}\n", encoding="utf-8")


class MacRemoteInstallerTest(unittest.TestCase):
    def answers(self, root: pathlib.Path) -> object:
        root = root.resolve()
        ssh_key = root / "aws.pem"
        ssh_key.write_text("test\n", encoding="ascii")
        return mac_installer.InstallAnswers(
            site_id="copper-test-01",
            domain="copper-test-01.example.org",
            public_ip="18.142.134.26",
            ssh_host="18.142.134.26",
            ssh_user="ubuntu",
            ssh_key=ssh_key,
            customer_reference="internal-test",
            primary_key_root=root / "primary",
            backup_key_root=root / "backup",
            time_zone="Asia/Shanghai",
        )

    def test_confirmation_displays_exact_text_inside_quotes(self) -> None:
        output = io.StringIO()
        with mock.patch("builtins.input", return_value="INSTALL copper-test-01"), redirect_stdout(output):
            mac_installer.confirm_install("copper-test-01", False)
        self.assertIn('"INSTALL copper-test-01"', output.getvalue())
        self.assertIn("双引号本身不用输入", output.getvalue())

    def test_domain_accepts_https_prefix_but_rejects_unsafe_or_ambiguous_addresses(self) -> None:
        self.assertEqual(
            mac_installer.normalize_domain("  HTTPS://Copper-Test.Example.Org/  "),
            "copper-test.example.org",
        )
        self.assertEqual(mac_installer.normalize_domain("Copper-Test.Example.Org."), "copper-test.example.org")
        for value in (
            "http://copper-test.example.org",
            "https://copper-test.example.org/login",
            "https://copper-test.example.org:8443",
            "https://operator@127.0.0.1",
        ):
            with self.subTest(value=value), self.assertRaises(mac_installer.LocalInstallError):
                mac_installer.normalize_domain(value)

    def test_default_site_id_does_not_duplicate_copper_prefix(self) -> None:
        self.assertEqual(mac_installer.default_site_id("copper-test-01.example.org"), "copper-test-01")
        self.assertEqual(mac_installer.default_site_id("shop-a.example.org"), "copper-shop-a")

    def test_interactive_install_prompts_for_server_login_user(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            ssh_key = root / "aws.pem"
            ssh_key.write_text("test\n", encoding="ascii")
            args = mac_installer.build_parser().parse_args([
                "--domain", "copper-test-01.example.org",
                "--site-id", "copper-test-01",
                "--public-ip", "18.142.134.26",
                "--ssh-host", "18.142.134.26",
                "--ssh-key", str(ssh_key),
                "--primary-key-root", str(root / "primary"),
                "--backup-key-root", str(root / "backup"),
            ])
            with mock.patch.object(mac_installer, "prompt", return_value="ec2-user") as prompt:
                answers = mac_installer.collect_answers(args)
            prompt.assert_called_once_with("服务器登录用户名", "ubuntu")
            self.assertEqual(answers.ssh_user, "ec2-user")

    def test_noninteractive_install_requires_explicit_server_login_user(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            ssh_key = root / "aws.pem"
            ssh_key.write_text("test\n", encoding="ascii")
            arguments = [
                "--yes",
                "--domain", "copper-test-01.example.org",
                "--site-id", "copper-test-01",
                "--public-ip", "18.142.134.26",
                "--ssh-host", "18.142.134.26",
                "--ssh-key", str(ssh_key),
                "--primary-key-root", str(root / "primary"),
                "--backup-key-root", str(root / "backup"),
            ]
            with self.assertRaisesRegex(mac_installer.LocalInstallError, "登录用户名"):
                mac_installer.collect_answers(mac_installer.build_parser().parse_args(arguments))
            answers = mac_installer.collect_answers(
                mac_installer.build_parser().parse_args([*arguments, "--ssh-user", "ec2-user"])
            )
            self.assertEqual(answers.ssh_user, "ec2-user")

    def test_server_login_user_rejects_shell_or_path_characters(self) -> None:
        self.assertEqual(mac_installer.normalize_ssh_user("ec2-user"), "ec2-user")
        for value in ("bad user", "../ubuntu", "user;id", "-ubuntu"):
            with self.subTest(value=value), self.assertRaises(mac_installer.LocalInstallError):
                mac_installer.normalize_ssh_user(value)

    def test_remote_boundary_rejects_orphaned_or_other_site_before_upload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            media = root / "media"
            media.mkdir()
            (media / "OPERATOR_VALUES.env").write_text(
                "COPPER_RELEASE_VERSION=0.4.0-rc.5\nCOPPER_DEPLOY_COMMIT=" + "a" * 40 + "\n",
                encoding="utf-8",
            )
            answers = self.answers(root)
            orphaned = subprocess.CompletedProcess(["ssh"], 0, stdout="__COPPER_ORPHANED_STATE__\n")
            with mock.patch.object(mac_installer, "run_remote_script", return_value=orphaned):
                with self.assertRaisesRegex(mac_installer.LocalInstallError, "存在旧 Copper"):
                    mac_installer.check_remote_installation_boundary(answers, media)

            old_state = {
                "installerVersion": mac_installer.INSTALLER_VERSION,
                "releaseVersion": "0.4.0-rc.5",
                "deployCommit": "a" * 40,
                "siteId": "copper-other",
                "domain": answers.domain,
                "publicIpv4": answers.public_ip,
            }
            observed = subprocess.CompletedProcess(["ssh"], 0, stdout=json.dumps(old_state))
            with mock.patch.object(mac_installer, "run_remote_script", return_value=observed):
                with self.assertRaisesRegex(mac_installer.LocalInstallError, "另一站点或另一版本"):
                    mac_installer.check_remote_installation_boundary(answers, media)

    def test_remote_boundary_allows_clean_host(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            media = root / "media"
            media.mkdir()
            clean = subprocess.CompletedProcess(["ssh"], 0, stdout="__COPPER_CLEAN_HOST__\n")
            with mock.patch.object(mac_installer, "run_remote_script", return_value=clean):
                mac_installer.check_remote_installation_boundary(self.answers(root), media)

    def test_macos_ipv4_mapped_result_is_not_treated_as_real_ipv6(self) -> None:
        mapped = (socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("::ffff:18.142.134.26", 443, 0, 0))
        with mock.patch.object(mac_installer.socket, "getaddrinfo", return_value=[mapped]):
            self.assertEqual(mac_installer.resolve_addresses("copper-test-01.example.org", socket.AF_INET6), set())

    def test_real_ipv6_result_still_blocks_domain_validation(self) -> None:
        mapped = (socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("::ffff:18.142.134.26", 443, 0, 0))
        real = (socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("2406:da18::1", 443, 0, 0))
        with mock.patch.object(mac_installer.socket, "getaddrinfo", return_value=[mapped, real]):
            self.assertEqual(
                mac_installer.resolve_addresses("copper-test-01.example.org", socket.AF_INET6),
                {"2406:da18::1"},
            )

    def test_verified_staging_excludes_unlisted_mac_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "media-source"
            root.mkdir()
            expected = make_media(root)
            (root / ".DS_Store").write_bytes(b"finder metadata")
            output = io.StringIO()
            with redirect_stdout(output):
                verified = mac_installer.verify_local_media(root)
            self.assertEqual(set(verified), set(expected))
            self.assertIn(".DS_Store", output.getvalue())
            staging_root = pathlib.Path(directory) / "stage"
            staging_root.mkdir()
            staged = mac_installer.stage_verified_media(root, verified, staging_root)
            self.assertFalse((staged / ".DS_Store").exists())
            self.assertTrue((staged / "SHA256SUMS").is_file())
            self.assertEqual(
                {path.relative_to(staged).as_posix() for path in staged.rglob("*") if path.is_file()},
                {"SHA256SUMS", *(path.relative_to(root).as_posix() for path in expected)},
            )

    def test_verified_staging_can_omit_exact_images_and_rewrites_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "media-source"
            root.mkdir()
            expected = make_media(root)
            staging_root = pathlib.Path(directory) / "stage"
            staging_root.mkdir()
            omitted = {
                pathlib.PurePosixPath("images/copper-backend-amd64.tar.gz"),
                pathlib.PurePosixPath("images/copper-mysql-amd64.tar.gz"),
            }
            staged = mac_installer.stage_verified_media(root, expected, staging_root, omitted)
            self.assertFalse((staged / "images/copper-backend-amd64.tar.gz").exists())
            self.assertFalse((staged / "images/copper-mysql-amd64.tar.gz").exists())
            inventory = (staged / "SHA256SUMS").read_text(encoding="utf-8")
            self.assertNotIn("copper-backend-amd64", inventory)
            self.assertNotIn("copper-mysql-amd64", inventory)
            self.assertIn("copper-web-amd64", inventory)

    def test_remote_inventory_reuses_only_exact_amd64_image_ids(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            media = root / "media"
            media.mkdir()
            make_media(media)
            observed = subprocess.CompletedProcess(["ssh"], 0, stdout="backend\nmysql\n")
            with mock.patch.object(mac_installer, "run_remote_script", return_value=observed):
                reusable = mac_installer.remote_reusable_image_archives(self.answers(root), media)
            self.assertEqual(
                reusable,
                {
                    pathlib.PurePosixPath("images/copper-backend-amd64.tar.gz"),
                    pathlib.PurePosixPath("images/copper-mysql-amd64.tar.gz"),
                },
            )

    def test_existing_key_pair_is_reused_and_verified(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            media = root / "media"
            media.mkdir()
            primary_root = root / "primary-root"
            backup_root = root / "backup-root"
            make_key_directory(primary_root / "copper-test-01")
            make_key_directory(backup_root / "copper-test-01")
            completed = subprocess.CompletedProcess(["age-keygen"], 0, stdout=RECIPIENT + "\n", stderr="")
            with mock.patch.object(mac_installer.subprocess, "run", return_value=completed) as run:
                recipient, primary, backup, independent = mac_installer.prepare_recovery_keys(
                    media,
                    "copper-test-01",
                    "internal-test",
                    primary_root,
                    backup_root,
                    allow_same_disk=True,
                    interactive=False,
                )
            self.assertEqual(recipient, RECIPIENT)
            self.assertEqual(primary, (primary_root / "copper-test-01").resolve())
            self.assertEqual(backup, (backup_root / "copper-test-01").resolve())
            self.assertFalse(independent)
            self.assertEqual(run.call_count, 2)

    def test_existing_key_pair_allows_only_local_recovery_runtime_records_to_differ(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            media = root / "media"
            media.mkdir()
            primary = root / "primary-root/copper-test-01"
            backup = root / "backup-root/copper-test-01"
            make_key_directory(primary)
            make_key_directory(backup)
            (primary / "recovery-receipts").mkdir()
            (primary / "recovery-receipts/.copper-test-01-pending.json").write_text(
                "{}\n", encoding="utf-8"
            )
            (primary / "restore-rehearsals").mkdir()
            (primary / "restore-rehearsals/copper-test-01-pending.json").write_text(
                "{}\n", encoding="utf-8"
            )
            completed = subprocess.CompletedProcess(
                ["age-keygen"], 0, stdout=RECIPIENT + "\n", stderr=""
            )
            with mock.patch.object(mac_installer.subprocess, "run", return_value=completed):
                recipient, observed_primary, observed_backup, _ = mac_installer.prepare_recovery_keys(
                    media,
                    "copper-test-01",
                    "internal-test",
                    primary,
                    backup,
                    allow_same_disk=True,
                    interactive=False,
                )
            self.assertEqual(recipient, RECIPIENT)
            self.assertEqual(observed_primary, primary.resolve())
            self.assertEqual(observed_backup, backup.resolve())

            (primary / "unexpected.txt").write_text("not replicated\n", encoding="utf-8")
            with mock.patch.object(mac_installer.subprocess, "run", return_value=completed):
                with self.assertRaisesRegex(mac_installer.LocalInstallError, "两份私钥目录不一致"):
                    mac_installer.prepare_recovery_keys(
                        media,
                        "copper-test-01",
                        "internal-test",
                        primary,
                        backup,
                        allow_same_disk=True,
                        interactive=False,
                    )

    def test_site_specific_backup_directory_is_not_duplicated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            media = root / "media"
            media.mkdir()
            primary_root = root / "primary-root"
            backup_directory = root / "backup-root" / "copper-test-01"
            make_key_directory(primary_root / "copper-test-01")
            completed = subprocess.CompletedProcess(["age-keygen"], 0, stdout=RECIPIENT + "\n", stderr="")
            with mock.patch.object(mac_installer.subprocess, "run", return_value=completed):
                recipient, primary, backup, independent = mac_installer.prepare_recovery_keys(
                    media,
                    "copper-test-01",
                    "internal-test",
                    primary_root,
                    backup_directory,
                    allow_same_disk=True,
                    interactive=False,
                )
            self.assertEqual(recipient, RECIPIENT)
            self.assertEqual(primary, (primary_root / "copper-test-01").resolve())
            self.assertEqual(backup, backup_directory.resolve())
            self.assertTrue((backup_directory / "copper-age-identity.txt").is_file())
            self.assertFalse((backup_directory / "copper-test-01").exists())
            self.assertFalse(independent)

    def test_plan_displays_normalized_site_specific_key_directories(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            answers = self.answers(root)
            answers = mac_installer.InstallAnswers(
                **{
                    **answers.__dict__,
                    "backup_key_root": root / "backup" / answers.site_id,
                }
            )
            output = io.StringIO()
            with redirect_stdout(output):
                mac_installer.show_plan(answers)
            expected = (root / "backup" / answers.site_id).resolve()
            self.assertIn(f"第二份私钥目录：{expected}", output.getvalue())
            self.assertNotIn(f"{answers.site_id}/{answers.site_id}", output.getvalue())

    def test_historical_persisted_duplicate_site_directory_collapses_to_key_parent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            site_id = "copper-test-01"
            expected = root / "backup" / site_id
            make_key_directory(expected)

            planned, storage_root = mac_installer.planned_key_directory(expected / site_id, site_id)

            self.assertEqual(planned, expected.resolve())
            self.assertEqual(storage_root, expected.parent.resolve())

    def test_same_disk_backup_requires_explicit_internal_rehearsal_choice(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            media = root / "media"
            media.mkdir()
            with self.assertRaisesRegex(mac_installer.LocalInstallError, "外接盘"):
                mac_installer.prepare_recovery_keys(
                    media,
                    "copper-test-01",
                    "internal-test",
                    root / "primary",
                    root / "backup",
                    allow_same_disk=False,
                    interactive=False,
                )

    def test_remote_answers_contain_public_key_but_no_private_key_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            answers = self.answers(root)
            document = mac_installer.answers_document(answers, RECIPIENT)
            serialized = json.dumps(document)
            self.assertEqual(document["backupPublicKey"], RECIPIENT)
            self.assertNotIn("AGE-SECRET-KEY", serialized)
            self.assertNotIn("Copper-Recovery-Keys", serialized)
            self.assertNotIn(str(answers.primary_key_root), serialized)

    def test_upload_resumes_partial_file_after_connection_drop(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            local = root / "large-image.tar.gz"
            local.write_bytes(b"copper" * 100)
            wanted = mac_installer.sha256_file(local)
            partial = mac_installer.RemoteFileState(120, "0" * 64)
            complete = mac_installer.RemoteFileState(local.stat().st_size, wanted)
            transfers = [
                subprocess.CompletedProcess(["sftp"], 1),
                subprocess.CompletedProcess(["sftp"], 0),
            ]
            with mock.patch.object(
                mac_installer,
                "remote_file_state",
                side_effect=[partial, partial, complete],
            ), mock.patch.object(mac_installer.subprocess, "run", side_effect=transfers) as run, mock.patch.object(
                mac_installer.time, "sleep"
            ):
                mac_installer.upload_file_resumable(
                    self.answers(root),
                    local,
                    ".copper-installer-media/test/media/images/large-image.tar.gz",
                    "大镜像",
                )
            self.assertEqual(run.call_count, 2)
            for call in run.call_args_list:
                self.assertEqual(call.args[0][0], "sftp")
                self.assertIn("put -a", call.kwargs["input"])

    def test_first_upload_creates_missing_remote_file_without_resume_flag(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            local = root / "SHA256SUMS"
            local.write_bytes(b"manifest")
            missing = mac_installer.RemoteFileState(None, "")
            complete = mac_installer.RemoteFileState(local.stat().st_size, mac_installer.sha256_file(local))
            transferred = subprocess.CompletedProcess(["sftp"], 0)
            with mock.patch.object(
                mac_installer, "remote_file_state", side_effect=[missing, complete]
            ), mock.patch.object(mac_installer.subprocess, "run", return_value=transferred) as run:
                mac_installer.upload_file_resumable(
                    self.answers(root),
                    local,
                    ".copper-installer-media/test/media/SHA256SUMS",
                    "SHA256SUMS",
                )
            self.assertIn('put "', run.call_args.kwargs["input"])
            self.assertNotIn("put -a", run.call_args.kwargs["input"])

    def test_upload_skips_remote_file_with_matching_sha256(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            local = root / "complete.tar.gz"
            local.write_bytes(b"complete")
            complete = mac_installer.RemoteFileState(local.stat().st_size, mac_installer.sha256_file(local))
            with mock.patch.object(mac_installer, "remote_file_state", return_value=complete), mock.patch.object(
                mac_installer.subprocess, "run"
            ) as run:
                mac_installer.upload_file_resumable(
                    self.answers(root),
                    local,
                    ".copper-installer-media/test/media/complete.tar.gz",
                    "完整文件",
                )
            run.assert_not_called()

    def test_upload_stops_after_three_failed_attempts_and_keeps_remote_progress(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            local = root / "unstable.tar.gz"
            local.write_bytes(b"unstable")
            partial = mac_installer.RemoteFileState(2, "0" * 64)
            failed = subprocess.CompletedProcess(["sftp"], 1)
            with mock.patch.object(mac_installer, "remote_file_state", return_value=partial), mock.patch.object(
                mac_installer.subprocess, "run", side_effect=[failed, failed, failed]
            ) as run, mock.patch.object(mac_installer.time, "sleep"):
                with self.assertRaisesRegex(mac_installer.LocalInstallError, "连续 3 次.*进度已保留"):
                    mac_installer.upload_file_resumable(
                        self.answers(root),
                        local,
                        ".copper-installer-media/test/media/unstable.tar.gz",
                        "不稳定文件",
                    )
            self.assertEqual(run.call_count, 3)

    def test_resume_state_round_trip_has_private_paths_but_no_private_key_content(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            path = root / "state" / "unfinished-install.json"
            answers = self.answers(root)
            mac_installer.save_resume_state(path, answers, "a" * 16, "uploading")
            loaded = mac_installer.load_resume_state(path, "a" * 16)
            self.assertEqual(loaded, answers)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            serialized = path.read_text(encoding="utf-8")
            self.assertIn(str(answers.ssh_key), serialized)
            self.assertNotIn("AGE-SECRET-KEY", serialized)
            self.assertNotIn("password", serialized.lower())

    def test_completed_site_profile_is_private_and_contains_paths_not_key_content(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            answers = self.answers(root)
            primary = root / "primary" / answers.site_id
            backup = root / "backup" / answers.site_id
            primary.mkdir(parents=True)
            backup.mkdir(parents=True)
            path = root / "sites" / f"{answers.site_id}.json"
            mac_installer.save_site_profile(path, answers, root / "media", primary, backup)
            document = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(document["format"], mac_installer.PROFILE_FORMAT)
            self.assertEqual(document["primaryKeyDirectory"], str(primary))
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            serialized = path.read_text(encoding="utf-8")
            self.assertNotIn("AGE-SECRET-KEY", serialized)
            self.assertNotIn("password", serialized.lower())

    def test_post_install_guide_defaults_to_continue_without_reinstalling(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            manager = root / "copper-manage-from-mac"
            manager.write_text("#!/bin/sh\n", encoding="utf-8")
            os.chmod(manager, 0o755)
            completed = subprocess.CompletedProcess([str(manager)], 0)
            with mock.patch("builtins.input", return_value=""), mock.patch.object(
                mac_installer.subprocess, "run", return_value=completed
            ) as runner:
                mac_installer.offer_post_install_guide(root, "copper-test-01", True)
            runner.assert_called_once_with(
                [str(manager), "--site-id", "copper-test-01"], check=False
            )

    def test_post_install_guide_can_be_deferred(self) -> None:
        with tempfile.TemporaryDirectory() as directory, mock.patch(
            "builtins.input", return_value="n"
        ), mock.patch.object(mac_installer.subprocess, "run") as runner:
            mac_installer.offer_post_install_guide(pathlib.Path(directory), "copper-test-01", True)
            runner.assert_not_called()

    def test_resume_choice_reuses_answers_without_prompting_for_every_field(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            path = root / "unfinished-install.json"
            answers = self.answers(root)
            mac_installer.save_resume_state(path, answers, "b" * 16, "uploading")
            args = mac_installer.build_parser().parse_args([])
            with mock.patch.object(mac_installer, "choose", return_value=1), mock.patch.object(
                mac_installer, "collect_answers", side_effect=AssertionError("不应重新输入")
            ):
                loaded, resumed = mac_installer.resume_or_collect_answers(args, "b" * 16, path)
            self.assertTrue(resumed)
            self.assertEqual(loaded, answers)

    def test_resume_state_with_shared_permissions_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            path = root / "unfinished-install.json"
            mac_installer.save_resume_state(path, self.answers(root), "d" * 16, "uploading")
            os.chmod(path, 0o644)
            with self.assertRaisesRegex(mac_installer.LocalInstallError, "权限不安全"):
                mac_installer.load_resume_state(path, "d" * 16)

    def test_other_installer_version_state_is_not_mixed_with_current_upload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            path = root / "unfinished-install.json"
            answers = self.answers(root)
            mac_installer.save_resume_state(path, answers, "c" * 16, "uploading")
            document = json.loads(path.read_text(encoding="utf-8"))
            document["installerVersion"] = "0.1.4"
            path.write_text(json.dumps(document) + "\n", encoding="utf-8")
            os.chmod(path, 0o600)
            self.assertIsNone(mac_installer.load_resume_state(path, "c" * 16))

    def test_remote_install_uploads_only_staged_media_and_public_answers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            media = root / "media-source"
            media.mkdir()
            expected = make_media(media)
            answers = self.answers(root)
            observed_answers: dict[str, str] = {}
            uploaded: list[tuple[str, str]] = []
            calls: list[list[str]] = []

            def fake_run(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
                calls.append(command)
                return subprocess.CompletedProcess(command, 0)

            def fake_upload(
                _answers: object,
                local_path: pathlib.Path,
                remote_relative: str,
                label: str,
            ) -> None:
                uploaded.append((local_path.name, remote_relative))
                if label == "安装答案":
                    observed_answers.update(json.loads(local_path.read_text(encoding="utf-8")))

            with mock.patch.object(mac_installer, "ensure_remote_directories"), mock.patch.object(
                mac_installer, "upload_file_resumable", side_effect=fake_upload
            ), mock.patch.object(
                mac_installer, "remote_reusable_image_archives", return_value=set()
            ), mock.patch.object(
                mac_installer, "run_remote_script", return_value=subprocess.CompletedProcess(["ssh"], 0)
            ), mock.patch.object(mac_installer.subprocess, "run", side_effect=fake_run):
                self.assertEqual(mac_installer.run_remote_install(answers, media, expected, RECIPIENT), 0)

            self.assertEqual(observed_answers["backupPublicKey"], RECIPIENT)
            serialized_activity = json.dumps({"calls": calls, "uploaded": uploaded})
            self.assertNotIn("copper-age-identity", serialized_activity)
            self.assertNotIn("AGE-SECRET-KEY", serialized_activity)
            self.assertEqual(len(uploaded), len(expected) + 2)
            self.assertTrue(all("/.copper-installer-media" not in remote for _, remote in uploaded))
            self.assertTrue(
                all(
                    remote.startswith(
                        f".copper-installer-media/copper-test-01/{mac_installer.INSTALLER_VERSION}-"
                    )
                    for _, remote in uploaded
                )
            )
            remote = next(command for command in calls if command[0] == "ssh" and any("copper-install install" in item for item in command))
            self.assertTrue(any("--answers" in item and "--yes" in item for item in remote))

    def test_remote_install_does_not_reference_reused_image_archives(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            media = root / "media-source"
            media.mkdir()
            expected = make_media(media)
            answers = self.answers(root)
            uploaded: list[str] = []
            reusable = {
                pathlib.PurePosixPath("images/copper-backend-amd64.tar.gz"),
                pathlib.PurePosixPath("images/copper-mysql-amd64.tar.gz"),
            }

            def fake_upload(
                _answers: object,
                local_path: pathlib.Path,
                _remote_relative: str,
                _label: str,
            ) -> None:
                uploaded.append(local_path.relative_to(local_path.parents[1]).as_posix())

            completed = subprocess.CompletedProcess(["ssh"], 0)
            with mock.patch.object(mac_installer, "ensure_remote_directories"), mock.patch.object(
                mac_installer, "upload_file_resumable", side_effect=fake_upload
            ), mock.patch.object(
                mac_installer, "remote_reusable_image_archives", return_value=reusable
            ), mock.patch.object(
                mac_installer, "run_remote_script", return_value=completed
            ), mock.patch.object(
                mac_installer.subprocess, "run", return_value=completed
            ):
                self.assertEqual(mac_installer.run_remote_install(answers, media, expected, RECIPIENT), 0)

            serialized = "\n".join(uploaded)
            self.assertNotIn("copper-backend-amd64.tar.gz", serialized)
            self.assertNotIn("copper-mysql-amd64.tar.gz", serialized)
            self.assertIn("copper-web-amd64.tar.gz", serialized)

    def test_remote_install_does_not_claim_success_when_postcheck_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            media = root / "media-source"
            media.mkdir()
            expected = make_media(media)
            answers = self.answers(root)
            completed = subprocess.CompletedProcess(["ssh"], 0)
            failed = subprocess.CompletedProcess(["ssh"], 1)

            with mock.patch.object(mac_installer, "ensure_remote_directories"), mock.patch.object(
                mac_installer, "upload_file_resumable"
            ), mock.patch.object(
                mac_installer, "remote_reusable_image_archives", return_value=set()
            ), mock.patch.object(
                mac_installer, "run_remote_script", side_effect=[completed, failed]
            ), mock.patch.object(
                mac_installer.subprocess, "run", return_value=completed
            ):
                self.assertEqual(mac_installer.run_remote_install(answers, media, expected, RECIPIENT), 1)


if __name__ == "__main__":
    unittest.main()
