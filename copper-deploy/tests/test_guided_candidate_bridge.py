#!/usr/bin/env python3
"""Fixture-only bridge checks; no Docker, scans or host acceptance are claimed."""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("copper_guided_candidate_bridge", ROOT / "scripts/build-guided-medium-from-candidate.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def add_file(archive, name, content):
    member = tarfile.TarInfo(name)
    member.size = len(content)
    archive.addfile(member, io.BytesIO(content))


def oci_archive(path, tag, commit, run_id):
    config = json.dumps({"architecture": "amd64", "os": "linux", "config": {"Labels": {"org.opencontainers.image.revision": commit, "copper.release.run_id": run_id}}}).encode()
    config_id = "sha256:" + digest(config)
    manifest = json.dumps({"schemaVersion": 2, "config": {"digest": config_id}, "layers": []}).encode()
    image_id = "sha256:" + digest(manifest)
    index = json.dumps({"schemaVersion": 2, "manifests": [{"digest": image_id, "annotations": {"io.containerd.image.name": tag}}]}).encode()
    with tarfile.open(path, "w:gz") as archive:
        add_file(archive, "index.json", index)
        add_file(archive, "blobs/sha256/" + config_id[7:], config)
        add_file(archive, "blobs/sha256/" + image_id[7:], manifest)
    return config_id, image_id


def freeze(root, name):
    bridge.write_json(root / name, {p.relative_to(root).as_posix(): bridge.sha256(p) for p in root.rglob("*") if p.is_file() and p.name != name})


class GuidedCandidateBridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="copper-guided-candidate-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inputs, self.package, self.bundle = (self.root / name for name in ("inputs", "package", "bundle"))
        for root in (self.inputs / "images", self.inputs / "release", self.package / "release", self.package / "offline", self.bundle):
            root.mkdir(parents=True)
        self.commit, self.run_id = "a" * 40, "012345abcdef"
        records = {}
        for role, commit in (("web", "b" * 40), ("backend", "c" * 40), ("mysql", self.commit)):
            tag = "127.0.0.1:50000/copper-" + role + ":" + self.run_id
            archive = self.inputs / "images" / ("copper-" + role + "-amd64.tar.gz")
            producer, image_id = oci_archive(archive, tag, commit, self.run_id)
            reference = tag.rsplit(":", 1)[0] + "@" + image_id
            provenance = {"format": "copper-local-build/v1", "role": role, "sourceCommit": commit, "buildId": self.run_id, "builtAt": "2026-10-16T00:00:00Z", "platform": "linux/amd64", "image": reference, "imageId": image_id, "builder": "fixture-not-real-build", "command": "fixture-only-no-docker", "tools": {"docker": "fixture"}}
            filename = role + "-local-build.json"
            bridge.write_json(self.inputs / "release" / filename, provenance)
            records[role] = {"reference": reference, "localTag": tag, "producerImageId": producer, "imageId": image_id, "importedImageId": image_id, "archive": "images/" + archive.name, "archiveSha256": bridge.sha256(archive), "provenanceFile": filename, "provenanceSha256": bridge.sha256(self.inputs / "release" / filename), "sourceCommit": commit}
        self.doc = {"format": "copper-release-build-inputs/v1", "version": bridge.VERSION, "status": "REAL_IMAGES_BUILT_PACKAGE_PENDING", "runId": self.run_id, "deployBuildCommit": self.commit, "images": records, "directUpgradeSources": []}
        bridge.write_json(self.inputs / "BUILD_INPUTS.json", self.doc)
        cleanup = {"runId": self.run_id, "cleanup": "PASS"}
        bridge.write_json(self.inputs / "BUILD_CLEANUP.json", cleanup)
        bridge.write_json(self.package / "PACKAGE_CLEANUP.json", cleanup)
        self.status = {"format": "copper-offline-candidate/v1", "version": bridge.VERSION, "status": "PACKAGED_INSTALL_RESTORE_ACCEPTANCE_PENDING", "deployCommit": self.commit, "publishedToGHCR": False, "publicReleaseCreated": False, "directUpgradeSources": []}
        bridge.write_json(self.package / "PACKAGE_STATUS.json", self.status)
        release = self.package / "release"
        (release / "SHA256SUMS").write_text("fixture original release checksum list\n")
        (release / "deploy.tar.gz").write_bytes(b"fixture deploy")
        (release / "notes.md").write_text("\n".join(bridge.DELIVERY["NOTES_HEADINGS"]))
        (release / "acme.tar.gz").write_bytes(b"fixture acme")
        shutil.copy2(release / "acme.tar.gz", self.inputs / "release/acme.tar.gz")
        (self.inputs / "release/migrations.sha256").write_text("fixture migrations\n")
        bridge.write_json(release / "matrix.json", {"format": "copper-upgrade-matrix/v1", "targetVersion": bridge.VERSION, "targetSchema": "68", "sources": [], "freshInstallOnly": True})
        values = {"COPPER_RELEASE_MANIFEST_FORMAT": "copper-release/v1", "COPPER_RELEASE_VERSION": bridge.VERSION, "COPPER_RELEASE_CHANNEL": "rc", "COPPER_RELEASED_AT": "2026-10-16T00:00:00Z", "COPPER_DEPLOY_TAG": "v" + bridge.VERSION, "COPPER_DEPLOY_COMMIT": self.commit, "COPPER_MYSQL_VERSION": "8.4.10", "COPPER_SUPPORTED_OS": "ubuntu-24.04", "COPPER_SUPPORTED_ARCH": "amd64", "COPPER_MIN_CPU": "2", "COPPER_MIN_MEMORY_GIB": "4", "COPPER_MIN_FREE_DISK_GIB": "20", "COPPER_DATABASE_SCHEMA_VERSION": "68", "COPPER_MIGRATION_SET_SHA256": bridge.sha256(self.inputs / "release/migrations.sha256"), "COPPER_BACKUP_FORMAT": "copper-backup/v1", "COPPER_EXPORT_FORMAT": "copper-export/v1", "COPPER_VALIDATION_LEVEL": "L4"}
        for name, filename in (("DEPLOY_ARCHIVE", "deploy.tar.gz"), ("RELEASE_NOTES_FILE", "notes.md"), ("ACME_ARCHIVE", "acme.tar.gz"), ("UPGRADE_MATRIX_FILE", "matrix.json")):
            values["COPPER_" + name] = filename
            hash_key = {"RELEASE_NOTES_FILE": "RELEASE_NOTES_SHA256", "UPGRADE_MATRIX_FILE": "UPGRADE_MATRIX_SHA256"}.get(name, name + "_SHA256")
            values["COPPER_" + hash_key] = bridge.sha256(release / filename)
        for role, record in records.items():
            prefix = "COPPER_" + role.upper() + "_"
            values[prefix + "IMAGE"] = record["reference"]
            values[prefix + "PLATFORM"] = "linux/amd64"
            if role != "mysql":
                values[prefix + "SOURCE_COMMIT"] = record["sourceCommit"]
                values[prefix + "BUILD_PROVENANCE_FILE"] = record["provenanceFile"]
                values[prefix + "BUILD_PROVENANCE_SHA256"] = record["provenanceSha256"]
                shutil.copy2(self.inputs / "release" / record["provenanceFile"], release / record["provenanceFile"])
        self.manifest = release / ("copper-release-" + bridge.VERSION + ".env")
        bridge.GUIDED["write_env"](self.manifest, values)
        offline = self.package / "offline"
        for path in release.iterdir():
            if path.name != "SHA256SUMS":
                shutil.copy2(path, offline / path.name)
        (offline / "images.tar.gz").write_bytes(b"fixture combined archive, not imported")
        index = offline / ("copper-delivery-offline-" + bridge.VERSION + ".json")
        with contextlib.redirect_stdout(io.StringIO()):
            bridge.DELIVERY["command_generate"](argparse.Namespace(manifest=offline / self.manifest.name, mode="offline", image_id=[role + "," + r["imageId"] for role, r in records.items()], image_archive=offline / "images.tar.gz", output=index))
        security = release / ("copper-security-" + bridge.VERSION + ".tar.gz")
        security.write_bytes(b"fixture security bundle, verification explicitly mocked")
        Path(str(security) + ".sha256").write_text(bridge.sha256(security) + "  " + security.name + "\n")
        self.security_doc = {"release": {"version": bridge.VERSION, "deployCommit": self.commit, "webSourceCommit": records["web"]["sourceCommit"], "backendSourceCommit": records["backend"]["sourceCommit"], "webImage": records["web"]["reference"], "backendImage": records["backend"]["reference"], "mysqlImage": records["mysql"]["reference"], "manifest": {"sha256": bridge.sha256(self.manifest)}}}
        patch = mock.patch.dict(bridge.SECURITY, {"verify_archive": lambda _: self.security_doc})
        patch.start()
        self.addCleanup(patch.stop)
        docker_values = {"COPPER_DOCKER_TARGET_OS": "ubuntu-24.04", "COPPER_DOCKER_TARGET_ARCH": "amd64", "COPPER_DOCKER_ENGINE_VERSION": "29.6.1", "COPPER_DOCKER_COMPOSE_VERSION": "5.3.1"}
        for index in range(1, 6):
            name = f"fixture{index}_amd64.deb"
            (self.bundle / name).write_bytes(f"fixture{index}".encode())
            docker_values.update({f"COPPER_DOCKER_PACKAGE_{index}_NAME": name, f"COPPER_DOCKER_PACKAGE_{index}_URL": "https://download.docker.com/linux/ubuntu/" + name, f"COPPER_DOCKER_PACKAGE_{index}_SHA256": bridge.sha256(self.bundle / name)})
        bridge.GUIDED["write_env"](self.bundle / "docker-ubuntu-amd64.env", docker_values)
        self.reviewed_docker = self.root / "reviewed-docker.env"
        shutil.copy2(self.bundle / "docker-ubuntu-amd64.env", self.reviewed_docker)
        (self.bundle / "SHA256SUMS").write_text("".join(bridge.sha256(p) + "  " + p.name + "\n" for p in sorted(self.bundle.iterdir())))
        self.refreeze()

    def refreeze(self):
        freeze(self.inputs, "INPUT_CHECKSUMS.json")
        self.status.update({"buildInputsSha256": bridge.sha256(self.inputs / "BUILD_INPUTS.json"), "inputChecksumsSha256": bridge.sha256(self.inputs / "INPUT_CHECKSUMS.json"), "deployBuildCommit": self.doc["deployBuildCommit"]})
        bridge.write_json(self.package / "PACKAGE_STATUS.json", self.status)
        freeze(self.package, "PACKAGE_CHECKSUMS.json")

    def validate(self):
        return bridge.validate_candidates(self.inputs, self.package)

    def test_accepts_current_fixture_and_distinct_producer_import_ids(self):
        result = self.validate()
        self.assertNotEqual(result[2]["images"]["web"]["producerImageId"], result[2]["images"]["web"]["imageId"])
        bridge.validate_docker_bundle(self.bundle, self.reviewed_docker)

    def test_rejects_tampered_or_extra_or_symlink_input(self):
        for mutation in ("tamper", "extra", "link"):
            with self.subTest(mutation=mutation):
                path = self.inputs / ("BUILD_INPUTS.json" if mutation == "tamper" else mutation)
                if mutation == "tamper":
                    old = path.read_bytes()
                    path.write_bytes(old + b" ")
                elif mutation == "extra":
                    path.write_text("unlisted")
                else:
                    path.symlink_to(self.manifest)
                with self.assertRaises(ValueError):
                    self.validate()
                if mutation == "tamper":
                    path.write_bytes(old)
                else:
                    path.unlink()

    def test_rejects_package_bound_to_other_inputs(self):
        self.status["inputChecksumsSha256"] = "f" * 64
        bridge.write_json(self.package / "PACKAGE_STATUS.json", self.status)
        freeze(self.package, "PACKAGE_CHECKSUMS.json")
        with self.assertRaisesRegex(ValueError, "frozen build input"):
            self.validate()

    def test_rejects_old_candidate_even_when_rehashed(self):
        for old_version in ("0.4.0-rc.6", "0.4.0-rc.8", "0.4.0-rc.9", "0.4.0-rc.10"):
            with self.subTest(version=old_version):
                self.doc["version"] = old_version
                bridge.write_json(self.inputs / "BUILD_INPUTS.json", self.doc)
                self.refreeze()
                with self.assertRaisesRegex(ValueError, "rc.11"):
                    self.validate()

    def test_rejects_failed_cleanup_even_when_rehashed(self):
        bridge.write_json(self.package / "PACKAGE_CLEANUP.json", {"runId": self.run_id, "cleanup": "FAILED"})
        self.refreeze()
        with self.assertRaisesRegex(ValueError, "cleanup"):
            self.validate()

    def test_rejects_different_packaged_source_or_runtime_image(self):
        self.doc["images"]["web"]["reference"] = "example.invalid/old@sha256:" + "0" * 64
        bridge.write_json(self.inputs / "BUILD_INPUTS.json", self.doc)
        self.refreeze()
        with self.assertRaisesRegex(ValueError, "offline package image"):
            self.validate()

    def test_rejects_old_security_evidence(self):
        self.security_doc["release"]["version"] = "0.4.0-rc.6"
        with self.assertRaisesRegex(ValueError, "security evidence"):
            self.validate()

    def test_rejects_missing_offline_package(self):
        shutil.rmtree(self.package / "offline")
        self.refreeze()
        with self.assertRaises((ValueError, OSError)):
            self.validate()

    def test_rejects_mismatched_source_provenance_after_rehash(self):
        record = self.doc["images"]["web"]
        record["sourceCommit"] = "e" * 40
        bridge.write_json(self.inputs / "BUILD_INPUTS.json", self.doc)
        self.refreeze()
        with self.assertRaises(ValueError):
            self.validate()

    def test_rejects_partial_docker_bundle(self):
        (self.bundle / "fixture1_amd64.deb").unlink()
        with self.assertRaisesRegex(ValueError, "incomplete"):
            bridge.validate_docker_bundle(self.bundle, self.reviewed_docker)

    def build_fixture(self, args):
        def fake_git(*argv):
            return "" if argv[0] == "status" else self.commit
        def fixture_archive(directory, commit):
            path = directory / ("copper-deploy-" + commit + ".tar.gz")
            with tarfile.open(path, "w:gz") as archive:
                add_file(archive, "copperctl", b"fixture")
            return path
        def existing_builder(source, destination):
            function = bridge.GUIDED["main"]
            with mock.patch.dict(function.__globals__, {"git": fake_git, "create_deploy_archive": fixture_archive}), mock.patch.object(sys, "argv", ["builder", "--source-medium", str(source), "--output", str(destination)]):
                self.assertEqual(function(), 0)
        with mock.patch.object(bridge, "CANDIDATE_DOCKER_MANIFEST", self.reviewed_docker), mock.patch.dict(bridge.GUIDED, {"git": fake_git}), mock.patch.object(bridge, "run_guided", existing_builder), contextlib.redirect_stdout(io.StringIO()):
            bridge.build(args)

    def arguments(self):
        return argparse.Namespace(inputs=self.inputs, package=self.package, docker_bundle=self.bundle, docker_manifest=self.reviewed_docker, output=self.root / "guided", archive=True)

    def test_default_docker_manifest_is_current_candidate_and_cannot_downgrade(self):
        self.assertEqual(bridge.CANDIDATE_DOCKER_MANIFEST, ROOT / "releases" / bridge.VERSION / "docker-ubuntu-amd64.env")
        values = bridge.GUIDED["parse_env"](bridge.CANDIDATE_DOCKER_MANIFEST)
        self.assertEqual(values["COPPER_DOCKER_ENGINE_VERSION"], "29.9.2")
        self.assertEqual(values["COPPER_DOCKER_COMPOSE_VERSION"], "5.6.1")
        names = [values[f"COPPER_DOCKER_PACKAGE_{i}_NAME"] for i in range(1, 6)]
        self.assertTrue(any("containerd.io_2.4.2-" in name for name in names))
        self.assertTrue(any("docker-buildx-plugin_0.38.1-" in name for name in names))
        # Fixture has a closed, valid older 29.x/5.x bundle, but rc.11 must not accept it.
        with mock.patch.object(bridge, "validate_candidates") as candidates, self.assertRaisesRegex(ValueError, "this candidate"):
            bridge.build(self.arguments())
        candidates.assert_not_called()
        self.assertFalse(self.arguments().output.exists())

    def test_current_reviewed_docker_manifest_may_be_relocated_without_changes(self):
        relocated = self.root / "current-reviewed.env"
        relocated.write_bytes(bridge.CANDIDATE_DOCKER_MANIFEST.read_bytes())
        self.assertEqual(bridge.candidate_docker_manifest(relocated), relocated.resolve())
        relocated.write_bytes(relocated.read_bytes() + b"# changed\n")
        with self.assertRaisesRegex(ValueError, "this candidate"):
            bridge.candidate_docker_manifest(relocated)

    def test_build_reuses_guided_builder_and_keeps_pending_state(self):
        args = self.arguments()
        output = args.output
        self.build_fixture(args)
        values = bridge.GUIDED["parse_env"](output / "OPERATOR_VALUES.env")
        self.assertFalse(any("WORKFLOW_RUN" in key for key in values))
        status = bridge.read_json(output / "GUIDED_CANDIDATE_STATUS.json")
        self.assertEqual(status["status"], "INSTALL_RESTORE_ACCEPTANCE_PENDING")
        self.assertFalse(status["networklessHostInstallationAccepted"])
        self.assertTrue(Path(str(output) + ".tar.gz.sha256").is_file())
        for role in bridge.ROLES:
            self.assertEqual(bridge.sha256(output / self.doc["images"][role]["archive"]), self.doc["images"][role]["archiveSha256"])

    def test_interrupted_outer_archive_removes_output_and_partial_archive(self):
        args = self.arguments()
        def interrupted_archive(output):
            Path(str(output) + ".tar.gz").write_bytes(b"partial fixture archive")
            Path(str(output) + ".tar.gz.sha256").write_text("partial fixture sidecar")
            raise KeyboardInterrupt()
        with mock.patch.dict(bridge.GUIDED, {"create_outer_archive": interrupted_archive}):
            with self.assertRaises(KeyboardInterrupt):
                self.build_fixture(args)
        for path in (args.output, Path(str(args.output) + ".tar.gz"), Path(str(args.output) + ".tar.gz.sha256")):
            self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
