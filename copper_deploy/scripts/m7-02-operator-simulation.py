#!/usr/bin/env python3
"""Run the M7-02 non-customer operator simulation without touching a real site."""

from __future__ import annotations

import argparse
import datetime as dt
import importlib.machinery
import importlib.util
import io
import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid
from contextlib import redirect_stdout
from unittest import mock


sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parent.parent
LOADER = importlib.machinery.SourceFileLoader(
    "copper_m7_02_simulated_installer", str(ROOT / "copper-install-from-mac")
)
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
assert SPEC and SPEC.loader
installer = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = installer
SPEC.loader.exec_module(installer)


class SimulatedLifecycleStop(RuntimeError):
    """Exercise the same cleanup path used after an interrupted acceptance run."""


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def process_ids_with_marker(marker: str) -> list[int]:
    observed = subprocess.run(
        ["ps", "-axo", "pid=,command="],
        text=True,
        capture_output=True,
        check=True,
    ).stdout.splitlines()
    result: list[int] = []
    for line in observed:
        line = line.strip()
        if marker not in line:
            continue
        raw_pid, _, _ = line.partition(" ")
        if raw_pid.isdigit() and int(raw_pid) != os.getpid():
            result.append(int(raw_pid))
    return sorted(result)


def port_accepts_connection(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as client:
        client.settimeout(0.2)
        return client.connect_ex(("127.0.0.1", port)) == 0


def wait_for_port_closed(port: int) -> bool:
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        if not port_accepts_connection(port):
            return True
        time.sleep(0.05)
    return False


def start_owned_listener(run_id: str) -> tuple[subprocess.Popen[str], int]:
    child_code = (
        "import socket,time; "
        "listener=socket.socket(); "
        "listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); "
        "listener.bind(('127.0.0.1',0)); listener.listen(); "
        "print(listener.getsockname()[1],flush=True); time.sleep(300)"
    )
    process = subprocess.Popen(
        [sys.executable, "-c", child_code, run_id],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    assert process.stdout is not None
    raw_port = process.stdout.readline().strip()
    if not raw_port.isdigit():
        process.terminate()
        process.wait(timeout=3)
        raise RuntimeError("模拟监听没有返回有效端口")
    return process, int(raw_port)


def stop_owned_process(process: subprocess.Popen[str]) -> None:
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
    if process.stdout is not None:
        process.stdout.close()
    if process.stderr is not None:
        process.stderr.close()


def exercise_operator_choices(root: pathlib.Path) -> dict[str, object]:
    ssh_key = root / "operator-test.pem"
    ssh_key.write_text("SIMULATED-SSH-KEY-NOT-USABLE\n", encoding="ascii")
    os.chmod(ssh_key, 0o600)
    args = installer.build_parser().parse_args(
        [
            "--domain", "old.example.org",
            "--site-id", "copper-simulated",
            "--public-ip", "18.142.134.26",
            "--ssh-host", "18.142.134.26",
            "--ssh-key", str(ssh_key),
            "--primary-key-root", str(root / "primary"),
            "--backup-key-root", str(root / "secondary"),
        ]
    )
    with mock.patch.object(installer, "prompt", return_value="ec2-user") as username_prompt:
        answers = installer.collect_answers(args)
    username_prompt.assert_called_once_with("服务器登录用户名", "ubuntu")
    if answers.ssh_user != "ec2-user":
        raise RuntimeError("服务器登录用户名没有进入安装答案")

    dns_attempts: list[tuple[str, str]] = []

    def simulated_dns(domain: str, public_ip: str) -> dict[str, list[str]]:
        dns_attempts.append((domain, public_ip))
        if len(dns_attempts) <= 2:
            raise installer.LocalInstallError("模拟 DNS 尚未生效")
        return {"ipv4": [public_ip], "ipv6": []}

    prompts = {
        "新的域名或固定公网 IPv4": "new.example.org",
        "新的服务器公网 IPv4": "18.142.134.27",
        "SSH 服务器地址": "18.142.134.27",
    }
    values = {
        "domain": "old.example.org",
        "publicIp": "18.142.134.26",
        "sshHost": "18.142.134.26",
    }
    with (
        mock.patch.object(installer, "validate_dns", side_effect=simulated_dns),
        mock.patch.object(installer, "choose", side_effect=[1, 2]),
        mock.patch.object(installer, "prompt", side_effect=lambda label, default=None: prompts[label]),
        redirect_stdout(io.StringIO()),
    ):
        dns_result = installer.validate_dns_with_choices(values, interactive=True)

    expected_attempts = [
        ("old.example.org", "18.142.134.26"),
        ("old.example.org", "18.142.134.26"),
        ("new.example.org", "18.142.134.27"),
    ]
    if dns_attempts != expected_attempts:
        raise RuntimeError(f"DNS 连续选择顺序不正确：{dns_attempts!r}")
    if values != {
        "domain": "new.example.org",
        "publicIp": "18.142.134.27",
        "sshHost": "18.142.134.27",
    }:
        raise RuntimeError("更换域名后没有原地更新安装答案")

    final_answers = installer.InstallAnswers(
        answers.site_id,
        values["domain"],
        values["publicIp"],
        values["sshHost"],
        answers.ssh_user,
        answers.ssh_key,
        answers.customer_reference,
        answers.primary_key_root,
        answers.backup_key_root,
        answers.time_zone,
    )
    plan_output = io.StringIO()
    with redirect_stdout(plan_output):
        installer.show_plan(final_answers)
    rendered_plan = plan_output.getvalue()
    if "https://new.example.org" not in rendered_plan or "ec2-user@18.142.134.27" not in rendered_plan:
        raise RuntimeError("最终计划没有显示更新后的域名、服务器或登录用户名")
    return {
        "sshPrompt": "服务器登录用户名 [ubuntu]",
        "selectedSshUser": answers.ssh_user,
        "dnsAttempts": dns_attempts,
        "dnsResult": dns_result,
        "changedValues": values,
    }


def run_simulation() -> dict[str, object]:
    run_id = f"copper-m7-02-sim-{uuid.uuid4().hex[:12]}"
    started_at = utc_now()
    temp_root = pathlib.Path(tempfile.gettempdir()) / run_id
    if temp_root.exists() or process_ids_with_marker(run_id):
        raise RuntimeError("本次模拟 run_id 在开始前已有残留")

    owned_process: subprocess.Popen[str] | None = None
    owned_port = 0
    operator_result: dict[str, object] = {}
    failure_cleanup_exercised = False
    try:
        (temp_root / "profile").mkdir(parents=True, mode=0o700)
        secret = temp_root / "one-time-secret.txt"
        secret.write_text("SIMULATED-ONE-TIME-SECRET\n", encoding="ascii")
        os.chmod(secret, 0o600)
        owned_process, owned_port = start_owned_listener(run_id)
        if owned_port == 8191 or not port_accepts_connection(owned_port):
            raise RuntimeError("本次专属模拟监听未正确隔离")
        operator_result = exercise_operator_choices(temp_root)
        try:
            raise SimulatedLifecycleStop("模拟操作者中断")
        except SimulatedLifecycleStop:
            failure_cleanup_exercised = True
    finally:
        if owned_process is not None:
            stop_owned_process(owned_process)
        shutil.rmtree(temp_root, ignore_errors=True)

    checks = {
        "sshUsernamePrompt": operator_result.get("selectedSshUser") == "ec2-user",
        "dnsRetryInPlace": len(operator_result.get("dnsAttempts", [])) >= 2,
        "dnsChangeInPlace": operator_result.get("changedValues", {}).get("domain") == "new.example.org",
        "failureCleanupExercised": failure_cleanup_exercised,
        "ownedProcessResidualZero": process_ids_with_marker(run_id) == [],
        "ownedListenerResidualZero": bool(owned_port) and wait_for_port_closed(owned_port),
        "temporaryProfileResidualZero": not temp_root.exists(),
        "oneTimeSecretResidualZero": not (temp_root / "one-time-secret.txt").exists(),
        "browserAutomationStarted": False,
        "formalBackendPortTouched": False,
        "containerRuntimeTouched": False,
    }
    result = "PASSED" if all(
        value is True
        for key, value in checks.items()
        if key not in {"browserAutomationStarted", "formalBackendPortTouched", "containerRuntimeTouched"}
    ) and not any(
        checks[key]
        for key in {"browserAutomationStarted", "formalBackendPortTouched", "containerRuntimeTouched"}
    ) else "FAILED"
    return {
        "formatVersion": "copper-m7-02-operator-simulation/v1",
        "sampleType": "SIMULATED_OPERATOR_CONTINUOUS",
        "runId": run_id,
        "installerVersion": installer.INSTALLER_VERSION,
        "startedAt": started_at,
        "finishedAt": utc_now(),
        "result": result,
        "checks": checks,
        "operatorFlow": operator_result,
        "humanActionRequired": False,
        "internalMilestoneUseAllowed": result == "PASSED",
        "formalIndependentCustomerClaimAllowed": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="执行 M7-02 普通操作者连续模拟验收")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    report = run_simulation()
    rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if report["result"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
