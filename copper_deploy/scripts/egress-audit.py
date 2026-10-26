#!/usr/bin/env python3
"""Report Copper runtime egress without reading credential contents."""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from typing import Any


FORBIDDEN_VENDOR_CONTROLS = (
    "COPPER_LICENSE_SERVER_URL",
    "COPPER_TELEMETRY_ENDPOINT",
    "COPPER_VENDOR_CONTROL_URL",
    "COPPER_REMOTE_KILL_SWITCH_URL",
)


def load_env(path: pathlib.Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def load_policy(path: pathlib.Path) -> dict[str, Any]:
    policy = json.loads(path.read_text(encoding="utf-8"))
    if policy.get("formatVersion") != "copper-egress-policy/v1":
        raise ValueError("外联策略格式不受支持")
    if policy.get("coreBusinessInternetRequired") is not False:
        raise ValueError("外联策略不得把公共互联网设为核心业务前置条件")
    vendor = policy.get("vendorControl")
    if not isinstance(vendor, dict) or any(
        vendor.get(key) is not False
        for key in ("licenseHeartbeat", "mandatoryTelemetry", "remoteKillSwitch")
    ):
        raise ValueError("外联策略必须显式关闭厂商控制面")
    if vendor.get("officialServiceEndpoints") != []:
        raise ValueError("核心运行时不得声明 Copper 官方外联地址")
    dependencies = policy.get("conditionalDependencies")
    if not isinstance(dependencies, list) or len(dependencies) != 5:
        raise ValueError("外联策略条件依赖不完整")
    return policy


def dependency_state(identifier: str, values: dict[str, str], isolated: bool) -> tuple[str, str]:
    deploy_mode = values.get("COPPER_DEPLOY_MODE", "standard")
    tls_mode = values.get("COPPER_TLS_MODE", "domain")
    backup_targets = {item.strip() for item in values.get("COPPER_BACKUP_TARGETS", "local").split(",") if item.strip()}
    alert_mode = values.get("COPPER_MONITOR_ALERT_MODE", "local")
    image_delivery = values.get("COPPER_IMAGE_DELIVERY", "offline")
    remote_access = (
        values.get("COPPER_REMOTE_ACCESS_PROVIDER", "none") == "cloudflare"
        and values.get("COPPER_REMOTE_ACCESS_ENABLED", "no") == "yes"
    )
    active = {
        "tls.acme": deploy_mode == "standard" and tls_mode == "domain",
        "backup.s3": "s3" in backup_targets,
        "monitor.webhook": alert_mode == "webhook",
        "release.online-delivery": image_delivery == "online",
        "remote-access.cloudflare": remote_access,
    }[identifier]
    if not active:
        return "DISABLED", "当前配置未启用"
    if isolated:
        return "DEGRADED", "外网已阻断；依赖失败不得阻塞无关核心业务"
    return "ACTIVE", "当前配置启用；由客户或管理员控制目标"


def build_report(policy: dict[str, Any], values: dict[str, str], isolated: bool) -> dict[str, Any]:
    violations = [key for key in FORBIDDEN_VENDOR_CONTROLS if values.get(key)]
    dependencies = []
    for item in policy["conditionalDependencies"]:
        state, explanation = dependency_state(item["id"], values, isolated)
        dependencies.append({**item, "state": state, "explanation": explanation})
    degraded = [item["id"] for item in dependencies if item["state"] == "DEGRADED"]
    business_data_transits_provider = any(
        item["id"] == "remote-access.cloudflare" and item["state"] in {"ACTIVE", "DEGRADED"}
        for item in dependencies
    )
    return {
        "formatVersion": "copper-egress-status/v1",
        "overallLevel": "BLOCKED" if violations else ("WARNING" if degraded else "NORMAL"),
        "coreBusiness": "BLOCKED" if violations else "AVAILABLE",
        "publicInternetBlocked": isolated,
        "offlineReady": not violations,
        "vendorControl": policy["vendorControl"],
        "violations": violations,
        "degradedCapabilities": degraded,
        "conditionalDependencies": dependencies,
        "privacy": {
            "businessDetailsCollected": False,
            "secretsCollected": False,
            "businessDetailsCollectedByCopperVendor": False,
            "businessDataTransitsConditionalProvider": business_data_transits_provider,
            "secretsCollectedByAudit": False,
        },
    }


def render_text(report: dict[str, Any]) -> str:
    lines = [
        f"总体状态：{report['overallLevel']}",
        f"核心业务：{report['coreBusiness']}",
        f"公共互联网已阻断：{'是' if report['publicInternetBlocked'] else '否'}",
        "厂商许可证心跳：关闭",
        "强制遥测：关闭",
        "远程停用开关：关闭",
    ]
    for item in report["conditionalDependencies"]:
        lines.append(f"- {item['id']}: {item['state']}；{item['coreFailureImpact']}")
    if report["violations"]:
        lines.append("禁止配置：" + ", ".join(report["violations"]))
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy", required=True, type=pathlib.Path)
    parser.add_argument("--env-file", required=True, type=pathlib.Path)
    parser.add_argument("--network-isolated", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        policy = load_policy(args.policy)
        values = load_env(args.env_file)
        report = build_report(policy, values, args.network_isolated)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 3
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else render_text(report))
    return 1 if report["overallLevel"] == "BLOCKED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
