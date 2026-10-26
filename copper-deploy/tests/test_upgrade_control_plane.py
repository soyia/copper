#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "upgrade-control-plane.py"
ENTRIES = ("copperctl", "copper-install", "copper-maintain")


class UpgradeControlPlaneTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="copper-control-plane-")
        self.root = pathlib.Path(self.temporary.name)
        self.releases = self.root / "releases"
        self.bin = self.root / "bin"
        self.releases.mkdir()
        self.bin.mkdir()
        self.source = self.make_release(self.releases / "0.4.0-rc.3", "source")
        for entry in ENTRIES:
            (self.bin / entry).symlink_to(self.source / entry)
        package = self.root / "target-package"
        self.make_release(package / "copper-deploy-0.4.0-rc.4", "target")
        self.archive = self.root / "deploy.tar.gz"
        with tarfile.open(self.archive, "w:gz") as tar:
            tar.add(package / "copper-deploy-0.4.0-rc.4", arcname="copper-deploy-0.4.0-rc.4")
        self.digest = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.state = self.root / "run" / "control-plane.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def make_release(path: pathlib.Path, marker: str) -> pathlib.Path:
        (path / "scripts").mkdir(parents=True)
        for name in (*ENTRIES, "compose.yaml"):
            target = path / name
            target.write_text(f"{marker}:{name}\n", encoding="utf-8")
            target.chmod(0o755 if name in ENTRIES else 0o644)
        return path

    def run_tool(self, *arguments: str, ok: bool = True) -> subprocess.CompletedProcess[str]:
        result = subprocess.run([sys.executable, str(TOOL), *arguments], text=True, capture_output=True, check=False)
        if ok and result.returncode != 0:
            self.fail(result.stderr)
        if not ok and result.returncode == 0:
            self.fail("control-plane tool unexpectedly passed")
        return result

    def stage(self) -> None:
        self.run_tool(
            "stage", "--archive", str(self.archive), "--archive-sha256", self.digest,
            "--source-version", "0.4.0-rc.3", "--target-version", "0.4.0-rc.4",
            "--state", str(self.state), "--releases-dir", str(self.releases), "--bin-dir", str(self.bin),
        )

    def assert_links(self, version: str) -> None:
        for entry in ENTRIES:
            self.assertEqual((self.bin / entry).resolve(), (self.releases / version / entry).resolve())

    def test_stage_switch_and_rollback_are_version_bound(self) -> None:
        self.stage()
        document = json.loads(self.state.read_text(encoding="utf-8"))
        self.assertEqual(document["activeSide"], "source")
        self.assertEqual(self.state.stat().st_mode & 0o777, 0o600)
        self.assertTrue((self.releases / "0.4.0-rc.4" / ".copper-upgrade-release.json").is_file())
        self.assert_links("0.4.0-rc.3")
        self.run_tool("activate", "--state", str(self.state), "--side", "target")
        self.run_tool("status", "--state", str(self.state), "--expect", "target")
        self.assert_links("0.4.0-rc.4")
        self.run_tool("activate", "--state", str(self.state), "--side", "source")
        self.run_tool("status", "--state", str(self.state), "--expect", "source")
        self.assert_links("0.4.0-rc.3")

    def test_stage_rejects_tampered_archive_and_foreign_target(self) -> None:
        self.run_tool(
            "stage", "--archive", str(self.archive), "--archive-sha256", "0" * 64,
            "--source-version", "0.4.0-rc.3", "--target-version", "0.4.0-rc.4",
            "--state", str(self.state), "--releases-dir", str(self.releases), "--bin-dir", str(self.bin),
            ok=False,
        )
        self.make_release(self.releases / "0.4.0-rc.4", "foreign")
        self.run_tool(
            "stage", "--archive", str(self.archive), "--archive-sha256", self.digest,
            "--source-version", "0.4.0-rc.3", "--target-version", "0.4.0-rc.4",
            "--state", str(self.state), "--releases-dir", str(self.releases), "--bin-dir", str(self.bin),
            ok=False,
        )


if __name__ == "__main__":
    unittest.main()
