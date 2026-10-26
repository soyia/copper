#!/usr/bin/env python3
"""Copper backup storage targets using only the Python standard library.

The adapter keeps copper-backup/v1 and copper-backup/v2 bytes unchanged. Filesystem targets publish
with same-filesystem temporary files and os.replace(). S3-compatible targets
publish temporary objects, verify size and SHA-256 metadata, copy to final keys,
and publish a success marker last.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import http.client
import json
import os
import pathlib
import re
import shutil
import ssl
import sys
import tempfile
import time
import urllib.parse
import uuid
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import BinaryIO, Iterable


ARCHIVE_RE = re.compile(
    r"^[a-z0-9][a-z0-9-]{2,31}_[0-9]{8}T[0-9]{6}Z_[0-9a-f]{32}_(manual|scheduled)\.copperbak$"
)
HASH_RE = re.compile(r"^[0-9a-f]{64}$")
TARGET_ID_RE = re.compile(r"^[0-9a-f]{32}$")
TARGETS = ("local", "external", "nas", "s3")
IDENTITY_FORMAT = "copper-storage-identity/v1"
IDENTITY_FILE = ".copper-storage-identity.json"
NETWORK_FILESYSTEMS = {"nfs", "nfs4", "cifs", "smb3"}
EXTERNAL_FILESYSTEMS = {"ext4", "xfs", "btrfs"}


class StorageError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise StorageError(message)


def load_env(path: pathlib.Path) -> dict[str, str]:
    if not path.is_file():
        fail(f"配置文件不存在：{path}")
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            continue
        values[key.strip()] = value.strip()
    return values


def required(config: dict[str, str], name: str) -> str:
    value = config.get(name, "").strip()
    if not value:
        fail(f"配置缺少 {name}")
    return value


def configured_targets(config: dict[str, str]) -> list[str]:
    raw = config.get("COPPER_BACKUP_TARGETS", "local")
    targets = [item.strip() for item in raw.split(",") if item.strip()]
    if not targets:
        fail("COPPER_BACKUP_TARGETS 至少包含 local")
    if len(targets) != len(set(targets)):
        fail("COPPER_BACKUP_TARGETS 不得重复")
    unknown = [item for item in targets if item not in TARGETS]
    if unknown:
        fail(f"不支持的备份目标：{','.join(unknown)}")
    if targets[0] != "local" or "local" not in targets:
        fail("COPPER_BACKUP_TARGETS 必须以 local 开头，服务器本地保留恢复主副本")
    return targets


def safe_archive_name(value: str) -> str:
    if pathlib.PurePath(value).name != value or not ARCHIVE_RE.fullmatch(value):
        fail(f"备份文件名不安全或格式不正确：{value}")
    return value


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_sidecar(path: pathlib.Path, archive_name: str) -> str:
    if not path.is_file() or path.is_symlink():
        fail(f"校验文件不存在或不是普通文件：{path}")
    lines = path.read_text(encoding="ascii").splitlines()
    if len(lines) != 1:
        fail(f"校验文件格式不正确：{path}")
    parts = lines[0].split()
    if len(parts) != 2 or not HASH_RE.fullmatch(parts[0]) or parts[1] != archive_name:
        fail(f"校验文件内容不正确：{path}")
    return parts[0]


def verify_source(archive: pathlib.Path, sidecar: pathlib.Path) -> tuple[str, int]:
    if not archive.is_file() or archive.is_symlink():
        fail(f"备份文件不存在或不是普通文件：{archive}")
    archive_name = safe_archive_name(archive.name)
    expected = read_sidecar(sidecar, archive_name)
    actual = sha256_file(archive)
    if actual != expected:
        fail(f"SHA-256 校验失败：{archive}")
    return actual, archive.stat().st_size


def fsync_directory(path: pathlib.Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def atomic_copy(source: pathlib.Path, destination: pathlib.Path, mode: int = 0o600) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.upload-", dir=destination.parent
    )
    temporary = pathlib.Path(temporary_name)
    try:
        with source.open("rb") as reader, os.fdopen(descriptor, "wb") as writer:
            shutil.copyfileobj(reader, writer, length=1024 * 1024)
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, destination)
        fsync_directory(destination.parent)
    finally:
        temporary.unlink(missing_ok=True)


def atomic_json(destination: pathlib.Path, document: dict[str, object]) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.tmp-", dir=destination.parent
    )
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as writer:
            json.dump(document, writer, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            writer.write("\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, destination)
        fsync_directory(destination.parent)
    finally:
        temporary.unlink(missing_ok=True)


def marker_document(
    target: str,
    name: str,
    digest: str,
    size: int,
    sidecar_digest: str,
    sidecar_size: int,
) -> dict[str, object]:
    return {
        "formatVersion": "copper-storage-success/v1",
        "target": target,
        "archive": name,
        "bytes": size,
        "sha256": digest,
        "sidecarBytes": sidecar_size,
        "sidecarSha256": sidecar_digest,
        "publishedAt": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    }


def risk_level(total: int | None, available: int | None) -> str:
    if total is None or available is None or total <= 0:
        return "UNKNOWN"
    percent = available * 100 / total
    if available < 1024**3 or percent < 5:
        return "DANGER"
    if available < 2 * 1024**3 or percent < 10:
        return "CRITICAL"
    if available < 5 * 1024**3 or percent < 20:
        return "WARNING"
    return "NORMAL"


@dataclass(frozen=True)
class MountInfo:
    mountpoint: pathlib.Path
    filesystem_type: str
    device_id: str
    source: str


def unescape_mount_field(value: str) -> str:
    return (
        value.replace("\\040", " ")
        .replace("\\011", "\t")
        .replace("\\012", "\n")
        .replace("\\134", "\\")
    )


def nearest_existing_path(path: pathlib.Path) -> pathlib.Path:
    candidate = path
    while not candidate.exists() and candidate != candidate.parent:
        candidate = candidate.parent
    return candidate


def mounted_filesystem_info(path: pathlib.Path) -> MountInfo:
    best = MountInfo(pathlib.Path("/"), "", "", "")
    try:
        lines = pathlib.Path("/proc/self/mountinfo").read_text(encoding="utf-8").splitlines()
    except OSError:
        return best
    for line in lines:
        left, separator, right = line.partition(" - ")
        if not separator:
            continue
        fields = left.split()
        after = right.split()
        if len(fields) < 5 or len(after) < 2:
            continue
        mount_text = unescape_mount_field(fields[4])
        mountpoint = pathlib.Path(mount_text)
        if (path == mountpoint or path.is_relative_to(mountpoint)) and len(str(mountpoint)) >= len(str(best.mountpoint)):
            best = MountInfo(
                mountpoint=mountpoint,
                filesystem_type=after[0],
                device_id=fields[2],
                source=unescape_mount_field(after[1]),
            )
    return best


def mounted_filesystem_type(path: pathlib.Path) -> str:
    return mounted_filesystem_info(path).filesystem_type


def site_fingerprint(site_id: str) -> str:
    if not site_id:
        fail("配置缺少 COPPER_SITE_ID，无法绑定备份介质身份")
    return hashlib.sha256(site_id.encode("utf-8")).hexdigest()[:16]


def identity_env_key(target: str) -> str:
    if target == "external":
        return "COPPER_BACKUP_EXTERNAL_ID"
    if target == "nas":
        return "COPPER_BACKUP_NAS_ID"
    fail(f"{target} 不使用可登记的文件系统身份")


def update_env_value(path: pathlib.Path, key: str, value: str) -> None:
    if not path.is_file() or path.is_symlink():
        fail(f"配置文件不存在、不是普通文件或是符号链接：{path}")
    original = path.stat()
    lines = path.read_text(encoding="utf-8").splitlines()
    result: list[str] = []
    replaced = False
    for line in lines:
        current = line.split("=", 1)[0].strip() if "=" in line and not line.lstrip().startswith("#") else ""
        if current == key:
            if replaced:
                fail(f"配置文件重复定义 {key}")
            result.append(f"{key}={value}")
            replaced = True
        else:
            result.append(line)
    if not replaced:
        result.append(f"{key}={value}")
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.identity-", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as writer:
            writer.write("\n".join(result) + "\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, original.st_mode & 0o777)
        try:
            os.chown(temporary, original.st_uid, original.st_gid)
        except PermissionError:
            if os.geteuid() == 0:
                raise
        os.replace(temporary, path)
        fsync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


class DirectoryTarget:
    def __init__(
        self,
        name: str,
        root: pathlib.Path,
        mount_policy: str,
        test_mode: bool,
        allow_unmounted: bool,
        expected_identity: str,
        site_id: str,
        data_root: pathlib.Path,
    ):
        self.name = name
        self.root = root
        self.mount_policy = mount_policy
        self.test_mode = test_mode
        self.allow_unmounted = allow_unmounted
        self.expected_identity = expected_identity
        self.site_id = site_id
        self.data_root = data_root

    def mount_context(self) -> tuple[pathlib.Path, MountInfo, MountInfo]:
        if self.mount_policy == "local":
            self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        elif not self.root.exists():
            fail(f"{self.name} 目标目录不存在；请先挂载介质，不得回退写入本机空目录：{self.root}")
        if self.root.is_symlink() or not self.root.is_dir():
            fail(f"{self.name} 目标不是安全目录：{self.root}")
        resolved = self.root.resolve()
        root_mount = mounted_filesystem_info(resolved)
        data_path = nearest_existing_path(self.data_root.resolve(strict=False))
        data_mount = mounted_filesystem_info(data_path)
        if self.mount_policy != "local":
            label = "外接盘" if self.mount_policy == "external" else "NAS"
            if self.test_mode and not self.allow_unmounted:
                if root_mount.mountpoint != resolved:
                    fail(f"{label} 测试目标不是配置目录本身的独立挂载：{self.root}")
                if root_mount.device_id and root_mount.device_id == data_mount.device_id:
                    fail(f"{label} 测试目标与主数据位于同一挂载设备：{self.root}")
            elif not (self.test_mode and self.allow_unmounted):
                allowed = EXTERNAL_FILESYSTEMS if self.mount_policy == "external" else NETWORK_FILESYSTEMS
                if root_mount.mountpoint == pathlib.Path(root_mount.mountpoint.anchor):
                    fail(f"{label} 目标未检测到独立稳定挂载：{self.root}")
                if root_mount.filesystem_type not in allowed:
                    allowed_text = "/".join(sorted(allowed))
                    fail(
                        f"{label} 目标文件系统必须是 {allowed_text}，"
                        f"当前为 {root_mount.filesystem_type or 'unknown'}：{self.root}"
                    )
                if root_mount.device_id and root_mount.device_id == data_mount.device_id:
                    fail(f"{label} 目标与主数据位于同一挂载设备，不能形成独立故障域：{self.root}")
        return resolved, root_mount, data_mount

    def identity_document(self, required_identity: bool = True) -> dict[str, object] | None:
        if self.mount_policy == "local":
            return None
        if required_identity and not TARGET_ID_RE.fullmatch(self.expected_identity):
            fail(f"{self.name} 目标尚未登记稳定身份；请先执行 copperctl storage enroll {self.name}")
        marker = self.root / IDENTITY_FILE
        if not marker.is_file() or marker.is_symlink():
            if required_identity:
                fail(f"{self.name} 目标身份标记缺失，可能未挂载或已换盘：{marker}")
            return None
        try:
            document = json.loads(marker.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            fail(f"{self.name} 目标身份标记无法读取或已损坏：{marker}")
        target_id = str(document.get("targetId", ""))
        if (
            document.get("formatVersion") != IDENTITY_FORMAT
            or document.get("target") != self.name
            or not TARGET_ID_RE.fullmatch(target_id)
            or document.get("siteFingerprint") != site_fingerprint(self.site_id)
        ):
            fail(f"{self.name} 目标身份标记不属于当前站点或目标类型：{marker}")
        if required_identity and target_id != self.expected_identity:
            fail(f"{self.name} 目标身份与配置不一致，已失败关闭；禁止向换盘或错误共享写入")
        return document

    def prepare(self, required_identity: bool = True) -> tuple[pathlib.Path, MountInfo, MountInfo]:
        context = self.mount_context()
        self.identity_document(required_identity)
        probe = self.root / f".copper-write-probe-{os.getpid()}"
        try:
            with probe.open("xb") as writer:
                writer.write(b"copper-storage-probe\n")
                writer.flush()
                os.fsync(writer.fileno())
            renamed = probe.with_suffix(".renamed")
            os.replace(probe, renamed)
            renamed.unlink()
            fsync_directory(self.root)
        except OSError as error:
            probe.unlink(missing_ok=True)
            fail(f"{self.name} 目标不支持写入和原子改名：{error}")
        final_context = self.mount_context()
        self.identity_document(required_identity)
        if context[1].device_id != final_context[1].device_id or context[1].mountpoint != final_context[1].mountpoint:
            fail(f"{self.name} 目标在检查期间发生挂载变化，已失败关闭")
        return final_context

    def enroll(self, env_file: pathlib.Path, confirmation: str) -> dict[str, object]:
        if self.mount_policy not in {"external", "nas"}:
            fail("只有 external 或 nas 目标需要登记稳定身份")
        if confirmation != str(self.root):
            fail(f"登记确认必须精确输入目标路径：{self.root}")
        self.prepare(required_identity=False)
        document = self.identity_document(required_identity=False)
        if document is None:
            if self.expected_identity:
                fail(f"{self.name} 已配置目标身份但介质标记缺失；禁止自动把替换介质登记为原目标")
            target_id = uuid.uuid4().hex
            mount = mounted_filesystem_info(self.root.resolve())
            document = {
                "formatVersion": IDENTITY_FORMAT,
                "target": self.name,
                "targetId": target_id,
                "siteFingerprint": site_fingerprint(self.site_id),
                "filesystemType": mount.filesystem_type,
                "createdAt": dt.datetime.now(dt.timezone.utc)
                .replace(microsecond=0)
                .isoformat()
                .replace("+00:00", "Z"),
            }
            atomic_json(self.root / IDENTITY_FILE, document)
        else:
            target_id = str(document["targetId"])
            if self.expected_identity and self.expected_identity != target_id:
                fail(f"{self.name} 已登记身份与配置不一致，禁止覆盖")
        update_env_value(env_file, identity_env_key(self.name), target_id)
        self.expected_identity = target_id
        self.prepare(required_identity=True)
        return {
            "formatVersion": IDENTITY_FORMAT,
            "target": self.name,
            "targetId": target_id,
            "location": str(self.root),
            "status": "ENROLLED",
        }

    def paths(self, name: str) -> tuple[pathlib.Path, pathlib.Path, pathlib.Path]:
        return (
            self.root / name,
            self.root / f"{name}.sha256",
            self.root / f"{name}.success.json",
        )

    def test_fail_at(self, point: str) -> None:
        if self.test_mode and os.environ.get("COPPER_STORAGE_TEST_FAIL_AT", "") == f"{self.name}-{point}":
            fail(f"测试注入 {self.name}-{point} 发布故障")

    def preflight(self, required_bytes: int) -> dict[str, object]:
        self.prepare()
        usage = shutil.disk_usage(self.root)
        level = risk_level(usage.total, usage.free)
        if level in {"CRITICAL", "DANGER"}:
            fail(
                f"{self.name} 目标空间已进入 {level} 级别：可用 {usage.free} 字节；"
                "请先扩容或清理可安全删除的旧备份"
            )
        if usage.free < required_bytes:
            fail(
                f"{self.name} 目标空间不足：需要 {required_bytes} 字节，可用 {usage.free} 字节"
            )
        return self.status()

    def publish(self, archive: pathlib.Path, sidecar: pathlib.Path, digest: str, size: int) -> None:
        self.prepare()
        final_archive, final_sidecar, marker = self.paths(archive.name)
        same_archive = archive.resolve() == final_archive.resolve()
        same_sidecar = sidecar.resolve() == final_sidecar.resolve()
        try:
            marker.unlink(missing_ok=True)
            if not same_archive:
                atomic_copy(archive, final_archive)
            self.test_fail_at("after-archive-copy")
            if not same_sidecar:
                atomic_copy(sidecar, final_sidecar)
            self.test_fail_at("after-sidecar-copy")
            if sha256_file(final_archive) != digest or final_archive.stat().st_size != size:
                fail(f"{self.name} 目标发布后字节或 SHA-256 不一致")
            if read_sidecar(final_sidecar, archive.name) != digest:
                fail(f"{self.name} 目标校验文件不一致")
            self.prepare()
            self.test_fail_at("before-success-marker")
            atomic_json(
                marker,
                marker_document(
                    self.name,
                    archive.name,
                    digest,
                    size,
                    sha256_file(final_sidecar),
                    final_sidecar.stat().st_size,
                ),
            )
        except Exception:
            try:
                self.mount_context()
                self.identity_document()
                marker.unlink(missing_ok=True)
                if not same_archive:
                    final_archive.unlink(missing_ok=True)
                if not same_sidecar:
                    final_sidecar.unlink(missing_ok=True)
                fsync_directory(self.root)
            except Exception:
                pass
            raise

    def verify(self, name: str, digest: str, size: int) -> None:
        self.prepare()
        final_archive, final_sidecar, marker = self.paths(name)
        if not marker.is_file() or marker.is_symlink():
            fail(f"{self.name} 目标缺少成功标记：{name}")
        document = json.loads(marker.read_text(encoding="utf-8"))
        if document.get("sha256") != digest or document.get("bytes") != size:
            fail(f"{self.name} 目标成功标记不一致：{name}")
        actual, actual_size = verify_source(final_archive, final_sidecar)
        if actual != digest or actual_size != size:
            fail(f"{self.name} 目标备份内容不一致：{name}")
        if (
            document.get("sidecarSha256") != sha256_file(final_sidecar)
            or document.get("sidecarBytes") != final_sidecar.stat().st_size
        ):
            fail(f"{self.name} 目标校验文件摘要不一致：{name}")

    def fetch(self, name: str, destination: pathlib.Path) -> pathlib.Path:
        self.prepare()
        source, sidecar, marker = self.paths(name)
        if not marker.is_file():
            fail(f"{self.name} 目标没有已完成备份：{name}")
        digest, size = verify_source(source, sidecar)
        destination.mkdir(parents=True, exist_ok=True, mode=0o700)
        result = destination / name
        atomic_copy(source, result)
        atomic_copy(sidecar, destination / f"{name}.sha256")
        if sha256_file(result) != digest or result.stat().st_size != size:
            fail(f"从 {self.name} 读取后校验失败：{name}")
        return result

    def list(self) -> list[dict[str, object]]:
        self.prepare()
        records: list[dict[str, object]] = []
        for marker in sorted(self.root.glob("*.copperbak.success.json")):
            try:
                document = json.loads(marker.read_text(encoding="utf-8"))
                name = safe_archive_name(str(document["archive"]))
                digest = str(document["sha256"])
                size = int(document["bytes"])
                if HASH_RE.fullmatch(digest):
                    self.verify(name, digest, size)
                    records.append(document)
            except (KeyError, TypeError, ValueError, json.JSONDecodeError, StorageError):
                continue
        return records

    def delete(self, name: str) -> None:
        self.prepare()
        final_archive, final_sidecar, marker = self.paths(name)
        marker.unlink(missing_ok=True)
        fsync_directory(self.root)
        final_archive.unlink(missing_ok=True)
        final_sidecar.unlink(missing_ok=True)
        fsync_directory(self.root)

    def status(self) -> dict[str, object]:
        _, mount, data_mount = self.prepare()
        usage = shutil.disk_usage(self.root)
        records = self.list()
        used = sum(int(item["bytes"]) for item in records)
        same_failure_domain = (
            not mount.device_id
            or not data_mount.device_id
            or mount.device_id == data_mount.device_id
        ) if self.mount_policy == "local" else bool(
            mount.device_id and mount.device_id == data_mount.device_id
        )
        return {
            "target": self.name,
            "type": "FILESYSTEM",
            "location": str(self.root),
            "filesystemType": mount.filesystem_type or "unknown",
            "mountpoint": str(mount.mountpoint),
            "identityBound": self.mount_policy == "local" or bool(self.expected_identity),
            "targetIdFingerprint": self.expected_identity[:12] if self.expected_identity else None,
            "failureDomain": "SAME_AS_PRIMARY_DATA" if same_failure_domain else "SEPARATE_MOUNT",
            "failureDomainRisk": "WARNING" if same_failure_domain else "NORMAL",
            "failureDomainMessage": (
                "本地备份与主数据位于同一挂载设备，不能防止整机或磁盘损坏"
                if same_failure_domain
                else "备份目标与主数据挂载设备已分离"
            ),
            "backupCount": len(records),
            "backupBytes": used,
            "capacityBytes": usage.total,
            "availableBytes": usage.free,
            "quotaKnown": True,
            "riskLevel": risk_level(usage.total, usage.free),
        }


@dataclass(frozen=True)
class S3Object:
    key: str
    size: int


class S3Target:
    def __init__(self, config: dict[str, str], test_mode: bool):
        self.name = "s3"
        endpoint = urllib.parse.urlsplit(required(config, "COPPER_BACKUP_S3_ENDPOINT"))
        if endpoint.scheme not in ("http", "https") or not endpoint.hostname:
            fail("COPPER_BACKUP_S3_ENDPOINT 必须是有效 http/https 地址")
        if endpoint.scheme != "https" and not test_mode:
            fail("生产 S3 兼容目标必须使用 HTTPS；HTTP 仅允许隔离测试")
        if endpoint.query or endpoint.fragment:
            fail("COPPER_BACKUP_S3_ENDPOINT 不得包含查询参数或片段")
        self.endpoint = endpoint
        self.region = config.get("COPPER_BACKUP_S3_REGION", "us-east-1")
        self.bucket = required(config, "COPPER_BACKUP_S3_BUCKET")
        if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", self.bucket):
            fail("COPPER_BACKUP_S3_BUCKET 格式不正确")
        prefix = config.get("COPPER_BACKUP_S3_PREFIX", "copper-backups").strip("/")
        if not prefix or ".." in prefix or not re.fullmatch(r"[A-Za-z0-9._/-]+", prefix):
            fail("COPPER_BACKUP_S3_PREFIX 格式不安全")
        self.prefix = prefix
        self.access_key = self._read_secret(required(config, "COPPER_BACKUP_S3_ACCESS_KEY_FILE"), "访问密钥")
        self.secret_key = self._read_secret(required(config, "COPPER_BACKUP_S3_SECRET_KEY_FILE"), "秘密密钥")
        token_file = config.get("COPPER_BACKUP_S3_SESSION_TOKEN_FILE", "").strip()
        self.session_token = self._read_secret(token_file, "会话令牌") if token_file else None

    @staticmethod
    def _read_secret(path: str, label: str) -> str:
        secret_path = pathlib.Path(path)
        if not secret_path.is_file() or secret_path.is_symlink():
            fail(f"S3 {label}文件不存在或不是普通文件：{path}")
        if secret_path.stat().st_mode & 0o077:
            fail(f"S3 {label}文件权限必须为 0600 或更严格：{path}")
        value = secret_path.read_text(encoding="utf-8").strip()
        if not value or any(character in value for character in "\r\n"):
            fail(f"S3 {label}内容无效")
        return value

    def _key(self, category: str, name: str) -> str:
        return f"{self.prefix}/{category}/{name}"

    def _canonical_uri(self, key: str | None = None) -> str:
        parts = [part for part in self.endpoint.path.split("/") if part]
        parts.append(self.bucket)
        if key:
            parts.extend(key.split("/"))
        return "/" + "/".join(urllib.parse.quote(part, safe="-_.~") for part in parts)

    def _connection(self) -> http.client.HTTPConnection:
        port = self.endpoint.port
        if self.endpoint.scheme == "https":
            return http.client.HTTPSConnection(
                self.endpoint.hostname, port or 443, timeout=60, context=ssl.create_default_context()
            )
        return http.client.HTTPConnection(self.endpoint.hostname, port or 80, timeout=60)

    def _authorization(
        self,
        method: str,
        uri: str,
        query: str,
        headers: dict[str, str],
        payload_hash: str,
        timestamp: dt.datetime,
    ) -> str:
        amz_date = timestamp.strftime("%Y%m%dT%H%M%SZ")
        date_stamp = timestamp.strftime("%Y%m%d")
        normalized = {key.lower(): " ".join(value.strip().split()) for key, value in headers.items()}
        signed_headers = ";".join(sorted(normalized))
        canonical_headers = "".join(f"{key}:{normalized[key]}\n" for key in sorted(normalized))
        canonical_request = "\n".join(
            [method, uri, query, canonical_headers, signed_headers, payload_hash]
        )
        scope = f"{date_stamp}/{self.region}/s3/aws4_request"
        string_to_sign = "\n".join(
            [
                "AWS4-HMAC-SHA256",
                amz_date,
                scope,
                hashlib.sha256(canonical_request.encode()).hexdigest(),
            ]
        )
        date_key = hmac.new(("AWS4" + self.secret_key).encode(), date_stamp.encode(), hashlib.sha256).digest()
        region_key = hmac.new(date_key, self.region.encode(), hashlib.sha256).digest()
        service_key = hmac.new(region_key, b"s3", hashlib.sha256).digest()
        signing_key = hmac.new(service_key, b"aws4_request", hashlib.sha256).digest()
        signature = hmac.new(signing_key, string_to_sign.encode(), hashlib.sha256).hexdigest()
        return (
            f"AWS4-HMAC-SHA256 Credential={self.access_key}/{scope},"
            f"SignedHeaders={signed_headers},Signature={signature}"
        )

    def _request(
        self,
        method: str,
        key: str | None = None,
        query: Iterable[tuple[str, str]] = (),
        body_path: pathlib.Path | None = None,
        body_bytes: bytes | None = None,
        extra_headers: dict[str, str] | None = None,
        output_path: pathlib.Path | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        if body_path is not None and body_bytes is not None:
            fail("S3 请求体不能同时来自文件和内存")
        uri = self._canonical_uri(key)
        encoded_query = urllib.parse.urlencode(sorted(query), quote_via=urllib.parse.quote, safe="-_.~")
        payload_hash = hashlib.sha256(body_bytes or b"").hexdigest()
        content_length = len(body_bytes or b"")
        if body_path is not None:
            payload_hash = sha256_file(body_path)
            content_length = body_path.stat().st_size
        now = dt.datetime.now(dt.timezone.utc)
        host = self.endpoint.hostname or ""
        effective_port = self.endpoint.port
        if effective_port and effective_port not in (80, 443):
            host = f"{host}:{effective_port}"
        headers = {
            "host": host,
            "x-amz-content-sha256": payload_hash,
            "x-amz-date": now.strftime("%Y%m%dT%H%M%SZ"),
        }
        if self.session_token:
            headers["x-amz-security-token"] = self.session_token
        if content_length or method in ("PUT", "POST"):
            headers["content-length"] = str(content_length)
        if extra_headers:
            headers.update({key.lower(): value for key, value in extra_headers.items()})
        headers["authorization"] = self._authorization(
            method, uri, encoded_query, headers, payload_hash, now
        )
        request_target = uri + (f"?{encoded_query}" if encoded_query else "")
        last_error: Exception | None = None
        for attempt in range(3):
            connection = self._connection()
            try:
                connection.putrequest(method, request_target, skip_host=True)
                for header, value in headers.items():
                    connection.putheader(header, value)
                connection.endheaders()
                if body_bytes:
                    connection.send(body_bytes)
                elif body_path is not None:
                    with body_path.open("rb") as reader:
                        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
                            connection.send(chunk)
                response = connection.getresponse()
                response_headers = {key.lower(): value for key, value in response.getheaders()}
                if output_path is not None and 200 <= response.status < 300:
                    output_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    descriptor, temporary_name = tempfile.mkstemp(
                        prefix=f".{output_path.name}.download-", dir=output_path.parent
                    )
                    temporary = pathlib.Path(temporary_name)
                    try:
                        with os.fdopen(descriptor, "wb") as writer:
                            while True:
                                chunk = response.read(1024 * 1024)
                                if not chunk:
                                    break
                                writer.write(chunk)
                            writer.flush()
                            os.fsync(writer.fileno())
                        os.chmod(temporary, 0o600)
                        os.replace(temporary, output_path)
                        fsync_directory(output_path.parent)
                    finally:
                        temporary.unlink(missing_ok=True)
                    data = b""
                else:
                    data = response.read()
                status = response.status
                connection.close()
                if 200 <= status < 300:
                    return status, response_headers, data
                if status not in (429, 500, 502, 503, 504) or attempt == 2:
                    detail = data[:500].decode("utf-8", errors="replace")
                    fail(f"S3 请求失败：HTTP {status} {detail}")
            except (OSError, http.client.HTTPException) as error:
                last_error = error
                connection.close()
                if attempt == 2:
                    fail(f"S3 连接失败：{error}")
            time.sleep(2**attempt)
        fail(f"S3 请求失败：{last_error}")

    def _head_verify(self, key: str, digest: str, size: int) -> None:
        _, headers, _ = self._request("HEAD", key)
        if int(headers.get("content-length", "-1")) != size:
            fail(f"S3 对象大小校验失败：{key}")
        if headers.get("x-amz-meta-sha256") != digest:
            fail(f"S3 对象 SHA-256 元数据校验失败：{key}")

    def _copy(self, source_key: str, destination_key: str) -> None:
        copy_source = "/" + urllib.parse.quote(f"{self.bucket}/{source_key}", safe="/-_.~")
        self._request("PUT", destination_key, extra_headers={"x-amz-copy-source": copy_source})

    def _delete_key(self, key: str, *, ignore_errors: bool = False) -> None:
        try:
            self._request("DELETE", key)
        except StorageError:
            if not ignore_errors:
                raise

    def preflight(self, required_bytes: int) -> dict[str, object]:
        del required_bytes
        self._request("GET", query=(("list-type", "2"), ("max-keys", "1"), ("prefix", self.prefix + "/")))
        return self.status()

    def publish(self, archive: pathlib.Path, sidecar: pathlib.Path, digest: str, size: int) -> None:
        name = archive.name
        temp_prefix = self._key(".upload", name + "-" + digest[:12])
        temp_archive = temp_prefix + "/archive"
        temp_sidecar = temp_prefix + "/sidecar"
        final_archive = self._key("archives", name)
        final_sidecar = self._key("archives", name + ".sha256")
        marker_key = self._key("success", name + ".json")
        sidecar_digest = sha256_file(sidecar)
        sidecar_size = sidecar.stat().st_size
        marker = json.dumps(
            marker_document(self.name, name, digest, size, sidecar_digest, sidecar_size),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8") + b"\n"
        marker_digest = hashlib.sha256(marker).hexdigest()
        try:
            self._request("PUT", temp_archive, body_path=archive, extra_headers={"x-amz-meta-sha256": digest})
            self._request(
                "PUT",
                temp_sidecar,
                body_path=sidecar,
                extra_headers={"x-amz-meta-sha256": sidecar_digest},
            )
            self._head_verify(temp_archive, digest, size)
            self._copy(temp_archive, final_archive)
            self._copy(temp_sidecar, final_sidecar)
            self._head_verify(final_archive, digest, size)
            self._head_verify(final_sidecar, sidecar_digest, sidecar_size)
            self._request(
                "PUT", marker_key, body_bytes=marker, extra_headers={"x-amz-meta-sha256": marker_digest}
            )
        except Exception:
            self._delete_key(marker_key, ignore_errors=True)
            self._delete_key(final_archive, ignore_errors=True)
            self._delete_key(final_sidecar, ignore_errors=True)
            raise
        finally:
            self._delete_key(temp_archive, ignore_errors=True)
            self._delete_key(temp_sidecar, ignore_errors=True)

    def verify(self, name: str, digest: str, size: int) -> None:
        marker_key = self._key("success", name + ".json")
        _, _, marker_bytes = self._request("GET", marker_key)
        document = json.loads(marker_bytes.decode("utf-8"))
        if document.get("sha256") != digest or document.get("bytes") != size:
            fail(f"S3 成功标记不一致：{name}")
        self._head_verify(self._key("archives", name), digest, size)
        sidecar_digest = str(document.get("sidecarSha256", ""))
        try:
            sidecar_size = int(document.get("sidecarBytes", -1))
        except (TypeError, ValueError):
            sidecar_size = -1
        if not HASH_RE.fullmatch(sidecar_digest) or sidecar_size < 0:
            fail(f"S3 成功标记缺少校验文件摘要：{name}")
        self._head_verify(self._key("archives", name + ".sha256"), sidecar_digest, sidecar_size)

    def fetch(self, name: str, destination: pathlib.Path) -> pathlib.Path:
        records = {str(item["archive"]): item for item in self.list()}
        if name not in records:
            fail(f"S3 目标没有已完成备份：{name}")
        destination.mkdir(parents=True, exist_ok=True, mode=0o700)
        archive = destination / name
        sidecar = destination / f"{name}.sha256"
        self._request("GET", self._key("archives", name), output_path=archive)
        self._request("GET", self._key("archives", name + ".sha256"), output_path=sidecar)
        digest, size = verify_source(archive, sidecar)
        record = records[name]
        if digest != record["sha256"] or size != record["bytes"]:
            fail(f"从 S3 读取后校验失败：{name}")
        return archive

    def _list_objects(self, prefix: str) -> list[S3Object]:
        objects: list[S3Object] = []
        continuation = ""
        while True:
            query = [("list-type", "2"), ("prefix", prefix)]
            if continuation:
                query.append(("continuation-token", continuation))
            _, _, data = self._request("GET", query=query)
            root = ET.fromstring(data)
            namespace = ""
            if root.tag.startswith("{"):
                namespace = root.tag.split("}", 1)[0] + "}"
            for content in root.findall(f"{namespace}Contents"):
                key = content.findtext(f"{namespace}Key", default="")
                size = int(content.findtext(f"{namespace}Size", default="0"))
                objects.append(S3Object(key, size))
            truncated = root.findtext(f"{namespace}IsTruncated", default="false").lower() == "true"
            if not truncated:
                break
            continuation = root.findtext(f"{namespace}NextContinuationToken", default="")
            if not continuation:
                fail("S3 列举分页缺少 continuation token")
        return objects

    def list(self) -> list[dict[str, object]]:
        records: list[dict[str, object]] = []
        for item in self._list_objects(self._key("success", "")):
            if not item.key.endswith(".copperbak.json"):
                continue
            _, _, marker = self._request("GET", item.key)
            try:
                document = json.loads(marker.decode("utf-8"))
                name = safe_archive_name(str(document["archive"]))
                digest = str(document["sha256"])
                size = int(document["bytes"])
                if not HASH_RE.fullmatch(digest):
                    continue
                self._head_verify(self._key("archives", name), digest, size)
                records.append(document)
            except (KeyError, TypeError, ValueError, json.JSONDecodeError, StorageError):
                continue
        return sorted(records, key=lambda item: str(item["archive"]))

    def delete(self, name: str) -> None:
        self._delete_key(self._key("success", name + ".json"))
        self._delete_key(self._key("archives", name))
        self._delete_key(self._key("archives", name + ".sha256"))

    def status(self) -> dict[str, object]:
        records = self.list()
        return {
            "target": self.name,
            "type": "S3_COMPATIBLE",
            "location": f"{self.endpoint.geturl().rstrip('/')}/{self.bucket}/{self.prefix}",
            "identityBound": True,
            "targetIdFingerprint": None,
            "failureDomain": "OFF_HOST_OBJECT_STORAGE",
            "failureDomainRisk": "NORMAL",
            "failureDomainMessage": "对象存储与主机数据盘故障域分离；供应商配额需单独确认",
            "backupCount": len(records),
            "backupBytes": sum(int(item["bytes"]) for item in records),
            "capacityBytes": None,
            "availableBytes": None,
            "quotaKnown": False,
            "quotaMessage": "由对象存储供应商配额决定",
            "riskLevel": "UNKNOWN",
        }


def target_for(name: str, config: dict[str, str], test_mode: bool):
    site_id = config.get("COPPER_SITE_ID", "")
    data_root = pathlib.Path(config.get("COPPER_VAR_DIR", "/var/lib/copper"))
    if name == "local":
        root = pathlib.Path(config.get("COPPER_BACKUP_DIR", "/var/backups/copper"))
        return DirectoryTarget("local", root, "local", test_mode, False, "", site_id, data_root)
    if name == "external":
        root = pathlib.Path(required(config, "COPPER_BACKUP_EXTERNAL_DIR"))
        allow = config.get("COPPER_STORAGE_TEST_ALLOW_UNMOUNTED_EXTERNAL", "0") == "1"
        return DirectoryTarget(
            "external",
            root,
            "external",
            test_mode,
            allow,
            config.get("COPPER_BACKUP_EXTERNAL_ID", ""),
            site_id,
            data_root,
        )
    if name == "nas":
        root = pathlib.Path(required(config, "COPPER_BACKUP_NAS_DIR"))
        allow = config.get("COPPER_STORAGE_TEST_ALLOW_UNMOUNTED_NAS", "0") == "1"
        return DirectoryTarget(
            "nas",
            root,
            "nas",
            test_mode,
            allow,
            config.get("COPPER_BACKUP_NAS_ID", ""),
            site_id,
            data_root,
        )
    if name == "s3":
        return S3Target(config, test_mode)
    fail(f"不支持的备份目标：{name}")


def select_targets(config: dict[str, str], requested: str | None) -> list[str]:
    configured = configured_targets(config)
    if requested:
        if requested not in configured:
            fail(f"目标 {requested} 未在 COPPER_BACKUP_TARGETS 中启用")
        return [requested]
    return configured


def unavailable_status(name: str, config: dict[str, str], error: Exception) -> dict[str, object]:
    if name == "local":
        location = config.get("COPPER_BACKUP_DIR", "/var/backups/copper")
        target_type = "FILESYSTEM"
    elif name == "external":
        location = config.get("COPPER_BACKUP_EXTERNAL_DIR", "")
        target_type = "FILESYSTEM"
    elif name == "nas":
        location = config.get("COPPER_BACKUP_NAS_DIR", "")
        target_type = "FILESYSTEM"
    else:
        endpoint = config.get("COPPER_BACKUP_S3_ENDPOINT", "")
        bucket = config.get("COPPER_BACKUP_S3_BUCKET", "")
        prefix = config.get("COPPER_BACKUP_S3_PREFIX", "copper-backups").strip("/")
        location = "/".join(part.strip("/") for part in (endpoint, bucket, prefix) if part)
        target_type = "S3_COMPATIBLE"
    return {
        "target": name,
        "type": target_type,
        "location": location,
        "backupCount": 0,
        "backupBytes": 0,
        "capacityBytes": None,
        "availableBytes": None,
        "quotaKnown": False,
        "quotaMessage": "目标当前不可用，容量无法确认",
        "riskLevel": "UNKNOWN",
        "identityBound": False,
        "failureDomain": "UNKNOWN",
        "failureDomainRisk": "BLOCKED",
        "failureDomainMessage": "目标身份、挂载或可写性未通过检查",
        "available": False,
        "errorCode": type(error).__name__.upper(),
        "errorMessage": str(error),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Copper backup storage target adapter")
    parser.add_argument("--env-file", default=os.environ.get("COPPER_ENV_FILE", "/etc/copper/copper.env"))
    parser.add_argument("--target", choices=TARGETS)
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("targets")
    preflight = subparsers.add_parser("preflight")
    preflight.add_argument("required_bytes", type=int)
    publish = subparsers.add_parser("publish")
    publish.add_argument("archive")
    publish.add_argument("sidecar")
    fetch = subparsers.add_parser("fetch")
    fetch.add_argument("archive_name")
    fetch.add_argument("destination")
    verify = subparsers.add_parser("verify")
    verify.add_argument("archive_name")
    verify.add_argument("sha256")
    verify.add_argument("bytes", type=int)
    listing = subparsers.add_parser("list")
    listing.add_argument("--json", action="store_true")
    delete = subparsers.add_parser("delete")
    delete.add_argument("archive_name")
    enroll = subparsers.add_parser("enroll")
    enroll.add_argument("enroll_target", choices=("external", "nas"))
    enroll.add_argument("--confirm", required=True)
    subparsers.add_parser("status")

    args = parser.parse_args()
    env_path = pathlib.Path(args.env_file)
    config = load_env(env_path)
    test_mode = os.environ.get("COPPER_TEST_MODE", "0") == "1"
    if args.command == "enroll":
        if args.enroll_target not in configured_targets(config):
            fail(f"目标 {args.enroll_target} 未在 COPPER_BACKUP_TARGETS 中启用")
        adapter = target_for(args.enroll_target, config, test_mode)
        if not isinstance(adapter, DirectoryTarget):
            fail("只有文件系统目标可以登记身份")
        print(json.dumps(adapter.enroll(env_path, args.confirm), ensure_ascii=False, sort_keys=True))
        return 0
    names = select_targets(config, args.target)

    if args.command == "targets":
        print("\n".join(names))
        return 0
    if args.command == "status":
        statuses = []
        for name in names:
            try:
                status = target_for(name, config, test_mode).status()
                status["available"] = True
                status["errorCode"] = ""
                statuses.append(status)
            except Exception as error:
                statuses.append(unavailable_status(name, config, error))
        print(
            json.dumps(
                {
                    "formatVersion": "copper-storage-status/v1",
                    "observedAt": dt.datetime.now(dt.timezone.utc)
                    .replace(microsecond=0)
                    .isoformat()
                    .replace("+00:00", "Z"),
                    "targets": statuses,
                },
                ensure_ascii=False,
                sort_keys=True,
            )
        )
        return 0
    adapters = [(name, target_for(name, config, test_mode)) for name in names]
    if args.command == "preflight":
        if args.required_bytes < 0:
            fail("required_bytes 必须为非负整数")
        print(json.dumps([adapter.preflight(args.required_bytes) for _, adapter in adapters], ensure_ascii=False))
        return 0
    if args.command == "publish":
        archive = pathlib.Path(args.archive)
        sidecar = pathlib.Path(args.sidecar)
        digest, size = verify_source(archive, sidecar)
        completed: list[tuple[str, object]] = []
        try:
            for name, adapter in adapters:
                adapter.publish(archive, sidecar, digest, size)
                adapter.verify(archive.name, digest, size)
                completed.append((name, adapter))
        except Exception:
            for _, adapter in reversed(completed):
                try:
                    adapter.delete(archive.name)
                except Exception:
                    pass
            raise
        print(json.dumps({"archive": archive.name, "bytes": size, "sha256": digest, "targets": names}))
        return 0
    if args.command == "fetch":
        name = safe_archive_name(args.archive_name)
        if len(adapters) != 1:
            fail("fetch 必须使用 --target 指定一个目标")
        result = adapters[0][1].fetch(name, pathlib.Path(args.destination))
        print(result)
        return 0
    if args.command == "verify":
        name = safe_archive_name(args.archive_name)
        if not HASH_RE.fullmatch(args.sha256) or args.bytes < 0:
            fail("verify 的 SHA-256 或字节数不正确")
        for _, adapter in adapters:
            adapter.verify(name, args.sha256, args.bytes)
        print(json.dumps({"archive": name, "verifiedTargets": names}))
        return 0
    if args.command == "list":
        documents = {name: adapter.list() for name, adapter in adapters}
        if args.json:
            print(json.dumps(documents, ensure_ascii=False, sort_keys=True))
        else:
            for name in names:
                for document in documents[name]:
                    print(f"{name}\t{document['archive']}\t{document['bytes']}\t{document['sha256']}")
        return 0
    if args.command == "delete":
        name = safe_archive_name(args.archive_name)
        for _, adapter in adapters:
            adapter.delete(name)
        print(json.dumps({"archive": name, "deletedTargets": names}))
        return 0
    fail(f"未知命令：{args.command}")
    return 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except StorageError as error:
        print(f"错误：{error}", file=sys.stderr)
        raise SystemExit(1)
