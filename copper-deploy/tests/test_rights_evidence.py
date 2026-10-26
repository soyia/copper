#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import io
import json
import pathlib
import shutil
import subprocess
import tarfile
import tempfile
import types
import unittest


ROOT = pathlib.Path(__file__).resolve().parent.parent


def load(name: str, path: pathlib.Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


RIGHTS = load("rights_evidence", ROOT / "scripts" / "rights-evidence.py")
SECURITY = load("security_evidence_for_rights", ROOT / "scripts" / "security-evidence.py")


class RightsEvidenceTest(unittest.TestCase):
    def manifest(self, root: pathlib.Path) -> pathlib.Path:
        path = root / "copper-release-0.4.0-rc.1.env"
        path.write_text(
            "COPPER_RELEASE_VERSION=0.4.0-rc.1\n"
            "COPPER_RELEASE_CHANNEL=rc\n"
            "COPPER_RELEASED_AT=2026-07-16T00:00:00Z\n"
            f"COPPER_DEPLOY_COMMIT={'a' * 40}\n"
            f"COPPER_WEB_SOURCE_COMMIT={'b' * 40}\n"
            f"COPPER_BACKEND_SOURCE_COMMIT={'c' * 40}\n"
            f"COPPER_WEB_IMAGE=ghcr.io/soyia/copper_web@sha256:{'d' * 64}\n"
            f"COPPER_BACKEND_IMAGE=ghcr.io/soyia/copper_backend@sha256:{'e' * 64}\n"
            f"COPPER_MYSQL_IMAGE=mysql:8.4.10@sha256:{'f' * 64}\n",
            encoding="utf-8",
        )
        return path

    def security_bundle(self, root: pathlib.Path, manifest: pathlib.Path) -> pathlib.Path:
        reports = root / "reports"
        reports.mkdir()
        for role in SECURITY.ROLES:
            components = [{
                "type": "library",
                "name": "fixture",
                "version": "1.0.0",
                "purl": "pkg:generic/fixture@1.0.0",
                "licenses": [{"license": {"id": "MIT"}}],
            }]
            if role == "image-mysql":
                components.append({
                    "type": "file",
                    "name": "/etc/example.conf",
                    "version": None,
                })
            (reports / f"sbom-{role}.cdx.json").write_text(json.dumps({
                "bomFormat": "CycloneDX",
                "specVersion": "1.6",
                "version": 1,
                "components": components,
            }), encoding="utf-8")
            (reports / f"vulnerability-{role}.json").write_text(
                json.dumps({"matches": []}), encoding="utf-8"
            )
        for role in SECURITY.SECRET_ROLES:
            (reports / f"secrets-{role}.json").write_text("[]", encoding="utf-8")
        (reports / "grype-db-status.json").write_text(json.dumps({
            "schemaVersion": "v6.1.7",
            "built": SECURITY.iso(SECURITY.utc_now()),
            "path": "/grype-db/6/vulnerability.db",
            "valid": True,
        }), encoding="utf-8")
        (reports / "image-provenance.json").write_text(json.dumps({
            "format": "copper-image-scan-provenance/v1",
            "images": [
                {
                    "role": "web",
                    "publishedReference": f"ghcr.io/soyia/copper_web@sha256:{'d' * 64}",
                    "scannedReference": f"ghcr.io/soyia/copper_web@sha256:{'d' * 64}",
                    "localId": "sha256:" + "1" * 64,
                    "architecture": "amd64",
                    "source": "registry",
                    "sourceEvidenceSha256": None,
                },
                {
                    "role": "backend",
                    "publishedReference": f"ghcr.io/soyia/copper_backend@sha256:{'e' * 64}",
                    "scannedReference": f"ghcr.io/soyia/copper_backend@sha256:{'e' * 64}",
                    "localId": "sha256:" + "2" * 64,
                    "architecture": "amd64",
                    "source": "registry",
                    "sourceEvidenceSha256": None,
                },
                {
                    "role": "mysql",
                    "publishedReference": f"mysql:8.4.10@sha256:{'f' * 64}",
                    "scannedReference": f"mysql:8.4.10@sha256:{'f' * 64}",
                    "localId": "sha256:" + "3" * 64,
                    "architecture": "amd64",
                    "source": "registry",
                    "sourceEvidenceSha256": None,
                },
            ],
        }), encoding="utf-8")
        bundle = root / "security.tar.gz"
        SECURITY.build(types.SimpleNamespace(
            manifest=str(manifest),
            policy=str(ROOT / "config" / "security-policy.env"),
            reports_dir=str(reports),
            exceptions=None,
            output=str(bundle),
        ))
        return bundle

    def fixture(self, root: pathlib.Path) -> dict[str, pathlib.Path]:
        manifest = self.manifest(root)
        security = self.security_bundle(root, manifest)
        inventory = root / "inventory.json"
        inventory.write_text(json.dumps({
            "format": RIGHTS.INVENTORY_FORMAT,
            "releaseVersion": "0.4.0-rc.1",
            "repositories": [
                {
                    "role": "source-web",
                    "commit": "b" * 40,
                    "assetCount": 1,
                    "assets": [{
                        "path": "public/example.svg",
                        "category": "image-or-icon",
                        "bytes": 7,
                        "sha256": "1" * 64,
                    }],
                    "buildInputs": [{
                        "path": "Dockerfile",
                        "bytes": 10,
                        "sha256": "2" * 64,
                        "references": ["tool.example/build@sha256:" + "9" * 64],
                    }],
                },
                {
                    "role": "source-backend",
                    "commit": "c" * 40,
                    "assetCount": 0,
                    "assets": [],
                    "buildInputs": [],
                },
                {
                    "role": "source-deploy",
                    "commit": "a" * 40,
                    "assetCount": 0,
                    "assets": [],
                    "buildInputs": [],
                },
            ],
        }), encoding="utf-8")
        license_dir = root / "licenses"
        license_dir.mkdir()
        (license_dir / "MIT.txt").write_text("MIT fixture text\n", encoding="utf-8")
        policy = json.loads(
            (ROOT / "config" / "third-party-rights-policy.json").read_text(encoding="utf-8")
        )
        tool_references = {
            "tool.example/build@sha256:" + "9" * 64,
            "mysql:8.4.10@sha256:" + "f" * 64,
            "docker.io/anchore/syft@sha256:86fde6445b483d902fe011dd9f68c4987dd94e07da1e9edc004e3c2422650de6",
            "docker.io/anchore/grype@sha256:391bfda62888fb4e98ff5c4c81598f7431a3c1eac3f8519d69d1ff00df247c1d",
            "ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f",
        }

        def common(identifier: str) -> dict[str, object]:
            return {
                "id": identifier,
                "source": "https://example.test/upstream",
                "rightsHolders": ["Fixture upstream author"],
                "licenseExpression": "MIT",
                "licenseTextFile": "MIT.txt",
                "usage": "Unit-test fixture",
                "redistributionStatus": "ENGINEERING_APPROVED",
                "attribution": "Fixture attribution",
                "notice": "No additional NOTICE requirement in this fixture.",
                "evidenceSummary": "Fixture metadata was reviewed.",
                "reviewedBy": "test-rights-owner",
                "reviewedAt": "2026-07-15T00:00:00Z",
            }

        component = common("fixture-component")
        component.update({
            "componentKey": "pkg:generic/fixture@1.0.0",
            "roles": list(RIGHTS.SBOM_ROLES),
            "relationship": "direct",
        })
        asset = common("fixture-asset")
        asset.update({"repositoryRole": "source-web", "pathPrefix": "public/"})
        tools = []
        for index, reference in enumerate(sorted(tool_references)):
            item = common(f"fixture-tool-{index}")
            item.update({
                "reference": reference,
                "category": "runtime-image" if reference.startswith("mysql:") else "build-tool",
            })
            tools.append(item)
        ledger = root / "ledger.json"
        ledger.write_text(json.dumps({
            "format": RIGHTS.LEDGER_FORMAT,
            "releaseVersion": "0.4.0-rc.1",
            "engineeringReview": {
                "owner": "test-rights-owner",
                "reviewedAt": "2026-07-15T00:00:00Z",
                "status": "ENGINEERING_REVIEWED",
            },
            "legalReview": {"status": "REQUIRED", "issue": policy["legalReviewIssue"]},
            "componentRights": [component],
            "assetRights": [asset],
            "toolRights": tools,
        }), encoding="utf-8")
        return {
            "manifest": manifest,
            "security": security,
            "inventory": inventory,
            "ledger": ledger,
            "licenses": license_dir,
        }

    def build(self, fixture: dict[str, pathlib.Path], output: pathlib.Path) -> None:
        RIGHTS.build(types.SimpleNamespace(
            manifest=str(fixture["manifest"]),
            policy=str(ROOT / "config" / "third-party-rights-policy.json"),
            security_bundle=str(fixture["security"]),
            inventory=str(fixture["inventory"]),
            ledger=str(fixture["ledger"]),
            license_dir=str(fixture["licenses"]),
            exceptions=None,
            output=str(output),
        ))

    def test_builds_deterministic_bundle_with_six_sboms_and_notice(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            fixture = self.fixture(root)
            first = root / "first.tar.gz"
            second = root / "second.tar.gz"
            self.build(fixture, first)
            self.build(fixture, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            evidence = RIGHTS.verify_archive(first)
            self.assertEqual(evidence["status"], "ENGINEERING_PASS")
            self.assertEqual(evidence["counts"]["sboms"], 6)
            self.assertTrue(evidence["gate"]["legalReviewRequired"])
            with tarfile.open(first, "r:gz") as archive:
                notice_member = next(
                    member for member in archive.getmembers()
                    if member.name.endswith("/THIRD_PARTY_NOTICES.md")
                )
                notice = archive.extractfile(notice_member).read().decode("utf-8")
            self.assertIn("source-web:public/", notice)

    def test_unknown_component_fails_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            fixture = self.fixture(root)
            ledger = json.loads(fixture["ledger"].read_text(encoding="utf-8"))
            ledger["componentRights"] = []
            fixture["ledger"].write_text(json.dumps(ledger), encoding="utf-8")
            output = root / "blocked.tar.gz"
            with self.assertRaisesRegex(RIGHTS.RightsEvidenceError, "缺少唯一权利台账覆盖"):
                self.build(fixture, output)
            self.assertFalse(output.exists())

    def local_provenance(self, fixture, *, registry="registry.example/tool@sha256:" + "7" * 64):
        manifest = fixture["manifest"]
        path = manifest.parent / "web-local-build.json"
        document = {
            "format": "copper-local-build/v1", "role": "web", "sourceCommit": "b" * 40,
            "buildId": "synthetic-rights-test", "builtAt": "2026-07-16T00:00:00Z",
            "platform": "linux/amd64", "image": "ghcr.io/soyia/copper_web@sha256:" + "d" * 64,
            "imageId": "sha256:" + "6" * 64, "builder": "synthetic-fixture",
            "command": "synthetic-test-only", "tools": {
                "docker": "29.6.0", "registryImage": registry,
                "buildkitImage": "buildkit.example/tool@sha256:" + "8" * 64,
            },
        }
        path.write_text(json.dumps(document))
        with manifest.open("a") as stream:
            stream.write("COPPER_WEB_PLATFORM=linux/amd64\n")
            stream.write("COPPER_WEB_BUILD_PROVENANCE_FILE=" + path.name + "\n")
            stream.write("COPPER_WEB_BUILD_PROVENANCE_SHA256=" + RIGHTS.sha256_file(path) + "\n")
        return path, document

    def test_local_factory_tool_images_require_unique_license_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            fixture = self.fixture(root)
            path, provenance = self.local_provenance(fixture)
            # Refresh the synthetic security bundle after adding bound provenance.
            fixture["security"].unlink()
            shutil.rmtree(root / "reports")
            fixture["security"] = self.security_bundle(root, fixture["manifest"])
            output = root / "rights.tar.gz"
            with self.assertRaisesRegex(RIGHTS.RightsEvidenceError, "缺少唯一权利台账覆盖"):
                self.build(fixture, output)
            self.assertFalse(output.exists())
            ledger = json.loads(fixture["ledger"].read_text())
            for key in ("registryImage", "buildkitImage"):
                template = dict(ledger["toolRights"][0])
                template.update(id="synthetic-" + key, reference=provenance["tools"][key], category="build-tool")
                ledger["toolRights"].append(template)
            fixture["ledger"].write_text(json.dumps(ledger))
            self.build(fixture, output)
            evidence = RIGHTS.verify_archive(output)
            references = {item["reference"] for item in evidence["tools"]}
            self.assertTrue({provenance["tools"][key] for key in ("registryImage", "buildkitImage")} <= references)
            with tarfile.open(output) as archive:
                member = next(item for item in archive.getmembers() if item.name.endswith("/release/web-local-build.json"))
                self.assertEqual(archive.extractfile(member).read(), path.read_bytes())

    def test_tampered_or_unpinned_local_factory_provenance_fails(self) -> None:
        for mode in ("tampered", "unpinned"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                fixture = self.fixture(pathlib.Path(directory))
                path, _ = self.local_provenance(fixture, registry="registry.example/tool:latest" if mode == "unpinned" else "registry.example/tool@sha256:" + "7" * 64)
                if mode == "tampered":
                    path.write_text(path.read_text() + " ")
                with self.assertRaisesRegex(RIGHTS.RightsEvidenceError, "SHA-256|不可变镜像摘要"):
                    RIGHTS.local_build_provenances(fixture["manifest"], RIGHTS.validate_manifest(fixture["manifest"]))

    def test_discovery_is_assignable_and_excludes_syft_file_observations(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            fixture = self.fixture(root)
            output = root / "discovery.json"
            RIGHTS.discover(types.SimpleNamespace(
                manifest=str(fixture["manifest"]),
                security_bundle=str(fixture["security"]),
                output=str(output),
            ))
            document = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(document["format"], RIGHTS.DISCOVERY_FORMAT)
            self.assertEqual(document["status"], "REVIEW_REQUIRED")
            self.assertEqual(document["counts"], {
                "components": 1,
                "sbomLicenseMetadataMissing": 0,
            })
            component = document["components"][0]
            self.assertEqual(component["componentKey"], "pkg:generic/fixture@1.0.0")
            self.assertEqual(component["types"], ["library"])
            self.assertEqual(component["roles"], sorted(RIGHTS.SBOM_ROLES))
            self.assertNotIn("file", component["types"])

    def test_sbom_and_ledger_license_conflict_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            fixture = self.fixture(root)
            ledger = json.loads(fixture["ledger"].read_text(encoding="utf-8"))
            ledger["componentRights"][0]["licenseExpression"] = "Apache-2.0"
            fixture["ledger"].write_text(json.dumps(ledger), encoding="utf-8")
            with self.assertRaisesRegex(RIGHTS.RightsEvidenceError, "许可证.*冲突"):
                self.build(fixture, root / "conflict.tar.gz")

    def test_blocked_content_asset_cannot_be_excepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            fixture = self.fixture(root)
            ledger = json.loads(fixture["ledger"].read_text(encoding="utf-8"))
            ledger["assetRights"][0]["redistributionStatus"] = "BLOCKED"
            fixture["ledger"].write_text(json.dumps(ledger), encoding="utf-8")
            with self.assertRaisesRegex(RIGHTS.RightsEvidenceError, "明确 BLOCKED"):
                self.build(fixture, root / "blocked.tar.gz")

    def test_review_exception_requires_owner_expiry_summary_and_bound_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            fixture = self.fixture(root)
            ledger = json.loads(fixture["ledger"].read_text(encoding="utf-8"))
            ledger["assetRights"][0]["redistributionStatus"] = "REVIEW_REQUIRED"
            fixture["ledger"].write_text(json.dumps(ledger), encoding="utf-8")
            evidence_file = root / "authorization-summary.txt"
            evidence_file.write_text("sanitized authorization summary\n", encoding="utf-8")
            exception_path = root / "exceptions.json"
            exception_path.write_text(json.dumps({
                "format": RIGHTS.EXCEPTION_FORMAT,
                "releaseVersion": "0.4.0-rc.1",
                "exceptions": [{
                    "subjectType": "asset",
                    "subjectId": "fixture-asset",
                    "disposition": "WRITTEN_AUTHORIZATION_CONFIRMED",
                    "owner": "named-rights-owner",
                    "reason": "Release-specific written authorization was reviewed.",
                    "expiresAt": "2026-07-20T00:00:00Z",
                    "evidenceSummary": "The exact asset prefix and redistribution modes are covered.",
                    "evidenceFile": evidence_file.name,
                    "evidenceSha256": RIGHTS.sha256_file(evidence_file),
                }],
            }), encoding="utf-8")
            output = root / "excepted.tar.gz"
            RIGHTS.build(types.SimpleNamespace(
                manifest=str(fixture["manifest"]),
                policy=str(ROOT / "config" / "third-party-rights-policy.json"),
                security_bundle=str(fixture["security"]),
                inventory=str(fixture["inventory"]),
                ledger=str(fixture["ledger"]),
                license_dir=str(fixture["licenses"]),
                exceptions=str(exception_path),
                output=str(output),
            ))
            self.assertEqual(RIGHTS.verify_archive(output)["counts"]["exceptions"], 1)
            exception = json.loads(exception_path.read_text(encoding="utf-8"))
            exception["exceptions"][0]["evidenceSha256"] = "0" * 64
            exception_path.write_text(json.dumps(exception), encoding="utf-8")
            with self.assertRaisesRegex(RIGHTS.RightsEvidenceError, "摘要不一致"):
                RIGHTS.build(types.SimpleNamespace(
                    manifest=str(fixture["manifest"]),
                    policy=str(ROOT / "config" / "third-party-rights-policy.json"),
                    security_bundle=str(fixture["security"]),
                    inventory=str(fixture["inventory"]),
                    ledger=str(fixture["ledger"]),
                    license_dir=str(fixture["licenses"]),
                    exceptions=str(exception_path),
                    output=str(root / "invalid.tar.gz"),
                ))

    def test_unindexed_archive_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            fixture = self.fixture(root)
            original = root / "original.tar.gz"
            self.build(fixture, original)
            tampered = root / "tampered.tar.gz"
            with tarfile.open(original, "r:gz") as source, tarfile.open(tampered, "w:gz") as target:
                for member in source.getmembers():
                    data = source.extractfile(member).read()
                    copy = tarfile.TarInfo(member.name)
                    copy.size = len(data)
                    copy.mode = 0o444
                    target.addfile(copy, io.BytesIO(data))
                extra = b"not indexed"
                info = tarfile.TarInfo("copper-rights-0.4.0-rc.1/extra.txt")
                info.size = len(extra)
                info.mode = 0o444
                target.addfile(info, io.BytesIO(extra))
            with self.assertRaisesRegex(RIGHTS.RightsEvidenceError, "文件集合不精确"):
                RIGHTS.verify_archive(tampered)

    def test_inventory_binds_clean_commits_assets_and_build_references(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            repositories = {}
            commits = {}
            for role in RIGHTS.SOURCE_ROLES:
                repo = root / role
                repo.mkdir()
                subprocess.run(["git", "init", "-q", str(repo)], check=True)
                subprocess.run(["git", "-C", str(repo), "config", "user.name", "Test"], check=True)
                subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.com"], check=True)
                (repo / "Dockerfile").write_text(
                    "# syntax=docker/dockerfile:1.7@sha256:" + "1" * 64 + "\n"
                    "FROM example/build@sha256:" + "2" * 64 + "\n",
                    encoding="utf-8",
                )
                workflow = repo / ".github" / "workflows"
                workflow.mkdir(parents=True)
                (workflow / "build.yml").write_text(
                    "steps:\n"
                    "  - uses: actions/checkout@" + "3" * 40 + "\n",
                    encoding="utf-8",
                )
                content = repo / "docs" / "product-catalog" / "fixture"
                content.mkdir(parents=True)
                (content / "image.svg").write_text("<svg/>\n", encoding="utf-8")
                subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
                subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
                repositories[role] = repo
                commits[role] = subprocess.check_output(
                    ["git", "-C", str(repo), "rev-parse", "HEAD"], text=True
                ).strip()
            manifest = root / "manifest.env"
            manifest.write_text(
                "COPPER_RELEASE_VERSION=0.4.0-rc.1\n"
                "COPPER_RELEASED_AT=2026-07-16T00:00:00Z\n"
                f"COPPER_DEPLOY_COMMIT={commits['source-deploy']}\n"
                f"COPPER_WEB_SOURCE_COMMIT={commits['source-web']}\n"
                f"COPPER_BACKEND_SOURCE_COMMIT={commits['source-backend']}\n"
                f"COPPER_WEB_IMAGE=example/web@sha256:{'3' * 64}\n"
                f"COPPER_BACKEND_IMAGE=example/backend@sha256:{'4' * 64}\n"
                f"COPPER_MYSQL_IMAGE=mysql@sha256:{'5' * 64}\n",
                encoding="utf-8",
            )
            output = root / "inventory.json"
            RIGHTS.inventory(types.SimpleNamespace(
                manifest=str(manifest),
                policy=str(ROOT / "config" / "third-party-rights-policy.json"),
                web_repo=str(repositories["source-web"]),
                backend_repo=str(repositories["source-backend"]),
                deploy_repo=str(repositories["source-deploy"]),
                output=str(output),
            ))
            document = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(sum(item["assetCount"] for item in document["repositories"]), 3)
            references = {
                reference
                for repo in document["repositories"]
                for build in repo["buildInputs"]
                for reference in build["references"]
            }
            self.assertIn("example/build@sha256:" + "2" * 64, references)
            self.assertIn("actions/checkout@" + "3" * 40, references)


if __name__ == "__main__":
    unittest.main()
