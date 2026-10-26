#!/usr/bin/env python3
from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "github-source-rehearsal.py"


class GitHubSourceRehearsalTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="copper-github-source-rehearsal-")
        self.root = pathlib.Path(self.temporary.name)
        self.remotes: dict[str, pathlib.Path] = {}
        for role in ("web", "backend", "deploy"):
            source = self.root / f"{role}-source"
            source.mkdir()
            self.git(source, "init", "-q", "-b", "main")
            self.git(source, "config", "user.name", "Copper Test")
            self.git(source, "config", "user.email", "test@example.com")
            (source / "README.md").write_text(f"{role} source\n", encoding="utf-8")
            self.git(source, "add", "README.md")
            self.git(source, "commit", "-qm", "initial source")
            remote = self.root / f"{role}.git"
            self.git(self.root, "clone", "--bare", str(source), str(remote))
            self.remotes[role] = remote

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def git(self, cwd: pathlib.Path, *args: str) -> None:
        result = subprocess.run(["git", "-C", str(cwd), *args], text=True, capture_output=True, check=False)
        if result.returncode:
            self.fail(result.stderr)

    def command(self, operation: str, workspace: pathlib.Path, report: pathlib.Path, *, ok: bool = True) -> subprocess.CompletedProcess[str]:
        arguments = [sys.executable, str(TOOL), operation, "--workspace", str(workspace), "--report", str(report), "--test-mode"]
        for role in ("web", "backend", "deploy"):
            arguments.extend(["--repository", f"{role}=file://{self.remotes[role]}"])
        result = subprocess.run(arguments, text=True, capture_output=True, check=False)
        if ok and result.returncode:
            self.fail(result.stderr)
        if not ok and result.returncode == 0:
            self.fail(f"command unexpectedly passed: {result.stdout}")
        return result

    def test_clones_three_fresh_sources_and_verifies(self) -> None:
        workspace = self.root / "workspace"
        report = self.root / "report.json"
        clone = self.command("clone", workspace, report)
        value = json.loads(clone.stdout)
        self.assertEqual("INTERNAL_REHEARSAL_ONLY", value["purpose"])
        self.assertEqual("FORMAL_RELEASE_BLOCKED", value["formalRelease"])
        self.assertEqual("LOCAL_TEST_ONLY", value["sourceMode"])
        self.assertEqual({"web", "backend", "deploy"}, {item["role"] for item in value["repositories"]})
        verified = self.command("verify", workspace, report)
        self.assertEqual("PASS", json.loads(verified.stdout)["verification"])

    def test_verify_rejects_dirty_clone(self) -> None:
        workspace = self.root / "workspace"
        report = self.root / "report.json"
        self.command("clone", workspace, report)
        (workspace / "sources" / "copper_web" / "untracked.txt").write_text("dirty\n", encoding="utf-8")
        result = self.command("verify", workspace, report, ok=False)
        self.assertIn("工作区不干净", result.stderr)

    def test_clone_refuses_existing_workspace(self) -> None:
        workspace = self.root / "workspace"
        workspace.mkdir()
        result = self.command("clone", workspace, self.root / "report.json", ok=False)
        self.assertIn("已存在", result.stderr)


if __name__ == "__main__":
    unittest.main()
