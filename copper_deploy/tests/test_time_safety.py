#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import unittest
import datetime as dt


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("copper_time_safety", ROOT / "scripts/time-safety.py")
TIME_SAFETY = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(TIME_SAFETY)


class TimeSafetyTests(unittest.TestCase):
    def test_offset_parsing_supports_chrony_and_timesyncd(self) -> None:
        self.assertEqual(TIME_SAFETY.parse_offset_ms("Offset: +1.25s"), 1250)
        self.assertEqual(TIME_SAFETY.parse_offset_ms("System time     : -65.5 seconds fast of NTP time"), -65500)
        self.assertEqual(TIME_SAFETY.parse_offset_ms("Last offset     : +0.000006 seconds"), 0.006)

    def test_classification_prioritizes_dangerous_offset(self) -> None:
        self.assertEqual(TIME_SAFETY.classify(False, 65_000)[0], "BLOCKED")
        self.assertEqual(TIME_SAFETY.classify(False, None)[0], "CRITICAL")
        self.assertEqual(TIME_SAFETY.classify(True, 5_500)[0], "CRITICAL")
        self.assertEqual(TIME_SAFETY.classify(True, 1_500)[0], "WARNING")
        self.assertEqual(TIME_SAFETY.classify(True, 0.5)[0], "NORMAL")
        self.assertEqual(TIME_SAFETY.classify(True, 0.5, 120_000)[0], "BLOCKED")
        self.assertEqual(TIME_SAFETY.classify(False, 0.5, None, blocked_latched=True)[0], "BLOCKED")
        self.assertEqual(TIME_SAFETY.classify(True, 0.5, None, blocked_latched=True)[0], "NORMAL")

    def test_same_boot_monotonic_clock_detects_wall_time_jump(self) -> None:
        previous = {
            "formatVersion": "copper-time-safety/v1",
            "observedAt": "2026-07-15T00:00:00Z",
            "bootId": "boot-1",
            "monotonicNs": 10_000_000_000,
        }
        jump = TIME_SAFETY.discontinuity_ms(
            previous,
            dt.datetime(2026, 7, 15, 0, 2, 1, tzinfo=dt.timezone.utc),
            11_000_000_000,
            "boot-1",
        )
        self.assertEqual(jump, 120_000)
        self.assertIsNone(TIME_SAFETY.discontinuity_ms(
            previous,
            dt.datetime(2026, 7, 15, 0, 2, 1, tzinfo=dt.timezone.utc),
            11_000_000_000,
            "another-boot",
        ))

    def test_collect_uses_real_command_shapes_without_secrets(self) -> None:
        def runner(command: list[str]) -> subprocess.CompletedProcess[str]:
            if "NTPSynchronized" in command:
                return subprocess.CompletedProcess(command, 0, "yes\n", "")
            if command[:2] == ["timedatectl", "timesync-status"]:
                return subprocess.CompletedProcess(command, 1, "", "")
            return subprocess.CompletedProcess(command, 0, "System time     : 0.000003 seconds fast of NTP time\n", "")

        document = TIME_SAFETY.collect(runner)
        self.assertEqual(document["formatVersion"], "copper-time-safety/v1")
        self.assertEqual(document["level"], "NORMAL")
        self.assertTrue(document["highRiskWritesAllowed"])
        self.assertNotIn("password", str(document).lower())

    def test_blocked_unsynchronized_snapshot_preserves_original_dangerous_jump(self) -> None:
        def runner(command: list[str]) -> subprocess.CompletedProcess[str]:
            if "NTPSynchronized" in command:
                return subprocess.CompletedProcess(command, 0, "no\n", "")
            if command[:2] == ["timedatectl", "timesync-status"]:
                return subprocess.CompletedProcess(command, 1, "", "")
            return subprocess.CompletedProcess(command, 0, "System time : 0.000001 seconds fast of NTP time\n", "")

        previous = {
            "formatVersion": "copper-time-safety/v1",
            "observedAt": "2026-07-15T00:00:00Z",
            "bootId": "boot-1",
            "monotonicNs": 10_000_000_000,
            "level": "BLOCKED",
            "clockDiscontinuityMs": 120_000,
        }
        document = TIME_SAFETY.collect(
            runner,
            previous=previous,
            now=lambda: dt.datetime(2026, 7, 15, 0, 0, 1, tzinfo=dt.timezone.utc),
            monotonic=lambda: 11_000_000_000,
            current_boot_id="boot-1",
        )
        self.assertEqual(document["level"], "BLOCKED")
        self.assertEqual(document["clockDiscontinuityMs"], 120_000)


if __name__ == "__main__":
    unittest.main()
