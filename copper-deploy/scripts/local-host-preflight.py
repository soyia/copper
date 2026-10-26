#!/usr/bin/env python3
"""Read-only M5 local-host preflight with a machine-readable fail-closed result."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import platform
import re
import shutil
import subprocess
import sys
from typing import Any


FORMAT = "copper-local-host-preflight/v1"
STATUSES = {"PASS", "FAIL", "MANUAL_REVIEW"}


class PreflightError(RuntimeError):
    pass


def run(command: list[str]) -> tuple[int, str]:
    try:
        completed = subprocess.run(command, check=False, capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return 127, ""
    return completed.returncode, completed.stdout.strip()


def os_release() -> dict[str, str]:
    result: dict[str, str] = {}
    try:
        lines = pathlib.Path("/etc/os-release").read_text(encoding="utf-8").splitlines()
    except OSError:
        return result
    for line in lines:
        if "=" not in line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        result[key] = value.strip().strip('"')
    return result


def unescape_mount(value: str) -> str:
    return value.replace("\\040", " ").replace("\\011", "\t").replace("\\134", "\\")


def nearest_existing(path: pathlib.Path) -> pathlib.Path:
    current = path
    while not current.exists() and current != current.parent:
        current = current.parent
    return current


def mount_for(path: pathlib.Path) -> dict[str, Any]:
    best: dict[str, Any] = {"mountpoint": "/", "source": "unknown", "filesystem": "unknown", "options": [], "device": "unknown"}
    try:
        lines = pathlib.Path("/proc/self/mountinfo").read_text(encoding="utf-8").splitlines()
    except OSError:
        return best
    absolute = pathlib.Path(os.path.abspath(path))
    for line in lines:
        fields = line.split()
        if "-" not in fields or len(fields) < 10:
            continue
        separator = fields.index("-")
        mountpoint = pathlib.Path(unescape_mount(fields[4]))
        try:
            relative = absolute == mountpoint or absolute.is_relative_to(mountpoint)
        except ValueError:
            relative = False
        if not relative or len(str(mountpoint)) < len(str(best["mountpoint"])):
            continue
        best = {
            "mountpoint": str(mountpoint),
            "source": unescape_mount(fields[separator + 2]),
            "filesystem": fields[separator + 1],
            "options": sorted(set(fields[5].split(",")) | set(fields[separator + 3].split(","))),
            "device": fields[2],
        }
    return best


def removable_for(device: str) -> bool | None:
    if not re.fullmatch(r"[0-9]+:[0-9]+", device):
        return None
    link = pathlib.Path("/sys/dev/block") / device
    try:
        current = link.resolve(strict=True)
    except OSError:
        return None
    for candidate in (current, *current.parents):
        removable = candidate / "removable"
        if removable.is_file():
            try:
                return removable.read_text(encoding="utf-8").strip() == "1"
            except OSError:
                return None
        if candidate == pathlib.Path("/sys"):
            break
    return None


def has_symlink_component(path: pathlib.Path) -> bool:
    absolute = pathlib.Path(os.path.abspath(path))
    current = pathlib.Path(absolute.anchor)
    for part in absolute.parts[1:]:
        current /= part
        if current.is_symlink():
            return True
        if not current.exists():
            break
    return False


def memory_bytes() -> int:
    try:
        for line in pathlib.Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
            if line.startswith("MemTotal:"):
                return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    return 0


def occupied_ports() -> tuple[bool, list[int]]:
    code, output = run(["ss", "-H", "-lntup"])
    if code != 0:
        return False, []
    result: set[int] = set()
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 5:
            continue
        match = re.search(r":([0-9]+)$", fields[4])
        if match:
            result.add(int(match.group(1)))
    return True, sorted(result)


def docker_facts() -> dict[str, Any]:
    engine_code, engine = run(["docker", "version", "--format", "{{.Server.Version}}"])
    compose_code, compose = run(["docker", "compose", "version", "--short"])
    os_code, operating_system = run(["docker", "info", "--format", "{{.OperatingSystem}}"])
    return {
        "available": engine_code == 0 and compose_code == 0,
        "engineVersion": engine,
        "composeVersion": compose,
        "operatingSystem": operating_system if os_code == 0 else "",
    }


def collect_facts(data_dir: pathlib.Path, etc_dir: pathlib.Path) -> dict[str, Any]:
    release = os_release()
    kernel = platform.release()
    virt_code, virt = run(["systemd-detect-virt"])
    systemd_running = pathlib.Path("/run/systemd/system").is_dir()
    docker_enabled_code, docker_enabled = run(["systemctl", "is-enabled", "docker.service"])
    ntp_code, ntp_value = run(["timedatectl", "show", "--property=NTPSynchronized", "--value"])
    existing = nearest_existing(data_dir)
    usage = shutil.disk_usage(existing)
    mount = mount_for(existing)
    docker = docker_facts()
    secret_dir = etc_dir / "secrets"
    try:
        data_directory_has_entries = data_dir.is_dir() and any(data_dir.iterdir())
    except OSError:
        data_directory_has_entries = True
    try:
        etc_directory_has_entries = etc_dir.is_dir() and any(etc_dir.iterdir())
    except OSError:
        etc_directory_has_entries = True
    port_inspection_available, ports = occupied_ports()
    return {
        "os": {
            "id": release.get("ID", ""),
            "versionId": release.get("VERSION_ID", ""),
            "prettyName": release.get("PRETTY_NAME", "unknown"),
            "kernelRelease": kernel,
        },
        "architecture": platform.machine(),
        "cpuCount": os.cpu_count() or 0,
        "memoryBytes": memory_bytes(),
        "virtualization": virt if virt_code in {0, 1} and virt else "none",
        "docker": docker,
        "system": {
            "systemd": systemd_running,
            "dockerEnabled": docker_enabled_code == 0 and docker_enabled == "enabled",
            "timeSynchronized": ntp_value.lower() == "yes" if ntp_code == 0 else None,
        },
        "data": {
            "path": str(data_dir),
            "existingAncestor": str(existing),
            "availableBytes": usage.free,
            "totalBytes": usage.total,
            "mountpoint": mount["mountpoint"],
            "source": mount["source"],
            "filesystem": mount["filesystem"],
            "mountOptions": mount["options"],
            "removable": removable_for(str(mount["device"])),
            "hasSymlinkComponent": has_symlink_component(data_dir),
            "dataDirectoryExists": data_dir.is_dir(),
            "dataDirectoryHasEntries": data_directory_has_entries,
            "mysqlDirectoryExists": (data_dir / "mysql").is_dir(),
            "configExists": (etc_dir / "copper.env").is_file(),
            "etcDirectoryHasEntries": etc_directory_has_entries,
            "secretsPresent": (secret_dir / "copper_db_password").is_file() and (secret_dir / "mysql_root_password").is_file(),
        },
        "portInspectionAvailable": port_inspection_available,
        "occupiedPorts": ports,
    }


def parse_version(value: str) -> tuple[int, ...]:
    match = re.fullmatch(r"([0-9]+)(?:\.([0-9]+))?(?:\.([0-9]+))?.*", value)
    if not match:
        return ()
    return tuple(int(part or 0) for part in match.groups())


def check(checks: list[dict[str, Any]], identifier: str, status: str, observed: Any, required: str, remediation: str) -> None:
    if status not in STATUSES:
        raise AssertionError(status)
    checks.append({
        "id": identifier,
        "status": status,
        "observed": observed,
        "required": required,
        "remediation": remediation if status != "PASS" else "",
    })


def evaluate(policy: dict[str, Any], profile_name: str, facts: dict[str, Any]) -> dict[str, Any]:
    profiles = policy.get("profiles", {})
    if profile_name not in profiles:
        raise PreflightError(f"未知本地主机 profile：{profile_name}")
    profile_policy = profiles[profile_name]
    checks: list[dict[str, Any]] = []
    os_facts = facts.get("os", {})
    docker = facts.get("docker", {})
    system = facts.get("system", {})
    data = facts.get("data", {})

    check(checks, "platform.os", "PASS" if os_facts.get("id") == profile_policy["osId"] and os_facts.get("versionId") == profile_policy["osVersion"] else "FAIL", f"{os_facts.get('id', 'unknown')} {os_facts.get('versionId', 'unknown')}", f"{profile_policy['osId']} {profile_policy['osVersion']}", "使用 Ubuntu Server 24.04 LTS；Windows、macOS 和其他 Linux 发行版不在支持范围")

    architecture = facts.get("architecture", "unknown")
    check(checks, "platform.architecture", "PASS" if architecture == profile_policy["architecture"] else "FAIL", architecture, profile_policy["architecture"], "更换到当前 profile 的精确 CPU 架构；禁止依赖模拟层冒充正式支持")
    check(checks, "platform.virtualization", "PASS", facts.get("virtualization", "unknown"), "记录裸机/虚拟化类型", "")

    cpu_count = int(facts.get("cpuCount", 0) or 0)
    check(checks, "resource.cpu", "PASS" if cpu_count >= int(profile_policy["minimumCpuCount"]) else "FAIL", cpu_count, f">={profile_policy['minimumCpuCount']}", "增加 CPU 配额或更换设备")
    memory = int(facts.get("memoryBytes", 0) or 0)
    check(checks, "resource.memory", "PASS" if memory >= int(profile_policy["minimumMemoryBytes"]) else "FAIL", memory, f">={profile_policy['minimumMemoryBytes']} bytes", "增加可用内存；不要依赖持续交换空间维持数据库")
    available = int(data.get("availableBytes", 0) or 0)
    check(checks, "resource.disk", "PASS" if available >= int(profile_policy["minimumAvailableBytes"]) else "FAIL", available, f">={profile_policy['minimumAvailableBytes']} bytes", "扩容本地数据盘并重新预检")

    expected_path = profile_policy["dataPath"]
    data_path = str(data.get("path", ""))
    check(checks, "data.path", "PASS" if data_path == expected_path else "FAIL", data_path, expected_path, "把可靠本地数据盘挂载到固定路径 /var/lib/copper；不要改成任意共享盘路径")
    check(checks, "data.symlink", "FAIL" if data.get("hasSymlinkComponent") else "PASS", data.get("hasSymlinkComponent"), "路径不得含符号链接", "移除符号链接，使用可审计的本地挂载点")
    filesystem = str(data.get("filesystem", "unknown")).lower()
    forbidden = set(policy.get("forbiddenDataFilesystems", []))
    allowed = set(profile_policy.get("allowedFilesystems", []))
    if filesystem in forbidden:
        filesystem_status = "FAIL"
    elif filesystem in allowed:
        filesystem_status = "PASS"
    else:
        filesystem_status = "MANUAL_REVIEW"
    check(checks, "data.filesystem", filesystem_status, filesystem, f"本地可靠文件系统：{', '.join(sorted(allowed))}", "MySQL 在线数据不得位于 SMB/NFS/同步盘；未知文件系统需专项评审")
    mount_options = set(data.get("mountOptions", []))
    check(checks, "data.readWrite", "FAIL" if "ro" in mount_options or "rw" not in mount_options else "PASS", sorted(mount_options), "读写挂载", "修复只读或异常挂载后重新预检")
    removable = data.get("removable")
    removable_status = "FAIL" if removable is True else "PASS" if removable is False else "MANUAL_REVIEW"
    check(checks, "data.removable", removable_status, removable, "不可移除的本地块存储", "无法确认磁盘身份时人工核对；USB/可移除盘只作为备份，不作在线数据库盘")
    config_exists = bool(data.get("configExists"))
    etc_has_entries = bool(data.get("etcDirectoryHasEntries"))
    data_has_entries = bool(data.get("dataDirectoryHasEntries"))
    secrets_present = bool(data.get("secretsPresent"))
    if (data_has_entries or etc_has_entries or secrets_present) and not config_exists:
        data_state, data_status = "orphaned-data", "FAIL"
        remediation = "发现已有数据但缺少站点配置；停止安装，先按备份/恢复或人工接管流程确认归属"
    elif config_exists and not secrets_present:
        data_state, data_status = "incomplete-secrets", "FAIL"
        remediation = "配置存在但数据库秘密不完整；禁止生成新秘密覆盖旧数据库，先执行恢复处置"
    elif config_exists:
        data_state, data_status, remediation = "managed-reinstall", "PASS", ""
    else:
        data_state, data_status, remediation = "fresh", "PASS", ""
    check(checks, "data.existingState", data_status, data_state, "fresh 或具有完整配置/秘密的 managed-reinstall", remediation)

    engine = str(docker.get("engineVersion", ""))
    compose = str(docker.get("composeVersion", ""))
    docker_available = bool(docker.get("available"))
    docker_ok = docker_available and parse_version(engine)[:1] == (int(profile_policy["dockerEngineMajor"]),)
    compose_ok = docker_available and parse_version(compose)[:1] == (int(profile_policy["composeMajor"]),)
    check(checks, "runtime.docker", "PASS" if docker_ok else "FAIL", engine or "unavailable", f"Docker Engine {profile_policy['dockerEngineMajor']}.x", "安装指定 Docker Engine；不得覆盖未知客户运行时")
    check(checks, "runtime.compose", "PASS" if compose_ok else "FAIL", compose or "unavailable", f"Compose Plugin {profile_policy['composeMajor']}.x", "安装指定 Compose Plugin 后重新预检")
    check(checks, "runtime.systemd", "PASS" if system.get("systemd") else "FAIL", system.get("systemd"), "systemd 可用", "使用标准 Ubuntu Server systemd 启动环境")
    check(checks, "runtime.dockerAutostart", "PASS" if system.get("dockerEnabled") else "FAIL", system.get("dockerEnabled"), "docker.service enabled", "启用 Docker 开机启动后重新预检")
    synchronized = system.get("timeSynchronized")
    time_status = "PASS" if synchronized is True else "FAIL" if synchronized is False else "MANUAL_REVIEW"
    check(checks, "system.time", time_status, synchronized, "系统时间已同步", "启用可信 NTP；时间不可信时禁止证书和正式业务启动")

    occupied = {int(port) for port in facts.get("occupiedPorts", [])}
    port_inspection_available = bool(facts.get("portInspectionAvailable"))
    check(checks, "network.portInspection", "PASS" if port_inspection_available else "FAIL", port_inspection_available, "ss 成功读取宿主机监听端口", "安装 iproute2 并确认 ss 可读取监听端口；探测失败时禁止安装")
    blocking = sorted(occupied & {int(port) for port in policy.get("blockingHostPorts", [])})
    internal = sorted(occupied & {int(port) for port in policy.get("internalReviewPorts", [])})
    check(checks, "network.publicPorts", "FAIL" if blocking else "PASS", blocking, "80/443 可用于 Copper Web", "确认端口归属；禁止自动停止未知服务")
    check(checks, "network.internalPorts", "MANUAL_REVIEW" if internal else "PASS", internal, "宿主机不监听 3306/8191，Copper 也不会映射这两个内部端口", "确认现有数据库/Backend 监听范围；不得把内部端口暴露到 LAN")

    overall = "FAIL" if any(item["status"] == "FAIL" for item in checks) else "MANUAL_REVIEW" if any(item["status"] == "MANUAL_REVIEW" for item in checks) else "PASS"
    return {
        "format": FORMAT,
        "generatedAt": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "profile": profile_name,
        "supportStatus": profile_policy["supportStatus"],
        "status": overall,
        "writeAllowed": overall == "PASS",
        "facts": {
            "os": facts.get("os", {}),
            "architecture": facts.get("architecture"),
            "cpuCount": cpu_count,
            "memoryBytes": memory,
            "virtualization": facts.get("virtualization"),
            "docker": docker,
            "system": system,
            "data": data,
            "portInspectionAvailable": port_inspection_available,
            "occupiedPorts": sorted(occupied),
        },
        "checks": checks,
    }


def print_human(document: dict[str, Any]) -> None:
    labels = {"PASS": "通过", "FAIL": "失败", "MANUAL_REVIEW": "需人工复核"}
    print(f"Copper 本地主机预检：{document['profile']}")
    for item in document["checks"]:
        print(f"[{labels[item['status']]}] {item['id']}：{item['observed']}")
        if item["remediation"]:
            print(f"       下一步：{item['remediation']}")
    print()
    print(f"结论：{document['status']}；允许写入：{'是' if document['writeAllowed'] else '否'}")


def load_json(path: pathlib.Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PreflightError(f"无法读取{label}：{path}") from exc
    if not isinstance(value, dict):
        raise PreflightError(f"{label}必须是 JSON object")
    return value


def main(argv: list[str] | None = None) -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description="Copper M5 本地主机只读预检")
    parser.add_argument("--policy", type=pathlib.Path, default=root / "config/local-host-policy.json")
    parser.add_argument("--profile", default="auto")
    parser.add_argument("--data-dir", type=pathlib.Path, default=pathlib.Path("/var/lib/copper"))
    parser.add_argument("--etc-dir", type=pathlib.Path, default=pathlib.Path("/etc/copper"))
    parser.add_argument("--facts", type=pathlib.Path, help="仅 COPPER_TEST_MODE=1 可注入测试 facts")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    policy = load_json(args.policy, "本地主机策略")
    if policy.get("format") != "copper-local-host-policy/v1":
        raise PreflightError("本地主机策略 format 不受支持")
    if args.facts:
        if os.environ.get("COPPER_TEST_MODE") != "1":
            raise PreflightError("--facts 只允许 COPPER_TEST_MODE=1")
        facts = load_json(args.facts, "测试 facts")
    else:
        facts = collect_facts(args.data_dir, args.etc_dir)
    profile_name = "ubuntu-24.04-x86_64" if args.profile == "auto" else args.profile
    document = evaluate(policy, profile_name, facts)
    if args.json:
        print(json.dumps(document, ensure_ascii=False, sort_keys=True))
    else:
        print_human(document)
    return 0 if document["status"] == "PASS" else 3


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PreflightError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        raise SystemExit(3)
