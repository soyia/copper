import hashlib
import io
import json
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "oci-archive-image-id.py"


def canonical(document: object) -> bytes:
    return json.dumps(document, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest(content: bytes) -> str:
    return "sha256:" + hashlib.sha256(content).hexdigest()


class OciArchiveImageIdTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.reference = "ghcr.io/soyia/copper_web:test-amd64"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def archive(self, *, architecture: str = "amd64", corrupt_manifest: bool = False) -> tuple[pathlib.Path, str, str]:
        config = canonical({"architecture": architecture, "os": "linux"})
        config_digest = digest(config)
        manifest = canonical(
            {
                "schemaVersion": 2,
                "mediaType": "application/vnd.oci.image.manifest.v1+json",
                "config": {
                    "mediaType": "application/vnd.oci.image.config.v1+json",
                    "digest": config_digest,
                    "size": len(config),
                },
                "layers": [],
            }
        )
        manifest_digest = digest(manifest)
        index = canonical(
            {
                "schemaVersion": 2,
                "manifests": [
                    {
                        "mediaType": "application/vnd.oci.image.manifest.v1+json",
                        "digest": manifest_digest,
                        "size": len(manifest),
                        "annotations": {"io.containerd.image.name": self.reference},
                    }
                ],
            }
        )
        archive = self.root / "images.tar.gz"
        members = {
            "index.json": index,
            f"blobs/sha256/{config_digest.removeprefix('sha256:')}": config,
            f"blobs/sha256/{manifest_digest.removeprefix('sha256:')}": (
                manifest + b"corrupt" if corrupt_manifest else manifest
            ),
        }
        with tarfile.open(archive, "w:gz") as handle:
            for name, content in members.items():
                info = tarfile.TarInfo(name)
                info.size = len(content)
                info.mode = 0o444
                handle.addfile(info, io.BytesIO(content))
        return archive, manifest_digest, config_digest

    def run_tool(self, archive: pathlib.Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(TOOL), str(archive), self.reference],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_returns_import_stable_manifest_digest_not_config_digest(self) -> None:
        archive, manifest_digest, config_digest = self.archive()
        result = self.run_tool(archive)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), f"{self.reference}\t{manifest_digest}\tamd64")
        self.assertNotIn(config_digest, result.stdout)

    def test_rejects_manifest_blob_with_wrong_digest(self) -> None:
        archive, _, _ = self.archive(corrupt_manifest=True)
        result = self.run_tool(archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("manifest对象 SHA-256 不匹配", result.stderr)

    def test_rejects_non_amd64_config(self) -> None:
        archive, _, _ = self.archive(architecture="arm64")
        result = self.run_tool(archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("镜像归档架构不是 amd64", result.stderr)

    def rewrite_index(self, archive, mutate):
        with tarfile.open(archive) as source:
            members = {m.name: source.extractfile(m).read() for m in source.getmembers()}
        index = json.loads(members["index.json"])
        mutate(index)
        members["index.json"] = canonical(index)
        with tarfile.open(archive, "w:gz") as target:
            for name, content in members.items():
                info = tarfile.TarInfo(name)
                info.size = len(content)
                target.addfile(info, io.BytesIO(content))

    def test_digest_reference_without_name_uses_exact_verified_manifest(self) -> None:
        archive, manifest_digest, _ = self.archive()
        self.rewrite_index(archive, lambda index: index["manifests"][0].pop("annotations"))
        self.reference = "127.0.0.1:38945/copper-mysql@" + manifest_digest
        result = self.run_tool(archive)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), f"{self.reference}\t{manifest_digest}\tamd64")

    def test_missing_name_never_resolves_a_tag(self) -> None:
        archive, _, _ = self.archive()
        self.rewrite_index(archive, lambda index: index["manifests"][0].pop("annotations"))
        result = self.run_tool(archive)
        self.assertNotEqual(result.returncode, 0)

    def test_requested_digest_cannot_match_config_or_unrelated_manifest(self) -> None:
        for wanted in ("config", "unrelated"):
            with self.subTest(wanted=wanted):
                archive, _, config_digest = self.archive()
                self.rewrite_index(archive, lambda index: index["manifests"][0].pop("annotations"))
                self.reference = "registry.invalid/mysql@" + (config_digest if wanted == "config" else "sha256:" + "f" * 64)
                self.assertNotEqual(self.run_tool(archive).returncode, 0)

    def test_digest_fallback_preserves_blob_integrity_and_platform_checks(self) -> None:
        for arguments in ({"corrupt_manifest": True}, {"architecture": "arm64"}):
            with self.subTest(arguments=arguments):
                archive, manifest_digest, _ = self.archive(**arguments)
                self.rewrite_index(archive, lambda index: index["manifests"][0].pop("annotations"))
                self.reference = "registry.invalid/mysql@" + manifest_digest
                self.assertNotEqual(self.run_tool(archive).returncode, 0)

    def test_duplicate_digest_descriptors_remain_ambiguous(self) -> None:
        archive, manifest_digest, _ = self.archive()
        def duplicate(index):
            index["manifests"][0].pop("annotations")
            index["manifests"].append(dict(index["manifests"][0]))
        self.rewrite_index(archive, duplicate)
        self.reference = "registry.invalid/mysql@" + manifest_digest
        self.assertNotEqual(self.run_tool(archive).returncode, 0)


if __name__ == "__main__":
    unittest.main()
