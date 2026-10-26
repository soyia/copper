#!/usr/bin/env python3
"""Build, validate and atomically stage Copper M3 delivery contracts."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Any


DELIVERY_FORMAT = "copper-delivery/v1"
DELTA_DELIVERY_FORMAT = "copper-upgrade-delivery/v1"
READY_FORMAT = "copper-delivery-ready/v1"
HASH_RE = re.compile(r"[0-9a-f]{64}")
IMAGE_ID_RE = re.compile(r"sha256:[0-9a-f]{64}")
VERSION_RE = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-rc\.[1-9][0-9]*)?")
SAFE_NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,191}")
INDEX_KEYS = {"format", "version", "mode", "os", "arch", "manifest", "artifacts", "images"}
DELTA_INDEX_KEYS = INDEX_KEYS | {"baseVersion", "baseIndexSha256"}
FILE_KEYS = {"role", "name", "bytes", "sha256"}
IMAGE_KEYS = {"role", "reference", "platform"}
OFFLINE_IMAGE_KEYS = IMAGE_KEYS | {"localId"}
DELTA_IMAGE_KEYS = OFFLINE_IMAGE_KEYS | {"delivery", "baseReference", "baseLocalId"}
READY_KEYS = {
    "format", "status", "version", "mode", "deliveredAt", "indexFile", "indexSha256",
    "manifestSha256", "images",
}
EVIDENCE_KEYS = {"role", "reference", "localId", "architecture"}
BASE_ROLES = {"manifest", "deploy", "matrix", "notes", "acme"}
PROVENANCE_ROLES = {"build-web", "build-backend"}
IMAGE_ROLES = {"web", "backend", "mysql"}
NOTES_HEADINGS = ("## 功能变化", "## 数据库影响", "## 已知问题", "## 升级步骤", "## 停机与回退")


class DeliveryError(ValueError):
    pass


def fail(message: str) -> None:
    raise DeliveryError(message)


def load_release_module() -> Any:
    path = pathlib.Path(__file__).resolve().with_name("release-contract.py")
    spec = importlib.util.spec_from_file_location("copper_release_contract", path)
    if spec is None or spec.loader is None:
        fail("无法加载发布清单校验器")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


RELEASE = load_release_module()


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as reader:
        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_name(value: object, label: str) -> str:
    if not isinstance(value, str) or not SAFE_NAME_RE.fullmatch(value):
        fail(f"{label} 必须是安全普通文件名")
    if pathlib.PurePosixPath(value).name != value or value in {".", ".."}:
        fail(f"{label} 禁止路径或特殊目录")
    return value


def secure_file(path: pathlib.Path, label: str) -> pathlib.Path:
    if path.is_symlink() or not path.is_file():
        fail(f"{label} 必须是普通文件且不能是符号链接：{path}")
    return path.resolve()


def file_record(role: str, path: pathlib.Path) -> dict[str, object]:
    resolved = secure_file(path, role)
    safe_name(resolved.name, f"{role}.name")
    return {
        "role": role,
        "name": resolved.name,
        "bytes": resolved.stat().st_size,
        "sha256": sha256_file(resolved),
    }


def parse_document(path: pathlib.Path) -> tuple[pathlib.Path, dict[str, Any]]:
    resolved = secure_file(path, "交付索引")
    try:
        document = json.loads(resolved.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        fail(f"交付索引不是有效 UTF-8 JSON：{exc}")
    validate_document(document)
    return resolved, document


def validate_file_record(value: object, label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != FILE_KEYS:
        fail(f"{label} 字段不完整或包含未知字段")
    role = value.get("role")
    if not isinstance(role, str):
        fail(f"{label}.role 不合法")
    safe_name(value.get("name"), f"{label}.name")
    size = value.get("bytes")
    if not isinstance(size, int) or size < 0:
        fail(f"{label}.bytes 必须是非负整数")
    if not isinstance(value.get("sha256"), str) or not HASH_RE.fullmatch(value["sha256"]):
        fail(f"{label}.sha256 必须是小写 SHA-256")
    return value


def validate_document(document: object) -> dict[str, Any]:
    if isinstance(document, dict) and document.get("format") == DELTA_DELIVERY_FORMAT:
        return validate_delta_document(document)
    if not isinstance(document, dict) or set(document) != INDEX_KEYS:
        fail("交付索引顶层字段必须精确匹配 copper-delivery/v1")
    if document.get("format") != DELIVERY_FORMAT:
        fail(f"交付索引 format 必须是 {DELIVERY_FORMAT}")
    version = document.get("version")
    if not isinstance(version, str) or not VERSION_RE.fullmatch(version):
        fail("交付索引 version 不是 M3 产品版本")
    mode = document.get("mode")
    if mode not in {"online", "offline"}:
        fail("交付索引 mode 只允许 online 或 offline")
    if document.get("os") != "ubuntu-24.04" or document.get("arch") != "amd64":
        fail("交付索引只允许 ubuntu-24.04/amd64")

    manifest = validate_file_record(document.get("manifest"), "manifest")
    if manifest["role"] != "manifest" or manifest["name"] != f"copper-release-{version}.env":
        fail("交付索引 manifest 文件名或角色与版本不一致")

    artifacts = document.get("artifacts")
    if not isinstance(artifacts, list):
        fail("交付索引 artifacts 必须是数组")
    expected_roles = {"deploy", "matrix", "notes", "acme"} | ({"images"} if mode == "offline" else set())
    records = [validate_file_record(item, f"artifacts[{index}]") for index, item in enumerate(artifacts)]
    roles = [item["role"] for item in records]
    names = [item["name"] for item in records] + [manifest["name"]]
    expected_roles |= set(roles) & PROVENANCE_ROLES
    if set(roles) != expected_roles or len(roles) != len(expected_roles):
        fail(f"交付索引 artifacts 角色必须精确为：{','.join(sorted(expected_roles))}")
    if len(names) != len(set(names)):
        fail("交付索引包含重复文件名")

    images = document.get("images")
    if not isinstance(images, list) or len(images) != 3:
        fail("交付索引必须精确包含三张镜像")
    image_roles: set[str] = set()
    for index, image in enumerate(images):
        expected_image_keys = OFFLINE_IMAGE_KEYS if mode == "offline" else IMAGE_KEYS
        if not isinstance(image, dict) or set(image) != expected_image_keys:
            fail(f"images[{index}] 字段不完整或包含未知字段")
        role = image.get("role")
        reference = image.get("reference")
        if role not in IMAGE_ROLES or role in image_roles:
            fail(f"images[{index}].role 非法或重复")
        image_roles.add(role)
        if not isinstance(reference, str) or not RELEASE.IMAGE_RE.fullmatch(reference):
            fail(f"images[{index}].reference 必须是 registry@sha256 不可变引用")
        if image.get("platform") != "linux/amd64":
            fail(f"images[{index}].platform 必须是 linux/amd64")
        if mode == "offline" and not IMAGE_ID_RE.fullmatch(str(image.get("localId", ""))):
            fail(f"images[{index}].localId 必须是归档内 sha256 镜像 ID")
    if image_roles != IMAGE_ROLES:
        fail("交付索引镜像角色必须精确为 web/backend/mysql")
    return document


def validate_delta_document(document: dict[str, Any]) -> dict[str, Any]:
    if set(document) != DELTA_INDEX_KEYS:
        fail("差异升级索引顶层字段必须精确匹配 copper-upgrade-delivery/v1")
    version = document.get("version")
    base_version = document.get("baseVersion")
    if not isinstance(version, str) or not VERSION_RE.fullmatch(version):
        fail("差异升级目标版本不正确")
    if not isinstance(base_version, str) or not VERSION_RE.fullmatch(base_version) or base_version == version:
        fail("差异升级来源版本不正确或与目标版本相同")
    if document.get("mode") != "offline-delta":
        fail("差异升级索引 mode 必须是 offline-delta")
    if document.get("os") != "ubuntu-24.04" or document.get("arch") != "amd64":
        fail("差异升级索引只允许 ubuntu-24.04/amd64")
    if not isinstance(document.get("baseIndexSha256"), str) or not HASH_RE.fullmatch(document["baseIndexSha256"]):
        fail("差异升级来源索引 SHA-256 不正确")

    manifest = validate_file_record(document.get("manifest"), "manifest")
    if manifest["role"] != "manifest" or manifest["name"] != f"copper-release-{version}.env":
        fail("差异升级发布清单文件名或角色与目标版本不一致")

    images = document.get("images")
    if not isinstance(images, list) or len(images) != 3:
        fail("差异升级索引必须精确包含三项目标镜像")
    image_roles: set[str] = set()
    included = 0
    for index, image in enumerate(images):
        if not isinstance(image, dict) or set(image) != DELTA_IMAGE_KEYS:
            fail(f"images[{index}] 差异升级字段不完整或包含未知字段")
        role = image.get("role")
        if role not in IMAGE_ROLES or role in image_roles:
            fail(f"images[{index}].role 非法或重复")
        image_roles.add(role)
        if image.get("platform") != "linux/amd64":
            fail(f"images[{index}].platform 必须是 linux/amd64")
        for key in ("reference", "baseReference"):
            if not isinstance(image.get(key), str) or not RELEASE.IMAGE_RE.fullmatch(image[key]):
                fail(f"images[{index}].{key} 必须是 registry@sha256 不可变引用")
        for key in ("localId", "baseLocalId"):
            if not isinstance(image.get(key), str) or not IMAGE_ID_RE.fullmatch(image[key]):
                fail(f"images[{index}].{key} 必须是 sha256 镜像 ID")
        delivery = image.get("delivery")
        if delivery not in {"included", "reuse"}:
            fail(f"images[{index}].delivery 只允许 included 或 reuse")
        same_content = image["localId"] == image["baseLocalId"]
        if (delivery == "reuse") != same_content:
            fail(f"images[{index}] 复用结论与来源/目标内容摘要不一致")
        if delivery == "included":
            included += 1
    if image_roles != IMAGE_ROLES:
        fail("差异升级镜像角色必须精确为 web/backend/mysql")

    artifacts = document.get("artifacts")
    if not isinstance(artifacts, list):
        fail("差异升级 artifacts 必须是数组")
    expected_roles = {"deploy", "matrix", "notes", "acme"} | ({"images"} if included else set())
    records = [validate_file_record(item, f"artifacts[{index}]") for index, item in enumerate(artifacts)]
    roles = [item["role"] for item in records]
    names = [item["name"] for item in records] + [manifest["name"]]
    expected_roles |= set(roles) & PROVENANCE_ROLES
    if set(roles) != expected_roles or len(roles) != len(expected_roles):
        fail(f"差异升级 artifacts 角色必须精确为：{','.join(sorted(expected_roles))}")
    if len(names) != len(set(names)):
        fail("差异升级索引包含重复文件名")
    return document


def artifact_map(document: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {item["role"]: item for item in [document["manifest"], *document["artifacts"]]}


def local_build_records(manifest: pathlib.Path, values: dict[str, str]) -> list[dict[str, object]]:
    return [
        file_record(f"build-{role.lower()}", manifest.parent / values[f"COPPER_{role}_BUILD_PROVENANCE_FILE"])
        for role in ("WEB", "BACKEND") if f"COPPER_{role}_BUILD_PROVENANCE_FILE" in values
    ]


def validate_release_notes(path: pathlib.Path) -> None:
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeError as exc:
        fail(f"发布说明不是 UTF-8：{exc}")
    missing = [heading for heading in NOTES_HEADINGS if heading not in content]
    if missing:
        fail(f"发布说明缺少固定章节：{','.join(missing)}")


def validate_index(path: pathlib.Path, allowed_extra: set[str] | None = None) -> tuple[pathlib.Path, dict[str, Any]]:
    resolved, document = parse_document(path)
    records = artifact_map(document)
    if resolved.name in {item["name"] for item in records.values()}:
        fail("交付索引文件名不能与任何被绑定文件重名")
    expected_files = {resolved.name, *(item["name"] for item in records.values())}
    expected_files.update(allowed_extra or set())
    actual_files: set[str] = set()
    for entry in resolved.parent.iterdir():
        if entry.is_symlink() or not entry.is_file():
            fail(f"交付目录包含非普通文件：{entry.name}")
        actual_files.add(entry.name)
    if actual_files != expected_files:
        missing = sorted(expected_files - actual_files)
        unknown = sorted(actual_files - expected_files)
        fail(f"交付目录文件集合不匹配：missing={missing}, unknown={unknown}")
    for role, record in records.items():
        candidate = secure_file(resolved.parent / record["name"], role)
        if candidate.stat().st_size != record["bytes"] or sha256_file(candidate) != record["sha256"]:
            fail(f"交付文件字节数或 SHA-256 不一致：{record['name']}")

    manifest_path = resolved.parent / records["manifest"]["name"]
    _, values, matrix_path, _ = RELEASE.load_manifest(
        manifest_path, pathlib.Path(__file__).resolve().parent.parent / "config/release-manifest-v1.fields"
    )
    if values["COPPER_RELEASE_VERSION"] != document["version"]:
        fail("发布清单版本与交付索引不一致")
    if values["COPPER_SUPPORTED_OS"] != document["os"] or values["COPPER_SUPPORTED_ARCH"] != document["arch"]:
        fail("发布清单平台与交付索引不一致")
    role_expectations = {
        "deploy": (values["COPPER_DEPLOY_ARCHIVE"], values["COPPER_DEPLOY_ARCHIVE_SHA256"]),
        "matrix": (values["COPPER_UPGRADE_MATRIX_FILE"], values["COPPER_UPGRADE_MATRIX_SHA256"]),
        "notes": (values["COPPER_RELEASE_NOTES_FILE"], values["COPPER_RELEASE_NOTES_SHA256"]),
        "acme": (values["COPPER_ACME_ARCHIVE"], values["COPPER_ACME_ARCHIVE_SHA256"]),
    }
    for role in ("WEB", "BACKEND"):
        name = values.get(f"COPPER_{role}_BUILD_PROVENANCE_FILE")
        record_role = f"build-{role.lower()}"
        if name:
            role_expectations[record_role] = (name, values[f"COPPER_{role}_BUILD_PROVENANCE_SHA256"])
            if record_role not in records:
                fail(f"交付索引缺少本地构建证据：{role}")
        elif record_role in records:
            fail(f"交付索引包含未绑定本地构建证据：{role}")
    for role, (name, digest) in role_expectations.items():
        if records[role]["name"] != name or records[role]["sha256"] != digest:
            fail(f"{role} 未精确绑定发布清单")
    if matrix_path.name != records["matrix"]["name"]:
        fail("升级矩阵路径与交付索引不一致")
    validate_release_notes(resolved.parent / records["notes"]["name"])
    expected_images = {
        "web": values["COPPER_WEB_IMAGE"],
        "backend": values["COPPER_BACKEND_IMAGE"],
        "mysql": values["COPPER_MYSQL_IMAGE"],
    }
    for image in document["images"]:
        if image["reference"] != expected_images[image["role"]]:
            fail(f"{image['role']} 镜像未精确绑定发布清单")
    return resolved, document


def atomic_json(path: pathlib.Path, document: object, mode: int) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as writer:
            json.dump(document, writer, ensure_ascii=False, indent=2)
            writer.write("\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def command_generate(args: argparse.Namespace) -> None:
    manifest = pathlib.Path(args.manifest).resolve()
    _, values, matrix, _ = RELEASE.load_manifest(
        manifest, pathlib.Path(__file__).resolve().parent.parent / "config/release-manifest-v1.fields"
    )
    validate_release_notes(manifest.parent / values["COPPER_RELEASE_NOTES_FILE"])
    artifacts = [
        file_record("deploy", manifest.parent / values["COPPER_DEPLOY_ARCHIVE"]),
        file_record("matrix", matrix),
        file_record("notes", manifest.parent / values["COPPER_RELEASE_NOTES_FILE"]),
        file_record("acme", manifest.parent / values["COPPER_ACME_ARCHIVE"]),
    ]
    artifacts.extend(local_build_records(manifest, values))
    local_ids: dict[str, str] = {}
    for item in args.image_id or []:
        parts = item.split(",", 1)
        if len(parts) != 2 or parts[0] not in IMAGE_ROLES or parts[0] in local_ids:
            fail("--image-id 必须是唯一的 ROLE,sha256:LOCAL_ID")
        if not IMAGE_ID_RE.fullmatch(parts[1]):
            fail("--image-id 必须包含 sha256 镜像 ID")
        local_ids[parts[0]] = parts[1]
    if args.mode == "offline":
        if not args.image_archive:
            fail("offline 交付必须提供 --image-archive")
        if set(local_ids) != IMAGE_ROLES:
            fail("offline 交付必须精确提供 web、backend、mysql 三项 --image-id")
        artifacts.append(file_record("images", pathlib.Path(args.image_archive)))
    elif args.image_archive or local_ids:
        fail("online 交付禁止包含镜像归档或本地镜像 ID")
    images = [
        {"role": "web", "reference": values["COPPER_WEB_IMAGE"], "platform": values["COPPER_WEB_PLATFORM"]},
        {"role": "backend", "reference": values["COPPER_BACKEND_IMAGE"], "platform": values["COPPER_BACKEND_PLATFORM"]},
        {"role": "mysql", "reference": values["COPPER_MYSQL_IMAGE"], "platform": values["COPPER_MYSQL_PLATFORM"]},
    ]
    if args.mode == "offline":
        for image in images:
            image["localId"] = local_ids[image["role"]]
    document = {
        "format": DELIVERY_FORMAT,
        "version": values["COPPER_RELEASE_VERSION"],
        "mode": args.mode,
        "os": values["COPPER_SUPPORTED_OS"],
        "arch": values["COPPER_SUPPORTED_ARCH"],
        "manifest": file_record("manifest", manifest),
        "artifacts": artifacts,
        "images": images,
    }
    validate_document(document)
    output = pathlib.Path(args.output).resolve()
    if output.parent != manifest.parent:
        fail("交付索引必须与发布清单和交付文件位于同一目录")
    atomic_json(output, document, 0o644)
    validate_index(output)
    print(f"index={output}")
    print(f"sha256={sha256_file(output)}")
    print(f"mode={args.mode}")


def command_generate_delta(args: argparse.Namespace) -> None:
    manifest = pathlib.Path(args.manifest).resolve()
    _, values, matrix, _ = RELEASE.load_manifest(
        manifest, pathlib.Path(__file__).resolve().parent.parent / "config/release-manifest-v1.fields"
    )
    validate_release_notes(manifest.parent / values["COPPER_RELEASE_NOTES_FILE"])
    base_index, base_document = validate_index(pathlib.Path(args.base_index))
    if base_document["version"] == values["COPPER_RELEASE_VERSION"]:
        fail("差异升级来源版本不能等于目标版本")
    if base_document["os"] != values["COPPER_SUPPORTED_OS"] or base_document["arch"] != values["COPPER_SUPPORTED_ARCH"]:
        fail("差异升级来源与目标平台不一致")

    target_ids: dict[str, str] = {}
    for item in args.image_id or []:
        parts = item.split(",", 1)
        if len(parts) != 2 or parts[0] not in IMAGE_ROLES or parts[0] in target_ids:
            fail("--image-id 必须是唯一的 ROLE,sha256:LOCAL_ID")
        if not IMAGE_ID_RE.fullmatch(parts[1]):
            fail("--image-id 必须包含 sha256 镜像 ID")
        target_ids[parts[0]] = parts[1]
    if set(target_ids) != IMAGE_ROLES:
        fail("差异升级必须精确提供 web、backend、mysql 三项目标 --image-id")

    base_images = {item["role"]: item for item in base_document["images"]}
    target_references = {
        "web": values["COPPER_WEB_IMAGE"],
        "backend": values["COPPER_BACKEND_IMAGE"],
        "mysql": values["COPPER_MYSQL_IMAGE"],
    }
    images: list[dict[str, str]] = []
    included_roles: set[str] = set()
    for role in ("web", "backend", "mysql"):
        base = base_images[role]
        base_local_id = str(base.get("localId", ""))
        if not IMAGE_ID_RE.fullmatch(base_local_id):
            fail("差异升级来源必须是带本机镜像身份的离线交付索引")
        delivery = "reuse" if target_ids[role] == base_local_id else "included"
        if delivery == "included":
            included_roles.add(role)
        images.append({
            "role": role,
            "reference": target_references[role],
            "platform": values[f"COPPER_{role.upper()}_PLATFORM"],
            "localId": target_ids[role],
            "delivery": delivery,
            "baseReference": base["reference"],
            "baseLocalId": base_local_id,
        })

    artifacts = [
        file_record("deploy", manifest.parent / values["COPPER_DEPLOY_ARCHIVE"]),
        file_record("matrix", matrix),
        file_record("notes", manifest.parent / values["COPPER_RELEASE_NOTES_FILE"]),
        file_record("acme", manifest.parent / values["COPPER_ACME_ARCHIVE"]),
    ]
    artifacts.extend(local_build_records(manifest, values))
    if included_roles:
        if not args.image_archive:
            fail("存在变化镜像时，差异升级必须提供 --image-archive")
        artifacts.append(file_record("images", pathlib.Path(args.image_archive)))
    elif args.image_archive:
        fail("三张镜像均可复用时，差异升级禁止携带多余镜像归档")

    document = {
        "format": DELTA_DELIVERY_FORMAT,
        "version": values["COPPER_RELEASE_VERSION"],
        "baseVersion": base_document["version"],
        "baseIndexSha256": sha256_file(base_index),
        "mode": "offline-delta",
        "os": values["COPPER_SUPPORTED_OS"],
        "arch": values["COPPER_SUPPORTED_ARCH"],
        "manifest": file_record("manifest", manifest),
        "artifacts": artifacts,
        "images": images,
    }
    validate_document(document)
    output = pathlib.Path(args.output).resolve()
    if output.parent != manifest.parent:
        fail("差异升级索引必须与目标发布清单和交付文件位于同一目录")
    atomic_json(output, document, 0o644)
    validate_index(output)
    print(f"index={output}")
    print(f"sha256={sha256_file(output)}")
    print("mode=offline-delta")
    print(f"included={','.join(sorted(included_roles)) or 'none'}")


def command_validate(args: argparse.Namespace) -> None:
    path, document = validate_index(pathlib.Path(args.index))
    print(f"format={document['format']}")
    print(f"version={document['version']}")
    print(f"mode={document['mode']}")
    print(f"sha256={sha256_file(path)}")


def command_files(args: argparse.Namespace) -> None:
    _, document = parse_document(pathlib.Path(args.index))
    for record in artifact_map(document).values():
        print(record["name"])


def command_images(args: argparse.Namespace) -> None:
    _, document = parse_document(pathlib.Path(args.index))
    for image in document["images"]:
        print(f"{image['role']}\t{image['reference']}\t{image['platform']}\t{image.get('localId', '')}")


def command_image_archive(args: argparse.Namespace) -> None:
    _, document = parse_document(pathlib.Path(args.index))
    record = artifact_map(document).get("images")
    if record is None:
        return
    if document["mode"] not in {"offline", "offline-delta"}:
        fail("online 交付没有离线镜像归档")
    print(record["name"])


def command_delta_plan(args: argparse.Namespace) -> None:
    _, document = parse_document(pathlib.Path(args.index))
    if document["format"] != DELTA_DELIVERY_FORMAT:
        fail("只有差异升级索引可以读取复用计划")
    for image in document["images"]:
        print(
            f"{image['role']}\t{image['delivery']}\t{image['baseLocalId']}\t"
            f"{image['localId']}\t{image['baseReference']}\t{image['reference']}"
        )


def command_evidence(args: argparse.Namespace) -> None:
    _, document = parse_document(pathlib.Path(args.index))
    published = {item["role"]: item["reference"] for item in document["images"]}
    evidence: list[dict[str, str]] = []
    seen: set[str] = set()
    for value in args.image:
        parts = value.split(",")
        if len(parts) != 4:
            fail("--image 必须是 ROLE,REFERENCE,LOCAL_ID,ARCHITECTURE")
        role, reference, local_id, architecture = parts
        if role not in IMAGE_ROLES or role in seen or published.get(role) != reference:
            fail("镜像证据角色重复或未绑定发布索引")
        if not IMAGE_ID_RE.fullmatch(local_id):
            fail("镜像证据 localId 必须是 sha256 镜像 ID")
        indexed = next(item for item in document["images"] if item["role"] == role)
        if document["mode"] in {"offline", "offline-delta"} and indexed["localId"] != local_id:
            fail("离线镜像证据 localId 未绑定交付索引")
        if architecture not in {"amd64", "arm64"}:
            fail("镜像证据 architecture 不受支持")
        seen.add(role)
        evidence.append({"role": role, "reference": reference, "localId": local_id, "architecture": architecture})
    if seen != IMAGE_ROLES:
        fail("镜像证据必须精确包含 web/backend/mysql")
    output = pathlib.Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    atomic_json(output, {"images": evidence}, 0o600)


def load_evidence(path: pathlib.Path, document: dict[str, Any], test_mode: bool) -> list[dict[str, str]]:
    resolved = secure_file(path, "镜像证据")
    try:
        raw = json.loads(resolved.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        fail(f"镜像证据不是有效 JSON：{exc}")
    if not isinstance(raw, dict) or set(raw) != {"images"} or not isinstance(raw["images"], list):
        fail("镜像证据结构不正确")
    published = {item["role"]: item["reference"] for item in document["images"]}
    seen: set[str] = set()
    result: list[dict[str, str]] = []
    for item in raw["images"]:
        if not isinstance(item, dict) or set(item) != EVIDENCE_KEYS:
            fail("镜像证据字段不正确")
        role = item.get("role")
        if role not in IMAGE_ROLES or role in seen or item.get("reference") != published.get(role):
            fail("镜像证据未精确绑定发布索引")
        if not isinstance(item.get("localId"), str) or not IMAGE_ID_RE.fullmatch(item["localId"]):
            fail("镜像证据 localId 不正确")
        indexed = next(image for image in document["images"] if image["role"] == role)
        if document["mode"] in {"offline", "offline-delta"} and indexed["localId"] != item["localId"]:
            fail("离线镜像证据与归档内容 ID 不一致")
        expected_arch = "amd64"
        if item.get("architecture") != expected_arch and not (test_mode and item.get("architecture") == "arm64"):
            fail("正式镜像证据架构必须是 amd64")
        seen.add(role)
        result.append(item)
    if seen != IMAGE_ROLES:
        fail("镜像证据数量不完整")
    return result


def validate_ready(directory: pathlib.Path) -> dict[str, Any]:
    if directory.is_symlink() or not directory.is_dir():
        fail(f"READY 发布目录不是普通目录：{directory}")
    if directory.stat().st_mode & 0o777 != 0o700:
        fail(f"READY 发布目录权限必须是 0700：{directory}")
    if directory.stat().st_uid != os.geteuid():
        fail(f"READY 发布目录所有者必须是执行账户：{directory}")
    ready_path = directory / "ready.json"
    if ready_path.is_symlink() or not ready_path.is_file() or ready_path.stat().st_mode & 0o777 != 0o400:
        fail("READY 收据必须是 0400 普通文件")
    if ready_path.stat().st_uid != os.geteuid():
        fail("READY 收据所有者必须是执行账户")
    try:
        ready = json.loads(ready_path.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        fail(f"READY 收据不是有效 JSON：{exc}")
    if not isinstance(ready, dict) or set(ready) != READY_KEYS:
        fail("READY 收据字段不完整或包含未知字段")
    if ready.get("format") != READY_FORMAT or ready.get("status") != "READY":
        fail("READY 收据格式或状态不正确")
    index_name = safe_name(ready.get("indexFile"), "ready.indexFile")
    index_path, document = validate_index(directory / index_name, {"ready.json"})
    for entry in directory.iterdir():
        if entry.name == "ready.json":
            continue
        if entry.stat().st_mode & 0o777 != 0o400 or entry.stat().st_uid != os.geteuid():
            fail(f"READY 发布文件必须由执行账户持有且权限为 0400：{entry.name}")
    if ready.get("version") != document["version"] or ready.get("mode") != document["mode"]:
        fail("READY 收据版本或模式与交付索引不一致")
    if ready.get("indexSha256") != sha256_file(index_path):
        fail("READY 收据索引摘要不一致")
    if ready.get("manifestSha256") != document["manifest"]["sha256"]:
        fail("READY 收据发布清单摘要不一致")
    if not isinstance(ready.get("deliveredAt"), str) or not RELEASE.TIMESTAMP_RE.fullmatch(ready["deliveredAt"]):
        fail("READY 收据 deliveredAt 不正确")
    evidence = ready.get("images")
    if not isinstance(evidence, list) or len(evidence) != 3:
        fail("READY 收据镜像证据不完整")
    published = {item["role"]: item["reference"] for item in document["images"]}
    if {item.get("role") for item in evidence if isinstance(item, dict)} != IMAGE_ROLES:
        fail("READY 收据镜像角色不完整")
    for item in evidence:
        if not isinstance(item, dict) or set(item) != EVIDENCE_KEYS or item["reference"] != published[item["role"]]:
            fail("READY 收据镜像证据与交付索引不一致")
        if not IMAGE_ID_RE.fullmatch(item["localId"]):
            fail("READY 收据镜像 ID 不正确")
        indexed = next(image for image in document["images"] if image["role"] == item["role"])
        if document["mode"] in {"offline", "offline-delta"} and indexed["localId"] != item["localId"]:
            fail("READY 收据离线镜像 ID 未绑定交付索引")
    return ready


def command_stage(args: argparse.Namespace) -> None:
    index, document = validate_index(pathlib.Path(args.index))
    expected_hash = args.index_sha256
    if not HASH_RE.fullmatch(expected_hash) or sha256_file(index) != expected_hash:
        fail("交付索引与独立 SHA-256 不一致")
    evidence = load_evidence(pathlib.Path(args.image_evidence), document, args.test_mode)
    release_root_input = pathlib.Path(args.release_root)
    if release_root_input.is_symlink():
        fail("发布根目录不能是符号链接")
    release_root = release_root_input.resolve()
    release_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(release_root, 0o700)
    destination = release_root / document["version"]
    if destination.exists() or destination.is_symlink():
        ready = validate_ready(destination)
        if ready["indexSha256"] == expected_hash:
            print(f"status=READY")
            print(f"version={document['version']}")
            print("idempotent=true")
            return
        fail("同版本已存在不同交付内容，拒绝覆盖")

    staging = pathlib.Path(tempfile.mkdtemp(prefix=f".{document['version']}.", dir=release_root))
    os.chmod(staging, 0o700)
    try:
        names = [index.name, *(item["name"] for item in artifact_map(document).values())]
        for name in names:
            source = secure_file(index.parent / name, name)
            target = staging / name
            shutil.copyfile(source, target)
            os.chmod(target, 0o400)
        now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")
        ready = {
            "format": READY_FORMAT,
            "status": "READY",
            "version": document["version"],
            "mode": document["mode"],
            "deliveredAt": now,
            "indexFile": index.name,
            "indexSha256": expected_hash,
            "manifestSha256": document["manifest"]["sha256"],
            "images": evidence,
        }
        atomic_json(staging / "ready.json", ready, 0o400)
        validate_ready(staging)
        os.replace(staging, destination)
        descriptor = os.open(release_root, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    print("status=READY")
    print(f"version={document['version']}")
    print("idempotent=false")


def command_status(args: argparse.Namespace) -> None:
    root_input = pathlib.Path(args.release_root)
    if root_input.is_symlink():
        fail("发布根目录不存在或不安全")
    root = root_input.resolve()
    if not root.exists():
        if args.json:
            print('{"releases":[]}')
        else:
            print("status=EMPTY")
        return
    if not root.is_dir():
        fail("发布根目录不存在或不安全")
    if args.version:
        if not VERSION_RE.fullmatch(args.version):
            fail("版本格式不正确")
        directories = [root / args.version]
    else:
        directories = sorted(path for path in root.iterdir() if path.is_dir() or path.is_symlink())
    records = [validate_ready(directory) for directory in directories]
    if args.verify_images:
        for ready in records:
            for image in ready["images"]:
                targets = [image["localId"]]
                if not args.test_mode and ready["mode"] == "online":
                    targets.append(image["reference"])
                for target in targets:
                    result = subprocess.run(
                        ["docker", "image", "inspect", "--format", "{{.Id}}\t{{.Architecture}}", target],
                        text=True, capture_output=True, check=False,
                    )
                    if result.returncode != 0:
                        fail(f"READY 镜像已不在本机：{image['role']}={target}")
                    parts = result.stdout.strip().split("\t")
                    if len(parts) != 2 or parts[0] != image["localId"] or parts[1] != image["architecture"]:
                        fail(f"READY 镜像身份或架构已变化：{image['role']}")
    if args.json:
        print(json.dumps({"releases": records}, ensure_ascii=False, separators=(",", ":")))
        return
    if not records:
        print("status=EMPTY")
        return
    for ready in records:
        print(f"version={ready['version']} status={ready['status']} mode={ready['mode']} index_sha256={ready['indexSha256']}")


def command_assert_manifest(args: argparse.Namespace) -> None:
    manifest = secure_file(pathlib.Path(args.manifest), "升级发布清单")
    release_root_input = pathlib.Path(args.release_root)
    if release_root_input.is_symlink():
        fail("发布根目录不能是符号链接")
    release_root = release_root_input.resolve()
    if manifest.parent.parent != release_root:
        fail("升级发布清单不在 root-only READY 发布目录中")
    ready = validate_ready(manifest.parent)
    index = manifest.parent / ready["indexFile"]
    _, document = parse_document(index)
    expected_manifest = artifact_map(document)["manifest"]["name"]
    if manifest.name != expected_manifest or ready["manifestSha256"] != sha256_file(manifest):
        fail("升级发布清单未精确绑定 READY 收据")
    print(f"status={ready['status']}")
    print(f"version={ready['version']}")
    print(f"mode={ready['mode']}")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description="Copper M3 delivery contract helper")
    subparsers = root.add_subparsers(dest="command", required=True)

    generate = subparsers.add_parser("generate")
    generate.add_argument("--manifest", required=True)
    generate.add_argument("--mode", choices=("online", "offline"), required=True)
    generate.add_argument("--image-archive")
    generate.add_argument("--image-id", action="append")
    generate.add_argument("--output", required=True)
    generate.set_defaults(handler=command_generate)

    generate_delta = subparsers.add_parser("generate-delta")
    generate_delta.add_argument("--manifest", required=True)
    generate_delta.add_argument("--base-index", required=True)
    generate_delta.add_argument("--image-archive")
    generate_delta.add_argument("--image-id", action="append", required=True)
    generate_delta.add_argument("--output", required=True)
    generate_delta.set_defaults(handler=command_generate_delta)

    validate = subparsers.add_parser("validate")
    validate.add_argument("index")
    validate.set_defaults(handler=command_validate)

    files = subparsers.add_parser("files")
    files.add_argument("index")
    files.set_defaults(handler=command_files)

    images = subparsers.add_parser("images")
    images.add_argument("index")
    images.set_defaults(handler=command_images)

    archive = subparsers.add_parser("image-archive")
    archive.add_argument("index")
    archive.set_defaults(handler=command_image_archive)

    delta_plan = subparsers.add_parser("delta-plan")
    delta_plan.add_argument("index")
    delta_plan.set_defaults(handler=command_delta_plan)

    evidence = subparsers.add_parser("evidence")
    evidence.add_argument("--index", required=True)
    evidence.add_argument("--image", action="append", required=True)
    evidence.add_argument("--output", required=True)
    evidence.set_defaults(handler=command_evidence)

    stage = subparsers.add_parser("stage")
    stage.add_argument("--index", required=True)
    stage.add_argument("--index-sha256", required=True)
    stage.add_argument("--image-evidence", required=True)
    stage.add_argument("--release-root", required=True)
    stage.add_argument("--test-mode", action="store_true")
    stage.set_defaults(handler=command_stage)

    status = subparsers.add_parser("status")
    status.add_argument("--release-root", required=True)
    status.add_argument("--version")
    status.add_argument("--json", action="store_true")
    status.add_argument("--verify-images", action="store_true")
    status.add_argument("--test-mode", action="store_true")
    status.set_defaults(handler=command_status)

    assert_manifest = subparsers.add_parser("assert-manifest")
    assert_manifest.add_argument("--manifest", required=True)
    assert_manifest.add_argument("--release-root", required=True)
    assert_manifest.set_defaults(handler=command_assert_manifest)
    return root


def main() -> int:
    try:
        args = parser().parse_args()
        args.handler(args)
        return 0
    except (DeliveryError, RELEASE.ContractError, OSError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
