#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "release-contract.py"


class ReleaseContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="copper-release-contract-")
        self.root = pathlib.Path(self.temporary.name)
        self.matrix = self.root / "upgrade-matrix.json"
        self.manifest = self.root / "copper-release-0.2.0-rc.1.env"
        self.values = self.root / "values.env"
        self.deploy_archive = self.root / "copper-deploy-0.2.0-rc.1.tar.gz"
        self.release_notes = self.root / "release-notes.md"
        self.acme_archive = self.root / "acme.sh-3.1.3.tar.gz"
        self.deploy_archive.write_bytes(b"deploy")
        self.release_notes.write_text("release notes\n", encoding="utf-8")
        self.acme_archive.write_bytes(b"acme")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_tool(self, *arguments: str, ok: bool = True) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [sys.executable, str(TOOL), *arguments],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if ok and result.returncode != 0:
            self.fail(f"tool failed: {result.stderr}")
        if not ok and result.returncode == 0:
            self.fail(f"tool unexpectedly passed: {result.stdout}")
        return result

    def generate_matrix(self) -> None:
        self.run_tool(
            "generate-matrix",
            "--target-version", "0.2.0-rc.1",
            "--target-schema", "4",
            "--source", "0.1.0-m2-rc.2,3,retained-sidecar,maintenance-window,retained-sidecar-or-verified-backup",
            "--output", str(self.matrix),
        )

    def write_values(self) -> None:
        zeros = "0" * 64
        ones = "1" * 64
        lines = {
            "COPPER_RELEASE_VERSION": "0.2.0-rc.1",
            "COPPER_RELEASE_CHANNEL": "rc",
            "COPPER_RELEASED_AT": "2026-07-14T00:00:00Z",
            "COPPER_DEPLOY_TAG": "v0.2.0-rc.1",
            "COPPER_DEPLOY_COMMIT": "a" * 40,
            "COPPER_WEB_IMAGE": f"ghcr.io/soyia/copper_web@sha256:{zeros}",
            "COPPER_WEB_SOURCE_COMMIT": "b" * 40,
            "COPPER_WEB_WORKFLOW_RUN": "123",
            "COPPER_WEB_PLATFORM": "linux/amd64",
            "COPPER_BACKEND_IMAGE": f"ghcr.io/soyia/copper_backend@sha256:{ones}",
            "COPPER_BACKEND_SOURCE_COMMIT": "c" * 40,
            "COPPER_BACKEND_WORKFLOW_RUN": "456",
            "COPPER_BACKEND_PLATFORM": "linux/amd64",
            "COPPER_MYSQL_IMAGE": f"mysql:8.4.10@sha256:{'2' * 64}",
            "COPPER_MYSQL_VERSION": "8.4.10",
            "COPPER_MYSQL_PLATFORM": "linux/amd64",
            "COPPER_SUPPORTED_OS": "ubuntu-24.04",
            "COPPER_SUPPORTED_ARCH": "amd64",
            "COPPER_MIN_CPU": "2",
            "COPPER_MIN_MEMORY_GIB": "4",
            "COPPER_MIN_FREE_DISK_GIB": "20",
            "COPPER_DATABASE_SCHEMA_VERSION": "4",
            "COPPER_MIGRATION_SET_SHA256": "3" * 64,
            "COPPER_BACKUP_FORMAT": "copper-backup/v1",
            "COPPER_EXPORT_FORMAT": "copper-export/v1",
            "COPPER_VALIDATION_LEVEL": "L4",
            "COPPER_RELEASE_NOTES_SHA256": "4" * 64,
            "COPPER_DEPLOY_ARCHIVE": self.deploy_archive.name,
            "COPPER_DEPLOY_ARCHIVE_SHA256": hashlib.sha256(self.deploy_archive.read_bytes()).hexdigest(),
            "COPPER_RELEASE_NOTES_FILE": self.release_notes.name,
            "COPPER_ACME_ARCHIVE": self.acme_archive.name,
            "COPPER_ACME_ARCHIVE_SHA256": hashlib.sha256(self.acme_archive.read_bytes()).hexdigest(),
        }
        lines["COPPER_RELEASE_NOTES_SHA256"] = hashlib.sha256(self.release_notes.read_bytes()).hexdigest()
        self.values.write_text("".join(f"{key}={value}\n" for key, value in lines.items()), encoding="utf-8")

    def generate_manifest(self) -> None:
        self.generate_matrix()
        self.write_values()
        self.run_tool(
            "generate-manifest",
            "--values", str(self.values),
            "--matrix", str(self.matrix),
            "--output", str(self.manifest),
        )

    def test_generate_validate_and_find_exact_legacy_source(self) -> None:
        self.generate_manifest()
        result = self.run_tool("validate-manifest", str(self.manifest))
        self.assertIn("source_count=1", result.stdout)
        found = self.run_tool(
            "find-source", str(self.manifest),
            "--source-version", "0.1.0-m2-rc.2",
            "--source-schema", "3",
        )
        document = json.loads(found.stdout)
        self.assertEqual(document["targetVersion"], "0.2.0-rc.1")
        self.assertEqual(document["source"]["rollbackMode"], "retained-sidecar-or-verified-backup")
        self.run_tool(
            "find-source", str(self.manifest),
            "--source-version", "0.1.0-m2-rc.1",
            "--source-schema", "3",
            ok=False,
        )

    def test_matrix_tampering_breaks_manifest_binding(self) -> None:
        self.generate_manifest()
        document = json.loads(self.matrix.read_text(encoding="utf-8"))
        document["sources"][0]["schema"] = "2"
        self.matrix.write_text(json.dumps(document), encoding="utf-8")
        result = self.run_tool("validate-manifest", str(self.manifest), ok=False)
        self.assertIn("SHA-256", result.stderr)

    def test_explicit_fresh_install_rc_manifest_refuses_every_upgrade_source(self) -> None:
        self.run_tool("generate-matrix", "--target-version", "0.2.0-rc.1", "--target-schema", "4", "--fresh-install-only", "--output", str(self.matrix))
        self.write_values()
        self.run_tool("generate-manifest", "--values", str(self.values), "--matrix", str(self.matrix), "--output", str(self.manifest))
        self.assertIn("source_count=0", self.run_tool("validate-manifest", str(self.manifest)).stdout)
        self.assertIs(json.loads(self.matrix.read_text())["freshInstallOnly"], True)
        for version, schema in (("0.1.0-m2-rc.2", "3"), ("0.2.0-rc.1", "4")):
            result = self.run_tool("find-source", str(self.manifest), "--source-version", version, "--source-schema", schema, ok=False)
            self.assertIn("未明确允许来源", result.stderr)

    def test_empty_matrix_requires_explicit_true_flag_and_rc_channel(self) -> None:
        base = {"format": "copper-upgrade-matrix/v1", "targetVersion": "0.2.0-rc.1", "targetSchema": "4", "sources": []}
        invalid = [base, *[base | {"freshInstallOnly": value} for value in (False, "true", 1, 0, None)], base | {"freshInstallOnly": True, "targetVersion": "1.0.0"}]
        for document in invalid:
            with self.subTest(document=document):
                self.matrix.write_text(json.dumps(document))
                self.run_tool("validate-matrix", str(self.matrix), ok=False)
        self.generate_matrix()
        document = json.loads(self.matrix.read_text()) | {"freshInstallOnly": True}
        self.matrix.write_text(json.dumps(document))
        self.run_tool("validate-matrix", str(self.matrix), ok=False)

    def test_generate_matrix_fresh_mode_and_source_are_mutually_exclusive(self) -> None:
        args = ("generate-matrix", "--target-version", "0.2.0-rc.1", "--target-schema", "4", "--output", str(self.matrix))
        self.run_tool(*args, ok=False)
        self.run_tool(*args, "--fresh-install-only", "--source", "0.1.0-m2-rc.2,3,retained-sidecar,maintenance-window,verified-backup", ok=False)
        self.run_tool("generate-matrix", "--target-version", "1.0.0", "--target-schema", "4", "--fresh-install-only", "--output", str(self.matrix), ok=False)

    def test_bound_release_artifact_tampering_breaks_manifest(self) -> None:
        self.generate_manifest()
        self.deploy_archive.write_bytes(b"tampered")
        result = self.run_tool("validate-manifest", str(self.manifest), ok=False)
        self.assertIn("SHA-256", result.stderr)

    def test_duplicate_or_ranged_sources_are_rejected(self) -> None:
        duplicate = {
            "format": "copper-upgrade-matrix/v1",
            "targetVersion": "0.2.0-rc.1",
            "targetSchema": "4",
            "sources": [
                {
                    "version": "0.1.0-m2-rc.2", "schema": "3", "mode": "retained-sidecar",
                    "downtimeClass": "maintenance-window", "rollbackMode": "verified-backup",
                },
                {
                    "version": "0.1.0-m2-rc.2", "schema": "3", "mode": "retained-sidecar",
                    "downtimeClass": "maintenance-window", "rollbackMode": "verified-backup",
                },
            ],
        }
        self.matrix.write_text(json.dumps(duplicate), encoding="utf-8")
        self.run_tool("validate-matrix", str(self.matrix), ok=False)
        duplicate["sources"] = [duplicate["sources"][0] | {"version": ">=0.1.0"}]
        self.matrix.write_text(json.dumps(duplicate), encoding="utf-8")
        self.run_tool("validate-matrix", str(self.matrix), ok=False)
        duplicate["sources"] = [duplicate["sources"][0] | {"version": "0.2.0-rc.1"}]
        self.matrix.write_text(json.dumps(duplicate), encoding="utf-8")
        self.run_tool("validate-matrix", str(self.matrix), ok=False)

    def test_manifest_forbids_unknown_and_secret_fields(self) -> None:
        self.generate_manifest()
        with self.manifest.open("a", encoding="utf-8") as writer:
            writer.write("COPPER_EXTRA_FIELD=value\n")
        self.run_tool("validate-manifest", str(self.manifest), ok=False)
        self.assertNotEqual(hashlib.sha256(self.manifest.read_bytes()).hexdigest(), "")

    def test_local_build_provenance_is_bound_and_does_not_forge_actions(self) -> None:
        self.generate_manifest()
        values = dict(line.split("=", 1) for line in self.manifest.read_text().splitlines())
        values.pop("COPPER_WEB_WORKFLOW_RUN")
        provenance = self.root / "web-local-build.json"
        document = {
            "format": "copper-local-build/v1", "role": "web", "sourceCommit": "b" * 40,
            "buildId": "local-20261016-acceptance", "builtAt": "2026-10-16T01:00:00Z",
            "platform": "linux/amd64", "image": values["COPPER_WEB_IMAGE"],
            "imageId": "sha256:" + "5" * 64, "builder": "isolated-release-factory",
            "command": "docker build --platform linux/amd64 .", "tools": {"docker": "29.6.2"},
        }
        provenance.write_text(json.dumps(document))
        values["COPPER_WEB_BUILD_PROVENANCE_FILE"] = provenance.name
        values["COPPER_WEB_BUILD_PROVENANCE_SHA256"] = hashlib.sha256(provenance.read_bytes()).hexdigest()

        def write():
            self.manifest.write_text("".join(f"{key}={value}\n" for key, value in values.items()))

        write()
        self.run_tool("validate-manifest", str(self.manifest))
        self.values.write_text(self.manifest.read_text())
        self.run_tool("generate-manifest", "--values", str(self.values), "--matrix", str(self.matrix), "--output", str(self.manifest))
        self.assertIn("COPPER_WEB_BUILD_PROVENANCE_SHA256=", self.manifest.read_text())
        self.assertNotIn("COPPER_WEB_WORKFLOW_RUN=", self.manifest.read_text())
        values["COPPER_WEB_WORKFLOW_RUN"] = "123"
        write()
        self.assertIn("不得伪填", self.run_tool("validate-manifest", str(self.manifest), ok=False).stderr)
        values.pop("COPPER_WEB_WORKFLOW_RUN")
        write()
        provenance.write_text(json.dumps(document | {"sourceCommit": "c" * 40}))
        self.assertIn("SHA-256", self.run_tool("validate-manifest", str(self.manifest), ok=False).stderr)
        values["COPPER_WEB_BUILD_PROVENANCE_SHA256"] = hashlib.sha256(provenance.read_bytes()).hexdigest()
        write()
        self.assertIn("sourceCommit", self.run_tool("validate-manifest", str(self.manifest), ok=False).stderr)

    def test_local_build_source_without_evidence_is_rejected(self) -> None:
        self.generate_manifest()
        self.manifest.write_text(self.manifest.read_text().replace("COPPER_WEB_WORKFLOW_RUN=123\n", ""))
        self.assertIn("真实 Actions Run", self.run_tool("validate-manifest", str(self.manifest), ok=False).stderr)


if __name__ == "__main__":
    unittest.main()
