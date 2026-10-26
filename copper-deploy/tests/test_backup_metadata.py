#!/usr/bin/env python3

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "backup-metadata.py"


class BackupMetadataTest(unittest.TestCase):
    def run_tool(self, *args: object, expect_success: bool = True) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [sys.executable, str(TOOL), *(str(value) for value in args)],
            text=True,
            capture_output=True,
            check=False,
        )
        if expect_success and result.returncode != 0:
            self.fail(f"tool failed: {result.stderr}{result.stdout}")
        if not expect_success and result.returncode == 0:
            self.fail("tool unexpectedly succeeded")
        return result

    def test_record_counts_preserve_exact_integer_values(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "counts.tsv"
            output = root / "counts.json"
            source.write_text("partner\t8\nstock_balance\t2\n", encoding="utf-8")
            self.run_tool("record-counts", source, output)
            data = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(data["tableCount"], 2)
            self.assertEqual(data["totalRows"], 10)
            self.assertEqual(data["tables"][0], {"name": "partner", "rows": 8})

    def test_business_totals_reject_floating_point_notation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "totals.tsv"
            source.write_text("stock.amount_cost\t1.2e3\n", encoding="utf-8")
            self.run_tool("business-totals", source, root / "out.json", expect_success=False)

    def test_attachment_references_must_match_file_size_and_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            content = b"copper-attachment"
            digest = hashlib.sha256(content).hexdigest()
            files = root / "files.tsv"
            references = root / "references.tsv"
            output = root / "attachments.json"
            files.write_text(f"inbounds/7/a.pdf\t{len(content)}\t{digest}\n", encoding="utf-8")
            references.write_text(
                f"inbound_attachment\t2\tinbounds/7/a.pdf\t{len(content)}\t{digest}\n",
                encoding="utf-8",
            )
            self.run_tool("attachments", files, references, output)
            data = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(data["fileCount"], 1)
            self.assertEqual(data["databaseReferenceCount"], 1)
            self.assertEqual(data["orphanFileCount"], 0)

            references.write_text(
                f"inbound_attachment\t2\tinbounds/7/a.pdf\t999\t{digest}\n",
                encoding="utf-8",
            )
            self.run_tool("attachments", files, references, output, expect_success=False)

    def test_envelope_verification_detects_payload_corruption(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = root / "payload.tar.zst.age"
            payload.write_bytes(b"encrypted-payload")
            digest = hashlib.sha256(payload.read_bytes()).hexdigest()
            envelope = {
                "formatVersion": "copper-backup/v1",
                "backupId": "1" * 32,
                "encryption": {"format": "age", "recipientType": "X25519"},
                "payload": {
                    "path": payload.name,
                    "size": payload.stat().st_size,
                    "sha256": digest,
                },
            }
            (root / "envelope.json").write_text(json.dumps(envelope), encoding="utf-8")
            self.run_tool("verify-envelope", root)
            payload.write_bytes(b"corrupt")
            self.run_tool("verify-envelope", root, expect_success=False)

    def test_full_manifest_binds_incremental_start_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dump = root / "copper.sql.zst"
            dump.write_bytes(b"dump")
            documents = {
                "records.json": {"tableCount": 1, "totalRows": 2},
                "totals.json": {"metrics": []},
                "attachments.json": {"fileCount": 0, "totalBytes": 0, "databaseReferenceCount": 0, "orphanFileCount": 0},
                "runtime.json": {"containsSecrets": False, "values": {}},
                "schema.json": {"databaseProduct": "MySQL", "databaseVersion": "8.4.10", "schemaVersion": "1", "tableCount": 1, "tables": ["sample"]},
            }
            for name, document in documents.items():
                (root / name).write_text(json.dumps(document), encoding="utf-8")
            output = root / "manifest.json"
            common = (
                "manifest", output, "1" * 32, "copper-test", "manual",
                "2026-07-21T00:00:00Z", "2026-07-21T00:01:00Z", "Asia/Shanghai",
                "web", "backend", "mysql", "8.4.10", "1", dump,
                root / "records.json", root / "totals.json", root / "attachments.json",
                root / "runtime.json", root / "schema.json", "0.12.0-m5", "age", "zstd",
            )
            self.run_tool(*common, "--binlog-file", "binlog.000007", "--binlog-position", "157")
            manifest = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(manifest["database"]["binlogBoundary"], {"file": "binlog.000007", "position": 157})
            self.run_tool(*common, "--binlog-file", "unsafe", "--binlog-position", "157", expect_success=False)

    def test_retention_never_deletes_newest_successful_backup(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            names = [
                "copper-test_20260101T010000Z_11111111111111111111111111111111_manual.copperbak",
                "copper-test_20260201T010000Z_22222222222222222222222222222222_manual.copperbak",
                "copper-test_20260202T010000Z_33333333333333333333333333333333_scheduled.copperbak",
            ]
            for name in names:
                archive = root / name
                archive.write_bytes(name.encode("ascii"))
                digest = hashlib.sha256(archive.read_bytes()).hexdigest()
                (root / f"{name}.sha256").write_text(f"{digest}  {name}\n", encoding="ascii")
            result = self.run_tool("retention-plan", root, "copper-test", 1, 0, 0)
            planned = set(result.stdout.splitlines())
            self.assertNotIn(names[-1], planned)
            self.assertIn(names[0], planned)
            self.assertIn(names[1], planned)

    def test_retention_ignores_archive_with_false_sidecar(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / (
                "copper-test_20260701T020000Z_"
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_manual.copperbak"
            )
            archive.write_bytes(b"not-the-declared-content")
            (root / f"{archive.name}.sha256").write_text(
                f"{'0' * 64}  {archive.name}\n", encoding="ascii"
            )
            result = self.run_tool("retention-plan", root, "copper-test", 0, 0, 0)
            self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
