#!/usr/bin/env python3
"""Durable backup job state and privacy-safe webhook notification."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import json
import os
import pathlib
import re
import stat
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


FORMAT = "copper-backup-job-status/v1"
EVENT_FORMAT = "copper-backup-alert/v1"
JOB_ID_RE = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$")
HASH_RE = re.compile(r"^[0-9a-f]{64}$")
BACKUP_ID_RE = re.compile(r"^[0-9a-f]{32}$")
TARGETS = {"local", "external", "nas", "s3"}


class BackupJobError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise BackupJobError(message)


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


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


def append_event(directory: pathlib.Path, event: dict[str, object]) -> None:
    path = directory / "events.jsonl"
    if path.exists() and path.stat().st_size > 5 * 1024 * 1024:
        rotated = directory / "events.jsonl.1"
        rotated.unlink(missing_ok=True)
        os.replace(path, rotated)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    try:
        os.write(descriptor, (json.dumps(event, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"))
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def load_status(directory: pathlib.Path, required: bool = True) -> dict[str, object] | None:
    path = directory / "status.json"
    if not path.exists() and not required:
        return None
    if not path.is_file() or path.is_symlink() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        fail("备份任务状态文件不存在、不安全或权限过宽")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        fail("备份任务状态文件无法读取")
    if not isinstance(document, dict) or document.get("formatVersion") != FORMAT:
        fail("备份任务状态文件格式不受支持")
    return document


def parse_targets(value: str) -> list[str]:
    result = [item.strip() for item in value.split(",") if item.strip()]
    if not result or result[0] != "local" or len(set(result)) != len(result) or not set(result).issubset(TARGETS):
        fail("备份任务目标必须是以 local 开头且不重复的 local,external,nas,s3 子集")
    return result


def validate_job_id(value: str) -> str:
    if not JOB_ID_RE.fullmatch(value):
        fail("备份 jobId 格式不正确")
    return value


def command_start(args: argparse.Namespace) -> None:
    directory = pathlib.Path(args.state_dir)
    existing = load_status(directory, required=False)
    consecutive = 0
    last_success = None
    last_failure = None
    if existing:
        consecutive = max(0, int(existing.get("consecutiveFailures", 0)))
        last_success = existing.get("lastSuccess")
        last_failure = existing.get("lastFailure")
        current = existing.get("current")
        if isinstance(current, dict) and current.get("result") == "RUNNING":
            append_event(
                directory,
                {
                    "at": now(),
                    "type": "BACKUP_JOB_ABANDONED",
                    "jobId": current.get("jobId"),
                    "result": "FAILED",
                    "errorCode": "PREVIOUS_PROCESS_LOST",
                },
            )
            consecutive += 1
    current = {
        "jobId": validate_job_id(args.job_id),
        "purpose": args.purpose,
        "targets": parse_targets(args.targets),
        "startedAt": args.started_at,
        "finishedAt": None,
        "result": "RUNNING",
        "errorCode": "",
        "notificationEvent": "",
        "alertResult": "PENDING",
    }
    document = {
        "formatVersion": FORMAT,
        "updatedAt": now(),
        "consecutiveFailures": consecutive,
        "current": current,
        "lastSuccess": last_success,
        "lastFailure": last_failure,
    }
    atomic_json(directory / "status.json", document)
    append_event(directory, {"at": current["startedAt"], "type": "BACKUP_JOB_STARTED", **current})


def load_last_success(path: pathlib.Path) -> dict[str, object]:
    if not path.is_file() or path.is_symlink():
        fail("成功任务缺少 last-success.json")
    document = json.loads(path.read_text(encoding="utf-8"))
    backup_id = str(document.get("backupId", ""))
    digest = str(document.get("sha256", ""))
    if not BACKUP_ID_RE.fullmatch(backup_id) or not HASH_RE.fullmatch(digest):
        fail("last-success.json 的 backupId 或 SHA-256 不正确")
    format_version = str(document.get("formatVersion", ""))
    backup_type = str(document.get("backupType", "full" if format_version == "copper-backup/v1" else ""))
    if format_version not in {"copper-backup/v1", "copper-backup/v2"} or backup_type not in {"full", "incremental"}:
        fail("last-success.json 的格式或备份类型不正确")
    result = {
        "backupId": backup_id,
        "completedAt": document.get("completedAt"),
        "bytes": max(0, int(document.get("size", 0))),
        "sha256": digest,
        "formatVersion": format_version,
        "backupType": backup_type,
    }
    if backup_type == "incremental":
        base_id = str(document.get("baseBackupId", ""))
        parent_id = str(document.get("parentBackupId", ""))
        sequence = document.get("chainSequence")
        if not BACKUP_ID_RE.fullmatch(base_id) or not BACKUP_ID_RE.fullmatch(parent_id) or not isinstance(sequence, int) or isinstance(sequence, bool) or sequence < 1:
            fail("last-success.json 的增量父链信息不正确")
        result.update({"baseBackupId": base_id, "parentBackupId": parent_id, "chainSequence": sequence})
    return result


def command_finish(args: argparse.Namespace) -> None:
    directory = pathlib.Path(args.state_dir)
    document = load_status(directory)
    assert document is not None
    current = document.get("current")
    if not isinstance(current, dict) or current.get("jobId") != validate_job_id(args.job_id) or current.get("result") != "RUNNING":
        fail("备份任务状态与待完成 jobId 不一致")
    previous_failures = max(0, int(document.get("consecutiveFailures", 0)))
    current["finishedAt"] = args.finished_at
    if args.result == "SUCCESS":
        success = load_last_success(pathlib.Path(args.last_success))
        current["result"] = "SUCCESS"
        current["errorCode"] = ""
        current["backup"] = success
        current["notificationEvent"] = "BACKUP_RECOVERED" if previous_failures > 0 else ""
        document["consecutiveFailures"] = 0
        document["lastSuccess"] = {"jobId": args.job_id, "targets": list(current["targets"]), **success}
        event_type = "BACKUP_JOB_SUCCEEDED"
    else:
        if not re.fullmatch(r"[A-Z][A-Z0-9_]{2,63}", args.error_code):
            fail("备份失败 errorCode 格式不正确")
        current["result"] = "FAILED"
        current["errorCode"] = args.error_code
        current["notificationEvent"] = "BACKUP_FAILED"
        document["consecutiveFailures"] = previous_failures + 1
        document["lastFailure"] = {
            "jobId": args.job_id,
            "failedAt": args.finished_at,
            "errorCode": args.error_code,
        }
        event_type = "BACKUP_JOB_FAILED"
    document["updatedAt"] = now()
    atomic_json(directory / "status.json", document)
    append_event(
        directory,
        {
            "at": args.finished_at,
            "type": event_type,
            "jobId": args.job_id,
            "purpose": current["purpose"],
            "targets": current["targets"],
            "result": current["result"],
            "errorCode": current["errorCode"],
            "consecutiveFailures": document["consecutiveFailures"],
        },
    )
    print(str(current["notificationEvent"]))


def classify(stderr_text: str, exit_code: int) -> str:
    lowered = stderr_text.lower()
    if exit_code in {130, 143}:
        return "INTERRUPTED"
    if any(value in stderr_text for value in ("空间不足", "DANGER", "CRITICAL", "容量不足")):
        return "SPACE_INSUFFICIENT"
    if any(value in lowered for value in ("timeout", "timed out")) or "超时" in stderr_text or "连接失败" in stderr_text:
        return "TARGET_TIMEOUT"
    if any(value in lowered for value in ("permission denied", "read-only")) or any(value in stderr_text for value in ("权限", "只读", "0600")):
        return "PERMISSION_DENIED"
    if "age" in lowered or "接收公钥" in stderr_text or "私钥" in stderr_text:
        return "KEY_CONFIGURATION_ERROR"
    if "校验" in stderr_text or "sha-256" in lowered or "损坏" in stderr_text:
        return "VERIFICATION_FAILED"
    if "健康" in stderr_text or "readiness" in lowered:
        return "SERVICE_RECOVERY_FAILED"
    return "BACKUP_FAILED"


def command_classify(args: argparse.Namespace) -> None:
    path = pathlib.Path(args.stderr_file)
    text = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""
    print(classify(text, args.exit_code))


def secure_secret_file(path: pathlib.Path, label: str, minimum_bytes: int = 1) -> bytes:
    if not path.is_file() or path.is_symlink():
        fail(f"{label} 不存在、不是普通文件或是符号链接")
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o077:
        fail(f"{label} 权限必须为 0600/0400 或更严格")
    content = path.read_bytes().strip()
    if len(content) < minimum_bytes:
        fail(f"{label} 内容长度不足")
    return content


def safe_alert_payload(state: dict[str, object], site_id: str) -> bytes:
    current = state.get("current")
    if not isinstance(current, dict):
        fail("备份任务状态缺少 current")
    event_name = str(current.get("notificationEvent", ""))
    if event_name not in {"BACKUP_FAILED", "BACKUP_RECOVERED"}:
        fail("当前备份任务没有需要发送的通知事件")
    payload = {
        "formatVersion": EVENT_FORMAT,
        "event": event_name,
        "siteFingerprint": hashlib.sha256(site_id.encode("utf-8")).hexdigest()[:16],
        "jobId": current.get("jobId"),
        "purpose": current.get("purpose"),
        "targets": current.get("targets"),
        "startedAt": current.get("startedAt"),
        "finishedAt": current.get("finishedAt"),
        "result": current.get("result"),
        "errorCode": current.get("errorCode"),
        "consecutiveFailures": max(0, int(state.get("consecutiveFailures", 0))),
        "nextActionCode": "CHECK_COPPER_BACKUP_STATUS" if event_name == "BACKUP_FAILED" else "NONE",
    }
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def validate_webhook_url(value: str, test_mode: bool) -> str:
    if any(character in value for character in "\r\n\t\"'"):
        fail("Webhook URL 包含不安全字符")
    parsed = urllib.parse.urlsplit(value)
    if parsed.username or parsed.password or parsed.fragment or not parsed.hostname:
        fail("Webhook URL 不得包含用户口令、片段或空主机")
    if test_mode:
        if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            fail("测试模式 HTTP Webhook 仅允许本机回环地址")
        if parsed.scheme not in {"http", "https"}:
            fail("Webhook URL 只支持 HTTP/HTTPS")
    elif parsed.scheme != "https":
        fail("生产 Webhook 必须使用 HTTPS")
    return value


def command_send(args: argparse.Namespace) -> None:
    state = load_status(pathlib.Path(args.state_dir))
    assert state is not None
    payload = safe_alert_payload(state, args.site_id)
    url = secure_secret_file(pathlib.Path(args.url_file), "Webhook URL 文件").decode("utf-8").strip()
    url = validate_webhook_url(url, args.test_mode)
    signing_key = secure_secret_file(pathlib.Path(args.hmac_key_file), "Webhook HMAC 密钥文件", 32)
    signature = hmac.new(signing_key, payload, hashlib.sha256).hexdigest()
    headers = {
        "Content-Type": "application/json",
        "User-Agent": "Copper-Backup-Alert/1",
        "X-Copper-Event": json.loads(payload)["event"],
        "X-Copper-Signature": f"sha256={signature}",
    }
    last_error: Exception | None = None
    for attempt in range(3):
        request = urllib.request.Request(url, data=payload, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                if 200 <= response.status < 300:
                    print("SENT")
                    return
                last_error = BackupJobError(f"HTTP_{response.status}")
        except (OSError, urllib.error.URLError, urllib.error.HTTPError) as error:
            last_error = error
        if attempt < 2:
            time.sleep(2**attempt)
    fail(f"Webhook 告警发送失败：{type(last_error).__name__}")


def command_record_alert(args: argparse.Namespace) -> None:
    directory = pathlib.Path(args.state_dir)
    document = load_status(directory)
    assert document is not None
    current = document.get("current")
    if not isinstance(current, dict) or current.get("jobId") != validate_job_id(args.job_id):
        fail("告警结果与当前备份 jobId 不一致")
    current["alertResult"] = args.result
    document["updatedAt"] = now()
    atomic_json(directory / "status.json", document)
    append_event(
        directory,
        {
            "at": document["updatedAt"],
            "type": "BACKUP_ALERT_RESULT",
            "jobId": args.job_id,
            "result": args.result,
            "event": current.get("notificationEvent", ""),
        },
    )


def command_status(args: argparse.Namespace) -> None:
    document = load_status(pathlib.Path(args.state_dir))
    assert document is not None
    if args.human:
        current = document.get("current") if isinstance(document.get("current"), dict) else {}
        print("备份任务状态")
        print(f"  当前结果：{current.get('result', 'UNKNOWN')}")
        print(f"  任务编号：{current.get('jobId', '无')}")
        print(f"  来源：{current.get('purpose', '未知')}")
        print(f"  目标：{','.join(current.get('targets', [])) if isinstance(current.get('targets'), list) else '未知'}")
        print(f"  开始：{current.get('startedAt', '未知')}")
        print(f"  结束：{current.get('finishedAt') or '尚未结束'}")
        print(f"  错误类别：{current.get('errorCode') or '无'}")
        print(f"  连续失败：{document.get('consecutiveFailures', 0)}")
        print(f"  告警结果：{current.get('alertResult', '未知')}")
        last_success = document.get("lastSuccess")
        last_failure = document.get("lastFailure")
        if isinstance(last_success, dict):
            print(f"  最近成功：{last_success.get('completedAt', '未知')} / {last_success.get('backupId', '未知')}")
            if last_success.get("backupType") == "incremental":
                print(f"  备份类型：增量，第 {last_success.get('chainSequence', '未知')} 段，基础包 {last_success.get('baseBackupId', '未知')}")
            elif last_success.get("backupType") == "full":
                print("  备份类型：全量基础包")
        if isinstance(last_failure, dict):
            print(f"  最近失败：{last_failure.get('failedAt', '未知')} / {last_failure.get('errorCode', '未知')}")
        return
    print(json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser(description="Copper backup job state and alert helper")
    parser.add_argument("--state-dir", required=True)
    subparsers = parser.add_subparsers(dest="command", required=True)

    start = subparsers.add_parser("start")
    start.add_argument("--job-id", required=True)
    start.add_argument("--purpose", choices=("manual", "scheduled"), required=True)
    start.add_argument("--targets", required=True)
    start.add_argument("--started-at", required=True)
    start.set_defaults(function=command_start)

    finish = subparsers.add_parser("finish")
    finish.add_argument("--job-id", required=True)
    finish.add_argument("--result", choices=("SUCCESS", "FAILED"), required=True)
    finish.add_argument("--error-code", default="")
    finish.add_argument("--finished-at", required=True)
    finish.add_argument("--last-success", default="")
    finish.set_defaults(function=command_finish)

    classify_parser = subparsers.add_parser("classify")
    classify_parser.add_argument("--stderr-file", required=True)
    classify_parser.add_argument("--exit-code", type=int, required=True)
    classify_parser.set_defaults(function=command_classify)

    send = subparsers.add_parser("send")
    send.add_argument("--site-id", required=True)
    send.add_argument("--url-file", required=True)
    send.add_argument("--hmac-key-file", required=True)
    send.add_argument("--timeout", type=int, choices=range(1, 31), required=True)
    send.add_argument("--test-mode", action="store_true")
    send.set_defaults(function=command_send)

    record = subparsers.add_parser("record-alert")
    record.add_argument("--job-id", required=True)
    record.add_argument("--result", choices=("SENT", "FAILED", "LOCAL_ONLY", "NOT_REQUIRED"), required=True)
    record.set_defaults(function=command_record_alert)

    status = subparsers.add_parser("status")
    status.add_argument("--human", action="store_true")
    status.set_defaults(function=command_status)

    args = parser.parse_args()
    args.function(args)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (BackupJobError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        print(f"错误：{error}", file=os.sys.stderr)
        raise SystemExit(1)
