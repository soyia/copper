import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("lan_access", ROOT / "scripts/lan-access.py")
LAN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(LAN)


def valid_values() -> dict[str, str]:
    return {
        "COPPER_DEPLOY_MODE": "lan",
        "COPPER_TLS_MODE": "lan",
        "COPPER_DOMAIN": "copper.office.example.cn",
        "COPPER_LAN_BIND_IP": "192.168.50.20",
        "COPPER_LAN_INTERFACE": "ens33",
        "COPPER_LAN_DNS_SERVER": "192.168.50.1",
        "COPPER_LAN_ALLOWED_CIDRS": "192.168.50.0/24",
        "COPPER_LAN_ADDRESS_RESERVED": "yes",
        "COPPER_LAN_DOMAIN_CONTROL_CONFIRMED": "yes",
        "COPPER_LAN_NAME_RESOLUTION": "dns",
    }


def valid_facts() -> dict:
    return {
        "interfaceAvailable": True,
        "interfaceAddresses": ["192.168.50.20"],
        "dnsAvailable": True,
        "resolvedAddresses": ["192.168.50.20"],
        "systemDnsAvailable": True,
        "systemResolvedAddresses": ["192.168.50.20"],
        "hostsOverrides": [],
        "certificate": {
            "present": True,
            "permissions": {
                "fullchain": "0:10001 640",
                "privateKey": "0:10001 640",
                "trustAnchor": "0:0 644",
            },
            "hostnameMatches": True,
            "keyMatches": True,
            "chainTrusted": True,
            "trustAnchorIsCa": True,
            "trustAnchorValidForMinimumDays": True,
            "selfSignedLeaf": False,
            "validForMinimumDays": True,
            "daysRemaining": 364.0,
        },
        "firewallInstalled": True,
        "firewallError": "",
    }


class LanAccessTests(unittest.TestCase):
    def test_domain_identity_rejects_ip_literals_and_reserved_names(self) -> None:
        self.assertTrue(LAN.valid_domain("copper.office.customer.com"))
        self.assertFalse(LAN.valid_domain("192.168.50.20"))
        self.assertFalse(LAN.valid_domain("copper.local"))
        self.assertFalse(LAN.valid_domain("copper.example"))

    def test_complete_lan_contract_passes(self) -> None:
        document = LAN.evaluate(valid_values(), valid_facts(), require_firewall=True)
        self.assertEqual(document["status"], "PASS")
        self.assertTrue(document["writeAllowed"])

    def test_public_bind_address_is_rejected(self) -> None:
        values = valid_values()
        values["COPPER_LAN_BIND_IP"] = "8.8.8.8"
        facts = valid_facts()
        facts["interfaceAddresses"] = ["8.8.8.8"]
        facts["resolvedAddresses"] = ["8.8.8.8"]
        document = LAN.evaluate(values, facts)
        self.assertEqual(document["status"], "FAIL")
        self.assertEqual(self.status_for(document, "address.privateIpv4"), "FAIL")

    def test_mdns_and_hosts_shortcuts_are_not_formal_identity(self) -> None:
        values = valid_values()
        values["COPPER_DOMAIN"] = "copper.local"
        values["COPPER_LAN_NAME_RESOLUTION"] = "hosts"
        document = LAN.evaluate(values, valid_facts())
        self.assertEqual(self.status_for(document, "name.domain"), "FAIL")
        self.assertEqual(self.status_for(document, "name.strategy"), "FAIL")

    def test_dns_must_resolve_only_to_bound_lan_address(self) -> None:
        facts = valid_facts()
        facts["resolvedAddresses"] = ["192.168.50.20", "203.0.113.10"]
        document = LAN.evaluate(valid_values(), facts)
        self.assertEqual(self.status_for(document, "name.authoritativeResolution"), "FAIL")

    def test_private_dns_server_is_mandatory(self) -> None:
        values = valid_values()
        values["COPPER_LAN_DNS_SERVER"] = "8.8.8.8"
        document = LAN.evaluate(values, valid_facts())
        self.assertEqual(self.status_for(document, "name.dnsServer"), "FAIL")

    def test_system_resolution_must_match_controlled_dns(self) -> None:
        facts = valid_facts()
        facts["systemResolvedAddresses"] = ["127.0.0.1"]
        document = LAN.evaluate(valid_values(), facts)
        self.assertEqual(self.status_for(document, "name.clientResolution"), "FAIL")

    def test_hosts_override_is_rejected_even_when_dns_exists(self) -> None:
        facts = valid_facts()
        facts["hostsOverrides"] = ["line 7: 192.168.50.20"]
        document = LAN.evaluate(valid_values(), facts)
        self.assertEqual(self.status_for(document, "name.noHostsOverride"), "FAIL")

    def test_default_route_and_public_cidrs_are_rejected(self) -> None:
        values = valid_values()
        values["COPPER_LAN_ALLOWED_CIDRS"] = "0.0.0.0/0"
        document = LAN.evaluate(values, valid_facts())
        self.assertEqual(self.status_for(document, "firewall.cidrs"), "FAIL")

    def test_unreserved_address_is_rejected(self) -> None:
        values = valid_values()
        values["COPPER_LAN_ADDRESS_RESERVED"] = "no"
        document = LAN.evaluate(values, valid_facts())
        self.assertEqual(self.status_for(document, "address.reserved"), "FAIL")

    def test_self_signed_leaf_is_rejected(self) -> None:
        facts = valid_facts()
        facts["certificate"]["selfSignedLeaf"] = True
        document = LAN.evaluate(valid_values(), facts)
        self.assertEqual(self.status_for(document, "tls.leaf"), "FAIL")

    def test_short_lived_or_untrusted_certificate_is_rejected(self) -> None:
        facts = valid_facts()
        facts["certificate"]["chainTrusted"] = False
        facts["certificate"]["validForMinimumDays"] = False
        facts["certificate"]["daysRemaining"] = 6.0
        document = LAN.evaluate(valid_values(), facts)
        self.assertEqual(self.status_for(document, "tls.chain"), "FAIL")
        self.assertEqual(self.status_for(document, "tls.validity"), "FAIL")

    def test_non_ca_or_expiring_trust_anchor_is_rejected(self) -> None:
        facts = valid_facts()
        facts["certificate"]["trustAnchorIsCa"] = False
        facts["certificate"]["trustAnchorValidForMinimumDays"] = False
        document = LAN.evaluate(valid_values(), facts)
        self.assertEqual(self.status_for(document, "tls.trustAnchorCa"), "FAIL")
        self.assertEqual(self.status_for(document, "tls.trustAnchorValidity"), "FAIL")

    def test_runtime_status_requires_firewall(self) -> None:
        facts = valid_facts()
        facts["firewallInstalled"] = False
        document = LAN.evaluate(valid_values(), facts, require_firewall=True)
        self.assertEqual(self.status_for(document, "firewall.runtime"), "FAIL")

    @staticmethod
    def status_for(document: dict, check_id: str) -> str:
        return next(item["status"] for item in document["checks"] if item["id"] == check_id)


if __name__ == "__main__":
    unittest.main()
