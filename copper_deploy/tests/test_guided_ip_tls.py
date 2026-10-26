"""Guided public-IP contracts; no Docker, network calls or real certificates."""
from __future__ import annotations

import hashlib
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]


def module(name, filename):
    loader = importlib.machinery.SourceFileLoader(name, str(ROOT / filename))
    spec = importlib.util.spec_from_loader(name, loader)
    loaded = importlib.util.module_from_spec(spec)
    sys.modules[name] = loaded
    loader.exec_module(loaded)
    return loaded


INSTALL = module("guided_ip_server", "copper-install")
MAC = module("guided_ip_mac", "copper-install-from-mac")
IP = "18.142.134.26"
RECIPIENT = "age1" + "a" * 58


class GuidedIpTlsTest(unittest.TestCase):
    def fixture(self, base):
        layout = INSTALL.Layout(base / "etc/copper", base / "var/lib/copper", base / "opt/copper/releases", base / "bin/copper-install")
        values = {"COPPER_RELEASE_VERSION": "0.4.0-rc.8", "COPPER_DEPLOY_COMMIT": "a" * 40}
        for index, role in enumerate(INSTALL.IMAGE_ROLES):
            values["COPPER_" + role + "_IMAGE"] = "sha256:" + str(index + 1) * 64
        answers = {"siteId": "copper-ip-acceptance", "domain": IP, "publicIp": IP, "backupPublicKey": RECIPIENT, "timeZone": "Asia/Shanghai"}
        plan = INSTALL.plan_document(answers, values, {"ipv4": [IP], "ipv6": []}, "fresh-install")
        root = layout.releases_dir / values["COPPER_RELEASE_VERSION"]
        (root / "config").mkdir(parents=True)
        shutil.copy2(ROOT / "config/copper.env.example", root / "config/copper.env.example")
        (root / "scripts").mkdir()
        (root / "copperctl").write_text("# synthetic installer fixture; never executed\n")
        media = base / "media"
        (media / "offline").mkdir(parents=True)
        data = b"explicitly synthetic ACME archive fixture, never executed"
        archive = media / "offline/acme.sh-3.1.3.tar.gz"
        archive.write_bytes(data)
        (root / "scripts/ip-tls.sh").write_text('ACME_VERSION="3.1.3"\nACME_ARCHIVE_SHA256="' + hashlib.sha256(data).hexdigest() + '"\n')
        return layout, root, media, values, answers, plan, archive

    def test_public_ip_identity_is_consistent_on_mac_and_server(self):
        for implementation in (INSTALL, MAC):
            with self.subTest(entry=implementation.__name__):
                self.assertEqual(implementation.normalize_domain(IP), IP)
                self.assertEqual(implementation.normalize_domain("https://" + IP + "/"), IP)
                with mock.patch.object(implementation, "resolve_addresses", side_effect=AssertionError("IP identity must not query DNS")):
                    self.assertEqual(implementation.validate_dns(IP, IP), {"ipv4": [IP], "ipv6": []})
                    with self.assertRaisesRegex((INSTALL.InstallerError, MAC.LocalInstallError), "不一致"):
                        implementation.validate_dns(IP, "18.142.134.27")
        self.assertEqual(INSTALL.tls_mode_for_address(IP), "ip")
        self.assertEqual(INSTALL.tls_mode_for_address("copper.example.org"), "domain")

    def test_nonpublic_malformed_and_ipv6_addresses_fail_on_both_entries(self):
        rejected = ("10.0.0.1", "192.168.1.2", "127.0.0.1", "169.254.1.1", "100.64.1.2", "0.0.0.0", "224.0.0.1", "240.0.0.1", "192.0.2.5", "018.142.134.26", "256.1.1.1", "2606:4700:4700::1111", "https://[2606:4700:4700::1111]/", "https://" + IP + ":443/", "http://" + IP)
        for implementation in (INSTALL, MAC):
            for address in rejected:
                with self.subTest(entry=implementation.__name__, address=address), self.assertRaises((INSTALL.InstallerError, MAC.LocalInstallError)):
                    implementation.normalize_domain(address)

    def test_ip_configuration_persists_only_the_pinned_client_and_offline_ids(self):
        with tempfile.TemporaryDirectory() as temporary:
            layout, root, media, values, answers, plan, source = self.fixture(Path(temporary))
            with mock.patch.object(INSTALL, "run"):
                INSTALL.ensure_configuration(layout, root, plan, values, answers, media)
            configuration = INSTALL.parse_env(layout.env_file)
            self.assertEqual(configuration["COPPER_TLS_MODE"], "ip")
            self.assertEqual(configuration["COPPER_DOMAIN"], IP)
            self.assertEqual(configuration["COPPER_IMAGE_DELIVERY"], "offline")
            installed = Path(configuration["COPPER_ACME_ARCHIVE"])
            self.assertEqual(installed.read_bytes(), source.read_bytes())
            self.assertEqual(installed.parent, layout.releases_dir.parent / "offline")
            self.assertEqual(installed.stat().st_mode & 0o777, 0o400)
            self.assertNotIn("COPPER_TEST_MODE", configuration)
            self.assertEqual(configuration["COPPER_BACKUP_AGE_RECIPIENT"], RECIPIENT)

    def test_same_version_repair_preserves_customer_configuration(self):
        with tempfile.TemporaryDirectory() as temporary:
            layout, root, media, values, answers, plan, _ = self.fixture(Path(temporary))
            with mock.patch.object(INSTALL, "run"):
                INSTALL.ensure_configuration(layout, root, plan, values, answers, media)
            original = layout.env_file.read_text().replace("COPPER_TLS_MODE=ip", "COPPER_TLS_MODE=domain").replace("COPPER_OCR_ENABLED=false", "COPPER_OCR_ENABLED=true").replace("COPPER_BACKUP_DAILY_TIME=02:30", "COPPER_BACKUP_DAILY_TIME=03:15")
            original += "# customer-owned extension\nCOPPER_CUSTOM_POLICY=keep-me\n"
            layout.env_file.write_text(original)
            with mock.patch.object(INSTALL, "run"):
                INSTALL.ensure_configuration(layout, root, plan, values, answers, None)
            actual = layout.env_file.read_text()
            self.assertEqual(actual, original.replace("COPPER_TLS_MODE=domain", "COPPER_TLS_MODE=ip"))

    def test_existing_customer_key_site_or_release_cannot_be_replaced(self):
        for field, value in (("backupPublicKey", "age1" + "b" * 58), ("siteId", "another-site"), ("releaseVersion", "0.4.0-rc.9")):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temporary:
                layout, root, media, values, answers, plan, _ = self.fixture(Path(temporary))
                with mock.patch.object(INSTALL, "run"):
                    INSTALL.ensure_configuration(layout, root, plan, values, answers, media)
                before = layout.env_file.read_bytes()
                if field == "backupPublicKey":
                    answers[field] = value
                else:
                    plan[field] = value
                with mock.patch.object(INSTALL, "run") as calls, self.assertRaises(INSTALL.InstallerError):
                    INSTALL.ensure_configuration(layout, root, plan, values, answers, media)
                calls.assert_not_called()
                self.assertEqual(layout.env_file.read_bytes(), before)

    def test_missing_tampered_or_preexisting_wrong_acme_client_fails_closed(self):
        for mode in ("missing", "tampered", "preexisting"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                layout, root, media, _, _, _, source = self.fixture(Path(temporary))
                destination = layout.releases_dir.parent / "offline" / source.name
                if mode == "missing":
                    source.unlink()
                elif mode == "tampered":
                    source.write_bytes(b"tampered")
                else:
                    destination.parent.mkdir()
                    destination.write_bytes(b"unowned existing archive")
                with self.assertRaises(INSTALL.InstallerError):
                    INSTALL.prepare_ip_tls_archive(layout, root, media)
                if mode == "preexisting":
                    self.assertEqual(destination.read_bytes(), b"unowned existing archive")
                else:
                    self.assertFalse(destination.exists())

    def test_guided_ip_plan_calls_the_real_standard_install_entry(self):
        with tempfile.TemporaryDirectory() as temporary:
            layout, root, media, values, answers, plan, _ = self.fixture(Path(temporary))
            state = INSTALL.initial_state(plan, media)
            with mock.patch.object(INSTALL, "extract_release", return_value=root), mock.patch.object(INSTALL, "ensure_command_link"), mock.patch.object(INSTALL, "ensure_maintenance_link"), mock.patch.object(INSTALL, "load_images"), mock.patch.object(INSTALL, "run") as calls, mock.patch.object(INSTALL, "wait_for_https", return_value=True), mock.patch.object(INSTALL, "finalize_install") as finalized:
                self.assertEqual(INSTALL.apply_plan(layout, media, values, plan, answers, state), 0)
            self.assertIn(mock.call([str(root / "copperctl"), "install"], cwd=root), calls.call_args_list)
            self.assertEqual(INSTALL.parse_env(layout.env_file)["COPPER_TLS_MODE"], "ip")
            finalized.assert_called_once()
            self.assertEqual(state["domain"], IP)

    def test_continue_reuses_ip_identity_without_dns_or_configuration_reset(self):
        with tempfile.TemporaryDirectory() as temporary:
            layout, root, media, values, answers, plan, _ = self.fixture(Path(temporary))
            with mock.patch.object(INSTALL, "run"):
                INSTALL.ensure_configuration(layout, root, plan, values, answers, media)
            before = layout.env_file.read_bytes()
            state = INSTALL.initial_state(plan, media)
            state["phase"] = "CONFIG_READY"
            INSTALL.write_json(layout.plan_file, plan)
            INSTALL.write_json(layout.state_file, state)
            (media / "OPERATOR_VALUES.env").write_text("".join(f"{key}={value}\n" for key, value in values.items()))
            with mock.patch.object(INSTALL, "require_operator_root"), mock.patch.object(INSTALL, "media_directory", return_value=media), mock.patch.object(INSTALL, "require_media_values"), mock.patch.object(INSTALL, "verify_media"), mock.patch.object(INSTALL, "ensure_command_link"), mock.patch.object(INSTALL, "ensure_maintenance_link"), mock.patch.object(INSTALL, "resolve_addresses", side_effect=AssertionError("no DNS for public IP")), mock.patch.object(INSTALL, "run") as calls, mock.patch.object(INSTALL, "wait_for_https", return_value=True), mock.patch.object(INSTALL, "finalize_install"):
                self.assertEqual(INSTALL.command_continue(layout, SimpleNamespace(media_dir=str(media), answers=None, yes=True)), 0)
            self.assertIn(mock.call([str(root / "copperctl"), "install"], cwd=root), calls.call_args_list)
            self.assertEqual(layout.env_file.read_bytes(), before)

    def test_change_address_involving_ip_stops_before_any_writes_or_service_calls(self):
        for old, new in (("copper.example.org", IP), (IP, "copper.example.org"), (IP, "18.142.134.27")):
            with self.subTest(old=old, new=new), tempfile.TemporaryDirectory() as temporary:
                layout, _, media, values, answers, plan, _ = self.fixture(Path(temporary))
                answers["domain"] = old
                plan["domain"] = old
                state = INSTALL.initial_state(plan, media)
                INSTALL.write_json(layout.plan_file, plan)
                INSTALL.write_json(layout.state_file, state)
                layout.etc_dir.mkdir(parents=True)
                layout.env_file.write_text("COPPER_DOMAIN=" + old + "\n")
                answer_file = Path(temporary) / "answers.json"
                answer_file.write_text(json.dumps({"domain": new, "publicIp": IP}))
                original = layout.env_file.read_bytes()
                with mock.patch.object(INSTALL, "require_operator_root"), mock.patch.object(INSTALL, "run") as calls, self.assertRaisesRegex(INSTALL.InstallerError, "模式迁移"):
                    INSTALL.command_change_domain(layout, SimpleNamespace(answers=str(answer_file), yes=True))
                calls.assert_not_called()
                self.assertEqual(layout.env_file.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
