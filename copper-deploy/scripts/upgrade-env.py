#!/usr/bin/env python3
"""Build target sidecar/current env files from an immutable PREPARED contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import sys
import tempfile


KEY_RE = re.compile(r"COPPER_[A-Z0-9_]+")
VALUE_RE = re.compile(r"[^\x00\r\n]*")
REPLACED = {
    "COPPER_RELEASE_VERSION",
    "COPPER_IMAGE_DELIVERY",
    "COPPER_WEB_IMAGE",
    "COPPER_BACKEND_IMAGE",
    "COPPER_MYSQL_IMAGE",
}


class EnvError(ValueError):
    pass


def fail(message: str) -> None:
    raise EnvError(message)


def parse(path: pathlib.Path, label: str) -> tuple[list[str], dict[str, str]]:
    if path.is_symlink() or not path.is_file():
        fail(f"{label} 不存在、不是普通文件或是符号链接")
    lines = path.read_text(encoding="utf-8").splitlines()
    values: dict[str, str] = {}
    for number, original in enumerate(lines, 1):
        line = original.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"{label} 第 {number} 行不是 KEY=VALUE")
        key, value = line.split("=", 1)
        if not KEY_RE.fullmatch(key) or key in values or not VALUE_RE.fullmatch(value):
            fail(f"{label} 第 {number} 行字段不安全或重复")
        values[key] = value
    return lines, values


def atomic_write(path: pathlib.Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as writer:
            writer.write(content)
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def command_build(args: argparse.Namespace) -> None:
    source_lines, source = parse(pathlib.Path(args.source), "来源运行配置")
    _, manifest = parse(pathlib.Path(args.manifest), "目标发布清单")
    target = {
        "COPPER_RELEASE_VERSION": manifest.get("COPPER_RELEASE_VERSION", ""),
        "COPPER_IMAGE_DELIVERY": "registry",
        "COPPER_WEB_IMAGE": manifest.get("COPPER_WEB_IMAGE", ""),
        "COPPER_BACKEND_IMAGE": manifest.get("COPPER_BACKEND_IMAGE", ""),
        "COPPER_MYSQL_IMAGE": manifest.get("COPPER_MYSQL_IMAGE", ""),
    }
    if any(not value for value in target.values()):
        fail("目标发布清单缺少版本或三项镜像")
    if args.ready:
        ready_path = pathlib.Path(args.ready)
        if ready_path.is_symlink() or not ready_path.is_file():
            fail("READY 收据不存在、不是普通文件或是符号链接")
        ready = json.loads(ready_path.read_text(encoding="utf-8"))
        required = {
            "format", "status", "version", "mode", "deliveredAt", "indexFile", "indexSha256",
            "manifestSha256", "images",
        }
        if not isinstance(ready, dict) or set(ready) != required:
            fail("READY 收据字段不完整")
        digest = hashlib.sha256(pathlib.Path(args.manifest).read_bytes()).hexdigest()
        if ready["format"] != "copper-delivery-ready/v1" or ready["status"] != "READY":
            fail("READY 收据状态不可信")
        if ready["version"] != target["COPPER_RELEASE_VERSION"] or ready["manifestSha256"] != digest:
            fail("READY 收据未绑定目标发布清单")
        if ready["mode"] not in {"online", "offline", "offline-delta"}:
            fail("READY 交付模式不受支持")
        evidence = ready.get("images")
        evidence_keys = {"role", "reference", "localId", "architecture"}
        if (
            not isinstance(evidence, list) or len(evidence) != 3
            or any(not isinstance(item, dict) or set(item) != evidence_keys for item in evidence)
            or {item["role"] for item in evidence} != {"web", "backend", "mysql"}
        ):
            fail("READY 镜像证据不完整")
        target["COPPER_IMAGE_DELIVERY"] = "offline" if ready["mode"] in {"offline", "offline-delta"} else "registry"
        for item in evidence:
            role = item["role"]
            key = {"web": "COPPER_WEB_IMAGE", "backend": "COPPER_BACKEND_IMAGE", "mysql": "COPPER_MYSQL_IMAGE"}[role]
            if item.get("reference") != manifest.get(key) or item.get("architecture") != "amd64":
                fail(f"READY {role} 镜像证据未绑定目标发布摘要或架构")
            if ready["mode"] in {"offline", "offline-delta"}:
                local_id = item.get("localId", "")
                if not re.fullmatch(r"sha256:[0-9a-f]{64}", local_id):
                    fail(f"READY {role} 离线内容 ID 不正确")
                target[key] = local_id
    test_overrides = {
        "COPPER_WEB_IMAGE": args.test_web_image,
        "COPPER_BACKEND_IMAGE": args.test_backend_image,
        "COPPER_MYSQL_IMAGE": args.test_mysql_image,
    }
    if any(test_overrides.values()):
        if os.environ.get("COPPER_TEST_MODE") != "1":
            fail("目标镜像替换只允许隔离测试模式")
        local_id = re.compile(r"^sha256:[0-9a-f]{64}$")
        if any(not value or not local_id.fullmatch(value) for value in test_overrides.values()):
            fail("测试目标镜像必须同时提供三项完整 sha256 本地内容 ID，禁止标签、registry 引用或部分替换")
        target.update(test_overrides)
        target["COPPER_IMAGE_DELIVERY"] = "offline"
    missing = sorted(REPLACED - source.keys())
    if missing:
        fail(f"来源运行配置缺少升级字段：{','.join(missing)}")
    output: list[str] = []
    seen: set[str] = set()
    for original in source_lines:
        stripped = original.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            key = stripped.split("=", 1)[0]
            if key in REPLACED:
                output.append(f"{key}={target[key]}")
                seen.add(key)
                continue
        output.append(original)
    if seen != REPLACED:
        fail("未能完整替换升级运行字段")
    atomic_write(pathlib.Path(args.output), "\n".join(output) + "\n")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Copper target upgrade environment builder")
    parser.add_argument("--source", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--ready")
    parser.add_argument("--test-web-image")
    parser.add_argument("--test-backend-image")
    parser.add_argument("--test-mysql-image")
    parser.set_defaults(handler=command_build)
    return parser


def main() -> int:
    try:
        args = build_parser().parse_args()
        args.handler(args)
        return 0
    except (EnvError, OSError, UnicodeError, json.JSONDecodeError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
