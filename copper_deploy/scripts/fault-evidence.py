#!/usr/bin/env python3
"""Append and verify a root-only, hash-chained M4 fault-drill journal."""

from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import hashlib
import json
import os
import pathlib
import re
import tempfile
import time
import uuid
from typing import Any


FORMAT = "copper-fault-drill-event/v1"
SUMMARY_FORMAT = "copper-fault-drill-summary/v1"
FAULTS = ("database", "disk", "backup", "certificate", "time", "power")
PHASES = ("BASELINE", "INJECTED", "DETECTED", "WRITE_ATTEMPT", "RECOVERY_STARTED", "RECOVERED", "VERIFIED")


def iso_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def canonical(document: dict[str, Any]) -> bytes:
    return json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def event_hash(document: dict[str, Any]) -> str:
    return hashlib.sha256(canonical(document)).hexdigest()


def safe_text(value: str, limit: int) -> str:
    value = value.strip()
    if not value or len(value) > limit or any(ord(char) < 32 for char in value):
        raise ValueError("故障证据文本为空、过长或包含控制字符")
    return value


def load_events(path: pathlib.Path) -> list[dict[str, Any]]:
    if not path.is_file() or path.is_symlink():
        raise ValueError("故障证据日志不存在或不安全")
    events: list[dict[str, Any]] = []
    previous = "0" * 64
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        try:
            event = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"故障证据第{number}行不是合法JSON") from exc
        stored_hash = event.pop("eventHash", "")
        if event.get("formatVersion") != FORMAT or event.get("previousHash") != previous:
            raise ValueError(f"故障证据第{number}行格式或链路错误")
        calculated = event_hash(event)
        if not re.fullmatch(r"[0-9a-f]{64}", stored_hash) or stored_hash != calculated:
            raise ValueError(f"故障证据第{number}行摘要错误")
        event["eventHash"] = stored_hash
        events.append(event)
        previous = stored_hash
    if not events:
        raise ValueError("故障证据日志为空")
    return events


def append_event(args: argparse.Namespace) -> dict[str, Any]:
    path = pathlib.Path(args.journal)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.exists() and path.is_symlink():
        raise ValueError("故障证据日志不能是符号链接")
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | os.O_APPEND, 0o600)
    os.chmod(path, 0o600)
    with os.fdopen(descriptor, "a+", encoding="utf-8") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        handle.seek(0)
        lines = [line for line in handle.read().splitlines() if line]
        previous = "0" * 64
        if lines:
            last = json.loads(lines[-1])
            previous = str(last.get("eventHash", ""))
            if not re.fullmatch(r"[0-9a-f]{64}", previous):
                raise ValueError("现有故障证据日志尾部摘要无效")
        event = {
            "formatVersion": FORMAT,
            "eventId": uuid.uuid4().hex,
            "runId": safe_text(args.run_id, 96),
            "fault": args.fault,
            "phase": args.phase,
            "result": safe_text(args.result, 32),
            "message": safe_text(args.message, 500),
            "dataImpact": safe_text(args.data_impact, 500),
            "manualStep": safe_text(args.manual_step, 500),
            "remainingRisk": safe_text(args.remaining_risk, 500),
            "occurredAt": iso_now(),
            "monotonicSeconds": round(time.monotonic(), 6),
            "previousHash": previous,
        }
        event["eventHash"] = event_hash(event)
        handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
        handle.flush()
        os.fsync(handle.fileno())
        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        return event


def verify(args: argparse.Namespace) -> dict[str, Any]:
    events = load_events(pathlib.Path(args.journal))
    run_ids = {str(event.get("runId")) for event in events}
    if len(run_ids) != 1:
        raise ValueError("故障证据混入多个runId")
    results = []
    for fault in FAULTS:
        fault_events = [event for event in events if event.get("fault") == fault]
        phases = {event.get("phase") for event in fault_events}
        required = {"INJECTED", "DETECTED", "RECOVERED", "VERIFIED"}
        if not required.issubset(phases):
            raise ValueError(f"{fault}故障证据阶段不完整")
        detected = next(event for event in fault_events if event.get("phase") == "DETECTED")
        recovered = next(event for event in reversed(fault_events) if event.get("phase") == "RECOVERED")
        if float(recovered["monotonicSeconds"]) < float(detected["monotonicSeconds"]):
            raise ValueError(f"{fault}恢复时间早于发现时间")
        verified = next(event for event in reversed(fault_events) if event.get("phase") == "VERIFIED")
        results.append({
            "fault": fault,
            "result": verified["result"],
            "detectedAt": detected["occurredAt"],
            "recoveredAt": recovered["occurredAt"],
            "recoverySeconds": round(float(recovered["monotonicSeconds"]) - float(detected["monotonicSeconds"]), 3),
            "dataImpact": verified["dataImpact"],
            "manualStep": verified["manualStep"],
            "remainingRisk": verified["remainingRisk"],
        })
    summary = {
        "formatVersion": SUMMARY_FORMAT,
        "runId": next(iter(run_ids)),
        "result": "PASSED" if all(item["result"] == "PASSED" for item in results) else "FAILED",
        "completedAt": iso_now(),
        "eventCount": len(events),
        "chainHead": events[-1]["eventHash"],
        "faults": results,
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{output.name}.tmp-", dir=output.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(summary, handle, ensure_ascii=False, sort_keys=True, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary_name, 0o600)
        os.replace(temporary_name, output)
    finally:
        pathlib.Path(temporary_name).unlink(missing_ok=True)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    subcommands = parser.add_subparsers(dest="command", required=True)
    append_parser = subcommands.add_parser("append")
    append_parser.add_argument("--journal", required=True)
    append_parser.add_argument("--run-id", required=True)
    append_parser.add_argument("--fault", choices=FAULTS, required=True)
    append_parser.add_argument("--phase", choices=PHASES, required=True)
    append_parser.add_argument("--result", required=True)
    append_parser.add_argument("--message", required=True)
    append_parser.add_argument("--data-impact", default="none")
    append_parser.add_argument("--manual-step", default="automated-acceptance")
    append_parser.add_argument("--remaining-risk", default="none")
    append_parser.set_defaults(function=append_event)
    verify_parser = subcommands.add_parser("verify")
    verify_parser.add_argument("--journal", required=True)
    verify_parser.add_argument("--output", required=True)
    verify_parser.set_defaults(function=verify)
    args = parser.parse_args()
    result = args.function(args)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
