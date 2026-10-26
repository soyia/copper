#!/usr/bin/env python3
"""Build and validate public Copper backup metadata without third-party packages."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import runpy
import sys
from pathlib import Path, PurePosixPath


TABLE_RE = re.compile(r"^[A-Za-z0-9_]+$")
METRIC_RE = re.compile(r"^[a-z0-9_.:-]+$")
BACKUP_RE_TEMPLATE = (
    r"^{site}_(?P<timestamp>\d{{8}}T\d{{6}}Z)_"
    r"(?P<backup_id>[0-9a-f]{{32}})_(?P<purpose>manual|scheduled)\.copperbak$"
)
RUNTIME_REQUIRED_KEYS = {
    "siteId",
    "projectName",
    "deployMode",
    "domain",
    "timeZone",
    "logLevel",
    "bootstrapUsername",
    "databaseName",
    "etcDirectory",
    "dataDirectory",
    "attachmentDirectory",
    "webImage",
    "backendImage",
    "mysqlImage",
}
OCR_RUNTIME_KEYS = {"ocrEnabled", "ocrEndpoint", "ocrNetwork", "ocrEngine", "ocrModel", "ocrTimeoutSeconds"}
RUNTIME_ALLOWED_KEYS = RUNTIME_REQUIRED_KEYS | OCR_RUNTIME_KEYS


def fail(message: str) -> "None":
    raise SystemExit(f"错误：{message}")


def write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def read_tsv(path: Path, columns: int) -> list[list[str]]:
    rows: list[list[str]] = []
    with path.open("r", encoding="utf-8", newline="") as handle:
        for number, raw_line in enumerate(handle, 1):
            line = raw_line.rstrip("\n")
            if line.endswith("\r"):
                line = line[:-1]
            if not line:
                continue
            values = line.split("\t")
            if len(values) != columns:
                fail(f"{path} 第 {number} 行应有 {columns} 列，实际为 {len(values)} 列")
            rows.append(values)
    return rows


def safe_relative_path(value: str) -> str:
    if not value or "\x00" in value or "\n" in value or "\r" in value or "\t" in value:
        fail("附件路径为空或包含控制字符")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        fail(f"附件路径不安全：{value}")
    normalized = str(path)
    if normalized != value:
        fail(f"附件路径未规范化：{value}")
    return normalized


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def command_record_counts(args: argparse.Namespace) -> None:
    tables: list[dict[str, object]] = []
    seen: set[str] = set()
    total_rows = 0
    for table, count_text in read_tsv(Path(args.input), 2):
        if not TABLE_RE.fullmatch(table) or table in seen:
            fail(f"记录数清单包含非法或重复表名：{table}")
        try:
            count = int(count_text)
        except ValueError:
            fail(f"表 {table} 的记录数不是整数")
        if count < 0:
            fail(f"表 {table} 的记录数不能为负数")
        seen.add(table)
        total_rows += count
        tables.append({"name": table, "rows": count})
    tables.sort(key=lambda item: str(item["name"]))
    write_json(
        Path(args.output),
        {"tableCount": len(tables), "totalRows": total_rows, "tables": tables},
    )


def command_business_totals(args: argparse.Namespace) -> None:
    metrics: list[dict[str, str]] = []
    seen: set[str] = set()
    for metric, value in read_tsv(Path(args.input), 2):
        if not METRIC_RE.fullmatch(metric) or metric in seen:
            fail(f"关键余额清单包含非法或重复指标：{metric}")
        if not re.fullmatch(r"-?\d+(?:\.\d+)?", value):
            fail(f"关键余额 {metric} 不是十进制定点字符串")
        seen.add(metric)
        metrics.append({"metric": metric, "value": value})
    metrics.sort(key=lambda item: item["metric"])
    write_json(Path(args.output), {"metrics": metrics})


def command_attachments(args: argparse.Namespace) -> None:
    files: dict[str, dict[str, object]] = {}
    total_bytes = 0
    for relative, size_text, digest in read_tsv(Path(args.files), 3):
        relative = safe_relative_path(relative)
        if relative in files:
            fail(f"附件文件清单存在重复路径：{relative}")
        try:
            size = int(size_text)
        except ValueError:
            fail(f"附件 {relative} 的大小不是整数")
        if size < 0 or not re.fullmatch(r"[0-9a-f]{64}", digest):
            fail(f"附件 {relative} 的大小或 SHA-256 不正确")
        files[relative] = {
            "path": relative,
            "size": size,
            "sha256": digest,
            "referencedBy": [],
        }
        total_bytes += size

    reference_count = 0
    seen_references: set[tuple[str, int]] = set()
    for source, record_id_text, relative, size_text, digest in read_tsv(Path(args.references), 5):
        if not TABLE_RE.fullmatch(source):
            fail(f"附件引用来源不安全：{source}")
        try:
            record_id = int(record_id_text)
            size = int(size_text)
        except ValueError:
            fail(f"附件引用 {source}/{record_id_text} 的 ID 或大小不是整数")
        reference_key = (source, record_id)
        if record_id < 0 or reference_key in seen_references:
            fail(f"附件引用重复或 ID 非法：{source}/{record_id}")
        seen_references.add(reference_key)
        relative = safe_relative_path(relative)
        entry = files.get(relative)
        if entry is None:
            fail(f"数据库附件 {source}/{record_id} 对应文件不存在：{relative}")
        if entry["size"] != size or entry["sha256"] != digest:
            fail(f"数据库附件 {source}/{record_id} 的大小或 SHA-256 与原文件不一致")
        references = entry["referencedBy"]
        assert isinstance(references, list)
        references.append({"source": source, "id": record_id})
        reference_count += 1

    ordered_files = [files[name] for name in sorted(files)]
    orphan_count = sum(1 for entry in ordered_files if not entry["referencedBy"])
    write_json(
        Path(args.output),
        {
            "fileCount": len(ordered_files),
            "totalBytes": total_bytes,
            "databaseReferenceCount": reference_count,
            "orphanFileCount": orphan_count,
            "files": ordered_files,
        },
    )


def command_runtime_config(args: argparse.Namespace) -> None:
    values: dict[str, str] = {}
    for item in args.values:
        if "=" not in item:
            fail(f"配置项缺少等号：{item}")
        key, value = item.split("=", 1)
        if key not in RUNTIME_ALLOWED_KEYS or key in values:
            fail(f"配置项不在允许清单或重复：{key}")
        values[key] = value
    missing = sorted(RUNTIME_REQUIRED_KEYS - values.keys())
    if missing:
        fail(f"配置允许清单缺少：{', '.join(missing)}")
    try:
        runpy.run_path(str(Path(__file__).with_name("ocr-config.py")))["validate_runtime"](values)
    except ValueError as error:
        fail(str(error))
    write_json(Path(args.output), {"values": values, "containsSecrets": False})


def command_schema(args: argparse.Namespace) -> None:
    tables: list[str] = []
    with Path(args.tables).open("r", encoding="utf-8") as handle:
        for raw in handle:
            table = raw.strip()
            if not table:
                continue
            if not TABLE_RE.fullmatch(table) or table in tables:
                fail(f"结构清单包含非法或重复表名：{table}")
            tables.append(table)
    tables.sort()
    write_json(
        Path(args.output),
        {
            "databaseProduct": "MySQL",
            "databaseVersion": args.database_version,
            "schemaVersion": args.schema_version,
            "tableCount": len(tables),
            "tables": tables,
        },
    )


def load_json(path: str) -> object:
    with Path(path).open("r", encoding="utf-8") as handle:
        return json.load(handle)


def command_manifest(args: argparse.Namespace) -> None:
    dump_path = Path(args.database_dump)
    records = load_json(args.record_counts)
    totals = load_json(args.business_totals)
    attachments = load_json(args.attachments)
    runtime = load_json(args.runtime_config)
    schema = load_json(args.schema)
    assert isinstance(records, dict)
    assert isinstance(totals, dict)
    assert isinstance(attachments, dict)
    assert isinstance(runtime, dict)
    assert isinstance(schema, dict)
    database = {
        "name": "copper_v2",
        "version": args.database_version,
        "schemaVersion": args.schema_version,
        "dump": {
            "path": "database/copper.sql.zst",
            "size": dump_path.stat().st_size,
            "sha256": sha256_file(dump_path),
        },
        "tableCount": records.get("tableCount"),
        "totalRows": records.get("totalRows"),
    }
    if bool(args.binlog_file) != bool(args.binlog_position):
        fail("全量备份 binlog 文件和位置必须同时提供")
    if args.binlog_file:
        if not re.fullmatch(r"binlog\.[0-9]{6,}", args.binlog_file):
            fail("全量备份 binlog 文件名不正确")
        try:
            binlog_position = int(args.binlog_position)
        except ValueError:
            fail("全量备份 binlog 位置不是整数")
        if binlog_position < 4:
            fail("全量备份 binlog 位置不正确")
        database["binlogBoundary"] = {"file": args.binlog_file, "position": binlog_position}
    write_json(
        Path(args.output),
        {
            "formatVersion": "copper-backup/v1",
            "backupId": args.backup_id,
            "siteId": args.site_id,
            "purpose": args.purpose,
            "startedAt": args.started_at,
            "completedAt": args.completed_at,
            "timeZone": args.time_zone,
            "consistency": {
                "mode": "graceful-write-quiesce",
                "webStopped": True,
                "backendStopped": True,
                "activeTransactionsAtSnapshot": 0,
                "maintenanceExit": "healthy",
            },
            "components": {
                "webImage": args.web_image,
                "backendImage": args.backend_image,
                "mysqlImage": args.mysql_image,
            },
            "database": database,
            "attachments": {
                "fileCount": attachments.get("fileCount"),
                "totalBytes": attachments.get("totalBytes"),
                "databaseReferenceCount": attachments.get("databaseReferenceCount"),
                "orphanFileCount": attachments.get("orphanFileCount"),
            },
            "verification": {
                "schema": "verification/schema.json",
                "recordCounts": "verification/record-counts.json",
                "businessTotals": "verification/business-totals.json",
                "attachments": "verification/attachments.json",
                "offlineKeyProof": "verification/offline-proof.txt",
            },
            "runtimeConfig": runtime,
            "schema": schema,
            "businessMetricCount": len(totals.get("metrics", [])),
            "tools": {
                "copperctl": args.copperctl_version,
                "age": args.age_version,
                "zstd": args.zstd_version,
            },
        },
    )


def command_envelope(args: argparse.Namespace) -> None:
    payload = Path(args.payload)
    document = {
        "formatVersion": "copper-backup/v1",
        "backupId": args.backup_id,
        "siteFingerprint": hashlib.sha256(args.site_id.encode("utf-8")).hexdigest()[:16],
        "createdAt": args.created_at,
        "encryption": {
            "format": "age",
            "recipientType": "X25519",
            "recipientFingerprint": args.recipient_fingerprint,
        },
        "payload": {
            "path": "payload.tar.zst.age",
            "size": payload.stat().st_size,
            "sha256": sha256_file(payload),
        },
    }
    if args.offline_proof_sha256:
        if not re.fullmatch(r"[0-9a-f]{64}", args.offline_proof_sha256):
            fail("离线持钥证明 SHA-256 不正确")
        document["offlineVerification"] = {
            "format": "copper-offline-key-proof/v1",
            "proofSha256": args.offline_proof_sha256,
        }
    write_json(
        Path(args.output),
        document,
    )


def command_last_success(args: argparse.Namespace) -> None:
    archive = Path(args.archive)
    document = {
        "formatVersion": "copper-backup/v1",
        "backupId": args.backup_id,
        "completedAt": args.completed_at,
        "archive": archive.name,
        "size": archive.stat().st_size,
        "sha256": sha256_file(archive),
        "recipientFingerprint": args.recipient_fingerprint,
    }
    if args.offline_proof_sha256:
        if not re.fullmatch(r"[0-9a-f]{64}", args.offline_proof_sha256):
            fail("离线持钥证明 SHA-256 不正确")
        document["offlineVerificationProofSha256"] = args.offline_proof_sha256
    write_json(
        Path(args.output),
        document,
    )


def command_verify_envelope(args: argparse.Namespace) -> None:
    directory = Path(args.directory)
    envelope_path = directory / "envelope.json"
    payload_path = directory / "payload.tar.zst.age"
    envelope = load_json(str(envelope_path))
    if not isinstance(envelope, dict) or envelope.get("formatVersion") not in {"copper-backup/v1", "copper-backup/v2"}:
        fail("envelope.json 的备份格式版本不受支持")
    backup_id = envelope.get("backupId")
    if not isinstance(backup_id, str) or not re.fullmatch(r"[0-9a-f]{32}", backup_id):
        fail("envelope.json 的 backupId 不正确")
    encryption = envelope.get("encryption")
    if not isinstance(encryption, dict) or encryption.get("format") != "age":
        fail("envelope.json 未声明 age 加密")
    recipient_fingerprint = encryption.get("recipientFingerprint")
    if recipient_fingerprint is not None and (
        not isinstance(recipient_fingerprint, str) or not re.fullmatch(r"[0-9a-f]{16}", recipient_fingerprint)
    ):
        fail("envelope.json 的 age 接收公钥指纹不正确")
    payload = envelope.get("payload")
    if not isinstance(payload, dict) or payload.get("path") != "payload.tar.zst.age":
        fail("envelope.json 的载荷路径不正确")
    if payload.get("size") != payload_path.stat().st_size:
        fail("envelope.json 的载荷大小与文件不一致")
    if payload.get("sha256") != sha256_file(payload_path):
        fail("envelope.json 的载荷 SHA-256 与文件不一致")
    if envelope.get("formatVersion") == "copper-backup/v2":
        if envelope.get("backupType") != "incremental":
            fail("copper-backup/v2 envelope 只支持 incremental 类型")
        base_backup_id = envelope.get("baseBackupId")
        parent_backup_id = envelope.get("parentBackupId")
        chain_sequence = envelope.get("chainSequence")
        if not isinstance(base_backup_id, str) or not re.fullmatch(r"[0-9a-f]{32}", base_backup_id):
            fail("envelope.json 的 baseBackupId 不正确")
        if not isinstance(parent_backup_id, str) or not re.fullmatch(r"[0-9a-f]{32}", parent_backup_id):
            fail("envelope.json 的 parentBackupId 不正确")
        if not isinstance(chain_sequence, int) or isinstance(chain_sequence, bool) or chain_sequence < 1:
            fail("envelope.json 的 chainSequence 不正确")
    offline_verification = envelope.get("offlineVerification")
    if offline_verification is not None and (
        not isinstance(offline_verification, dict)
        or offline_verification.get("format") != "copper-offline-key-proof/v1"
        or not isinstance(offline_verification.get("proofSha256"), str)
        or not re.fullmatch(r"[0-9a-f]{64}", offline_verification["proofSha256"])
    ):
        fail("envelope.json 的离线持钥证明摘要不正确")


def parse_nonnegative(value: str, label: str) -> int:
    try:
        parsed = int(value)
    except ValueError:
        fail(f"{label} 必须是非负整数")
    if parsed < 0:
        fail(f"{label} 必须是非负整数")
    return parsed


def sidecar_is_well_formed(archive: Path) -> bool:
    sidecar = archive.with_name(f"{archive.name}.sha256")
    if not sidecar.is_file() or sidecar.is_symlink():
        return False
    try:
        lines = sidecar.read_text(encoding="ascii").splitlines()
    except (OSError, UnicodeError):
        return False
    if len(lines) != 1:
        return False
    match = re.fullmatch(r"([0-9a-f]{64})  (.+)", lines[0])
    return bool(
        match
        and match.group(2) == archive.name
        and match.group(1) == sha256_file(archive)
    )


def command_retention_plan(args: argparse.Namespace) -> None:
    directory = Path(args.directory)
    daily = parse_nonnegative(args.daily, "每日保留数")
    weekly = parse_nonnegative(args.weekly, "每周保留数")
    monthly = parse_nonnegative(args.monthly, "每月保留数")
    pattern = re.compile(BACKUP_RE_TEMPLATE.format(site=re.escape(args.site_id)))
    candidates: list[tuple[Path, dt.datetime]] = []
    for entry in directory.iterdir():
        if not entry.is_file() or entry.is_symlink():
            continue
        match = pattern.fullmatch(entry.name)
        if not match or not sidecar_is_well_formed(entry):
            continue
        timestamp = dt.datetime.strptime(match.group("timestamp"), "%Y%m%dT%H%M%SZ").replace(
            tzinfo=dt.timezone.utc
        )
        candidates.append((entry, timestamp))
    candidates.sort(key=lambda item: item[1], reverse=True)
    if not candidates:
        return

    keep: set[Path] = {candidates[0][0]}

    def keep_periods(limit: int, key_function) -> None:
        seen: set[object] = set()
        for path, timestamp in candidates:
            key = key_function(timestamp)
            if key in seen:
                continue
            if len(seen) >= limit:
                break
            seen.add(key)
            keep.add(path)

    keep_periods(daily, lambda value: value.date())
    keep_periods(weekly, lambda value: (value.isocalendar().year, value.isocalendar().week))
    keep_periods(monthly, lambda value: (value.year, value.month))

    for path, _ in sorted(candidates, key=lambda item: item[1]):
        if path not in keep:
            print(path.name)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    record_counts = subparsers.add_parser("record-counts")
    record_counts.add_argument("input")
    record_counts.add_argument("output")
    record_counts.set_defaults(function=command_record_counts)

    totals = subparsers.add_parser("business-totals")
    totals.add_argument("input")
    totals.add_argument("output")
    totals.set_defaults(function=command_business_totals)

    attachments = subparsers.add_parser("attachments")
    attachments.add_argument("files")
    attachments.add_argument("references")
    attachments.add_argument("output")
    attachments.set_defaults(function=command_attachments)

    runtime = subparsers.add_parser("runtime-config")
    runtime.add_argument("output")
    runtime.add_argument("values", nargs="+")
    runtime.set_defaults(function=command_runtime_config)

    schema = subparsers.add_parser("schema")
    schema.add_argument("output")
    schema.add_argument("database_version")
    schema.add_argument("schema_version")
    schema.add_argument("tables")
    schema.set_defaults(function=command_schema)

    manifest = subparsers.add_parser("manifest")
    for name in (
        "output",
        "backup_id",
        "site_id",
        "purpose",
        "started_at",
        "completed_at",
        "time_zone",
        "web_image",
        "backend_image",
        "mysql_image",
        "database_version",
        "schema_version",
        "database_dump",
        "record_counts",
        "business_totals",
        "attachments",
        "runtime_config",
        "schema",
        "copperctl_version",
        "age_version",
        "zstd_version",
    ):
        manifest.add_argument(name)
    manifest.add_argument("--binlog-file", default="")
    manifest.add_argument("--binlog-position", default="")
    manifest.set_defaults(function=command_manifest)

    envelope = subparsers.add_parser("envelope")
    envelope.add_argument("output")
    envelope.add_argument("backup_id")
    envelope.add_argument("site_id")
    envelope.add_argument("created_at")
    envelope.add_argument("payload")
    envelope.add_argument("recipient_fingerprint")
    envelope.add_argument("offline_proof_sha256", nargs="?", default="")
    envelope.set_defaults(function=command_envelope)

    last_success = subparsers.add_parser("last-success")
    last_success.add_argument("output")
    last_success.add_argument("backup_id")
    last_success.add_argument("completed_at")
    last_success.add_argument("archive")
    last_success.add_argument("recipient_fingerprint")
    last_success.add_argument("offline_proof_sha256", nargs="?", default="")
    last_success.set_defaults(function=command_last_success)

    verify_envelope = subparsers.add_parser("verify-envelope")
    verify_envelope.add_argument("directory")
    verify_envelope.set_defaults(function=command_verify_envelope)

    retention = subparsers.add_parser("retention-plan")
    retention.add_argument("directory")
    retention.add_argument("site_id")
    retention.add_argument("daily")
    retention.add_argument("weekly")
    retention.add_argument("monthly")
    retention.set_defaults(function=command_retention_plan)

    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.function(args)


if __name__ == "__main__":
    main()
