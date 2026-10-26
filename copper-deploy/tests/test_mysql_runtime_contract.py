#!/usr/bin/env python3
import pathlib
import os
import re
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


def safe_production_ca_run(root):
    dockerfile = (ROOT / "images/mysql/Dockerfile").read_text()
    command = re.search(r"^RUN (rpm -e mysql-shell.*?)\n\n", dockerfile, re.M | re.S)[1].replace("\\\n", " ")
    # Never execute the image's production absolute removal/download paths on
    # the host. All production RUN statements otherwise remain unchanged.
    for actual, relative in (("/usr/lib/mysqlsh", "runtime/usr/lib/mysqlsh"),
                             ("/root/.mysqlsh", "runtime/root/.mysqlsh"),
                             ("/tmp/copper-ca-certificates.rpm", "download.rpm")):
        assert actual in command
        command = command.replace(actual, str(root / relative))
    assert all(actual not in command for actual in (" /usr/lib/mysqlsh", " /root/.mysqlsh", " /tmp/copper-ca-certificates.rpm"))
    return command


class MysqlRuntimeContractTest(unittest.TestCase):
    def test_runtime_replaces_vulnerable_gosu_with_util_linux_setpriv(self):
        dockerfile = (ROOT / "images/mysql/Dockerfile").read_text(encoding="utf-8")
        wrapper = (ROOT / "images/mysql/gosu-setpriv.sh").read_text(encoding="utf-8")
        workflow = (ROOT / ".github/workflows/publish-mysql-runtime.yml").read_text(encoding="utf-8")

        self.assertIn("FROM mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af", dockerfile)
        self.assertIn("microdnf install -y --setopt=install_weak_deps=0 cpio util-linux", dockerfile)
        self.assertIn("mysql-community-client-8.4.10-1.el9", dockerfile)
        self.assertIn("6b4ab8b6f711a0f8460a411049ee7418c214aaa5bf64014c08e256db65ade69b", dockerfile)
        self.assertIn("28f32b4f932cb84ad3bb6e87b8cd26c02ce76d90b1082415d5dad8ff6014d6a8", dockerfile)
        self.assertIn("install -o root -g root -m 0755 /tmp/usr/bin/mysqlbinlog /usr/bin/mysqlbinlog", dockerfile)
        self.assertIn("COPY --chmod=0755 images/mysql/gosu-setpriv.sh /usr/local/bin/gosu", dockerfile)
        self.assertIn("exec setpriv --reuid=\"$user\" --regid=\"$group\" --init-groups -- \"$@\"", wrapper)
        self.assertNotIn("go1.24.6", dockerfile + wrapper)
        self.assertIn("MySQL init process done. Ready for start up.", workflow)
        self.assertIn("docker exec -e MYSQL_PWD=\"$password\"", workflow)
        self.assertIn("-Nse 'SELECT 1'", workflow)
        self.assertIn("mysqlbinlog --version", workflow)
        self.assertNotIn("-p\"$password\"", workflow)


    def test_runtime_removes_only_unused_shell_and_pins_oracle_ca_fix(self):
        dockerfile = (ROOT / "images/mysql/Dockerfile").read_text()
        self.assertIn("rpm -e mysql-shell", dockerfile)
        self.assertIn("&& ! rpm -q mysql-shell", dockerfile)
        self.assertIn("&& ! command -v mysqlsh", dockerfile)
        self.assertIn("ca-certificates-2026.2.92_v9.0.319-90.3.el9_8.noarch.rpm", dockerfile)
        self.assertIn("c69abcbaa031fddd4819ee451738ab28dfc4aae27c3f32d42e18c5bf61ad93ce", dockerfile)
        self.assertIn("&& update-ca-trust", dockerfile)
        for tool in ("mysql", "mysqldump", "mysqlbinlog"):
            self.assertIn("&& " + tool + " --version", dockerfile)
        self.assertNotIn("--nodeps", dockerfile)
        self.assertNotIn("microdnf remove", dockerfile)

    def test_production_ca_run_rejects_tampered_download_before_rpm_install(self):
        dockerfile = (ROOT / "images/mysql/Dockerfile").read_text()
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            logfile = root / "rpm-calls"
            rpm = root / "rpm"
            rpm.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$RPM_TEST_LOG\"\ncase \"$1\" in -e) exit 0;; -q) exit 1;; *) exit 83;; esac\n")
            curl = root / "curl"
            curl.write_text("#!/bin/sh\nwhile [ \"$#\" -gt 0 ]; do if [ \"$1\" = --output ]; then shift; printf tampered > \"$1\"; exit 0; fi; shift; done\nexit 84\n")
            rpm.chmod(0o755)
            curl.chmod(0o755)
            command = safe_production_ca_run(root)
            env = os.environ | {"PATH": str(root) + os.pathsep + os.environ["PATH"], "RPM_TEST_LOG": str(logfile)}
            result = subprocess.run(["/bin/sh", "-c", command], env=env, text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("FAILED", result.stdout + result.stderr)
            self.assertEqual(logfile.read_text().splitlines(), ["-e mysql-shell", "-q mysql-shell"])



    def test_production_run_removes_unowned_python_cache_but_preserves_formal_tools(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            shell = root / "runtime/usr/lib/mysqlsh"
            home = root / "runtime/root/.mysqlsh"
            cache = shell / "lib/python3.13/site-packages/certifi/__pycache__/core.cpython-313.pyc"
            cache.parent.mkdir(parents=True)
            cache.write_bytes(b"synthetic unowned cache")
            home.mkdir(parents=True)
            (home / "mysqlsh.log").write_text("synthetic upstream log")
            official = root / "runtime/usr/bin"
            official.mkdir(parents=True)
            for name in ("mysql", "mysqldump", "mysqlbinlog"):
                (official / name).write_bytes(b"owned formal tool sentinel")
            (root / "rpm").write_text("#!/bin/sh\ncase \"$1\" in -e) exit 0;; -q) exit 1;; *) exit 83;; esac\n")
            (root / "curl").write_text("#!/bin/sh\nprintf called > \"$CURL_TEST_LOG\"\nexit 84\n")
            for name in ("rpm", "curl"):
                (root / name).chmod(0o755)
            env = os.environ | {"PATH": str(root) + os.pathsep + os.environ["PATH"], "CURL_TEST_LOG": str(root / "curl-calls")}
            result = subprocess.run(["/bin/sh", "-c", safe_production_ca_run(root)], env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 84, result.stderr)
            self.assertFalse(shell.exists())
            self.assertFalse(home.exists())
            self.assertTrue((root / "curl-calls").exists())
            for name in ("mysql", "mysqldump", "mysqlbinlog"):
                self.assertEqual((official / name).read_bytes(), b"owned formal tool sentinel")

    def test_production_run_fails_before_download_if_removal_leaves_residue(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            for path in ("runtime/usr/lib/mysqlsh", "runtime/root/.mysqlsh"):
                (root / path).mkdir(parents=True)
            (root / "rpm").write_text("#!/bin/sh\ncase \"$1\" in -e) exit 0;; -q) exit 1;; *) exit 83;; esac\n")
            (root / "rm").write_text("#!/bin/sh\nexit 0\n")
            (root / "curl").write_text("#!/bin/sh\nprintf called > \"$CURL_TEST_LOG\"\nexit 84\n")
            for name in ("rpm", "rm", "curl"):
                (root / name).chmod(0o755)
            env = os.environ | {"PATH": str(root) + os.pathsep + os.environ["PATH"], "CURL_TEST_LOG": str(root / "curl-calls")}
            result = subprocess.run(["/bin/sh", "-c", safe_production_ca_run(root)], env=env, text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((root / "curl-calls").exists())
            self.assertTrue((root / "runtime/usr/lib/mysqlsh").exists())


if __name__ == "__main__":
    unittest.main()
