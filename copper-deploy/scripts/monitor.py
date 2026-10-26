#!/usr/bin/env python3
"""Local, privacy-safe Copper availability and recoverability monitor."""

from __future__ import annotations

import argparse
import datetime as dt
import email.utils
import hashlib
import hmac
import json
import os
import pathlib
import re
import shutil
import stat
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


FORMAT = "copper-monitor-status/v1"
EVENT_FORMAT = "copper-monitor-alert/v1"
LEVELS = {"NORMAL": 0, "WARNING": 1, "CRITICAL": 2, "BLOCKED": 3}
LEVEL_ZH = {"NORMAL": "正常", "WARNING": "预警", "CRITICAL": "严重", "BLOCKED": "阻断"}
MAX_HISTORY_BYTES = 5 * 1024 * 1024
HASH_RE = re.compile(r"^[0-9a-f]{64}$")


class MonitorError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise MonitorError(message)


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def iso(value: dt.datetime) -> str:
    return value.astimezone(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_time(value: object) -> dt.datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=dt.timezone.utc)
        return parsed.astimezone(dt.timezone.utc)
    except ValueError:
        return None


def level_max(*levels: str) -> str:
    return max(levels, key=lambda item: LEVELS.get(item, 3), default="NORMAL")


def site_fingerprint(site_id: str) -> str:
    return hashlib.sha256(site_id.encode("utf-8")).hexdigest()[:16]


def signal(
    identifier: str,
    level: str,
    summary: str,
    cause: str,
    action: str,
    action_code: str,
    observed_at: str,
    details: dict[str, object] | None = None,
) -> dict[str, object]:
    if level not in LEVELS:
        raise ValueError(f"unsupported level {level}")
    result: dict[str, object] = {
        "id": identifier,
        "level": level,
        "summary": summary,
        "cause": cause,
        "action": action,
        "actionCode": action_code,
        "observedAt": observed_at,
    }
    if details:
        result["details"] = details
    return result


def directory_bytes(path: pathlib.Path) -> int:
    if not path.exists() or path.is_symlink():
        return 0
    total = 0
    for root, directories, files in os.walk(path, followlinks=False):
        directories[:] = [name for name in directories if not (pathlib.Path(root) / name).is_symlink()]
        for name in files:
            item = pathlib.Path(root) / name
            try:
                if not item.is_symlink():
                    total += item.stat().st_size
            except (FileNotFoundError, PermissionError, OSError):
                continue
    return total


def secure_json(path: pathlib.Path) -> dict[str, object] | None:
    if not path.exists():
        return None
    if not path.is_file() or path.is_symlink() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        return None
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return document if isinstance(document, dict) else None


def parse_env(path: pathlib.Path) -> dict[str, str]:
    if not path.is_file() or path.is_symlink():
        fail("Copper 配置文件不存在、不是普通文件或是符号链接")
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            values[key] = value.strip()
    return values


def run(command: list[str], *, input_text: str | None = None, timeout: int = 15) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            input=input_text,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=timeout,
            check=False,
            env={**os.environ, "LC_ALL": "C"},
        )
    except (OSError, subprocess.TimeoutExpired):
        return subprocess.CompletedProcess(command, 124, "", "")


def compose_prefix(root: pathlib.Path, env_file: pathlib.Path, env: dict[str, str]) -> list[str]:
    command = ["docker", "compose", "--env-file", str(env_file), "-f", str(root / "compose.yaml")]
    if env.get("COPPER_DEPLOY_MODE", "standard") == "external-proxy":
        command.extend(["-f", str(root / "compose.external-proxy.yaml")])
    if env.get("COPPER_DEPLOY_MODE", "standard") == "lan":
        command.extend(["-f", str(root / "compose.lan.yaml")])
    if env.get("COPPER_TLS_MODE", "domain") == "ip":
        command.extend(["-f", str(root / "compose.ip-tls.yaml")])
    return command


def container_state(prefix: list[str], service: str) -> tuple[dict[str, object], str]:
    result = run(prefix + ["ps", "--all", "--quiet", service])
    container_id = result.stdout.strip().splitlines()[0] if result.returncode == 0 and result.stdout.strip() else ""
    if not container_id:
        return {"exists": False, "running": False, "health": "missing"}, ""
    inspected = run(
        [
            "docker",
            "inspect",
            "--format",
            "{{.State.Status}}\t{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}\t{{.State.StartedAt}}",
            container_id,
        ]
    )
    if inspected.returncode != 0:
        return {"exists": True, "running": False, "health": "unknown"}, container_id
    parts = inspected.stdout.strip().split("\t")
    status = parts[0] if parts else "unknown"
    health = parts[1] if len(parts) > 1 else "unknown"
    started = parts[2] if len(parts) > 2 else None
    return {"exists": True, "running": status == "running", "health": health, "startedAt": started}, container_id


def endpoint_probe(container_id: str, command: list[str]) -> bool:
    if not container_id:
        return False
    return run(["docker", "exec", container_id, *command], timeout=8).returncode == 0


def database_probe(container_id: str) -> dict[str, object]:
    if not container_id:
        return {"available": False, "writeProbe": False}
    sql = """SELECT VERSION(), @@global.read_only, @@global.super_read_only,
COALESCE((SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1),'unversioned');
CREATE TEMPORARY TABLE copper_monitor_write_probe (id INT PRIMARY KEY);
INSERT INTO copper_monitor_write_probe VALUES (1);
SELECT COUNT(*) FROM copper_monitor_write_probe;
DROP TEMPORARY TABLE copper_monitor_write_probe;
"""
    command = [
        "docker",
        "exec",
        "-i",
        container_id,
        "sh",
        "-c",
        'MYSQL_PWD="$(cat /run/secrets/copper_db_password)" exec mysql -ucopper -D copper_v2 --batch --raw --skip-column-names',
    ]
    result = run(command, input_text=sql, timeout=12)
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if result.returncode != 0 or len(lines) < 2:
        return {"available": False, "writeProbe": False}
    fields = lines[0].split("\t")
    if len(fields) != 4:
        return {"available": False, "writeProbe": False}
    return {
        "available": True,
        "version": fields[0],
        "readOnly": fields[1] == "1",
        "superReadOnly": fields[2] == "1",
        "flywayVersion": fields[3],
        "writeProbe": lines[-1] == "1",
    }


def storage_snapshot(var_dir: pathlib.Path, backup_dir: pathlib.Path, backend_id: str) -> dict[str, object]:
    try:
        usage = shutil.disk_usage(var_dir)
        filesystem = {"capacityBytes": usage.total, "availableBytes": usage.free}
    except OSError:
        filesystem = {"capacityBytes": 0, "availableBytes": 0}
    try:
        host_usage = shutil.disk_usage(pathlib.Path("/"))
        host = {"capacityBytes": host_usage.total, "availableBytes": host_usage.free}
    except OSError:
        host = {"capacityBytes": 0, "availableBytes": 0}
    writable = endpoint_probe(
        backend_id,
        ["sh", "-c", 'umask 077; p="/app/data/runtime/.copper-monitor-write-$$"; : > "$p" && rm -f -- "$p"'],
    )
    return {
        "dataFilesystem": filesystem,
        "hostFilesystem": host,
        "businessStorageWritable": writable,
        "usage": {
            "databaseBytes": directory_bytes(var_dir / "mysql"),
            "attachmentBytes": directory_bytes(var_dir / "files"),
            "exportBytes": directory_bytes(var_dir / "backend" / "exports"),
            "backupBytes": directory_bytes(backup_dir),
            "logBytes": directory_bytes(var_dir / "logs"),
        },
    }


def backup_snapshot(var_dir: pathlib.Path, backup_dir: pathlib.Path, configured_targets: list[str]) -> dict[str, object]:
    state = secure_json(var_dir / "backup-jobs" / "status.json")
    registry = secure_json(var_dir / "backup-keys" / "registry.json")
    result: dict[str, object] = {
        "statePresent": state is not None,
        "lastSuccessAt": None,
        "lastBackupId": None,
        "consecutiveFailures": 0,
        "currentResult": "UNKNOWN",
        "alertResult": "UNKNOWN",
        "targets": configured_targets,
        "packageVerified": False,
        "activeKeyRegistered": False,
        "keyHandoverProved": False,
        "lastRestoreTestAt": None,
    }
    if state:
        result["consecutiveFailures"] = max(0, int(state.get("consecutiveFailures", 0)))
        current = state.get("current") if isinstance(state.get("current"), dict) else {}
        result["currentResult"] = str(current.get("result", "UNKNOWN"))
        result["alertResult"] = str(current.get("alertResult", "UNKNOWN"))
        success = state.get("lastSuccess") if isinstance(state.get("lastSuccess"), dict) else {}
        result["lastSuccessAt"] = success.get("completedAt")
        result["lastBackupId"] = success.get("backupId")
        result["packageVerified"] = bool(
            re.fullmatch(r"[0-9a-f]{32}", str(success.get("backupId", "")))
            and HASH_RE.fullmatch(str(success.get("sha256", "")))
            and int(success.get("bytes", 0)) > 0
        )
        if current.get("jobId") == success.get("jobId") and isinstance(current.get("targets"), list):
            result["targets"] = [str(item) for item in current["targets"]]
    if registry and registry.get("formatVersion") == "copper-key-registry/v1":
        active_id = registry.get("activeKeyId")
        active = next(
            (
                item
                for item in registry.get("keys", [])
                if isinstance(item, dict) and item.get("keyId") == active_id and item.get("status") == "ACTIVE"
            ),
            None,
        )
        if active:
            result["activeKeyRegistered"] = True
            handover = active.get("handover") if isinstance(active.get("handover"), dict) else {}
            result["keyHandoverProved"] = bool(HASH_RE.fullmatch(str(handover.get("recordSha256", ""))))
            tests = active.get("restoreTests") if isinstance(active.get("restoreTests"), list) else []
            verified = [parse_time(item.get("verifiedAt")) for item in tests if isinstance(item, dict)]
            verified = [item for item in verified if item is not None]
            if verified:
                result["lastRestoreTestAt"] = iso(max(verified))
    last_success = secure_json(backup_dir / "last-success.json")
    if last_success and result["lastSuccessAt"] is None:
        result["lastSuccessAt"] = last_success.get("completedAt")
        result["lastBackupId"] = last_success.get("backupId")
    return result


def certificate_snapshot(root: pathlib.Path, env: dict[str, str], var_dir: pathlib.Path, etc_dir: pathlib.Path) -> dict[str, object]:
    domain = env.get("COPPER_DOMAIN", "unknown")
    mode = env.get("COPPER_DEPLOY_MODE", "standard")
    tls_mode = env.get("COPPER_TLS_MODE", "domain")
    candidate: pathlib.Path | None = None
    managed_by = "COPPER"
    configured = env.get("COPPER_MONITOR_TLS_CERT_FILE", "")
    if configured:
        candidate = pathlib.Path(configured)
        managed_by = "EXTERNAL_PROXY" if mode == "external-proxy" else "COPPER"
    elif tls_mode == "ip":
        candidate = etc_dir / "tls" / "fullchain.pem"
    elif tls_mode == "lan":
        candidate = etc_dir / "tls" / "lan-fullchain.pem"
        managed_by = "CUSTOMER_CA"
    elif mode == "standard":
        certificate_root = var_dir / "caddy" / "data" / "caddy" / "certificates"
        if certificate_root.is_dir() and not certificate_root.is_symlink():
            for item in sorted(certificate_root.rglob("*.crt"), key=lambda path: path.stat().st_mtime, reverse=True):
                if run(["openssl", "x509", "-in", str(item), "-noout", "-checkhost", domain], timeout=5).returncode == 0:
                    candidate = item
                    break
    else:
        managed_by = "EXTERNAL_PROXY"
    if candidate is None or not candidate.is_file() or candidate.is_symlink():
        return {"available": False, "domain": domain, "managedBy": managed_by, "daysRemaining": None}
    match_option = "-checkip" if tls_mode == "ip" else "-checkhost"
    if run(["openssl", "x509", "-in", str(candidate), "-noout", match_option, domain], timeout=5).returncode != 0:
        return {"available": False, "domain": domain, "managedBy": managed_by, "daysRemaining": None}
    parsed = run(["openssl", "x509", "-in", str(candidate), "-noout", "-enddate", "-issuer"], timeout=5)
    if parsed.returncode != 0:
        return {"available": False, "domain": domain, "managedBy": managed_by, "daysRemaining": None}
    not_after_text = ""
    issuer = "unknown"
    for line in parsed.stdout.splitlines():
        if line.startswith("notAfter="):
            not_after_text = line.split("=", 1)[1].strip()
        elif line.startswith("issuer="):
            issuer = line.split("=", 1)[1].strip()[:240]
    try:
        not_after = email.utils.parsedate_to_datetime(not_after_text).astimezone(dt.timezone.utc)
    except (TypeError, ValueError):
        return {"available": False, "domain": domain, "managedBy": managed_by, "daysRemaining": None}
    remaining = (not_after - utc_now()).total_seconds() / 86400
    return {
        "available": True,
        "domain": domain,
        "managedBy": managed_by,
        "issuer": issuer,
        "notAfter": iso(not_after),
        "daysRemaining": round(remaining, 2),
    }


def parse_offset_ms(text: str) -> float | None:
    match = re.search(r"Offset:\s*([+-]?[0-9.]+)\s*(us|ms|s)\b", text)
    if not match:
        return None
    value = float(match.group(1))
    return value * {"us": 0.001, "ms": 1.0, "s": 1000.0}[match.group(2)]


def parse_chrony_offset_ms(text: str) -> float | None:
    match = re.search(r"^(?:Last offset|System time)\s*:\s*([+-]?[0-9.]+)\s+seconds", text, re.MULTILINE)
    return float(match.group(1)) * 1000 if match else None


def time_snapshot(configured_timezone: str) -> dict[str, object]:
    timezone_result = run(["timedatectl", "show", "-p", "Timezone", "--value"], timeout=5)
    sync_result = run(["timedatectl", "show", "-p", "NTPSynchronized", "--value"], timeout=5)
    status_result = run(["timedatectl", "timesync-status"], timeout=5)
    offset = parse_offset_ms(status_result.stdout) if status_result.returncode == 0 else None
    if offset is None:
        chrony_result = run(["chronyc", "tracking"], timeout=5)
        offset = parse_chrony_offset_ms(chrony_result.stdout) if chrony_result.returncode == 0 else None
    return {
        "hostTimezone": timezone_result.stdout.strip() if timezone_result.returncode == 0 else "unknown",
        "applicationTimezone": configured_timezone,
        "ntpSynchronized": sync_result.returncode == 0 and sync_result.stdout.strip().lower() == "yes",
        "offsetMs": offset,
    }


def apply_trusted_time_snapshot(clock: dict[str, object], path: pathlib.Path) -> None:
    if not path.is_file() or path.is_symlink():
        return
    try:
        safety = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return
    if not isinstance(safety, dict) or safety.get("formatVersion") != "copper-time-safety/v1":
        return
    level = safety.get("level")
    if level not in LEVELS:
        return
    clock["safetyLevel"] = level
    clock["safetyReason"] = str(safety.get("reason", ""))
    jump = safety.get("clockDiscontinuityMs")
    if isinstance(jump, (int, float)):
        clock["clockDiscontinuityMs"] = float(jump)


def test_overrides(snapshot: dict[str, object]) -> None:
    if os.environ.get("COPPER_TEST_MODE") != "1":
        forbidden = [key for key in os.environ if key.startswith("COPPER_MONITOR_TEST_")]
        if forbidden:
            fail("COPPER_MONITOR_TEST_* 故障注入只允许 COPPER_TEST_MODE=1")
        return
    storage = snapshot["storage"]
    certificate = snapshot["certificate"]
    clock = snapshot["time"]
    database = snapshot["database"]
    if "COPPER_MONITOR_TEST_AVAILABLE_BYTES" in os.environ:
        storage["dataFilesystem"]["availableBytes"] = int(os.environ["COPPER_MONITOR_TEST_AVAILABLE_BYTES"])
    if "COPPER_MONITOR_TEST_CERT_DAYS" in os.environ:
        days = float(os.environ["COPPER_MONITOR_TEST_CERT_DAYS"])
        certificate.update({"available": True, "daysRemaining": days, "notAfter": iso(utc_now() + dt.timedelta(days=days))})
    if "COPPER_MONITOR_TEST_NTP_SYNC" in os.environ:
        clock["ntpSynchronized"] = os.environ["COPPER_MONITOR_TEST_NTP_SYNC"] == "1"
    if "COPPER_MONITOR_TEST_CLOCK_OFFSET_MS" in os.environ:
        clock["offsetMs"] = float(os.environ["COPPER_MONITOR_TEST_CLOCK_OFFSET_MS"])
    if os.environ.get("COPPER_MONITOR_TEST_DB_UNAVAILABLE") == "1":
        database.update({"available": False, "writeProbe": False})


def collect(root: pathlib.Path, env_file: pathlib.Path) -> dict[str, object]:
    env = parse_env(env_file)
    required = ["COPPER_SITE_ID", "COPPER_VAR_DIR", "COPPER_ETC_DIR"]
    missing = [key for key in required if not env.get(key)]
    if missing:
        fail("Copper 配置缺少监控必需项：" + ",".join(missing))
    var_dir = pathlib.Path(env["COPPER_VAR_DIR"])
    etc_dir = pathlib.Path(env["COPPER_ETC_DIR"])
    backup_dir = pathlib.Path(env.get("COPPER_BACKUP_DIR", "/var/backups/copper"))
    prefix = compose_prefix(root, env_file, env)
    services: dict[str, object] = {}
    identifiers: dict[str, str] = {}
    for service_name in ("mysql", "backend", "web"):
        services[service_name], identifiers[service_name] = container_state(prefix, service_name)
    application = {
        "services": services,
        "webLiveness": endpoint_probe(identifiers["web"], ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8081/healthz"]),
        "backendReadiness": endpoint_probe(
            identifiers["backend"],
            [
                "sh",
                "-c",
                "curl --fail --silent --max-time 3 http://127.0.0.1:8191/api/actuator/health/readiness | grep -q '\"status\":\"UP\"'",
            ],
        ),
    }
    configured_targets = [item.strip() for item in env.get("COPPER_BACKUP_TARGETS", "local").split(",") if item.strip()]
    clock = time_snapshot(env.get("COPPER_TIME_ZONE", "Asia/Shanghai"))
    apply_trusted_time_snapshot(clock, var_dir / "time-safety" / "time-safety.json")
    snapshot: dict[str, object] = {
        "observedAt": iso(utc_now()),
        "siteFingerprint": site_fingerprint(env["COPPER_SITE_ID"]),
        "application": application,
        "database": database_probe(identifiers["mysql"]),
        "storage": storage_snapshot(var_dir, backup_dir, identifiers["backend"]),
        "backup": backup_snapshot(var_dir, backup_dir, configured_targets),
        "certificate": certificate_snapshot(root, env, var_dir, etc_dir),
        "time": clock,
        "policy": {
            "diskWarning": "低于 20% 或 5 GiB",
            "diskCritical": "低于 10% 或 2 GiB",
            "diskBlocked": "低于 5% 或 1 GiB",
            "backupWarningHours": 30,
            "backupCriticalHours": 48,
            "certificateWarningDays": 2 if env.get("COPPER_TLS_MODE") == "ip" else 30,
            "certificateCriticalDays": 0.5 if env.get("COPPER_TLS_MODE") == "ip" else 7,
            "clockWarningMs": 1000,
            "clockCriticalMs": 5000,
            "clockBlockedMs": 60000,
        },
    }
    test_overrides(snapshot)
    return snapshot


def disk_level(total: int, available: int) -> str:
    if total <= 0 or available < 0:
        return "BLOCKED"
    percent = available * 100 / total
    if available < 1024**3 or percent < 5:
        return "BLOCKED"
    if available < 2 * 1024**3 or percent < 10:
        return "CRITICAL"
    if available < 5 * 1024**3 or percent < 20:
        return "WARNING"
    return "NORMAL"


def growth_signal(snapshot: dict[str, object], history: list[dict[str, object]], observed: dt.datetime) -> dict[str, object]:
    storage = snapshot["storage"]
    current_usage = sum(int(value) for value in storage.get("usage", {}).values())
    current_available = int(storage.get("dataFilesystem", {}).get("availableBytes", 0))
    baseline = None
    for item in reversed(history):
        item_time = parse_time(item.get("observedAt"))
        age_seconds = (observed - item_time).total_seconds() if item_time else -1
        if item_time and 6 * 3600 <= age_seconds <= 30 * 86400:
            baseline = item
            break
    details: dict[str, object] = {"measuredBytes": current_usage, "baselineAvailable": baseline is not None}
    if not baseline:
        return signal("storage.growth", "NORMAL", "容量增长基线正在建立", "有效历史不足 6 小时", "保持监控计时器运行", "KEEP_MONITOR_RUNNING", iso(observed), details)
    previous_usage = int(baseline.get("measuredUsageBytes", 0))
    baseline_time = parse_time(baseline.get("observedAt"))
    assert baseline_time is not None
    elapsed_days = max((observed - baseline_time).total_seconds() / 86400, 1 / 24)
    daily_growth = max(0, int((current_usage - previous_usage) / elapsed_days))
    details["dailyGrowthBytes"] = daily_growth
    if daily_growth <= 0:
        return signal("storage.growth", "NORMAL", "未发现持续容量增长风险", "观察窗口内净增长为零", "继续观察", "NONE", iso(observed), details)
    critical_headroom = max(0, current_available - 2 * 1024**3)
    days = critical_headroom / daily_growth
    details["daysToCritical"] = round(days, 1)
    if days <= 7:
        return signal("storage.growth", "CRITICAL", "预计 7 天内进入严重容量区", "按当前增长速度可用空间将快速降至严重门槛", "立即清理可再生文件或扩容", "EXPAND_STORAGE_NOW", iso(observed), details)
    if days <= 30:
        return signal("storage.growth", "WARNING", "预计 30 天内进入严重容量区", "当前增长速度需要提前安排容量", "安排扩容并检查附件、导出、备份和日志增长", "PLAN_STORAGE_EXPANSION", iso(observed), details)
    return signal("storage.growth", "NORMAL", "容量增长风险可控", "预计 30 天内不会触达严重门槛", "继续观察", "NONE", iso(observed), details)


def evaluate(snapshot: dict[str, object], history: list[dict[str, object]] | None = None) -> dict[str, object]:
    history = history or []
    observed_at = str(snapshot.get("observedAt", ""))
    observed = parse_time(observed_at)
    if observed is None:
        fail("监控快照 observedAt 无效")
    signals: list[dict[str, object]] = []
    layers: dict[str, list[str]] = {"liveness": [], "readiness": [], "writable": [], "recoverable": []}

    application = snapshot.get("application") if isinstance(snapshot.get("application"), dict) else {}
    services = application.get("services") if isinstance(application.get("services"), dict) else {}
    for name in ("mysql", "backend", "web"):
        state = services.get(name) if isinstance(services.get(name), dict) else {}
        if not state.get("exists") or not state.get("running"):
            item = signal(f"service.{name}", "BLOCKED", f"{name} 服务未运行", "容器不存在或不在运行状态", "查看本机日志并恢复服务", "RESTORE_SERVICE", observed_at)
        elif state.get("health") != "healthy":
            item = signal(f"service.{name}", "CRITICAL", f"{name} 服务健康检查未通过", f"当前健康状态为 {state.get('health', 'unknown')}", "运行 copperctl doctor 并检查对应服务日志", "CHECK_SERVICE_HEALTH", observed_at)
        else:
            item = signal(f"service.{name}", "NORMAL", f"{name} 服务存活", "容器运行且健康检查通过", "无需操作", "NONE", observed_at)
        signals.append(item)
        layers["liveness"].append(str(item["level"]))
    for key, label in (("webLiveness", "Web 存活端点"), ("backendReadiness", "Backend 就绪端点")):
        ok = bool(application.get(key))
        item = signal(
            f"application.{key}",
            "NORMAL" if ok else "BLOCKED",
            f"{label}{'正常' if ok else '不可用'}",
            "同容器本地探测通过" if ok else "同容器本地探测失败",
            "无需操作" if ok else "检查服务日志、数据库和迁移状态",
            "NONE" if ok else "RESTORE_APPLICATION_READINESS",
            observed_at,
        )
        signals.append(item)
        layers["readiness"].append(str(item["level"]))

    database = snapshot.get("database") if isinstance(snapshot.get("database"), dict) else {}
    available = bool(database.get("available"))
    db_item = signal(
        "database.availability",
        "NORMAL" if available else "BLOCKED",
        "数据库连接正常" if available else "数据库不可用",
        f"MySQL {database.get('version', 'unknown')} / Flyway {database.get('flywayVersion', 'unknown')}" if available else "业务账户连接或查询失败",
        "无需操作" if available else "恢复 MySQL 后重新运行监控",
        "NONE" if available else "RESTORE_DATABASE",
        observed_at,
        {"version": database.get("version"), "flywayVersion": database.get("flywayVersion")} if available else None,
    )
    signals.append(db_item)
    layers["readiness"].append(str(db_item["level"]))
    writable = available and not database.get("readOnly") and not database.get("superReadOnly") and bool(database.get("writeProbe"))
    write_item = signal(
        "database.writable",
        "NORMAL" if writable else "BLOCKED",
        "数据库业务写入探测通过" if writable else "数据库不允许安全业务写入",
        "业务账户临时表写入成功且未启用只读" if writable else "只读状态或无持久副作用写入探测失败",
        "无需操作" if writable else "禁止继续业务写入，修复只读或权限后复测",
        "NONE" if writable else "KEEP_BUSINESS_WRITE_BLOCKED",
        observed_at,
    )
    signals.append(write_item)
    layers["writable"].append(str(write_item["level"]))

    storage = snapshot.get("storage") if isinstance(snapshot.get("storage"), dict) else {}
    for filesystem_name, label in (("dataFilesystem", "数据盘"), ("hostFilesystem", "系统盘")):
        filesystem = storage.get(filesystem_name) if isinstance(storage.get(filesystem_name), dict) else {}
        total = int(filesystem.get("capacityBytes", 0))
        available_bytes = int(filesystem.get("availableBytes", 0))
        disk_status = disk_level(total, available_bytes)
        percent = round(available_bytes * 100 / total, 1) if total > 0 else 0
        action = {
            "NORMAL": "无需操作",
            "WARNING": "检查增长来源并安排清理或扩容",
            "CRITICAL": "立即扩容；暂停新的导出、备份和大附件",
            "BLOCKED": "保持业务写保护，扩容或安全清理后复测",
        }[disk_status]
        disk_item = signal(
            f"storage.{filesystem_name}",
            disk_status,
            f"{label}容量{LEVEL_ZH[disk_status]}",
            f"剩余 {available_bytes} 字节（{percent}%）",
            action,
            {"NORMAL": "NONE", "WARNING": "PLAN_STORAGE_EXPANSION", "CRITICAL": "EXPAND_STORAGE_NOW", "BLOCKED": "KEEP_BUSINESS_WRITE_BLOCKED"}[disk_status],
            observed_at,
            {"capacityBytes": total, "availableBytes": available_bytes, "availablePercent": percent},
        )
        signals.append(disk_item)
        if filesystem_name == "dataFilesystem":
            layers["writable"].append(str(disk_item["level"]))
    business_storage = bool(storage.get("businessStorageWritable"))
    storage_write_item = signal(
        "storage.businessWritable",
        "NORMAL" if business_storage else "BLOCKED",
        "业务文件区可写" if business_storage else "业务文件区不可写",
        "Backend 身份无持久副作用写入探测通过" if business_storage else "Backend 身份无法创建并清理探测文件",
        "无需操作" if business_storage else "修复数据目录权限或只读挂载后复测",
        "NONE" if business_storage else "KEEP_BUSINESS_WRITE_BLOCKED",
        observed_at,
    )
    signals.append(storage_write_item)
    layers["writable"].append(str(storage_write_item["level"]))
    usage = storage.get("usage") if isinstance(storage.get("usage"), dict) else {}
    signals.append(
        signal(
            "storage.areas",
            "NORMAL",
            "数据库、附件、导出、备份和日志占用已采集",
            "仅记录基础设施字节数，不读取业务明细",
            "继续观察各区域增长",
            "NONE",
            observed_at,
            {key: max(0, int(value)) for key, value in usage.items()},
        )
    )
    growth = growth_signal(snapshot, history, observed)
    signals.append(growth)
    layers["writable"].append(str(growth["level"]))

    backup = snapshot.get("backup") if isinstance(snapshot.get("backup"), dict) else {}
    last_success = parse_time(backup.get("lastSuccessAt"))
    if not last_success:
        backup_level, backup_cause, backup_action = "BLOCKED", "没有可识别的成功备份", "立即创建并验证一致性整站备份"
    else:
        age_hours = (observed - last_success).total_seconds() / 3600
        if age_hours > 48:
            backup_level, backup_cause, backup_action = "CRITICAL", f"最近成功备份已过去 {age_hours:.1f} 小时", "立即排查备份任务并补做成功备份"
        elif age_hours > 30:
            backup_level, backup_cause, backup_action = "WARNING", f"最近成功备份已过去 {age_hours:.1f} 小时", "检查下一次计划任务和存储目标"
        else:
            backup_level, backup_cause, backup_action = "NORMAL", f"最近成功备份距今 {max(0, age_hours):.1f} 小时", "无需操作"
        if not backup.get("packageVerified"):
            backup_level, backup_cause, backup_action = "BLOCKED", "最近备份缺少完整包校验实证", "重新创建并验证一致性整站备份"
        failures = int(backup.get("consecutiveFailures", 0))
        if failures >= 2 and LEVELS[backup_level] < LEVELS["CRITICAL"]:
            backup_level, backup_cause, backup_action = "CRITICAL", f"备份已经连续失败 {failures} 次", "查看 copperctl backup status 并恢复备份链路"
        elif failures == 1 and LEVELS[backup_level] < LEVELS["WARNING"]:
            backup_level, backup_cause, backup_action = "WARNING", "最近一次备份失败", "查看备份错误类别并在修复后重试"
    backup_item = signal(
        "backup.freshness",
        backup_level,
        f"整站备份{LEVEL_ZH[backup_level]}",
        backup_cause,
        backup_action,
        "NONE" if backup_level == "NORMAL" else "RESTORE_BACKUP_CHAIN",
        observed_at,
        {
            "lastSuccessAt": backup.get("lastSuccessAt"),
            "consecutiveFailures": max(0, int(backup.get("consecutiveFailures", 0))),
            "targets": backup.get("targets", []),
            "packageVerified": bool(backup.get("packageVerified")),
        },
    )
    signals.append(backup_item)
    layers["recoverable"].append(str(backup_item["level"]))
    key_ready = bool(backup.get("activeKeyRegistered")) and bool(backup.get("keyHandoverProved")) and bool(backup.get("lastRestoreTestAt"))
    key_item = signal(
        "backup.recoveryKeyProof",
        "NORMAL" if key_ready else "BLOCKED",
        "客户恢复密钥实证完整" if key_ready else "客户恢复密钥实证不完整",
        "活动公钥、双人交接和离线恢复测试均有记录" if key_ready else "缺少活动公钥、双人交接或离线恢复测试记录",
        "无需操作" if key_ready else "完成客户持钥交接和真实离线恢复验证",
        "NONE" if key_ready else "COMPLETE_RECOVERY_KEY_PROOF",
        observed_at,
        {"lastRestoreTestAt": backup.get("lastRestoreTestAt")},
    )
    signals.append(key_item)
    layers["recoverable"].append(str(key_item["level"]))

    certificate = snapshot.get("certificate") if isinstance(snapshot.get("certificate"), dict) else {}
    if not certificate.get("available"):
        cert_level = "WARNING" if certificate.get("managedBy") == "EXTERNAL_PROXY" else "BLOCKED"
        cert_cause = "外部代理证书文件尚未配置给本地监控" if cert_level == "WARNING" else "Copper 未找到可验证的当前证书"
    else:
        remaining = float(certificate.get("daysRemaining", -1))
        policy = snapshot.get("policy") if isinstance(snapshot.get("policy"), dict) else {}
        warning_days = float(policy.get("certificateWarningDays", 30))
        critical_days = float(policy.get("certificateCriticalDays", 7))
        if remaining <= 0:
            cert_level, cert_cause = "BLOCKED", "HTTPS 证书已经过期"
        elif remaining <= critical_days:
            cert_level, cert_cause = "CRITICAL", f"HTTPS 证书仅剩 {remaining:.1f} 天"
        elif remaining <= warning_days:
            cert_level, cert_cause = "WARNING", f"HTTPS 证书剩余 {remaining:.1f} 天"
        else:
            cert_level, cert_cause = "NORMAL", f"HTTPS 证书剩余 {remaining:.1f} 天"
    cert_item = signal(
        "tls.certificate",
        cert_level,
        f"HTTPS 证书{LEVEL_ZH[cert_level]}",
        cert_cause,
        "无需操作" if cert_level == "NORMAL" else "检查证书续期、DNS、80/443 端口和系统时间",
        "NONE" if cert_level == "NORMAL" else "RENEW_OR_CONFIGURE_CERTIFICATE",
        observed_at,
        {
            "domain": certificate.get("domain"),
            "managedBy": certificate.get("managedBy"),
            "issuer": certificate.get("issuer"),
            "notAfter": certificate.get("notAfter"),
            "daysRemaining": certificate.get("daysRemaining"),
        },
    )
    signals.append(cert_item)

    clock = snapshot.get("time") if isinstance(snapshot.get("time"), dict) else {}
    synchronized = bool(clock.get("ntpSynchronized"))
    offset = clock.get("offsetMs")
    offset_abs = abs(float(offset)) if isinstance(offset, (int, float)) else None
    safety_level = str(clock.get("safetyLevel", ""))
    discontinuity = clock.get("clockDiscontinuityMs")
    if safety_level == "BLOCKED":
        jump = abs(float(discontinuity)) if isinstance(discontinuity, (int, float)) else None
        clock_level = "BLOCKED"
        clock_cause = f"检测到系统时间跳变 {jump:.1f} 毫秒" if jump is not None else "可信时间安全状态已阻断"
    elif not synchronized:
        clock_level, clock_cause = "CRITICAL", "操作系统报告 NTP 未同步"
    elif offset_abs is not None and offset_abs >= 60000:
        clock_level, clock_cause = "BLOCKED", f"系统时间偏差 {offset_abs:.1f} 毫秒"
    elif offset_abs is not None and offset_abs >= 5000:
        clock_level, clock_cause = "CRITICAL", f"系统时间偏差 {offset_abs:.1f} 毫秒"
    elif offset_abs is not None and offset_abs >= 1000:
        clock_level, clock_cause = "WARNING", f"系统时间偏差 {offset_abs:.1f} 毫秒"
    else:
        clock_level, clock_cause = "NORMAL", "NTP 已同步" + (f"，偏差 {offset_abs:.1f} 毫秒" if offset_abs is not None else "")
    clock_item = signal(
        "time.synchronization",
        clock_level,
        f"系统时间{LEVEL_ZH[clock_level]}",
        clock_cause,
        "无需操作" if clock_level == "NORMAL" else "恢复 NTP 同步并确认时区与审计顺序",
        "NONE" if clock_level == "NORMAL" else "RESTORE_TIME_SYNCHRONIZATION",
        observed_at,
        {
            "hostTimezone": clock.get("hostTimezone", clock.get("timezone")),
            "applicationTimezone": clock.get("applicationTimezone"),
            "ntpSynchronized": synchronized,
            "offsetMs": offset,
            "clockDiscontinuityMs": discontinuity,
            "safetyLevel": safety_level or None,
        },
    )
    signals.append(clock_item)
    layers["writable"].append(str(clock_item["level"]))

    layer_output = {
        name: {"level": level_max(*values), "label": {"liveness": "存活", "readiness": "就绪", "writable": "可写", "recoverable": "可恢复"}[name]}
        for name, values in layers.items()
    }
    overall = level_max(*(str(item["level"]) for item in signals))
    return {
        "formatVersion": FORMAT,
        "observedAt": observed_at,
        "changedAt": observed_at,
        "siteFingerprint": snapshot.get("siteFingerprint"),
        "overallLevel": overall,
        "overallLabel": LEVEL_ZH[overall],
        "layers": layer_output,
        "signals": signals,
        "notification": {"mode": "local", "result": "NOT_REQUIRED", "attemptedAt": None, "errorCode": ""},
        "privacy": {"businessDetailsCollected": False, "secretsCollected": False},
    }


def ensure_state_dir(path: pathlib.Path) -> None:
    if path.exists() and (not path.is_dir() or path.is_symlink()):
        fail("监控状态目录不是安全的普通目录")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path, 0o700)


def atomic_json(path: pathlib.Path, document: dict[str, object]) -> None:
    ensure_state_dir(path.parent)
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
    finally:
        temporary.unlink(missing_ok=True)


def history_documents(state_dir: pathlib.Path, limit: int = 5000) -> list[dict[str, object]]:
    path = state_dir / "history.jsonl"
    if not path.exists():
        return []
    if not path.is_file() or path.is_symlink() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        fail("监控历史文件权限不安全")
    documents: list[dict[str, object]] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines()[-limit:]:
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(item, dict):
            documents.append(item)
    return documents


def append_history(state_dir: pathlib.Path, status: dict[str, object], snapshot: dict[str, object]) -> None:
    ensure_state_dir(state_dir)
    path = state_dir / "history.jsonl"
    if path.exists() and path.stat().st_size > MAX_HISTORY_BYTES:
        rotated = state_dir / "history.jsonl.1"
        rotated.unlink(missing_ok=True)
        os.replace(path, rotated)
    usage = snapshot.get("storage", {}).get("usage", {})
    event = {
        "formatVersion": "copper-monitor-history/v1",
        "observedAt": status["observedAt"],
        "changedAt": status["changedAt"],
        "overallLevel": status["overallLevel"],
        "layers": status["layers"],
        "measuredUsageBytes": sum(max(0, int(value)) for value in usage.values()),
        "notification": status["notification"],
    }
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    try:
        os.write(descriptor, (json.dumps(event, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"))
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def secure_secret(path: pathlib.Path, label: str, minimum: int = 1) -> bytes:
    if not path.is_file() or path.is_symlink() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        fail(f"{label} 不存在、不是普通文件、是符号链接或权限过宽")
    content = path.read_bytes().strip()
    if len(content) < minimum:
        fail(f"{label} 内容长度不足")
    return content


def validate_webhook_url(value: str, test_mode: bool) -> str:
    if any(character in value for character in "\r\n\t\"'"):
        fail("监控 Webhook URL 包含不安全字符")
    parsed = urllib.parse.urlsplit(value)
    if parsed.username or parsed.password or parsed.fragment or not parsed.hostname:
        fail("监控 Webhook URL 不得包含用户口令、片段或空主机")
    if test_mode and parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"}:
        return value
    if parsed.scheme != "https":
        fail("生产监控 Webhook 必须使用 HTTPS")
    return value


def should_notify(current: dict[str, object], previous: dict[str, object] | None, repeat_hours: int) -> bool:
    if previous is None:
        return current["overallLevel"] != "NORMAL"
    if current["overallLevel"] != previous.get("overallLevel"):
        return True
    if current["overallLevel"] == "NORMAL":
        return False
    previous_notification = previous.get("notification") if isinstance(previous.get("notification"), dict) else {}
    if previous_notification.get("result") == "FAILED":
        return True
    attempted = parse_time(previous_notification.get("attemptedAt"))
    observed = parse_time(current.get("observedAt"))
    return attempted is None or observed is None or (observed - attempted).total_seconds() >= repeat_hours * 3600


def send_notification(status: dict[str, object], env: dict[str, str], previous: dict[str, object] | None) -> None:
    mode = env.get("COPPER_MONITOR_ALERT_MODE", "local")
    if mode not in {"local", "webhook"}:
        fail("COPPER_MONITOR_ALERT_MODE 仅允许 local 或 webhook")
    status["notification"] = {"mode": mode, "result": "NOT_REQUIRED", "attemptedAt": None, "errorCode": ""}
    repeat = int(env.get("COPPER_MONITOR_ALERT_REPEAT_HOURS", "24"))
    if repeat < 1 or repeat > 168:
        fail("COPPER_MONITOR_ALERT_REPEAT_HOURS 必须为 1-168")
    if not should_notify(status, previous, repeat):
        if previous and isinstance(previous.get("notification"), dict) and previous["notification"].get("mode") == mode:
            status["notification"] = dict(previous["notification"])
        return
    attempted_at = status["observedAt"]
    if mode == "local":
        status["notification"] = {"mode": mode, "result": "LOCAL_ONLY", "attemptedAt": attempted_at, "errorCode": ""}
        return
    url_path = pathlib.Path(env.get("COPPER_MONITOR_ALERT_WEBHOOK_URL_FILE", ""))
    hmac_path = pathlib.Path(env.get("COPPER_MONITOR_ALERT_HMAC_KEY_FILE", ""))
    timeout = int(env.get("COPPER_MONITOR_ALERT_TIMEOUT_SECONDS", "10"))
    if timeout < 1 or timeout > 30:
        fail("COPPER_MONITOR_ALERT_TIMEOUT_SECONDS 必须为 1-30")
    payload = {
        "formatVersion": EVENT_FORMAT,
        "siteFingerprint": status.get("siteFingerprint"),
        "observedAt": status.get("observedAt"),
        "changedAt": status.get("changedAt"),
        "overallLevel": status.get("overallLevel"),
        "layers": {name: value.get("level") for name, value in status.get("layers", {}).items()},
        "actions": sorted(
            {
                str(item.get("actionCode"))
                for item in status.get("signals", [])
                if item.get("level") != "NORMAL" and item.get("actionCode") != "NONE"
            }
        ),
    }
    body = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    try:
        url = validate_webhook_url(secure_secret(url_path, "监控 Webhook URL 文件").decode("utf-8"), os.environ.get("COPPER_TEST_MODE") == "1")
        key = secure_secret(hmac_path, "监控 Webhook HMAC 密钥文件", 32)
        signature = hmac.new(key, body, hashlib.sha256).hexdigest()
        request = urllib.request.Request(
            url,
            data=body,
            headers={
                "Content-Type": "application/json",
                "User-Agent": "Copper-Monitor/1",
                "X-Copper-Event": str(status.get("overallLevel")),
                "X-Copper-Signature": f"sha256={signature}",
            },
            method="POST",
        )
        if os.environ.get("COPPER_MONITOR_TEST_WEBHOOK_FAIL") == "1":
            raise OSError("test notification failure")
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if not 200 <= response.status < 300:
                raise OSError("non-success response")
        status["notification"] = {"mode": mode, "result": "SENT", "attemptedAt": attempted_at, "errorCode": ""}
    except (MonitorError, OSError, UnicodeError, urllib.error.URLError, urllib.error.HTTPError):
        status["notification"] = {"mode": mode, "result": "FAILED", "attemptedAt": attempted_at, "errorCode": "DELIVERY_FAILED"}
        status["signals"].append(
            signal(
                "notification.delivery",
                "WARNING",
                "远程告警通知失败",
                "本地健康事实已保存，但 Webhook 未送达",
                "检查通知地址、网络和凭据权限；本地 status.json 不受影响",
                "RESTORE_NOTIFICATION_DELIVERY",
                str(status["observedAt"]),
            )
        )
        status["overallLevel"] = level_max(str(status["overallLevel"]), "WARNING")
        status["overallLabel"] = LEVEL_ZH[str(status["overallLevel"])]


def human_output(status: dict[str, object]) -> str:
    lines = [
        "Copper 系统监控",
        f"  总体：{status['overallLabel']}（{status['overallLevel']}）",
        f"  检查时间：{status['observedAt']}",
    ]
    for name in ("liveness", "readiness", "writable", "recoverable"):
        item = status["layers"][name]
        lines.append(f"  {item['label']}：{LEVEL_ZH[item['level']]}（{item['level']}）")
    problems = [item for item in status["signals"] if item["level"] != "NORMAL"]
    if problems:
        lines.append("需要处理：")
        for item in sorted(problems, key=lambda value: LEVELS[str(value["level"])], reverse=True):
            lines.append(f"  [{LEVEL_ZH[str(item['level'])]}] {item['summary']}")
            lines.append(f"    原因：{item['cause']}")
            lines.append(f"    建议：{item['action']}")
    else:
        lines.append("  所有强制检查均正常。")
    notification = status.get("notification", {})
    lines.append(f"  通知：{notification.get('result', 'UNKNOWN')}（{notification.get('mode', 'local')}）")
    return "\n".join(lines)


def check_command(args: argparse.Namespace) -> int:
    state_dir = pathlib.Path(args.state_dir)
    ensure_state_dir(state_dir)
    previous = secure_json(state_dir / "status.json")
    history = history_documents(state_dir)
    if args.input:
        with pathlib.Path(args.input).open(encoding="utf-8") as handle:
            snapshot = json.load(handle)
    else:
        snapshot = collect(pathlib.Path(args.root), pathlib.Path(args.env_file))
    status = evaluate(snapshot, history)
    if previous and previous.get("overallLevel") == status["overallLevel"]:
        status["changedAt"] = previous.get("changedAt", status["observedAt"])
    env = parse_env(pathlib.Path(args.env_file))
    send_notification(status, env, previous)
    if previous and previous.get("overallLevel") != status["overallLevel"]:
        status["changedAt"] = status["observedAt"]
    atomic_json(state_dir / "status.json", status)
    append_history(state_dir, status, snapshot)
    print(json.dumps(status, ensure_ascii=False, sort_keys=True, indent=2) if args.json else human_output(status))
    return 1 if status["overallLevel"] in {"CRITICAL", "BLOCKED"} else 0


def status_command(args: argparse.Namespace) -> int:
    document = secure_json(pathlib.Path(args.state_dir) / "status.json")
    if not document or document.get("formatVersion") != FORMAT:
        fail("尚无有效监控状态；请先运行 copperctl monitor check")
    print(json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) if args.json else human_output(document))
    return 1 if document.get("overallLevel") in {"CRITICAL", "BLOCKED"} else 0


def history_command(args: argparse.Namespace) -> int:
    documents = history_documents(pathlib.Path(args.state_dir), args.limit)
    selected = documents[-args.limit :]
    if args.json:
        print(json.dumps(selected, ensure_ascii=False, sort_keys=True, indent=2))
    else:
        print("Copper 监控历史")
        for item in selected:
            print(f"  {item.get('observedAt', 'unknown')}  {item.get('overallLevel', 'UNKNOWN')}  通知={item.get('notification', {}).get('result', 'UNKNOWN')}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Copper local health and recoverability monitor")
    parser.add_argument("--root", required=True)
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--state-dir", required=True)
    subparsers = parser.add_subparsers(dest="command", required=True)

    check_parser = subparsers.add_parser("check")
    check_parser.add_argument("--json", action="store_true")
    check_parser.add_argument("--input", help=argparse.SUPPRESS)
    check_parser.set_defaults(function=check_command)

    status_parser = subparsers.add_parser("status")
    status_parser.add_argument("--json", action="store_true")
    status_parser.set_defaults(function=status_command)

    history_parser = subparsers.add_parser("history")
    history_parser.add_argument("--json", action="store_true")
    history_parser.add_argument("--limit", type=int, choices=range(1, 1001), default=20)
    history_parser.set_defaults(function=history_command)

    args = parser.parse_args()
    return args.function(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (MonitorError, ValueError, TypeError, json.JSONDecodeError) as error:
        print(f"错误：{error}", file=os.sys.stderr)
        raise SystemExit(3)
