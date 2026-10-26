#!/usr/bin/env python3
"""Safely inspect, extract and compare Copper restore metadata."""

from __future__ import annotations

import argparse
import codecs
import datetime as dt
import hashlib
import json
import os
import re
import runpy
import shutil
import tarfile
from pathlib import Path, PurePosixPath


FORMAT = "copper-backup/v1"
INCREMENTAL_FORMAT = "copper-backup/v2"
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{7,47}$")
BACKUP_ID_RE = re.compile(r"^[0-9a-f]{32}$")
TABLE_RE = re.compile(r"^[A-Za-z0-9_]+$")
METRIC_RE = re.compile(r"^[a-z0-9_.:-]+$")
DECIMAL_RE = re.compile(r"^-?\d+(?:\.\d+)?$")
BINLOG_RE = re.compile(r"^binlog\.[0-9]{6,}$")
OUTER_FILES = {"README.txt", "checksums.sha256", "envelope.json", "payload.tar.zst.age"}
PAYLOAD_REQUIRED = {
    "manifest.json",
    "database/copper.sql.zst",
    "config/runtime-allowlist.json",
    "verification/schema.json",
    "verification/record-counts.json",
    "verification/business-totals.json",
    "verification/attachments.json",
    "checksums.sha256",
}
PAYLOAD_OPTIONAL = {"verification/offline-proof.txt"}
PAYLOAD_COMMON_REQUIRED = PAYLOAD_REQUIRED - {"database/copper.sql.zst"}
VERIFY_FILES = (
    "schema.json",
    "record-counts.json",
    "business-totals.json",
    "attachments.json",
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
CORE_FOREIGN_KEY_SCHEMA_VERSION = 67
CORE_FOREIGN_KEYS = {
    ("fk_purchase_order_item_order", "purchase_order_item", "purchase_order_id", "purchase_order", "id", 1),
    ("fk_purchase_order_item_product", "purchase_order_item", "product_id", "product", "id", 1),
    ("fk_sales_order_item_order", "sales_order_item", "sales_order_id", "sales_order", "id", 1),
    ("fk_sales_order_item_product", "sales_order_item", "product_id", "product", "id", 1),
    ("fk_inbound_item_order", "inbound_item", "inbound_id", "inbound_order", "id", 1),
    ("fk_inbound_item_product", "inbound_item", "product_id", "product", "id", 1),
    ("fk_outbound_item_order", "outbound_item", "outbound_id", "outbound_order", "id", 1),
    ("fk_outbound_item_product", "outbound_item", "product_id", "product", "id", 1),
    ("fk_inventory_batch_product", "inventory_batch", "product_id", "product", "id", 1),
    ("fk_inventory_batch_warehouse", "inventory_batch", "warehouse_id", "warehouse", "id", 1),
    ("fk_stock_balance_product", "stock_balance", "product_id", "product", "id", 1),
    ("fk_stock_balance_warehouse", "stock_balance", "warehouse_id", "warehouse", "id", 1),
    ("fk_inventory_movement_product", "inventory_movement", "product_id", "product", "id", 1),
    ("fk_inventory_movement_warehouse", "inventory_movement", "warehouse_id", "warehouse", "id", 1),
    ("fk_inventory_movement_batch", "inventory_movement", "batch_id", "inventory_batch", "id", 1),
    ("fk_inventory_transfer_item_order", "inventory_transfer_item", "transfer_id", "inventory_transfer_order", "id", 1),
    ("fk_inventory_transfer_item_batch", "inventory_transfer_item", "source_batch_id", "inventory_batch", "id", 1),
    ("fk_inventory_stocktake_item_order", "inventory_stocktake_item", "stocktake_id", "inventory_stocktake_order", "id", 1),
    ("fk_inventory_stocktake_item_batch", "inventory_stocktake_item", "batch_id", "inventory_batch", "id", 1),
    ("fk_purchase_return_item_order", "purchase_return_item", "return_id", "purchase_return_order", "id", 1),
    ("fk_purchase_return_item_inbound", "purchase_return_item", "inbound_item_id", "inbound_item", "id", 1),
    ("fk_purchase_return_item_batch", "purchase_return_item", "batch_id", "inventory_batch", "id", 1),
    ("fk_sales_return_item_order", "sales_return_item", "sales_return_id", "sales_return_order", "id", 1),
    ("fk_sales_return_item_outbound", "sales_return_item", "outbound_item_id", "outbound_item", "id", 1),
    ("fk_sales_return_item_batch", "sales_return_item", "inventory_batch_id", "inventory_batch", "id", 1),
    ("fk_payment_allocation_payment", "payment_allocation", "payment_id", "payment", "id", 1),
    ("fk_supplier_allocation_payment", "supplier_payment_allocation", "supplier_payment_id", "supplier_payment", "id", 1),
    ("fk_accounting_voucher_line_voucher", "accounting_voucher_line", "voucher_id", "accounting_voucher", "id", 1),
    ("fk_accounting_voucher_line_account", "accounting_voucher_line", "account_id", "accounting_account", "id", 1),
    ("fk_accounting_ledger_voucher", "accounting_ledger_entry", "voucher_id", "accounting_voucher", "id", 1),
    ("fk_accounting_ledger_voucher_line", "accounting_ledger_entry", "voucher_line_id", "accounting_voucher_line", "id", 1),
    ("fk_accounting_ledger_account", "accounting_ledger_entry", "account_id", "accounting_account", "id", 1),
    ("fk_tax_invoice_line_invoice", "tax_invoice_line", "invoice_id", "tax_invoice", "id", 1),
    ("fk_tax_invoice_line_rate", "tax_invoice_line", "tax_rate_rule_id", "tax_rate_rule", "id", 1),
    ("fk_bank_reconciliation_match_session", "bank_reconciliation_match", "session_id", "bank_reconciliation_session", "id", 1),
    ("fk_bank_reconciliation_match_statement", "bank_reconciliation_match", "statement_line_id", "bank_statement_line", "id", 1),
    ("fk_inventory_non_trade_product", "inventory_non_trade_document", "product_id", "product", "id", 1),
    ("fk_inventory_non_trade_warehouse", "inventory_non_trade_document", "warehouse_id", "warehouse", "id", 1),
    ("fk_inventory_non_trade_batch", "inventory_non_trade_document", "batch_id", "inventory_batch", "id", 1),
    ("fk_purchase_quote_order", "purchase_quote_option", "purchase_order_id", "purchase_order", "id", 1),
    ("fk_purchase_quote_supplier", "purchase_quote_option", "supplier_id", "partner", "id", 1),
    ("fk_purchase_quote_item_option", "purchase_quote_option_item", "quote_option_id", "purchase_quote_option", "id", 1),
    ("fk_purchase_quote_item_product", "purchase_quote_option_item", "product_id", "product", "id", 1),
    ("fk_sales_cost_treasury_document", "sales_cost_allocation", "treasury_document_id", "treasury_document", "id", 1),
    ("fk_outbound_delivery_outbound", "outbound_delivery", "outbound_id", "outbound_order", "id", 1),
    ("fk_outbound_delivery_event_delivery", "outbound_delivery_event", "delivery_id", "outbound_delivery", "id", 1),
    ("fk_external_share_partner", "external_share_link", "partner_id", "partner", "id", 1),
    ("fk_direct_trade_customer", "direct_trade_order", "customer_id", "partner", "id", 1),
    ("fk_direct_trade_supplier", "direct_trade_order", "supplier_id", "partner", "id", 1),
    ("fk_direct_trade_address", "direct_trade_order", "address_id", "partner_address", "id", 1),
    ("fk_direct_trade_sales_order", "direct_trade_order", "sales_order_id", "sales_order", "id", 1),
    ("fk_direct_trade_purchase_order", "direct_trade_order", "purchase_order_id", "purchase_order", "id", 1),
    ("fk_direct_trade_item_order", "direct_trade_item", "direct_trade_id", "direct_trade_order", "id", 1),
    ("fk_direct_trade_item_product", "direct_trade_item", "product_id", "product", "id", 1),
    ("fk_direct_trade_item_sales", "direct_trade_item", "sales_order_item_id", "sales_order_item", "id", 1),
    ("fk_direct_trade_item_purchase", "direct_trade_item", "purchase_order_item_id", "purchase_order_item", "id", 1),
    ("fk_direct_trade_delivery_order", "direct_trade_delivery", "direct_trade_id", "direct_trade_order", "id", 1),
    ("fk_direct_trade_delivery_item_delivery", "direct_trade_delivery_item", "delivery_id", "direct_trade_delivery", "id", 1),
    ("fk_direct_trade_delivery_item_trade", "direct_trade_delivery_item", "direct_trade_item_id", "direct_trade_item", "id", 1),
    ("fk_direct_trade_delivery_item_product", "direct_trade_delivery_item", "product_id", "product", "id", 1),
    ("fk_direct_trade_return_order", "direct_trade_return", "direct_trade_id", "direct_trade_order", "id", 1),
    ("fk_direct_trade_return_item_return", "direct_trade_return_item", "return_id", "direct_trade_return", "id", 1),
    ("fk_direct_trade_return_item_trade", "direct_trade_return_item", "direct_trade_item_id", "direct_trade_item", "id", 1),
    ("fk_direct_trade_return_item_product", "direct_trade_return_item", "product_id", "product", "id", 1),
    ("fk_direct_trade_event_order", "direct_trade_event", "direct_trade_id", "direct_trade_order", "id", 1),
    ("fk_purchase_followup_order", "purchase_followup_event", "purchase_order_id", "purchase_order", "id", 1),
    ("fk_purchase_followup_item_event", "purchase_followup_event_item", "event_id", "purchase_followup_event", "id", 1),
    ("fk_purchase_followup_item_order_item", "purchase_followup_event_item", "purchase_order_item_id", "purchase_order_item", "id", 1),
    ("fk_external_confirmation_partner", "external_confirmation_packet", "partner_id", "partner", "id", 1),
    ("fk_external_confirmation_record_packet", "external_confirmation_record", "packet_id", "external_confirmation_packet", "id", 1),
    ("fk_document_intake_candidate_batch", "document_intake_candidate", "batch_id", "document_intake_batch", "id", 1),
    ("fk_inventory_package_usage_instance", "inventory_package_instance_usage", "package_instance_id", "inventory_package_instance", "id", 1),
    ("fk_inventory_package_usage_batch", "inventory_package_instance_usage", "batch_id", "inventory_batch", "id", 1),
    ("fk_inventory_package_usage_product", "inventory_package_instance_usage", "product_id", "product", "id", 1),
    ("fk_inventory_non_trade_item_document", "inventory_non_trade_item", "document_id", "inventory_non_trade_document", "id", 1),
    ("fk_inventory_non_trade_item_product", "inventory_non_trade_item", "product_id", "product", "id", 1),
    ("fk_inventory_non_trade_item_warehouse", "inventory_non_trade_item", "warehouse_id", "warehouse", "id", 1),
    ("fk_inventory_non_trade_item_batch", "inventory_non_trade_item", "batch_id", "inventory_batch", "id", 1),
}


def fail(message: str) -> "None":
    raise SystemExit(f"错误：{message}")


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> object:
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"无法读取 JSON {path}: {exc}")


def write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def normalized_relative(value: str, label: str) -> str:
    while value.startswith("./"):
        value = value[2:]
    if value == ".":
        return ""
    if not value or "\\" in value or any(char in value for char in ("\x00", "\n", "\r", "\t")):
        fail(f"{label} 路径为空或包含控制字符")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        fail(f"{label} 路径不安全：{value}")
    normalized = str(path)
    if normalized != value:
        fail(f"{label} 路径未规范化：{value}")
    return normalized


def parse_checksums(path: Path, expected_names: set[str], label: str) -> dict[str, str]:
    result: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="ascii").splitlines()
    except (OSError, UnicodeError) as exc:
        fail(f"无法读取 {label} 校验清单：{exc}")
    for number, line in enumerate(lines, 1):
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not match:
            fail(f"{label} 校验清单第 {number} 行格式不正确")
        name = normalized_relative(match.group(2), label)
        if name in result:
            fail(f"{label} 校验清单包含重复文件：{name}")
        result[name] = match.group(1)
    if set(result) != expected_names:
        missing = sorted(expected_names - set(result))
        extra = sorted(set(result) - expected_names)
        fail(f"{label} 校验清单文件集合不一致，缺少={missing}，多出={extra}")
    return result


def ensure_empty_directory(path: Path) -> None:
    if not path.is_dir() or path.is_symlink():
        fail(f"目标不是普通空目录：{path}")
    if any(path.iterdir()):
        fail(f"目标目录不为空：{path}")


def copy_tar_member(archive: tarfile.TarFile, member: tarfile.TarInfo, target: Path) -> None:
    source = archive.extractfile(member)
    if source is None:
        fail(f"无法读取归档成员：{member.name}")
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with target.open("xb") as output:
        shutil.copyfileobj(source, output, length=1024 * 1024)
        output.flush()
        os.fsync(output.fileno())
    os.chmod(target, 0o600)


def command_extract_outer(args: argparse.Namespace) -> None:
    archive_path = Path(args.archive)
    sidecar_path = Path(args.sidecar)
    output = Path(args.output)
    if not archive_path.is_file() or archive_path.is_symlink():
        fail("备份主包不存在、是符号链接或不是普通文件")
    if not sidecar_path.is_file() or sidecar_path.is_symlink():
        fail("备份外层 SHA-256 文件不存在、是符号链接或不是普通文件")
    sidecar = parse_checksums(sidecar_path, {archive_path.name}, "备份外层")
    if sidecar[archive_path.name] != sha256_file(archive_path):
        fail("备份主包 SHA-256 与外层校验文件不一致")
    ensure_empty_directory(output)
    try:
        archive = tarfile.open(archive_path, mode="r:")
    except (tarfile.TarError, OSError) as exc:
        fail(f"无法打开备份外层归档：{exc}")
    with archive:
        members = archive.getmembers()
        names: set[str] = set()
        for member in members:
            name = normalized_relative(member.name, "备份外层")
            if not member.isfile() or name not in OUTER_FILES or name in names:
                fail(f"备份外层包含未声明、重复或非普通文件成员：{member.name}")
            names.add(name)
            copy_tar_member(archive, member, output / name)
        if names != OUTER_FILES:
            fail(f"备份外层文件集合不完整：{sorted(names)}")
    outer_checks = parse_checksums(
        output / "checksums.sha256",
        {"README.txt", "envelope.json", "payload.tar.zst.age"},
        "备份 envelope",
    )
    for name, expected in outer_checks.items():
        if sha256_file(output / name) != expected:
            fail(f"备份外层成员 SHA-256 不一致：{name}")
    envelope = load_json(output / "envelope.json")
    if not isinstance(envelope, dict) or envelope.get("formatVersion") not in {FORMAT, INCREMENTAL_FORMAT}:
        fail("envelope.json 格式版本不受支持")
    backup_id = envelope.get("backupId")
    if not isinstance(backup_id, str) or not BACKUP_ID_RE.fullmatch(backup_id):
        fail("envelope.json backupId 不正确")
    encryption = envelope.get("encryption")
    payload = envelope.get("payload")
    offline = envelope.get("offlineVerification")
    site_fingerprint = envelope.get("siteFingerprint")
    created_at = envelope.get("createdAt")
    if not isinstance(site_fingerprint, str) or not re.fullmatch(r"[0-9a-f]{16}", site_fingerprint):
        fail("envelope.json siteFingerprint 不正确")
    validate_timestamp(created_at, "envelope.createdAt")
    if not isinstance(encryption, dict) or encryption.get("format") != "age" or encryption.get("recipientType") != "X25519":
        fail("envelope.json 加密声明不受支持")
    payload_path = output / "payload.tar.zst.age"
    if not isinstance(payload, dict) or payload.get("path") != payload_path.name:
        fail("envelope.json 密文路径不正确")
    if payload.get("size") != payload_path.stat().st_size or payload.get("sha256") != sha256_file(payload_path):
        fail("envelope.json 密文大小或 SHA-256 不一致")
    base_backup_id = ""
    parent_backup_id = ""
    chain_sequence = 0
    if envelope.get("formatVersion") == INCREMENTAL_FORMAT:
        if envelope.get("backupType") != "incremental":
            fail("copper-backup/v2 envelope 类型不受支持")
        base_backup_id = envelope.get("baseBackupId")
        parent_backup_id = envelope.get("parentBackupId")
        chain_sequence = envelope.get("chainSequence")
        if not isinstance(base_backup_id, str) or not BACKUP_ID_RE.fullmatch(base_backup_id):
            fail("envelope.json baseBackupId 不正确")
        if not isinstance(parent_backup_id, str) or not BACKUP_ID_RE.fullmatch(parent_backup_id):
            fail("envelope.json parentBackupId 不正确")
        if not isinstance(chain_sequence, int) or isinstance(chain_sequence, bool) or chain_sequence < 1:
            fail("envelope.json chainSequence 不正确")
    offline_proof_sha256 = ""
    if offline is not None:
        if (
            not isinstance(offline, dict)
            or set(offline) != {"format", "proofSha256"}
            or offline.get("format") != "copper-offline-key-proof/v1"
            or not isinstance(offline.get("proofSha256"), str)
            or not SHA_RE.fullmatch(offline["proofSha256"])
        ):
            fail("envelope.json 离线持钥证明声明不正确")
        offline_proof_sha256 = offline["proofSha256"]
    print(json.dumps({
        "formatVersion": envelope.get("formatVersion"),
        "backupId": backup_id,
        "baseBackupId": base_backup_id,
        "parentBackupId": parent_backup_id,
        "chainSequence": chain_sequence,
        "createdAt": created_at,
        "offlineProofSha256": offline_proof_sha256,
        "payloadBytes": payload_path.stat().st_size,
        "siteFingerprint": site_fingerprint,
    }, sort_keys=True))


def payload_member_allowed(name: str, is_directory: bool) -> bool:
    if is_directory:
        return name in {"", "database", "database/binlog", "attachments", "config", "verification"} or name.startswith("attachments/")
    return (
        name in PAYLOAD_REQUIRED
        or name in PAYLOAD_OPTIONAL
        or name == "verification/attachment-delta.json"
        or name.startswith("attachments/")
        or name.startswith("database/binlog/")
    )


def command_extract_payload(args: argparse.Namespace) -> None:
    archive_path = Path(args.archive)
    output = Path(args.output)
    if not archive_path.is_file() or archive_path.is_symlink():
        fail("解密后的 payload tar 不存在或不是普通文件")
    ensure_empty_directory(output)
    try:
        archive = tarfile.open(archive_path, mode="r:")
    except (tarfile.TarError, OSError) as exc:
        fail(f"无法打开 payload tar：{exc}")
    regular_names: set[str] = set()
    seen: set[str] = set()
    with archive:
        for member in archive.getmembers():
            name = normalized_relative(member.name, "payload")
            if name in seen:
                fail(f"payload 包含重复路径：{name}")
            seen.add(name)
            if not (member.isfile() or member.isdir()) or not payload_member_allowed(name, member.isdir()):
                fail(f"payload 包含未声明或非普通成员：{member.name}")
            if member.isdir():
                target = output if not name else output / name
                target.mkdir(parents=True, exist_ok=True, mode=0o700)
                os.chmod(target, 0o700)
            else:
                regular_names.add(name)
                copy_tar_member(archive, member, output / name)
    if not PAYLOAD_COMMON_REQUIRED.issubset(regular_names):
        fail(f"payload 缺少公共必需文件：{sorted(PAYLOAD_COMMON_REQUIRED - regular_names)}")


def ensure_dict(value: object, label: str) -> dict:
    if not isinstance(value, dict):
        fail(f"{label} 必须是 JSON 对象")
    return value


def ensure_list(value: object, label: str) -> list:
    if not isinstance(value, list):
        fail(f"{label} 必须是 JSON 数组")
    return value


def validate_timestamp(value: object, label: str) -> dt.datetime:
    if not isinstance(value, str):
        fail(f"{label} 不是时间字符串")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        fail(f"{label} 不是合法 ISO 8601 时间")
    if parsed.tzinfo is None:
        fail(f"{label} 必须包含时区")
    return parsed


def command_verify_payload(args: argparse.Namespace) -> None:
    root = Path(args.directory)
    if not root.is_dir() or root.is_symlink():
        fail("payload 目录不存在或不安全")
    actual_files: set[str] = set()
    for path in root.rglob("*"):
        if path.is_symlink() or (not path.is_dir() and not path.is_file()):
            fail(f"payload 解包目录包含符号链接或非常规文件：{path}")
        if path.is_file():
            actual_files.add(path.relative_to(root).as_posix())
    if not PAYLOAD_REQUIRED.issubset(actual_files):
        fail(f"payload 解包后缺少必需文件：{sorted(PAYLOAD_REQUIRED - actual_files)}")
    checksums = parse_checksums(root / "checksums.sha256", actual_files - {"checksums.sha256"}, "payload")
    for name, expected in checksums.items():
        if sha256_file(root / name) != expected:
            fail(f"payload 文件 SHA-256 不一致：{name}")

    manifest = ensure_dict(load_json(root / "manifest.json"), "manifest.json")
    schema = ensure_dict(load_json(root / "verification/schema.json"), "schema.json")
    records = ensure_dict(load_json(root / "verification/record-counts.json"), "record-counts.json")
    totals = ensure_dict(load_json(root / "verification/business-totals.json"), "business-totals.json")
    attachments = ensure_dict(load_json(root / "verification/attachments.json"), "attachments.json")
    runtime = ensure_dict(load_json(root / "config/runtime-allowlist.json"), "runtime-allowlist.json")

    if manifest.get("formatVersion") != FORMAT:
        fail("manifest.json 格式版本不受支持")
    backup_id = manifest.get("backupId")
    if not isinstance(backup_id, str) or not BACKUP_ID_RE.fullmatch(backup_id):
        fail("manifest.json backupId 不正确")
    if args.expected_backup_id and backup_id != args.expected_backup_id:
        fail("外层 envelope 与内层 manifest 的 backupId 不一致")
    source_site_id = manifest.get("siteId")
    if not isinstance(source_site_id, str) or not ID_RE.fullmatch(source_site_id):
        fail("manifest.json siteId 不正确")
    source_site_fingerprint = hashlib.sha256(source_site_id.encode("utf-8")).hexdigest()[:16]
    if args.expected_site_fingerprint:
        calculated_fingerprint = source_site_fingerprint
        if args.expected_site_fingerprint != calculated_fingerprint:
            fail("外层 envelope 与内层 siteId 指纹不一致")
    if manifest.get("purpose") not in {"manual", "scheduled"}:
        fail("manifest.json purpose 不受支持")
    started_at = validate_timestamp(manifest.get("startedAt"), "startedAt")
    completed_at = validate_timestamp(manifest.get("completedAt"), "completedAt")
    if completed_at < started_at:
        fail("manifest 完成时间早于开始时间")
    if args.expected_created_at and manifest.get("completedAt") != args.expected_created_at:
        fail("外层 envelope 创建时间与内层 manifest 完成时间不一致")
    consistency = ensure_dict(manifest.get("consistency"), "consistency")
    if consistency.get("mode") != "graceful-write-quiesce" or consistency.get("activeTransactionsAtSnapshot") != 0 or consistency.get("maintenanceExit") != "healthy":
        fail("备份未声明成功的一致性停写边界")
    components = ensure_dict(manifest.get("components"), "components")
    expected_components = {
        "webImage": args.web_image,
        "backendImage": args.backend_image,
        "mysqlImage": args.mysql_image,
    }
    if components != expected_components:
        fail("备份组件镜像与当前同版本恢复环境不一致")
    expected_verification = {
        "schema": "verification/schema.json",
        "recordCounts": "verification/record-counts.json",
        "businessTotals": "verification/business-totals.json",
        "attachments": "verification/attachments.json",
    }
    proof_path = root / "verification/offline-proof.txt"
    if proof_path.is_file():
        try:
            proof_raw = proof_path.read_text(encoding="ascii")
        except (OSError, UnicodeError) as exc:
            fail(f"无法读取离线持钥证明：{exc}")
        if not re.fullmatch(r"[0-9a-f]{64}\n", proof_raw):
            fail("离线持钥证明内容或换行格式不正确")
        proof = proof_raw[:-1]
        expected_verification["offlineKeyProof"] = "verification/offline-proof.txt"
        if not args.expected_offline_proof_sha256:
            fail("payload 含离线持钥证明但外层 envelope 未声明证明摘要")
        if hashlib.sha256(proof.encode("ascii")).hexdigest() != args.expected_offline_proof_sha256:
            fail("外层 envelope 与 payload 离线持钥证明摘要不一致")
    elif args.expected_offline_proof_sha256:
        fail("外层 envelope 声明了离线持钥证明但 payload 缺少证明文件")
    if manifest.get("verification") != expected_verification:
        fail("manifest 验证文件路径声明不正确")
    if manifest.get("schema") != schema or manifest.get("runtimeConfig") != runtime:
        fail("manifest 内嵌结构或运行配置与独立清单不一致")

    database = ensure_dict(manifest.get("database"), "database")
    dump = ensure_dict(database.get("dump"), "database.dump")
    dump_path = root / "database/copper.sql.zst"
    if database.get("name") != "copper_v2" or dump.get("path") != "database/copper.sql.zst":
        fail("备份数据库名称或 dump 路径不受支持")
    boundary = database.get("binlogBoundary")
    if boundary is not None:
        boundary = ensure_dict(boundary, "database.binlogBoundary")
        if (
            set(boundary) != {"file", "position"}
            or not isinstance(boundary.get("file"), str)
            or not BINLOG_RE.fullmatch(str(boundary.get("file")))
            or not isinstance(boundary.get("position"), int)
            or isinstance(boundary.get("position"), bool)
            or int(boundary.get("position")) < 4
        ):
            fail("全量备份 binlog 截点不正确")
    if dump.get("size") != dump_path.stat().st_size or dump.get("sha256") != sha256_file(dump_path):
        fail("数据库 dump 大小或 SHA-256 与 manifest 不一致")

    schema_tables = ensure_list(schema.get("tables"), "schema.tables")
    if schema.get("databaseProduct") != "MySQL" or schema.get("databaseVersion") != database.get("version") or schema.get("schemaVersion") != database.get("schemaVersion"):
        fail("结构清单与数据库版本信息不一致")
    if schema.get("tableCount") != len(schema_tables) or schema_tables != sorted(schema_tables) or any(not isinstance(name, str) or not TABLE_RE.fullmatch(name) for name in schema_tables):
        fail("结构清单的表数量、顺序或表名不正确")
    record_tables = ensure_list(records.get("tables"), "record-counts.tables")
    record_names: list[str] = []
    row_sum = 0
    for item in record_tables:
        row = ensure_dict(item, "record-counts.tables item")
        name, count = row.get("name"), row.get("rows")
        if not isinstance(name, str) or not TABLE_RE.fullmatch(name) or not isinstance(count, int) or isinstance(count, bool) or count < 0:
            fail("记录数清单包含非法表名或数量")
        record_names.append(name)
        row_sum += count
    if record_names != schema_tables or records.get("tableCount") != len(record_names) or records.get("totalRows") != row_sum:
        fail("记录数清单与结构/汇总不一致")
    if database.get("tableCount") != len(record_names) or database.get("totalRows") != row_sum:
        fail("manifest 数据库记录汇总不一致")

    metrics = ensure_list(totals.get("metrics"), "business-totals.metrics")
    metric_names: set[str] = set()
    for item in metrics:
        metric = ensure_dict(item, "business-totals metric")
        name, value = metric.get("metric"), metric.get("value")
        if not isinstance(name, str) or not METRIC_RE.fullmatch(name) or name in metric_names or not isinstance(value, str) or not DECIMAL_RE.fullmatch(value):
            fail("关键业务余额包含非法、重复或非十进制定点指标")
        metric_names.add(name)
    if manifest.get("businessMetricCount") != len(metrics):
        fail("manifest 关键业务余额指标数量不一致")

    listed_files = ensure_list(attachments.get("files"), "attachments.files")
    listed: dict[str, tuple[int, str]] = {}
    referenced: set[tuple[str, int]] = set()
    reference_count = 0
    orphan_count = 0
    total_bytes = 0
    for item in listed_files:
        entry = ensure_dict(item, "attachments file")
        relative = normalized_relative(str(entry.get("path", "")), "附件")
        size, digest = entry.get("size"), entry.get("sha256")
        refs = ensure_list(entry.get("referencedBy"), "attachments.referencedBy")
        if relative in listed or not isinstance(size, int) or isinstance(size, bool) or size < 0 or not isinstance(digest, str) or not SHA_RE.fullmatch(digest):
            fail("附件清单包含重复路径或非法大小/摘要")
        listed[relative] = (size, digest)
        total_bytes += size
        if not refs:
            orphan_count += 1
        for reference in refs:
            reference_entry = ensure_dict(reference, "attachments.referencedBy item")
            source = reference_entry.get("source")
            record_id = reference_entry.get("id")
            if (
                not isinstance(source, str)
                or not TABLE_RE.fullmatch(source)
                or not isinstance(record_id, int)
                or isinstance(record_id, bool)
                or record_id < 0
                or (source, record_id) in referenced
            ):
                fail("附件引用包含非法来源、记录 ID 或重复引用")
            referenced.add((source, record_id))
        reference_count += len(refs)
    attachment_root = root / "attachments"
    actual_attachments: dict[str, tuple[int, str]] = {}
    for path in attachment_root.rglob("*"):
        if path.is_file():
            relative = path.relative_to(attachment_root).as_posix()
            actual_attachments[relative] = (path.stat().st_size, sha256_file(path))
    if actual_attachments != listed:
        fail("附件原文件集合、大小或 SHA-256 与清单不一致")
    if (
        attachments.get("fileCount") != len(listed)
        or attachments.get("totalBytes") != total_bytes
        or attachments.get("databaseReferenceCount") != reference_count
        or attachments.get("orphanFileCount") != orphan_count
    ):
        fail("附件汇总数量或字节数不一致")
    if manifest.get("attachments") != {
        "fileCount": len(listed),
        "totalBytes": total_bytes,
        "databaseReferenceCount": reference_count,
        "orphanFileCount": orphan_count,
    }:
        fail("manifest 附件汇总与附件清单不一致")

    values = ensure_dict(runtime.get("values"), "runtime.values")
    if (
        runtime.get("containsSecrets") is not False
        or not RUNTIME_REQUIRED_KEYS <= set(values) <= RUNTIME_ALLOWED_KEYS
        or any(not isinstance(value, str) for value in values.values())
        or values.get("siteId") != source_site_id
        or values.get("databaseName") != "copper_v2"
        or values.get("webImage") != args.web_image
        or values.get("backendImage") != args.backend_image
        or values.get("mysqlImage") != args.mysql_image
    ):
        fail("运行配置允许清单含秘密或站点标识不一致")
    try:
        runpy.run_path(str(Path(__file__).with_name("ocr-config.py")))["validate_runtime"](values)
    except ValueError as error:
        fail(str(error))
    output = {
        "formatVersion": FORMAT,
        "backupId": backup_id,
        "siteId": args.target_site_id,
        "sourceSiteId": source_site_id,
        "sourceSiteFingerprint": source_site_fingerprint,
        "databaseVersion": database.get("version"),
        "schemaVersion": database.get("schemaVersion"),
        "tableCount": len(record_names),
        "totalRows": row_sum,
        "businessMetricCount": len(metrics),
        "attachmentFileCount": len(listed),
        "attachmentBytes": total_bytes,
        "attachmentReferenceCount": reference_count,
        "verifiedAt": utc_now(),
    }
    write_json(Path(args.output), output)


def command_validate_sql(args: argparse.Namespace) -> None:
    path = Path(args.path)
    if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
        fail("解压后的数据库 SQL 不存在、为空或不安全")
    dangerous = (
        r"(?:^|\n)\s*DROP\s+DATABASE\b",
        r"(?:^|\n)\s*CREATE\s+USER\b",
        r"(?:^|\n)\s*ALTER\s+USER\b",
        r"(?:^|\n)\s*DROP\s+USER\b",
        r"(?:^|\n)\s*GRANT\b",
        r"(?:^|\n)\s*REVOKE\b",
        r"(?:^|\n)\s*INSTALL\s+PLUGIN\b",
        r"(?:^|\n)\s*UNINSTALL\s+PLUGIN\b",
        r"(?:^|\n)\s*SET\s+GLOBAL\b",
        r"(?:^|\n)\s*LOAD\s+DATA\b",
        r"(?:^|\n)\s*SELECT\b[^\n]{0,4096}\bINTO\s+(?:OUTFILE|DUMPFILE)\b",
        r"(?:^|\n)\s*SOURCE\s+",
        r"(?:^|\n)\s*\\!",
    )
    decoder = codecs.getincrementaldecoder("utf-8")("strict")
    overlap = ""
    first_window = True
    uses: list[str] = []
    create_database_statements: list[str] = []
    try:
        with path.open("rb") as handle:
            for raw in iter(lambda: handle.read(1024 * 1024), b""):
                text = decoder.decode(raw).upper()
                if "\x00" in text:
                    fail("数据库 SQL 包含 NUL 字节")
                window = overlap + text
                if first_window:
                    scan_window = window
                    first_window = False
                else:
                    newline = window.find("\n")
                    scan_window = window[newline:] if newline >= 0 else ""
                for pattern in dangerous:
                    if re.search(pattern, scan_window):
                        fail(f"数据库 SQL 包含不允许的服务器级语句：{pattern}")
                if re.search(r"(?:^|\n)\s*(?:INSERT|REPLACE|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE)\b[^\n]{0,4096}`?(?:MYSQL|SYS|PERFORMANCE_SCHEMA|INFORMATION_SCHEMA)`?\s*\.", scan_window):
                    fail("数据库 SQL 引用了系统数据库")
                uses.extend(re.findall(r"(?:^|\n)\s*USE\s+`?([A-Z0-9_]+)`?\s*;", scan_window))
                create_database_statements.extend(re.findall(r"(?:^|\n)\s*CREATE\s+DATABASE\b[^;]{0,4096};", scan_window))
                overlap = window[-8192:]
            final = decoder.decode(b"", final=True).upper()
    except (OSError, UnicodeError) as exc:
        fail(f"数据库 SQL 不是合法 UTF-8 文本：{exc}")
    window = overlap + final
    newline = window.find("\n")
    scan_window = window if first_window else (window[newline:] if newline >= 0 else "")
    if "\x00" in window:
        fail("数据库 SQL 包含 NUL 字节")
    for pattern in dangerous:
        if re.search(pattern, scan_window):
            fail(f"数据库 SQL 包含不允许的服务器级语句：{pattern}")
    uses.extend(re.findall(r"(?:^|\n)\s*USE\s+`?([A-Z0-9_]+)`?\s*;", scan_window))
    create_database_statements.extend(re.findall(r"(?:^|\n)\s*CREATE\s+DATABASE\b[^;]{0,4096};", scan_window))
    if not uses or set(uses) != {"COPPER_V2"}:
        fail("数据库 SQL 必须且只能 USE copper_v2")
    for statement in create_database_statements:
        if "`COPPER_V2`" not in statement and not re.search(r"\bCOPPER_V2\b", statement):
            fail("数据库 SQL 试图创建 copper_v2 之外的数据库")
    if re.search(r"(?:^|\n)\s*(?:INSERT|REPLACE|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE)\b[^\n]{0,4096}`?(?:MYSQL|SYS|PERFORMANCE_SCHEMA|INFORMATION_SCHEMA)`?\s*\.", scan_window):
        fail("数据库 SQL 引用了系统数据库")
    print(f"sql_bytes={path.stat().st_size}")


def command_bounded_copy(args: argparse.Namespace) -> None:
    output = Path(args.output)
    try:
        limit = int(args.limit)
    except ValueError:
        fail("写入上限不是整数")
    if limit <= 0 or output.exists():
        fail("写入上限必须为正数且目标不能已存在")
    temporary = output.with_name(f".{output.name}.tmp-{os.getpid()}")
    written = 0
    try:
        with temporary.open("xb") as handle:
            while True:
                chunk = os.read(0, min(1024 * 1024, limit - written + 1))
                if not chunk:
                    break
                written += len(chunk)
                if written > limit:
                    fail("解压结果超过恢复空间安全上限")
                handle.write(chunk)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, output)
    finally:
        if temporary.exists():
            temporary.unlink()


def read_foreign_key_rows(source: Path) -> list[tuple[str, str, str, str, str, int, str | None, str | None]]:
    rows: list[tuple[str, str, str, str, str, int, str | None, str | None]] = []
    with source.open("r", encoding="utf-8") as handle:
        for number, raw in enumerate(handle, 1):
            line = raw.rstrip("\n\r")
            if not line:
                continue
            values = line.split("\t")
            if len(values) not in {6, 8}:
                fail(f"外键清单第 {number} 行不是 6 或 8 列")
            constraint, table, column, referenced_table, referenced_column, ordinal_text = values[:6]
            if any(not TABLE_RE.fullmatch(value) for value in values[:5]):
                fail("外键清单包含不安全标识符")
            try:
                ordinal = int(ordinal_text)
            except ValueError:
                fail("外键列序号不是整数")
            delete_rule = values[6] if len(values) == 8 else None
            update_rule = values[7] if len(values) == 8 else None
            if delete_rule is not None and delete_rule not in {"CASCADE", "SET NULL", "RESTRICT", "NO ACTION"}:
                fail("外键删除规则不正确")
            if update_rule is not None and update_rule not in {"CASCADE", "SET NULL", "RESTRICT", "NO ACTION"}:
                fail("外键更新规则不正确")
            rows.append((
                constraint,
                table,
                column,
                referenced_table,
                referenced_column,
                ordinal,
                delete_rule,
                update_rule,
            ))
    return rows


def read_foreign_key_metadata(source: Path) -> dict[tuple[str, str, str], list[tuple[int, str, str]]]:
    groups: dict[tuple[str, str, str], list[tuple[int, str, str]]] = {}
    for constraint, table, column, referenced_table, referenced_column, ordinal, _, _ in read_foreign_key_rows(source):
        groups.setdefault((constraint, table, referenced_table), []).append((ordinal, column, referenced_column))
    return groups


def command_assert_core_foreign_keys(args: argparse.Namespace) -> None:
    if args.schema_version == "unversioned":
        print("core_foreign_keys=not_required schema=unversioned")
        return
    try:
        schema_version = int(args.schema_version.split(".", 1)[0])
    except ValueError:
        fail("Flyway schema 版本不是数字")
    if schema_version < CORE_FOREIGN_KEY_SCHEMA_VERSION:
        print(f"core_foreign_keys=not_required schema={args.schema_version}")
        return

    rows = read_foreign_key_rows(Path(args.input))
    actual = {
        (constraint, table, column, referenced_table, referenced_column, ordinal)
        for constraint, table, column, referenced_table, referenced_column, ordinal, _, _ in rows
    }
    missing = sorted(CORE_FOREIGN_KEYS - actual)
    if missing:
        fail("核心外键缺失或映射错误：" + ", ".join(row[0] for row in missing))
    engines: dict[str, str] = {}
    if args.table_engines:
        for raw in Path(args.table_engines).read_text(encoding="utf-8").splitlines():
            values = raw.split("\t")
            if len(values) != 2 or not TABLE_RE.fullmatch(values[0]) or values[0] in engines:
                fail("表引擎证据缺列、表名不安全或重复")
            engines[values[0]] = values[1]

    def immediately_restricts(rule: str | None, table: str, referenced_table: str) -> bool:
        if rule == "RESTRICT":
            return True
        # MySQL 8.4 InnoDB checks NO ACTION immediately, unlike NDB.
        # Require actual server/engine evidence instead of changing historical FK metadata.
        return (
            rule == "NO ACTION"
            and bool(re.fullmatch(r"8\.4\.\d+(?:[-+].*)?", args.database_version or ""))
            and engines.get(table) == "InnoDB"
            and engines.get(referenced_table) == "InnoDB"
        )

    invalid_rules = sorted(
        constraint
        for constraint, table, column, referenced_table, referenced_column, ordinal, delete_rule, update_rule in rows
        if (constraint, table, column, referenced_table, referenced_column, ordinal) in CORE_FOREIGN_KEYS
        and not (immediately_restricts(delete_rule, table, referenced_table)
                 and immediately_restricts(update_rule, table, referenced_table))
    )
    if invalid_rules:
        fail("核心外键不是 RESTRICT 或已证明等价的 MySQL 8.4 InnoDB NO ACTION：" + ", ".join(invalid_rules))
    print(f"core_foreign_keys={len(CORE_FOREIGN_KEYS)} schema={args.schema_version}")


def command_foreign_key_sql(args: argparse.Namespace) -> None:
    groups = read_foreign_key_metadata(Path(args.input))
    statements: list[str] = []
    for (constraint, table, referenced_table), columns in sorted(groups.items()):
        ordered = sorted(columns)
        joins = " AND ".join(f"p.`{ref}` <=> c.`{col}`" for _, col, ref in ordered)
        non_null = " AND ".join(f"c.`{col}` IS NOT NULL" for _, col, _ in ordered)
        statements.append(
            f"SELECT '{table}__{constraint}', COUNT(*) FROM `{table}` c "
            f"WHERE ({non_null}) AND NOT EXISTS (SELECT 1 FROM `{referenced_table}` p WHERE {joins});"
        )
    if not statements:
        statements.append("SELECT '__none__', 0;")
    Path(args.output).write_text("\n".join(statements) + "\n", encoding="utf-8")
    os.chmod(args.output, 0o600)


def read_fk(path: Path) -> dict[str, int]:
    result: dict[str, int] = {}
    with path.open("r", encoding="utf-8") as handle:
        for number, raw in enumerate(handle, 1):
            values = raw.rstrip("\n\r").split("\t")
            if len(values) != 2 or not TABLE_RE.fullmatch(values[0]):
                fail(f"外键验证结果第 {number} 行不正确")
            try:
                count = int(values[1])
            except ValueError:
                fail("外键违反数量不是整数")
            if count < 0 or values[0] in result:
                fail("外键验证结果重复或为负数")
            result[values[0]] = count
    return result


def command_compare_verification(args: argparse.Namespace) -> None:
    expected = Path(args.expected)
    actual = Path(args.actual)
    mismatches: list[str] = []
    for name in VERIFY_FILES:
        if load_json(expected / name) != load_json(actual / name):
            mismatches.append(name)
    foreign_keys = read_fk(Path(args.foreign_keys))
    violations = {name: count for name, count in foreign_keys.items() if count != 0}
    if violations:
        mismatches.append("foreign-keys")
    result = {
        "formatVersion": "copper-restore-verification/v1",
        "verifiedAt": utc_now(),
        "matchedFiles": [name for name in VERIFY_FILES if name not in mismatches],
        "foreignKeyConstraintCount": 0 if set(foreign_keys) == {"__none__"} else len(foreign_keys),
        "foreignKeyViolations": violations,
        "result": "passed" if not mismatches else "failed",
        "mismatches": mismatches,
    }
    write_json(Path(args.output), result)
    if mismatches:
        fail(f"恢复后验证不一致：{', '.join(mismatches)}")


def table_rows(document: object, label: str) -> dict[str, int]:
    data = ensure_dict(document, label)
    rows = data.get("tables")
    if not isinstance(rows, list):
        fail(f"{label} 缺少 tables")
    result: dict[str, int] = {}
    for entry in rows:
        if not isinstance(entry, dict) or set(entry) != {"name", "rows"}:
            fail(f"{label} 的表记录结构不正确")
        name = entry.get("name")
        count = entry.get("rows")
        if not isinstance(name, str) or not TABLE_RE.fullmatch(name) or name in result:
            fail(f"{label} 包含非法或重复表名")
        if not isinstance(count, int) or count < 0:
            fail(f"{label} 的表记录数不正确")
        result[name] = count
    return result


def read_flyway_history(path: Path) -> list[list[str]]:
    rows: list[list[str]] = []
    with path.open("r", encoding="utf-8") as handle:
        for number, raw in enumerate(handle, 1):
            values = raw.rstrip("\n\r").split("\t")
            if len(values) != 7:
                fail(f"Flyway 历史第 {number} 行不是 7 列")
            rank, version, description, migration_type, script, checksum, success = values
            if not rank.isdigit() or not version or not script or success not in {"0", "1"}:
                fail(f"Flyway 历史第 {number} 行字段不正确")
            if any("\x00" in value or "\n" in value or "\r" in value for value in values):
                fail("Flyway 历史包含控制字符")
            rows.append([rank, version, description, migration_type, script, checksum, success])
    return rows


def command_compare_upgrade_verification(args: argparse.Namespace) -> None:
    source = Path(args.source)
    target = Path(args.target)
    source_schema = ensure_dict(load_json(source / "schema.json"), "升级前 schema")
    target_schema = ensure_dict(load_json(target / "schema.json"), "升级后 schema")
    mismatches: list[str] = []
    if str(source_schema.get("schemaVersion")) != args.source_schema:
        mismatches.append("source-schema")
    if str(target_schema.get("schemaVersion")) != args.target_schema:
        mismatches.append("target-schema")
    if not str(target_schema.get("databaseVersion", "")).startswith("8.4."):
        mismatches.append("mysql-8.4")

    source_counts = table_rows(load_json(source / "record-counts.json"), "升级前记录数")
    target_counts = table_rows(load_json(target / "record-counts.json"), "升级后记录数")
    changed_source_tables = {
        name: {"before": count, "after": target_counts.get(name)}
        for name, count in source_counts.items()
        if target_counts.get(name) != count
    }
    missing_source_tables = sorted(set(source_counts) - set(target_counts))
    if missing_source_tables:
        mismatches.append("missing-source-tables")
    if load_json(source / "business-totals.json") != load_json(target / "business-totals.json"):
        mismatches.append("business-totals")
    if load_json(source / "attachments.json") != load_json(target / "attachments.json"):
        mismatches.append("attachments")

    foreign_keys = read_fk(Path(args.foreign_keys))
    violations = {name: count for name, count in foreign_keys.items() if count != 0}
    if violations:
        mismatches.append("foreign-keys")

    source_history = read_flyway_history(source / "flyway-history.tsv")
    target_history = read_flyway_history(target / "flyway-history.tsv")
    failed_migrations = [row for row in target_history if row[-1] != "1"]
    if failed_migrations:
        mismatches.append("failed-flyway-migration")
    if target_history[:len(source_history)] != source_history:
        mismatches.append("flyway-history-prefix")
    if len(target_history) < len(source_history):
        mismatches.append("flyway-history-truncated")

    result = {
        "formatVersion": "copper-upgrade-verification/v1",
        "verifiedAt": utc_now(),
        "sourceSchema": args.source_schema,
        "targetSchema": args.target_schema,
        "preservedSourceTableCount": len(source_counts),
        "newTargetTables": sorted(set(target_counts) - set(source_counts)),
        "changedSourceTables": changed_source_tables,
        "missingSourceTables": missing_source_tables,
        "flywayRowsBefore": len(source_history),
        "flywayRowsAfter": len(target_history),
        "foreignKeyViolations": violations,
        "result": "passed" if not mismatches else "failed",
        "mismatches": mismatches,
    }
    write_json(Path(args.output), result)
    if mismatches:
        fail(f"升级后验证不一致：{', '.join(mismatches)}")


STATE_KEYS = {
    "restoreId",
    "status",
    "phase",
    "backupId",
    "siteId",
    "sourceSiteId",
    "sourceSiteFingerprint",
    "archiveSha256",
    "proxyPort",
    "composeProject",
    "createdAt",
    "validatedAt",
    "switchedAt",
    "rolledBackAt",
    "lastError",
    "upgradeMode",
    "sourceVersion",
    "sourceSchema",
    "targetVersion",
    "targetSchema",
    "preparedSha256",
    "incrementalCount",
}


def command_write_state(args: argparse.Namespace) -> None:
    path = Path(args.output)
    data: dict[str, object]
    if path.exists():
        data = ensure_dict(load_json(path), "restore-state")
    else:
        data = {"formatVersion": "copper-restore-state/v1", "createdAt": utc_now()}
    for item in args.values:
        if "=" not in item:
            fail(f"恢复状态字段缺少等号：{item}")
        key, value = item.split("=", 1)
        if key not in STATE_KEYS:
            fail(f"恢复状态字段不受支持：{key}")
        if key == "restoreId" and not ID_RE.fullmatch(value):
            fail("restoreId 格式不正确")
        if key == "sourceSiteId" and not ID_RE.fullmatch(value):
            fail("sourceSiteId 格式不正确")
        if key == "sourceSiteFingerprint" and not re.fullmatch(r"[0-9a-f]{16}", value):
            fail("sourceSiteFingerprint 格式不正确")
        if key in {"proxyPort", "incrementalCount"}:
            try:
                data[key] = int(value)
            except ValueError:
                fail(f"{key} 不是整数")
        else:
            data[key] = value
    data["updatedAt"] = utc_now()
    write_json(path, data)


def command_event(args: argparse.Namespace) -> None:
    if not ID_RE.fullmatch(args.restore_id):
        fail("restoreId 格式不正确")
    if args.result not in {"started", "passed", "failed"}:
        fail("恢复事件结果不受支持")
    message = args.message.replace("\n", " ").replace("\r", " ")[:500]
    entry = {
        "formatVersion": "copper-restore-event/v1",
        "at": utc_now(),
        "restoreId": args.restore_id,
        "action": args.action,
        "result": args.result,
        "message": message,
    }
    path = Path(args.log)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.stat().st_size > 5 * 1024 * 1024:
        rotated = path.with_name(path.name + ".1")
        rotated.unlink(missing_ok=True)
        os.replace(path, rotated)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(descriptor, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry, ensure_ascii=False, sort_keys=True) + "\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(path, 0o600)


def command_show_state(args: argparse.Namespace) -> None:
    data = ensure_dict(load_json(Path(args.path)), "restore-state")
    print(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    outer = subparsers.add_parser("extract-outer")
    outer.add_argument("archive")
    outer.add_argument("sidecar")
    outer.add_argument("output")
    outer.set_defaults(function=command_extract_outer)

    payload = subparsers.add_parser("extract-payload")
    payload.add_argument("archive")
    payload.add_argument("output")
    payload.set_defaults(function=command_extract_payload)

    verify = subparsers.add_parser("verify-payload")
    verify.add_argument("directory")
    verify.add_argument("target_site_id")
    verify.add_argument("web_image")
    verify.add_argument("backend_image")
    verify.add_argument("mysql_image")
    verify.add_argument("output")
    verify.add_argument("--expected-backup-id")
    verify.add_argument("--expected-site-fingerprint")
    verify.add_argument("--expected-created-at")
    verify.add_argument("--expected-offline-proof-sha256")
    verify.set_defaults(function=command_verify_payload)

    sql = subparsers.add_parser("validate-sql")
    sql.add_argument("path")
    sql.set_defaults(function=command_validate_sql)

    bounded = subparsers.add_parser("bounded-copy")
    bounded.add_argument("output")
    bounded.add_argument("limit")
    bounded.set_defaults(function=command_bounded_copy)

    foreign = subparsers.add_parser("foreign-key-sql")
    foreign.add_argument("input")
    foreign.add_argument("output")
    foreign.set_defaults(function=command_foreign_key_sql)

    core_foreign = subparsers.add_parser("assert-core-foreign-keys")
    core_foreign.add_argument("schema_version")
    core_foreign.add_argument("input")
    core_foreign.add_argument("--table-engines", help="实际 information_schema.TABLES 的表名/引擎两列 TSV")
    core_foreign.add_argument("--database-version", help="实际 SELECT VERSION()，NO ACTION 等价判断仅支持 MySQL 8.4 InnoDB")
    core_foreign.set_defaults(function=command_assert_core_foreign_keys)

    compare = subparsers.add_parser("compare-verification")
    compare.add_argument("expected")
    compare.add_argument("actual")
    compare.add_argument("foreign_keys")
    compare.add_argument("output")
    compare.set_defaults(function=command_compare_verification)

    compare_upgrade = subparsers.add_parser("compare-upgrade-verification")
    compare_upgrade.add_argument("source")
    compare_upgrade.add_argument("target")
    compare_upgrade.add_argument("foreign_keys")
    compare_upgrade.add_argument("output")
    compare_upgrade.add_argument("--source-schema", required=True)
    compare_upgrade.add_argument("--target-schema", required=True)
    compare_upgrade.set_defaults(function=command_compare_upgrade_verification)

    state = subparsers.add_parser("write-state")
    state.add_argument("output")
    state.add_argument("values", nargs="+")
    state.set_defaults(function=command_write_state)

    event = subparsers.add_parser("event")
    event.add_argument("log")
    event.add_argument("restore_id")
    event.add_argument("action")
    event.add_argument("result")
    event.add_argument("message")
    event.set_defaults(function=command_event)

    show = subparsers.add_parser("show-state")
    show.add_argument("path")
    show.set_defaults(function=command_show_state)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.function(args)


if __name__ == "__main__":
    main()
