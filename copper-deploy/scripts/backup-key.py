#!/usr/bin/env python3
"""Server-side lifecycle registry for customer-owned age backup keys."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import secrets
import stat
import subprocess
import tarfile
import tempfile


FORMAT = "copper-key-registry/v1"
RECIPIENT_RE = re.compile(r"^age1[0-9a-z]{20,}$")
HASH_RE = re.compile(r"^[0-9a-f]{64}$")
KEY_ID_RE = re.compile(r"^[0-9a-f]{16}$")
BACKUP_ID_RE = re.compile(r"^[0-9a-f]{32}$")


class KeyLifecycleError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise KeyLifecycleError(message)


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def key_id(recipient: str) -> str:
    return hashlib.sha256(recipient.encode("ascii")).hexdigest()[:16]


def site_fingerprint(site_id: str) -> str:
    return hashlib.sha256(site_id.encode("utf-8")).hexdigest()[:16]


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as reader:
        for block in iter(lambda: reader.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def secure_regular_file(path: pathlib.Path, label: str) -> None:
    if not path.is_file() or path.is_symlink():
        fail(f"{label} 不存在、不是普通文件或是符号链接：{path}")
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o077:
        fail(f"{label} 权限必须为 0600/0400 或更严格，当前为 {mode:04o}")


def atomic_json(path: pathlib.Path, document: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.tmp-", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as writer:
            json.dump(document, writer, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            writer.write("\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
        directory_descriptor = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
    finally:
        temporary.unlink(missing_ok=True)


def load_registry(path: pathlib.Path, required: bool = True) -> dict[str, object] | None:
    if not path.exists() and not required:
        return None
    secure_regular_file(path, "密钥生命周期登记簿")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"密钥生命周期登记簿无法读取：{type(error).__name__}")
    if not isinstance(document, dict) or document.get("formatVersion") != FORMAT:
        fail("密钥生命周期登记簿格式不受支持")
    if not isinstance(document.get("keys"), list) or not isinstance(document.get("events"), list):
        fail("密钥生命周期登记簿结构不完整")
    return document


def env_value(path: pathlib.Path, name: str) -> str:
    secure_regular_file(path, "Copper 配置文件")
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    fail(f"Copper 配置缺少 {name}")


def update_env_recipient(path: pathlib.Path, recipient: str) -> None:
    secure_regular_file(path, "Copper 配置文件")
    lines = path.read_text(encoding="utf-8").splitlines()
    changed = 0
    output: list[str] = []
    for line in lines:
        if line.startswith("COPPER_BACKUP_AGE_RECIPIENT="):
            output.append(f"COPPER_BACKUP_AGE_RECIPIENT={recipient}")
            changed += 1
        else:
            output.append(line)
    if changed != 1:
        fail("Copper 配置中的 COPPER_BACKUP_AGE_RECIPIENT 必须且只能出现一次")
    original = path.stat()
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.key-rotation-", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as writer:
            writer.write("\n".join(output) + "\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, stat.S_IMODE(original.st_mode))
        try:
            os.chown(temporary, original.st_uid, original.st_gid)
        except PermissionError:
            if os.geteuid() == 0:
                raise
        os.replace(temporary, path)
        directory_descriptor = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
    finally:
        temporary.unlink(missing_ok=True)


def validate_recipient(recipient: str) -> None:
    if not RECIPIENT_RE.fullmatch(recipient):
        fail("age 接收公钥格式不正确")
    try:
        subprocess.run(
            ["age", "--encrypt", "--recipient", recipient, "--output", os.devnull],
            input=b"",
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        fail("age 接收公钥未通过工具校验")


def clean_confirmation(value: str, label: str) -> str:
    text = value.strip()
    if not text or len(text) > 100 or any(character in text for character in "\r\n\t"):
        fail(f"{label} 必须为 1-100 个不含换行的字符")
    return text


def find_key(registry: dict[str, object], wanted: str) -> dict[str, object]:
    for item in registry["keys"]:
        if isinstance(item, dict) and item.get("keyId") == wanted:
            return item
    fail(f"登记簿中不存在 keyId={wanted}")


def active_key(registry: dict[str, object]) -> dict[str, object]:
    active_id = registry.get("activeKeyId")
    if not isinstance(active_id, str) or not KEY_ID_RE.fullmatch(active_id):
        fail("登记簿没有有效的当前密钥")
    item = find_key(registry, active_id)
    if item.get("status") != "ACTIVE":
        fail("登记簿当前密钥状态不是 ACTIVE")
    return item


def event(registry: dict[str, object], event_type: str, item_key_id: str, result: str) -> None:
    registry["events"].append(
        {"at": now(), "type": event_type, "keyId": item_key_id, "result": result}
    )


def handover(args: argparse.Namespace) -> dict[str, str]:
    digest = args.handover_sha256.lower()
    if not HASH_RE.fullmatch(digest):
        fail("交接单 SHA-256 必须为 64 位小写十六进制")
    customer = clean_confirmation(args.customer_confirmed_by, "客户确认人")
    witness = clean_confirmation(args.witness_confirmed_by, "复核见证人")
    if customer.casefold() == witness.casefold():
        fail("客户确认人和复核见证人必须是两名不同人员")
    return {
        "recordSha256": digest,
        "customerConfirmedBy": customer,
        "witnessConfirmedBy": witness,
    }


def command_register(args: argparse.Namespace) -> None:
    path = pathlib.Path(args.registry)
    config = pathlib.Path(args.env_file)
    recipient = env_value(config, "COPPER_BACKUP_AGE_RECIPIENT")
    validate_recipient(recipient)
    identifier = key_id(recipient)
    if load_registry(path, required=False) is not None:
        fail("密钥登记簿已经存在；轮换必须使用 prepare-rotation，禁止覆盖历史")
    registered_at = now()
    registry: dict[str, object] = {
        "formatVersion": FORMAT,
        "siteFingerprint": site_fingerprint(args.site_id),
        "activeKeyId": identifier,
        "updatedAt": registered_at,
        "keys": [
            {
                "keyId": identifier,
                "recipient": recipient,
                "status": "ACTIVE",
                "registeredAt": registered_at,
                "activatedAt": registered_at,
                "challengeVerifiedAt": None,
                "handover": handover(args),
                "restoreTests": [],
            }
        ],
        "events": [],
    }
    event(registry, "INITIAL_KEY_REGISTERED", identifier, "PASSED")
    atomic_json(path, registry)
    print(json.dumps({"keyId": identifier, "status": "ACTIVE"}, ensure_ascii=False))


def command_prepare_rotation(args: argparse.Namespace) -> None:
    path = pathlib.Path(args.registry)
    registry = load_registry(path)
    assert registry is not None
    current = active_key(registry)
    recipient = args.new_recipient.strip()
    validate_recipient(recipient)
    identifier = key_id(recipient)
    if identifier == current["keyId"]:
        fail("新接收公钥与当前活动公钥相同")
    if any(isinstance(item, dict) and item.get("keyId") == identifier for item in registry["keys"]):
        fail("该接收公钥已存在于密钥登记簿，禁止重复轮换")
    challenge_dir = pathlib.Path(args.challenge_dir)
    challenge_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(challenge_dir, 0o700)
    challenge = challenge_dir / f"rotation-{identifier}.age"
    if challenge.exists():
        fail(f"轮换挑战文件已经存在：{challenge}")
    proof = secrets.token_urlsafe(32)
    plaintext = f"copper-key-proof-v1:{identifier}:{proof}\n".encode("ascii")
    try:
        subprocess.run(
            ["age", "--encrypt", "--recipient", recipient, "--output", str(challenge)],
            input=plaintext,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        challenge.unlink(missing_ok=True)
        fail("生成 age 轮换挑战失败")
    os.chmod(challenge, 0o600)
    prepared_at = now()
    registry["keys"].append(
        {
            "keyId": identifier,
            "recipient": recipient,
            "status": "PENDING_PROOF",
            "registeredAt": prepared_at,
            "activatedAt": None,
            "challengeVerifiedAt": None,
            "handover": handover(args),
            "restoreTests": [],
            "proofSha256": hashlib.sha256(proof.encode("ascii")).hexdigest(),
            "challengeFile": str(challenge),
        }
    )
    registry["updatedAt"] = prepared_at
    event(registry, "KEY_ROTATION_PREPARED", identifier, "PASSED")
    atomic_json(path, registry)
    print(json.dumps({"keyId": identifier, "challenge": str(challenge)}, ensure_ascii=False))


def command_activate_rotation(args: argparse.Namespace) -> None:
    if args.confirm != args.key_id:
        fail("--confirm 必须与 keyId 完全一致")
    path = pathlib.Path(args.registry)
    registry = load_registry(path)
    assert registry is not None
    previous = active_key(registry)
    pending = find_key(registry, args.key_id)
    if pending.get("status") != "PENDING_PROOF":
        fail("待激活密钥状态不是 PENDING_PROOF")
    proof_file = pathlib.Path(args.proof_file)
    secure_regular_file(proof_file, "离线挑战证明文件")
    proof = proof_file.read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{40,80}", proof):
        fail("离线挑战证明格式不正确")
    if not secrets.compare_digest(
        hashlib.sha256(proof.encode("ascii")).hexdigest(), str(pending.get("proofSha256", ""))
    ):
        event(registry, "KEY_ROTATION_PROOF", args.key_id, "FAILED")
        registry["updatedAt"] = now()
        atomic_json(path, registry)
        fail("离线挑战证明不匹配；新私钥持有能力尚未验证")
    activated_at = now()
    update_env_recipient(pathlib.Path(args.env_file), str(pending["recipient"]))
    previous["status"] = "RETIRED"
    previous["retiredAt"] = activated_at
    pending["status"] = "ACTIVE"
    pending["activatedAt"] = activated_at
    pending["challengeVerifiedAt"] = activated_at
    challenge = pathlib.Path(str(pending.pop("challengeFile", "")))
    pending.pop("proofSha256", None)
    registry["activeKeyId"] = args.key_id
    registry["updatedAt"] = activated_at
    event(registry, "KEY_ROTATION_ACTIVATED", args.key_id, "PASSED")
    atomic_json(path, registry)
    if challenge.name:
        challenge.unlink(missing_ok=True)
    print(json.dumps({"keyId": args.key_id, "previousKeyId": previous["keyId"], "status": "ACTIVE"}))


def command_record_restore_test(args: argparse.Namespace) -> None:
    path = pathlib.Path(args.registry)
    registry = load_registry(path)
    assert registry is not None
    receipt = pathlib.Path(args.receipt)
    secure_regular_file(receipt, "离线备份验证回执")
    try:
        document = json.loads(receipt.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        fail("离线备份验证回执不是有效 JSON")
    if not isinstance(document, dict) or document.get("formatVersion") != "copper-key-backup-verification/v1":
        fail("离线备份验证回执格式不受支持")
    if document.get("result") != "PASSED":
        fail("离线备份验证回执结果不是 PASSED")
    identifier = str(document.get("keyId", ""))
    backup_id = str(document.get("backupId", ""))
    backup_sha = str(document.get("backupSha256", ""))
    proof = str(document.get("proof", ""))
    proof_sha = str(document.get("proofSha256", ""))
    if (
        not KEY_ID_RE.fullmatch(identifier)
        or not BACKUP_ID_RE.fullmatch(backup_id)
        or not HASH_RE.fullmatch(backup_sha)
        or not re.fullmatch(r"[0-9a-f]{64}", proof)
        or not HASH_RE.fullmatch(proof_sha)
        or hashlib.sha256(proof.encode("ascii")).hexdigest() != proof_sha
    ):
        fail("离线备份验证回执字段不完整")
    item = find_key(registry, identifier)
    validate_receipt_against_archive(
        pathlib.Path(args.backup_dir), document, identifier, backup_id, backup_sha, proof_sha
    )
    receipt_digest = sha256_file(receipt)
    if any(test.get("receiptSha256") == receipt_digest for test in item.get("restoreTests", [])):
        fail("该离线备份验证回执已经登记")
    item.setdefault("restoreTests", []).append(
        {
            "backupId": backup_id,
            "backupSha256": backup_sha,
            "verifiedAt": str(document.get("verifiedAt", "")),
            "receiptSha256": receipt_digest,
        }
    )
    registry["updatedAt"] = now()
    event(registry, "OFFLINE_BACKUP_VERIFICATION_RECORDED", identifier, "PASSED")
    atomic_json(path, registry)
    print(json.dumps({"keyId": identifier, "backupId": backup_id, "receiptSha256": receipt_digest}))


def validate_receipt_against_archive(
    backup_dir: pathlib.Path,
    receipt: dict[str, object],
    identifier: str,
    backup_id: str,
    backup_sha: str,
    proof_sha: str,
) -> None:
    if not backup_dir.is_dir() or backup_dir.is_symlink():
        fail("本地备份目录不存在或不安全，无法核对离线回执")
    candidates = sorted(backup_dir.glob(f"*_{backup_id}_*.copperbak"))
    for archive in candidates:
        try:
            if archive.is_symlink() or not archive.is_file() or sha256_file(archive) != backup_sha:
                continue
            sidecar = archive.with_name(archive.name + ".sha256")
            if sidecar.is_symlink() or not sidecar.is_file():
                continue
            lines = sidecar.read_text(encoding="ascii").splitlines()
            if lines != [f"{backup_sha}  {archive.name}"]:
                continue
            with tarfile.open(archive, "r:") as package:
                members = package.getmembers()
                if {member.name for member in members} != {
                    "README.txt",
                    "checksums.sha256",
                    "envelope.json",
                    "payload.tar.zst.age",
                } or any(not member.isfile() for member in members):
                    continue
                envelope_member = package.getmember("envelope.json")
                if envelope_member.size > 1024 * 1024:
                    continue
                reader = package.extractfile(envelope_member)
                if reader is None:
                    continue
                envelope = json.loads(reader.read().decode("utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError, tarfile.TarError):
            continue
        encryption = envelope.get("encryption") if isinstance(envelope, dict) else None
        offline = envelope.get("offlineVerification") if isinstance(envelope, dict) else None
        if (
            envelope.get("formatVersion") in {"copper-backup/v1", "copper-backup/v2"}
            and envelope.get("backupId") == backup_id
            and isinstance(encryption, dict)
            and encryption.get("format") == "age"
            and encryption.get("recipientFingerprint") == identifier
            and isinstance(offline, dict)
            and offline.get("format") == "copper-offline-key-proof/v1"
            and offline.get("proofSha256") == proof_sha
        ):
            return
    fail("离线回执未能与本地实际备份、接收公钥指纹和加密证明摘要匹配")


def command_audit(args: argparse.Namespace) -> None:
    path = pathlib.Path(args.registry)
    registry = load_registry(path)
    assert registry is not None
    current = active_key(registry)
    configured = env_value(pathlib.Path(args.env_file), "COPPER_BACKUP_AGE_RECIPIENT")
    if configured != current.get("recipient"):
        fail("配置中的 age 公钥与登记簿活动密钥不一致")
    findings = 0
    for root_text in args.scan_root:
        root = pathlib.Path(root_text)
        if not root.exists() or root.is_symlink():
            continue
        for directory, names, files in os.walk(root, followlinks=False):
            names[:] = [name for name in names if name not in {"mysql", ".git"}]
            for name in files:
                candidate = pathlib.Path(directory) / name
                try:
                    if candidate.is_symlink() or candidate.stat().st_size > 2 * 1024 * 1024:
                        continue
                    if b"AGE-SECRET-KEY-" in candidate.read_bytes():
                        findings += 1
                except (FileNotFoundError, PermissionError, OSError):
                    continue
    result = "PASSED" if findings == 0 else "FAILED"
    event(registry, "SERVER_PRIVATE_KEY_SCAN", str(current["keyId"]), result)
    registry["updatedAt"] = now()
    registry["lastServerPrivateKeyScan"] = {"at": registry["updatedAt"], "result": result, "findingCount": findings}
    atomic_json(path, registry)
    if findings:
        fail(f"服务器受控目录发现 {findings} 个疑似 age 私钥文件；请隔离并按交付流程销毁")
    print(json.dumps(registry["lastServerPrivateKeyScan"]))


def assert_registry_ready(registry: dict[str, object], config: pathlib.Path, require_restore_test: bool) -> None:
    current = active_key(registry)
    if env_value(config, "COPPER_BACKUP_AGE_RECIPIENT") != current.get("recipient"):
        fail("配置中的 age 公钥与登记簿活动密钥不一致")
    handover_record = current.get("handover")
    if not isinstance(handover_record, dict) or not HASH_RE.fullmatch(str(handover_record.get("recordSha256", ""))):
        fail("活动密钥缺少双人复核交接记录")
    if require_restore_test and not current.get("restoreTests"):
        fail("活动密钥尚无离线备份验证回执，不能启用自动调度")


def command_assert_ready(args: argparse.Namespace) -> None:
    registry = load_registry(pathlib.Path(args.registry))
    assert registry is not None
    assert_registry_ready(registry, pathlib.Path(args.env_file), args.require_restore_test)
    print("ready")


def command_status(args: argparse.Namespace) -> None:
    registry = load_registry(pathlib.Path(args.registry))
    assert registry is not None
    print(json.dumps(registry, ensure_ascii=False, sort_keys=True, indent=2))


def common_handover(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--handover-sha256", required=True)
    parser.add_argument("--customer-confirmed-by", required=True)
    parser.add_argument("--witness-confirmed-by", required=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Copper customer-owned backup key registry")
    parser.add_argument("--registry", required=True)
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--site-id", required=True)
    subparsers = parser.add_subparsers(dest="command", required=True)

    register = subparsers.add_parser("register")
    common_handover(register)
    register.set_defaults(function=command_register)

    prepare = subparsers.add_parser("prepare-rotation")
    prepare.add_argument("--new-recipient", required=True)
    prepare.add_argument("--challenge-dir", required=True)
    common_handover(prepare)
    prepare.set_defaults(function=command_prepare_rotation)

    activate = subparsers.add_parser("activate-rotation")
    activate.add_argument("--key-id", required=True)
    activate.add_argument("--proof-file", required=True)
    activate.add_argument("--confirm", required=True)
    activate.set_defaults(function=command_activate_rotation)

    restore_test = subparsers.add_parser("record-restore-test")
    restore_test.add_argument("--receipt", required=True)
    restore_test.add_argument("--backup-dir", required=True)
    restore_test.set_defaults(function=command_record_restore_test)

    audit = subparsers.add_parser("audit")
    audit.add_argument("--scan-root", action="append", required=True)
    audit.set_defaults(function=command_audit)

    ready = subparsers.add_parser("assert-ready")
    ready.add_argument("--require-restore-test", action="store_true")
    ready.set_defaults(function=command_assert_ready)

    status = subparsers.add_parser("status")
    status.set_defaults(function=command_status)

    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,31}", args.site_id):
        fail("siteId 格式不正确")
    args.function(args)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyLifecycleError as error:
        print(f"错误：{error}", file=os.sys.stderr)
        raise SystemExit(1)
