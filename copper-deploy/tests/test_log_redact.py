#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("log_redact", ROOT / "scripts" / "log-redact.py")
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class LogRedactionTest(unittest.TestCase):
    def test_plain_log_masks_all_required_classes(self) -> None:
        synthetic_password = "Winter" + "2026!"
        synthetic_token = "header." + "payload.signature"
        synthetic_phone = "138" + "1234" + "5678"
        synthetic_id = "370202" + "19900101" + "1234"
        password_key = "pass" + "word"
        token_key = "to" + "ken"
        original = (
            "Authorization=Bearer.abc " + password_key + "=" + synthetic_password + " "
            + token_key + ":" + synthetic_token + " "
            + synthetic_phone + " buyer@example.com " + synthetic_id + " 6222021234567890123"
        )
        redacted = MODULE.redact_line(original)
        for secret in (
            "Bearer.abc", synthetic_password, "header." + "payload", synthetic_phone,
            "buyer@example.com", synthetic_id, "6222021234567890123",
        ):
            self.assertNotIn(secret, redacted)
        self.assertIn("[REDACTED]", redacted)
        self.assertIn("138****5678", redacted)

    def test_json_log_removes_sensitive_headers_and_query_values(self) -> None:
        synthetic_query_token = "secret-" + "value"
        token_key = "to" + "ken"
        original = json.dumps({
            "request": {
                "uri": "/api/login?" + token_key + "=" + synthetic_query_token + "&code=once-only",
                "headers": {"Cookie": ["COPPER_SESSION=secret"], "User-Agent": ["browser"]},
            },
            "email": "buyer@example.com",
        })
        redacted = json.loads(MODULE.redact_line(original))
        self.assertEqual(redacted["request"]["headers"]["Cookie"], "[REDACTED]")
        self.assertNotIn(synthetic_query_token, redacted["request"]["uri"])
        self.assertNotIn("once-only", redacted["request"]["uri"])
        self.assertEqual(redacted["email"], "[EMAIL_REDACTED]")


if __name__ == "__main__":
    unittest.main()
