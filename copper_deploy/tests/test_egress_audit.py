import importlib.util
import json
import pathlib
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("copper_egress", ROOT / "scripts" / "egress-audit.py")
assert SPEC and SPEC.loader
EGRESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EGRESS)


class EgressAuditTest(unittest.TestCase):
    def setUp(self) -> None:
        self.policy = EGRESS.load_policy(ROOT / "config" / "egress-policy.json")

    def test_offline_local_runtime_has_no_vendor_control(self) -> None:
        report = EGRESS.build_report(
            self.policy,
            {
                "COPPER_DEPLOY_MODE": "standard",
                "COPPER_TLS_MODE": "domain",
                "COPPER_IMAGE_DELIVERY": "offline",
                "COPPER_BACKUP_TARGETS": "local",
                "COPPER_MONITOR_ALERT_MODE": "local",
            },
            True,
        )
        self.assertEqual(report["overallLevel"], "WARNING")
        self.assertEqual(report["coreBusiness"], "AVAILABLE")
        self.assertEqual(report["degradedCapabilities"], ["tls.acme"])
        self.assertEqual(report["vendorControl"]["officialServiceEndpoints"], [])
        self.assertTrue(report["offlineReady"])

    def test_optional_s3_and_webhook_degrade_without_blocking_core(self) -> None:
        report = EGRESS.build_report(
            self.policy,
            {
                "COPPER_BACKUP_TARGETS": "local,s3",
                "COPPER_MONITOR_ALERT_MODE": "webhook",
                "COPPER_IMAGE_DELIVERY": "offline",
            },
            True,
        )
        self.assertEqual(report["coreBusiness"], "AVAILABLE")
        self.assertEqual(
            report["degradedCapabilities"],
            ["tls.acme", "backup.s3", "monitor.webhook"],
        )
        self.assertFalse(report["privacy"]["secretsCollectedByAudit"])

    def test_cloudflare_remote_access_is_explicit_and_degrades_without_blocking_lan(self) -> None:
        values = {
            "COPPER_DEPLOY_MODE": "lan",
            "COPPER_TLS_MODE": "lan",
            "COPPER_IMAGE_DELIVERY": "offline",
            "COPPER_BACKUP_TARGETS": "local",
            "COPPER_MONITOR_ALERT_MODE": "local",
            "COPPER_REMOTE_ACCESS_PROVIDER": "cloudflare",
            "COPPER_REMOTE_ACCESS_ENABLED": "yes",
        }
        online = EGRESS.build_report(self.policy, values, False)
        dependency = next(
            item for item in online["conditionalDependencies"]
            if item["id"] == "remote-access.cloudflare"
        )
        self.assertEqual(dependency["state"], "ACTIVE")
        self.assertTrue(online["privacy"]["businessDataTransitsConditionalProvider"])
        isolated = EGRESS.build_report(self.policy, values, True)
        self.assertEqual(isolated["coreBusiness"], "AVAILABLE")
        self.assertEqual(isolated["degradedCapabilities"], ["remote-access.cloudflare"])

    def test_remote_license_or_vendor_control_is_blocking(self) -> None:
        report = EGRESS.build_report(
            self.policy,
            {"COPPER_LICENSE_SERVER_URL": "https://vendor.invalid/license"},
            False,
        )
        self.assertEqual(report["overallLevel"], "BLOCKED")
        self.assertEqual(report["coreBusiness"], "BLOCKED")
        self.assertFalse(report["offlineReady"])

    def test_env_parser_never_expands_or_reads_referenced_secret(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            secret = root / "secret"
            secret.write_text("must-not-appear", encoding="utf-8")
            env = root / "copper.env"
            env.write_text(f"COPPER_MONITOR_WEBHOOK_URL_FILE={secret}\n", encoding="utf-8")
            values = EGRESS.load_env(env)
            self.assertEqual(values["COPPER_MONITOR_WEBHOOK_URL_FILE"], str(secret))
            self.assertNotIn("must-not-appear", json.dumps(values))


if __name__ == "__main__":
    unittest.main()
