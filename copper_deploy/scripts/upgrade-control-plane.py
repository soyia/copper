#!/usr/bin/env python3
"""Stage and atomically switch Copper's installed upgrade control plane."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import shutil
import sys
import tarfile
import tempfile
from typing import Any


FORMAT = "copper-upgrade-control-plane/v1"
VERSION_RE = re.compile(r"[0-9A-Za-z][0-9A-Za-z._-]{0,63}")
SHA_RE = re.compile(r"[0-9a-f]{64}")
LINKS = {
    "copperctl": "copperctl",
    "copper-install": "copper-install",
    "copper-maintain": "copper-maintain",
}
REQUIRED_FILES = ("copperctl", "copper-install", "copper-maintain", "compose.yaml")


class ControlPlaneError(ValueError):
    pass


def fail(message: str) -> None:
    raise ControlPlaneError(message)


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as reader:
        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def atomic_json(path: pathlib.Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as writer:
            json.dump(document, writer, ensure_ascii=False, indent=2, sort_keys=True)
            writer.write("\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def load_state(path: pathlib.Path) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        fail("控制程序切换状态不存在、不是普通文件或是符号链接")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"控制程序切换状态无法读取：{exc}")
    required = {
        "formatVersion", "status", "sourceVersion", "targetVersion", "sourceRoot", "targetRoot",
        "releasesDirectory", "binaryDirectory", "deployArchiveSha256", "activeSide", "stagedAt", "updatedAt",
    }
    if not isinstance(document, dict) or set(document) != required:
        fail("控制程序切换状态字段不完整")
    if document.get("formatVersion") != FORMAT or document.get("status") != "STAGED":
        fail("控制程序切换状态版本或状态不受支持")
    if document.get("activeSide") not in {"source", "target"}:
        fail("控制程序当前侧不正确")
    if not SHA_RE.fullmatch(str(document.get("deployArchiveSha256", ""))):
        fail("控制程序发布包摘要不正确")
    return document


def safe_relative(value: str) -> pathlib.PurePosixPath:
    while value.startswith("./"):
        value = value[2:]
    path = pathlib.PurePosixPath(value)
    if not value or path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        fail(f"部署包包含不安全路径：{value}")
    return path


def extract_archive(archive: pathlib.Path, destination: pathlib.Path) -> pathlib.Path:
    unpack = destination / "unpack"
    unpack.mkdir(mode=0o700)
    try:
        package = tarfile.open(archive, mode="r:gz")
    except (OSError, tarfile.TarError) as exc:
        fail(f"无法打开目标部署包：{exc}")
    top_levels: set[str] = set()
    with package:
        members = package.getmembers()
        if not members:
            fail("目标部署包为空")
        for member in members:
            relative = safe_relative(member.name)
            top_levels.add(relative.parts[0])
            if member.issym() or member.islnk() or member.isdev() or member.isfifo():
                fail(f"目标部署包包含链接或特殊文件：{member.name}")
            target = unpack.joinpath(*relative.parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True, mode=0o700)
                continue
            if not member.isfile():
                fail(f"目标部署包包含不支持的成员：{member.name}")
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            source = package.extractfile(member)
            if source is None:
                fail(f"无法读取目标部署包成员：{member.name}")
            with source, target.open("xb") as writer:
                shutil.copyfileobj(source, writer, length=1024 * 1024)
                writer.flush()
                os.fsync(writer.fileno())
            os.chmod(target, member.mode & 0o777)
    if all((unpack / name).is_file() for name in REQUIRED_FILES):
        return unpack
    if len(top_levels) == 1:
        candidate = unpack / next(iter(top_levels))
        if candidate.is_dir() and all((candidate / name).is_file() for name in REQUIRED_FILES):
            return candidate
    fail("目标部署包没有唯一、完整的 Copper 程序根目录")


def validate_release_root(root: pathlib.Path, label: str) -> None:
    if root.is_symlink() or not root.is_dir():
        fail(f"{label}程序目录不存在、不是目录或是符号链接：{root}")
    for relative in REQUIRED_FILES:
        path = root / relative
        if path.is_symlink() or not path.is_file():
            fail(f"{label}程序目录缺少普通文件：{relative}")
    if not (root / "scripts").is_dir() or (root / "scripts").is_symlink():
        fail(f"{label}程序目录缺少安全的 scripts 目录")
    for executable in ("copperctl", "copper-install", "copper-maintain"):
        if not os.access(root / executable, os.X_OK):
            fail(f"{label}程序入口不可执行：{executable}")


def expected_link_target(root: pathlib.Path, link_name: str) -> pathlib.Path:
    return (root / LINKS[link_name]).resolve(strict=True)


def assert_links(bin_dir: pathlib.Path, root: pathlib.Path, label: str) -> None:
    for link_name in LINKS:
        link = bin_dir / link_name
        if not link.is_symlink():
            fail(f"{label}系统入口不是 Copper 管理的符号链接：{link}")
        try:
            actual = link.resolve(strict=True)
        except OSError as exc:
            fail(f"{label}系统入口无法解析：{link}：{exc}")
        expected = expected_link_target(root, link_name)
        if actual != expected:
            fail(f"{label}系统入口未指向预期版本：{link}")


def install_links(bin_dir: pathlib.Path, root: pathlib.Path) -> None:
    bin_dir.mkdir(parents=True, exist_ok=True)
    staged: list[tuple[pathlib.Path, pathlib.Path]] = []
    try:
        for link_name in LINKS:
            link = bin_dir / link_name
            if link.exists() and not link.is_symlink():
                fail(f"系统入口已存在且不是符号链接：{link}")
            temporary = bin_dir / f".{link_name}.{os.getpid()}"
            temporary.unlink(missing_ok=True)
            temporary.symlink_to(root / LINKS[link_name])
            staged.append((temporary, link))
        for temporary, link in staged:
            os.replace(temporary, link)
    finally:
        for temporary, _ in staged:
            temporary.unlink(missing_ok=True)


def command_stage(args: argparse.Namespace) -> None:
    archive = pathlib.Path(args.archive)
    state_path = pathlib.Path(args.state)
    releases_dir = pathlib.Path(args.releases_dir).resolve()
    bin_dir = pathlib.Path(args.bin_dir).resolve()
    if not VERSION_RE.fullmatch(args.source_version) or not VERSION_RE.fullmatch(args.target_version):
        fail("来源或目标产品版本格式不正确")
    if args.source_version == args.target_version:
        fail("控制程序来源与目标版本不能相同")
    if not SHA_RE.fullmatch(args.archive_sha256):
        fail("目标部署包 SHA-256 格式不正确")
    if archive.is_symlink() or not archive.is_file() or sha256_file(archive) != args.archive_sha256:
        fail("目标部署包不存在、不安全或 SHA-256 不一致")
    if releases_dir.is_symlink() or not releases_dir.is_dir():
        fail("Copper 产品版本目录不存在或不安全")
    if bin_dir.is_symlink() or not bin_dir.is_dir():
        fail("Copper 系统入口目录不存在或不安全")
    source_root = releases_dir / args.source_version
    target_root = releases_dir / args.target_version
    validate_release_root(source_root, "来源")
    assert_links(bin_dir, source_root, "来源")
    marker_name = ".copper-upgrade-release.json"
    if target_root.exists() or target_root.is_symlink():
        validate_release_root(target_root, "目标")
        marker = target_root / marker_name
        try:
            existing = json.loads(marker.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            fail(f"目标版本目录已存在但缺少可信升级标记：{exc}")
        if (
            existing.get("formatVersion") != "copper-upgrade-release/v1"
            or existing.get("releaseVersion") != args.target_version
            or existing.get("deployArchiveSha256") != args.archive_sha256
        ):
            fail("目标版本目录已存在但与本次不可变部署包不一致")
    else:
        temporary = pathlib.Path(tempfile.mkdtemp(prefix=f".{args.target_version}.", dir=releases_dir))
        try:
            extracted = extract_archive(archive, temporary)
            validate_release_root(extracted, "目标")
            marker = extracted / marker_name
            marker.write_text(json.dumps({
                "formatVersion": "copper-upgrade-release/v1",
                "releaseVersion": args.target_version,
                "deployArchiveSha256": args.archive_sha256,
                "stagedAt": utc_now(),
            }, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            os.chmod(marker, 0o444)
            os.chmod(extracted, 0o700)
            os.replace(extracted, target_root)
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)
    validate_release_root(target_root, "目标")
    now = utc_now()
    atomic_json(state_path, {
        "formatVersion": FORMAT,
        "status": "STAGED",
        "sourceVersion": args.source_version,
        "targetVersion": args.target_version,
        "sourceRoot": str(source_root),
        "targetRoot": str(target_root),
        "releasesDirectory": str(releases_dir),
        "binaryDirectory": str(bin_dir),
        "deployArchiveSha256": args.archive_sha256,
        "activeSide": "source",
        "stagedAt": now,
        "updatedAt": now,
    })
    print(f"control_plane=STAGED source={args.source_version} target={args.target_version}")


def command_activate(args: argparse.Namespace) -> None:
    state_path = pathlib.Path(args.state)
    document = load_state(state_path)
    current_side = str(document["activeSide"])
    desired_side = args.side
    source_root = pathlib.Path(str(document["sourceRoot"]))
    target_root = pathlib.Path(str(document["targetRoot"]))
    bin_dir = pathlib.Path(str(document["binaryDirectory"]))
    validate_release_root(source_root, "来源")
    validate_release_root(target_root, "目标")
    current_root = source_root if current_side == "source" else target_root
    desired_root = source_root if desired_side == "source" else target_root
    assert_links(bin_dir, current_root, "当前")
    if desired_side == current_side:
        print(f"control_plane={desired_side.upper()}")
        return
    try:
        install_links(bin_dir, desired_root)
        assert_links(bin_dir, desired_root, "切换后")
        document["activeSide"] = desired_side
        document["updatedAt"] = utc_now()
        atomic_json(state_path, document)
    except Exception:
        try:
            install_links(bin_dir, current_root)
            assert_links(bin_dir, current_root, "自动恢复后")
        except Exception as recovery_error:
            raise ControlPlaneError(f"控制程序切换失败且自动恢复未完成：{recovery_error}")
        raise
    print(f"control_plane={desired_side.upper()}")


def command_status(args: argparse.Namespace) -> None:
    document = load_state(pathlib.Path(args.state))
    active = str(document["activeSide"])
    if args.expect and args.expect != active:
        fail(f"控制程序当前为 {active}，预期为 {args.expect}")
    root = pathlib.Path(str(document[f"{active}Root"]))
    validate_release_root(root, "当前")
    assert_links(pathlib.Path(str(document["binaryDirectory"])), root, "当前")
    print(f"control_plane={active.upper()} version={document[f'{active}Version']}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Copper upgrade control-plane switch")
    subparsers = parser.add_subparsers(dest="command", required=True)
    stage = subparsers.add_parser("stage")
    stage.add_argument("--archive", required=True)
    stage.add_argument("--archive-sha256", required=True)
    stage.add_argument("--source-version", required=True)
    stage.add_argument("--target-version", required=True)
    stage.add_argument("--state", required=True)
    stage.add_argument("--releases-dir", default="/opt/copper/releases")
    stage.add_argument("--bin-dir", default="/usr/local/sbin")
    stage.set_defaults(handler=command_stage)
    activate = subparsers.add_parser("activate")
    activate.add_argument("--state", required=True)
    activate.add_argument("--side", required=True, choices=("source", "target"))
    activate.set_defaults(handler=command_activate)
    status = subparsers.add_parser("status")
    status.add_argument("--state", required=True)
    status.add_argument("--expect", choices=("source", "target"))
    status.set_defaults(handler=command_status)
    return parser


def main() -> int:
    try:
        args = build_parser().parse_args()
        args.handler(args)
        return 0
    except (ControlPlaneError, OSError, UnicodeError, json.JSONDecodeError, tarfile.TarError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
