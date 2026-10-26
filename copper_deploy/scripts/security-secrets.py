#!/usr/bin/env python3
"""Audit and record the lifecycle of Copper production secrets without exposing them."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import stat
import tempfile
from typing import Any


AUDIT_FORMAT = "copper-secret-audit/v1"
REGISTRY_FORMAT = "copper-secret-lifecycle/v1"
SENSITIVE_ENV = re.compile(r"(?i)(password|passwd|pwd|token|secret|private[_-]?key|credential|api[_-]?key)")
HASH_RE = re.compile(r"[0-9a-f]{64}")
SECRET_ID_RE = re.compile(r"[a-z][a-z0-9.-]{2,63}")
EVENTS = {
    "GENERATED", "DELIVERED", "ROTATED", "REVOKED", "DESTROYED",
    "INCIDENT_OPENED", "INCIDENT_CONTAINED", "INCIDENT_CLOSED",
}


class SecretError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise SecretError(message)


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as reader:
        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def site_fingerprint(site_id: str) -> str:
    return hashlib.sha256(site_id.encode("utf-8")).hexdigest()[:16]


def parse_env(path: pathlib.Path) -> dict[str, str]:
    if path.is_symlink() or not path.is_file():
        fail(f"配置文件不存在、不是普通文件或是符号链接：{path}")
    values: dict[str, str] = {}
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"配置文件第 {line_number} 行格式不正确")
        key, value = line.split("=", 1)
        key = key.strip()
        if key in values:
            fail(f"配置文件存在重复字段：{key}")
        values[key] = value.strip()
    return values


def is_file_reference(key: str, value: str) -> bool:
    return key.endswith("_FILE") and value.startswith("/")


def secure_mode(path: pathlib.Path, expected_modes: set[int], expected_uid: int, expected_gid: int) -> str | None:
    try:
        info = path.lstat()
    except FileNotFoundError:
        return "不存在"
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        return "不是普通文件或是符号链接"
    mode = stat.S_IMODE(info.st_mode)
    if mode not in expected_modes:
        return f"权限必须为 {','.join(f'{item:04o}' for item in sorted(expected_modes))}，当前 {mode:04o}"
    if info.st_uid != expected_uid or info.st_gid != expected_gid:
        return f"属主必须为 {expected_uid}:{expected_gid}，当前 {info.st_uid}:{info.st_gid}"
    if info.st_size == 0:
        return "文件为空"
    return None


def check_directory_files(path: pathlib.Path, expected_uid: int, expected_gid: int) -> list[str]:
    failures: list[str] = []
    if path.is_symlink() or not path.is_dir():
        return [f"目录不存在、不是目录或是符号链接：{path}"]
    for entry in path.iterdir():
        error = secure_mode(entry, {0o400, 0o600}, expected_uid, expected_gid)
        if error:
            failures.append(f"{entry.name}: {error}")
    return failures


def secret_values(paths: list[pathlib.Path]) -> list[bytes]:
    values: list[bytes] = []
    for path in paths:
        try:
            if path.is_file() and not path.is_symlink() and path.stat().st_size <= 64 * 1024:
                value = path.read_bytes().strip()
                if len(value) >= 8:
                    values.append(value)
        except OSError:
            continue
    return values


def process_leaks(proc_root: pathlib.Path, secrets: list[bytes]) -> list[str]:
    failures: list[str] = []
    if not proc_root.is_dir():
        return ["无法读取进程信息"]
    for entry in proc_root.iterdir():
        if not entry.name.isdigit() or not entry.is_dir():
            continue
        for field in ("cmdline", "environ"):
            try:
                content = (entry / field).read_bytes()
            except OSError:
                continue
            if any(secret in content for secret in secrets):
                failures.append(f"进程 {entry.name} 的 {field} 包含已知秘密")
    return failures


def scan_for_offline_private_key(roots: list[pathlib.Path]) -> list[str]:
    failures: list[str] = []
    marker = b"AGE-SECRET-KEY-"
    for root in roots:
        if not root.exists() or root.is_symlink():
            continue
        for current, directories, files in os.walk(root):
            current_path = pathlib.Path(current)
            directories[:] = [name for name in directories if name not in {"mysql", "files", "backups", "logs"}]
            for name in files:
                path = current_path / name
                try:
                    if path.is_symlink() or path.stat().st_size > 1024 * 1024:
                        continue
                    if marker in path.read_bytes():
                        failures.append(f"服务器发现客户离线 age 私钥：{path}")
                except OSError:
                    continue
    return failures


def audit(args: argparse.Namespace) -> tuple[dict[str, Any], int]:
    env_path = pathlib.Path(args.env_file).resolve()
    values = parse_env(env_path)
    site_id = values.get("COPPER_SITE_ID", "")
    if not site_id:
        fail("配置缺少 COPPER_SITE_ID")
    etc_dir = pathlib.Path(values.get("COPPER_ETC_DIR", "/etc/copper"))
    var_dir = pathlib.Path(values.get("COPPER_VAR_DIR", "/var/lib/copper"))
    expected_root_uid = os.geteuid() if args.test_mode else 0
    expected_root_gid = os.getegid() if args.test_mode else 0
    expected_app = os.geteuid() if args.test_mode else 10001
    expected_app_gid = os.getegid() if args.test_mode else 10001
    checks: list[dict[str, str]] = []

    def add(identifier: str, errors: list[str]) -> None:
        checks.append({
            "id": identifier,
            "status": "FAIL" if errors else "PASS",
            "detail": "; ".join(errors) if errors else "符合安全基线",
        })

    direct_secret_keys = [
        key for key, value in values.items()
        if SENSITIVE_ENV.search(key)
        and value
        and key != "COPPER_BACKUP_AGE_RECIPIENT"
        and not is_file_reference(key, value)
        and key not in {"COPPER_COOKIE_SECURE", "COPPER_COOKIE_SAME_SITE"}
    ]
    add("environment.file-references", [f"秘密字段必须改用 _FILE：{key}" for key in direct_secret_keys])

    config_error = secure_mode(env_path, {0o600}, expected_root_uid, expected_root_gid)
    add("configuration.permissions", [config_error] if config_error else [])

    database_files = [
        etc_dir / "secrets" / "copper_db_password",
        etc_dir / "secrets" / "mysql_root_password",
    ]
    database_errors: list[str] = []
    for path, uid, gid in (
        (database_files[0], expected_app, expected_app_gid),
        (database_files[1], expected_root_uid, expected_root_gid),
    ):
        error = secure_mode(path, {0o400}, uid, gid)
        if error:
            database_errors.append(f"{path.name}: {error}")
        elif len(path.read_bytes().strip()) < 32:
            database_errors.append(f"{path.name}: 长度不足 32 字节")
    add("database.credentials", database_errors)

    external_files: list[pathlib.Path] = []
    external_errors: list[str] = []
    for directory_name in ("storage-credentials", "notification-credentials"):
        directory = etc_dir / directory_name
        if directory.exists():
            external_errors.extend(check_directory_files(directory, expected_root_uid, expected_root_gid))
            external_files.extend(item for item in directory.iterdir() if item.is_file() and not item.is_symlink())
    add("external.credentials", external_errors)

    remote_access_files: list[pathlib.Path] = []
    remote_access_errors: list[str] = []
    if values.get("COPPER_REMOTE_ACCESS_PROVIDER", "none") == "cloudflare":
        token_path = pathlib.Path(
            values.get("COPPER_CLOUDFLARE_TOKEN_FILE")
            or etc_dir / "remote-access" / "cloudflare" / "tunnel-token"
        )
        remote_access_files.append(token_path)
        token_error = secure_mode(token_path, {0o600}, expected_root_uid, expected_root_gid)
        if token_error:
            remote_access_errors.append(f"cloudflare_tunnel_token: {token_error}")
        elif len(token_path.read_bytes().strip()) < 100:
            remote_access_errors.append("cloudflare_tunnel_token: 长度异常")
    add("remote-access.credentials", remote_access_errors)

    credential_file = etc_dir / "credentials" / "initial-admin.txt"
    credential_errors: list[str] = []
    if credential_file.exists() or credential_file.is_symlink():
        error = secure_mode(credential_file, {0o600}, expected_app, expected_app_gid)
        if error:
            credential_errors.append(error)
    add("initial-admin.credential", credential_errors)

    tls_errors: list[str] = []
    tls_keys = [etc_dir / "tls" / "privkey.pem", etc_dir / "tls" / "lan-privkey.pem"]
    for tls_key in tls_keys:
        if tls_key.exists() or tls_key.is_symlink():
            error = secure_mode(tls_key, {0o400, 0o600, 0o640}, expected_root_uid, expected_app_gid)
            if error:
                tls_errors.append(f"{tls_key.name}: {error}")
    tls_archive = etc_dir / "tls" / "archive"
    if tls_archive.exists() or tls_archive.is_symlink():
        try:
            archive_info = tls_archive.lstat()
            if tls_archive.is_symlink() or not tls_archive.is_dir() or stat.S_IMODE(archive_info.st_mode) != 0o700 \
                    or archive_info.st_uid != expected_root_uid or archive_info.st_gid != expected_root_gid:
                tls_errors.append("archive: 必须是 root:root 0700 普通目录且不能是符号链接")
            else:
                for current, directories, files in os.walk(tls_archive):
                    current_path = pathlib.Path(current)
                    for name in directories:
                        directory = current_path / name
                        info = directory.lstat()
                        if directory.is_symlink() or stat.S_IMODE(info.st_mode) != 0o700 \
                                or info.st_uid != expected_root_uid or info.st_gid != expected_root_gid:
                            tls_errors.append(f"archive/{directory.relative_to(tls_archive)}: 目录必须为 root:root 0700")
                    for name in files:
                        path = current_path / name
                        error = secure_mode(path, {0o600}, expected_root_uid, expected_root_gid)
                        if error:
                            tls_errors.append(f"archive/{path.relative_to(tls_archive)}: {error}")
        except OSError as error:
            tls_errors.append(f"archive: 无法检查：{error}")
    add("tls.private-key", tls_errors)

    all_secret_files = database_files + external_files + remote_access_files + [item for item in tls_keys if item.exists()] + ([credential_file] if credential_file.exists() else [])
    leaks = process_leaks(pathlib.Path(args.proc_root), secret_values(all_secret_files))
    add("process.arguments-and-environment", leaks)

    offline_key_errors = scan_for_offline_private_key([etc_dir, var_dir / "backup-keys"])
    add("backup.customer-private-key-absent", offline_key_errors)

    status = "PASS" if all(item["status"] == "PASS" for item in checks) else "FAIL"
    document = {
        "format": AUDIT_FORMAT,
        "status": status,
        "checkedAt": now(),
        "siteFingerprint": site_fingerprint(site_id),
        "checks": checks,
    }
    return document, 0 if status == "PASS" else 1


def atomic_json(path: pathlib.Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as writer:
            json.dump(document, writer, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            writer.write("\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def load_registry(path: pathlib.Path, site_id: str) -> dict[str, Any]:
    if not path.exists():
        return {
            "format": REGISTRY_FORMAT,
            "siteFingerprint": site_fingerprint(site_id),
            "updatedAt": now(),
            "events": [],
        }
    if path.is_symlink() or not path.is_file() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        fail("秘密生命周期登记簿必须是 0600/0400 普通文件且不能是符号链接")
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("format") != REGISTRY_FORMAT or not isinstance(document.get("events"), list):
        fail("秘密生命周期登记簿格式不受支持")
    if document.get("siteFingerprint") != site_fingerprint(site_id):
        fail("秘密生命周期登记簿不属于当前站点")
    return document


def clean_text(value: str, label: str, limit: int) -> str:
    value = value.strip()
    if not value or len(value) > limit or any(character in value for character in "\r\n\t"):
        fail(f"{label} 必须为 1-{limit} 个不含换行的字符")
    return value


def record(args: argparse.Namespace) -> None:
    event_type = args.event.upper()
    if event_type not in EVENTS:
        fail("不支持的生命周期事件")
    secret_id = args.secret_id.strip().lower()
    if not SECRET_ID_RE.fullmatch(secret_id):
        fail("secret-id 必须是 3-64 位小写稳定标识")
    evidence = args.evidence_sha256.lower() if args.evidence_sha256 else None
    if evidence and not HASH_RE.fullmatch(evidence):
        fail("证据 SHA-256 格式不正确")
    env = parse_env(pathlib.Path(args.env_file))
    site_id = env.get("COPPER_SITE_ID", "")
    if not site_id:
        fail("配置缺少 COPPER_SITE_ID")
    path = pathlib.Path(args.registry)
    registry = load_registry(path, site_id)
    entry = {
        "eventId": os.urandom(16).hex(),
        "at": now(),
        "event": event_type,
        "secretId": secret_id,
        "actor": clean_text(args.actor, "actor", 80),
        "reason": clean_text(args.reason, "reason", 300),
        "evidenceSha256": evidence,
    }
    registry["events"].append(entry)
    registry["updatedAt"] = entry["at"]
    atomic_json(path, registry)
    print(json.dumps({"eventId": entry["eventId"], "event": event_type, "secretId": secret_id}, ensure_ascii=False))


def status(args: argparse.Namespace) -> None:
    env = parse_env(pathlib.Path(args.env_file))
    registry = load_registry(pathlib.Path(args.registry), env.get("COPPER_SITE_ID", ""))
    latest: dict[str, dict[str, Any]] = {}
    for item in registry["events"]:
        if isinstance(item, dict) and isinstance(item.get("secretId"), str):
            latest[item["secretId"]] = item
    safe = [
        {"secretId": key, "event": value.get("event"), "at": value.get("at"), "actor": value.get("actor")}
        for key, value in sorted(latest.items())
    ]
    print(json.dumps({"format": REGISTRY_FORMAT, "updatedAt": registry["updatedAt"], "secrets": safe}, ensure_ascii=False))


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    subcommands = result.add_subparsers(dest="command", required=True)
    audit_parser = subcommands.add_parser("audit")
    audit_parser.add_argument("--env-file", required=True)
    audit_parser.add_argument("--proc-root", default="/proc")
    audit_parser.add_argument("--json", action="store_true")
    audit_parser.add_argument("--test-mode", action="store_true")
    audit_parser.set_defaults(handler="audit")

    record_parser = subcommands.add_parser("record")
    record_parser.add_argument("--env-file", required=True)
    record_parser.add_argument("--registry", required=True)
    record_parser.add_argument("--event", required=True)
    record_parser.add_argument("--secret-id", required=True)
    record_parser.add_argument("--actor", required=True)
    record_parser.add_argument("--reason", required=True)
    record_parser.add_argument("--evidence-sha256")
    record_parser.set_defaults(handler="record")

    status_parser = subcommands.add_parser("status")
    status_parser.add_argument("--env-file", required=True)
    status_parser.add_argument("--registry", required=True)
    status_parser.set_defaults(handler="status")
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.handler == "audit":
            document, exit_code = audit(args)
            if args.json:
                print(json.dumps(document, ensure_ascii=False, separators=(",", ":")))
            else:
                for item in document["checks"]:
                    print(f"[{item['status']}] {item['id']}: {item['detail']}")
                print(f"secret_audit={document['status']}")
            return exit_code
        if args.handler == "record":
            record(args)
            return 0
        status(args)
        return 0
    except (OSError, UnicodeError, json.JSONDecodeError, SecretError) as error:
        print(f"错误：{error}", file=os.sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
