#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import os
import pathlib
import stat
import tempfile
import types
import unittest


ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("security_secrets", ROOT / "scripts" / "security-secrets.py")
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SecuritySecretsTest(unittest.TestCase):
    def fixture(self, root: pathlib.Path, direct_secret: bool = False) -> tuple[pathlib.Path, pathlib.Path]:
        etc_dir = root / "etc"
        var_dir = root / "var"
        (etc_dir / "secrets").mkdir(parents=True)
        (etc_dir / "storage-credentials").mkdir()
        (etc_dir / "notification-credentials").mkdir()
        (etc_dir / "credentials").mkdir()
        (etc_dir / "tls").mkdir()
        (var_dir / "backup-keys").mkdir(parents=True)
        for path in (
            etc_dir / "secrets" / "copper_db_password",
            etc_dir / "secrets" / "mysql_root_password",
        ):
            path.write_text("a" * 64, encoding="utf-8")
            path.chmod(0o400)
        env_file = etc_dir / "copper.env"
        env_file.write_text(
            "COPPER_SITE_ID=test-site\n"
            f"COPPER_ETC_DIR={etc_dir}\n"
            f"COPPER_VAR_DIR={var_dir}\n"
            + ("COPPER_DB_PASSWORD=plain-text-is-forbidden\n" if direct_secret else "")
            + f"COPPER_BACKUP_S3_SECRET_KEY_FILE={etc_dir}/storage-credentials/s3_secret_key\n",
            encoding="utf-8",
        )
        env_file.chmod(0o600)
        proc_root = root / "proc"
        proc_root.mkdir()
        return env_file, proc_root

    def test_audit_passes_root_only_file_references(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file, proc_root = self.fixture(pathlib.Path(directory))
            args = types.SimpleNamespace(env_file=str(env_file), proc_root=str(proc_root), test_mode=True)
            document, exit_code = MODULE.audit(args)
            self.assertEqual(exit_code, 0)
            self.assertEqual(document["status"], "PASS")

    def test_audit_rejects_direct_secret_and_process_leak(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file, proc_root = self.fixture(pathlib.Path(directory), direct_secret=True)
            process = proc_root / "123"
            process.mkdir()
            (process / "cmdline").write_bytes(b"java\0--password=" + b"a" * 64)
            (process / "environ").write_bytes(b"")
            args = types.SimpleNamespace(env_file=str(env_file), proc_root=str(proc_root), test_mode=True)
            document, exit_code = MODULE.audit(args)
            self.assertEqual(exit_code, 1)
            failures = {item["id"] for item in document["checks"] if item["status"] == "FAIL"}
            self.assertIn("environment.file-references", failures)
            self.assertIn("process.arguments-and-environment", failures)

    def test_audit_enforces_lan_private_key_and_rotation_archive_permissions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            env_file, proc_root = self.fixture(root)
            tls_dir = root / "etc" / "tls"
            lan_key = tls_dir / "lan-privkey.pem"
            lan_key.write_text("lan-private-key-material", encoding="utf-8")
            lan_key.chmod(0o640)
            archive = tls_dir / "archive"
            generation = archive / "20260715T120000Z.fixture"
            generation.mkdir(parents=True)
            archive.chmod(0o700)
            generation.chmod(0o700)
            archived_key = generation / "lan-privkey.pem"
            archived_key.write_text("old-lan-private-key-material", encoding="utf-8")
            archived_key.chmod(0o600)
            args = types.SimpleNamespace(env_file=str(env_file), proc_root=str(proc_root), test_mode=True)

            document, exit_code = MODULE.audit(args)
            self.assertEqual(exit_code, 0)
            tls_check = next(item for item in document["checks"] if item["id"] == "tls.private-key")
            self.assertEqual(tls_check["status"], "PASS")

            archived_key.chmod(0o644)
            document, exit_code = MODULE.audit(args)
            self.assertEqual(exit_code, 1)
            tls_check = next(item for item in document["checks"] if item["id"] == "tls.private-key")
            self.assertEqual(tls_check["status"], "FAIL")
            self.assertIn("archive/20260715T120000Z.fixture/lan-privkey.pem", tls_check["detail"])

    def test_registry_never_persists_secret_value(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            env_file, _ = self.fixture(root)
            registry = root / "registry.json"
            args = types.SimpleNamespace(
                env_file=str(env_file), registry=str(registry), event="ROTATED",
                secret_id="database.application", actor="operator", reason="scheduled rotation",
                evidence_sha256="a" * 64,
            )
            MODULE.record(args)
            document = json.loads(registry.read_text(encoding="utf-8"))
            self.assertEqual(stat.S_IMODE(registry.stat().st_mode), 0o600)
            self.assertEqual(document["events"][0]["event"], "ROTATED")
            self.assertNotIn("value", document["events"][0])
            self.assertNotIn("password", registry.read_text(encoding="utf-8").lower())


if __name__ == "__main__":
    unittest.main()
