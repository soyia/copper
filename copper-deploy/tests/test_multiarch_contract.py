from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location(
    "multiarch_contract", ROOT / "scripts/multiarch-contract.py"
)
assert SPEC and SPEC.loader
CONTRACT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTRACT)


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class MultiarchContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_archive(self, directory: pathlib.Path, role: str, arch: str) -> pathlib.Path:
        path = directory / f"copper-{role}-{arch}.tar.gz"
        path.write_bytes(f"{role}-{arch}-immutable-archive".encode())
        return path

    def write_app_bom(self, role: str, seed: str) -> pathlib.Path:
        directory = self.root / role
        directory.mkdir()
        platforms = []
        for index, (platform, arch) in enumerate(
            (("linux/amd64", "amd64"), ("linux/arm64", "arm64")), start=1
        ):
            archive = self.write_archive(directory, role, arch)
            platforms.append(
                {
                    "format": "copper-image-platform/v1",
                    "role": role,
                    "sourceRepository": f"soyia/copper_{role}",
                    "sourceCommit": seed * 40,
                    "workflowRun": "12345",
                    "workflowAttempt": "1",
                    "createdAt": "2026-07-15T00:00:00Z",
                    "imageRepository": f"ghcr.io/soyia/copper_{role}",
                    "platform": platform,
                    "platformDigest": f"sha256:{str(index) * 64}",
                    "localId": f"sha256:{str(index + 2) * 64}",
                    "archive": {
                        "name": archive.name,
                        "bytes": archive.stat().st_size,
                        "sha256": sha256(archive),
                    },
                }
            )
        manifest_digest = f"sha256:{seed * 64}"
        document = {
            "format": "copper-image-bom/v1",
            "role": role,
            "sourceRepository": f"soyia/copper_{role}",
            "sourceCommit": seed * 40,
            "workflowRun": "12345",
            "image": f"ghcr.io/soyia/copper_{role}@{manifest_digest}",
            "manifestDigest": manifest_digest,
            "platforms": platforms,
        }
        path = directory / "image-bom.json"
        path.write_text(json.dumps(document), encoding="utf-8")
        return path

    def assemble(self) -> tuple[pathlib.Path, pathlib.Path]:
        web = self.write_app_bom("web", "a")
        backend = self.write_app_bom("backend", "b")
        mysql_amd64 = self.write_archive(self.root, "mysql", "amd64")
        mysql_arm64 = self.write_archive(self.root, "mysql", "arm64")
        output = self.root / "release"
        CONTRACT.command_assemble(
            argparse.Namespace(
                version="0.4.0-m5-rc.1",
                created_at="2026-07-15T00:00:00Z",
                deploy_commit="c" * 40,
                web_bom=str(web),
                backend_bom=str(backend),
                mysql_version="8.4.10",
                mysql_image=f"mysql:8.4.10@sha256:{'d' * 64}",
                mysql_platform=[
                    f"linux/amd64,sha256:{'e' * 64},sha256:{'f' * 64},{mysql_amd64}",
                    f"linux/arm64,sha256:{'1' * 64},sha256:{'2' * 64},{mysql_arm64}",
                ],
                output_dir=str(output),
            )
        )
        return output, output / "copper-multiarch-release-0.4.0-m5-rc.1.json"

    def test_validates_app_bom_and_assembles_closed_release_directory(self) -> None:
        output, index = self.assemble()
        CONTRACT.command_validate_release(
            argparse.Namespace(index=str(index), artifact_dir=None)
        )
        document = json.loads(index.read_text())
        self.assertEqual(
            document["platforms"],
            [
                {"platform": "linux/amd64", "status": "certified"},
                {"platform": "linux/arm64", "status": "candidate"},
            ],
        )
        self.assertEqual([item["role"] for item in document["components"]], ["web", "backend", "mysql"])
        self.assertEqual(len(list(output.iterdir())), 8)

    def test_rejects_missing_arm64_platform(self) -> None:
        path = self.write_app_bom("web", "a")
        document = json.loads(path.read_text())
        document["platforms"] = document["platforms"][:1]
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaises(CONTRACT.ContractError):
            CONTRACT.validate_app_bom(path)

    def test_rejects_latest_even_when_digest_is_present(self) -> None:
        path = self.write_app_bom("web", "a")
        document = json.loads(path.read_text())
        document["image"] = f"ghcr.io/soyia/copper_web:latest@sha256:{'a' * 64}"
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaises(CONTRACT.ContractError):
            CONTRACT.validate_app_bom(path)

    def test_rejects_modified_offline_archive(self) -> None:
        path = self.write_app_bom("backend", "b")
        (path.parent / "copper-backend-arm64.tar.gz").write_bytes(b"changed")
        with self.assertRaises(CONTRACT.ContractError):
            CONTRACT.validate_app_bom(path)

    def test_rejects_claiming_arm64_is_certified(self) -> None:
        output, index = self.assemble()
        document = json.loads(index.read_text())
        document["platforms"][1]["status"] = "certified"
        with self.assertRaises(CONTRACT.ContractError):
            CONTRACT.validate_release_document(document, output)

    def test_rejects_missing_checksum_inventory_entry(self) -> None:
        output, index = self.assemble()
        sums = output / "SHA256SUMS"
        sums.chmod(0o644)
        sums.write_text("\n".join(sums.read_text().splitlines()[:-1]) + "\n")
        with self.assertRaises(CONTRACT.ContractError):
            CONTRACT.command_validate_release(
                argparse.Namespace(index=str(index), artifact_dir=None)
            )

    def test_rejects_non_regular_entries_in_closed_release_directory(self) -> None:
        output, index = self.assemble()
        unexpected_directory = output / "unexpected-directory"
        unexpected_directory.mkdir()
        with self.assertRaises(CONTRACT.ContractError):
            CONTRACT.command_validate_release(
                argparse.Namespace(index=str(index), artifact_dir=None)
            )
        unexpected_directory.rmdir()

        unexpected_link = output / "unexpected-link"
        unexpected_link.symlink_to(index.name)
        with self.assertRaises(CONTRACT.ContractError):
            CONTRACT.command_validate_release(
                argparse.Namespace(index=str(index), artifact_dir=None)
            )


if __name__ == "__main__":
    unittest.main()
