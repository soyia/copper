#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import datetime as dt
import io
import json
import pathlib
import tarfile
import tempfile
import types
import unittest


ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("security_evidence", ROOT / "scripts" / "security-evidence.py")
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SecurityEvidenceTest(unittest.TestCase):
    def fixture(self, root: pathlib.Path, secret: bool = False, critical: bool = False) -> tuple[pathlib.Path, pathlib.Path]:
        reports = root / "reports"
        reports.mkdir()
        manifest = root / "copper-release-0.3.0-rc.1.env"
        manifest.write_text(
            "COPPER_RELEASE_VERSION=0.3.0-rc.1\n"
            "COPPER_RELEASE_CHANNEL=rc\n"
            f"COPPER_DEPLOY_COMMIT={'a' * 40}\n"
            f"COPPER_WEB_SOURCE_COMMIT={'b' * 40}\n"
            f"COPPER_BACKEND_SOURCE_COMMIT={'c' * 40}\n"
            f"COPPER_WEB_IMAGE=ghcr.io/soyia/copper_web@sha256:{'d' * 64}\n"
            f"COPPER_BACKEND_IMAGE=ghcr.io/soyia/copper_backend@sha256:{'e' * 64}\n"
            f"COPPER_MYSQL_IMAGE=mysql:8.4.10@sha256:{'f' * 64}\n",
            encoding="utf-8",
        )
        for role in MODULE.ROLES:
            (reports / f"sbom-{role}.cdx.json").write_text(json.dumps({
                "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
                "components": [{"type": "library", "name": "fixture", "version": "1.0"}],
            }), encoding="utf-8")
            matches = []
            if critical and role == "image-backend":
                matches = [{
                    "vulnerability": {"id": "CVE-2099-0001", "severity": "Critical", "fix": {"versions": ["2.0"]}},
                    "artifact": {"name": "fixture", "version": "1.0"},
                }]
            (reports / f"vulnerability-{role}.json").write_text(json.dumps({"matches": matches}), encoding="utf-8")
        for role in MODULE.SECRET_ROLES:
            findings = [{"RuleID": "generic-api-key", "Secret": "REDACTED"}] if secret and role == "source-web" else []
            (reports / f"secrets-{role}.json").write_text(json.dumps(findings), encoding="utf-8")
        (reports / "grype-db-status.json").write_text(json.dumps({
            "schemaVersion": "v6.1.7",
            "built": MODULE.iso(MODULE.utc_now()),
            "path": "/grype-db/6/vulnerability.db",
            "valid": True,
        }), encoding="utf-8")
        (reports / "image-provenance.json").write_text(json.dumps({
            "format": "copper-image-scan-provenance/v1",
            "images": [
                {
                    "role": "web", "publishedReference": f"ghcr.io/soyia/copper_web@sha256:{'d' * 64}",
                    "scannedReference": f"ghcr.io/soyia/copper_web@sha256:{'d' * 64}",
                    "localId": "sha256:" + "1" * 64, "architecture": "amd64", "source": "verified-preloaded",
                    "sourceEvidenceSha256": "a" * 64,
                },
                {
                    "role": "backend", "publishedReference": f"ghcr.io/soyia/copper_backend@sha256:{'e' * 64}",
                    "scannedReference": f"ghcr.io/soyia/copper_backend@sha256:{'e' * 64}",
                    "localId": "sha256:" + "2" * 64, "architecture": "amd64", "source": "registry",
                    "sourceEvidenceSha256": None,
                },
                {
                    "role": "mysql", "publishedReference": f"mysql:8.4.10@sha256:{'f' * 64}",
                    "scannedReference": f"mysql:8.4.10@sha256:{'f' * 64}",
                    "localId": "sha256:" + "3" * 64, "architecture": "amd64", "source": "registry",
                    "sourceEvidenceSha256": None,
                },
            ],
        }), encoding="utf-8")
        return manifest, reports

    def test_builds_and_verifies_deterministic_safe_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            manifest, reports = self.fixture(root)
            output = root / "bundle.tar.gz"
            MODULE.build(types.SimpleNamespace(
                manifest=str(manifest), policy=str(ROOT / "config" / "security-policy.env"),
                reports_dir=str(reports), exceptions=None, output=str(output),
            ))
            evidence = MODULE.verify_archive(output)
            self.assertEqual(evidence["status"], "PASS")
            self.assertEqual(evidence["release"]["version"], "0.3.0-rc.1")
            self.assertEqual(evidence["gate"]["blockingFindings"], 0)

    def test_secret_finding_fails_without_publishing_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            manifest, reports = self.fixture(root, secret=True)
            output = root / "bundle.tar.gz"
            with self.assertRaises(MODULE.SecurityEvidenceError):
                MODULE.build(types.SimpleNamespace(
                    manifest=str(manifest), policy=str(ROOT / "config" / "security-policy.env"),
                    reports_dir=str(reports), exceptions=None, output=str(output),
                ))
            self.assertFalse(output.exists())

    def test_unexcepted_critical_vulnerability_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            manifest, reports = self.fixture(root, critical=True)
            output = root / "bundle.tar.gz"
            with self.assertRaises(MODULE.SecurityEvidenceError):
                MODULE.build(types.SimpleNamespace(
                    manifest=str(manifest), policy=str(ROOT / "config" / "security-policy.env"),
                    reports_dir=str(reports), exceptions=None, output=str(output),
                ))
            self.assertFalse(output.exists())

    def test_stale_grype_database_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            manifest, reports = self.fixture(root)
            stale = MODULE.iso(MODULE.utc_now() - dt.timedelta(hours=73))
            (reports / "grype-db-status.json").write_text(json.dumps({
                "schemaVersion": "v6.1.7",
                "built": stale,
                "path": "/grype-db/6/vulnerability.db",
                "valid": True,
            }), encoding="utf-8")
            output = root / "bundle.tar.gz"
            with self.assertRaises(MODULE.SecurityEvidenceError):
                MODULE.build(types.SimpleNamespace(
                    manifest=str(manifest), policy=str(ROOT / "config" / "security-policy.env"),
                    reports_dir=str(reports), exceptions=None, output=str(output),
                ))
            self.assertFalse(output.exists())

    def test_exception_requires_hash_bound_evidence_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            evidence = root / "analysis.json.gz"
            evidence.write_bytes(b"reviewed binary analysis")
            evidence_sha = MODULE.sha256_file(evidence)
            generated_at = MODULE.utc_now()
            exception_path = root / "exceptions.json"
            exception = {
                "format": MODULE.EXCEPTION_FORMAT,
                "releaseVersion": "0.3.0-rc.1",
                "exceptions": [{
                    "vulnerabilityId": "CVE-2099-0001",
                    "artifactRole": "image-mysql",
                    "packageName": "stdlib",
                    "installedVersion": "go1.24.6",
                    "severity": "Critical",
                    "disposition": "RISK_ACCEPTED",
                    "owner": "security-owner",
                    "reason": "Binary reachability analysis was reviewed.",
                    "compensatingControl": "Database is isolated and the helper runs only during entrypoint setup.",
                    "evidenceFile": evidence.name,
                    "evidenceSha256": evidence_sha,
                    "expiresAt": MODULE.iso(generated_at + dt.timedelta(days=1)),
                }],
            }
            exception_path.write_text(json.dumps(exception), encoding="utf-8")
            policy = MODULE.parse_env(ROOT / "config" / "security-policy.env", "policy")
            loaded, record, files = MODULE.load_exceptions(
                exception_path, "0.3.0-rc.1", generated_at, policy
            )
            self.assertEqual(len(loaded), 1)
            self.assertEqual(record["evidenceFiles"][0]["sha256"], evidence_sha)
            self.assertEqual(files, [evidence.resolve()])

            exception["exceptions"][0]["evidenceSha256"] = "0" * 64
            exception_path.write_text(json.dumps(exception), encoding="utf-8")
            with self.assertRaises(MODULE.SecurityEvidenceError):
                MODULE.load_exceptions(exception_path, "0.3.0-rc.1", generated_at, policy)

    def test_m4_candidate_exceptions_bind_the_reviewed_raw_evidence(self) -> None:
        policy = MODULE.parse_env(ROOT / "config" / "security-policy.env", "policy")
        generated_at = dt.datetime(2026, 7, 14, 17, 42, tzinfo=dt.timezone.utc)
        for version in ("0.3.0-rc.1", "0.3.0-rc.2"):
            with self.subTest(version=version):
                exceptions, record, files = MODULE.load_exceptions(
                    ROOT / "releases" / version / "security-exceptions.json",
                    version, generated_at, policy,
                )
                self.assertEqual(len(exceptions), 3)
                self.assertEqual(len(files), 1)
                self.assertEqual(
                    record["evidenceFiles"][0]["sha256"],
                    "69519bbae8554e9f154e637c6e1f6bc2757b5253eca941234322ee62ebef0b88",
                )

    def test_minimal_pass_claim_is_not_a_valid_evidence_document(self) -> None:
        with self.assertRaises(MODULE.SecurityEvidenceError):
            MODULE.validate_evidence_structure({
                "format": MODULE.FORMAT,
                "status": "PASS",
                "gate": {"status": "PASS", "secretFindings": 0, "blockingFindings": 0},
            })

    def test_bundle_with_unindexed_extra_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            manifest, reports = self.fixture(root)
            original = root / "bundle.tar.gz"
            MODULE.build(types.SimpleNamespace(
                manifest=str(manifest), policy=str(ROOT / "config" / "security-policy.env"),
                reports_dir=str(reports), exceptions=None, output=str(original),
            ))
            tampered = root / "tampered.tar.gz"
            with tarfile.open(original, "r:gz") as source, tarfile.open(tampered, "w:gz") as target:
                for member in source.getmembers():
                    content = source.extractfile(member).read()
                    copied = tarfile.TarInfo(member.name)
                    copied.size = len(content)
                    copied.mode = 0o444
                    target.addfile(copied, io.BytesIO(content))
                extra = b"not indexed by security-evidence.json"
                info = tarfile.TarInfo("copper-security-0.3.0-rc.1/unindexed.txt")
                info.size = len(extra)
                info.mode = 0o444
                target.addfile(info, io.BytesIO(extra))
            with self.assertRaisesRegex(MODULE.SecurityEvidenceError, "文件集合不精确"):
                MODULE.verify_archive(tampered)


if __name__ == "__main__":
    unittest.main()
