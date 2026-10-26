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
TOOL = ROOT / "scripts/restore-metadata.py"
BACKUP_ID = "0123456789abcdef0123456789abcdef"
WEB = "web@sha256:" + "1" * 64
BACKEND = "backend@sha256:" + "2" * 64
MYSQL = "mysql@sha256:" + "3" * 64
OFFLINE_PROOF = "a" * 64
OFFLINE_PROOF_SHA256 = hashlib.sha256(OFFLINE_PROOF.encode("ascii")).hexdigest()


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True) + "\n", encoding="utf-8")


class RestoreMetadataTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="copper-restore-metadata-")
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

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

    def make_payload(self, ocr: dict[str, str] | None = None) -> Path:
        payload = self.root / "payload"
        attachment = payload / "attachments/inbounds/7/evidence.txt"
        dump = payload / "database/copper.sql.zst"
        attachment.parent.mkdir(parents=True)
        dump.parent.mkdir(parents=True)
        attachment.write_bytes(b"attachment\n")
        dump.write_bytes(b"fake-zstd-for-metadata-only")
        schema = {
            "databaseProduct": "MySQL",
            "databaseVersion": "8.4.10",
            "schemaVersion": "1",
            "tableCount": 1,
            "tables": ["sample_table"],
        }
        records = {"tableCount": 1, "totalRows": 2, "tables": [{"name": "sample_table", "rows": 2}]}
        totals = {"metrics": [{"metric": "stock.quantity_available", "value": "10.000000"}]}
        attachments = {
            "fileCount": 1,
            "totalBytes": attachment.stat().st_size,
            "databaseReferenceCount": 1,
            "orphanFileCount": 0,
            "files": [
                {
                    "path": "inbounds/7/evidence.txt",
                    "size": attachment.stat().st_size,
                    "sha256": digest(attachment),
                    "referencedBy": [{"source": "inbound_attachment", "id": 7}],
                }
            ],
        }
        runtime = {
            "containsSecrets": False,
            "values": {
                "siteId": "copper-test",
                "projectName": "copper",
                "deployMode": "external-proxy",
                "domain": "test.internal",
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
        manifest = {
            "formatVersion": "copper-backup/v1",
            "backupId": BACKUP_ID,
            "siteId": "copper-test",
            "purpose": "manual",
            "startedAt": "2026-07-13T01:00:00Z",
            "completedAt": "2026-07-13T01:01:00Z",
            "consistency": {
                "mode": "graceful-write-quiesce",
                "activeTransactionsAtSnapshot": 0,
                "maintenanceExit": "healthy",
            },
            "components": {"webImage": WEB, "backendImage": BACKEND, "mysqlImage": MYSQL},
            "database": {
                "name": "copper_v2",
                "version": "8.4.10",
                "schemaVersion": "1",
                "dump": {"path": "database/copper.sql.zst", "size": dump.stat().st_size, "sha256": digest(dump)},
                "tableCount": 1,
                "totalRows": 2,
            },
            "attachments": {
                "fileCount": 1,
                "totalBytes": attachment.stat().st_size,
                "databaseReferenceCount": 1,
                "orphanFileCount": 0,
            },
            "verification": {
                "schema": "verification/schema.json",
                "recordCounts": "verification/record-counts.json",
                "businessTotals": "verification/business-totals.json",
                "attachments": "verification/attachments.json",
                "offlineKeyProof": "verification/offline-proof.txt",
            },
            "schema": schema,
            "runtimeConfig": runtime,
            "businessMetricCount": 1,
        }
        write_json(payload / "verification/schema.json", schema)
        write_json(payload / "verification/record-counts.json", records)
        write_json(payload / "verification/business-totals.json", totals)
        write_json(payload / "verification/attachments.json", attachments)
        (payload / "verification/offline-proof.txt").write_text(OFFLINE_PROOF + "\n", encoding="ascii")
        runtime["values"].update(ocr or {})
        write_json(payload / "config/runtime-allowlist.json", runtime)
        write_json(payload / "manifest.json", manifest)
        files = sorted(path for path in payload.rglob("*") if path.is_file())
        checks = "".join(f"{digest(path)}  {path.relative_to(payload).as_posix()}\n" for path in files)
        (payload / "checksums.sha256").write_text(checks, encoding="ascii")
        return payload

    def refresh_checksums(self, payload: Path) -> None:
        files = sorted(path for path in payload.rglob("*") if path.is_file() and path.name != "checksums.sha256")
        (payload / "checksums.sha256").write_text(
            "".join(f"{digest(path)}  {path.relative_to(payload).as_posix()}\n" for path in files),
            encoding="ascii",
        )

    def test_full_payload_actual_writer_checksum_and_pax_metadata_roundtrip(self) -> None:
        payload = self.make_payload()
        writer = (ROOT / "scripts/backup.sh").read_text()
        checks = re.findall(r"^\s*(find \. -type f ! -name checksums\.sha256 -print0 \| LC_ALL=C sort -z \| xargs -0 sha256sum > checksums\.sha256)$", writer, re.M)
        self.assertEqual(len(checks), 2)
        subprocess.run(["bash", "-o", "pipefail", "-c", checks[0]], cwd=payload, check=True)
        self.assertIn("  ./config/runtime-allowlist.json\n", (payload / "checksums.sha256").read_text())
        tar_commands = re.findall(r'^\s*(tar --format=pax --numeric-owner -C "\$payload_dir" -cf - \.) \| zstd ', writer, re.M)
        self.assertEqual(len(tar_commands), 2)
        archive = self.root / "writer-full.tar"
        with archive.open("wb") as output:
            subprocess.run(["bash", "-c", tar_commands[0]], env=os.environ | {"payload_dir": str(payload), "COPYFILE_DISABLE": "1"}, stdout=output, check=True)
        extracted = self.root / "writer-full-extracted"
        extracted.mkdir()
        self.run_tool("extract-payload", archive, extracted)
        self.run_tool("verify-payload", extracted, "copper-test", WEB, BACKEND, MYSQL, self.root / "writer-full-summary.json",
                      "--expected-backup-id", BACKUP_ID, "--expected-offline-proof-sha256", OFFLINE_PROOF_SHA256)
        (extracted / "attachments/inbounds/7/evidence.txt").write_bytes(b"tampered")
        self.run_tool("verify-payload", extracted, "copper-test", WEB, BACKEND, MYSQL, self.root / "tampered-summary.json", success=False)

    def test_pax_payload_symlinks_and_hardlinks_remain_rejected(self) -> None:
        preserved = self.root / "outside"
        preserved.write_bytes(b"preserve outside")
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
            archive = self.root / ("link-" + kind.decode() + ".tar")
            with tarfile.open(archive, "w", format=tarfile.PAX_FORMAT) as handle:
                member = tarfile.TarInfo("./attachments/unsafe")
                member.type = kind
                member.linkname = "../../outside"
                handle.addfile(member)
            extracted = self.root / ("link-" + kind.decode())
            extracted.mkdir()
            result = self.run_tool("extract-payload", archive, extracted, success=False)
            self.assertIn("非普通成员", result.stderr)
            self.assertEqual(preserved.read_bytes(), b"preserve outside")

    def test_pax_payload_traversal_and_normalized_alias_duplicates_rejected(self) -> None:
        preserved = self.root / "outside"
        preserved.write_bytes(b"preserve outside")
        payload = self.make_payload()
        sample = payload / "config/runtime-allowlist.json"
        cases = [("../outside",), ("./../outside",), (str(preserved),),
                 ("config/runtime-allowlist.json", "./config/runtime-allowlist.json"),
                 ("./config/runtime-allowlist.json", "././config/runtime-allowlist.json")]
        for index, names in enumerate(cases):
            with self.subTest(names=names):
                archive = self.root / ("unsafe-" + str(index) + ".tar")
                with tarfile.open(archive, "w", format=tarfile.PAX_FORMAT) as handle:
                    for name in names:
                        # TarFile.add strips an absolute arcname. Preserve the
                        # hostile header explicitly to exercise the real guard.
                        member = handle.gettarinfo(sample, arcname=name)
                        member.name = name
                        with sample.open("rb") as stream:
                            handle.addfile(member, stream)
                extracted = self.root / ("unsafe-" + str(index))
                extracted.mkdir()
                result = self.run_tool("extract-payload", archive, extracted, success=False)
                self.assertIn("重复路径" if len(names) > 1 else "路径不安全", result.stderr)
                self.assertEqual(preserved.read_bytes(), b"preserve outside")

    def test_payload_contract_passes(self) -> None:
        payload = self.make_payload()
        output = self.root / "summary.json"
        self.run_tool(
            "verify-payload", payload, "copper-test", WEB, BACKEND, MYSQL, output,
            "--expected-backup-id", BACKUP_ID,
            "--expected-offline-proof-sha256", OFFLINE_PROOF_SHA256,
        )
        self.assertEqual(json.loads(output.read_text())["attachmentReferenceCount"], 1)

    def test_optional_ocr_settings_survive_payload_verification_without_changing_totals(self) -> None:
        settings = {"ocrEnabled": "true", "ocrEndpoint": "http://copper-ocr:8080/ocr",
                    "ocrNetwork": "copper-private-ocr", "ocrEngine": "PADDLEOCR_LOCAL",
                    "ocrModel": "PP-OCRv6-medium", "ocrTimeoutSeconds": "120"}
        payload = self.make_payload(settings)
        output = self.root / "ocr-summary.json"
        self.run_tool("verify-payload", payload, "copper-test", WEB, BACKEND, MYSQL, output,
                      "--expected-offline-proof-sha256", OFFLINE_PROOF_SHA256)
        self.assertEqual(json.loads(output.read_text())["totalRows"], 2)
        runtime = json.loads((payload / "config/runtime-allowlist.json").read_text())
        self.assertEqual({key: runtime["values"][key] for key in settings}, settings)
        self.assertEqual(json.loads((payload / "verification/business-totals.json").read_text())
                         ["metrics"][0]["value"], "10.000000")

    def test_partial_optional_ocr_configuration_is_rejected(self) -> None:
        payload = self.make_payload({"ocrEnabled": "true"})
        result = self.run_tool("verify-payload", payload, "copper-test", WEB, BACKEND, MYSQL,
                               self.root / "out.json", "--expected-offline-proof-sha256",
                               OFFLINE_PROOF_SHA256, success=False)
        self.assertIn("必须完整保留", result.stderr)

    def test_unknown_optional_configuration_is_rejected(self) -> None:
        payload = self.make_payload({"ocrUnknown": "value"})
        result = self.run_tool("verify-payload", payload, "copper-test", WEB, BACKEND, MYSQL,
                               self.root / "out.json", "--expected-offline-proof-sha256",
                               OFFLINE_PROOF_SHA256, success=False)
        self.assertIn("允许清单", result.stderr)

    def test_outer_and_inner_backup_ids_must_match(self) -> None:
        payload = self.make_payload()
        result = self.run_tool(
            "verify-payload", payload, "copper-test", WEB, BACKEND, MYSQL, self.root / "summary.json",
            "--expected-backup-id", "f" * 32,
            "--expected-offline-proof-sha256", OFFLINE_PROOF_SHA256, success=False,
        )
        self.assertIn("backupId", result.stderr)

    def test_backup_can_restore_to_a_distinct_target_site(self) -> None:
        payload = self.make_payload()
        output = self.root / "cross-site-summary.json"
        source_fingerprint = hashlib.sha256(b"copper-test").hexdigest()[:16]
        self.run_tool(
            "verify-payload", payload, "replacement-site", WEB, BACKEND, MYSQL, output,
            "--expected-backup-id", BACKUP_ID,
            "--expected-site-fingerprint", source_fingerprint,
            "--expected-offline-proof-sha256", OFFLINE_PROOF_SHA256,
        )
        summary = json.loads(output.read_text())
        self.assertEqual(summary["siteId"], "replacement-site")
        self.assertEqual(summary["sourceSiteId"], "copper-test")
        self.assertEqual(summary["sourceSiteFingerprint"], source_fingerprint)

    def test_outer_fingerprint_must_bind_to_source_site(self) -> None:
        payload = self.make_payload()
        target_fingerprint = hashlib.sha256(b"replacement-site").hexdigest()[:16]
        result = self.run_tool(
            "verify-payload", payload, "replacement-site", WEB, BACKEND, MYSQL, self.root / "summary.json",
            "--expected-backup-id", BACKUP_ID,
            "--expected-site-fingerprint", target_fingerprint,
            "--expected-offline-proof-sha256", OFFLINE_PROOF_SHA256, success=False,
        )
        self.assertIn("siteId 指纹不一致", result.stderr)

    def test_runtime_site_must_match_source_manifest(self) -> None:
        payload = self.make_payload()
        runtime_path = payload / "config/runtime-allowlist.json"
        runtime = json.loads(runtime_path.read_text())
        runtime["values"]["siteId"] = "another-source"
        write_json(runtime_path, runtime)
        manifest_path = payload / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["runtimeConfig"] = runtime
        write_json(manifest_path, manifest)
        self.refresh_checksums(payload)
        result = self.run_tool(
            "verify-payload", payload, "replacement-site", WEB, BACKEND, MYSQL, self.root / "summary.json",
            "--expected-backup-id", BACKUP_ID,
            "--expected-offline-proof-sha256", OFFLINE_PROOF_SHA256, success=False,
        )
        self.assertIn("站点标识不一致", result.stderr)

    def test_restore_state_records_distinct_source_provenance(self) -> None:
        state = self.root / "state.json"
        source_fingerprint = hashlib.sha256(b"copper-test").hexdigest()[:16]
        self.run_tool(
            "write-state", state,
            "restoreId=replacement-restore",
            "siteId=replacement-site",
            "sourceSiteId=copper-test",
            f"sourceSiteFingerprint={source_fingerprint}",
        )
        document = json.loads(state.read_text())
        self.assertEqual(document["siteId"], "replacement-site")
        self.assertEqual(document["sourceSiteId"], "copper-test")
        self.assertEqual(document["sourceSiteFingerprint"], source_fingerprint)

        result = self.run_tool(
            "write-state", state, "sourceSiteFingerprint=not-a-fingerprint", success=False,
        )
        self.assertIn("sourceSiteFingerprint 格式不正确", result.stderr)

    def test_duplicate_attachment_reference_is_rejected(self) -> None:
        payload = self.make_payload()
        path = payload / "verification/attachments.json"
        value = json.loads(path.read_text())
        value["files"][0]["referencedBy"].append({"source": "inbound_attachment", "id": 7})
        value["databaseReferenceCount"] = 2
        value_manifest = json.loads((payload / "manifest.json").read_text())
        value_manifest["attachments"]["databaseReferenceCount"] = 2
        write_json(path, value)
        write_json(payload / "manifest.json", value_manifest)
        self.refresh_checksums(payload)
        result = self.run_tool(
            "verify-payload", payload, "copper-test", WEB, BACKEND, MYSQL, self.root / "summary.json",
            "--expected-backup-id", BACKUP_ID,
            "--expected-offline-proof-sha256", OFFLINE_PROOF_SHA256, success=False,
        )
        self.assertIn("重复引用", result.stderr)

    def test_offline_proof_is_allowed_and_bound_to_envelope_digest(self) -> None:
        payload = self.make_payload()
        archive = self.root / "payload.tar"
        with tarfile.open(archive, "w", format=tarfile.PAX_FORMAT) as handle:
            for path in sorted(payload.rglob("*")):
                handle.add(path, arcname=path.relative_to(payload).as_posix(), recursive=False)
        extracted = self.root / "extracted"
        extracted.mkdir()
        self.run_tool("extract-payload", archive, extracted)
        self.assertEqual((extracted / "verification/offline-proof.txt").read_text(), OFFLINE_PROOF + "\n")
        (extracted / "verification/offline-proof.txt").write_text("b" * 64 + "\n", encoding="ascii")
        self.refresh_checksums(extracted)
        result = self.run_tool(
            "verify-payload", extracted, "copper-test", WEB, BACKEND, MYSQL, self.root / "summary.json",
            "--expected-backup-id", BACKUP_ID,
            "--expected-offline-proof-sha256", OFFLINE_PROOF_SHA256, success=False,
        )
        self.assertIn("离线持钥证明摘要不一致", result.stderr)

    def test_legacy_payload_without_offline_proof_remains_supported(self) -> None:
        payload = self.make_payload()
        (payload / "verification/offline-proof.txt").unlink()
        manifest_path = payload / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["verification"].pop("offlineKeyProof")
        write_json(manifest_path, manifest)
        self.refresh_checksums(payload)
        self.run_tool(
            "verify-payload", payload, "copper-test", WEB, BACKEND, MYSQL, self.root / "legacy-summary.json",
            "--expected-backup-id", BACKUP_ID,
        )

    def test_sql_scope_rejects_server_level_statements(self) -> None:
        good = self.root / "good.sql"
        good.write_text("CREATE DATABASE IF NOT EXISTS `copper_v2`;\nUSE `copper_v2`;\nCREATE TABLE `x` (`id` INT, `memo` TEXT);\nINSERT INTO `x` VALUES (1, 'GRANT CREATE USER');\n")
        self.run_tool("validate-sql", good)
        bad = self.root / "bad.sql"
        bad.write_text("USE copper_v2;\nCREATE USER bad@localhost IDENTIFIED BY 'x';\n")
        self.assertNotEqual(self.run_tool("validate-sql", bad, success=False).returncode, 0)

    def test_core_foreign_key_contract_is_versioned_and_rejects_missing_mapping(self) -> None:
        contract = runpy.run_path(str(TOOL))["CORE_FOREIGN_KEYS"]
        metadata = self.root / "foreign-key-metadata.tsv"

        metadata.write_text("", encoding="utf-8")
        legacy = self.run_tool("assert-core-foreign-keys", "56", metadata)
        self.assertIn("not_required", legacy.stdout)

        rows = ["\t".join(str(value) for value in (*row, "RESTRICT", "RESTRICT")) for row in sorted(contract)]
        metadata.write_text("\n".join(rows) + "\n", encoding="utf-8")
        previous = self.run_tool("assert-core-foreign-keys", "66", metadata)
        self.assertIn("not_required", previous.stdout)

        current = self.run_tool("assert-core-foreign-keys", "67", metadata)
        self.assertIn("core_foreign_keys=78", current.stdout)

        metadata.write_text("\n".join(rows[1:]) + "\n", encoding="utf-8")
        missing = self.run_tool("assert-core-foreign-keys", "67", metadata, success=False)
        self.assertIn("核心外键缺失或映射错误", missing.stderr)

        cascade = rows.copy()
        cascade[0] = cascade[0].rsplit("\t", 2)[0] + "\tCASCADE\tRESTRICT"
        metadata.write_text("\n".join(cascade) + "\n", encoding="utf-8")
        unsafe = self.run_tool("assert-core-foreign-keys", "67", metadata, success=False)
        self.assertIn("核心外键不是 RESTRICT", unsafe.stderr)

    def test_existing_no_action_requires_real_mysql84_innodb_engine_evidence(self) -> None:
        contract = sorted(runpy.run_path(str(TOOL))["CORE_FOREIGN_KEYS"])
        operational_prefixes = ("fk_purchase_followup_", "fk_external_confirmation_", "fk_document_intake_")
        historical = [row for row in contract if row[0].startswith(("fk_direct_trade_", *operational_prefixes))]
        self.assertEqual(24, len(historical))
        historical_names = {row[0] for row in historical}
        rows = ["\t".join(str(value) for value in (*row, *(('NO ACTION', 'NO ACTION') if row[0] in historical_names else ('RESTRICT', 'RESTRICT')))) for row in contract]
        metadata = self.root / "mixed-foreign-key-metadata.tsv"
        metadata.write_text("\n".join(rows) + "\n")
        self.run_tool("assert-core-foreign-keys", "67", metadata, success=False)
        engines = self.root / "table-engines.tsv"
        tables = sorted({name for row in contract for name in (row[1], row[3])})
        engines.write_text("".join(f"{table}\tInnoDB\n" for table in tables))
        arguments = ("assert-core-foreign-keys", "67", metadata, "--table-engines", engines, "--database-version", "8.4.11")
        self.assertIn("core_foreign_keys=78", self.run_tool(*arguments).stdout)
        engines.write_text("".join(f"{table}\t{'ndbcluster' if table == historical[0][1] else 'InnoDB'}\n" for table in tables))
        self.run_tool(*arguments, success=False)
        engines.write_text("".join(f"{table}\tInnoDB\n" for table in tables))
        self.run_tool(*arguments[:-1], "8.0.42", success=False)
        for rule in ("CASCADE", "SET NULL"):
            with self.subTest(rule=rule):
                metadata.write_text("\n".join(rows[:-1] + [rows[-1].rsplit("\t", 2)[0] + f"\t{rule}\tNO ACTION"]) + "\n")
                self.run_tool(*arguments, success=False)

    def test_outer_tar_rejects_traversal_member(self) -> None:
        archive = self.root / "bad.copperbak"
        content = self.root / "content"
        content.write_bytes(b"bad")
        with tarfile.open(archive, "w", format=tarfile.PAX_FORMAT) as handle:
            handle.add(content, arcname="../escape")
        sidecar = Path(f"{archive}.sha256")
        sidecar.write_text(f"{digest(archive)}  {archive.name}\n", encoding="ascii")
        output = self.root / "outer"
        output.mkdir()
        self.assertNotEqual(self.run_tool("extract-outer", archive, sidecar, output, success=False).returncode, 0)


if __name__ == "__main__":
    unittest.main()
