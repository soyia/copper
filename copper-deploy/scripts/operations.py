#!/usr/bin/env python3
"""Inspect Copper log caps, capacity protection and recoverability objectives."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import re
import shutil
import stat
import subprocess
import sys
from typing import Any


FORMAT = "copper-operations-status/v1"
POLICY_FORMAT = "copper-operations-policy/v1"
LEVELS = {"NORMAL": 0, "WARNING": 1, "CRITICAL": 2, "BLOCKED": 3}
LEVEL_ZH = {"NORMAL": "正常", "WARNING": "预警", "CRITICAL": "严重", "BLOCKED": "阻断"}


class OperationsError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise OperationsError(message)


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def iso(value: dt.datetime) -> str:
    return value.astimezone(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_time(value: object) -> dt.datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def parse_env(path: pathlib.Path) -> dict[str, str]:
    if not path.is_file() or path.is_symlink():
        fail("Copper 配置文件不存在、不是普通文件或是符号链接")
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if re.fullmatch(r"[A-Z][A-Z0-9_]*", key.strip()):
            values[key.strip()] = value.strip()
    return values


def secure_json(path: pathlib.Path, expected_format: str | None = None) -> dict[str, Any] | None:
    if not path.exists():
        return None
    if not path.is_file() or path.is_symlink() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        return None
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(document, dict) or (expected_format and document.get("formatVersion") != expected_format):
        return None
    return document


def load_policy(path: pathlib.Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"运维策略无法读取：{exc}")
    if not isinstance(document, dict) or document.get("formatVersion") != POLICY_FORMAT:
        fail("运维策略格式不受支持")
    logs = document.get("logClasses")
    required = {"gateway", "database", "applicationConsole", "applicationFile", "audit", "operations"}
    if not isinstance(logs, dict) or set(logs) != required:
        fail("运维策略日志分类不完整")
    baseline = document.get("recoveryObjectives", {}).get("engineeringBaseline", {})
    if baseline.get("scheduledBackupIntervalHours") != 24 or baseline.get("technicalRestoreTargetMinutes") != 240:
        fail("运维策略 RPO/RTO 工程基线被意外改变")
    if set(document.get("incidentChecklists", {})) != {"database", "disk", "backup", "certificate", "time", "power"}:
        fail("故障处置清单不完整")
    return document


def directory_bytes(path: pathlib.Path) -> int:
    if not path.exists() or path.is_symlink():
        return 0
    total = 0
    for root, directories, files in os.walk(path, followlinks=False):
        directories[:] = [name for name in directories if not (pathlib.Path(root) / name).is_symlink()]
        for name in files:
            item = pathlib.Path(root) / name
            try:
                if item.is_file() and not item.is_symlink():
                    total += item.stat().st_size
            except (FileNotFoundError, PermissionError, OSError):
                continue
    return total


def static_enforcement(root: pathlib.Path) -> dict[str, object]:
    compose = (root / "compose.yaml").read_text(encoding="utf-8")
    checks = {
        "dockerLocalDriver": "driver: local" in compose,
        "dockerMaxSize20MiB": "max-size: 20m" in compose,
        "dockerMaxFiles5": 'max-file: "5"' in compose,
        "applicationMaxFile20MiB": "LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE: 20MB" in compose,
        "applicationMaxHistory7Days": 'LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY: "7"' in compose,
        "applicationTotalCap200MiB": "LOGGING_LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP: 200MB" in compose,
        "mysqlRowBinaryLogEnabled": all(
            value in compose
            for value in (
                "--log-bin=/var/lib/mysql/binlog",
                "--binlog-format=ROW",
                "--binlog-row-image=FULL",
                "--binlog-expire-logs-seconds=2592000",
            )
        ),
        "mysqlGeneralLogDisabled": "--general-log=OFF" in compose,
        "mysqlSlowLogDisabled": "--slow-query-log=OFF" in compose,
    }
    return {"status": "PASS" if all(checks.values()) else "FAIL", "checks": checks}


def compose_prefix(root: pathlib.Path, env_file: pathlib.Path, env: dict[str, str]) -> list[str]:
    command = ["docker", "compose", "--env-file", str(env_file), "-f", str(root / "compose.yaml")]
    if env.get("COPPER_DEPLOY_MODE", "standard") == "external-proxy":
        command.extend(["-f", str(root / "compose.external-proxy.yaml")])
    if env.get("COPPER_DEPLOY_MODE", "standard") == "lan":
        command.extend(["-f", str(root / "compose.lan.yaml")])
    if env.get("COPPER_TLS_MODE", "domain") == "ip":
        command.extend(["-f", str(root / "compose.ip-tls.yaml")])
    return command


def run(command: list[str], timeout: int = 15) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=timeout,
            check=False,
            env={**os.environ, "LC_ALL": "C"},
        )
    except (OSError, subprocess.TimeoutExpired):
        return subprocess.CompletedProcess(command, 124, "", "")


def runtime_log_enforcement(root: pathlib.Path, env_file: pathlib.Path, env: dict[str, str]) -> dict[str, object]:
    if shutil.which("docker") is None:
        return {"status": "NOT_RUNNING", "services": {}}
    prefix = compose_prefix(root, env_file, env)
    services: dict[str, object] = {}
    for service in ("mysql", "backend", "web"):
        container = run(prefix + ["ps", "--quiet", service]).stdout.strip().splitlines()
        if not container:
            services[service] = {"running": False, "status": "NOT_RUNNING"}
            continue
        inspected = run(["docker", "inspect", "--format", "{{json .HostConfig.LogConfig}}", container[0]])
        try:
            config = json.loads(inspected.stdout) if inspected.returncode == 0 else {}
        except json.JSONDecodeError:
            config = {}
        options = config.get("Config", {}) if isinstance(config, dict) else {}
        passed = config.get("Type") == "local" and options.get("max-size") == "20m" and options.get("max-file") == "5"
        services[service] = {
            "running": True,
            "status": "PASS" if passed else "FAIL",
            "driver": config.get("Type"),
            "maxSize": options.get("max-size"),
            "maxFiles": options.get("max-file"),
        }
    statuses = {str(value.get("status")) for value in services.values() if isinstance(value, dict)}
    status = "FAIL" if "FAIL" in statuses else ("PASS" if statuses == {"PASS"} else "NOT_RUNNING")
    return {"status": status, "services": services}


def classify_capacity(total: int, available: int, policy: dict[str, Any]) -> str:
    if total <= 0 or available < 0:
        return "BLOCKED"
    percent = available * 100 / total
    capacity = policy["capacity"]
    for level, name in (("BLOCKED", "blocked"), ("CRITICAL", "critical"), ("WARNING", "warning")):
        threshold = capacity[name]
        if available < int(threshold["availableBytesBelow"]) or percent < float(threshold["availablePercentBelow"]):
            return level
    return "NORMAL"


def latest_restore_drill(restore_root: pathlib.Path, now: dt.datetime) -> dict[str, object]:
    latest: tuple[dt.datetime, dict[str, Any]] | None = None
    if restore_root.is_dir() and not restore_root.is_symlink():
        for path in restore_root.glob("*/state.json"):
            state = secure_json(path, "copper-restore-state/v1")
            if (
                not state
                or state.get("status") not in {"SWITCHED", "ROLLED_BACK"}
                or not parse_time(state.get("validatedAt"))
            ):
                continue
            finished = parse_time(state.get("switchedAt") or state.get("rolledBackAt") or state.get("updatedAt"))
            started = parse_time(state.get("createdAt"))
            if finished and (latest is None or finished > latest[0]):
                latest = (finished, state)
    if not latest:
        return {"status": "NO_FULL_DRILL", "completedAt": None, "durationSeconds": None, "ageDays": None}
    finished, state = latest
    started = parse_time(state.get("createdAt"))
    duration = max(0, int((finished - started).total_seconds())) if started else None
    return {
        "status": "EVIDENCED",
        "completedAt": iso(finished),
        "durationSeconds": duration,
        "ageDays": round(max(0, (now - finished).total_seconds()) / 86400, 2),
    }


def backup_objective(var_dir: pathlib.Path, env: dict[str, str], now: dt.datetime, policy: dict[str, Any]) -> dict[str, object]:
    state = secure_json(var_dir / "backup-jobs" / "status.json", "copper-backup-job-status/v1")
    configured = [item for item in env.get("COPPER_BACKUP_TARGETS", "local").split(",") if item]
    off_host_configured = any(item in {"nas", "s3"} for item in configured)
    result: dict[str, object] = {
        "scheduledTargetHours": 24,
        "lastSuccessAt": None,
        "ageHours": None,
        "packageVerified": False,
        "configuredTargets": configured,
        "offHostTargetConfigured": off_host_configured,
        "offHostTargetEvidenced": False,
        "localRecoveryPoint": "NOT_EVIDENCED",
        "hostLossRecoveryPoint": "NOT_SUPPORTED" if not off_host_configured else "NOT_EVIDENCED",
    }
    if not state:
        return result
    success = state.get("lastSuccess") if isinstance(state.get("lastSuccess"), dict) else {}
    successful_targets = success.get("targets") if isinstance(success.get("targets"), list) else []
    current = state.get("current") if isinstance(state.get("current"), dict) else {}
    if (
        not successful_targets
        and current.get("result") == "SUCCESS"
        and current.get("jobId") == success.get("jobId")
        and isinstance(current.get("targets"), list)
    ):
        successful_targets = current["targets"]
    off_host_evidenced = any(str(item) in {"nas", "s3"} for item in successful_targets)
    result["offHostTargetEvidenced"] = off_host_evidenced
    completed = parse_time(success.get("completedAt"))
    verified = bool(re.fullmatch(r"[0-9a-f]{32}", str(success.get("backupId", ""))) and re.fullmatch(r"[0-9a-f]{64}", str(success.get("sha256", ""))) and int(success.get("bytes", 0)) > 0)
    if not completed or not verified:
        return result
    age = max(0.0, (now - completed).total_seconds() / 3600)
    result.update({"lastSuccessAt": iso(completed), "ageHours": round(age, 2), "packageVerified": True})
    if age <= 24:
        local = "TARGET_MET"
    elif age <= int(policy["recoveryObjectives"]["engineeringBaseline"]["backupFreshnessWarningHours"]):
        local = "GRACE_WARNING"
    elif age <= int(policy["recoveryObjectives"]["engineeringBaseline"]["backupFreshnessCriticalHours"]):
        local = "BREACHED"
    else:
        local = "CRITICAL_BREACH"
    result["localRecoveryPoint"] = local
    if off_host_evidenced:
        result["hostLossRecoveryPoint"] = local
    return result


def collect(root: pathlib.Path, env_file: pathlib.Path, policy: dict[str, Any], now: dt.datetime | None = None) -> dict[str, object]:
    now = now or utc_now()
    env = parse_env(env_file)
    var_dir = pathlib.Path(env.get("COPPER_VAR_DIR", "/var/lib/copper"))
    restore_root = pathlib.Path(env.get("COPPER_RESTORE_ROOT", "/var/lib/copper-restores"))
    usage = shutil.disk_usage(var_dir)
    if os.environ.get("COPPER_TEST_MODE") == "1" and os.environ.get("COPPER_OPERATIONS_TEST_AVAILABLE_BYTES"):
        available = int(os.environ["COPPER_OPERATIONS_TEST_AVAILABLE_BYTES"])
    else:
        available = usage.free
    static = static_enforcement(root)
    runtime = runtime_log_enforcement(root, env_file, env)
    app_cap = int(policy["logClasses"]["applicationFile"]["maxTotalBytes"])
    app_bytes = directory_bytes(var_dir / "logs")
    capacity_level = classify_capacity(usage.total, available, policy)
    backup = backup_objective(var_dir, env, now, policy)
    restore = latest_restore_drill(restore_root, now)
    levels = [capacity_level]
    if static["status"] != "PASS" or runtime["status"] == "FAIL" or app_bytes > app_cap:
        levels.append("BLOCKED")
    elif runtime["status"] == "NOT_RUNNING":
        levels.append("WARNING")
    local_rpo = str(backup["localRecoveryPoint"])
    if local_rpo in {"NOT_EVIDENCED", "CRITICAL_BREACH"}:
        levels.append("BLOCKED")
    elif local_rpo in {"GRACE_WARNING", "BREACHED"}:
        levels.append("WARNING" if local_rpo == "GRACE_WARNING" else "CRITICAL")
    if not bool(backup["offHostTargetEvidenced"]):
        levels.append("WARNING")
    if restore["status"] == "NO_FULL_DRILL" or (restore.get("ageDays") is not None and float(restore["ageDays"]) > 180):
        levels.append("WARNING")
    overall = max(levels, key=lambda value: LEVELS[value])
    return {
        "formatVersion": FORMAT,
        "policyVersion": policy["policyVersion"],
        "observedAt": iso(now),
        "overallLevel": overall,
        "overallLabel": LEVEL_ZH[overall],
        "logProtection": {
            "staticEnforcement": static,
            "runtimeEnforcement": runtime,
            "applicationLogBytes": app_bytes,
            "applicationLogCapBytes": app_cap,
            "maximumManagedLogBytes": 3 * 100 * 1024**2 + app_cap + 2 * 10 * 1024**2,
            "auditAutoDelete": False,
        },
        "capacity": {
            "level": capacity_level,
            "capacityBytes": usage.total,
            "availableBytes": available,
            "availablePercent": round(available * 100 / usage.total, 2) if usage.total else 0,
            "largeJobsBlockedAt": "CRITICAL",
            "businessWritesBlockedAt": "BLOCKED",
        },
        "recovery": {
            "backup": backup,
            "restoreDrill": restore,
            "technicalRestoreTargetMinutes": 240,
            "commercialSla": False,
        },
        "privacy": {"businessDetailsCollected": False, "secretsCollected": False},
    }


def human_status(document: dict[str, object]) -> str:
    logs = document["logProtection"]
    capacity = document["capacity"]
    recovery = document["recovery"]
    backup = recovery["backup"]
    restore = recovery["restoreDrill"]
    return "\n".join(
        [
            f"总体：{document['overallLabel']}（{document['overallLevel']}）",
            f"日志：静态={logs['staticEnforcement']['status']}，运行时={logs['runtimeEnforcement']['status']}，应用文件={logs['applicationLogBytes']}/{logs['applicationLogCapBytes']} 字节",
            f"容量：{capacity['level']}，可用 {capacity['availableBytes']} 字节（{capacity['availablePercent']}%）",
            f"本机恢复点：{backup['localRecoveryPoint']}；整机损失恢复点：{backup['hostLossRecoveryPoint']}",
            f"完整恢复演练：{restore['status']}；技术恢复目标={recovery['technicalRestoreTargetMinutes']} 分钟（非商业 SLA）",
            "说明：响应时间、开始恢复时间和完成恢复时间是三个不同口径；以服务合同及当前证据为准。",
        ]
    )


def checklist(policy: dict[str, Any], fault: str) -> dict[str, object]:
    item = policy["incidentChecklists"].get(fault)
    if not isinstance(item, dict):
        fail("故障类型只支持 database、disk、backup、certificate、time 或 power")
    return {"formatVersion": "copper-incident-checklist/v1", "fault": fault, **item}


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--root", required=True)
    result.add_argument("--env-file", required=True)
    sub = result.add_subparsers(dest="command", required=True)
    status = sub.add_parser("status")
    status.add_argument("--json", action="store_true")
    checklist_parser = sub.add_parser("checklist")
    checklist_parser.add_argument("fault")
    checklist_parser.add_argument("--json", action="store_true")
    return result


def main() -> int:
    args = parser().parse_args()
    root = pathlib.Path(args.root).resolve()
    policy = load_policy(root / "config" / "operations-policy.json")
    if args.command == "checklist":
        document = checklist(policy, args.fault)
        if args.json:
            print(json.dumps(document, ensure_ascii=False, sort_keys=True))
        else:
            print(f"故障：{document['fault']}")
            print(f"停止规则：{document['stopRule']}")
            for command in document["commands"]:
                print(f"  - {command}")
            print(f"恢复门槛：{document['recoveryGate']}")
            print(f"升级路径：{document['escalation']}")
        return 0
    document = collect(root, pathlib.Path(args.env_file), policy)
    print(json.dumps(document, ensure_ascii=False, sort_keys=True) if args.json else human_status(document))
    return 1 if document["overallLevel"] in {"CRITICAL", "BLOCKED"} else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except OperationsError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        raise SystemExit(3)
