import importlib.util
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("local_host_preflight", ROOT / "scripts/local-host-preflight.py")
PREFLIGHT = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(PREFLIGHT)
POLICY = json.loads((ROOT / "config/local-host-policy.json").read_text(encoding="utf-8"))


def ubuntu_facts() -> dict:
    return {
        "os": {
            "id": "ubuntu",
            "versionId": "24.04",
            "prettyName": "Ubuntu 24.04.4 LTS",
            "kernelRelease": "6.8.0-generic",
        },
        "architecture": "x86_64",
        "cpuCount": 4,
        "memoryBytes": 8 * 1024**3,
        "virtualization": "kvm",
        "docker": {
            "available": True,
            "engineVersion": "29.5.3",
            "composeVersion": "5.1.4",
            "operatingSystem": "Ubuntu 24.04.4 LTS",
        },
        "system": {
            "systemd": True,
            "dockerEnabled": True,
            "timeSynchronized": True,
        },
        "data": {
            "path": "/var/lib/copper",
            "existingAncestor": "/var/lib",
            "availableBytes": 100 * 1024**3,
            "totalBytes": 120 * 1024**3,
            "mountpoint": "/",
            "source": "/dev/vda2",
            "filesystem": "ext4",
            "mountOptions": ["relatime", "rw"],
            "removable": False,
            "hasSymlinkComponent": False,
            "dataDirectoryExists": False,
            "dataDirectoryHasEntries": False,
            "mysqlDirectoryExists": False,
            "configExists": False,
            "etcDirectoryHasEntries": False,
            "secretsPresent": False,
        },
        "portInspectionAvailable": True,
        "occupiedPorts": [],
    }


class LocalHostPreflightTests(unittest.TestCase):
    def evaluate(self, facts: dict, profile: str = "ubuntu-24.04-x86_64") -> dict:
        return PREFLIGHT.evaluate(POLICY, profile, facts)

    def status_for(self, document: dict, check_id: str) -> str:
        return next(item["status"] for item in document["checks"] if item["id"] == check_id)

    def test_fresh_ubuntu_x86_host_passes(self) -> None:
        document = self.evaluate(ubuntu_facts())
        self.assertEqual(document["status"], "PASS")
        self.assertTrue(document["writeAllowed"])
        self.assertEqual(self.status_for(document, "data.existingState"), "PASS")

    def test_arm64_is_rejected_before_write(self) -> None:
        facts = ubuntu_facts()
        facts["architecture"] = "aarch64"
        document = self.evaluate(facts)
        self.assertEqual(document["status"], "FAIL")
        self.assertFalse(document["writeAllowed"])
        self.assertEqual(self.status_for(document, "platform.architecture"), "FAIL")

    def test_network_filesystem_is_rejected(self) -> None:
        facts = ubuntu_facts()
        facts["data"]["filesystem"] = "nfs4"
        facts["data"]["source"] = "nas:/volume/copper"
        document = self.evaluate(facts)
        self.assertEqual(document["status"], "FAIL")
        self.assertEqual(self.status_for(document, "data.filesystem"), "FAIL")

    def test_unknown_filesystem_requires_manual_review(self) -> None:
        facts = ubuntu_facts()
        facts["data"]["filesystem"] = "zfs"
        document = self.evaluate(facts)
        self.assertEqual(document["status"], "MANUAL_REVIEW")
        self.assertFalse(document["writeAllowed"])

    def test_public_port_conflict_blocks_install(self) -> None:
        facts = ubuntu_facts()
        facts["occupiedPorts"] = [80]
        document = self.evaluate(facts)
        self.assertEqual(document["status"], "FAIL")
        self.assertEqual(self.status_for(document, "network.publicPorts"), "FAIL")

    def test_failed_port_inspection_blocks_install(self) -> None:
        facts = ubuntu_facts()
        facts["portInspectionAvailable"] = False
        document = self.evaluate(facts)
        self.assertEqual(document["status"], "FAIL")
        self.assertEqual(self.status_for(document, "network.portInspection"), "FAIL")

    def test_internal_listener_requires_review(self) -> None:
        facts = ubuntu_facts()
        facts["occupiedPorts"] = [3306, 8191]
        document = self.evaluate(facts)
        self.assertEqual(document["status"], "MANUAL_REVIEW")
        self.assertEqual(self.status_for(document, "network.internalPorts"), "MANUAL_REVIEW")

    def test_orphaned_mysql_data_is_rejected(self) -> None:
        facts = ubuntu_facts()
        facts["data"]["dataDirectoryExists"] = True
        facts["data"]["dataDirectoryHasEntries"] = True
        facts["data"]["mysqlDirectoryExists"] = True
        document = self.evaluate(facts)
        self.assertEqual(document["status"], "FAIL")
        check = next(item for item in document["checks"] if item["id"] == "data.existingState")
        self.assertEqual(check["observed"], "orphaned-data")

    def test_managed_reinstall_preserves_existing_data_contract(self) -> None:
        facts = ubuntu_facts()
        facts["data"].update({
            "dataDirectoryExists": True,
            "dataDirectoryHasEntries": True,
            "mysqlDirectoryExists": True,
            "configExists": True,
            "secretsPresent": True,
        })
        document = self.evaluate(facts)
        self.assertEqual(document["status"], "PASS")
        check = next(item for item in document["checks"] if item["id"] == "data.existingState")
        self.assertEqual(check["observed"], "managed-reinstall")

    def test_unknown_existing_data_without_configuration_is_rejected(self) -> None:
        facts = ubuntu_facts()
        facts["data"]["dataDirectoryExists"] = True
        facts["data"]["dataDirectoryHasEntries"] = True
        document = self.evaluate(facts)
        self.assertEqual(document["status"], "FAIL")
        self.assertFalse(document["writeAllowed"])
        self.assertEqual(self.status_for(document, "data.existingState"), "FAIL")

    def test_existing_secrets_without_configuration_are_rejected(self) -> None:
        facts = ubuntu_facts()
        facts["data"]["etcDirectoryHasEntries"] = True
        facts["data"]["secretsPresent"] = True
        document = self.evaluate(facts)
        self.assertEqual(document["status"], "FAIL")
        self.assertFalse(document["writeAllowed"])
        self.assertEqual(self.status_for(document, "data.existingState"), "FAIL")

    def test_low_memory_and_disk_are_rejected(self) -> None:
        facts = ubuntu_facts()
        facts["memoryBytes"] = 2 * 1024**3
        facts["data"]["availableBytes"] = 10 * 1024**3
        document = self.evaluate(facts)
        self.assertEqual(document["status"], "FAIL")
        self.assertEqual(self.status_for(document, "resource.memory"), "FAIL")
        self.assertEqual(self.status_for(document, "resource.disk"), "FAIL")

if __name__ == "__main__":
    unittest.main()
