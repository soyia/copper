#!/usr/bin/env python3
"""Build, validate, retain and apply Copper incremental backup chains."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import runpy
import shutil
import tarfile
from pathlib import Path, PurePosixPath


FORMAT = "copper-backup/v2"
STATE_FORMAT = "copper-backup-chain-state/v1"
DELTA_FORMAT = "copper-attachment-delta/v1"
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
BACKUP_ID_RE = re.compile(r"^[0-9a-f]{32}$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
SITE_RE = re.compile(r"^[a-z0-9][a-z0-9-]{7,47}$")
BINLOG_RE = re.compile(r"^binlog\.[0-9]{6,}$")
TABLE_RE = re.compile(r"^[A-Za-z0-9_]+$")
METRIC_RE = re.compile(r"^[a-z0-9_.:-]+$")
DECIMAL_RE = re.compile(r"^-?\d+(?:\.\d+)?$")
ARCHIVE_RE_TEMPLATE = (
    r"^{site}_(?P<timestamp>\d{{8}}T\d{{6}}Z)_"
    r"(?P<backup_id>[0-9a-f]{{32}})_(?P<purpose>manual|scheduled)\.copperbak$"
)


def fail(message: str) -> "None":
    raise SystemExit(f"错误：{message}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path, label: str | None = None) -> object:
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"无法读取 {label or path}：{exc}")


def write_json(path: Path, document: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(document, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def ensure_dict(value: object, label: str) -> dict:
    if not isinstance(value, dict):
        fail(f"{label} 必须是 JSON 对象")
    return value


def ensure_list(value: object, label: str) -> list:
    if not isinstance(value, list):
        fail(f"{label} 必须是 JSON 数组")
    return value


def safe_relative(value: object, label: str = "附件") -> str:
    if not isinstance(value, str) or not value or any(char in value for char in ("\x00", "\n", "\r", "\t", "\\")):
        fail(f"{label}路径为空或包含控制字符")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts) or str(path) != value:
        fail(f"{label}路径不安全：{value}")
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


def validate_backup_id(value: object, label: str) -> str:
    if not isinstance(value, str) or not BACKUP_ID_RE.fullmatch(value):
        fail(f"{label} 格式不正确")
    return value


def validate_boundary(value: object, label: str) -> dict[str, object]:
    boundary = ensure_dict(value, label)
    if set(boundary) != {"file", "position"}:
        fail(f"{label} 字段不完整")
    file_name = boundary.get("file")
    position = boundary.get("position")
    if not isinstance(file_name, str) or not BINLOG_RE.fullmatch(file_name):
        fail(f"{label}.file 不是受支持的 MySQL binlog 文件名")
    if not isinstance(position, int) or isinstance(position, bool) or position < 4:
        fail(f"{label}.position 不是有效位置")
    return {"file": file_name, "position": position}


def validate_attachment_snapshot(value: object) -> dict[str, object]:
    snapshot = ensure_dict(value, "attachments.json")
    files = ensure_list(snapshot.get("files"), "attachments.files")
    paths: set[str] = set()
    references: set[tuple[str, int]] = set()
    total_bytes = 0
    reference_count = 0
    orphan_count = 0
    normalized: list[dict[str, object]] = []
    for raw in files:
        item = ensure_dict(raw, "attachments.files 项")
        path = safe_relative(item.get("path"))
        size = item.get("size")
        digest = item.get("sha256")
        refs = ensure_list(item.get("referencedBy"), "attachments.referencedBy")
        if path in paths or not isinstance(size, int) or isinstance(size, bool) or size < 0:
            fail("附件清单包含重复路径或非法大小")
        if not isinstance(digest, str) or not SHA_RE.fullmatch(digest):
            fail(f"附件 {path} 的 SHA-256 不正确")
        paths.add(path)
        total_bytes += size
        normalized_refs: list[dict[str, object]] = []
        if not refs:
            orphan_count += 1
        for raw_ref in refs:
            ref = ensure_dict(raw_ref, "附件引用")
            source = ref.get("source")
            record_id = ref.get("id")
            if (
                not isinstance(source, str)
                or not TABLE_RE.fullmatch(source)
                or not isinstance(record_id, int)
                or isinstance(record_id, bool)
                or record_id < 0
                or (source, record_id) in references
            ):
                fail("附件引用包含非法来源、ID 或重复引用")
            references.add((source, record_id))
            normalized_refs.append({"source": source, "id": record_id})
        reference_count += len(normalized_refs)
        normalized.append({"path": path, "size": size, "sha256": digest, "referencedBy": normalized_refs})
    normalized.sort(key=lambda item: str(item["path"]))
    if files != normalized:
        fail("附件清单必须按路径排序，引用字段必须规范化")
    expected = {
        "fileCount": len(normalized),
        "totalBytes": total_bytes,
        "databaseReferenceCount": reference_count,
        "orphanFileCount": orphan_count,
    }
    for key, expected_value in expected.items():
        if snapshot.get(key) != expected_value:
            fail(f"attachments.json 的 {key} 汇总不一致")
    return snapshot


def snapshot_map(snapshot: dict[str, object]) -> dict[str, dict[str, object]]:
    return {str(item["path"]): item for item in ensure_list(snapshot["files"], "attachments.files")}


def require_regular_tree_file(root: Path, relative: str) -> Path:
    if not root.is_dir() or root.is_symlink():
        fail(f"附件根目录不存在或不安全：{root}")
    current = root
    parts = PurePosixPath(relative).parts
    for part in parts[:-1]:
        current = current / part
        if not current.is_dir() or current.is_symlink():
            fail(f"附件路径经过符号链接或不存在：{relative}")
    path = current / parts[-1]
    if not path.is_file() or path.is_symlink():
        fail(f"附件不是普通文件或不存在：{relative}")
    return path


def command_attachment_delta(args: argparse.Namespace) -> None:
    state = ensure_dict(load_json(Path(args.state), "备份链状态"), "备份链状态")
    if state.get("formatVersion") != STATE_FORMAT:
        fail("备份链状态格式不受支持")
    previous = validate_attachment_snapshot(state.get("attachmentSnapshot"))
    current = validate_attachment_snapshot(load_json(Path(args.current), "当前附件快照"))
    previous_map = snapshot_map(previous)
    current_map = snapshot_map(current)
    changed = [
        path for path, item in current_map.items()
        if path not in previous_map
        or previous_map[path].get("size") != item.get("size")
        or previous_map[path].get("sha256") != item.get("sha256")
    ]
    deleted = sorted(set(previous_map) - set(current_map))
    source_root = Path(args.source)
    target_root = Path(args.target)
    if target_root.exists() and (not target_root.is_dir() or target_root.is_symlink()):
        fail("增量附件目标目录不安全")
    target_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    copied: list[dict[str, object]] = []
    for relative in sorted(changed):
        entry = current_map[relative]
        source = require_regular_tree_file(source_root, relative)
        if source.stat().st_size != entry["size"] or sha256_file(source) != entry["sha256"]:
            fail(f"附件在快照后发生变化：{relative}")
        destination = target_root.joinpath(*PurePosixPath(relative).parts)
        destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with source.open("rb") as source_handle, destination.open("xb") as target_handle:
            shutil.copyfileobj(source_handle, target_handle, length=1024 * 1024)
            target_handle.flush()
            os.fsync(target_handle.fileno())
        os.chmod(destination, 0o600)
        if destination.stat().st_size != entry["size"] or sha256_file(destination) != entry["sha256"]:
            fail(f"增量附件复制后校验失败：{relative}")
        copied.append({"path": relative, "size": entry["size"], "sha256": entry["sha256"]})
    write_json(
        Path(args.output),
        {
            "formatVersion": DELTA_FORMAT,
            "addedOrChanged": copied,
            "deleted": deleted,
            "finalFileCount": current["fileCount"],
            "finalTotalBytes": current["totalBytes"],
            "finalSnapshotSha256": sha256_file(Path(args.current)),
        },
    )


def parse_binlog_tsv(path: Path) -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    names: set[str] = set()
    with path.open("r", encoding="utf-8", newline="") as handle:
        for number, raw in enumerate(handle, 1):
            columns = raw.rstrip("\n").rstrip("\r").split("\t")
            if len(columns) != 3:
                fail(f"binlog 清单第 {number} 行不是三列")
            name, size_text, digest = columns
            if not BINLOG_RE.fullmatch(name) or name in names:
                fail(f"binlog 清单包含非法或重复文件名：{name}")
            try:
                size = int(size_text)
            except ValueError:
                fail(f"binlog {name} 大小不是整数")
            if size <= 0 or not SHA_RE.fullmatch(digest):
                fail(f"binlog {name} 大小或 SHA-256 不正确")
            names.add(name)
            result.append({"path": f"database/binlog/{name}", "name": name, "size": size, "sha256": digest})
    if not result:
        fail("增量备份至少必须包含一个已关闭 binlog 文件")
    if [str(item["name"]) for item in result] != sorted(str(item["name"]) for item in result):
        fail("binlog 清单顺序不正确")
    return result


def command_manifest(args: argparse.Namespace) -> None:
    backup_id = validate_backup_id(args.backup_id, "backupId")
    base_id = validate_backup_id(args.base_backup_id, "baseBackupId")
    parent_id = validate_backup_id(args.parent_backup_id, "parentBackupId")
    if backup_id in {base_id, parent_id}:
        fail("增量 backupId 不能与 base/parent 相同")
    try:
        sequence = int(args.sequence)
    except ValueError:
        fail("chainSequence 不是整数")
    if sequence < 1:
        fail("增量 chainSequence 必须大于 0")
    started = validate_timestamp(args.started_at, "startedAt")
    completed = validate_timestamp(args.completed_at, "completedAt")
    if completed < started:
        fail("备份完成时间早于开始时间")
    before = validate_boundary({"file": args.from_file, "position": int(args.from_position)}, "database.binlog.from")
    after = validate_boundary({"file": args.to_file, "position": int(args.to_position)}, "database.binlog.to")
    if str(after["file"]) <= str(before["file"]):
        fail("增量结束 binlog 必须晚于起点文件")
    binlogs = parse_binlog_tsv(Path(args.binlogs))
    if binlogs[0]["name"] != before["file"]:
        fail("第一个增量 binlog 必须等于起点文件")
    if str(binlogs[-1]["name"]) >= str(after["file"]):
        fail("增量包不得包含仍在写入的结束边界 binlog")
    attachments = validate_attachment_snapshot(load_json(Path(args.attachments), "附件快照"))
    delta = ensure_dict(load_json(Path(args.attachment_delta), "附件差异"), "附件差异")
    if delta.get("formatVersion") != DELTA_FORMAT or delta.get("finalSnapshotSha256") != sha256_file(Path(args.attachments)):
        fail("附件差异未绑定最终附件快照")
    schema = ensure_dict(load_json(Path(args.schema), "结构快照"), "结构快照")
    records = ensure_dict(load_json(Path(args.record_counts), "记录数快照"), "记录数快照")
    totals = ensure_dict(load_json(Path(args.business_totals), "业务余额快照"), "业务余额快照")
    runtime = ensure_dict(load_json(Path(args.runtime_config), "运行配置快照"), "运行配置快照")
    write_json(
        Path(args.output),
        {
            "formatVersion": FORMAT,
            "backupType": "incremental",
            "backupId": backup_id,
            "baseBackupId": base_id,
            "parentBackupId": parent_id,
            "chainSequence": sequence,
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
            "database": {
                "name": "copper_v2",
                "version": args.database_version,
                "schemaVersion": args.schema_version,
                "mode": "mysql-row-binlog",
                "binlog": {
                    "format": "ROW",
                    "rowImage": "FULL",
                    "from": before,
                    "to": after,
                    "files": binlogs,
                },
                "tableCount": records.get("tableCount"),
                "totalRows": records.get("totalRows"),
            },
            "attachments": {
                "mode": "content-addressed-delta",
                "fileCount": attachments.get("fileCount"),
                "totalBytes": attachments.get("totalBytes"),
                "databaseReferenceCount": attachments.get("databaseReferenceCount"),
                "orphanFileCount": attachments.get("orphanFileCount"),
                "addedOrChangedCount": len(ensure_list(delta.get("addedOrChanged"), "attachment delta addedOrChanged")),
                "deletedCount": len(ensure_list(delta.get("deleted"), "attachment delta deleted")),
                "delta": "verification/attachment-delta.json",
            },
            "verification": {
                "schema": "verification/schema.json",
                "recordCounts": "verification/record-counts.json",
                "businessTotals": "verification/business-totals.json",
                "attachments": "verification/attachments.json",
                "attachmentDelta": "verification/attachment-delta.json",
                "offlineKeyProof": "verification/offline-proof.txt",
            },
            "runtimeConfig": runtime,
            "schema": schema,
            "businessMetricCount": len(ensure_list(totals.get("metrics"), "business totals metrics")),
            "tools": {
                "copperctl": args.copperctl_version,
                "age": args.age_version,
                "zstd": args.zstd_version,
            },
        },
    )


def command_envelope(args: argparse.Namespace) -> None:
    payload = Path(args.payload)
    backup_id = validate_backup_id(args.backup_id, "backupId")
    base_id = validate_backup_id(args.base_backup_id, "baseBackupId")
    parent_id = validate_backup_id(args.parent_backup_id, "parentBackupId")
    try:
        sequence = int(args.sequence)
    except ValueError:
        fail("chainSequence 不是整数")
    document: dict[str, object] = {
        "formatVersion": FORMAT,
        "backupType": "incremental",
        "backupId": backup_id,
        "baseBackupId": base_id,
        "parentBackupId": parent_id,
        "chainSequence": sequence,
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
    if not SHA_RE.fullmatch(args.offline_proof_sha256):
        fail("离线持钥证明 SHA-256 不正确")
    document["offlineVerification"] = {
        "format": "copper-offline-key-proof/v1",
        "proofSha256": args.offline_proof_sha256,
    }
    write_json(Path(args.output), document)


def command_last_success(args: argparse.Namespace) -> None:
    archive = Path(args.archive)
    document = {
        "formatVersion": FORMAT,
        "backupType": "incremental",
        "backupId": validate_backup_id(args.backup_id, "backupId"),
        "baseBackupId": validate_backup_id(args.base_backup_id, "baseBackupId"),
        "parentBackupId": validate_backup_id(args.parent_backup_id, "parentBackupId"),
        "chainSequence": int(args.sequence),
        "completedAt": args.completed_at,
        "archive": archive.name,
        "size": archive.stat().st_size,
        "sha256": sha256_file(archive),
        "recipientFingerprint": args.recipient_fingerprint,
        "offlineVerificationProofSha256": args.offline_proof_sha256,
    }
    write_json(Path(args.output), document)


def command_write_state(args: argparse.Namespace) -> None:
    snapshot = validate_attachment_snapshot(load_json(Path(args.attachments), "附件快照"))
    backup_id = validate_backup_id(args.backup_id, "backupId")
    base_id = validate_backup_id(args.base_backup_id, "baseBackupId")
    parent_id: str | None = None
    if args.parent_backup_id:
        parent_id = validate_backup_id(args.parent_backup_id, "parentBackupId")
    sequence = int(args.sequence)
    if sequence < 0 or (sequence == 0 and backup_id != base_id) or (sequence > 0 and parent_id is None):
        fail("备份链序号、基础备份与父备份关系不正确")
    boundary = validate_boundary({"file": args.binlog_file, "position": int(args.binlog_position)}, "binlogBoundary")
    archive = Path(args.archive)
    if not archive.is_file() or archive.is_symlink() or not SHA_RE.fullmatch(args.archive_sha256):
        fail("备份链状态引用的归档不存在或摘要不正确")
    if sha256_file(archive) != args.archive_sha256:
        fail("备份链状态引用的归档 SHA-256 不一致")
    write_json(
        Path(args.output),
        {
            "formatVersion": STATE_FORMAT,
            "siteId": args.site_id,
            "backupId": backup_id,
            "baseBackupId": base_id,
            "parentBackupId": parent_id,
            "chainSequence": sequence,
            "completedAt": args.completed_at,
            "binlogBoundary": boundary,
            "archive": archive.name,
            "archiveSha256": args.archive_sha256,
            "attachmentSnapshot": snapshot,
        },
    )


def load_state(path: Path, site_id: str) -> dict:
    state = ensure_dict(load_json(path, "备份链状态"), "备份链状态")
    if state.get("formatVersion") != STATE_FORMAT or state.get("siteId") != site_id:
        fail("备份链状态格式或站点标识不正确")
    validate_backup_id(state.get("backupId"), "state.backupId")
    validate_backup_id(state.get("baseBackupId"), "state.baseBackupId")
    parent = state.get("parentBackupId")
    if parent is not None:
        validate_backup_id(parent, "state.parentBackupId")
    sequence = state.get("chainSequence")
    if not isinstance(sequence, int) or isinstance(sequence, bool) or sequence < 0:
        fail("备份链状态序号不正确")
    validate_timestamp(state.get("completedAt"), "state.completedAt")
    validate_boundary(state.get("binlogBoundary"), "state.binlogBoundary")
    validate_attachment_snapshot(state.get("attachmentSnapshot"))
    archive = state.get("archive")
    digest = state.get("archiveSha256")
    if not isinstance(archive, str) or "/" in archive or not isinstance(digest, str) or not SHA_RE.fullmatch(digest):
        fail("备份链状态归档引用不正确")
    return state


def command_state_info(args: argparse.Namespace) -> None:
    state = load_state(Path(args.state), args.site_id)
    boundary = ensure_dict(state["binlogBoundary"], "state.binlogBoundary")
    values = (
        state["baseBackupId"], state["backupId"], state["chainSequence"],
        boundary["file"], boundary["position"], state["archive"],
        state["archiveSha256"], state["completedAt"],
    )
    print("\t".join(str(value) for value in values))


def parse_checksums(path: Path, expected: set[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="ascii").splitlines()
    except (OSError, UnicodeError) as exc:
        fail(f"无法读取 payload 校验清单：{exc}")
    for number, line in enumerate(lines, 1):
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not match:
            fail(f"payload 校验清单第 {number} 行格式错误")
        # The full/incremental writer uses `find . | sha256sum`, so payload
        # checksum entries carry POSIX leading ./ prefixes. Normalize only
        # this checksum boundary; attachment/manifest paths remain strict.
        name = match.group(2)
        while name.startswith("./"):
            name = name[2:]
        name = safe_relative(name, "payload ")
        if name in result:
            fail(f"payload 校验清单包含重复路径：{name}")
        result[name] = match.group(1)
    if set(result) != expected:
        fail(f"payload 校验清单集合不一致，缺少={sorted(expected - set(result))}，多出={sorted(set(result) - expected)}")
    return result


def validate_common_verification(root: Path, manifest: dict) -> tuple[dict, dict, dict, dict, dict]:
    schema = ensure_dict(load_json(root / "verification/schema.json"), "schema.json")
    records = ensure_dict(load_json(root / "verification/record-counts.json"), "record-counts.json")
    totals = ensure_dict(load_json(root / "verification/business-totals.json"), "business-totals.json")
    attachments = validate_attachment_snapshot(load_json(root / "verification/attachments.json"))
    runtime = ensure_dict(load_json(root / "config/runtime-allowlist.json"), "runtime config")
    tables = ensure_list(schema.get("tables"), "schema.tables")
    record_tables = ensure_list(records.get("tables"), "record-counts.tables")
    if not all(isinstance(name, str) and TABLE_RE.fullmatch(name) for name in tables) or tables != sorted(tables):
        fail("schema 表清单不正确")
    if schema.get("tableCount") != len(tables):
        fail("schema 表数量不一致")
    total_rows = 0
    names: list[str] = []
    for raw in record_tables:
        item = ensure_dict(raw, "record-counts 项")
        name, rows = item.get("name"), item.get("rows")
        if not isinstance(name, str) or not TABLE_RE.fullmatch(name) or not isinstance(rows, int) or isinstance(rows, bool) or rows < 0:
            fail("record-counts 项不正确")
        names.append(name)
        total_rows += rows
    if names != tables or records.get("tableCount") != len(names) or records.get("totalRows") != total_rows:
        fail("schema 与 record-counts 不一致")
    metrics = ensure_list(totals.get("metrics"), "business-totals.metrics")
    metric_names: set[str] = set()
    for raw in metrics:
        item = ensure_dict(raw, "business total")
        name, value = item.get("metric"), item.get("value")
        if not isinstance(name, str) or not METRIC_RE.fullmatch(name) or name in metric_names or not isinstance(value, str) or not DECIMAL_RE.fullmatch(value):
            fail("业务余额包含非法、重复或非定点值")
        metric_names.add(name)
    if manifest.get("schema") != schema or manifest.get("runtimeConfig") != runtime or manifest.get("businessMetricCount") != len(metrics):
        fail("manifest 内嵌验证快照不一致")
    return schema, records, totals, attachments, runtime


def command_verify_incremental(args: argparse.Namespace) -> None:
    root = Path(args.directory)
    if not root.is_dir() or root.is_symlink():
        fail("增量 payload 目录不存在或不安全")
    actual: set[str] = set()
    for path in root.rglob("*"):
        if path.is_symlink() or (not path.is_dir() and not path.is_file()):
            fail(f"增量 payload 包含符号链接或非常规文件：{path}")
        if path.is_file():
            actual.add(path.relative_to(root).as_posix())
    required = {
        "manifest.json", "config/runtime-allowlist.json", "verification/schema.json",
        "verification/record-counts.json", "verification/business-totals.json",
        "verification/attachments.json", "verification/attachment-delta.json",
        "verification/offline-proof.txt", "checksums.sha256",
    }
    if not required.issubset(actual):
        fail(f"增量 payload 缺少文件：{sorted(required - actual)}")
    allowed_prefixes = ("attachments/", "database/binlog/")
    for name in actual - required:
        if not name.startswith(allowed_prefixes):
            fail(f"增量 payload 包含未声明文件：{name}")
    checksums = parse_checksums(root / "checksums.sha256", actual - {"checksums.sha256"})
    for name, expected in checksums.items():
        if sha256_file(root / name) != expected:
            fail(f"增量 payload 文件 SHA-256 不一致：{name}")
    manifest = ensure_dict(load_json(root / "manifest.json"), "manifest")
    if manifest.get("formatVersion") != FORMAT or manifest.get("backupType") != "incremental":
        fail("增量 manifest 格式或类型不受支持")
    backup_id = validate_backup_id(manifest.get("backupId"), "manifest.backupId")
    base_id = validate_backup_id(manifest.get("baseBackupId"), "manifest.baseBackupId")
    parent_id = validate_backup_id(manifest.get("parentBackupId"), "manifest.parentBackupId")
    sequence = manifest.get("chainSequence")
    if not isinstance(sequence, int) or isinstance(sequence, bool) or sequence < 1:
        fail("增量 chainSequence 不正确")
    if args.expected_backup_id and backup_id != args.expected_backup_id:
        fail("外层 envelope 与增量 manifest 的 backupId 不一致")
    if args.expected_base_backup_id and base_id != args.expected_base_backup_id:
        fail("外层 envelope 与增量 manifest 的 baseBackupId 不一致")
    if args.expected_parent_backup_id and parent_id != args.expected_parent_backup_id:
        fail("外层 envelope 与增量 manifest 的 parentBackupId 不一致")
    if args.expected_sequence and sequence != int(args.expected_sequence):
        fail("外层 envelope 与增量 manifest 的 chainSequence 不一致")
    site_id = manifest.get("siteId")
    if not isinstance(site_id, str) or not SITE_RE.fullmatch(site_id):
        fail("增量 manifest siteId 不正确")
    fingerprint = hashlib.sha256(site_id.encode("utf-8")).hexdigest()[:16]
    if args.expected_site_fingerprint and args.expected_site_fingerprint != fingerprint:
        fail("外层 envelope 与增量 siteId 指纹不一致")
    if args.expected_created_at and manifest.get("completedAt") != args.expected_created_at:
        fail("外层 envelope 与增量完成时间不一致")
    started = validate_timestamp(manifest.get("startedAt"), "startedAt")
    completed = validate_timestamp(manifest.get("completedAt"), "completedAt")
    if completed < started or manifest.get("purpose") not in {"manual", "scheduled"}:
        fail("增量时间或 purpose 不正确")
    consistency = ensure_dict(manifest.get("consistency"), "consistency")
    if consistency.get("mode") != "graceful-write-quiesce" or consistency.get("activeTransactionsAtSnapshot") != 0 or consistency.get("maintenanceExit") != "healthy":
        fail("增量未声明成功的一致性停写边界")
    components = ensure_dict(manifest.get("components"), "components")
    expected_components = {"webImage": args.web_image, "backendImage": args.backend_image, "mysqlImage": args.mysql_image}
    if components != expected_components:
        fail("增量组件镜像与当前恢复环境不一致")
    if manifest.get("verification") != {
        "schema": "verification/schema.json",
        "recordCounts": "verification/record-counts.json",
        "businessTotals": "verification/business-totals.json",
        "attachments": "verification/attachments.json",
        "attachmentDelta": "verification/attachment-delta.json",
        "offlineKeyProof": "verification/offline-proof.txt",
    }:
        fail("增量 manifest 验证文件路径声明不正确")
    schema, records, _totals, attachments, runtime = validate_common_verification(root, manifest)
    values = ensure_dict(runtime.get("values"), "runtime.values")
    try:
        runpy.run_path(str(Path(__file__).with_name("ocr-config.py")))["validate_runtime"](values)
    except ValueError as error:
        fail(str(error))
    if (
        runtime.get("containsSecrets") is not False
        or set(runtime) != {"containsSecrets", "values"}
        or not RUNTIME_REQUIRED_KEYS <= set(values) <= RUNTIME_ALLOWED_KEYS
        or any(not isinstance(value, str) for value in values.values())
        or values.get("siteId") != site_id
        or values.get("databaseName") != "copper_v2"
    ):
        fail("增量运行配置含秘密或站点/数据库不一致")
    if {key: values.get(key) for key in ("webImage", "backendImage", "mysqlImage")} != expected_components:
        fail("增量运行配置镜像不一致")
    database = ensure_dict(manifest.get("database"), "database")
    if database.get("name") != "copper_v2" or database.get("mode") != "mysql-row-binlog":
        fail("增量数据库模式不受支持")
    if schema.get("databaseVersion") != database.get("version") or schema.get("schemaVersion") != database.get("schemaVersion"):
        fail("增量数据库版本与结构快照不一致")
    if database.get("tableCount") != records.get("tableCount") or database.get("totalRows") != records.get("totalRows"):
        fail("增量数据库记录汇总不一致")
    binlog = ensure_dict(database.get("binlog"), "database.binlog")
    if binlog.get("format") != "ROW" or binlog.get("rowImage") != "FULL":
        fail("增量 binlog 必须是 ROW/FULL")
    before = validate_boundary(binlog.get("from"), "database.binlog.from")
    after = validate_boundary(binlog.get("to"), "database.binlog.to")
    files = ensure_list(binlog.get("files"), "database.binlog.files")
    names: list[str] = []
    for raw in files:
        item = ensure_dict(raw, "binlog file")
        name = item.get("name")
        path = item.get("path")
        if not isinstance(name, str) or not BINLOG_RE.fullmatch(name) or path != f"database/binlog/{name}" or name in names:
            fail("增量 binlog 文件声明不正确")
        actual_path = root / str(path)
        if not actual_path.is_file() or actual_path.is_symlink() or item.get("size") != actual_path.stat().st_size or item.get("sha256") != sha256_file(actual_path):
            fail(f"增量 binlog 文件大小或摘要不一致：{name}")
        names.append(name)
    # The writer snapshots closed logs in [from.file, to.file); the new
    # active to.file is excluded even when its header position is 157.
    # Compare suffixes numerically (999999 -> 1000000), checking the count
    # before iteration so an untrusted huge boundary cannot allocate a range.
    first = int(before["file"].split(".")[1])
    stop = int(after["file"].split(".")[1])
    if (
        not names
        or names[0] != before["file"]
        or stop <= first
        or len(names) != stop - first
        or any(int(name.split(".")[1]) != first + offset for offset, name in enumerate(names))
    ):
        fail("增量 binlog 文件范围不连续或边界不正确")
    if {"database/binlog/" + name for name in names} != {name for name in actual if name.startswith("database/binlog/")}:
        fail("增量 binlog 实际文件与声明集合不一致")
    delta = ensure_dict(load_json(root / "verification/attachment-delta.json"), "attachment delta")
    if delta.get("formatVersion") != DELTA_FORMAT or delta.get("finalSnapshotSha256") != sha256_file(root / "verification/attachments.json"):
        fail("增量附件差异未绑定最终快照")
    changed = ensure_list(delta.get("addedOrChanged"), "delta.addedOrChanged")
    deleted = ensure_list(delta.get("deleted"), "delta.deleted")
    changed_paths: set[str] = set()
    for raw in changed:
        item = ensure_dict(raw, "changed attachment")
        relative = safe_relative(item.get("path"))
        path = root / "attachments" / relative
        if relative in changed_paths or not path.is_file() or path.is_symlink() or item.get("size") != path.stat().st_size or item.get("sha256") != sha256_file(path):
            fail(f"增量附件文件大小、摘要或唯一性不正确：{relative}")
        changed_paths.add(relative)
    actual_changed = {
        path.relative_to(root / "attachments").as_posix()
        for path in (root / "attachments").rglob("*") if path.is_file()
    }
    if changed_paths != actual_changed:
        fail("增量附件目录与差异清单不一致")
    deleted_paths = [safe_relative(item) for item in deleted]
    if deleted_paths != sorted(set(deleted_paths)) or set(deleted_paths) & changed_paths:
        fail("附件删除清单重复、无序或与变更清单冲突")
    if delta.get("finalFileCount") != attachments.get("fileCount") or delta.get("finalTotalBytes") != attachments.get("totalBytes"):
        fail("附件差异最终汇总与附件快照不一致")
    attachment_summary = ensure_dict(manifest.get("attachments"), "manifest.attachments")
    expected_attachment_summary = {
        "mode": "content-addressed-delta",
        "fileCount": attachments.get("fileCount"),
        "totalBytes": attachments.get("totalBytes"),
        "databaseReferenceCount": attachments.get("databaseReferenceCount"),
        "orphanFileCount": attachments.get("orphanFileCount"),
        "addedOrChangedCount": len(changed),
        "deletedCount": len(deleted),
        "delta": "verification/attachment-delta.json",
    }
    if attachment_summary != expected_attachment_summary:
        fail("增量 manifest 附件汇总不一致")
    proof = (root / "verification/offline-proof.txt").read_text(encoding="ascii")
    if not re.fullmatch(r"[0-9a-f]{64}\n", proof):
        fail("增量离线持钥证明内容不正确")
    if args.expected_offline_proof_sha256 and hashlib.sha256(proof[:-1].encode("ascii")).hexdigest() != args.expected_offline_proof_sha256:
        fail("外层 envelope 与增量离线证明不一致")
    write_json(Path(args.output), {
        "formatVersion": FORMAT,
        "backupId": backup_id,
        "baseBackupId": base_id,
        "parentBackupId": parent_id,
        "chainSequence": sequence,
        "sourceSiteId": site_id,
        "sourceSiteFingerprint": fingerprint,
        "from": before,
        "to": after,
        "attachmentBytes": attachments["totalBytes"],
        "verifiedAt": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    })


def command_verify_chain(args: argparse.Namespace) -> None:
    base_manifest = ensure_dict(load_json(Path(args.base) / "manifest.json"), "基础备份 manifest")
    base_id = validate_backup_id(base_manifest.get("backupId"), "基础备份 backupId")
    if base_manifest.get("formatVersion") != "copper-backup/v1":
        fail("增量链基础包必须是经过验证的 copper-backup/v1 全量备份")
    base_database = ensure_dict(base_manifest.get("database"), "基础备份 database")
    base_boundary = validate_boundary(base_database.get("binlogBoundary"), "基础备份 binlogBoundary")
    previous_id = base_id
    previous_to: dict[str, object] = base_boundary
    previous_snapshot = validate_attachment_snapshot(load_json(Path(args.base) / "verification/attachments.json"))
    previous_completed = validate_timestamp(base_manifest.get("completedAt"), "基础备份 completedAt")
    for expected_sequence, directory_text in enumerate(args.incrementals, 1):
        directory = Path(directory_text)
        manifest = ensure_dict(load_json(directory / "manifest.json"), "增量 manifest")
        if manifest.get("formatVersion") != FORMAT or manifest.get("backupType") != "incremental":
            fail("备份链包含非增量 v2 包")
        if manifest.get("baseBackupId") != base_id or manifest.get("parentBackupId") != previous_id or manifest.get("chainSequence") != expected_sequence:
            fail(f"第 {expected_sequence} 个增量包的 base/parent/sequence 链接不连续")
        database = ensure_dict(manifest.get("database"), "增量 database")
        binlog = ensure_dict(database.get("binlog"), "增量 binlog")
        current_from = validate_boundary(binlog.get("from"), "增量 from")
        current_to = validate_boundary(binlog.get("to"), "增量 to")
        if current_from != previous_to:
            fail(f"第 {expected_sequence} 个增量包的 binlog 起点与父包终点不一致")
        completed = validate_timestamp(manifest.get("completedAt"), "增量 completedAt")
        if completed < previous_completed:
            fail("备份链完成时间倒序")
        delta = ensure_dict(load_json(directory / "verification/attachment-delta.json"), "附件差异")
        expected_snapshot = validate_attachment_snapshot(load_json(directory / "verification/attachments.json"))
        materialized = snapshot_map(previous_snapshot)
        for path in ensure_list(delta.get("deleted"), "delta.deleted"):
            relative = safe_relative(path)
            if relative not in materialized:
                fail(f"附件差异删除了父快照中不存在的路径：{relative}")
            del materialized[relative]
        expected_map = snapshot_map(expected_snapshot)
        for raw in ensure_list(delta.get("addedOrChanged"), "delta.addedOrChanged"):
            item = ensure_dict(raw, "delta change")
            relative = safe_relative(item.get("path"))
            if relative not in expected_map:
                fail(f"附件差异新增项不在最终快照：{relative}")
            materialized[relative] = expected_map[relative]
        if materialized != expected_map:
            fail(f"第 {expected_sequence} 个增量的附件差异无法重建最终快照")
        previous_snapshot = expected_snapshot
        previous_to = current_to
        previous_id = validate_backup_id(manifest.get("backupId"), "增量 backupId")
        previous_completed = completed
    print(json.dumps({"baseBackupId": base_id, "lastBackupId": previous_id, "chainLength": len(args.incrementals)}, sort_keys=True))


def command_apply_delta(args: argparse.Namespace) -> None:
    payload = Path(args.payload)
    target = Path(args.target)
    if not target.is_dir() or target.is_symlink():
        fail("附件恢复目标目录不安全")
    delta = ensure_dict(load_json(payload / "verification/attachment-delta.json"), "附件差异")
    for raw in ensure_list(delta.get("deleted"), "delta.deleted"):
        relative = safe_relative(raw)
        destination = target.joinpath(*PurePosixPath(relative).parts)
        if destination.is_symlink() or (destination.exists() and not destination.is_file()):
            fail(f"待删除附件目标不是普通文件：{relative}")
        if destination.exists():
            destination.unlink()
    for raw in ensure_list(delta.get("addedOrChanged"), "delta.addedOrChanged"):
        item = ensure_dict(raw, "delta change")
        relative = safe_relative(item.get("path"))
        source = require_regular_tree_file(payload / "attachments", relative)
        if source.stat().st_size != item.get("size") or sha256_file(source) != item.get("sha256"):
            fail(f"增量源附件校验失败：{relative}")
        destination = target.joinpath(*PurePosixPath(relative).parts)
        current = target
        for part in PurePosixPath(relative).parts[:-1]:
            current = current / part
            if current.exists() and (not current.is_dir() or current.is_symlink()):
                fail(f"附件恢复路径经过不安全目录：{relative}")
            current.mkdir(mode=0o750, exist_ok=True)
        temporary = destination.with_name(f".{destination.name}.delta-{os.getpid()}")
        with source.open("rb") as source_handle, temporary.open("xb") as target_handle:
            shutil.copyfileobj(source_handle, target_handle, length=1024 * 1024)
            target_handle.flush()
            os.fsync(target_handle.fileno())
        os.chmod(temporary, 0o640)
        os.replace(temporary, destination)
    for directory in sorted((path for path in target.rglob("*") if path.is_dir()), reverse=True):
        try:
            directory.rmdir()
        except OSError:
            pass


def sidecar_valid(archive: Path) -> bool:
    sidecar = archive.with_name(f"{archive.name}.sha256")
    if not sidecar.is_file() or sidecar.is_symlink():
        return False
    try:
        content = sidecar.read_text(encoding="ascii")
    except (OSError, UnicodeError):
        return False
    match = re.fullmatch(r"([0-9a-f]{64})  (.+)\n?", content)
    return bool(match and match.group(2) == archive.name and match.group(1) == sha256_file(archive))


def read_envelope(archive: Path) -> dict | None:
    try:
        with tarfile.open(archive, "r:") as handle:
            members = [member for member in handle.getmembers() if member.name == "envelope.json" and member.isfile()]
            if len(members) != 1:
                return None
            source = handle.extractfile(members[0])
            if source is None:
                return None
            document = json.loads(source.read().decode("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError, tarfile.TarError):
        return None
    return document if isinstance(document, dict) else None


def command_retention_plan(args: argparse.Namespace) -> None:
    directory = Path(args.directory)
    daily, weekly, monthly = (int(args.daily), int(args.weekly), int(args.monthly))
    if min(daily, weekly, monthly) < 0:
        fail("备份保留数必须是非负整数")
    pattern = re.compile(ARCHIVE_RE_TEMPLATE.format(site=re.escape(args.site_id)))
    entries: list[dict[str, object]] = []
    for archive in directory.iterdir():
        match = pattern.fullmatch(archive.name)
        if not archive.is_file() or archive.is_symlink() or not match or not sidecar_valid(archive):
            continue
        envelope = read_envelope(archive)
        if envelope is None:
            continue
        backup_id = envelope.get("backupId")
        if backup_id != match.group("backup_id") or not isinstance(backup_id, str):
            continue
        timestamp = dt.datetime.strptime(match.group("timestamp"), "%Y%m%dT%H%M%SZ").replace(tzinfo=dt.timezone.utc)
        if envelope.get("formatVersion") == "copper-backup/v1":
            entries.append({"path": archive, "id": backup_id, "base": backup_id, "parent": None, "sequence": 0, "timestamp": timestamp})
        elif envelope.get("formatVersion") == FORMAT and envelope.get("backupType") == "incremental":
            base, parent, sequence = envelope.get("baseBackupId"), envelope.get("parentBackupId"), envelope.get("chainSequence")
            if not isinstance(base, str) or not BACKUP_ID_RE.fullmatch(base) or not isinstance(parent, str) or not BACKUP_ID_RE.fullmatch(parent) or not isinstance(sequence, int) or sequence < 1:
                continue
            entries.append({"path": archive, "id": backup_id, "base": base, "parent": parent, "sequence": sequence, "timestamp": timestamp})
    if not entries:
        return
    id_counts: dict[str, int] = {}
    for item in entries:
        backup_id = str(item["id"])
        id_counts[backup_id] = id_counts.get(backup_id, 0) + 1
    valid_chains: dict[str, list[dict[str, object]]] = {}
    for item in entries:
        if item["sequence"] != 0:
            continue
        base = str(item["id"])
        if id_counts.get(base) != 1:
            continue
        chain = [item]
        previous = base
        candidates = sorted(
            (entry for entry in entries if entry["base"] == base and entry["sequence"] != 0),
            key=lambda entry: (int(entry["sequence"]), str(entry["id"])),
        )
        chain_is_complete = True
        for candidate in candidates:
            if (
                candidate["sequence"] != len(chain)
                or candidate["parent"] != previous
                or id_counts.get(str(candidate["id"])) != 1
            ):
                chain_is_complete = False
                break
            chain.append(candidate)
            previous = str(candidate["id"])
        if chain_is_complete:
            valid_chains[base] = chain
    if not valid_chains:
        return
    bases = sorted((chain[0] for chain in valid_chains.values()), key=lambda item: item["timestamp"], reverse=True)
    keep_bases: set[str] = {str(bases[0]["id"])}

    def keep_periods(limit: int, key_function) -> None:
        seen: set[object] = set()
        for item in bases:
            key = key_function(item["timestamp"])
            if key in seen:
                continue
            if len(seen) >= limit:
                break
            seen.add(key)
            keep_bases.add(str(item["id"]))

    keep_periods(daily, lambda value: value.date())
    keep_periods(weekly, lambda value: (value.isocalendar().year, value.isocalendar().week))
    keep_periods(monthly, lambda value: (value.year, value.month))
    keep_paths = {Path(item["path"]) for base in keep_bases for item in valid_chains[base]}
    # Incomplete/orphan increments are never auto-deleted: they require operator review.
    deletable = [item for base, chain in valid_chains.items() if base not in keep_bases for item in chain]
    for item in sorted(deletable, key=lambda entry: entry["timestamp"]):
        path = Path(item["path"])
        if path not in keep_paths:
            print(path.name)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    delta = sub.add_parser("attachment-delta")
    delta.add_argument("state"); delta.add_argument("current"); delta.add_argument("source"); delta.add_argument("target"); delta.add_argument("output")
    delta.set_defaults(function=command_attachment_delta)

    manifest = sub.add_parser("manifest")
    for name in (
        "output", "backup_id", "base_backup_id", "parent_backup_id", "sequence", "site_id", "purpose",
        "started_at", "completed_at", "time_zone", "web_image", "backend_image", "mysql_image",
        "database_version", "schema_version", "from_file", "from_position", "to_file", "to_position",
        "binlogs", "record_counts", "business_totals", "attachments", "attachment_delta", "runtime_config",
        "schema", "copperctl_version", "age_version", "zstd_version",
    ):
        manifest.add_argument(name)
    manifest.set_defaults(function=command_manifest)

    envelope = sub.add_parser("envelope")
    for name in ("output", "backup_id", "base_backup_id", "parent_backup_id", "sequence", "site_id", "created_at", "payload", "recipient_fingerprint", "offline_proof_sha256"):
        envelope.add_argument(name)
    envelope.set_defaults(function=command_envelope)

    success = sub.add_parser("last-success")
    for name in ("output", "backup_id", "base_backup_id", "parent_backup_id", "sequence", "completed_at", "archive", "recipient_fingerprint", "offline_proof_sha256"):
        success.add_argument(name)
    success.set_defaults(function=command_last_success)

    state = sub.add_parser("write-state")
    for name in ("output", "site_id", "backup_id", "base_backup_id", "parent_backup_id", "sequence", "completed_at", "binlog_file", "binlog_position", "archive", "archive_sha256", "attachments"):
        state.add_argument(name)
    state.set_defaults(function=command_write_state)

    info = sub.add_parser("state-info")
    info.add_argument("state"); info.add_argument("site_id")
    info.set_defaults(function=command_state_info)

    verify = sub.add_parser("verify-incremental")
    verify.add_argument("directory"); verify.add_argument("web_image"); verify.add_argument("backend_image"); verify.add_argument("mysql_image"); verify.add_argument("output")
    verify.add_argument("--expected-backup-id", default="")
    verify.add_argument("--expected-base-backup-id", default="")
    verify.add_argument("--expected-parent-backup-id", default="")
    verify.add_argument("--expected-sequence", default="")
    verify.add_argument("--expected-site-fingerprint", default="")
    verify.add_argument("--expected-created-at", default="")
    verify.add_argument("--expected-offline-proof-sha256", default="")
    verify.set_defaults(function=command_verify_incremental)

    chain = sub.add_parser("verify-chain")
    chain.add_argument("base"); chain.add_argument("incrementals", nargs="+")
    chain.set_defaults(function=command_verify_chain)

    apply_delta = sub.add_parser("apply-delta")
    apply_delta.add_argument("payload"); apply_delta.add_argument("target")
    apply_delta.set_defaults(function=command_apply_delta)

    retention = sub.add_parser("retention-plan")
    retention.add_argument("directory"); retention.add_argument("site_id"); retention.add_argument("daily"); retention.add_argument("weekly"); retention.add_argument("monthly")
    retention.set_defaults(function=command_retention_plan)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.function(args)


if __name__ == "__main__":
    main()
