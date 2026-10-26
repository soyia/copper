#!/usr/bin/env python3
"""Fail-closed LAN identity, addressing, certificate and firewall preflight."""

from __future__ import annotations

import argparse
import datetime as dt
import email.utils
import ipaddress
import json
import os
import pathlib
import re
import secrets
import socket
import stat
import struct
import subprocess
import sys
from typing import Any


FORMAT = "copper-lan-access-preflight/v1"
RFC1918 = tuple(ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
RESERVED_SUFFIXES = (".local", ".localhost", ".invalid", ".example", ".test")
CERTIFICATE_MINIMUM_SECONDS = 30 * 86400


def run(command: list[str], timeout: int = 10, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env,
        )
    except (OSError, subprocess.TimeoutExpired):
        return subprocess.CompletedProcess(command, 127, "", "")


def parse_env(path: pathlib.Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ValueError(f"无法读取配置文件 {path}：{error}") from error
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def private_ipv4(value: str) -> bool:
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        return False
    return address.version == 4 and any(address in network for network in RFC1918)


def valid_domain(value: str) -> bool:
    lowered = value.lower().rstrip(".")
    if len(lowered) > 253 or "." not in lowered or lowered.endswith(RESERVED_SUFFIXES):
        return False
    try:
        ipaddress.ip_address(lowered)
        return False
    except ValueError:
        pass
    labels = lowered.split(".")
    return all(re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in labels)


def parse_cidrs(value: str) -> tuple[list[str], bool]:
    canonical: list[str] = []
    try:
        for item in value.split(","):
            item = item.strip()
            if not item:
                continue
            network = ipaddress.ip_network(item, strict=True)
            if network.version != 4 or not any(network.subnet_of(parent) for parent in RFC1918):
                return [], False
            canonical.append(str(network))
    except ValueError:
        return [], False
    return sorted(set(canonical)), bool(canonical)


def permission(path: pathlib.Path) -> str:
    try:
        value = path.stat()
    except OSError:
        return "missing"
    return f"{value.st_uid}:{value.st_gid} {stat.S_IMODE(value.st_mode):03o}"


def interface_addresses(interface: str) -> tuple[bool, list[str]]:
    result = run(["ip", "-j", "address", "show", "dev", interface])
    if result.returncode != 0:
        return False, []
    try:
        document = json.loads(result.stdout)
        addresses = [
            item["local"]
            for link in document
            for item in link.get("addr_info", [])
            if item.get("family") == "inet" and isinstance(item.get("local"), str)
        ]
    except (ValueError, TypeError, KeyError):
        return False, []
    return True, sorted(set(addresses))


def skip_dns_name(message: bytes, offset: int) -> int:
    while offset < len(message):
        length = message[offset]
        if length & 0xC0 == 0xC0:
            if offset + 1 >= len(message):
                raise ValueError("truncated DNS compression pointer")
            return offset + 2
        offset += 1
        if length == 0:
            return offset
        if length > 63 or offset + length > len(message):
            raise ValueError("invalid DNS label")
        offset += length
    raise ValueError("truncated DNS name")


def resolved_addresses(domain: str, dns_server: str) -> tuple[bool, list[str]]:
    transaction = secrets.randbelow(65536)
    try:
        question_name = b"".join(bytes((len(label),)) + label.encode("ascii") for label in domain.split(".")) + b"\x00"
        query = struct.pack("!HHHHHH", transaction, 0x0100, 1, 0, 0, 0) + question_name + struct.pack("!HH", 1, 1)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.settimeout(3)
            client.sendto(query, (dns_server, 53))
            message, peer = client.recvfrom(4096)
        if peer[0] != dns_server or len(message) < 12:
            return False, []
        response_id, flags, questions, answers, _, _ = struct.unpack("!HHHHHH", message[:12])
        if response_id != transaction or flags & 0x8000 == 0 or flags & 0x000F != 0 or flags & 0x0200:
            return False, []
        offset = 12
        for _ in range(questions):
            offset = skip_dns_name(message, offset)
            offset += 4
        values: set[str] = set()
        for _ in range(answers):
            offset = skip_dns_name(message, offset)
            if offset + 10 > len(message):
                raise ValueError("truncated DNS answer")
            record_type, record_class, _, length = struct.unpack("!HHIH", message[offset:offset + 10])
            offset += 10
            if offset + length > len(message):
                raise ValueError("truncated DNS record")
            if record_type == 1 and record_class == 1 and length == 4:
                values.add(socket.inet_ntoa(message[offset:offset + 4]))
            offset += length
        return bool(values), sorted(values)
    except (OSError, UnicodeError, ValueError, struct.error):
        return False, []


def system_resolved_addresses(domain: str) -> tuple[bool, list[str]]:
    try:
        values = {
            value[4][0]
            for value in socket.getaddrinfo(domain, 443, family=socket.AF_INET, type=socket.SOCK_STREAM)
        }
    except (OSError, socket.gaierror):
        return False, []
    return True, sorted(values)


def hosts_overrides(domain: str) -> list[str]:
    matches: list[str] = []
    try:
        lines = pathlib.Path("/etc/hosts").read_text(encoding="utf-8").splitlines()
    except OSError:
        return ["/etc/hosts unreadable"]
    wanted = domain.lower().rstrip(".")
    for line_number, raw in enumerate(lines, 1):
        fields = raw.split("#", 1)[0].split()
        if len(fields) >= 2 and wanted in {item.lower().rstrip(".") for item in fields[1:]}:
            matches.append(f"line {line_number}: {fields[0]}")
    return matches


def certificate_facts(domain: str, tls_dir: pathlib.Path) -> dict[str, Any]:
    fullchain = tls_dir / "lan-fullchain.pem"
    private_key = tls_dir / "lan-privkey.pem"
    trust_anchor = tls_dir / "lan-ca.pem"
    result: dict[str, Any] = {
        "present": all(item.is_file() and not item.is_symlink() for item in (fullchain, private_key, trust_anchor)),
        "permissions": {
            "fullchain": permission(fullchain),
            "privateKey": permission(private_key),
            "trustAnchor": permission(trust_anchor),
        },
        "hostnameMatches": False,
        "keyMatches": False,
        "chainTrusted": False,
        "selfSignedLeaf": None,
        "validForMinimumDays": False,
        "trustAnchorIsCa": False,
        "trustAnchorValidForMinimumDays": False,
        "daysRemaining": None,
        "trustAnchorSha256": None,
    }
    if not result["present"]:
        return result

    result["hostnameMatches"] = run(
        ["openssl", "x509", "-in", str(fullchain), "-noout", "-checkhost", domain]
    ).returncode == 0
    certificate_key = run(["openssl", "x509", "-in", str(fullchain), "-noout", "-pubkey"])
    private_public = run(["openssl", "pkey", "-in", str(private_key), "-pubout"])
    result["keyMatches"] = (
        certificate_key.returncode == 0
        and private_public.returncode == 0
        and certificate_key.stdout.strip() == private_public.stdout.strip()
    )
    result["chainTrusted"] = run(
        ["openssl", "verify", "-CAfile", str(trust_anchor), "-untrusted", str(fullchain), str(fullchain)]
    ).returncode == 0
    anchor_constraints = run(["openssl", "x509", "-in", str(trust_anchor), "-noout", "-ext", "basicConstraints"])
    result["trustAnchorIsCa"] = anchor_constraints.returncode == 0 and "CA:TRUE" in anchor_constraints.stdout
    result["trustAnchorValidForMinimumDays"] = run(
        ["openssl", "x509", "-in", str(trust_anchor), "-noout", "-checkend", str(CERTIFICATE_MINIMUM_SECONDS)]
    ).returncode == 0
    identity = run(["openssl", "x509", "-in", str(fullchain), "-noout", "-subject", "-issuer", "-enddate"])
    if identity.returncode == 0:
        subject = issuer = not_after_text = ""
        for line in identity.stdout.splitlines():
            if line.startswith("subject="):
                subject = line.split("=", 1)[1].strip()
            elif line.startswith("issuer="):
                issuer = line.split("=", 1)[1].strip()
            elif line.startswith("notAfter="):
                not_after_text = line.split("=", 1)[1].strip()
        result["selfSignedLeaf"] = bool(subject and subject == issuer)
        try:
            not_after = email.utils.parsedate_to_datetime(not_after_text).astimezone(dt.timezone.utc)
            remaining = (not_after - dt.datetime.now(dt.timezone.utc)).total_seconds()
            result["daysRemaining"] = round(remaining / 86400, 3)
            result["validForMinimumDays"] = remaining >= CERTIFICATE_MINIMUM_SECONDS
        except (TypeError, ValueError):
            pass
    anchor_fingerprint = run(["openssl", "x509", "-in", str(trust_anchor), "-noout", "-fingerprint", "-sha256"])
    if anchor_fingerprint.returncode == 0 and "=" in anchor_fingerprint.stdout:
        result["trustAnchorSha256"] = anchor_fingerprint.stdout.split("=", 1)[1].strip().replace(":", "").lower()
    return result


def firewall_installed(root: pathlib.Path, env_file: pathlib.Path) -> tuple[bool, str]:
    environment = {**os.environ, "COPPER_ENV_FILE": str(env_file)}
    result = run([str(root / "scripts" / "lan-firewall.sh"), "status", "--quiet"], env=environment)
    return result.returncode == 0, result.stderr.strip()[:240]


def collect_facts(root: pathlib.Path, env_file: pathlib.Path, values: dict[str, str]) -> dict[str, Any]:
    interface = values.get("COPPER_LAN_INTERFACE", "")
    domain = values.get("COPPER_DOMAIN", "")
    dns_server = values.get("COPPER_LAN_DNS_SERVER", "")
    etc_dir = pathlib.Path(values.get("COPPER_ETC_DIR", "/etc/copper"))
    interface_available, addresses = interface_addresses(interface)
    dns_available, resolved = resolved_addresses(domain, dns_server) if private_ipv4(dns_server) else (False, [])
    system_dns_available, system_resolved = system_resolved_addresses(domain)
    firewall, firewall_error = firewall_installed(root, env_file)
    return {
        "interfaceAvailable": interface_available,
        "interfaceAddresses": addresses,
        "dnsAvailable": dns_available,
        "resolvedAddresses": resolved,
        "systemDnsAvailable": system_dns_available,
        "systemResolvedAddresses": system_resolved,
        "hostsOverrides": hosts_overrides(domain),
        "certificate": certificate_facts(domain, etc_dir / "tls"),
        "firewallInstalled": firewall,
        "firewallError": firewall_error,
    }


def add_check(checks: list[dict[str, Any]], identifier: str, passed: bool, observed: Any, required: str, remediation: str) -> None:
    checks.append({
        "id": identifier,
        "status": "PASS" if passed else "FAIL",
        "observed": observed,
        "required": required,
        "remediation": "" if passed else remediation,
    })


def evaluate(values: dict[str, str], facts: dict[str, Any], require_firewall: bool = False) -> dict[str, Any]:
    checks: list[dict[str, Any]] = []
    domain = values.get("COPPER_DOMAIN", "").lower().rstrip(".")
    bind_ip = values.get("COPPER_LAN_BIND_IP", "")
    interface = values.get("COPPER_LAN_INTERFACE", "")
    dns_server = values.get("COPPER_LAN_DNS_SERVER", "")
    cidrs, cidrs_valid = parse_cidrs(values.get("COPPER_LAN_ALLOWED_CIDRS", ""))
    add_check(checks, "mode.deploy", values.get("COPPER_DEPLOY_MODE") == "lan", values.get("COPPER_DEPLOY_MODE"), "lan", "设置 COPPER_DEPLOY_MODE=lan")
    add_check(checks, "mode.tls", values.get("COPPER_TLS_MODE") == "lan", values.get("COPPER_TLS_MODE"), "lan", "设置 COPPER_TLS_MODE=lan")
    add_check(checks, "address.privateIpv4", private_ipv4(bind_ip), bind_ip, "RFC1918 私网 IPv4", "使用 10/8、172.16/12 或 192.168/16 内的固定地址")
    add_check(checks, "address.reserved", values.get("COPPER_LAN_ADDRESS_RESERVED") == "yes", values.get("COPPER_LAN_ADDRESS_RESERVED"), "yes", "先在路由器设置 DHCP 保留或在 Ubuntu 配置受控静态地址")
    add_check(checks, "address.interface", bool(interface) and facts.get("interfaceAvailable") is True, interface, "存在的专用 LAN 网卡", "填写承载局域网访问的 Ubuntu 网卡名")
    add_check(checks, "address.bound", bind_ip in facts.get("interfaceAddresses", []), facts.get("interfaceAddresses", []), bind_ip, "让指定私网地址真实配置在 COPPER_LAN_INTERFACE 上")
    add_check(checks, "name.domain", valid_domain(domain), domain, "客户控制域名，禁止 .local/localhost/example/test", "使用客户控制域名的内网专用子域名")
    add_check(checks, "name.control", values.get("COPPER_LAN_DOMAIN_CONTROL_CONFIRMED") == "yes", values.get("COPPER_LAN_DOMAIN_CONTROL_CONFIRMED"), "yes", "确认客户控制域名和内网 DNS 后设置为 yes")
    add_check(checks, "name.strategy", values.get("COPPER_LAN_NAME_RESOLUTION") == "dns", values.get("COPPER_LAN_NAME_RESOLUTION"), "dns", "正式多人模式使用路由器 DNS 或客户内部 DNS；mDNS/hosts 只可排障")
    add_check(checks, "name.dnsServer", private_ipv4(dns_server), dns_server, "客户受控 RFC1918 DNS 服务器", "填写路由器或客户内部 DNS 的私网 IPv4，禁止依赖 hosts")
    add_check(checks, "name.noHostsOverride", facts.get("hostsOverrides", []) == [], facts.get("hostsOverrides", []), [], "删除该正式域名的 /etc/hosts 临时记录，使用受控 DNS")
    resolved = facts.get("resolvedAddresses", [])
    add_check(checks, "name.authoritativeResolution", facts.get("dnsAvailable") is True and resolved == [bind_ip], resolved, [bind_ip], "在 COPPER_LAN_DNS_SERVER 修正 A 记录，确保唯一指向固定私网地址")
    system_resolved = facts.get("systemResolvedAddresses", [])
    add_check(checks, "name.clientResolution", facts.get("systemDnsAvailable") is True and system_resolved == [bind_ip], system_resolved, [bind_ip], "让 Ubuntu 系统解析也使用客户受控 DNS；临时 hosts 不能关闭此门槛")
    add_check(checks, "firewall.cidrs", cidrs_valid, cidrs, "一个或多个 RFC1918 严格网段", "填写逗号分隔的严格 CIDR，不得使用 0.0.0.0/0")
    try:
        bind_address = ipaddress.ip_address(bind_ip)
        bind_allowed = cidrs_valid and any(bind_address in ipaddress.ip_network(value) for value in cidrs)
    except ValueError:
        bind_allowed = False
    add_check(checks, "firewall.bindIpCovered", bind_allowed, bind_ip, "绑定地址位于允许网段", "修正 COPPER_LAN_ALLOWED_CIDRS")
    certificate = facts.get("certificate", {})
    add_check(checks, "tls.files", certificate.get("present") is True, certificate.get("present"), "证书链、私钥和信任锚均存在且不是符号链接", "运行 copperctl lan certificate install")
    expected_permissions = {"fullchain": "0:10001 640", "privateKey": "0:10001 640", "trustAnchor": "0:0 644"}
    add_check(checks, "tls.permissions", certificate.get("permissions") == expected_permissions, certificate.get("permissions"), expected_permissions, "重新安装证书，禁止放宽私钥权限")
    add_check(checks, "tls.hostname", certificate.get("hostnameMatches") is True, certificate.get("hostnameMatches"), domain, "重新签发包含当前 DNS SAN 的叶证书")
    add_check(checks, "tls.privateKey", certificate.get("keyMatches") is True, certificate.get("keyMatches"), True, "使用与叶证书匹配的私钥")
    add_check(checks, "tls.chain", certificate.get("chainTrusted") is True, certificate.get("chainTrusted"), True, "提供签发该叶证书的客户 CA 信任锚和完整链")
    add_check(checks, "tls.trustAnchorCa", certificate.get("trustAnchorIsCa") is True, certificate.get("trustAnchorIsCa"), True, "信任锚必须是 basicConstraints CA:TRUE 的客户根 CA")
    add_check(checks, "tls.trustAnchorValidity", certificate.get("trustAnchorValidForMinimumDays") is True, certificate.get("trustAnchorValidForMinimumDays"), True, "客户根 CA 有效期不足 30 天；先完成新旧根 CA 过渡")
    add_check(checks, "tls.leaf", certificate.get("selfSignedLeaf") is False, certificate.get("selfSignedLeaf"), False, "正式多人模式禁止单张自签名叶证书；使用自签名根 CA 签发可轮换叶证书")
    add_check(checks, "tls.validity", certificate.get("validForMinimumDays") is True, certificate.get("daysRemaining"), ">=30 days", "在到期前重新签发并执行受控轮换")
    if require_firewall:
        add_check(checks, "firewall.runtime", facts.get("firewallInstalled") is True, facts.get("firewallError") or facts.get("firewallInstalled"), True, "运行 copperctl lan firewall apply；Docker 必须提供 DOCKER-USER 链")
    status = "PASS" if all(item["status"] == "PASS" for item in checks) else "FAIL"
    return {
        "format": FORMAT,
        "status": status,
        "writeAllowed": status == "PASS",
        "domain": domain,
        "bindIp": bind_ip,
        "interface": interface,
        "dnsServer": dns_server,
        "allowedCidrs": cidrs,
        "checks": checks,
    }


def render_human(document: dict[str, Any]) -> None:
    print("Copper 局域网安全预检")
    for item in document["checks"]:
        marker = "通过" if item["status"] == "PASS" else "失败"
        print(f"[{marker}] {item['id']}: {item['observed']}")
        if item["remediation"]:
            print(f"       下一步：{item['remediation']}")
    print(f"结论：{document['status']}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("preflight", "status"))
    parser.add_argument("--env-file", default=os.environ.get("COPPER_ENV_FILE", "/etc/copper/copper.env"))
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    env_file = pathlib.Path(args.env_file)
    try:
        values = parse_env(env_file)
        facts = collect_facts(root, env_file, values)
        document = evaluate(values, facts, require_firewall=args.command == "status")
    except ValueError as error:
        print(f"错误：{error}", file=sys.stderr)
        return 3
    if args.json:
        print(json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        render_human(document)
    return 0 if document["status"] == "PASS" else 3


if __name__ == "__main__":
    raise SystemExit(main())
