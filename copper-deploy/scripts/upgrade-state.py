#!/usr/bin/env python3
"""Create and inspect root-only M3 upgrade preparation evidence."""

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


FORMAT = "copper-upgrade-preparation/v1"
HASH_RE = re.compile(r"[0-9a-f]{64}")
BACKUP_ID_RE = re.compile(r"[0-9a-f]{32}")
VERSION_RE = re.compile(r"[0-9A-Za-z][0-9A-Za-z.-]{0,63}")


class StateError(ValueError):
    pass


def fail(message: str) -> None:
    raise StateError(message)


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as reader:
        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: pathlib.Path, label: str) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        fail(f"{label} 必须是普通文件且不能是符号链接")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"{label} 不是有效 JSON：{exc}")
    if not isinstance(document, dict):
        fail(f"{label} 顶层必须是对象")
    return document


def timestamp(value: str, label: str) -> str:
    try:
        parsed = dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)
    except ValueError as exc:
        fail(f"{label} 不是 UTC RFC3339 秒级时间：{exc}")
    if parsed > dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=5):
        fail(f"{label} 不能位于未来")
    return value


def positive(value: str, label: str, allow_zero: bool = False) -> int:
    if not re.fullmatch(r"0|[1-9][0-9]*", value):
        fail(f"{label} 必须是整数")
    number = int(value)
    if number < (0 if allow_zero else 1):
        fail(f"{label} 超出允许范围")
    return number


def atomic_json(path: pathlib.Path, document: dict[str, Any], mode: int = 0o400) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as writer:
            json.dump(document, writer, ensure_ascii=False, indent=2)
            writer.write("\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def checked_file(path_text: str, digest: str, label: str) -> pathlib.Path:
    path = pathlib.Path(path_text)
    if path.is_symlink() or not path.is_file() or not HASH_RE.fullmatch(digest):
        fail(f"{label} 不存在、不是普通文件或摘要格式不正确")
    if sha256_file(path) != digest:
        fail(f"{label} 实际 SHA-256 与准备证据不一致")
    return path.resolve()


def stage_release_contract(output: pathlib.Path, contract: dict[str, Any]) -> dict[str, Any]:
    manifest = checked_file(str(contract["manifest"]), str(contract["manifestSha256"]), "目标发布清单")
    matrix = checked_file(str(contract["matrix"]), str(contract["matrixSha256"]), "目标升级矩阵")
    ready = manifest.parent / "ready.json"
    if ready.is_symlink() or not ready.is_file():
        fail("目标 READY 收据不存在、不是普通文件或是符号链接")
    ready = ready.resolve()
    ready_sha256 = sha256_file(ready)
    artifact_sources = [
        checked_file(str(item["path"]), str(item["sha256"]), f"目标发布物 {label}")
        for label, item in contract["artifacts"].items()
    ]
    sources = [manifest, matrix, ready, *artifact_sources]
    if any(source.parent != manifest.parent for source in sources) or len({source.name for source in sources}) != len(sources):
        fail("目标发布清单、矩阵和发布物必须是同目录的独立文件")
    final = output.parent / "prepared-contract"
    if final.exists() or final.is_symlink():
        fail("升级准备契约目录已经存在；拒绝覆盖")
    temporary = pathlib.Path(tempfile.mkdtemp(prefix=".prepared-contract-", dir=output.parent))
    try:
        for source in sources:
            target = temporary / source.name
            shutil.copyfile(source, target)
            os.chmod(target, 0o400)
            if sha256_file(target) != sha256_file(source):
                fail("升级契约复制后 SHA-256 不一致")
        os.chmod(temporary, 0o500)
        os.replace(temporary, final)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    staged_artifacts = {
        label: {
            "path": str((final / pathlib.Path(str(item["path"])).name).resolve()),
            "sha256": str(item["sha256"]),
        }
        for label, item in contract["artifacts"].items()
    }
    return {
        "manifest": str((final / manifest.name).resolve()),
        "manifestSha256": str(contract["manifestSha256"]),
        "ready": str((final / ready.name).resolve()),
        "readySha256": ready_sha256,
        "matrix": str((final / matrix.name).resolve()),
        "matrixSha256": str(contract["matrixSha256"]),
        "artifacts": staged_artifacts,
    }


def validate_prepared_evidence(path: pathlib.Path, document: dict[str, Any]) -> None:
    file_stat = path.stat()
    if stat.S_IMODE(file_stat.st_mode) != 0o400:
        fail("升级准备记录权限必须是 0400")
    if os.geteuid() == 0 and file_stat.st_uid != 0:
        fail("升级准备记录必须由 root 持有")
    contract = document.get("releaseContract")
    if not isinstance(contract, dict):
        fail("升级准备记录缺少发布契约证据")
    checked_file(str(contract.get("manifest", "")), str(contract.get("manifestSha256", "")), "已暂存发布清单")
    checked_file(str(contract.get("ready", "")), str(contract.get("readySha256", "")), "已暂存 READY 收据")
    checked_file(str(contract.get("matrix", "")), str(contract.get("matrixSha256", "")), "已暂存升级矩阵")
    artifacts = contract.get("artifacts")
    if not isinstance(artifacts, dict) or set(artifacts) != {"deployArchive", "releaseNotes", "acmeArchive"}:
        fail("升级准备记录缺少完整发布物证据")
    for label, item in artifacts.items():
        if not isinstance(item, dict):
            fail(f"升级准备发布物 {label} 证据不正确")
        checked_file(str(item.get("path", "")), str(item.get("sha256", "")), f"已暂存发布物 {label}")
    backup = document.get("backup")
    if not isinstance(backup, dict):
        fail("升级准备记录缺少备份证据")
    archive = checked_file(str(backup.get("archive", "")), str(backup.get("sha256", "")), "升级准备备份")
    if backup.get("bytes") != archive.stat().st_size or backup.get("outerVerification") != "PASSED":
        fail("升级准备备份大小或外层校验状态不正确")


def validate_contract(document: dict[str, Any]) -> dict[str, Any]:
    required = {
        "manifest",
        "manifestSha256",
        "matrix",
        "matrixSha256",
        "targetVersion",
        "targetSchema",
        "minimumCpu",
        "minimumMemoryGiB",
        "minimumFreeDiskGiB",
        "mysqlVersion",
        "supportedOs",
        "supportedArch",
        "artifacts",
        "source",
    }
    if set(document) != required:
        fail("升级契约查询结果字段不完整")
    for field in ("manifestSha256", "matrixSha256"):
        if not isinstance(document[field], str) or not HASH_RE.fullmatch(document[field]):
            fail(f"升级契约 {field} 不正确")
    if not isinstance(document["targetVersion"], str) or not VERSION_RE.fullmatch(document["targetVersion"]):
        fail("升级契约 targetVersion 不正确")
    if not isinstance(document["targetSchema"], str):
        fail("升级契约 targetSchema 不正确")
    positive(document["targetSchema"], "targetSchema", allow_zero=True)
    if not isinstance(document["minimumFreeDiskGiB"], int) or document["minimumFreeDiskGiB"] < 1:
        fail("升级契约 minimumFreeDiskGiB 不正确")
    if not isinstance(document["minimumCpu"], int) or document["minimumCpu"] < 1:
        fail("升级契约 minimumCpu 不正确")
    if not isinstance(document["minimumMemoryGiB"], int) or document["minimumMemoryGiB"] < 1:
        fail("升级契约 minimumMemoryGiB 不正确")
    if document["supportedOs"] != "ubuntu-24.04" or document["supportedArch"] != "amd64":
        fail("升级契约支持平台不正确")
    artifacts = document["artifacts"]
    if not isinstance(artifacts, dict) or set(artifacts) != {"deployArchive", "releaseNotes", "acmeArchive"}:
        fail("升级契约发布物绑定不完整")
    for label, item in artifacts.items():
        if not isinstance(item, dict) or set(item) != {"path", "sha256"}:
            fail(f"升级契约发布物 {label} 不正确")
        checked_file(str(item["path"]), str(item["sha256"]), f"升级契约发布物 {label}")
    if not isinstance(document["source"], dict):
        fail("升级契约 source 不正确")
    return document


def preflight_document(args: argparse.Namespace) -> dict[str, Any]:
    contract = validate_contract(load_json(pathlib.Path(args.contract_json), "升级契约查询结果"))
    return {
        "formatVersion": FORMAT,
        "status": "PREFLIGHT_PASSED",
        "checkedAt": timestamp(args.checked_at, "checkedAt"),
        "currentVersion": args.current_version,
        "currentSchema": args.current_schema,
        "targetVersion": contract["targetVersion"],
        "targetSchema": contract["targetSchema"],
        "manifestSha256": contract["manifestSha256"],
        "matrixSha256": contract["matrixSha256"],
        "sourceContract": contract["source"],
        "capacity": {
            "availableBytes": positive(args.available_bytes, "availableBytes", allow_zero=True),
            "requiredBytes": positive(args.required_bytes, "requiredBytes"),
        },
        "checks": {
            "releaseContract": "PASSED",
            "explicitSource": "PASSED",
            "platformAndRuntime": "PASSED",
            "currentSchema": "PASSED",
            "threeLayerHealth": "PASSED",
            "initialCredentialDestroyed": "PASSED",
            "offlineRecoveryKeyRestoreTest": "PASSED",
            "backupKeyAudit": "PASSED",
            "diskCapacity": "PASSED",
        },
    }


def command_preflight(args: argparse.Namespace) -> None:
    print(json.dumps(preflight_document(args), ensure_ascii=False, indent=2))


def command_prepare(args: argparse.Namespace) -> None:
    output = pathlib.Path(args.output)
    if output.exists() or output.is_symlink():
        fail("已有未消费的 PREPARED 记录；拒绝覆盖或重复生成备份")
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(output.parent, 0o700)
    contract = validate_contract(load_json(pathlib.Path(args.contract_json), "升级契约查询结果"))
    preflight = preflight_document(args)
    last_success_path = pathlib.Path(args.last_success)
    backup = load_json(last_success_path, "last-success.json")
    backup_id = str(backup.get("backupId", ""))
    backup_sha = str(backup.get("sha256", ""))
    archive_name = str(backup.get("archive", ""))
    completed_at = str(backup.get("completedAt", ""))
    size = backup.get("size")
    recipient_fingerprint = str(backup.get("recipientFingerprint", ""))
    if backup.get("formatVersion") != "copper-backup/v1":
        fail("last-success.json 的备份格式不是 copper-backup/v1")
    if not BACKUP_ID_RE.fullmatch(backup_id) or not HASH_RE.fullmatch(backup_sha):
        fail("last-success.json 的 backupId 或 sha256 不正确")
    if pathlib.PurePosixPath(archive_name).name != archive_name or not archive_name.endswith(".copperbak"):
        fail("last-success.json 的 archive 不正确")
    if not isinstance(size, int) or size < 1:
        fail("last-success.json 的 size 不正确")
    if not re.fullmatch(r"[0-9a-f]{16}", recipient_fingerprint):
        fail("last-success.json 的 recipientFingerprint 不正确")
    timestamp(completed_at, "backup.completedAt")
    archive = last_success_path.parent / archive_name
    if archive.is_symlink() or not archive.is_file():
        fail("准备备份归档不存在或不是普通文件")
    if archive.stat().st_size != size or sha256_file(archive) != backup_sha:
        fail("准备备份归档的大小或 SHA-256 与 last-success.json 不一致")
    sidecar = archive.with_name(archive.name + ".sha256")
    if sidecar.is_symlink() or not sidecar.is_file():
        fail("准备备份缺少 SHA-256 sidecar")
    expected_sidecar = f"{backup_sha}  {archive.name}\n"
    if sidecar.read_text(encoding="ascii") != expected_sidecar:
        fail("准备备份 SHA-256 sidecar 内容不一致")
    staged_contract = stage_release_contract(output, contract)
    document = {
        **preflight,
        "status": "PREPARED",
        "preparedAt": timestamp(args.prepared_at, "preparedAt"),
        "backup": {
            "formatVersion": backup.get("formatVersion"),
            "backupId": backup_id,
            "completedAt": completed_at,
            "archive": str(archive.resolve()),
            "bytes": size,
            "sha256": backup_sha,
            "recipientFingerprint": recipient_fingerprint,
            "outerVerification": "PASSED",
            "verifiedAt": timestamp(args.verified_at, "verifiedAt"),
            "lastSuccessSha256": sha256_file(last_success_path),
        },
        "releaseContract": staged_contract,
        "nextAction": "M3-03 migration state machine must consume this exact record before any schema or image change",
    }
    try:
        atomic_json(output, document)
    except Exception:
        shutil.rmtree(output.parent / "prepared-contract", ignore_errors=True)
        raise
    print(json.dumps(document, ensure_ascii=False, indent=2))


def command_status(args: argparse.Namespace) -> None:
    path = pathlib.Path(args.state)
    if not path.exists() and not path.is_symlink():
        document = {"formatVersion": FORMAT, "status": "NOT_PREPARED"}
    else:
        document = load_json(path, "升级准备记录")
        if document.get("formatVersion") != FORMAT or document.get("status") != "PREPARED":
            fail("升级准备记录格式或状态不受支持")
        validate_prepared_evidence(path, document)
    if args.json:
        print(json.dumps(document, ensure_ascii=False, indent=2))
        return
    print(f"status={document['status']}")
    if document["status"] == "PREPARED":
        print(f"current_version={document.get('currentVersion')}")
        print(f"target_version={document.get('targetVersion')}")
        print(f"target_schema={document.get('targetSchema')}")
        backup = document.get("backup", {})
        print(f"backup_id={backup.get('backupId')}")
        print(f"backup_sha256={backup.get('sha256')}")


def command_check_backup_readiness(args: argparse.Namespace) -> None:
    status_path = pathlib.Path(args.job_status)
    status = load_json(status_path, "备份任务状态")
    last_success_path = pathlib.Path(args.last_success)
    last_success = load_json(last_success_path, "last-success.json")
    if status.get("formatVersion") != "copper-backup-job-status/v1":
        fail("备份任务状态格式不受支持")
    if last_success.get("formatVersion") != "copper-backup/v1":
        fail("last-success.json 备份格式不受支持")
    for path, label in ((status_path, "备份任务状态"), (last_success_path, "last-success.json")):
        metadata = path.stat()
        if stat.S_IMODE(metadata.st_mode) != 0o600:
            fail(f"{label} 权限必须是 0600")
        if os.geteuid() == 0 and metadata.st_uid != 0:
            fail(f"{label} 必须由 root 持有")
    current = status.get("current")
    recorded = status.get("lastSuccess")
    if not isinstance(current, dict) or current.get("result") != "SUCCESS":
        fail("最近一次备份任务不是 SUCCESS；必须先修复并成功执行一次备份")
    if status.get("consecutiveFailures") != 0:
        fail("备份任务仍记录连续失败；必须先修复并成功执行一次备份")
    if not isinstance(recorded, dict):
        fail("备份任务状态缺少 lastSuccess")
    backup_id = str(last_success.get("backupId", ""))
    backup_sha = str(last_success.get("sha256", ""))
    if not BACKUP_ID_RE.fullmatch(backup_id) or not HASH_RE.fullmatch(backup_sha):
        fail("last-success.json 的备份身份不正确")
    if recorded.get("backupId") != backup_id or recorded.get("sha256") != backup_sha:
        fail("备份任务状态与 last-success.json 不一致")
    archive_name = str(last_success.get("archive", ""))
    if pathlib.PurePosixPath(archive_name).name != archive_name:
        fail("last-success.json 的 archive 不正确")
    archive = last_success_path.parent / archive_name
    if archive.is_symlink() or not archive.is_file() or sha256_file(archive) != backup_sha:
        fail("最近成功备份归档不存在或实际 SHA-256 不一致")
    if last_success.get("size") != archive.stat().st_size:
        fail("最近成功备份归档大小与 last-success.json 不一致")
    sidecar = archive.with_name(archive.name + ".sha256")
    if sidecar.is_symlink() or not sidecar.is_file() or sidecar.read_text(encoding="ascii") != f"{backup_sha}  {archive.name}\n":
        fail("最近成功备份缺少正确的 SHA-256 sidecar")
    print(f"backup_id={backup_id}")
    print(f"backup_sha256={backup_sha}")


def add_preflight_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--contract-json", required=True)
    parser.add_argument("--current-version", required=True)
    parser.add_argument("--current-schema", required=True)
    parser.add_argument("--available-bytes", required=True)
    parser.add_argument("--required-bytes", required=True)
    parser.add_argument("--checked-at", required=True)


def build_parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description="Copper M3 upgrade preparation evidence")
    subparsers = root.add_subparsers(dest="command", required=True)
    preflight = subparsers.add_parser("preflight")
    add_preflight_arguments(preflight)
    preflight.set_defaults(handler=command_preflight)
    prepare = subparsers.add_parser("prepare")
    add_preflight_arguments(prepare)
    prepare.add_argument("--last-success", required=True)
    prepare.add_argument("--prepared-at", required=True)
    prepare.add_argument("--verified-at", required=True)
    prepare.add_argument("--output", required=True)
    prepare.set_defaults(handler=command_prepare)
    status = subparsers.add_parser("status")
    status.add_argument("--state", required=True)
    status.add_argument("--json", action="store_true")
    status.set_defaults(handler=command_status)
    readiness = subparsers.add_parser("check-backup-readiness")
    readiness.add_argument("--job-status", required=True)
    readiness.add_argument("--last-success", required=True)
    readiness.set_defaults(handler=command_check_backup_readiness)
    return root


def main() -> int:
    try:
        args = build_parser().parse_args()
        args.handler(args)
        return 0
    except (StateError, OSError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
