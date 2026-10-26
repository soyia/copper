#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import io
import os
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "source-rehearsal.py"


class SourceRehearsalTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="copper-source-rehearsal-")
        self.root = pathlib.Path(self.temporary.name)
        self.web = self.create_repository("copper_web", {"README.md": "web source\n"})
        self.backend = self.create_repository("copper_backend", {"pom.xml": "<project/>\n"})
        self.deploy = self.create_repository("copper_deploy", {
            "compose.yaml": "services: {}\n",
            "scripts/install.sh": "#!/usr/bin/env bash\necho install\n",
        })
        os.chmod(self.deploy / "scripts" / "install.sh", 0o755)
        self.run_git(self.deploy, "add", "scripts/install.sh")
        self.run_git(self.deploy, "commit", "-qm", "make installer executable")
        self.manifest = self.root / "copper-release-0.4.0-rc.1.env"
        self.manifest.write_text(
            "\n".join([
                "COPPER_RELEASE_VERSION=0.4.0-rc.1",
                "COPPER_RELEASED_AT=2026-07-16T11:13:22Z",
                f"COPPER_WEB_SOURCE_COMMIT={self.commit(self.web)}",
                f"COPPER_BACKEND_SOURCE_COMMIT={self.commit(self.backend)}",
                f"COPPER_DEPLOY_COMMIT={self.commit(self.deploy)}",
                f"COPPER_WEB_IMAGE=ghcr.io/soyia/copper_web@sha256:{'1' * 64}",
                f"COPPER_BACKEND_IMAGE=ghcr.io/soyia/copper_backend@sha256:{'2' * 64}",
                f"COPPER_MYSQL_IMAGE=ghcr.io/soyia/copper_mysql@sha256:{'3' * 64}",
                f"COPPER_MIGRATION_SET_SHA256={'4' * 64}",
                "COPPER_DATABASE_SCHEMA_VERSION=5",
                "COPPER_SUPPORTED_OS=ubuntu-24.04",
                "COPPER_SUPPORTED_ARCH=amd64",
                "",
            ]),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_git(self, repo: pathlib.Path, *args: str) -> None:
        result = subprocess.run(["git", "-C", str(repo), *args], text=True, capture_output=True, check=False)
        if result.returncode:
            self.fail(result.stderr)

    def create_repository(self, name: str, files: dict[str, str]) -> pathlib.Path:
        repo = self.root / name
        repo.mkdir()
        self.run_git(repo, "init", "-q")
        self.run_git(repo, "config", "user.name", "Copper Test")
        self.run_git(repo, "config", "user.email", "test@example.com")
        for relative, content in files.items():
            path = repo / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        self.run_git(repo, "add", ".")
        self.run_git(repo, "commit", "-qm", "initial source")
        return repo

    def commit(self, repo: pathlib.Path) -> str:
        return subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()

    def run_tool(self, *args: str, ok: bool = True) -> subprocess.CompletedProcess[str]:
        result = subprocess.run([sys.executable, str(TOOL), *args], text=True, capture_output=True, check=False)
        if ok and result.returncode:
            self.fail(result.stderr)
        if not ok and result.returncode == 0:
            self.fail(f"command unexpectedly passed: {result.stdout}")
        return result

    def build(self, output: pathlib.Path, ok: bool = True) -> subprocess.CompletedProcess[str]:
        return self.run_tool(
            "build", "--manifest", str(self.manifest), "--web-repo", str(self.web),
            "--backend-repo", str(self.backend), "--deploy-repo", str(self.deploy),
            "--output", str(output), ok=ok,
        )

    def test_deterministic_internal_package_and_offline_verification(self) -> None:
        first = self.root / "first.tar.gz"
        second = self.root / "second.tar.gz"
        self.build(first)
        self.build(second)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        verified = self.run_tool("verify", "--bundle", str(first))
        self.assertIn('"formalRelease": "FORMAL_RELEASE_BLOCKED"', verified.stdout)
        self.assertIn('"purpose": "INTERNAL_REHEARSAL_ONLY"', verified.stdout)
        with tarfile.open(first, "r:gz") as archive:
            names = archive.getnames()
            self.assertIn("REHEARSAL-NOTICE.md", names)
            self.assertIn("sources/copper_web/README.md", names)
            self.assertIn("sources/copper_backend/pom.xml", names)
            self.assertIn("sources/copper_deploy/compose.yaml", names)
            self.assertNotIn(".git/HEAD", names)
            self.assertIn("INTERNAL_REHEARSAL_ONLY", archive.extractfile("REHEARSAL-NOTICE.md").read().decode())

    def test_tampering_is_rejected(self) -> None:
        output = self.root / "candidate.tar.gz"
        self.build(output)
        tampered = self.root / "tampered.tar.gz"
        with tarfile.open(output, "r:gz") as source, tarfile.open(tampered, "w:gz") as target:
            for member in source.getmembers():
                content = source.extractfile(member).read()
                if member.name == "REHEARSAL-NOTICE.md":
                    content = b"tampered internal notice\n"
                copied = tarfile.TarInfo(member.name)
                copied.size = len(content)
                copied.mode = member.mode
                target.addfile(copied, io.BytesIO(content))
        self.run_tool("verify", "--bundle", str(tampered), ok=False)

    def test_private_key_is_rejected(self) -> None:
        old_commit = self.commit(self.web)
        secret = self.web / "private.pem"
        private_key_header = "-----BEGIN " + "PRIVATE KEY-----"
        secret.write_text(f"{private_key_header}\nnot-a-key\n-----END PRIVATE KEY-----\n", encoding="utf-8")
        self.run_git(self.web, "add", "private.pem")
        self.run_git(self.web, "commit", "-qm", "bad key")
        self.manifest.write_text(
            self.manifest.read_text(encoding="utf-8").replace(
                f"COPPER_WEB_SOURCE_COMMIT={old_commit}",
                f"COPPER_WEB_SOURCE_COMMIT={self.commit(self.web)}",
            ),
            encoding="utf-8",
        )
        self.build(self.root / "private-key.tar.gz", ok=False)

    def test_private_key_detection_rule_is_not_treated_as_a_private_key(self) -> None:
        old_commit = self.commit(self.web)
        rule = self.web / "config" / "security-rules.json"
        rule.parent.mkdir()
        rule.write_text(
            '{"pattern":"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"}\n',
            encoding="utf-8",
        )
        self.run_git(self.web, "add", "config/security-rules.json")
        self.run_git(self.web, "commit", "-qm", "add key detector")
        self.manifest.write_text(
            self.manifest.read_text(encoding="utf-8").replace(
                f"COPPER_WEB_SOURCE_COMMIT={old_commit}",
                f"COPPER_WEB_SOURCE_COMMIT={self.commit(self.web)}",
            ),
            encoding="utf-8",
        )
        output = self.root / "security-rule.tar.gz"
        self.build(output)
        self.run_tool("verify", "--bundle", str(output))

    def test_dirty_worktree_is_rejected(self) -> None:
        (self.web / "uncommitted.txt").write_text("not reviewed\n", encoding="utf-8")
        self.build(self.root / "dirty.tar.gz", ok=False)


if __name__ == "__main__":
    unittest.main()
