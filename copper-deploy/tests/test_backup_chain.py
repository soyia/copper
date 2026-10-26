#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import os
import re
import runpy
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts/backup-chain.py"
BASE_ID = "1" * 32
INC1_ID = "2" * 32
INC2_ID = "3" * 32
WEB = "web@sha256:" + "4" * 64
BACKEND = "backend@sha256:" + "5" * 64
MYSQL = "mysql@sha256:" + "6" * 64


def digest_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True) + "\n", encoding="utf-8")


def attachment_snapshot(entries: dict[str, bytes]) -> dict[str, object]:
    files = [
        {"path": path, "size": len(content), "sha256": digest_bytes(content), "referencedBy": []}
        for path, content in sorted(entries.items())
    ]
    return {
        "fileCount": len(files),
        "totalBytes": sum(len(content) for content in entries.values()),
        "databaseReferenceCount": 0,
        "orphanFileCount": len(files),
        "files": files,
    }


class BackupChainTests(unittest.TestCase):
    def run_tool(self, *args: object, success: bool = True) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            ["python3", str(TOOL), *(str(value) for value in args)],
            text=True,
            capture_output=True,
            check=False,
        )
        if success and result.returncode != 0:
            self.fail(result.stderr or result.stdout)
        if not success and result.returncode == 0:
            self.fail("command unexpectedly succeeded")
        return result

    def test_attachment_delta_uses_content_and_preserves_deletions(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            target = root / "delta-files"
            source.mkdir()
            previous_entries = {"docs/kept.pdf": b"old", "docs/deleted.pdf": b"delete"}
            current_entries = {"docs/kept.pdf": b"new-content", "docs/new.jpg": b"new"}
            for relative, content in current_entries.items():
                path = source / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            state = {
                "formatVersion": "copper-backup-chain-state/v1",
                "attachmentSnapshot": attachment_snapshot(previous_entries),
            }
            state_path = root / "state.json"
            current_path = root / "current.json"
            delta_path = root / "delta.json"
            write_json(state_path, state)
            write_json(current_path, attachment_snapshot(current_entries))
            self.run_tool("attachment-delta", state_path, current_path, source, target, delta_path)
            delta = json.loads(delta_path.read_text())
            self.assertEqual([item["path"] for item in delta["addedOrChanged"]], ["docs/kept.pdf", "docs/new.jpg"])
            self.assertEqual(delta["deleted"], ["docs/deleted.pdf"])
            self.assertEqual((target / "docs/kept.pdf").read_bytes(), b"new-content")

            materialized = root / "materialized"
            for relative, content in previous_entries.items():
                path = materialized / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            payload = root / "payload"
            (payload / "verification").mkdir(parents=True)
            (payload / "attachments").mkdir(parents=True)
            write_json(payload / "verification/attachment-delta.json", delta)
            for path in target.rglob("*"):
                if path.is_file():
                    destination = payload / "attachments" / path.relative_to(target)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(path.read_bytes())
            self.run_tool("apply-delta", payload, materialized)
            actual = {path.relative_to(materialized).as_posix(): path.read_bytes() for path in materialized.rglob("*") if path.is_file()}
            self.assertEqual(actual, current_entries)

    def test_chain_rejects_missing_parent_and_accepts_exact_sequence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "base"
            inc1 = root / "inc1"
            inc2 = root / "inc2"
            base.mkdir(); inc1.mkdir(); inc2.mkdir()
            base_snapshot = attachment_snapshot({"a.pdf": b"a"})
            inc1_snapshot = attachment_snapshot({"a.pdf": b"b"})
            inc2_snapshot = attachment_snapshot({"a.pdf": b"b", "b.pdf": b"c"})
            write_json(base / "manifest.json", {
                "formatVersion": "copper-backup/v1",
                "backupId": BASE_ID,
                "completedAt": "2026-07-20T00:00:00Z",
                "database": {"binlogBoundary": {"file": "binlog.000001", "position": 157}},
            })
            write_json(base / "verification/attachments.json", base_snapshot)
            write_json(inc1 / "manifest.json", {
                "formatVersion": "copper-backup/v2", "backupType": "incremental", "backupId": INC1_ID,
                "baseBackupId": BASE_ID, "parentBackupId": BASE_ID, "chainSequence": 1,
                "completedAt": "2026-07-21T00:00:00Z",
                "database": {"binlog": {"from": {"file": "binlog.000001", "position": 157}, "to": {"file": "binlog.000002", "position": 157}}},
            })
            write_json(inc1 / "verification/attachments.json", inc1_snapshot)
            write_json(inc1 / "verification/attachment-delta.json", {
                "deleted": [], "addedOrChanged": [{"path": "a.pdf", "size": 1, "sha256": digest_bytes(b"b")}],
            })
            write_json(inc2 / "manifest.json", {
                "formatVersion": "copper-backup/v2", "backupType": "incremental", "backupId": INC2_ID,
                "baseBackupId": BASE_ID, "parentBackupId": INC1_ID, "chainSequence": 2,
                "completedAt": "2026-07-22T00:00:00Z",
                "database": {"binlog": {"from": {"file": "binlog.000002", "position": 157}, "to": {"file": "binlog.000003", "position": 157}}},
            })
            write_json(inc2 / "verification/attachments.json", inc2_snapshot)
            write_json(inc2 / "verification/attachment-delta.json", {
                "deleted": [], "addedOrChanged": [{"path": "b.pdf", "size": 1, "sha256": digest_bytes(b"c")}],
            })
            result = self.run_tool("verify-chain", base, inc1, inc2)
            self.assertEqual(json.loads(result.stdout)["chainLength"], 2)
            base_manifest = json.loads((base / "manifest.json").read_text())
            base_manifest["database"]["binlogBoundary"]["position"] = 158
            write_json(base / "manifest.json", base_manifest)
            self.run_tool("verify-chain", base, inc1, inc2, success=False)
            base_manifest["database"]["binlogBoundary"]["position"] = 157
            write_json(base / "manifest.json", base_manifest)
            manifest = json.loads((inc2 / "manifest.json").read_text())
            manifest["parentBackupId"] = BASE_ID
            write_json(inc2 / "manifest.json", manifest)
            self.run_tool("verify-chain", base, inc1, inc2, success=False)

    def make_archive(self, directory: Path, timestamp: str, backup_id: str, envelope: dict) -> str:
        name = f"copper-test_{timestamp}_{backup_id}_scheduled.copperbak"
        source = directory / f"{name}.envelope.json"
        write_json(source, envelope)
        archive = directory / name
        with tarfile.open(archive, "w", format=tarfile.PAX_FORMAT) as handle:
            handle.add(source, arcname="envelope.json")
        (directory / f"{name}.sha256").write_text(f"{digest(archive)}  {name}\n", encoding="ascii")
        source.unlink()
        return name

    def test_retention_deletes_only_complete_expired_chains(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old_base = "a" * 32
            old_inc = "b" * 32
            new_base = "c" * 32
            old_base_name = self.make_archive(root, "20260601T000000Z", old_base, {"formatVersion": "copper-backup/v1", "backupId": old_base})
            old_inc_name = self.make_archive(root, "20260602T000000Z", old_inc, {
                "formatVersion": "copper-backup/v2", "backupType": "incremental", "backupId": old_inc,
                "baseBackupId": old_base, "parentBackupId": old_base, "chainSequence": 1,
            })
            new_base_name = self.make_archive(root, "20260701T000000Z", new_base, {"formatVersion": "copper-backup/v1", "backupId": new_base})
            result = self.run_tool("retention-plan", root, "copper-test", 0, 0, 0)
            planned = set(result.stdout.splitlines())
            self.assertEqual(planned, {old_base_name, old_inc_name})
            self.assertNotIn(new_base_name, planned)

    def test_retention_preserves_entire_incomplete_chain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old_base = "d" * 32
            first = "e" * 32
            broken = "f" * 32
            newest = "9" * 32
            names = {
                self.make_archive(root, "20260601T000000Z", old_base, {"formatVersion": "copper-backup/v1", "backupId": old_base}),
                self.make_archive(root, "20260602T000000Z", first, {
                    "formatVersion": "copper-backup/v2", "backupType": "incremental", "backupId": first,
                    "baseBackupId": old_base, "parentBackupId": old_base, "chainSequence": 1,
                }),
                self.make_archive(root, "20260604T000000Z", broken, {
                    "formatVersion": "copper-backup/v2", "backupType": "incremental", "backupId": broken,
                    "baseBackupId": old_base, "parentBackupId": first, "chainSequence": 3,
                }),
            }
            self.make_archive(root, "20260701T000000Z", newest, {"formatVersion": "copper-backup/v1", "backupId": newest})
            result = self.run_tool("retention-plan", root, "copper-test", 0, 0, 0)
            self.assertTrue(names.isdisjoint(set(result.stdout.splitlines())))

    def test_incremental_payload_preserves_optional_ocr_configuration(self) -> None:
        self.test_incremental_payload_verifies_all_declared_files_and_boundaries({
            "ocrEnabled": "true", "ocrEndpoint": "http://copper-ocr:8080/ocr",
            "ocrNetwork": "copper-private-ocr", "ocrEngine": "PADDLEOCR_LOCAL",
            "ocrModel": "PP-OCRv6-medium", "ocrTimeoutSeconds": "120"})

    def test_incremental_payload_rejects_partial_unknown_and_sensitive_ocr_configuration(self) -> None:
        for settings in ({"ocrEnabled": "true"}, {"ocrUnknown": "value"}, {
                "ocrEnabled": "true", "ocrEndpoint": "http://" + "fixture-user:" + "example-only" + "@copper-ocr/ocr",
                "ocrNetwork": "copper-private-ocr", "ocrEngine": "PADDLEOCR_LOCAL",
                "ocrModel": "PP-OCRv6-medium", "ocrTimeoutSeconds": "120"}):
            with self.subTest(keys=sorted(settings)):
                self.test_incremental_payload_verifies_all_declared_files_and_boundaries(settings, False)

    def test_incremental_payload_uses_actual_backup_writer_and_pax_extractor(self) -> None:
        self.test_incremental_payload_verifies_all_declared_files_and_boundaries(writer_roundtrip=True)

    def test_checksum_boundary_normalizes_only_leading_dot_prefixes(self) -> None:
        tool = runpy.run_path(str(TOOL))
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "checksums.sha256"
            for prefix in ("", "./", "././"):
                path.write_text("a" * 64 + "  " + prefix + "config/runtime-allowlist.json\n")
                self.assertEqual(tool["parse_checksums"](path, {"config/runtime-allowlist.json"}), {"config/runtime-allowlist.json": "a" * 64})
            # Global logical paths must not inherit checksum-only normalization.
            with self.assertRaises(SystemExit):
                tool["safe_relative"]("./attachments/receipt.pdf")

    def test_checksum_alias_duplicates_are_rejected_after_normalization(self) -> None:
        tool = runpy.run_path(str(TOOL))
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "checksums.sha256"
            for alias in ("./config/runtime-allowlist.json", "././config/runtime-allowlist.json"):
                path.write_text("a" * 64 + "  config/runtime-allowlist.json\n" + "a" * 64 + "  " + alias + "\n")
                with self.assertRaisesRegex(SystemExit, "重复路径"):
                    tool["parse_checksums"](path, {"config/runtime-allowlist.json"})

    def test_checksum_traversal_absolute_and_noncanonical_paths_remain_rejected(self) -> None:
        tool = runpy.run_path(str(TOOL))
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "checksums.sha256"
            for unsafe in ("../escape", "./../escape", "././../escape", "/etc/escape", ".//etc/escape", "config/../escape", "config/./runtime-allowlist.json", "config//runtime-allowlist.json", "config\\runtime.json", "./", "config/evil\tname"):
                with self.subTest(path=unsafe):
                    path.write_text("a" * 64 + "  " + unsafe + "\n")
                    with self.assertRaises(SystemExit):
                        tool["parse_checksums"](path, {"config/runtime-allowlist.json"})

    def test_incremental_payload_verifies_all_declared_files_and_boundaries(self, ocr=None, expected_success=True, writer_roundtrip=False) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            payload = Path(temporary) / "payload"
            (payload / "database/binlog").mkdir(parents=True)
            (payload / "attachments/docs").mkdir(parents=True)
            (payload / "verification").mkdir()
            (payload / "config").mkdir()
            binlog = payload / "database/binlog/binlog.000001"
            attachment = payload / "attachments/docs/receipt.pdf"
            proof = "7" * 64
            binlog.write_bytes(b"raw-binlog-fixture")
            attachment.write_bytes(b"signed")
            snapshot = attachment_snapshot({"docs/receipt.pdf": b"signed"})
            write_json(payload / "verification/attachments.json", snapshot)
            delta = {
                "formatVersion": "copper-attachment-delta/v1",
                "addedOrChanged": [{"path": "docs/receipt.pdf", "size": 6, "sha256": digest_bytes(b"signed")}],
                "deleted": [],
                "finalFileCount": 1,
                "finalTotalBytes": 6,
                "finalSnapshotSha256": digest(payload / "verification/attachments.json"),
            }
            write_json(payload / "verification/attachment-delta.json", delta)
            schema = {"databaseProduct": "MySQL", "databaseVersion": "8.4.10", "schemaVersion": "1", "tableCount": 1, "tables": ["stock_balance"]}
            records = {"tableCount": 1, "totalRows": 2, "tables": [{"name": "stock_balance", "rows": 2}]}
            totals = {"metrics": [{"metric": "stock.quantity_available", "value": "18.345000"}]}
            runtime = {
                "containsSecrets": False,
                "values": {
                    "siteId": "copper-test",
                    "projectName": "copper-test",
                    "deployMode": "lan",
                    "domain": "copper.test",
                    "timeZone": "Asia/Shanghai",
                    "logLevel": "INFO",
                    "bootstrapUsername": "admin",
                    "databaseName": "copper_v2",
                    "etcDirectory": "/etc/copper",
                    "dataDirectory": "/var/lib/copper",
                    "attachmentDirectory": "/var/lib/copper/files",
                    "webImage": WEB,
                    "backendImage": BACKEND,
                    "mysqlImage": MYSQL,
                },
            }
            write_json(payload / "verification/schema.json", schema)
            write_json(payload / "verification/record-counts.json", records)
            write_json(payload / "verification/business-totals.json", totals)
            runtime["values"].update(ocr or {})
            write_json(payload / "config/runtime-allowlist.json", runtime)
            (payload / "verification/offline-proof.txt").write_text(proof + "\n", encoding="ascii")
            manifest = {
                "formatVersion": "copper-backup/v2", "backupType": "incremental",
                "backupId": INC1_ID, "baseBackupId": BASE_ID, "parentBackupId": BASE_ID, "chainSequence": 1,
                "siteId": "copper-test", "purpose": "scheduled", "startedAt": "2026-07-21T00:00:00Z", "completedAt": "2026-07-21T00:01:00Z",
                "consistency": {"mode": "graceful-write-quiesce", "activeTransactionsAtSnapshot": 0, "maintenanceExit": "healthy"},
                "components": {"webImage": WEB, "backendImage": BACKEND, "mysqlImage": MYSQL},
                "database": {
                    "name": "copper_v2", "version": "8.4.10", "schemaVersion": "1", "mode": "mysql-row-binlog", "tableCount": 1, "totalRows": 2,
                    "binlog": {"format": "ROW", "rowImage": "FULL", "from": {"file": "binlog.000001", "position": 157}, "to": {"file": "binlog.000002", "position": 157},
                               "files": [{"path": "database/binlog/binlog.000001", "name": "binlog.000001", "size": binlog.stat().st_size, "sha256": digest(binlog)}]},
                },
                "attachments": {"mode": "content-addressed-delta", "fileCount": 1, "totalBytes": 6, "databaseReferenceCount": 0, "orphanFileCount": 1,
                                "addedOrChangedCount": 1, "deletedCount": 0, "delta": "verification/attachment-delta.json"},
                "verification": {"schema": "verification/schema.json", "recordCounts": "verification/record-counts.json", "businessTotals": "verification/business-totals.json",
                                 "attachments": "verification/attachments.json", "attachmentDelta": "verification/attachment-delta.json", "offlineKeyProof": "verification/offline-proof.txt"},
                "runtimeConfig": runtime, "schema": schema, "businessMetricCount": 1,
            }
            write_json(payload / "manifest.json", manifest)
            files = sorted(path for path in payload.rglob("*") if path.is_file())
            (payload / "checksums.sha256").write_text(
                "".join(f"{digest(path)}  {path.relative_to(payload).as_posix()}\n" for path in files), encoding="ascii"
            )
            if writer_roundtrip:
                writer = (ROOT / "scripts/backup.sh").read_text()
                checksum_lines = re.findall(r"^\s*(find \. -type f ! -name checksums\.sha256 -print0 \| LC_ALL=C sort -z \| xargs -0 sha256sum > checksums\.sha256)$", writer, re.M)
                self.assertEqual(len(checksum_lines), 2)  # full and incremental production writers
                self.assertEqual(checksum_lines[0], checksum_lines[1])
                subprocess.run(["bash", "-o", "pipefail", "-c", checksum_lines[1]], cwd=payload, check=True)
                self.assertIn("  ./config/runtime-allowlist.json\n", (payload / "checksums.sha256").read_text())
                archive = Path(temporary) / "writer-payload.tar"
                tar_commands = re.findall(r'^\s*(tar --format=pax --numeric-owner -C "\$payload_dir" -cf - \.) \| zstd ', writer, re.M)
                self.assertEqual(len(tar_commands), 2)
                # BSD tar on macOS otherwise adds AppleDouble metadata that the
                # Ubuntu production writer never emits; keep the actual RUN flags.
                with archive.open("wb") as stream:
                    subprocess.run(["bash", "-c", tar_commands[1]], env=os.environ | {"payload_dir": str(payload), "COPYFILE_DISABLE": "1"}, stdout=stream, check=True)
                extracted = Path(temporary) / "extracted"
                extracted.mkdir()
                unpack = subprocess.run(["python3", str(ROOT / "scripts/restore-metadata.py"), "extract-payload", str(archive), str(extracted)], capture_output=True, text=True)
                self.assertEqual(unpack.returncode, 0, unpack.stderr)
                payload = extracted
                binlog = payload / "database/binlog/binlog.000001"
            output = Path(temporary) / "summary.json"
            result = self.run_tool(
                "verify-incremental", payload, WEB, BACKEND, MYSQL, output,
                "--expected-backup-id", INC1_ID, "--expected-base-backup-id", BASE_ID,
                "--expected-parent-backup-id", BASE_ID, "--expected-sequence", "1",
                "--expected-site-fingerprint", hashlib.sha256(b"copper-test").hexdigest()[:16],
                "--expected-created-at", "2026-07-21T00:01:00Z",
                "--expected-offline-proof-sha256", hashlib.sha256(proof.encode("ascii")).hexdigest(),
                success=expected_success,
            )
            if not expected_success:
                self.assertIn("错误", result.stderr)
                self.assertNotIn("example-only", result.stderr)
                return
            self.assertEqual(json.loads(output.read_text())["backupId"], INC1_ID)
            binlog.write_bytes(b"corrupt")
            self.run_tool("verify-incremental", payload, WEB, BACKEND, MYSQL, output, success=False)


if __name__ == "__main__":
    unittest.main()
