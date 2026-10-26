#!/usr/bin/env python3
"""Redact secrets and personal data from ordinary operator log output."""

from __future__ import annotations

import json
import re
import sys
from typing import Any


SENSITIVE_KEY = re.compile(
    r"(?i)(authorization|proxy-authorization|cookie|set-cookie|password|passwd|pwd|"
    r"token|secret|api[_-]?key|credential|session)"
)
SECRET_ASSIGNMENT = re.compile(
    r"(?i)(\b(?:authorization|proxy-authorization|cookie|set-cookie|password|passwd|pwd|"
    r"token|access[_-]?token|refresh[_-]?token|secret|api[_-]?key|credential|session)\b"
    r"[\s\"']*[:=][\s\"']*)([^\s,;}\"']+)"
)
BEARER = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+")
EMAIL = re.compile(r"(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}(?![A-Z0-9._%+-])")
CHINA_ID = re.compile(r"(?<!\d)(\d{3})\d{13}([0-9Xx]{2})(?!\d)")
CHINA_MOBILE = re.compile(r"(?<!\d)(1[3-9]\d)\d{4}(\d{4})(?!\d)")
BANK_ACCOUNT = re.compile(r"(?<!\d)\d{8,15}(\d{4})(?!\d)")
SENSITIVE_QUERY = re.compile(
    r"(?i)([?&](?:password|passwd|pwd|token|access_token|refresh_token|secret|api_key|key|code)=)[^&#\s]+"
)


def redact_text(value: str) -> str:
    value = SECRET_ASSIGNMENT.sub(lambda match: match.group(1) + "[REDACTED]", value)
    value = BEARER.sub("Bearer [REDACTED]", value)
    value = SENSITIVE_QUERY.sub(lambda match: match.group(1) + "REDACTED", value)
    value = EMAIL.sub("[EMAIL_REDACTED]", value)
    value = CHINA_ID.sub(r"\1*************\2", value)
    value = CHINA_MOBILE.sub(r"\1****\2", value)
    return BANK_ACCOUNT.sub(r"************\1", value)


def redact_value(value: Any, key: str = "") -> Any:
    if SENSITIVE_KEY.search(key):
        return "[REDACTED]"
    if isinstance(value, dict):
        return {item_key: redact_value(item, str(item_key)) for item_key, item in value.items()}
    if isinstance(value, list):
        return [redact_value(item) for item in value]
    if isinstance(value, str):
        return redact_text(value)
    return value


def redact_line(line: str) -> str:
    stripped = line.rstrip("\n")
    try:
        document = json.loads(stripped)
    except (json.JSONDecodeError, TypeError):
        return redact_text(stripped)
    return json.dumps(redact_value(document), ensure_ascii=False, separators=(",", ":"))


def main() -> int:
    for line in sys.stdin:
        print(redact_line(line), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
