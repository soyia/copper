#!/usr/bin/env python3
"""Build the host capacity snapshot consumed by copperctl and copper_backend."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import shutil
import sys
import tempfile


def directory_bytes(path: pathlib.Path) -> int:
    if not path.exists():
        return 0
    total = 0
    for root, directories, files in os.walk(path, followlinks=False):
        directories[:] = [name for name in directories if not (pathlib.Path(root) / name).is_symlink()]
        for name in files:
            item = pathlib.Path(root) / name
            try:
                if not item.is_symlink():
                    total += item.stat().st_size
            except FileNotFoundError:
                continue
    return total


def risk_level(total: int, available: int) -> str:
    if total <= 0:
        return "UNKNOWN"
    percent = available * 100 / total
    if available < 1024**3 or percent < 5:
        return "DANGER"
    if available < 2 * 1024**3 or percent < 10:
        return "CRITICAL"
    if available < 5 * 1024**3 or percent < 20:
        return "WARNING"
    return "NORMAL"


def last_success(path: pathlib.Path) -> dict[str, object] | None:
    if not path.is_file() or path.is_symlink():
        return None
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
        archive = pathlib.PurePath(str(document["archive"])).name
        return {
            "backupId": document.get("backupId"),
            "completedAt": document.get("completedAt"),
            "archive": archive,
        }
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
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
        directory_descriptor = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--var-dir", required=True)
    parser.add_argument("--backup-dir", required=True)
    parser.add_argument("--targets-json", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    var_dir = pathlib.Path(args.var_dir)
    backup_dir = pathlib.Path(args.backup_dir)
    with pathlib.Path(args.targets_json).open(encoding="utf-8") as handle:
        targets = json.load(handle)
    if targets.get("formatVersion") != "copper-storage-status/v1":
        raise ValueError("存储目标状态格式不正确")

    data_usage = shutil.disk_usage(var_dir)
    database_bytes = directory_bytes(var_dir / "mysql")
    attachment_bytes = directory_bytes(var_dir / "files")
    export_bytes = directory_bytes(var_dir / "backend" / "exports")
    log_bytes = directory_bytes(var_dir / "logs")
    local_backup_bytes = 0
    for target in targets.get("targets", []):
        if target.get("target") == "local":
            local_backup_bytes = int(target.get("backupBytes", 0))
            break
    document = {
        "formatVersion": "copper-capacity/v1",
        "observedAt": dt.datetime.now(dt.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "usage": {
            "databaseBytes": database_bytes,
            "attachmentBytes": attachment_bytes,
            "exportBytes": export_bytes,
            "backupBytes": local_backup_bytes,
            "logBytes": log_bytes,
        },
        "dataFilesystem": {
            "capacityBytes": data_usage.total,
            "availableBytes": data_usage.free,
            "riskLevel": risk_level(data_usage.total, data_usage.free),
        },
        "targets": targets.get("targets", []),
        "lastSuccessfulBackup": last_success(backup_dir / "last-success.json"),
        "policy": {
            "quotaSource": "CUSTOMER_STORAGE",
            "artificialCopperQuota": False,
            "warning": "低于 20% 或 5 GiB",
            "critical": "低于 10% 或 2 GiB；禁止向该目标备份，并禁止导出和附件上传",
            "danger": "低于 5% 或 1 GiB；业务写入进入受控只读",
        },
    }
    atomic_write(pathlib.Path(args.output), document)
    print(json.dumps(document, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
