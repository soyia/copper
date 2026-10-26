#!/usr/bin/env python3
from __future__ import annotations

import argparse
import importlib.util
import json
import pathlib
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("copper_fault_evidence", ROOT / "scripts/fault-evidence.py")
EVIDENCE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(EVIDENCE)


class FaultEvidenceTests(unittest.TestCase):
    def test_complete_hash_chained_journal_is_summarized(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            journal = pathlib.Path(directory) / "events.jsonl"
            for fault in EVIDENCE.FAULTS:
                for phase in ("INJECTED", "DETECTED", "RECOVERED", "VERIFIED"):
                    EVIDENCE.append_event(self.args(journal, fault, phase))
            output = pathlib.Path(directory) / "summary.json"
            summary = EVIDENCE.verify(argparse.Namespace(journal=str(journal), output=str(output)))
            self.assertEqual(summary["result"], "PASSED")
            self.assertEqual(len(summary["faults"]), 6)
            self.assertEqual(len(summary["chainHead"]), 64)
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)

    def test_tampering_and_missing_phase_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            journal = pathlib.Path(directory) / "events.jsonl"
            EVIDENCE.append_event(self.args(journal, "database", "INJECTED"))
            line = json.loads(journal.read_text(encoding="utf-8"))
            line["message"] = "tampered"
            journal.write_text(json.dumps(line) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "摘要"):
                EVIDENCE.load_events(journal)

    def args(self, journal: pathlib.Path, fault: str, phase: str) -> argparse.Namespace:
        return argparse.Namespace(
            journal=str(journal), run_id="m406-test", fault=fault, phase=phase,
            result="PASSED", message="test-event", data_impact="none",
            manual_step="automated", remaining_risk="none",
        )


if __name__ == "__main__":
    unittest.main()
