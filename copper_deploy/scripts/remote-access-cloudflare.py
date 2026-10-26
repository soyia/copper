#!/usr/bin/env python3
"""Manage the optional Cloudflare Tunnel connector without storing its token in env or argv."""

from __future__ import annotations

import argparse
import datetime as dt
import getpass
import json
import os
import pathlib
import re
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.error
import urllib.request
from typing import Any, Callable


FORMAT = "copper-cloudflare-remote-access/v1"
REMOVAL_FORMAT = "copper-cloudflare-removal/v1"
PROFILE = "remote-access-cloudflare"
DEFAULT_IMAGE = (
    "cloudflare/cloudflared@sha256:"
    "4f6655284ab3d252b7f28fedb19fe6c8fc82ee5b1295c20ac74d475e5398a52d"
)
IMAGE_RE = re.compile(r"^[a-z0-9./_-]+@sha256:[0-9a-f]{64}$")
TOKEN_RE = re.compile(r"^eyJ[A-Za-z0-9._~-]{97,}$")
METRIC_RE = re.compile(
    r"^cloudflared_tunnel_ha_connections(?:\{[^}]*\})?\s+([0-9]+(?:\.[0-9]+)?)$",
    re.MULTILINE,
)


class RemoteAccessError(RuntimeError):
    pass


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_env(path: pathlib.Path) -> dict[str, str]:
    if path.is_symlink() or not path.is_file():
        raise RemoteAccessError(f"配置文件不存在、不是普通文件或是符号链接：{path}")
    values: dict[str, str] = {}
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise RemoteAccessError(f"配置文件第 {line_number} 行格式不正确")
        key, value = line.split("=", 1)
        key = key.strip()
        if key in values:
            raise RemoteAccessError(f"配置文件存在重复字段：{key}")
        values[key] = value.strip()
    return values


def atomic_write(path: pathlib.Path, content: bytes, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.parent.is_symlink():
        raise RemoteAccessError(f"安全目录不能是符号链接：{path.parent}")
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(descriptor, mode)
        with os.fdopen(descriptor, "wb") as writer:
            writer.write(content)
            writer.flush()
            os.fsync(writer.fileno())
        os.replace(temporary, path)
        os.chmod(path, mode)
    except BaseException:
        try:
            os.close(descriptor)
        except OSError:
            pass
        pathlib.Path(temporary).unlink(missing_ok=True)
        raise


def ensure_private_directory(path: pathlib.Path) -> None:
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink() or not path.is_dir():
        raise RemoteAccessError(f"安全目录不能是符号链接且必须是目录：{path}")
    os.chmod(path, 0o700)


def update_env(path: pathlib.Path, changes: dict[str, str]) -> None:
    original_mode = stat.S_IMODE(path.stat().st_mode)
    lines = path.read_text(encoding="utf-8").splitlines()
    remaining = dict(changes)
    output: list[str] = []
    for raw in lines:
        stripped = raw.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            key = stripped.split("=", 1)[0].strip()
            if key in remaining:
                output.append(f"{key}={remaining.pop(key)}")
                continue
        output.append(raw)
    if remaining:
        if output and output[-1]:
            output.append("")
        output.append("# Optional Internet remote access; secrets remain in root-only files.")
        output.extend(f"{key}={value}" for key, value in remaining.items())
    atomic_write(path, ("\n".join(output) + "\n").encode("utf-8"), original_mode)


def validate_token(value: str) -> str:
    candidate = value.strip()
    if not TOKEN_RE.fullmatch(candidate) or any(character.isspace() for character in candidate):
        raise RemoteAccessError("Tunnel token 格式或长度异常；请从 Cloudflare Tunnel 安装命令重新复制")
    return candidate


def read_new_token(source: pathlib.Path | None, test_mode: bool) -> str:
    if source is None:
        if not sys.stdin.isatty():
            raise RemoteAccessError("非交互运行必须使用 --token-file 提供仅供本次读取的 token 文件")
        first = getpass.getpass("粘贴 Cloudflare Tunnel token（输入不回显）：")
        second = getpass.getpass("再次粘贴 token：")
        if first != second:
            raise RemoteAccessError("两次输入的 Tunnel token 不一致")
        return validate_token(first)
    if source.is_symlink() or not source.is_file():
        raise RemoteAccessError(f"token 来源必须是普通文件且不能是符号链接：{source}")
    source_info = source.stat()
    if source_info.st_size > 16 * 1024:
        raise RemoteAccessError("token 来源文件异常过大")
    if not test_mode and (
        (source_info.st_uid, source_info.st_gid) != (0, 0)
        or stat.S_IMODE(source_info.st_mode) not in {0o400, 0o600}
    ):
        raise RemoteAccessError("token 来源文件必须是 root:root 0400 或 0600")
    return validate_token(source.read_text(encoding="utf-8"))


def secure_token_file(path: pathlib.Path, test_mode: bool) -> str:
    if path.is_symlink() or not path.is_file():
        raise RemoteAccessError(f"Tunnel token 不存在、不是普通文件或是符号链接：{path}")
    info = path.stat()
    if stat.S_IMODE(info.st_mode) != 0o600:
        raise RemoteAccessError(f"Tunnel token 权限必须为 0600：{path}")
    if not test_mode and (info.st_uid, info.st_gid) != (0, 0):
        raise RemoteAccessError(f"Tunnel token 必须由 root:root 持有：{path}")
    return validate_token(path.read_text(encoding="utf-8"))


def paths(values: dict[str, str]) -> tuple[pathlib.Path, pathlib.Path, pathlib.Path]:
    etc_dir = pathlib.Path(values.get("COPPER_ETC_DIR", "/etc/copper")).resolve()
    var_dir = pathlib.Path(values.get("COPPER_VAR_DIR", "/var/lib/copper")).resolve()
    token_path = pathlib.Path(
        os.path.abspath(
            pathlib.Path(
                values.get("COPPER_CLOUDFLARE_TOKEN_FILE")
                or etc_dir / "remote-access" / "cloudflare" / "tunnel-token"
            )
        )
    )
    resolved_token = token_path.resolve()
    expected_parent = (etc_dir / "remote-access" / "cloudflare").resolve()
    try:
        resolved_token.relative_to(expected_parent)
    except ValueError as error:
        raise RemoteAccessError(f"Tunnel token 必须位于 {expected_parent}") from error
    state = var_dir / "remote-access" / "cloudflare-state.json"
    receipt = var_dir / "remote-access" / "cloudflare-removal.json"
    return token_path, state, receipt


def validate_configuration(
    values: dict[str, str], test_mode: bool, require_token: bool = True
) -> tuple[pathlib.Path, int]:
    if values.get("COPPER_REMOTE_ACCESS_PROVIDER", "none") != "cloudflare":
        raise RemoteAccessError("尚未配置 Cloudflare；请先运行 configure")
    if values.get("COPPER_DEPLOY_MODE") != "lan" or values.get("COPPER_TLS_MODE") != "lan":
        raise RemoteAccessError("Cloudflare Tunnel 一期只支持 LAN 部署模式")
    hostname = values.get("COPPER_CLOUDFLARE_HOSTNAME", "")
    if not hostname or hostname != values.get("COPPER_DOMAIN"):
        raise RemoteAccessError("互联网 hostname 必须与 COPPER_DOMAIN 相同，以保持 LAN/互联网同源")
    image = values.get("COPPER_CLOUDFLARED_IMAGE", "")
    if not IMAGE_RE.fullmatch(image):
        raise RemoteAccessError("COPPER_CLOUDFLARED_IMAGE 必须使用 registry @sha256 不可变摘要")
    if image != DEFAULT_IMAGE:
        raise RemoteAccessError("COPPER_CLOUDFLARED_IMAGE 必须使用当前发布已审核的 cloudflared 摘要")
    try:
        metrics_port = int(values.get("COPPER_CLOUDFLARE_METRICS_PORT", "20241"))
    except ValueError as error:
        raise RemoteAccessError("Cloudflare metrics 端口不是整数") from error
    try:
        restore_port = int(values.get("COPPER_RESTORE_PROXY_PORT", "18080"))
    except ValueError as error:
        raise RemoteAccessError("COPPER_RESTORE_PROXY_PORT 不是整数") from error
    reserved = {3306, 8080, 8191, restore_port}
    if not 1024 <= metrics_port <= 65535 or metrics_port in reserved:
        raise RemoteAccessError("Cloudflare metrics 端口无效或与 Copper 保留端口冲突")
    token, _, _ = paths(values)
    if require_token:
        secure_token_file(token, test_mode)
    return token, metrics_port


def run(command: list[str], timeout: int = 120) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(command, check=False, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        return subprocess.CompletedProcess(command, 127, "", str(error))


def compose_command(
    root: pathlib.Path,
    env_file: pathlib.Path,
    values: dict[str, str],
    *arguments: str,
    profile: bool = True,
) -> list[str]:
    command = [
        "docker",
        "compose",
        "--env-file",
        str(env_file),
        "-f",
        str(root / "compose.yaml"),
    ]
    mode = values.get("COPPER_DEPLOY_MODE", "standard")
    if mode == "lan":
        command.extend(["-f", str(root / "compose.lan.yaml")])
    elif mode == "external-proxy":
        command.extend(["-f", str(root / "compose.external-proxy.yaml")])
    if values.get("COPPER_TLS_MODE") == "ip":
        command.extend(["-f", str(root / "compose.ip-tls.yaml")])
    command.extend(["-f", str(root / "compose.remote-access-cloudflare.yaml")])
    if profile:
        command.extend(["--profile", PROFILE])
    command.extend(arguments)
    return command


def require_success(result: subprocess.CompletedProcess[str], message: str) -> None:
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip().splitlines()
        safe_detail = detail[-1] if detail else "命令失败且没有输出"
        raise RemoteAccessError(f"{message}：{safe_detail}")


def write_state(values: dict[str, str], state_path: pathlib.Path, status: str, detail: str) -> None:
    document = {
        "formatVersion": FORMAT,
        "updatedAt": now(),
        "siteId": values.get("COPPER_SITE_ID", ""),
        "provider": "cloudflare",
        "hostname": values.get("COPPER_CLOUDFLARE_HOSTNAME", ""),
        "status": status,
        "detail": detail,
        "tokenStored": True,
        "tokenValueRecorded": False,
    }
    atomic_write(state_path, (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))


def cloudflare_addresses(hostname: str, timeout: int = 8) -> list[str]:
    query = urllib.parse.urlencode({"name": hostname, "type": "A"})
    request = urllib.request.Request(
        f"https://cloudflare-dns.com/dns-query?{query}",
        headers={"Accept": "application/dns-json", "User-Agent": "Copper-Remote-Access-Check/1"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            document = json.loads(response.read(128 * 1024).decode("utf-8"))
    except (OSError, ValueError, urllib.error.URLError) as error:
        raise RemoteAccessError(f"无法通过公共 DNS 验证 Cloudflare 路由：{error}") from error
    addresses = sorted(
        {
            item["data"]
            for item in document.get("Answer", [])
            if isinstance(item, dict)
            and item.get("type") == 1
            and isinstance(item.get("data"), str)
        }
    )
    if not addresses:
        raise RemoteAccessError("公共 DNS 未返回 Cloudflare Tunnel hostname 的 IPv4 地址")
    return addresses


def access_gate_check(hostname: str, timeout: int = 8) -> tuple[bool, str]:
    addresses = cloudflare_addresses(hostname, timeout)
    request = (
        f"GET / HTTP/1.1\r\nHost: {hostname}\r\nUser-Agent: Copper-Remote-Access-Check/1\r\n"
        "Accept: text/html\r\nConnection: close\r\n\r\n"
    ).encode("ascii")
    last_error = ""
    for address in addresses:
        try:
            with socket.create_connection((address, 443), timeout=timeout) as raw:
                context = ssl.create_default_context()
                with context.wrap_socket(raw, server_hostname=hostname) as client:
                    client.settimeout(timeout)
                    client.sendall(request)
                    response = b""
                    while len(response) < 64 * 1024:
                        chunk = client.recv(8192)
                        if not chunk:
                            break
                        response += chunk
        except OSError as error:
            last_error = str(error)
            continue
        header = response.split(b"\r\n\r\n", 1)[0].decode("iso-8859-1", errors="replace")
        status_line = header.splitlines()[0] if header else ""
        location = ""
        for line in header.splitlines()[1:]:
            if line.lower().startswith("location:"):
                location = line.split(":", 1)[1].strip()
                break
        lowered = location.lower()
        gated = (
            status_line.startswith(("HTTP/1.1 302", "HTTP/2 302", "HTTP/1.1 303", "HTTP/2 303"))
            and ("cloudflareaccess.com" in lowered or "/cdn-cgi/access/" in lowered)
        )
        if gated:
            return True, f"{status_line} -> Cloudflare Access"
        return False, f"{status_line or '无 HTTP 状态'}；匿名访问没有进入 Cloudflare Access"
    return False, f"Cloudflare 边缘连接失败：{last_error or '没有可用地址'}"


def origin_check(
    root: pathlib.Path, env_file: pathlib.Path, values: dict[str, str]
) -> tuple[bool, str]:
    hostname = values.get("COPPER_CLOUDFLARE_HOSTNAME", "")
    result = run(
        compose_command(
            root,
            env_file,
            values,
            "exec",
            "-T",
            "web",
            "wget",
            "-q",
            "-O",
            "/dev/null",
            "--header",
            f"Host: {hostname}",
            "http://127.0.0.1:8080/readyz",
        ),
        timeout=15,
    )
    return result.returncode == 0, "Web 内部 origin 就绪" if result.returncode == 0 else "Web 内部 origin 不可用"


def connector_check(
    root: pathlib.Path, env_file: pathlib.Path, values: dict[str, str], metrics_port: int
) -> tuple[bool, str, int]:
    result = run(
        compose_command(root, env_file, values, "ps", "--status", "running", "--services"),
        timeout=15,
    )
    if result.returncode != 0 or "cloudflared" not in result.stdout.split():
        return False, "cloudflared 容器未运行", 0
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{metrics_port}/metrics", timeout=5) as response:
            metrics = response.read(1024 * 1024).decode("utf-8", errors="replace")
    except (OSError, urllib.error.URLError) as error:
        return False, f"cloudflared metrics 不可读取：{error}", 0
    connections = int(sum(float(value) for value in METRIC_RE.findall(metrics)))
    if connections <= 0:
        return False, "cloudflared 正在运行，但到 Cloudflare 的 HA 连接数为 0", 0
    return True, f"cloudflared 已连接，HA 连接数 {connections}", connections


def wait_until(
    check: Callable[[], tuple[bool, str]], description: str, timeout: int = 90
) -> str:
    deadline = time.monotonic() + timeout
    detail = ""
    while time.monotonic() < deadline:
        try:
            passed, detail = check()
        except RemoteAccessError as error:
            passed, detail = False, str(error)
        if passed:
            return detail
        time.sleep(3)
    raise RemoteAccessError(f"{description}超时：{detail}")


def configure(args: argparse.Namespace, root: pathlib.Path, env_file: pathlib.Path) -> int:
    values = parse_env(env_file)
    original_env = env_file.read_bytes()
    original_mode = stat.S_IMODE(env_file.stat().st_mode)
    if values.get("COPPER_DEPLOY_MODE") != "lan" or values.get("COPPER_TLS_MODE") != "lan":
        raise RemoteAccessError("请先完成 LAN 模式部署；Cloudflare 一期不改变标准或外部代理部署")
    hostname = args.hostname or values.get("COPPER_DOMAIN", "")
    if hostname != values.get("COPPER_DOMAIN"):
        raise RemoteAccessError("一期要求互联网 hostname 与 COPPER_DOMAIN 完全相同")
    metrics_port = args.metrics_port
    if not 1024 <= metrics_port <= 65535:
        raise RemoteAccessError("metrics 端口必须是 1024-65535")
    token_path, state_path, _ = paths(values)
    if token_path.exists() and values.get("COPPER_REMOTE_ACCESS_PROVIDER") == "cloudflare":
        raise RemoteAccessError("Cloudflare 已配置；更换 token 请使用 rotate-token")
    tunnel_credential = read_new_token(args.token_file, args.test_mode)
    ensure_private_directory(token_path.parent.parent)
    ensure_private_directory(token_path.parent)
    atomic_write(token_path, (tunnel_credential + "\n").encode("utf-8"))
    changes = {
        "COPPER_REMOTE_ACCESS_PROVIDER": "cloudflare",
        "COPPER_REMOTE_ACCESS_ENABLED": "no",
        "COPPER_CLOUDFLARED_IMAGE": DEFAULT_IMAGE,
        "COPPER_CLOUDFLARE_HOSTNAME": hostname,
        "COPPER_CLOUDFLARE_TOKEN_FILE": str(token_path),
        "COPPER_CLOUDFLARE_METRICS_PORT": str(metrics_port),
    }
    try:
        update_env(env_file, changes)
        configured = parse_env(env_file)
        validate_configuration(configured, args.test_mode)
        write_state(configured, state_path, "CONFIGURED_DISABLED", "已保存本地 connector 配置，尚未启用")
    except BaseException:
        token_path.unlink(missing_ok=True)
        atomic_write(env_file, original_env, original_mode)
        raise
    print("Cloudflare 本地连接器已配置，当前仍为停用状态。")
    print("启用前请在 Cloudflare Dashboard 完成：")
    print(f"1. 将 Public Hostname {hostname} 的 service 指向 http://web:8080")
    print("2. 为同一 hostname 创建 Cloudflare Access 应用和默认拒绝策略，再添加授权用户/组")
    print("3. 不要把 Tunnel token 或 Cloudflare API token 写入工单、脚本参数或备份")
    print("完成后返回“互联网远程访问”维护菜单，选择“启用并验证”。")
    return 0


def stop_connector(root: pathlib.Path, env_file: pathlib.Path, values: dict[str, str]) -> None:
    stop = run(compose_command(root, env_file, values, "stop", "-t", "20", "cloudflared"), timeout=45)
    if stop.returncode not in (0,):
        require_success(stop, "停止 cloudflared 失败")
    remove = run(compose_command(root, env_file, values, "rm", "-f", "cloudflared"), timeout=45)
    require_success(remove, "移除 cloudflared 容器失败")


def enable(args: argparse.Namespace, root: pathlib.Path, env_file: pathlib.Path) -> int:
    values = parse_env(env_file)
    _, metrics_port = validate_configuration(values, args.test_mode)
    image = values["COPPER_CLOUDFLARED_IMAGE"]
    inspected = run(["docker", "image", "inspect", image], timeout=30)
    if inspected.returncode != 0:
        pulled = run(["docker", "pull", image], timeout=300)
        require_success(
            pulled,
            "拉取不可变 cloudflared 镜像失败；无外网环境请先通过受控离线介质导入该摘要镜像",
        )
        require_success(run(["docker", "image", "inspect", image], timeout=30), "cloudflared 镜像摘要验证失败")
    require_success(
        run(compose_command(root, env_file, values, "config", "--quiet"), timeout=30),
        "Cloudflare Compose 配置校验失败",
    )
    try:
        require_success(
            run(
                compose_command(
                    root,
                    env_file,
                    values,
                    "up",
                    "-d",
                    "--force-recreate",
                    "--wait",
                    "--wait-timeout",
                    "240",
                    "web",
                    "cloudflared",
                ),
                timeout=270,
            ),
            "启动 Cloudflare connector 失败",
        )
        wait_until(
            lambda: origin_check(root, env_file, values),
            "Web 内部 origin 就绪检查",
            timeout=args.wait_timeout,
        )
        wait_until(
            lambda: connector_check(root, env_file, values, metrics_port)[:2],
            "Cloudflare connector 连接检查",
            timeout=args.wait_timeout,
        )
        access_detail = wait_until(
            lambda: access_gate_check(values["COPPER_CLOUDFLARE_HOSTNAME"]),
            "Cloudflare Access 默认拒绝检查",
            timeout=args.wait_timeout,
        )
    except BaseException as error:
        try:
            stop_connector(root, env_file, values)
        finally:
            update_env(env_file, {"COPPER_REMOTE_ACCESS_ENABLED": "no"})
            _, state_path, _ = paths(values)
            write_state(values, state_path, "ENABLE_FAILED_CLOSED", str(error))
        raise
    _, state_path, _ = paths(values)
    try:
        update_env(env_file, {"COPPER_REMOTE_ACCESS_ENABLED": "yes"})
        values["COPPER_REMOTE_ACCESS_ENABLED"] = "yes"
        write_state(values, state_path, "ENABLED", access_detail)
    except BaseException as error:
        try:
            stop_connector(root, env_file, values)
        finally:
            try:
                update_env(env_file, {"COPPER_REMOTE_ACCESS_ENABLED": "no"})
                values["COPPER_REMOTE_ACCESS_ENABLED"] = "no"
                write_state(
                    values,
                    state_path,
                    "ENABLE_FAILED_CLOSED",
                    "健康检查已通过，但本地启用状态未能安全落盘；connector 已停止",
                )
            except OSError:
                pass
        raise RemoteAccessError(f"远程访问状态未能安全落盘，已停止 connector：{error}") from error
    print(f"互联网远程访问已启用：https://{values['COPPER_CLOUDFLARE_HOSTNAME']}")
    print("LAN 访问仍使用原固定地址和客户 DNS；Cloudflare 故障不会停止 LAN 核心业务。")
    return 0


def build_status(
    root: pathlib.Path,
    env_file: pathlib.Path,
    values: dict[str, str],
    test_mode: bool,
) -> tuple[dict[str, Any], int]:
    provider = values.get("COPPER_REMOTE_ACCESS_PROVIDER", "none")
    enabled = values.get("COPPER_REMOTE_ACCESS_ENABLED", "no") == "yes"
    if provider == "none":
        return {
            "formatVersion": FORMAT,
            "provider": "none",
            "configured": False,
            "enabled": False,
            "overallLevel": "DISABLED",
            "coreLanImpact": "NONE",
        }, 0
    token_path, metrics_port = validate_configuration(values, test_mode, require_token=False)
    try:
        secure_token_file(token_path, test_mode)
        credential_ok, credential_detail = True, "Tunnel token 文件权限与格式正确"
    except RemoteAccessError as error:
        credential_ok, credential_detail = False, str(error)
    origin_ok, origin_detail = origin_check(root, env_file, values)
    if enabled:
        connector_ok, connector_detail, connections = connector_check(
            root, env_file, values, metrics_port
        )
        try:
            access_ok, access_detail = access_gate_check(values["COPPER_CLOUDFLARE_HOSTNAME"])
        except RemoteAccessError as error:
            access_ok, access_detail = False, str(error)
    else:
        connector_ok, connector_detail, connections = False, "配置已停用", 0
        access_ok, access_detail = False, "配置已停用，未执行公网 Access 检查"
    healthy = enabled and credential_ok and origin_ok and connector_ok and access_ok
    report = {
        "formatVersion": FORMAT,
        "provider": "cloudflare",
        "configured": True,
        "enabled": enabled,
        "hostname": values["COPPER_CLOUDFLARE_HOSTNAME"],
        "overallLevel": "HEALTHY" if healthy else ("DISABLED" if not enabled else "DEGRADED"),
        "coreLanImpact": "NONE",
        "checks": {
            "credential": {"passed": credential_ok, "detail": credential_detail},
            "origin": {"passed": origin_ok, "detail": origin_detail},
            "connector": {
                "passed": connector_ok,
                "detail": connector_detail,
                "haConnections": connections,
            },
            "accessGate": {"passed": access_ok, "detail": access_detail},
        },
        "note": "connector 已连接不等于 origin 或 Access 策略健康，三项必须分别通过",
    }
    return report, 0 if healthy or not enabled else 1


def render_status(report: dict[str, Any]) -> str:
    lines = [
        f"远程访问提供商：{report['provider']}",
        f"总体状态：{report['overallLevel']}",
        f"LAN 核心业务影响：{report['coreLanImpact']}",
    ]
    if "hostname" in report:
        lines.append(f"互联网地址：https://{report['hostname']}")
    for name, item in report.get("checks", {}).items():
        lines.append(f"- {name}: {'PASS' if item['passed'] else 'FAIL'}；{item['detail']}")
    if report.get("note"):
        lines.append(report["note"])
    return "\n".join(lines)


def status(args: argparse.Namespace, root: pathlib.Path, env_file: pathlib.Path) -> int:
    report, exit_code = build_status(root, env_file, parse_env(env_file), args.test_mode)
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else render_status(report))
    return exit_code


def disable(args: argparse.Namespace, root: pathlib.Path, env_file: pathlib.Path) -> int:
    values = parse_env(env_file)
    if values.get("COPPER_REMOTE_ACCESS_PROVIDER", "none") == "none":
        print("Cloudflare 远程访问未配置，无需停用。")
        return 0
    validate_configuration(values, args.test_mode, require_token=False)
    stop_connector(root, env_file, values)
    update_env(env_file, {"COPPER_REMOTE_ACCESS_ENABLED": "no"})
    values["COPPER_REMOTE_ACCESS_ENABLED"] = "no"
    _, state_path, _ = paths(values)
    write_state(values, state_path, "DISABLED", "connector 已停止并移除；token 和配置保留")
    print("Cloudflare connector 已停止并移除；LAN 访问、数据库、图片、备份和本地 token 均保留。")
    return 0


def rotate_token(args: argparse.Namespace, root: pathlib.Path, env_file: pathlib.Path) -> int:
    values = parse_env(env_file)
    token_path, metrics_port = validate_configuration(values, args.test_mode)
    old_token = secure_token_file(token_path, args.test_mode)
    new_token = read_new_token(args.token_file, args.test_mode)
    if new_token == old_token:
        raise RemoteAccessError("新 token 与现有 token 相同，未执行轮换")
    enabled = values.get("COPPER_REMOTE_ACCESS_ENABLED") == "yes"
    atomic_write(token_path, (new_token + "\n").encode("utf-8"))
    try:
        if enabled:
            require_success(
                run(
                    compose_command(
                        root,
                        env_file,
                        values,
                        "up",
                        "-d",
                        "--force-recreate",
                        "cloudflared",
                    ),
                    timeout=120,
                ),
                "使用新 token 重建 connector 失败",
            )
            wait_until(
                lambda: connector_check(root, env_file, values, metrics_port)[:2],
                "新 token 连接检查",
                timeout=args.wait_timeout,
            )
            wait_until(
                lambda: access_gate_check(values["COPPER_CLOUDFLARE_HOSTNAME"]),
                "新 token 的 Cloudflare Access 检查",
                timeout=args.wait_timeout,
            )
    except BaseException as error:
        atomic_write(token_path, (old_token + "\n").encode("utf-8"))
        rollback_ok = True
        if enabled:
            rollback = run(
                compose_command(
                    root,
                    env_file,
                    values,
                    "up",
                    "-d",
                    "--force-recreate",
                    "cloudflared",
                ),
                timeout=120,
            )
            rollback_ok = rollback.returncode == 0
            if rollback_ok:
                try:
                    wait_until(
                        lambda: connector_check(root, env_file, values, metrics_port)[:2],
                        "旧 token connector 恢复检查",
                        timeout=args.wait_timeout,
                    )
                    wait_until(
                        lambda: access_gate_check(values["COPPER_CLOUDFLARE_HOSTNAME"]),
                        "旧 token Cloudflare Access 恢复检查",
                        timeout=args.wait_timeout,
                    )
                except RemoteAccessError:
                    rollback_ok = False
        _, state_path, _ = paths(values)
        if rollback_ok:
            write_state(
                values,
                state_path,
                "ENABLED" if enabled else "CONFIGURED_DISABLED",
                "新 token 验证失败，已恢复旧 token",
            )
            raise
        try:
            stop_connector(root, env_file, values)
        except RemoteAccessError:
            pass
        update_env(env_file, {"COPPER_REMOTE_ACCESS_ENABLED": "no"})
        values["COPPER_REMOTE_ACCESS_ENABLED"] = "no"
        write_state(
            values,
            state_path,
            "ROTATE_FAILED_CLOSED",
            "新 token 验证失败，旧 token connector 也未能恢复；已标记停用",
        )
        raise RemoteAccessError(
            f"token 轮换失败且旧连接器未能恢复，远程访问已标记停用：{error}"
        ) from error
    _, state_path, _ = paths(values)
    write_state(values, state_path, "ENABLED" if enabled else "CONFIGURED_DISABLED", "Tunnel token 已轮换")
    print("Cloudflare Tunnel token 已原子轮换；token 内容未写入日志、参数或状态文件。")
    return 0


def remove(args: argparse.Namespace, root: pathlib.Path, env_file: pathlib.Path) -> int:
    values = parse_env(env_file)
    site_id = values.get("COPPER_SITE_ID", "")
    expected = f"{site_id}:REMOVE-CLOUDFLARE"
    if args.confirm != expected:
        raise RemoteAccessError(f"永久移除本地 Cloudflare 配置必须输入 --confirm {expected}")
    provider = values.get("COPPER_REMOTE_ACCESS_PROVIDER", "none")
    token_path, state_path, receipt_path = paths(values)
    if provider == "cloudflare":
        validate_configuration(values, args.test_mode, require_token=False)
        stop_connector(root, env_file, values)
    token_path.unlink(missing_ok=True)
    state_path.unlink(missing_ok=True)
    try:
        token_path.parent.rmdir()
        token_path.parent.parent.rmdir()
    except OSError:
        pass
    update_env(
        env_file,
        {
            "COPPER_REMOTE_ACCESS_PROVIDER": "none",
            "COPPER_REMOTE_ACCESS_ENABLED": "no",
            "COPPER_CLOUDFLARE_HOSTNAME": "",
            "COPPER_CLOUDFLARE_TOKEN_FILE": "",
        },
    )
    receipt = {
        "formatVersion": REMOVAL_FORMAT,
        "removedAt": now(),
        "siteId": site_id,
        "localConnectorRemoved": True,
        "localTokenDestroyed": True,
        "copperBusinessDataPreserved": True,
        "copperBackupsPreserved": True,
        "cloudCleanup": "PENDING_CUSTOMER_ACTION",
        "tokenValueRecorded": False,
    }
    atomic_write(receipt_path, (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    print("本地 cloudflared 容器、token 和远程访问配置已移除。Copper 数据、图片、镜像和备份未删除。")
    print("Cloudflare 云端资源没有自动删除：请由客户登录 Dashboard 删除 Tunnel、Public Hostname 和 Access 应用/策略。")
    return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="管理 Copper 的 Cloudflare 互联网远程访问")
    result.add_argument("--env-file", required=True, type=pathlib.Path)
    result.add_argument("--root", required=True, type=pathlib.Path)
    result.add_argument("--test-mode", action="store_true", help=argparse.SUPPRESS)
    subcommands = result.add_subparsers(dest="command", required=True)

    configure_parser = subcommands.add_parser("configure")
    configure_parser.add_argument("--hostname")
    configure_parser.add_argument("--token-file", type=pathlib.Path)
    configure_parser.add_argument("--metrics-port", type=int, default=20241)
    enable_parser = subcommands.add_parser("enable")
    enable_parser.add_argument("--wait-timeout", type=int, default=90)
    status_parser = subcommands.add_parser("status")
    status_parser.add_argument("--json", action="store_true")
    rotate_parser = subcommands.add_parser("rotate-token")
    rotate_parser.add_argument("--token-file", type=pathlib.Path)
    rotate_parser.add_argument("--wait-timeout", type=int, default=90)
    subcommands.add_parser("disable")
    remove_parser = subcommands.add_parser("remove")
    remove_parser.add_argument("--confirm", required=True)
    return result


def main() -> int:
    args = parser().parse_args()
    args.test_mode = args.test_mode or os.environ.get("COPPER_TEST_MODE") == "1"
    if os.geteuid() != 0 and not args.test_mode:
        print("错误：远程访问命令需要读取 root-only token，请使用 sudo 执行", file=sys.stderr)
        return 2
    handlers = {
        "configure": configure,
        "enable": enable,
        "status": status,
        "rotate-token": rotate_token,
        "disable": disable,
        "remove": remove,
    }
    try:
        return handlers[args.command](args, args.root.resolve(), args.env_file.resolve())
    except RemoteAccessError as error:
        print(f"错误：{error}", file=sys.stderr)
        return 3
    except OSError as error:
        print(f"错误：本地文件或进程操作失败：{error}", file=sys.stderr)
        return 3
    except KeyboardInterrupt:
        print("错误：操作已由用户中断", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
