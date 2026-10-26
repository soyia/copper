#!/usr/bin/env python3
"""Validate opt-in private OCR configuration without contacting a remote service."""
import argparse
import ipaddress
import json
import re
import sys
from urllib.parse import urlsplit

OCR_KEYS = {"ocrEnabled", "ocrEndpoint", "ocrNetwork", "ocrEngine", "ocrModel", "ocrTimeoutSeconds"}


def validate(values):
    if any(not isinstance(values.get(key), str) for key in OCR_KEYS):
        raise ValueError("OCR 配置必须完整且各项均为字符串")
    enabled = values.get("ocrEnabled", "false")
    if enabled not in {"true", "false"}:
        raise ValueError("COPPER_OCR_ENABLED 仅允许 true 或 false")
    endpoint = values.get("ocrEndpoint", "")
    network = values.get("ocrNetwork", "")
    if enabled == "true" and (not endpoint or not network):
        raise ValueError("启用 OCR 前必须填写私网 COPPER_OCR_ENDPOINT 与 COPPER_OCR_NETWORK；未配置时请设为 false")
    if network and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", network):
        raise ValueError("COPPER_OCR_NETWORK 必须是已有私有 Docker 网络名称")
    if endpoint:
        try:
            url = urlsplit(endpoint)
            port = url.port
            host = url.hostname or ""
            if url.scheme not in {"http", "https"} or not host or url.username or url.password or url.query or url.fragment:
                raise ValueError()
            if not url.path.startswith("/") or any(c.isspace() for c in endpoint):
                raise ValueError()
            if port is not None and not 1 <= port <= 65535:
                raise ValueError()
            if host.lower().rstrip(".") in {"localhost", "backend", "mysql", "host.docker.internal"}:
                raise ValueError()
            try:
                address = ipaddress.ip_address(host)
            except ValueError:
                if re.fullmatch(r"(?:0[xX][0-9A-Fa-f]+|[0-9]+)(?:\.(?:0[xX][0-9A-Fa-f]+|[0-9]+))*", host):
                    raise ValueError()
                if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,252}", host):
                    raise ValueError()
            else:
                if not address.is_private or address.is_loopback or address.is_link_local or address.is_unspecified or address.is_multicast:
                    raise ValueError()
        except (ValueError, TypeError):
            raise ValueError("OCR 地址必须是独立私网服务 URL，禁止回环、数据库地址、用户口令和查询参数") from None
    for key in ("ocrEngine", "ocrModel"):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", values.get(key, "")):
            raise ValueError("OCR 引擎与模型名称只能包含字母、数字、点、下划线和短横线")
    try:
        timeout = int(values.get("ocrTimeoutSeconds", ""))
    except (ValueError, TypeError):
        timeout = 0
    if not 5 <= timeout <= 300:
        raise ValueError("COPPER_OCR_TIMEOUT_SECONDS 必须为 5 到 300 秒")


def validate_runtime(values):
    if not isinstance(values, dict):
        raise ValueError("备份中的运行配置必须是对象")
    keys = OCR_KEYS.intersection(values)
    if keys and keys != OCR_KEYS:
        raise ValueError("备份中的可选 OCR 配置必须完整保留，不能缺项")
    if keys:
        validate(values)


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    config = commands.add_parser("config")
    for name in ("enabled", "endpoint", "network", "engine", "model", "timeout"):
        config.add_argument("--" + name, required=True)
    commands.add_parser("runtime")
    network = commands.add_parser("network")
    network.add_argument("--name", required=True)
    args = parser.parse_args()
    if args.command == "config":
        validate(dict(zip(("ocrEnabled", "ocrEndpoint", "ocrNetwork", "ocrEngine", "ocrModel", "ocrTimeoutSeconds"),
                          (args.enabled, args.endpoint, args.network, args.engine, args.model, args.timeout))))
    elif args.command == "runtime":
        validate_runtime(json.load(sys.stdin))
    else:
        values = json.load(sys.stdin)
        if not isinstance(values, list) or len(values) != 1:
            raise ValueError("无法读取 OCR 私网；请先创建并连接独立 OCR 服务")
        value = values[0]
        if value.get("Name") != args.name or value.get("Internal") is not True:
            raise ValueError("OCR 网络必须实际为 internal:true；请先创建独立内部网络，不能使用可出网桥接网")
        if (value.get("Labels") or {}).get("com.docker.compose.network") == "data":
            raise ValueError("OCR 禁止共用 Copper 数据库 data 网络；请创建独立内部网络")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print("错误：" + str(error), file=sys.stderr)
        raise SystemExit(1)
