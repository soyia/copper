from __future__ import annotations

import importlib.util
import importlib.machinery
import hashlib
import io
import json
import os
import pathlib
import subprocess
import tarfile
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parent.parent
LOADER = importlib.machinery.SourceFileLoader("copper_guided_installer", str(ROOT / "copper-install"))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
assert SPEC and SPEC.loader
installer = importlib.util.module_from_spec(SPEC)
import sys
sys.modules[SPEC.name] = installer
SPEC.loader.exec_module(installer)

BUILDER_LOADER = importlib.machinery.SourceFileLoader(
    "copper_guided_installer_builder", str(ROOT / "scripts/build-guided-installer-medium.py")
)
BUILDER_SPEC = importlib.util.spec_from_loader(BUILDER_LOADER.name, BUILDER_LOADER)
assert BUILDER_SPEC and BUILDER_SPEC.loader
builder = importlib.util.module_from_spec(BUILDER_SPEC)
sys.modules[BUILDER_SPEC.name] = builder
BUILDER_SPEC.loader.exec_module(builder)


class GuidedInstallerTest(unittest.TestCase):
    def removal_fixture(self, root: pathlib.Path) -> tuple[installer.Layout, dict[str, object], dict[str, str]]:
        layout = installer.Layout(
            root / "etc/copper",
            root / "var/lib/copper",
            root / "opt/copper/releases",
            root / "usr/local/sbin/copper-install",
        )
        backup_dir = root / "backups"
        restore_root = root / "restores"
        layout.etc_dir.mkdir(parents=True)
        layout.env_file.write_text(
            "COPPER_SITE_ID=copper-test\n"
            f"COPPER_BACKUP_DIR={backup_dir}\n"
            f"COPPER_RESTORE_ROOT={restore_root}\n",
            encoding="utf-8",
        )
        os.chmod(layout.env_file, 0o600)
        backup_dir.mkdir()
        backup_id = "a" * 32
        receipt_sha = "b" * 64
        archive = backup_dir / f"copper-test_20260719T000000Z_{backup_id}_manual.copperbak"
        archive.write_bytes(b"encrypted whole-site backup")
        backup_sha = installer.sha256_file(archive)
        pathlib.Path(str(archive) + ".sha256").write_text(
            f"{backup_sha}  {archive.name}\n", encoding="ascii"
        )
        installer.write_json(
            backup_dir / "last-success.json",
            {
                "formatVersion": "copper-backup/v1",
                "backupId": backup_id,
                "archive": archive.name,
                "sha256": backup_sha,
                "size": archive.stat().st_size,
                "completedAt": "2026-07-19T00:00:00Z",
            },
        )
        installer.write_json(
            layout.var_dir / "backup-keys/registry.json",
            {
                "formatVersion": "copper-key-registry/v1",
                "activeKeyId": "key-a",
                "keys": [
                    {
                        "keyId": "key-a",
                        "status": "ACTIVE",
                        "restoreTests": [
                            {
                                "backupId": backup_id,
                                "backupSha256": backup_sha,
                                "receiptSha256": receipt_sha,
                            }
                        ],
                    }
                ],
            },
        )
        state: dict[str, object] = {"phase": "COMPLETE", "siteId": "copper-test"}
        proof = {
            "site_id": "copper-test",
            "backup_id": backup_id,
            "backup_sha256": backup_sha,
            "receipt_sha256": receipt_sha,
        }
        return layout, state, proof

    def test_installer_state_is_outside_business_data_root(self) -> None:
        layout = installer.Layout(
            pathlib.Path("/etc/copper"),
            pathlib.Path("/var/lib/copper"),
            pathlib.Path("/opt/copper/releases"),
            pathlib.Path("/usr/local/sbin/copper-install"),
        )
        self.assertEqual(layout.state_dir, pathlib.Path("/var/lib/copper-installer"))
        self.assertNotEqual(layout.state_dir.parent, layout.var_dir)

    def test_orphaned_broken_control_entry_is_not_treated_as_blank_host(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            layout = installer.Layout(root / "etc", root / "var/copper", root / "releases", root / "bin/copper-install")
            layout.command_link.parent.mkdir(parents=True)
            layout.command_link.symlink_to(root / "missing-release/copper-install")
            with self.assertRaisesRegex(installer.InstallerError, "旧安装记录或失效的程序入口"):
                installer.existing_environment(layout)

    def test_managed_request_rejects_another_installer_or_site(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            layout = installer.Layout(root / "etc", root / "var/copper", root / "releases", root / "bin/copper-install")
            plan = {
                "format": installer.PLAN_FORMAT,
                "installerVersion": "0.2.0",
                "status": "INTERNAL_REHEARSAL_ONLY",
                "mode": "fresh-install",
                "siteId": "copper-old",
                "releaseVersion": "0.4.0-rc.4",
                "deployCommit": "a" * 40,
                "domain": "old.example.org",
                "publicIpv4": "18.142.134.26",
                "dns": {"ipv4": ["18.142.134.26"], "ipv6": []},
                "timeZone": "Asia/Shanghai",
                "backupPublicKey": "age1" + "a" * 58,
                "backupPublicKeySha256": "b" * 64,
                "images": {"web": "sha256:" + "1" * 64, "backend": "sha256:" + "2" * 64, "mysql": "sha256:" + "3" * 64},
            }
            state = installer.initial_state(plan, root)
            state["installerVersion"] = "0.2.0"
            with self.assertRaisesRegex(installer.InstallerError, "另一份安装记录"):
                installer.validate_managed_request(
                    layout,
                    state,
                    plan,
                    {
                        "COPPER_RELEASE_VERSION": "0.4.0-rc.5",
                        "COPPER_DEPLOY_COMMIT": "c" * 40,
                    },
                    None,
                )

    def test_completed_installation_requires_all_entries_services_and_https(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            root = base / "releases/0.4.0-rc.5"
            root.mkdir(parents=True)
            layout = installer.Layout(base / "etc", base / "var/copper", base / "releases", base / "bin/copper-install")
            layout.command_link.parent.mkdir(parents=True)
            for name, link in (
                ("copper-install", layout.command_link),
                ("copperctl", layout.copperctl_link),
                ("copper-maintain", layout.maintenance_link),
            ):
                source = root / name
                source.write_text("#!/bin/sh\n", encoding="utf-8")
                source.chmod(0o755)
                link.symlink_to(source)
            results = [
                subprocess.CompletedProcess(["copperctl", "version"], 0, stdout="copperctl 1\n"),
                subprocess.CompletedProcess(["copperctl", "status"], 0, stdout="healthy\n"),
                *[
                    subprocess.CompletedProcess(["docker", "inspect", service], 0, stdout="healthy\n")
                    for service in ("mysql", "backend", "web")
                ],
            ]
            with mock.patch.object(installer, "run", side_effect=results), mock.patch.object(
                installer, "https_ready", return_value=(True, "")
            ):
                installer.verify_completed_installation(layout, root, {"domain": "copper.example.org"})

            layout.maintenance_link.unlink()
            layout.maintenance_link.symlink_to(base / "missing/copper-maintain")
            with self.assertRaisesRegex(installer.InstallerError, "系统入口已经失效"):
                installer.verify_completed_installation(layout, root, {"domain": "copper.example.org"})

    def test_render_environment_replaces_exact_lines_and_keeps_comments_separate(self) -> None:
        template = "# Stable site identity.\nCOPPER_SITE_ID=copper-prod\nCOPPER_DOMAIN=copper.example.com\n"
        result = installer.render_environment(
            template,
            {"COPPER_SITE_ID": "copper-test", "COPPER_DOMAIN": "copper-test.example.org"},
        )
        self.assertEqual(
            result,
            "# Stable site identity.\nCOPPER_SITE_ID=copper-test\nCOPPER_DOMAIN=copper-test.example.org\n",
        )
        self.assertNotIn("COPPER_SITE_ID#", result)

    def test_render_environment_fails_when_template_key_is_missing_or_repeated(self) -> None:
        with self.assertRaisesRegex(installer.InstallerError, "配置模板缺少"):
            installer.render_environment("COPPER_SITE_ID=a\n", {"COPPER_DOMAIN": "a.example.org"})
        with self.assertRaisesRegex(installer.InstallerError, "重复包含"):
            installer.render_environment(
                "COPPER_DOMAIN=a.example.org\nCOPPER_DOMAIN=b.example.org\n",
                {"COPPER_DOMAIN": "c.example.org"},
            )

    def test_input_validation_uses_plain_bounded_values(self) -> None:
        self.assertEqual(installer.normalize_domain("Copper-Test.Example.Org."), "copper-test.example.org")
        self.assertEqual(
            installer.normalize_domain("  HTTPS://Copper-Test.Example.Org/  "),
            "copper-test.example.org",
        )
        self.assertEqual(installer.normalize_public_ipv4("18.142.134.26"), "18.142.134.26")
        self.assertEqual(installer.normalize_site_id("copper-test"), "copper-test")
        with self.assertRaises(installer.InstallerError):
            installer.normalize_domain("https://copper.example.org/login")
        with self.assertRaises(installer.InstallerError):
            installer.normalize_domain("https://copper.example.org:8443")
        with self.assertRaises(installer.InstallerError):
            installer.normalize_domain("http://copper.example.org")
        with self.assertRaises(installer.InstallerError):
            installer.normalize_public_ipv4("192.168.1.2")
        with self.assertRaises(installer.InstallerError):
            installer.validate_backup_public_key("AGE-SECRET-KEY-DO-NOT-STORE")

    def test_default_site_id_does_not_duplicate_copper_prefix(self) -> None:
        self.assertEqual(installer.default_site_id("copper-test-01.example.org"), "copper-test-01")
        self.assertEqual(installer.default_site_id("shop-a.example.org"), "copper-shop-a")

    def test_confirmation_displays_exact_text_inside_quotes(self) -> None:
        output = io.StringIO()
        args = mock.Mock(yes=False, answers=None)
        with mock.patch.object(installer.sys.stdin, "isatty", return_value=True), mock.patch(
            "builtins.input", return_value="INSTALL copper-test-01"
        ), redirect_stdout(output):
            installer.confirm_plan({"siteId": "copper-test-01"}, args)
        self.assertIn('"INSTALL copper-test-01"', output.getvalue())
        self.assertIn("双引号本身不用输入", output.getvalue())

    def test_dns_requires_one_expected_ipv4_and_no_ipv6(self) -> None:
        with mock.patch.object(installer, "resolve_addresses", side_effect=[{"18.142.134.26"}, set()]):
            result = installer.validate_dns("copper.example.org", "18.142.134.26")
        self.assertEqual(result, {"ipv4": ["18.142.134.26"], "ipv6": []})
        with mock.patch.object(installer, "resolve_addresses", side_effect=[{"18.142.134.27"}, set()]):
            with self.assertRaisesRegex(installer.InstallerError, "不一致"):
                installer.validate_dns("copper.example.org", "18.142.134.26")
        with mock.patch.object(installer, "resolve_addresses", side_effect=[{"18.142.134.26"}, {"2406:da18::1"}]):
            with self.assertRaisesRegex(installer.InstallerError, "IPv6"):
                installer.validate_dns("copper.example.org", "18.142.134.26")

    def test_dns_choice_can_retry_or_change_without_restarting_installer(self) -> None:
        answers = {"domain": "old.example.org", "publicIp": "18.142.134.26"}
        with mock.patch.object(
            installer,
            "validate_dns",
            side_effect=[installer.InstallerError("尚未生效"), {"ipv4": ["18.142.134.26"], "ipv6": []}],
        ), mock.patch.object(installer, "choose", return_value=1):
            result = installer.validate_dns_with_choices(answers, interactive=True)
        self.assertEqual(result["ipv4"], ["18.142.134.26"])

        changed = {"domain": "old.example.org", "publicIp": "18.142.134.26"}
        with mock.patch.object(
            installer,
            "validate_dns",
            side_effect=[installer.InstallerError("尚未生效"), {"ipv4": ["18.142.134.27"], "ipv6": []}],
        ), mock.patch.object(installer, "choose", return_value=2), mock.patch.object(
            installer, "prompt", side_effect=["new.example.org", "18.142.134.27"]
        ):
            installer.validate_dns_with_choices(changed, interactive=True)
        self.assertEqual(changed, {"domain": "new.example.org", "publicIp": "18.142.134.27"})

    def test_parse_operator_values_rejects_duplicate_and_malformed_lines(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "values.env"
            path.write_text("COPPER_A=1\nCOPPER_A=2\n", encoding="utf-8")
            with self.assertRaisesRegex(installer.InstallerError, "重复填写"):
                installer.parse_env(path)
            path.write_text("COPPER_A=1\nBROKEN\n", encoding="utf-8")
            with self.assertRaisesRegex(installer.InstallerError, "格式不正确"):
                installer.parse_env(path)

    def test_media_inventory_is_closed_and_hash_bound(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            values = {
                "COPPER_DEPLOY_COMMIT": "a" * 40,
                "COPPER_DEPLOY_ARCHIVE": "deploy.tar.gz",
                "COPPER_WEB_ARCHIVE": "images/web.tar.gz",
                "COPPER_BACKEND_ARCHIVE": "images/backend.tar.gz",
                "COPPER_MYSQL_ARCHIVE": "images/mysql.tar.gz",
            }
            files = [
                "OPERATOR_VALUES.env", "RELEASE-MANIFEST.json", "copper-install", "deploy.tar.gz",
                "images/web.tar.gz", "images/backend.tar.gz", "images/mysql.tar.gz",
            ]
            for relative in files:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                if relative == "RELEASE-MANIFEST.json":
                    path.write_text(
                        json.dumps(
                            {
                                "format": "copper-m7-zero-support-medium/v1",
                                "status": "INTERNAL_REHEARSAL_ONLY",
                                "platform": "linux/amd64",
                                "deploy": {"commit": "a" * 40},
                                "web": {"importedImageId": "sha256:" + "1" * 64},
                                "backend": {"importedImageId": "sha256:" + "2" * 64},
                                "mysql": {"importedImageId": "sha256:" + "3" * 64},
                            }
                        ),
                        encoding="utf-8",
                    )
                else:
                    path.write_text(relative, encoding="utf-8")
            values.update(
                {
                    "COPPER_WEB_IMAGE": "sha256:" + "1" * 64,
                    "COPPER_BACKEND_IMAGE": "sha256:" + "2" * 64,
                    "COPPER_MYSQL_IMAGE": "sha256:" + "3" * 64,
                }
            )
            sums = [f"{installer.sha256_file(root / relative)}  ./{relative}" for relative in files]
            (root / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="utf-8")
            installer.verify_media(root, values)
            (root / "surprise.txt").write_text("not inventoried", encoding="utf-8")
            with self.assertRaisesRegex(installer.InstallerError, "未校验文件"):
                installer.verify_media(root, values)

    def test_release_manifest_must_bind_deploy_and_all_imported_images(self) -> None:
        values = {
            "COPPER_DEPLOY_COMMIT": "a" * 40,
            "COPPER_WEB_IMAGE": "sha256:" + "1" * 64,
            "COPPER_BACKEND_IMAGE": "sha256:" + "2" * 64,
            "COPPER_MYSQL_IMAGE": "sha256:" + "3" * 64,
        }
        document = {
            "format": "copper-m7-zero-support-medium/v1",
            "status": "INTERNAL_REHEARSAL_ONLY",
            "platform": "linux/amd64",
            "deploy": {"commit": "a" * 40},
            "web": {"importedImageId": "sha256:" + "1" * 64},
            "backend": {"importedImageId": "sha256:" + "2" * 64},
            "mysql": {"importedImageId": "sha256:" + "3" * 64},
        }
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "manifest.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            installer.validate_release_manifest(path, values)
            document["backend"]["importedImageId"] = "sha256:" + "9" * 64
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(installer.InstallerError, "backend"):
                installer.validate_release_manifest(path, values)

    def test_sparse_remote_media_is_allowed_only_for_exact_cached_images(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            values = {
                "COPPER_DEPLOY_COMMIT": "a" * 40,
                "COPPER_DEPLOY_ARCHIVE": "deploy.tar.gz",
                "COPPER_WEB_ARCHIVE": "images/web.tar.gz",
                "COPPER_BACKEND_ARCHIVE": "images/backend.tar.gz",
                "COPPER_MYSQL_ARCHIVE": "images/mysql.tar.gz",
                "COPPER_WEB_IMAGE": "sha256:" + "1" * 64,
                "COPPER_BACKEND_IMAGE": "sha256:" + "2" * 64,
                "COPPER_MYSQL_IMAGE": "sha256:" + "3" * 64,
            }
            manifest = {
                "format": "copper-m7-zero-support-medium/v1",
                "status": "INTERNAL_REHEARSAL_ONLY",
                "platform": "linux/amd64",
                "deploy": {"commit": "a" * 40},
                "web": {"importedImageId": values["COPPER_WEB_IMAGE"]},
                "backend": {"importedImageId": values["COPPER_BACKEND_IMAGE"]},
                "mysql": {"importedImageId": values["COPPER_MYSQL_IMAGE"]},
            }
            files = {
                "OPERATOR_VALUES.env": "values\n",
                "RELEASE-MANIFEST.json": json.dumps(manifest),
                "copper-install": "installer\n",
                "deploy.tar.gz": "deploy\n",
                "images/web.tar.gz": "web\n",
            }
            paths = []
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                paths.append(path)
            (root / "SHA256SUMS").write_text(
                "".join(f"{installer.sha256_file(path)}  ./{path.relative_to(root).as_posix()}\n" for path in paths),
                encoding="utf-8",
            )

            def cached(command: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
                image_id = command[3]
                return subprocess.CompletedProcess(command, 0, stdout=f"{image_id}|amd64\n")

            with mock.patch.object(installer, "run", side_effect=cached):
                installer.verify_media(root, values)
            missing = subprocess.CompletedProcess(["docker"], 1, stdout="")
            with mock.patch.object(installer, "run", return_value=missing):
                with self.assertRaisesRegex(installer.InstallerError, "服务器也没有完全相同"):
                    installer.verify_media(root, values)

    def test_tar_path_traversal_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = pathlib.Path(directory) / "bad.tar.gz"
            with tarfile.open(archive, "w:gz") as package:
                member = tarfile.TarInfo("../outside")
                member.size = 1
                package.addfile(member, io.BytesIO(b"x"))
            with self.assertRaisesRegex(installer.InstallerError, "不安全路径"):
                installer.safe_tar_members(archive)

    def test_rate_limit_retry_time_is_extracted(self) -> None:
        text = 'error="too many certificates" retry after 2026-07-17T22:40:32Z'
        self.assertEqual(installer.parse_retry_after(text), "2026-07-17T22:40:32Z")

    def test_https_ready_uses_python_312_handshake_hostname_verification(self) -> None:
        context = mock.MagicMock()
        connection_manager = mock.MagicMock()
        connection = mock.MagicMock()
        tls_manager = mock.MagicMock()
        tls = mock.MagicMock()
        connection_manager.__enter__.return_value = connection
        context.wrap_socket.return_value = tls_manager
        tls_manager.__enter__.return_value = tls
        tls.recv.return_value = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"

        with mock.patch.object(installer.ssl, "create_default_context", return_value=context), mock.patch.object(
            installer.socket, "create_connection", return_value=connection_manager
        ) as create_connection:
            self.assertEqual(installer.https_ready("copper.example.org"), (True, ""))

        create_connection.assert_called_once_with(("copper.example.org", 443), timeout=5.0)
        context.wrap_socket.assert_called_once_with(connection, server_hostname="copper.example.org")
        tls.sendall.assert_called_once()

    def test_https_ready_reports_tls_hostname_rejection(self) -> None:
        context = mock.MagicMock()
        connection_manager = mock.MagicMock()
        connection = mock.MagicMock()
        connection_manager.__enter__.return_value = connection
        context.wrap_socket.return_value.__enter__.side_effect = installer.ssl.CertificateError("hostname mismatch")

        with mock.patch.object(installer.ssl, "create_default_context", return_value=context), mock.patch.object(
            installer.socket, "create_connection", return_value=connection_manager
        ):
            ready, error = installer.https_ready("copper.example.org")

        self.assertFalse(ready)
        self.assertIn("hostname mismatch", error)

    def test_outer_archive_keeps_full_semantic_version_in_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "copper-m7-guided-installer-v0.1.1"
            output.mkdir()
            (output / "START-HERE.md").write_text("internal rehearsal\n", encoding="utf-8")

            archive, sidecar = builder.create_outer_archive(output)

            self.assertEqual(archive.name, "copper-m7-guided-installer-v0.1.1.tar.gz")
            self.assertEqual(sidecar.name, "copper-m7-guided-installer-v0.1.1.tar.gz.sha256")
            self.assertIn(archive.name, sidecar.read_text(encoding="utf-8"))

    def test_medium_recomputes_docker_29_import_ids_from_each_archive(self) -> None:
        def add_bytes(package: tarfile.TarFile, name: str, content: bytes) -> None:
            member = tarfile.TarInfo(name)
            member.size = len(content)
            package.addfile(member, io.BytesIO(content))

        def image_archive(path: pathlib.Path, reference: str) -> tuple[str, str]:
            config = json.dumps(
                {"architecture": "amd64", "os": "linux"},
                sort_keys=True,
                separators=(",", ":"),
            ).encode()
            config_digest = "sha256:" + hashlib.sha256(config).hexdigest()
            manifest = json.dumps(
                {
                    "schemaVersion": 2,
                    "mediaType": "application/vnd.oci.image.manifest.v1+json",
                    "config": {
                        "mediaType": "application/vnd.oci.image.config.v1+json",
                        "digest": config_digest,
                        "size": len(config),
                    },
                    "layers": [],
                },
                sort_keys=True,
                separators=(",", ":"),
            ).encode()
            manifest_digest = "sha256:" + hashlib.sha256(manifest).hexdigest()
            index = json.dumps(
                {
                    "schemaVersion": 2,
                    "manifests": [
                        {
                            "mediaType": "application/vnd.oci.image.manifest.v1+json",
                            "digest": manifest_digest,
                            "size": len(manifest),
                            "annotations": {"io.containerd.image.name": reference},
                        }
                    ],
                },
                separators=(",", ":"),
            ).encode()
            with tarfile.open(path, "w:gz") as package:
                add_bytes(package, "index.json", index)
                add_bytes(package, f"blobs/sha256/{config_digest.removeprefix('sha256:')}", config)
                add_bytes(package, f"blobs/sha256/{manifest_digest.removeprefix('sha256:')}", manifest)
            return config_digest, manifest_digest

        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory)
            values: dict[str, str] = {}
            manifest: dict[str, object] = {}
            expected: dict[str, tuple[str, str]] = {}
            for role in ("web", "backend", "mysql"):
                upper = role.upper()
                archive = output / "images" / f"copper-{role}-amd64.tar.gz"
                archive.parent.mkdir(parents=True, exist_ok=True)
                reference = f"example/{role}:immutable-amd64"
                config_digest, imported_id = image_archive(archive, reference)
                expected[role] = (config_digest, imported_id)
                values.update(
                    {
                        f"COPPER_{upper}_ARCHIVE": f"images/copper-{role}-amd64.tar.gz",
                        f"COPPER_{upper}_TAG": reference,
                        f"COPPER_{upper}_IMAGE": config_digest,
                    }
                )
                manifest[role] = {"importedImageId": config_digest}
                if role != "mysql":
                    evidence = output / "evidence" / f"copper-{role}-platform-amd64.json"
                    evidence.parent.mkdir(parents=True, exist_ok=True)
                    evidence.write_text(
                        json.dumps(
                            {
                                "role": role,
                                "localId": config_digest,
                                "archive": {
                                    "name": archive.name,
                                    "sha256": builder.sha256_file(archive),
                                },
                            }
                        ),
                        encoding="utf-8",
                    )

            builder.reconcile_image_metadata(output, values, manifest)

            for role, (config_digest, imported_id) in expected.items():
                upper = role.upper()
                self.assertNotEqual(config_digest, imported_id)
                self.assertEqual(values[f"COPPER_{upper}_IMAGE"], imported_id)
                self.assertEqual(manifest[role]["importedImageId"], imported_id)
                evidence = output / "evidence" / f"copper-{role}-platform-amd64.json"
                if evidence.exists():
                    document = json.loads(evidence.read_text(encoding="utf-8"))
                    self.assertEqual(document["localId"], config_digest)
                    self.assertEqual(document["importedImageId"], imported_id)

    def test_plan_persists_only_public_backup_recipient(self) -> None:
        recipient = "age1" + "a" * 58
        plan = installer.plan_document(
            {"siteId": "copper-test", "domain": "copper.example.org", "publicIp": "18.142.134.26", "backupPublicKey": recipient, "timeZone": "Asia/Shanghai"},
            {"COPPER_RELEASE_VERSION": "0.4.0-rc.3", "COPPER_DEPLOY_COMMIT": "a" * 40, "COPPER_WEB_IMAGE": "sha256:" + "1" * 64, "COPPER_BACKEND_IMAGE": "sha256:" + "2" * 64, "COPPER_MYSQL_IMAGE": "sha256:" + "3" * 64},
            {"ipv4": ["18.142.134.26"], "ipv6": []},
            "fresh-install",
        )
        serialized = json.dumps(plan)
        self.assertIn(recipient, serialized)
        self.assertNotIn("AGE-SECRET-KEY", serialized)
        self.assertNotIn("password", serialized.lower())

    def test_retry_before_certificate_service_time_does_not_reapply(self) -> None:
        future = "2999-01-01T00:00:00Z"
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            layout = installer.Layout(root / "etc", root / "var", root / "releases", root / "bin/copper-install")
            plan = {
                "format": installer.PLAN_FORMAT,
                "installerVersion": installer.INSTALLER_VERSION,
                "status": "INTERNAL_REHEARSAL_ONLY",
                "mode": "fresh-install",
                "siteId": "copper-test",
                "releaseVersion": "0.4.0-rc.3",
                "deployCommit": "a" * 40,
                "domain": "copper.example.org",
                "publicIpv4": "18.142.134.26",
                "dns": {"ipv4": ["18.142.134.26"], "ipv6": []},
                "timeZone": "Asia/Shanghai",
                "backupPublicKey": "age1" + "a" * 58,
                "backupPublicKeySha256": "b" * 64,
                "images": {"web": "sha256:" + "1" * 64, "backend": "sha256:" + "2" * 64, "mysql": "sha256:" + "3" * 64},
            }
            state = installer.initial_state(plan, root)
            state.update({"phase": "WAITING_FOR_CERTIFICATE", "certificateRetryAfter": future})
            installer.write_json(layout.plan_file, plan)
            installer.write_json(layout.state_file, state)
            args = mock.Mock(media_dir=None)
            with mock.patch.object(installer, "require_operator_root"), mock.patch.object(
                installer, "https_ready", return_value=(False, "not ready")
            ), mock.patch.object(installer, "media_directory") as media:
                self.assertEqual(installer.command_continue(layout, args), 4)
                media.assert_not_called()

    def test_same_version_repair_temporarily_stops_and_recovers_site_on_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            root = base / "releases/0.4.0-rc.5"
            (root / "scripts").mkdir(parents=True)
            (root / "copperctl").write_text("#!/bin/sh\n", encoding="utf-8")
            (root / "copperctl").chmod(0o755)
            layout = installer.Layout(
                base / "etc", base / "var/copper", base / "releases", base / "bin/copper-install"
            )
            plan = {"siteId": "copper-test"}
            state = {"phase": "RELEASE_READY", "releaseVersion": "0.4.0-rc.5"}
            calls: list[list[str]] = []

            def fake_run(command, **_kwargs):
                calls.append(command)
                if command[-1].endswith("bootstrap-ubuntu.sh"):
                    raise subprocess.CalledProcessError(1, command)
                return subprocess.CompletedProcess(command, 0)

            with mock.patch.object(installer, "extract_release", return_value=root), mock.patch.object(
                installer, "ensure_command_link"
            ), mock.patch.object(installer, "ensure_maintenance_link"), mock.patch.object(
                installer, "save_state"
            ), mock.patch.object(installer, "run", side_effect=fake_run):
                with self.assertRaises(installer.InstallerError):
                    installer.apply_plan(layout, base, {}, plan, {}, state, repair=True)
            self.assertIn([str(root / "copperctl"), "stop"], calls)
            self.assertIn([str(root / "copperctl"), "up"], calls)

    def test_permanent_removal_requires_latest_exact_offline_verified_backup(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            layout, state, proof = self.removal_fixture(pathlib.Path(directory))
            result = installer.validate_removal_proof(layout, state, **proof)
            self.assertEqual(result["backupId"], proof["backup_id"])
            self.assertEqual(result["receiptSha256"], proof["receipt_sha256"])

            broken = dict(proof)
            broken["receipt_sha256"] = "c" * 64
            with self.assertRaisesRegex(installer.InstallerError, "离线验证回执"):
                installer.validate_removal_proof(layout, state, **broken)

            archive = pathlib.Path(result["backupDirectory"]) / str(result["archive"])
            archive.write_bytes(b"changed after verification")
            with self.assertRaisesRegex(installer.InstallerError, "SHA-256"):
                installer.validate_removal_proof(layout, state, **proof)

    def test_permanent_removal_refuses_running_site(self) -> None:
        running = subprocess.CompletedProcess(["docker", "ps"], 0, stdout="copper-web-1\n")
        with tempfile.TemporaryDirectory() as directory:
            layout, _, _ = self.removal_fixture(pathlib.Path(directory))
            with mock.patch.object(installer, "run", return_value=running):
                with self.assertRaisesRegex(installer.InstallerError, "仍在运行"):
                    installer.require_site_stopped(layout, pathlib.Path("/opt/copper/releases/0.4.0"))

            stopped = subprocess.CompletedProcess(["docker", "ps"], 0, stdout="")
            with mock.patch.object(installer, "run", return_value=stopped):
                installer.require_site_stopped(layout, pathlib.Path("/opt/copper/releases/0.4.0"))

    def test_authorized_server_removal_resumes_without_repeating_proof_or_confirmation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            layout, _, proof = self.removal_fixture(base)
            release_root = layout.releases_dir / "0.4.0-rc.5"
            script = release_root / "scripts/permanently-delete-data.sh"
            script.parent.mkdir(parents=True)
            script.write_text("#!/bin/sh\n", encoding="utf-8")
            removal = {
                "format": installer.REMOVAL_FORMAT,
                "status": "INTERNAL_REHEARSAL_ONLY",
                "phase": "AUTHORIZED",
                "siteId": proof["site_id"],
                "backupId": proof["backup_id"],
                "backupSha256": proof["backup_sha256"],
                "receiptSha256": proof["receipt_sha256"],
                "releaseRoot": str(release_root),
                "imageIds": [],
                "backupDirectory": str(base / "backups"),
                "restoreRoot": str(base / "restores"),
            }
            installer.write_json(layout.removal_file, removal)
            args = mock.Mock(
                site_id=proof["site_id"],
                backup_id=proof["backup_id"],
                backup_sha256=proof["backup_sha256"],
                receipt_sha256=proof["receipt_sha256"],
            )
            completed = subprocess.CompletedProcess([str(script)], 0, stdout="deleted\n")
            with mock.patch.object(installer, "require_operator_root"), mock.patch.object(
                installer, "require_site_stopped"
            ), mock.patch.object(installer, "run", return_value=completed) as run_step, mock.patch.object(
                installer, "finalize_site_removal"
            ) as finalize:
                self.assertEqual(installer.command_remove_site(layout, args), 0)

            run_step.assert_called_once()
            self.assertEqual(run_step.call_args.kwargs["input_text"], "DELETE copper-test\n")
            self.assertEqual(finalize.call_args.args[1]["phase"], "DATA_DELETED")


if __name__ == "__main__":
    unittest.main()
