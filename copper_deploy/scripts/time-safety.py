#!/usr/bin/env python3
"""Publish the host clock state consumed by Copper high-risk write guards."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import re
import subprocess
import tempfile
import time
from typing import Callable


FORMAT = "copper-time-safety/v1"


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def iso(value: dt.datetime) -> str:
    return value.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=5,
            check=False,
            env={**os.environ, "LC_ALL": "C"},
        )
    except (OSError, subprocess.TimeoutExpired):
        return subprocess.CompletedProcess(command, 124, "", "")


def parse_offset_ms(text: str) -> float | None:
    match = re.search(r"Offset:\s*([+-]?[0-9.]+)\s*(us|ms|s)\b", text)
    if match:
        return float(match.group(1)) * {"us": 0.001, "ms": 1.0, "s": 1000.0}[match.group(2)]
    match = re.search(r"^(?:System time|Last offset)\s*:\s*([+-]?[0-9.]+)\s+seconds", text, re.MULTILINE)
    return float(match.group(1)) * 1000 if match else None


def classify(
    ntp_synchronized: bool,
    offset_ms: float | None,
    clock_discontinuity_ms: float | None = None,
    blocked_latched: bool = False,
) -> tuple[str, str]:
    discontinuity = abs(clock_discontinuity_ms) if clock_discontinuity_ms is not None else None
    if discontinuity is not None and discontinuity >= 60_000:
        return "BLOCKED", f"clock-discontinuity-{discontinuity:.1f}ms"
    if blocked_latched and not ntp_synchronized:
        return "BLOCKED", "clock-safety-blocked-until-ntp-recovery"
    absolute = abs(offset_ms) if offset_ms is not None else None
    if absolute is not None and absolute >= 60_000:
        return "BLOCKED", f"clock-offset-{absolute:.1f}ms"
    if not ntp_synchronized:
        return "CRITICAL", "ntp-not-synchronized"
    if absolute is not None and absolute >= 5_000:
        return "CRITICAL", f"clock-offset-{absolute:.1f}ms"
    if absolute is not None and absolute >= 1_000:
        return "WARNING", f"clock-offset-{absolute:.1f}ms"
    return "NORMAL", "ntp-synchronized"


def boot_id() -> str:
    try:
        return pathlib.Path("/proc/sys/kernel/random/boot_id").read_text(encoding="ascii").strip()
    except OSError:
        return ""


def discontinuity_ms(
    previous: dict[str, object] | None,
    observed_at: dt.datetime,
    monotonic_ns: int,
    current_boot_id: str,
) -> float | None:
    if not previous or previous.get("formatVersion") != FORMAT or previous.get("bootId") != current_boot_id:
        return None
    previous_monotonic = previous.get("monotonicNs")
    previous_observed = previous.get("observedAt")
    if not isinstance(previous_monotonic, int) or not isinstance(previous_observed, str) or monotonic_ns < previous_monotonic:
        return None
    try:
        prior = dt.datetime.fromisoformat(previous_observed.replace("Z", "+00:00"))
    except ValueError:
        return None
    wall_elapsed_ms = (observed_at - prior).total_seconds() * 1000
    monotonic_elapsed_ms = (monotonic_ns - previous_monotonic) / 1_000_000
    return round(wall_elapsed_ms - monotonic_elapsed_ms, 3)


def collect(
    command_runner: Callable[[list[str]], subprocess.CompletedProcess[str]] = run,
    previous: dict[str, object] | None = None,
    now: Callable[[], dt.datetime] = utc_now,
    monotonic: Callable[[], int] = time.monotonic_ns,
    current_boot_id: str | None = None,
) -> dict[str, object]:
    observed_at = now()
    monotonic_ns = monotonic()
    current_boot_id = boot_id() if current_boot_id is None else current_boot_id
    sync = command_runner(["timedatectl", "show", "-p", "NTPSynchronized", "--value"])
    ntp_synchronized = sync.returncode == 0 and sync.stdout.strip().lower() == "yes"
    status = command_runner(["timedatectl", "timesync-status"])
    offset = parse_offset_ms(status.stdout) if status.returncode == 0 else None
    source = "systemd-timesyncd" if offset is not None else "unknown"
    if offset is None:
        chrony = command_runner(["chronyc", "tracking"])
        offset = parse_offset_ms(chrony.stdout) if chrony.returncode == 0 else None
        if chrony.returncode == 0:
            source = "chrony"
    jump = discontinuity_ms(previous, observed_at, monotonic_ns, current_boot_id)
    previous_jump = previous.get("clockDiscontinuityMs") if previous else None
    if (
        previous
        and previous.get("level") == "BLOCKED"
        and not ntp_synchronized
        and isinstance(previous_jump, (int, float))
        and (jump is None or abs(float(previous_jump)) > abs(jump))
    ):
        jump = float(previous_jump)
    level, reason = classify(
        ntp_synchronized,
        offset,
        jump,
        blocked_latched=bool(previous and previous.get("level") == "BLOCKED"),
    )
    return {
        "formatVersion": FORMAT,
        "observedAt": iso(observed_at),
        "bootId": current_boot_id,
        "monotonicNs": monotonic_ns,
        "level": level,
        "reason": reason,
        "ntpSynchronized": ntp_synchronized,
        "offsetMs": None if offset is None else round(offset, 3),
        "clockDiscontinuityMs": jump,
        "source": source,
        "highRiskWritesAllowed": level != "BLOCKED",
    }


def load_previous(path: pathlib.Path) -> dict[str, object] | None:
    if not path.is_file() or path.is_symlink():
        return None
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
        return document if isinstance(document, dict) else None
    except (OSError, json.JSONDecodeError):
        return None


def atomic_write(path: pathlib.Path, document: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o750)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.tmp-", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as writer:
            json.dump(document, writer, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            writer.write("\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
        parent = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    output = pathlib.Path(args.output)
    document = collect(previous=load_previous(output))
    atomic_write(output, document)
    if args.json:
        print(json.dumps(document, ensure_ascii=False, sort_keys=True))
    else:
        print(f"可信时间：{document['level']}（{document['reason']}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
