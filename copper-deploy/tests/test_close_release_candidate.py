"""Safety contracts; all Docker operations are mocked, never run on the Mac."""
import importlib.util
import datetime as dt
import shutil
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("closure", ROOT / "scripts/close-release-candidate.py")
closure = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(closure)
RUN = "123456abcdef"
COMMIT = "a" * 40
DIGEST = "sha256:" + "b" * 64
PRODUCER = "sha256:" + "c" * 64
ADDRESS = "127.0.0.1:53123"


def result(code=0, stdout="", stderr=""):
    return subprocess.CompletedProcess([], code, stdout, stderr)


def image(role="web"):
    return {"Id": DIGEST, "Architecture": "amd64", "Os": "linux", "RepoDigests": [ADDRESS + "/copper-" + role + "@" + DIGEST], "Config": {"Labels": {"org.opencontainers.image.revision": COMMIT, "copper.release.run_id": RUN}}}


def state():
    return {"runId": RUN, "registry": "copper-closure-registry-" + RUN, "builder": "copper-closure-" + RUN, "tags": []}


def inputs(root):
    for name in ("images", "preloaded", "release"):
        (root / name).mkdir()
    records = {}
    for role in ("web", "backend", "mysql"):
        archive = "images/copper-" + role + "-amd64.tar.gz"
        provenance = role + "-local-build.json"
        (root / archive).write_bytes(b"synthetic unit-test archive")
        (root / "release" / provenance).write_text("{}\n")
        records[role] = {"reference": ADDRESS + "/copper-" + role + "@" + DIGEST, "localTag": ADDRESS + "/copper-" + role + ":" + RUN, "imageId": DIGEST, "importedImageId": DIGEST, "producerImageId": PRODUCER, "sourceCommit": COMMIT, "archive": archive, "archiveSha256": closure.sha256(root / archive), "provenanceFile": provenance, "provenanceSha256": closure.sha256(root / "release" / provenance)}
    (root / "release/acme.tar.gz").write_bytes(b"synthetic acme fixture")
    doc = {"format": "copper-release-build-inputs/v1", "version": closure.VERSION, "runId": RUN, "deployBuildCommit": COMMIT, "images": records, "acmeArchive": "acme.tar.gz", "tools": {"registryImage": "registry.invalid/registry@" + DIGEST, "buildkitImage": "registry.invalid/buildkit@" + DIGEST}}
    closure.write_json(root / "BUILD_INPUTS.json", doc)
    rehash(root)
    return doc


def rehash(root):
    closure.write_json(root / "INPUT_CHECKSUMS.json", {str(p.relative_to(root)): closure.sha256(p) for p in root.rglob("*") if p.is_file() and p.name != "INPUT_CHECKSUMS.json"})


class ClosureSafetyTest(unittest.TestCase):
    def test_candidate_version_matches_policy_and_current_plan(self):
        policy = dict(line.split("=", 1) for line in (ROOT / "config/release-policy.env").read_text().splitlines() if line.startswith("COPPER_"))
        self.assertEqual(closure.VERSION, policy["COPPER_NEXT_CANDIDATE_VERSION"])
        plan = json.loads((ROOT / "releases" / closure.VERSION / "CLOSURE_PLAN.json").read_text())
        self.assertEqual(plan["version"], closure.VERSION)
        self.assertEqual(plan["priorCandidate"]["version"], "0.4.0-rc.10")
        self.assertFalse(plan["priorCandidate"]["reusedAsPassingEvidence"])

    def test_rc11_keeps_exact_docker_inputs_and_empty_upgrade_scope(self):
        candidate = ROOT / "releases" / closure.VERSION
        self.assertEqual(closure.VERSION, "0.4.0-rc.11")
        self.assertEqual((candidate / "docker-ubuntu-amd64.env").read_bytes(),
                         (ROOT / "releases/0.4.0-rc.10/docker-ubuntu-amd64.env").read_bytes())
        plan = json.loads((candidate / "CLOSURE_PLAN.json").read_text())
        self.assertEqual(plan["directUpgradeSources"], [])
        self.assertTrue(plan["rc10FreezeUnchanged"])
        self.assertEqual(plan["priorCandidate"]["immutableFreezeCommit"], "f0ba8cc5b7660ff0a8dae88db641f433f0f0628b")
        self.assertEqual(closure.candidate_matrix(68), {
            "format": "copper-upgrade-matrix/v1", "targetVersion": closure.VERSION,
            "targetSchema": "68", "sources": [], "freshInstallOnly": True})

    def test_frozen_older_inputs_cannot_be_reused_even_after_rehash(self):
        for old_version in ("0.4.0-rc.8", "0.4.0-rc.9", "0.4.0-rc.10"):
            with self.subTest(version=old_version), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                doc = inputs(root)
                doc["version"] = old_version
                closure.write_json(root / "BUILD_INPUTS.json", doc)
                rehash(root)
                before = (root / "BUILD_INPUTS.json").read_bytes()
                with self.assertRaisesRegex(ValueError, "format/version"):
                    closure.load_inputs(root)
                self.assertEqual((root / "BUILD_INPUTS.json").read_bytes(), before)

    def test_platform_rejects_mac_before_any_docker_operation(self):
        with patch.object(closure.os, "uname", return_value=SimpleNamespace(machine="arm64")), patch.object(closure, "run") as tool:
            with self.assertRaisesRegex(ValueError, "Ubuntu 24.04"):
                closure.platform()
            tool.assert_not_called()

    def test_platform_rejects_remote_context_before_daemon_mutation(self):
        with patch.object(closure.os, "uname", return_value=SimpleNamespace(machine="x86_64")), patch.object(closure.Path, "exists", return_value=True), patch.object(closure.Path, "read_text", return_value='ID=ubuntu\nVERSION_ID="24.04"'), patch.dict(closure.os.environ, {}, clear=True), patch.object(closure, "run", return_value=result(stdout='[{"Endpoints":{"docker":{"Host":"ssh://another-host"}}}]')) as tool:
            with self.assertRaisesRegex(ValueError, "local system Docker"):
                closure.platform()
            self.assertEqual(tool.call_count, 1)

    def test_loopback_digest_is_accepted_by_existing_release_contract(self):
        spec = importlib.util.spec_from_file_location("release_contract", ROOT / "scripts/release-contract.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertTrue(module.IMAGE_RE.fullmatch(ADDRESS + "/copper-web@" + DIGEST))

    def test_cached_origin_main_cannot_replace_actual_github_main(self):
        def git(_, *args):
            if args[0] == "remote":
                return "git" + "@github.com:soyia/copper_deploy.git"
            if args[0] == "ls-remote":
                return "d" * 40 + "\trefs/heads/main"
            return COMMIT
        with patch.object(closure, "git", side_effect=git), self.assertRaisesRegex(ValueError, "current GitHub main"):
            closure.reviewed_main(Path("checkout"), COMMIT, "deploy")

    def test_each_role_accepts_only_its_actual_github_origin(self):
        for role, repo in (("web", "copper_web"), ("backend", "copper_backend"), ("deploy", "copper_deploy")):
            for prefix in ("git" + "@github.com:", "https://github.com/"):
                def git(_, *args):
                    if args[0] == "remote":
                        return prefix + "soyia/" + repo + ".git"
                    if args[0] == "ls-remote":
                        return COMMIT + "\trefs/heads/main"
                    return COMMIT
                with self.subTest(role=role, protocol=prefix), patch.object(closure, "git", side_effect=git):
                    closure.reviewed_main(Path("checkout"), COMMIT, role)
                    other = "backend" if role != "backend" else "web"
                    with self.assertRaisesRegex(ValueError, "exact"):
                        closure.reviewed_main(Path("checkout"), COMMIT, other)

    def test_generated_candidate_matrix_meets_official_release_contract(self):
        spec = importlib.util.spec_from_file_location("release_contract_matrix", ROOT / "scripts/release-contract.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        matrix = closure.candidate_matrix(68)
        self.assertEqual(module.validate_matrix_document(matrix), matrix)
        self.assertTrue(matrix["freshInstallOnly"])
        self.assertEqual(matrix["sources"], [])

    def test_image_platform_and_source_must_match(self):
        for field, value in (("Architecture", "arm64"), ("Os", "windows"), ("RepoDigests", [])):
            document = image()
            document[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                closure.image_identity(document, repository=ADDRESS + "/copper-web", commit=COMMIT, run_id=RUN)
        document = image()
        document["Config"]["Labels"]["org.opencontainers.image.revision"] = "d" * 40
        with self.assertRaises(ValueError):
            closure.image_identity(document, repository=ADDRESS + "/copper-web", commit=COMMIT, run_id=RUN)

    def test_import_id_can_differ_from_producer_config_id(self):
        record = {"localTag": ADDRESS + "/copper-web:" + RUN, "reference": ADDRESS + "/copper-web@" + DIGEST, "sourceCommit": COMMIT, "producerImageId": PRODUCER, "importedImageId": DIGEST}
        with patch.object(closure, "run", return_value=result()), patch.object(closure, "inspect", return_value=image()):
            self.assertEqual(closure.import_image(Path("unit.tar.gz"), record, RUN), DIGEST)

    def test_import_rejects_wrong_actual_id_before_push(self):
        with patch.object(closure, "run", return_value=result()) as tool, patch.object(closure, "inspect", return_value={"Id": PRODUCER}):
            with self.assertRaisesRegex(ValueError, "loaded image ID"):
                closure.import_image(Path("unit.tar.gz"), {"localTag": "unit", "importedImageId": DIGEST}, RUN)
            self.assertEqual(tool.call_count, 1)

    def test_cleanup_daemon_connection_error_never_passes(self):
        def broken(argv, **kwargs):
            if kwargs.get("check", True):
                raise RuntimeError("Cannot connect to Docker daemon")
            return result(1, stderr="Cannot connect to Docker daemon")
        record = state()
        with patch.object(closure, "run", side_effect=broken), self.assertRaises(RuntimeError):
            closure.clean_owned(record)
        self.assertEqual(record["cleanup"], "FAILED")

    def test_cleanup_unknown_registry_owner_is_not_deleted(self):
        def fake(argv, **_):
            if argv[1] == "info":
                return result(stdout="29.1")
            return result(stdout='[{"Config":{"Labels":{"copper.release.run_id":"someone-else"}}}]')
        with patch.object(closure, "run", side_effect=fake) as tool, self.assertRaisesRegex(ValueError, "not owned"):
            closure.clean_owned(state())
        self.assertFalse(any(call.args[0][1] == "rm" for call in tool.call_args_list))

    def test_cleanup_builder_without_marker_is_not_removed(self):
        def fake(argv, **_):
            if argv[1] == "info":
                return result(stdout="29.1")
            if argv[1] == "inspect":
                return result(1, stderr="Error: No such object")
            return result(stdout="Name: copper-closure-" + RUN + "\nDriver: docker-container\n")
        with patch.object(closure, "run", side_effect=fake) as tool, self.assertRaisesRegex(ValueError, "ownership marker"):
            closure.clean_owned(state())
        self.assertFalse(any("--force" in call.args[0] for call in tool.call_args_list))

    def test_cleanup_missing_resources_is_idempotent(self):
        def fake(argv, **_):
            if argv[1] == "info":
                return result(stdout="29.1")
            if argv[1] == "inspect":
                return result(1, stderr="No such object")
            return result(1, stderr='ERROR: no builder "copper-closure-' + RUN + '" found')
        record = state()
        with patch.object(closure, "run", side_effect=fake):
            closure.clean_owned(record)
        self.assertEqual(record["cleanup"], "PASS")

    def test_registry_refuses_reserved_port_before_docker(self):
        with patch.object(closure, "run") as tool, self.assertRaisesRegex(ValueError, "unsafe loopback"):
            closure.registry_start(state(), "registry.invalid@" + DIGEST, "127.0.0.1:8191")
        tool.assert_not_called()

    def test_input_bytes_and_closed_file_set_are_verified(self):
        for mode in ("tamper", "extra"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                inputs(root)
                closure.load_inputs(root)
                (root / ("images/copper-web-amd64.tar.gz" if mode == "tamper" else "unexpected.txt")).write_text("changed")
                with self.assertRaises(ValueError):
                    closure.load_inputs(root)

    def test_input_escaping_path_or_other_registry_fails_even_if_rehashed(self):
        for field, value in (("archive", "../outside.tar.gz"), ("localTag", "ghcr.io/other:candidate"), ("localTag", "127.0.0.1:8191/copper-web:" + RUN)):
            with self.subTest(field=field, value=value), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                doc = inputs(root)
                doc["images"]["web"][field] = value
                closure.write_json(root / "BUILD_INPUTS.json", doc)
                rehash(root)
                with self.assertRaises(ValueError):
                    closure.load_inputs(root)

    def test_stage_does_not_overwrite_owner_reviewed_candidate(self):
        with tempfile.TemporaryDirectory() as temp, tempfile.TemporaryDirectory() as checkout:
            root, deploy = Path(temp), Path(checkout)
            inputs(root)
            target = deploy / "releases" / closure.VERSION
            target.mkdir(parents=True)
            (target / "web-local-build.json").write_text("owner-reviewed bytes")
            with patch.object(closure, "ROOT", deploy), self.assertRaisesRegex(ValueError, "refuse to overwrite"):
                closure.stage(SimpleNamespace(inputs=root))
            self.assertEqual((target / "web-local-build.json").read_text(), "owner-reviewed bytes")

    def exceptions_fixture(self, root):
        evidence = root / "application-context.json"
        evidence.write_bytes(b"explicit synthetic configuration proof for unit test only")
        doc = {"format": "copper-security-exceptions/v1", "releaseVersion": closure.VERSION, "exceptions": [{
            "vulnerabilityId": "GHSA-test-test-test", "artifactRole": "image-backend", "packageName": "synthetic-library", "installedVersion": "1.2.3", "severity": "Critical", "disposition": "FALSE_POSITIVE", "owner": "synthetic-test-owner", "reason": "Synthetic test proof only, no actual finding disposition.", "compensatingControl": "Synthetic fixture has no production application.", "expiresAt": (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=1)).isoformat(), "evidenceFile": evidence.name, "evidenceSha256": closure.sha256(evidence)}]}
        path = root / "exceptions.json"
        closure.write_json(path, doc)
        return path, evidence, doc

    def test_explicit_exception_invalid_inputs_fail_before_any_docker_operation(self):
        for mutation in ("missing-json", "missing-evidence", "tamper", "symlink", "wrong-release", "expired"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                path, evidence, doc = self.exceptions_fixture(root)
                if mutation == "missing-json":
                    path.unlink()
                elif mutation == "missing-evidence":
                    evidence.unlink()
                elif mutation == "tamper":
                    evidence.write_bytes(b"changed evidence")
                elif mutation == "symlink":
                    path.rename(root / "real.json")
                    path.symlink_to(root / "real.json")
                elif mutation == "wrong-release":
                    doc["releaseVersion"] = "0.4.0-rc.8"
                    closure.write_json(path, doc)
                else:
                    doc["exceptions"][0]["expiresAt"] = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=1)).isoformat()
                    closure.write_json(path, doc)
                with patch.object(closure, "platform") as platform, self.assertRaises(RuntimeError):
                    closure.package(SimpleNamespace(security_exceptions=path))
                platform.assert_not_called()

    def test_package_preserves_explicit_exception_bytes_and_default_strict_arguments(self):
        for supplied in (False, True):
            with self.subTest(exceptions=supplied), tempfile.TemporaryDirectory() as temporary:
                base = Path(temporary)
                root = base / "inputs"
                root.mkdir()
                doc = inputs(root)
                deploy = base / "deploy"
                target = deploy / "releases" / closure.VERSION
                target.mkdir(parents=True)
                for source in (root / "release").iterdir():
                    if source.name not in {"mysql-local-build.json", "acme.tar.gz"}:
                        (target / source.name).write_bytes(source.read_bytes())
                (deploy / "scripts").mkdir()
                (deploy / "config").mkdir()
                for name in ("scripts/security-evidence.py", "config/security-policy.env"):
                    shutil.copy2(ROOT / name, deploy / name)
                path, evidence, _ = self.exceptions_fixture(base)
                original = path.read_bytes(), evidence.read_bytes()
                invocations = []
                def git(_, *args):
                    return COMMIT if args[0] == "rev-parse" else ""
                def build(argv, **_):
                    invocations.append(argv)
                    if argv[0].endswith("build-secure-release.sh"):
                        Path(argv[3]).mkdir()
                        self.assertEqual(len(argv), 7 if supplied else 6)
                        if supplied:
                            self.assertEqual(argv[6], str(path.resolve()))
                            self.assertEqual(Path(argv[6]).read_bytes(), original[0])
                            self.assertEqual((Path(argv[6]).parent / evidence.name).read_bytes(), original[1])
                    else:
                        Path(argv[3]).mkdir()
                    return result()
                def cleanup(state):
                    state["cleanup"] = "PASS"
                output = base / "candidate"
                args = SimpleNamespace(inputs=root, web_repo=deploy, backend_repo=deploy, output=output, security_exceptions=path if supplied else None)
                with patch.object(closure, "ROOT", deploy), patch.object(closure, "platform"), patch.object(closure, "reviewed_main"), patch.object(closure, "git", side_effect=git), patch.object(closure, "checkout", side_effect=lambda *args: deploy), patch.object(closure, "run", return_value=result()), patch.object(closure, "registry_start"), patch.object(closure, "import_image"), patch.object(closure.subprocess, "run", side_effect=build), patch.object(closure, "clean_owned", side_effect=cleanup):
                    closure.package(args)
                self.assertEqual(len(invocations), 2)
                self.assertTrue((output / "PACKAGE_CHECKSUMS.json").is_file())
                self.assertEqual((path.read_bytes(), evidence.read_bytes()), original)

    def test_package_cleanup_failure_never_publishes_output(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            root = base / "inputs"
            root.mkdir()
            doc = inputs(root)
            deploy = base / "deploy"
            target = deploy / "releases" / closure.VERSION
            target.mkdir(parents=True)
            for source in (root / "release").iterdir():
                if source.name not in {"mysql-local-build.json", "acme.tar.gz"}:
                    (target / source.name).write_bytes(source.read_bytes())
            def git(_, *args):
                return COMMIT if args[0] == "rev-parse" else ""
            def build(argv, **_):
                Path(argv[3]).mkdir()
                return result()
            output = base / "candidate"
            with patch.object(closure, "ROOT", deploy), patch.object(closure, "platform"), patch.object(closure, "reviewed_main"), patch.object(closure, "git", side_effect=git), patch.object(closure, "checkout", side_effect=lambda *args: deploy), patch.object(closure, "run", return_value=result()), patch.object(closure, "registry_start"), patch.object(closure, "import_image"), patch.object(closure.subprocess, "run", side_effect=build), patch.object(closure, "clean_owned", side_effect=RuntimeError("cleanup failed")):
                with self.assertRaisesRegex(RuntimeError, "cleanup failed"):
                    closure.package(SimpleNamespace(inputs=root, web_repo=deploy, backend_repo=deploy, output=output))
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
