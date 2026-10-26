#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("security_rotate_database", ROOT / "scripts" / "security-rotate-database.py")
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class SecurityRotateDatabaseTest(unittest.TestCase):
    def test_mysql_client_does_not_bypass_temporary_file_cleanup(self) -> None:
        self.assertIn("trap 'rm -f", MODULE.MYSQL_CLIENT_SCRIPT)
        self.assertNotIn("exec mysql", MODULE.MYSQL_CLIENT_SCRIPT)

    def test_alter_sql_accepts_only_generated_hex_secrets(self) -> None:
        sql = MODULE.alter_sql("a" * 64, "b" * 64, "root", "localhost")
        self.assertIn("ALTER USER 'copper'@'%'", sql)
        self.assertIn("ALTER USER 'root'@'localhost'", sql)
        with self.assertRaises(MODULE.RotationError):
            MODULE.alter_sql("plain password", "b" * 64, "root", "localhost")

    def test_audit_sql_encodes_operator_text_instead_of_interpolating(self) -> None:
        sql = MODULE.security_audit_sql("operator' DROP TABLE", "scheduled; --")
        self.assertTrue(sql.startswith("USE copper_v2;\n"))
        self.assertNotIn("operator' DROP TABLE", sql)
        self.assertNotIn("scheduled; --", sql)
        self.assertIn("SECRET_ROTATED", sql)
        self.assertIn("database.credentials", sql)

    def test_atomic_secret_preserves_root_only_contract_for_current_user(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "secret"
            path.write_text("a" * 64 + "\n", encoding="ascii")
            path.chmod(0o400)
            MODULE.atomic_secret(path, "b" * 64, os.geteuid(), os.getegid())
            self.assertEqual(path.read_text(encoding="ascii").strip(), "b" * 64)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o400)

    def test_partial_alter_failure_restores_database_credentials_and_services(self) -> None:
        old_app = "a" * 64
        old_root = "b" * 64
        new_app = "c" * 64
        new_root = "d" * 64
        alter_calls: list[str] = []

        class FakeCompose:
            instances: list["FakeCompose"] = []

            def __init__(self, root, env_file, values):
                self.commands: list[tuple[str, ...]] = []
                self.instances.append(self)

            def run(self, *arguments, check=True):
                self.commands.append(tuple(arguments))
                return subprocess.CompletedProcess(arguments, 0, "", "")

            def mysql_container(self):
                return "1" * 12

        def fake_mysql_sql(container, user, password, sql, check=True):
            if sql == "SELECT CURRENT_USER();":
                return subprocess.CompletedProcess([], 0, "root@localhost\n", "")
            if sql == "SELECT 1;":
                success = password == old_root
                return subprocess.CompletedProcess([], 0 if success else 1, "1\n" if success else "", "")
            if "ALTER USER" in sql:
                alter_calls.append(sql)
                if len(alter_calls) == 1:
                    raise subprocess.CalledProcessError(1, ["mysql"])
                return subprocess.CompletedProcess([], 0, "", "")
            raise AssertionError(f"unexpected SQL: {sql}")

        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            env_file = root / "copper.env"
            env_file.write_text(
                "COPPER_SITE_ID=copper-test\n"
                f"COPPER_ETC_DIR={root / 'etc'}\n"
                f"COPPER_VAR_DIR={root / 'var'}\n",
                encoding="utf-8",
            )
            args = types.SimpleNamespace(
                root=str(root), env_file=str(env_file), confirm="copper-test",
                actor="security-owner", reason="partial alter rollback test",
            )
            with mock.patch.object(MODULE.os, "geteuid", return_value=0), \
                    mock.patch.object(MODULE, "checked_secret", side_effect=[old_app, old_root]), \
                    mock.patch.object(MODULE.secrets, "token_hex", side_effect=[new_app, new_root]), \
                    mock.patch.object(MODULE, "Compose", FakeCompose), \
                    mock.patch.object(MODULE, "mysql_sql", side_effect=fake_mysql_sql), \
                    mock.patch.object(MODULE, "atomic_secret"):
                with self.assertRaisesRegex(MODULE.RotationError, "旧口令和服务已恢复"):
                    MODULE.rotate(args)

        self.assertEqual(len(alter_calls), 2)
        self.assertIn(old_app, alter_calls[1])
        self.assertIn(old_root, alter_calls[1])
        self.assertIn(("up", "-d", "--force-recreate", "mysql", "backend", "web", "--wait", "--wait-timeout", "240"), FakeCompose.instances[0].commands)


if __name__ == "__main__":
    unittest.main()
