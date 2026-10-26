#!/usr/bin/env python3
"""Rotate Copper MySQL credentials without exposing values in argv or logs."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import secrets
import stat
import subprocess
import sys
import tempfile
from dataclasses import dataclass


HEX_SECRET_RE = re.compile(r"[0-9a-f]{64}")
MYSQL_ACCOUNT_RE = re.compile(r"([A-Za-z0-9_]+)@([A-Za-z0-9_.:%-]+)")


class RotationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise RotationError(message)


def parse_env(path: pathlib.Path) -> dict[str, str]:
    if path.is_symlink() or not path.is_file():
        fail("Copper 配置必须是普通文件且不能是符号链接")
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail("Copper 配置格式不正确")
        key, value = line.split("=", 1)
        if key in values:
            fail(f"Copper 配置存在重复字段：{key}")
        values[key] = value
    return values


def checked_secret(path: pathlib.Path, uid: int, gid: int) -> str:
    if path.is_symlink() or not path.is_file():
        fail(f"秘密文件不存在、不是普通文件或是符号链接：{path}")
    info = path.stat()
    if stat.S_IMODE(info.st_mode) != 0o400 or info.st_uid != uid or info.st_gid != gid:
        fail(f"秘密文件权限或属主不符合基线：{path}")
    value = path.read_text(encoding="ascii").strip()
    if not HEX_SECRET_RE.fullmatch(value):
        fail(f"秘密文件不是 64 位随机十六进制值：{path}")
    return value


def clean_text(value: str, label: str, maximum: int) -> str:
    value = value.strip()
    if not value or len(value) > maximum or any(character in value for character in "\r\n\t"):
        fail(f"{label} 必须为 1-{maximum} 个不含换行的字符")
    return value


@dataclass
class Compose:
    root: pathlib.Path
    env_file: pathlib.Path
    values: dict[str, str]

    def command(self, *arguments: str) -> list[str]:
        command = ["docker", "compose", "--env-file", str(self.env_file), "-f", str(self.root / "compose.yaml")]
        if self.values.get("COPPER_DEPLOY_MODE", "standard") == "external-proxy":
            command.extend(("-f", str(self.root / "compose.external-proxy.yaml")))
        if self.values.get("COPPER_DEPLOY_MODE", "standard") == "lan":
            command.extend(("-f", str(self.root / "compose.lan.yaml")))
        if self.values.get("COPPER_TLS_MODE", "domain") == "ip":
            command.extend(("-f", str(self.root / "compose.ip-tls.yaml")))
        command.extend(arguments)
        return command

    def run(self, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            self.command(*arguments), text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=check,
        )

    def mysql_container(self) -> str:
        result = self.run("ps", "-q", "mysql")
        container = result.stdout.strip()
        if not re.fullmatch(r"[0-9a-f]{12,64}", container):
            fail("MySQL 容器未运行或无法唯一识别")
        return container


MYSQL_CLIENT_SCRIPT = r"""
set -eu
IFS= read -r copper_password
client_file="$(mktemp)"
trap 'rm -f "$client_file"' EXIT
umask 077
printf '[client]\nuser=%s\npassword=%s\n' "$COPPER_MYSQL_USER" "$copper_password" > "$client_file"
unset copper_password
mysql --defaults-extra-file="$client_file" --batch --raw --skip-column-names
"""


def mysql_sql(container: str, user: str, password: str, sql: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    if not HEX_SECRET_RE.fullmatch(password):
        fail("拒绝使用不符合基线的数据库口令")
    return subprocess.run(
        ["docker", "exec", "-i", "-e", f"COPPER_MYSQL_USER={user}", container, "sh", "-ceu", MYSQL_CLIENT_SCRIPT],
        input=password + "\n" + sql + "\n",
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=check,
    )


def sql_account(value: str) -> tuple[str, str]:
    match = MYSQL_ACCOUNT_RE.fullmatch(value.strip())
    if not match:
        fail("MySQL 当前 root 账户格式不安全")
    return match.group(1), match.group(2)


def atomic_secret(path: pathlib.Path, value: str, uid: int, gid: int) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.rotation-", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="ascii") as writer:
            writer.write(value + "\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, 0o400)
        os.chown(temporary, uid, gid)
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


def alter_sql(app_password: str, root_password: str, root_user: str, root_host: str) -> str:
    for value in (app_password, root_password):
        if not HEX_SECRET_RE.fullmatch(value):
            fail("待写入数据库的口令不符合安全格式")
    return (
        f"ALTER USER 'copper'@'%' IDENTIFIED BY '{app_password}';\n"
        f"ALTER USER '{root_user}'@'{root_host}' IDENTIFIED BY '{root_password}';\n"
        "FLUSH PRIVILEGES;"
    )


def sql_text(value: str) -> str:
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"


def security_audit_sql(actor: str, reason: str) -> str:
    event_id = secrets.token_hex(16)
    summary = f"数据库业务/root 口令已完成受控轮换；原因：{reason}"
    return f"""USE copper_v2;
INSERT INTO security_audit_log
  (event_id, operator_user_id, operator_name, action_code, result_code, failure_code,
   session_id, target_type, target_id, target_business_key, summary, before_json, after_json,
   ip_address, trace_id, request_method, request_path, user_agent_hash,
   subject_identifier_hash, created_at, occurred_at)
VALUES
  ('{event_id}', 0, {sql_text(actor)}, 'SECRET_ROTATED', 'SUCCESS', '', NULL,
   'SYSTEM_SECURITY', 0, 'database.credentials', {sql_text(summary)}, NULL,
   JSON_OBJECT('secretIds', JSON_ARRAY('database.application', 'database.root')),
   '', '{event_id}', 'LOCAL', 'copperctl/security/secrets/rotate-database', '', '',
   CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
"""


def authenticate_root(container: str, candidates: list[str]) -> str:
    for candidate in candidates:
        result = mysql_sql(container, "root", candidate, "SELECT 1;", check=False)
        if result.returncode == 0 and result.stdout.strip() == "1":
            return candidate
    fail("新旧 root 口令均无法认证，拒绝继续自动处理")


def record_event(root: pathlib.Path, env_file: pathlib.Path, registry: pathlib.Path, event: str, secret_id: str, actor: str, reason: str) -> None:
    subprocess.run([
        str(root / "scripts" / "security-secrets.py"), "record",
        "--env-file", str(env_file), "--registry", str(registry),
        "--event", event, "--secret-id", secret_id, "--actor", actor, "--reason", reason,
    ], check=True, stdout=subprocess.DEVNULL)


def rotate(args: argparse.Namespace) -> None:
    if os.geteuid() != 0:
        fail("数据库秘密轮换必须使用 root 执行")
    root = pathlib.Path(args.root).resolve()
    env_file = pathlib.Path(args.env_file).resolve()
    values = parse_env(env_file)
    site_id = values.get("COPPER_SITE_ID", "")
    if args.confirm != site_id:
        fail("--confirm 必须与 COPPER_SITE_ID 完全一致")
    actor = clean_text(args.actor, "actor", 80)
    reason = clean_text(args.reason, "reason", 300)
    etc_dir = pathlib.Path(values.get("COPPER_ETC_DIR", "/etc/copper"))
    var_dir = pathlib.Path(values.get("COPPER_VAR_DIR", "/var/lib/copper"))
    app_path = etc_dir / "secrets" / "copper_db_password"
    root_path = etc_dir / "secrets" / "mysql_root_password"
    old_app = checked_secret(app_path, 10001, 10001)
    old_root = checked_secret(root_path, 0, 0)
    new_app = secrets.token_hex(32)
    new_root = secrets.token_hex(32)
    compose = Compose(root, env_file, values)
    container = compose.mysql_container()
    current_account = mysql_sql(container, "root", old_root, "SELECT CURRENT_USER();").stdout.strip()
    root_user, root_host = sql_account(current_account)
    if root_user != "root":
        fail("MySQL 管理连接不是 root，拒绝轮换")

    phase = "preflight"
    changed = False
    files_changed = False
    try:
        phase = "stop-services"
        compose.run("stop", "web", "backend")
        # ALTER USER statements auto-commit independently. Mark the database as possibly changed
        # before sending either statement so a partial failure always enters credential rollback.
        phase = "alter-users"
        changed = True
        mysql_sql(container, "root", old_root, alter_sql(new_app, new_root, root_user, root_host))
        phase = "verify-new-credentials"
        if mysql_sql(container, "copper", new_app, "SELECT 1;", check=False).returncode != 0:
            fail("新业务数据库口令未通过独立认证")
        if mysql_sql(container, "root", new_root, "SELECT 1;", check=False).returncode != 0:
            fail("新 root 口令未通过独立认证")
        phase = "install-secret-files"
        atomic_secret(app_path, new_app, 10001, 10001)
        atomic_secret(root_path, new_root, 0, 0)
        files_changed = True
        phase = "recreate-services"
        compose.run("up", "-d", "--force-recreate", "mysql", "backend", "web", "--wait", "--wait-timeout", "240")
        container = compose.mysql_container()
        phase = "verify-recreated-root"
        if mysql_sql(container, "root", new_root, "SELECT 1;", check=False).returncode != 0:
            fail("重建后 root 口令验证失败")
        phase = "security-audit"
        audit = subprocess.run(
            [str(root / "scripts" / "security-secrets.py"), "audit", "--env-file", str(env_file)],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        if audit.returncode != 0:
            fail("轮换后秘密安全审计未通过")
        phase = "database-audit"
        mysql_sql(container, "root", new_root, security_audit_sql(actor, reason))
    except Exception as original:
        rollback_error: Exception | None = None
        try:
            if files_changed:
                atomic_secret(app_path, old_app, 10001, 10001)
                atomic_secret(root_path, old_root, 0, 0)
            if changed:
                compose.run("up", "-d", "mysql", check=False)
                container = compose.mysql_container()
                working_root = authenticate_root(container, [new_root, old_root])
                mysql_sql(container, "root", working_root, alter_sql(old_app, old_root, root_user, root_host))
            atomic_secret(app_path, old_app, 10001, 10001)
            atomic_secret(root_path, old_root, 0, 0)
            compose.run("up", "-d", "--force-recreate", "mysql", "backend", "web", "--wait", "--wait-timeout", "240")
        except Exception as rollback:
            rollback_error = rollback
        if rollback_error:
            fail(f"数据库秘密轮换失败且自动恢复未完成；保持业务停止并立即按泄露处置流程处理。原错误={type(original).__name__}，恢复错误={type(rollback_error).__name__}")
        fail(f"数据库秘密轮换失败，旧口令和服务已恢复：phase={phase} type={type(original).__name__}")

    registry = var_dir / "security" / "secret-lifecycle.json"
    try:
        record_event(root, env_file, registry, "ROTATED", "database.application", actor, reason)
        record_event(root, env_file, registry, "ROTATED", "database.root", actor, reason)
    except subprocess.SubprocessError as error:
        fail(f"数据库口令和统一安全审计已成功，但本地生命周期登记失败；请立即补登事件：{type(error).__name__}")
    print(json.dumps({"status": "PASS", "siteId": site_id, "rotated": ["database.application", "database.root"]}, ensure_ascii=False))


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--root", required=True)
    result.add_argument("--env-file", required=True)
    result.add_argument("--confirm", required=True)
    result.add_argument("--actor", required=True)
    result.add_argument("--reason", required=True)
    return result


def main() -> int:
    try:
        rotate(parser().parse_args())
        return 0
    except (OSError, UnicodeError, subprocess.SubprocessError, RotationError) as error:
        print(f"错误：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
