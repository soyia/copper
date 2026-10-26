from __future__ import annotations

import hashlib
import importlib.machinery
import importlib.util
import json
import os
import pathlib
import sys
import tarfile
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
LOADER = importlib.machinery.SourceFileLoader("copper_mac_guided_upgrade_test_module", str(ROOT / "copper-upgrade-from-mac"))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
assert SPEC and SPEC.loader
upgrade = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = upgrade
SPEC.loader.exec_module(upgrade)


def digest(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class MacGuidedUpgradeTest(unittest.TestCase):
    def delivery(self, root: pathlib.Path, version: str = "0.4.0-rc.6") -> tuple[pathlib.Path, str]:
        root.mkdir(parents=True)
        files = {
            "manifest": root / f"copper-release-{version}.env",
            "deploy": root / f"copper-deploy-{version}.tar.gz",
            "matrix": root / f"copper-upgrade-matrix-{version}.json",
            "notes": root / f"copper-release-notes-{version}.md",
            "acme": root / "acme.sh-3.1.3.tar.gz",
            "images": root / f"copper-images-{version}-amd64.tar",
        }
        for role, path in files.items():
            path.write_bytes(f"{role}-{version}".encode("ascii"))

        def record(role: str) -> dict[str, object]:
            path = files[role]
            return {"role": role, "name": path.name, "bytes": path.stat().st_size, "sha256": digest(path)}

        document = {
            "format": upgrade.DELIVERY_FORMAT,
            "version": version,
            "mode": "offline",
            "os": "ubuntu-24.04",
            "arch": "amd64",
            "manifest": record("manifest"),
            "artifacts": [record(role) for role in ("deploy", "matrix", "notes", "acme", "images")],
            "images": [
                {
                    "role": role,
                    "reference": f"ghcr.io/soyia/copper_{role}@sha256:{number * 64}",
                    "platform": "linux/amd64",
                    "localId": f"sha256:{number * 64}",
                }
                for role, number in (("web", "1"), ("backend", "2"), ("mysql", "3"))
            ],
        }
        index = root / f"copper-delivery-offline-{version}.json"
        index.write_text(json.dumps(document, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
        return index, digest(index)

    def test_closed_offline_delivery_is_verified_before_upload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "delivery"
            index, wanted = self.delivery(root)
            observed = upgrade.validate_delivery(root, root, wanted)
            self.assertEqual(observed.version, "0.4.0-rc.6")
            self.assertEqual(observed.index, index)
            self.assertEqual(len(observed.files), 7)
            with self.assertRaisesRegex(upgrade.UpgradeError, "完整校验码"):
                upgrade.validate_delivery(root, root, "0" * 64)
            (root / "unexpected.txt").write_text("unexpected\n", encoding="utf-8")
            with self.assertRaisesRegex(upgrade.UpgradeError, "多余文件"):
                upgrade.validate_delivery(root, root, wanted)

    def test_tar_archive_is_safely_opened_and_revalidated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            root = base / "copper-delivery-offline-0.4.0-rc.6"
            _, wanted = self.delivery(root)
            archive = base / "upgrade.tar.gz"
            with tarfile.open(archive, "w:gz") as package:
                package.add(root, arcname=root.name)
            with upgrade.open_delivery(archive, wanted) as observed:
                self.assertEqual(observed.version, "0.4.0-rc.6")
                self.assertTrue(observed.index.is_file())

    def test_mutable_image_reference_is_rejected_before_upload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "delivery"
            index, _wanted = self.delivery(root)
            document = json.loads(index.read_text(encoding="utf-8"))
            document["images"][0]["reference"] = "ghcr.io/soyia/copper_web:latest"
            index.write_text(json.dumps(document) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(upgrade.UpgradeError, "不可变摘要引用"):
                upgrade.validate_delivery(root, root, digest(index))

    def test_delta_delivery_reports_only_changed_components(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "delta"
            root.mkdir()
            version = "0.4.0-rc.7"
            files = {
                role: root / name
                for role, name in {
                    "manifest": f"copper-release-{version}.env",
                    "deploy": f"copper-deploy-{version}.tar.gz",
                    "matrix": f"copper-upgrade-matrix-{version}.json",
                    "notes": f"copper-release-notes-{version}.md",
                    "acme": "acme.sh-3.1.3.tar.gz",
                    "images": f"copper-images-{version}-amd64.tar.gz",
                }.items()
            }
            for role, path in files.items():
                path.write_bytes(role.encode("ascii"))

            def record(role: str) -> dict[str, object]:
                path = files[role]
                return {"role": role, "name": path.name, "bytes": path.stat().st_size, "sha256": digest(path)}

            images = []
            for role, base, target, delivery in (
                ("web", "1", "4", "included"),
                ("backend", "2", "2", "reuse"),
                ("mysql", "3", "3", "reuse"),
            ):
                images.append({
                    "role": role,
                    "reference": f"ghcr.io/soyia/copper_{role}@sha256:{target * 64}",
                    "platform": "linux/amd64",
                    "localId": f"sha256:{target * 64}",
                    "delivery": delivery,
                    "baseReference": f"ghcr.io/soyia/copper_{role}@sha256:{base * 64}",
                    "baseLocalId": f"sha256:{base * 64}",
                })
            document = {
                "format": upgrade.DELTA_DELIVERY_FORMAT,
                "version": version,
                "baseVersion": "0.4.0-rc.6",
                "baseIndexSha256": "a" * 64,
                "mode": "offline-delta",
                "os": "ubuntu-24.04",
                "arch": "amd64",
                "manifest": record("manifest"),
                "artifacts": [record(role) for role in ("deploy", "matrix", "notes", "acme", "images")],
                "images": images,
            }
            index = root / "copper-upgrade-delivery-offline-delta-0.4.0-rc.6-to-0.4.0-rc.7.json"
            index.write_text(json.dumps(document) + "\n", encoding="utf-8")
            observed = upgrade.validate_delivery(root, root, digest(index))
            self.assertEqual(observed.mode, "offline-delta")
            self.assertEqual(observed.base_version, "0.4.0-rc.6")
            self.assertEqual(dict(observed.image_plan), {
                "web": "included", "backend": "reuse", "mysql": "reuse",
            })

    def test_pending_record_is_private_and_never_contains_recovery_key(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "upgrades/copper-test-pending.json"
            state = {
                "formatVersion": upgrade.STATE_FORMAT,
                "siteId": "copper-test",
                "domain": "copper.example.org",
                "source": "/Volumes/USB/upgrade.tar.gz",
                "indexSha256": "a" * 64,
                "targetVersion": "0.4.0-rc.6",
                "upgradeId": "upgrade-20260720-120000",
                "remoteRoot": ".copper-upgrade-media/copper-test/0.4.0-rc.6-aaaaaaaaaaaa",
                "phase": "SELECTED",
            }
            upgrade.save_state(path, state)
            self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
            loaded = upgrade.load_state(path, "copper-test")
            self.assertEqual(loaded["targetVersion"], "0.4.0-rc.6")
            self.assertNotIn("AGE-SECRET-KEY", path.read_text(encoding="utf-8"))

    def test_manager_exposes_upgrade_without_embedding_switch_logic(self) -> None:
        manager_source = (ROOT / "copper-manage-from-mac").read_text(encoding="utf-8")
        upgrade_source = (ROOT / "copper-upgrade-from-mac").read_text(encoding="utf-8")
        self.assertIn("升级 Copper（自动备份、迁移、切换和失败保护）", manager_source)
        self.assertIn("copper-upgrade-from-mac", manager_source)
        self.assertIn("upgrade switch", upgrade_source)
        self.assertIn("upgrade accept", upgrade_source)
        self.assertIn("create_download_verify", upgrade_source)


if __name__ == "__main__":
    unittest.main()
