#!/usr/bin/env python3
"""Root-only lifecycle evidence for Copper retained-sidecar upgrades."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import shutil
import stat
import sys
import tempfile
from typing import Any


FORMAT = "copper-upgrade-run/v1"
ID_RE = re.compile(r"[a-z0-9][a-z0-9-]{7,47}")
HASH_RE = re.compile(r"[0-9a-f]{64}")
ALLOWED: dict[str, set[str]] = {
    "CLAIMED": {"RESTORING", "ABORTED"},
    "RESTORING": {"MIGRATING", "MIGRATION_FAILED"},
    "MIGRATING": {"MIGRATION_VALIDATED", "MIGRATION_FAILED"},
    "MIGRATION_VALIDATED": {"SWITCHING", "DIRTY", "ABORTED"},
    "SWITCHING": {"SWITCHED", "MIGRATION_VALIDATED", "MIGRATION_FAILED", "DIRTY", "RECOVERY_FAILED"},
    "SWITCHED": {"ROLLING_BACK", "COMPLETED"},
    "ROLLING_BACK": {"ROLLED_BACK", "SWITCHED", "RECOVERY_FAILED"},
    "DIRTY": {"ABORTED"},
    "MIGRATION_FAILED": {"ABORTED"},
    "ROLLED_BACK": {"COMPLETED"},
    "ABORTED": set(),
    "COMPLETED": set(),
    "RECOVERY_FAILED": set(),
}


class UpgradeRunError(ValueError):
    pass


def fail(message: str) -> None:
    raise UpgradeRunError(message)


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as reader:
        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: pathlib.Path, label: str) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        fail(f"{label} 不存在、不是普通文件或是符号链接")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        fail(f"{label} 不是有效 UTF-8 JSON：{exc}")
    if not isinstance(value, dict):
        fail(f"{label} 顶层必须是对象")
    return value


def atomic_json(path: pathlib.Path, document: dict[str, Any], mode: int = 0o400, exclusive: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    if exclusive and (path.exists() or path.is_symlink()):
        fail(f"状态证据已存在：{path}")
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as writer:
            json.dump(document, writer, ensure_ascii=False, indent=2)
            writer.write("\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, mode)
        if exclusive:
            try:
                os.link(temporary, path)
            except FileExistsError:
                fail(f"状态证据已存在：{path}")
            temporary.unlink()
        else:
            os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def validate_state(path: pathlib.Path) -> dict[str, Any]:
    document = load_json(path, "升级运行状态")
    if document.get("formatVersion") != FORMAT or document.get("status") not in ALLOWED:
        fail("升级运行状态格式或状态不受支持")
    if not ID_RE.fullmatch(str(document.get("upgradeId", ""))):
        fail("升级运行状态 upgradeId 不正确")
    if not HASH_RE.fullmatch(str(document.get("preparedSha256", ""))):
        fail("升级运行状态 PREPARED 摘要不正确")
    metadata = path.stat()
    if stat.S_IMODE(metadata.st_mode) != 0o400:
        fail("升级运行状态权限必须是 0400")
    if os.geteuid() == 0 and metadata.st_uid != 0:
        fail("升级运行状态必须由 root 持有")
    prepared = pathlib.Path(str(document.get("preparedPath", "")))
    if prepared.is_symlink() or not prepared.is_file() or sha256_file(prepared) != document["preparedSha256"]:
        fail("升级运行绑定的 PREPARED 记录不存在或摘要改变")
    return document


def append_event(run_dir: pathlib.Path, upgrade_id: str, action: str, result: str, message: str) -> None:
    if result not in {"started", "passed", "failed"}:
        fail("升级事件结果不受支持")
    entry = {
        "formatVersion": "copper-upgrade-event/v1",
        "at": now(),
        "upgradeId": upgrade_id,
        "action": action,
        "result": result,
        "message": message.replace("\n", " ").replace("\r", " ")[:500],
    }
    path = run_dir / "events.jsonl"
    if path.exists() and path.stat().st_size > 5 * 1024 * 1024:
        rotated = run_dir / "events.jsonl.1"
        rotated.unlink(missing_ok=True)
        os.replace(path, rotated)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(descriptor, "a", encoding="utf-8") as writer:
        writer.write(json.dumps(entry, ensure_ascii=False, sort_keys=True) + "\n")
        writer.flush()
        os.fsync(writer.fileno())
    os.chmod(path, 0o600)


def command_claim(args: argparse.Namespace) -> None:
    prepared_path = pathlib.Path(args.prepared).resolve()
    prepared = load_json(prepared_path, "PREPARED 记录")
    if prepared.get("formatVersion") != "copper-upgrade-preparation/v1" or prepared.get("status") != "PREPARED":
        fail("只能消费 M3-02 的 PREPARED 记录")
    if not ID_RE.fullmatch(args.upgrade_id):
        fail("upgradeId 必须是 8-48 位小写字母、数字和短横线")
    upgrade_root = pathlib.Path(args.upgrade_root).resolve()
    run_dir = upgrade_root / "runs" / args.upgrade_id
    if run_dir.exists() or run_dir.is_symlink():
        fail("upgradeId 已存在")
    run_dir.mkdir(parents=True, mode=0o700)
    os.chmod(run_dir, 0o700)
    prepared_sha = sha256_file(prepared_path)
    claim_path = upgrade_root / "prepared.claim.json"
    claim = {
        "formatVersion": "copper-upgrade-claim/v1",
        "claimedAt": now(),
        "upgradeId": args.upgrade_id,
        "preparedPath": str(prepared_path),
        "preparedSha256": prepared_sha,
    }
    try:
        atomic_json(claim_path, claim, exclusive=True)
    except Exception:
        run_dir.rmdir()
        raise
    document = {
        "formatVersion": FORMAT,
        "upgradeId": args.upgrade_id,
        "restoreId": args.upgrade_id,
        "status": "CLAIMED",
        "phase": "prepared-consumed",
        "createdAt": now(),
        "updatedAt": now(),
        "preparedPath": str(prepared_path),
        "preparedSha256": prepared_sha,
        "backupId": prepared.get("backup", {}).get("backupId"),
        "sourceVersion": prepared.get("currentVersion"),
        "sourceSchema": prepared.get("currentSchema"),
        "targetVersion": prepared.get("targetVersion"),
        "targetSchema": prepared.get("targetSchema"),
        "lastError": "",
    }
    atomic_json(run_dir / "state.json", document, exclusive=True)
    append_event(run_dir, args.upgrade_id, "claim", "passed", "exact PREPARED record claimed once")
    print(json.dumps(document, ensure_ascii=False, indent=2))


def command_transition(args: argparse.Namespace) -> None:
    path = pathlib.Path(args.state)
    document = validate_state(path)
    current = str(document["status"])
    if args.status not in ALLOWED[current]:
        fail(f"非法或重复状态迁移：{current} -> {args.status}")
    document["status"] = args.status
    document["phase"] = args.phase
    document["updatedAt"] = now()
    document["lastError"] = args.error or ""
    atomic_json(path, document)
    append_event(path.parent, str(document["upgradeId"]), args.action, args.result, args.message)
    print(json.dumps(document, ensure_ascii=False, indent=2))


def command_status(args: argparse.Namespace) -> None:
    document = validate_state(pathlib.Path(args.state))
    if args.json:
        print(json.dumps(document, ensure_ascii=False, indent=2))
    else:
        for key in ("upgradeId", "status", "phase", "sourceVersion", "sourceSchema", "targetVersion", "targetSchema", "lastError"):
            print(f"{key}={document.get(key, '')}")


def command_finalize(args: argparse.Namespace) -> None:
    state_path = pathlib.Path(args.state)
    document = validate_state(state_path)
    if document["status"] not in {"ABORTED", "COMPLETED"}:
        fail("只有 ABORTED 或 COMPLETED 才能归档 PREPARED 证据并释放下一次升级")
    run_dir = state_path.parent
    upgrade_root = run_dir.parent.parent
    claim_path = upgrade_root / "prepared.claim.json"
    claim = load_json(claim_path, "PREPARED 消费声明")
    if claim.get("upgradeId") != document["upgradeId"] or claim.get("preparedSha256") != document["preparedSha256"]:
        fail("PREPARED 消费声明与升级运行不一致")
    prepared_path = pathlib.Path(str(document["preparedPath"]))
    archived_prepared = run_dir / "prepared.json"
    contract_path = upgrade_root / "prepared-contract"
    archived_contract = run_dir / "prepared-contract"
    archived_claim = run_dir / "prepared.claim.json"
    if prepared_path != archived_prepared:
        if archived_prepared.exists() or archived_prepared.is_symlink():
            fail("升级运行目录已存在冲突的 PREPARED 归档")
        if sha256_file(prepared_path) != document["preparedSha256"]:
            fail("归档前 PREPARED 摘要改变")
        shutil.copyfile(prepared_path, archived_prepared)
        os.chmod(archived_prepared, 0o400)
        if sha256_file(archived_prepared) != document["preparedSha256"]:
            archived_prepared.unlink(missing_ok=True)
            fail("PREPARED 归档复制后摘要不一致")
        document["preparedPath"] = str(archived_prepared)
        document["phase"] = "evidence-archived"
        document["updatedAt"] = now()
        atomic_json(state_path, document)
        prepared_path.unlink()
    else:
        stale_prepared = upgrade_root / "prepared.json"
        if stale_prepared.exists() and sha256_file(stale_prepared) == document["preparedSha256"]:
            stale_prepared.unlink()
    if contract_path.exists():
        if contract_path.is_symlink() or archived_contract.exists() or archived_contract.is_symlink():
            fail("发布契约归档路径不安全或冲突")
        os.replace(contract_path, archived_contract)
    if claim_path.exists():
        if archived_claim.exists() or archived_claim.is_symlink():
            fail("消费声明归档路径冲突")
        os.replace(claim_path, archived_claim)
    append_event(run_dir, str(document["upgradeId"]), "finalize", "passed", "PREPARED evidence archived and next upgrade released")
    print(json.dumps(document, ensure_ascii=False, indent=2))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Copper M3 retained-sidecar upgrade state")
    subparsers = parser.add_subparsers(dest="command", required=True)
    claim = subparsers.add_parser("claim")
    claim.add_argument("--prepared", required=True)
    claim.add_argument("--upgrade-root", required=True)
    claim.add_argument("--upgrade-id", required=True)
    claim.set_defaults(handler=command_claim)
    transition = subparsers.add_parser("transition")
    transition.add_argument("--state", required=True)
    transition.add_argument("--status", required=True, choices=sorted(ALLOWED))
    transition.add_argument("--phase", required=True)
    transition.add_argument("--action", required=True)
    transition.add_argument("--result", required=True, choices=("started", "passed", "failed"))
    transition.add_argument("--message", required=True)
    transition.add_argument("--error")
    transition.set_defaults(handler=command_transition)
    status = subparsers.add_parser("status")
    status.add_argument("--state", required=True)
    status.add_argument("--json", action="store_true")
    status.set_defaults(handler=command_status)
    finalize = subparsers.add_parser("finalize")
    finalize.add_argument("--state", required=True)
    finalize.set_defaults(handler=command_finalize)
    return parser


def main() -> int:
    try:
        args = build_parser().parse_args()
        args.handler(args)
        return 0
    except (UpgradeRunError, OSError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
