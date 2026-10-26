#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
RELEASE_TOOL = ROOT / "scripts" / "release-contract.py"
DELIVERY_TOOL = ROOT / "scripts" / "delivery-contract.py"


class DeliveryContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="copper-delivery-contract-")
        self.root = pathlib.Path(self.temporary.name)
        self.source = self.root / "source"
        self.release_root = self.root / "releases"
        self.source.mkdir()
        self.manifest, self.index = self.build_source(self.source)
        self.index_hash = hashlib.sha256(self.index.read_bytes()).hexdigest()
        self.evidence = self.source.parent / "evidence.json"
        self.write_evidence(self.index, self.evidence)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_tool(
        self, tool: pathlib.Path, *args: str, ok: bool = True
    ) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [sys.executable, str(tool), *args], text=True, capture_output=True, check=False,
        )
        if ok and result.returncode != 0:
            self.fail(result.stderr)
        if not ok and result.returncode == 0:
            self.fail(f"command unexpectedly passed: {result.stdout}")
        return result

    def build_source(
        self,
        directory: pathlib.Path,
        notes_suffix: str = "",
        version: str = "0.2.0-rc.1",
    ) -> tuple[pathlib.Path, pathlib.Path]:
        directory.mkdir(parents=True, exist_ok=True)
        matrix = directory / f"copper-upgrade-matrix-{version}.json"
        manifest = directory / f"copper-release-{version}.env"
        values = directory / "values.env"
        deploy = directory / f"copper-deploy-{version}.tar.gz"
        notes = directory / f"copper-release-notes-{version}.md"
        acme = directory / "acme.sh-3.1.3.tar.gz"
        deploy.write_bytes(b"deploy-v1")
        notes.write_text(
            f"# Copper {version}\n\n"
            "## 功能变化\n隔离升级。\n\n"
            "## 数据库影响\nFlyway V4。\n\n"
            "## 已知问题\n仅用于内部验收。\n\n"
            "## 升级步骤\n先交付，再 prepare/migrate/switch。\n\n"
            "## 停机与回退\n维护窗口，保留旁路回退。\n" + notes_suffix,
            encoding="utf-8",
        )
        acme.write_bytes(b"acme-v1")
        self.run_tool(
            RELEASE_TOOL, "generate-matrix", "--target-version", version,
            "--target-schema", "4", "--source",
            "0.1.0-m2-rc.2,3,retained-sidecar,maintenance-window,retained-sidecar-or-verified-backup",
            "--output", str(matrix),
        )
        values_map = {
            "COPPER_RELEASE_VERSION": version,
            "COPPER_RELEASE_CHANNEL": "rc",
            "COPPER_RELEASED_AT": "2026-07-14T00:00:00Z",
            "COPPER_DEPLOY_TAG": f"v{version}",
            "COPPER_DEPLOY_COMMIT": "a" * 40,
            "COPPER_WEB_IMAGE": f"ghcr.io/soyia/copper_web@sha256:{'0' * 64}",
            "COPPER_WEB_SOURCE_COMMIT": "b" * 40,
            "COPPER_WEB_WORKFLOW_RUN": "123",
            "COPPER_WEB_PLATFORM": "linux/amd64",
            "COPPER_BACKEND_IMAGE": f"ghcr.io/soyia/copper_backend@sha256:{'1' * 64}",
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
            "COPPER_RELEASE_NOTES_SHA256": hashlib.sha256(notes.read_bytes()).hexdigest(),
            "COPPER_DEPLOY_ARCHIVE": deploy.name,
            "COPPER_DEPLOY_ARCHIVE_SHA256": hashlib.sha256(deploy.read_bytes()).hexdigest(),
            "COPPER_RELEASE_NOTES_FILE": notes.name,
            "COPPER_ACME_ARCHIVE": acme.name,
            "COPPER_ACME_ARCHIVE_SHA256": hashlib.sha256(acme.read_bytes()).hexdigest(),
        }
        values.write_text("".join(f"{key}={value}\n" for key, value in values_map.items()), encoding="utf-8")
        self.run_tool(
            RELEASE_TOOL, "generate-manifest", "--values", str(values), "--matrix", str(matrix),
            "--output", str(manifest),
        )
        values.unlink()
        index = directory / f"copper-delivery-online-{version}.json"
        self.run_tool(
            DELIVERY_TOOL, "generate", "--manifest", str(manifest), "--mode", "online",
            "--output", str(index),
        )
        return manifest, index

    def write_evidence(self, index: pathlib.Path, output: pathlib.Path, architecture: str = "arm64") -> None:
        images = json.loads(index.read_text(encoding="utf-8"))["images"]
        arguments = ["evidence", "--index", str(index), "--output", str(output)]
        for position, image in enumerate(images):
            arguments.extend([
                "--image",
                f"{image['role']},{image['reference']},sha256:{str(position + 4) * 64},{architecture}",
            ])
        self.run_tool(DELIVERY_TOOL, *arguments)

    def stage(self, index: pathlib.Path, digest: str, evidence: pathlib.Path, ok: bool = True) -> subprocess.CompletedProcess[str]:
        return self.run_tool(
            DELIVERY_TOOL, "stage", "--index", str(index), "--index-sha256", digest,
            "--image-evidence", str(evidence), "--release-root", str(self.release_root),
            "--test-mode", ok=ok,
        )

    def test_generate_validate_stage_status_and_idempotency(self) -> None:
        result = self.run_tool(DELIVERY_TOOL, "validate", str(self.index))
        self.assertIn("mode=online", result.stdout)
        first = self.stage(self.index, self.index_hash, self.evidence)
        self.assertIn("idempotent=false", first.stdout)
        second = self.stage(self.index, self.index_hash, self.evidence)
        self.assertIn("idempotent=true", second.stdout)
        status = self.run_tool(
            DELIVERY_TOOL, "status", "--release-root", str(self.release_root),
            "--version", "0.2.0-rc.1", "--json",
        )
        document = json.loads(status.stdout)
        self.assertEqual(document["releases"][0]["status"], "READY")
        destination = self.release_root / "0.2.0-rc.1"
        self.run_tool(
            DELIVERY_TOOL, "assert-manifest", "--manifest",
            str(destination / self.manifest.name), "--release-root", str(self.release_root),
        )
        self.assertEqual(destination.stat().st_mode & 0o777, 0o700)
        self.assertEqual((destination / "ready.json").stat().st_mode & 0o777, 0o400)

    def test_local_provenance_is_included_and_verified_after_staging(self) -> None:
        values = dict(line.split("=", 1) for line in self.manifest.read_text().splitlines())
        values.pop("COPPER_WEB_WORKFLOW_RUN")
        provenance = self.source / "local-web-build.json"
        provenance.write_text(json.dumps({
            "format": "copper-local-build/v1", "role": "web", "sourceCommit": "b" * 40,
            "buildId": "local-acceptance", "builtAt": "2026-10-16T01:00:00Z", "platform": "linux/amd64",
            "image": values["COPPER_WEB_IMAGE"], "imageId": "sha256:" + "4" * 64,
            "builder": "isolated-release-factory", "command": "docker build --platform linux/amd64 .",
            "tools": {"docker": "29.6.2"},
        }))
        values["COPPER_WEB_BUILD_PROVENANCE_FILE"] = provenance.name
        values["COPPER_WEB_BUILD_PROVENANCE_SHA256"] = hashlib.sha256(provenance.read_bytes()).hexdigest()
        self.manifest.write_text("".join(f"{key}={value}\n" for key, value in values.items()))
        self.index.unlink()
        self.run_tool(DELIVERY_TOOL, "generate", "--manifest", str(self.manifest), "--mode", "online", "--output", str(self.index))
        self.write_evidence(self.index, self.evidence)
        digest = hashlib.sha256(self.index.read_bytes()).hexdigest()
        self.stage(self.index, digest, self.evidence)
        delivered = self.release_root / "0.2.0-rc.1" / provenance.name
        self.assertEqual(provenance.read_bytes(), delivered.read_bytes())
        os.chmod(delivered, 0o600)
        delivered.write_text("{}")
        self.run_tool(DELIVERY_TOOL, "status", "--release-root", str(self.release_root), "--version", "0.2.0-rc.1", ok=False)

    def test_tampering_unknown_missing_symlink_and_path_traversal_are_rejected(self) -> None:
        extra = self.source / "unexpected.txt"
        extra.write_text("unexpected", encoding="utf-8")
        self.run_tool(DELIVERY_TOOL, "validate", str(self.index), ok=False)
        extra.unlink()
        deploy = self.source / "copper-deploy-0.2.0-rc.1.tar.gz"
        original = deploy.read_bytes()
        deploy.write_bytes(b"tampered")
        self.run_tool(DELIVERY_TOOL, "validate", str(self.index), ok=False)
        deploy.write_bytes(original)
        acme = self.source / "acme.sh-3.1.3.tar.gz"
        acme.unlink()
        acme.symlink_to(deploy)
        self.run_tool(DELIVERY_TOOL, "validate", str(self.index), ok=False)
        acme.unlink()
        acme.write_bytes(b"acme-v1")
        document = json.loads(self.index.read_text(encoding="utf-8"))
        document["artifacts"][0]["name"] = "../escape.tar.gz"
        self.index.write_text(json.dumps(document), encoding="utf-8")
        self.run_tool(DELIVERY_TOOL, "validate", str(self.index), ok=False)

    def test_offline_requires_bound_image_archive_and_online_forbids_it(self) -> None:
        offline = self.root / "offline"
        offline_manifest, generated_online = self.build_source(offline)
        generated_online.unlink()
        archive = offline / "copper-images-0.2.0-rc.1-amd64.tar.gz"
        archive.write_bytes(b"images")
        self.run_tool(
            DELIVERY_TOOL, "generate", "--manifest", str(offline_manifest), "--mode", "offline",
            "--image-archive", str(archive),
            "--image-id", f"web,sha256:{'4' * 64}",
            "--image-id", f"backend,sha256:{'5' * 64}",
            "--image-id", f"mysql,sha256:{'6' * 64}", "--output",
            str(offline / "copper-delivery-offline-0.2.0-rc.1.json"),
        )
        document = json.loads((offline / "copper-delivery-offline-0.2.0-rc.1.json").read_text(encoding="utf-8"))
        self.assertEqual({item["localId"] for item in document["images"]}, {
            f"sha256:{'4' * 64}", f"sha256:{'5' * 64}", f"sha256:{'6' * 64}",
        })
        online = self.root / "online-with-archive"
        online_manifest, generated_online = self.build_source(online)
        generated_online.unlink()
        online_archive = online / archive.name
        online_archive.write_bytes(b"images")
        self.run_tool(
            DELIVERY_TOOL, "generate", "--manifest", str(online_manifest), "--mode", "online",
            "--image-archive", str(online_archive), "--output", str(online / "bad-online.json"), ok=False,
        )

    def test_wrong_digest_conflict_and_ready_permission_tampering_are_rejected(self) -> None:
        self.stage(self.index, "f" * 64, self.evidence, ok=False)
        self.stage(self.index, self.index_hash, self.evidence)
        second = self.root / "second"
        _, second_index = self.build_source(second, "\n不同交付内容。\n")
        second_evidence = self.root / "second-evidence.json"
        self.write_evidence(second_index, second_evidence)
        self.stage(
            second_index, hashlib.sha256(second_index.read_bytes()).hexdigest(), second_evidence, ok=False,
        )
        ready = self.release_root / "0.2.0-rc.1" / "ready.json"
        os.chmod(ready, 0o644)
        self.run_tool(
            DELIVERY_TOOL, "status", "--release-root", str(self.release_root),
            "--version", "0.2.0-rc.1", ok=False,
        )

    def test_production_evidence_requires_amd64(self) -> None:
        self.run_tool(
            DELIVERY_TOOL, "stage", "--index", str(self.index), "--index-sha256", self.index_hash,
            "--image-evidence", str(self.evidence), "--release-root", str(self.release_root), ok=False,
        )

    def test_delta_delivery_omits_unchanged_backend_and_mysql_archives(self) -> None:
        base = self.root / "delta-base"
        base_manifest, base_online = self.build_source(base)
        base_online.unlink()
        base_archive = base / "copper-images-0.2.0-rc.1-amd64.tar.gz"
        base_archive.write_bytes(b"base-images")
        base_index = base / "copper-delivery-offline-0.2.0-rc.1.json"
        self.run_tool(
            DELIVERY_TOOL, "generate", "--manifest", str(base_manifest), "--mode", "offline",
            "--image-archive", str(base_archive),
            "--image-id", f"web,sha256:{'4' * 64}",
            "--image-id", f"backend,sha256:{'5' * 64}",
            "--image-id", f"mysql,sha256:{'6' * 64}",
            "--output", str(base_index),
        )

        target = self.root / "delta-target"
        target_manifest, target_online = self.build_source(target, version="0.3.0-rc.1")
        target_online.unlink()
        changed_archive = target / "copper-images-0.3.0-rc.1-amd64.tar.gz"
        changed_archive.write_bytes(b"only-web-changed")
        delta_index = target / "copper-upgrade-delivery-offline-delta-0.2.0-rc.1-to-0.3.0-rc.1.json"
        self.run_tool(
            DELIVERY_TOOL, "generate-delta", "--manifest", str(target_manifest),
            "--base-index", str(base_index), "--image-archive", str(changed_archive),
            "--image-id", f"web,sha256:{'7' * 64}",
            "--image-id", f"backend,sha256:{'5' * 64}",
            "--image-id", f"mysql,sha256:{'6' * 64}",
            "--output", str(delta_index),
        )
        document = json.loads(delta_index.read_text(encoding="utf-8"))
        self.assertEqual(document["format"], "copper-upgrade-delivery/v1")
        self.assertEqual(document["baseVersion"], "0.2.0-rc.1")
        self.assertEqual({item["role"] for item in document["artifacts"]}, {
            "deploy", "matrix", "notes", "acme", "images",
        })
        plan = {item["role"]: item["delivery"] for item in document["images"]}
        self.assertEqual(plan, {"web": "included", "backend": "reuse", "mysql": "reuse"})
        output = self.run_tool(DELIVERY_TOOL, "delta-plan", str(delta_index)).stdout
        self.assertIn("web\tincluded", output)
        self.assertIn("backend\treuse", output)

        evidence = self.root / "delta-evidence.json"
        arguments = ["evidence", "--index", str(delta_index), "--output", str(evidence)]
        for image in document["images"]:
            arguments.extend([
                "--image",
                f"{image['role']},{image['reference']},{image['localId']},arm64",
            ])
        self.run_tool(DELIVERY_TOOL, *arguments)
        digest = hashlib.sha256(delta_index.read_bytes()).hexdigest()
        self.stage(delta_index, digest, evidence)
        ready = json.loads((self.release_root / "0.3.0-rc.1/ready.json").read_text(encoding="utf-8"))
        self.assertEqual(ready["mode"], "offline-delta")


if __name__ == "__main__":
    unittest.main()
