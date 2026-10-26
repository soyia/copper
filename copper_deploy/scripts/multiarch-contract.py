#!/usr/bin/env python3
"""Validate and assemble Copper M5 multi-architecture image releases."""

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
import tempfile
from typing import Any


APP_BOM_FORMAT = "copper-image-bom/v1"
PLATFORM_EVIDENCE_FORMAT = "copper-image-platform/v1"
RELEASE_FORMAT = "copper-multiarch-release/v1"
PLATFORMS = ("linux/amd64", "linux/arm64")
APP_ROLES = ("web", "backend")
ALL_ROLES = ("web", "backend", "mysql")
HASH_RE = re.compile(r"[0-9a-f]{64}")
DIGEST_RE = re.compile(r"sha256:[0-9a-f]{64}")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")
IMAGE_RE = re.compile(r"[^\s:@]+(?:/[^\s:@]+)*(?::[^\s@]+)?@sha256:[0-9a-f]{64}")
VERSION_RE = re.compile(
    r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
    r"(?:-(?:rc\.[1-9][0-9]*|m[1-9][0-9]*-rc\.[1-9][0-9]*))?"
)
TIMESTAMP_RE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
SAFE_NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,191}")

APP_BOM_KEYS = {
    "format", "role", "sourceRepository", "sourceCommit", "workflowRun",
    "image", "manifestDigest", "platforms",
}
PLATFORM_KEYS = {
    "format", "role", "sourceRepository", "sourceCommit", "workflowRun",
    "workflowAttempt", "createdAt", "imageRepository", "platform",
    "platformDigest", "localId", "archive",
}
ARCHIVE_KEYS = {"name", "bytes", "sha256"}
RELEASE_KEYS = {"format", "version", "status", "createdAt", "deployment", "platforms", "components"}
DEPLOYMENT_KEYS = {
    "repository", "commit", "legacyReleaseFormat", "legacyDeliveryFormat",
    "backupFormat", "upgradeStateFormat",
}
RELEASE_PLATFORM_KEYS = {"platform", "status"}
COMPONENT_KEYS = {
    "role", "source", "image", "manifestDigest", "platforms",
}
SOURCE_KEYS = {"kind", "repository", "revision", "workflowRun"}
COMPONENT_PLATFORM_KEYS = {"platform", "digest", "localId", "archive"}


class ContractError(ValueError):
    pass


def fail(message: str) -> None:
    raise ContractError(message)


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as reader:
        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def secure_file(path: pathlib.Path, label: str) -> pathlib.Path:
    if path.is_symlink() or not path.is_file():
        fail(f"{label} 必须是普通文件且不能是符号链接：{path}")
    return path.resolve()


def parse_json(path: pathlib.Path, label: str) -> tuple[pathlib.Path, dict[str, Any]]:
    resolved = secure_file(path, label)
    try:
        document = json.loads(resolved.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        fail(f"{label} 不是有效 UTF-8 JSON：{exc}")
    if not isinstance(document, dict):
        fail(f"{label} 顶层必须是对象")
    return resolved, document


def parse_timestamp(value: object, label: str) -> str:
    if not isinstance(value, str) or not TIMESTAMP_RE.fullmatch(value):
        fail(f"{label} 必须是 UTC RFC3339 秒级时间")
    try:
        parsed = dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)
    except ValueError as exc:
        fail(f"{label} 不是有效时间：{exc}")
    if parsed > dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=5):
        fail(f"{label} 不能位于未来")
    return value


def positive_string_integer(value: object, label: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[1-9][0-9]*", value):
        fail(f"{label} 必须是字符串形式的正整数")
    return value


def safe_name(value: object, label: str) -> str:
    if not isinstance(value, str) or not SAFE_NAME_RE.fullmatch(value):
        fail(f"{label} 必须是安全普通文件名")
    if pathlib.PurePosixPath(value).name != value or value in {".", ".."}:
        fail(f"{label} 禁止路径或特殊目录")
    return value


def validate_archive_record(value: object, role: str, platform: str, directory: pathlib.Path) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != ARCHIVE_KEYS:
        fail(f"{role}/{platform} archive 字段必须精确匹配合同")
    arch = platform.split("/", 1)[1]
    expected_name = f"copper-{role}-{arch}.tar.gz"
    name = safe_name(value.get("name"), f"{role}/{platform}.archive.name")
    if name != expected_name:
        fail(f"{role}/{platform} 归档必须命名为 {expected_name}")
    size = value.get("bytes")
    digest = value.get("sha256")
    if not isinstance(size, int) or size <= 0:
        fail(f"{role}/{platform}.archive.bytes 必须是正整数")
    if not isinstance(digest, str) or not HASH_RE.fullmatch(digest):
        fail(f"{role}/{platform}.archive.sha256 必须是小写 SHA-256")
    archive = secure_file(directory / name, f"{role}/{platform} 离线归档")
    if archive.stat().st_size != size or sha256_file(archive) != digest:
        fail(f"{role}/{platform} 离线归档字节数或 SHA-256 不一致")
    return value


def validate_app_bom(path: pathlib.Path, artifact_dir: pathlib.Path | None = None) -> tuple[pathlib.Path, dict[str, Any]]:
    resolved, document = parse_json(path, "应用镜像 BOM")
    if set(document) != APP_BOM_KEYS or document.get("format") != APP_BOM_FORMAT:
        fail(f"应用镜像 BOM 字段必须精确匹配 {APP_BOM_FORMAT}")
    role = document.get("role")
    if role not in APP_ROLES:
        fail("应用镜像 BOM role 只允许 web 或 backend")
    expected_repo = f"soyia/copper_{role}"
    if document.get("sourceRepository") != expected_repo:
        fail(f"{role} sourceRepository 必须是 {expected_repo}")
    if not isinstance(document.get("sourceCommit"), str) or not COMMIT_RE.fullmatch(document["sourceCommit"]):
        fail(f"{role} sourceCommit 必须是 40 位 Git 提交")
    positive_string_integer(document.get("workflowRun"), f"{role}.workflowRun")
    image = document.get("image")
    manifest_digest = document.get("manifestDigest")
    if not isinstance(image, str) or not IMAGE_RE.fullmatch(image):
        fail(f"{role}.image 必须是不可变顶层 manifest 引用")
    if not isinstance(manifest_digest, str) or not DIGEST_RE.fullmatch(manifest_digest):
        fail(f"{role}.manifestDigest 必须是 sha256 摘要")
    if not image.endswith(f"@{manifest_digest}") or ":latest@" in image:
        fail(f"{role}.image 必须精确绑定 manifestDigest 且禁止 latest")

    platforms = document.get("platforms")
    if not isinstance(platforms, list) or len(platforms) != 2:
        fail(f"{role}.platforms 必须精确包含两个架构")
    directory = (artifact_dir or resolved.parent).resolve()
    seen: list[str] = []
    image_repository = image.rsplit("@", 1)[0]
    for item in platforms:
        if not isinstance(item, dict) or set(item) != PLATFORM_KEYS:
            fail(f"{role} 平台证据字段必须精确匹配 {PLATFORM_EVIDENCE_FORMAT}")
        platform = item.get("platform")
        if platform not in PLATFORMS or platform in seen:
            fail(f"{role} 平台非法或重复：{platform}")
        seen.append(platform)
        if item.get("format") != PLATFORM_EVIDENCE_FORMAT or item.get("role") != role:
            fail(f"{role}/{platform} 平台证据格式或角色不一致")
        for key in ("sourceRepository", "sourceCommit", "workflowRun"):
            if item.get(key) != document.get(key):
                fail(f"{role}/{platform} 未绑定 BOM 字段 {key}")
        positive_string_integer(item.get("workflowAttempt"), f"{role}/{platform}.workflowAttempt")
        parse_timestamp(item.get("createdAt"), f"{role}/{platform}.createdAt")
        if item.get("imageRepository") != image_repository:
            fail(f"{role}/{platform} imageRepository 与顶层 manifest 不一致")
        if not isinstance(item.get("platformDigest"), str) or not DIGEST_RE.fullmatch(item["platformDigest"]):
            fail(f"{role}/{platform}.platformDigest 必须是 sha256 摘要")
        if not isinstance(item.get("localId"), str) or not DIGEST_RE.fullmatch(item["localId"]):
            fail(f"{role}/{platform}.localId 必须是 sha256 内容 ID")
        validate_archive_record(item.get("archive"), role, platform, directory)
    if seen != list(PLATFORMS):
        fail(f"{role}.platforms 必须按 linux/amd64、linux/arm64 排列")
    return resolved, document


def component_from_app_bom(document: dict[str, Any]) -> dict[str, Any]:
    role = document["role"]
    return {
        "role": role,
        "source": {
            "kind": "git",
            "repository": document["sourceRepository"],
            "revision": document["sourceCommit"],
            "workflowRun": document["workflowRun"],
        },
        "image": document["image"],
        "manifestDigest": document["manifestDigest"],
        "platforms": [
            {
                "platform": item["platform"],
                "digest": item["platformDigest"],
                "localId": item["localId"],
                "archive": dict(item["archive"]),
            }
            for item in document["platforms"]
        ],
    }


def parse_mysql_platform(value: str) -> tuple[str, str, str, pathlib.Path]:
    parts = value.split(",", 3)
    if len(parts) != 4:
        fail("--mysql-platform 必须是 PLATFORM,DIGEST,LOCAL_ID,ARCHIVE")
    platform, digest, local_id, archive_name = parts
    if platform not in PLATFORMS:
        fail("--mysql-platform 只允许 linux/amd64 或 linux/arm64")
    if not DIGEST_RE.fullmatch(digest) or not DIGEST_RE.fullmatch(local_id):
        fail("--mysql-platform digest 与 local ID 必须是 sha256 摘要")
    return platform, digest, local_id, pathlib.Path(archive_name)


def validate_component(value: object, artifact_dir: pathlib.Path) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != COMPONENT_KEYS:
        fail("发布组件字段不完整或包含未知字段")
    role = value.get("role")
    if role not in ALL_ROLES:
        fail("发布组件 role 不受支持")
    source = value.get("source")
    if not isinstance(source, dict) or set(source) != SOURCE_KEYS:
        fail(f"{role}.source 字段不完整或包含未知字段")
    if source.get("kind") not in {"git", "registry"}:
        fail(f"{role}.source.kind 不受支持")
    if not isinstance(source.get("repository"), str) or not source["repository"]:
        fail(f"{role}.source.repository 不能为空")
    if not isinstance(source.get("revision"), str) or not source["revision"]:
        fail(f"{role}.source.revision 不能为空")
    workflow_run = source.get("workflowRun")
    if role in APP_ROLES:
        if source["kind"] != "git" or not COMMIT_RE.fullmatch(source["revision"]):
            fail(f"{role} 必须绑定 Git 提交")
        positive_string_integer(workflow_run, f"{role}.source.workflowRun")
    elif (
        source.get("kind") != "registry"
        or source.get("repository") != "docker.io/library/mysql"
        or source.get("workflowRun") is not None
        or not re.fullmatch(r"8\.4\.[0-9]+", source["revision"])
    ):
        fail("mysql 必须绑定 docker.io/library/mysql 的 8.4 LTS 补丁版本")

    image = value.get("image")
    manifest_digest = value.get("manifestDigest")
    if not isinstance(image, str) or not IMAGE_RE.fullmatch(image) or ":latest@" in image:
        fail(f"{role}.image 必须是禁止 latest 的不可变引用")
    if not isinstance(manifest_digest, str) or not DIGEST_RE.fullmatch(manifest_digest):
        fail(f"{role}.manifestDigest 必须是 sha256 摘要")
    if not image.endswith(f"@{manifest_digest}"):
        fail(f"{role}.image 未绑定 manifestDigest")
    platforms = value.get("platforms")
    if not isinstance(platforms, list) or len(platforms) != 2:
        fail(f"{role}.platforms 必须精确包含两个架构")
    seen: list[str] = []
    for item in platforms:
        if not isinstance(item, dict) or set(item) != COMPONENT_PLATFORM_KEYS:
            fail(f"{role} 平台字段不完整或包含未知字段")
        platform = item.get("platform")
        if platform not in PLATFORMS or platform in seen:
            fail(f"{role} 平台非法或重复：{platform}")
        seen.append(platform)
        if not isinstance(item.get("digest"), str) or not DIGEST_RE.fullmatch(item["digest"]):
            fail(f"{role}/{platform}.digest 必须是 sha256 摘要")
        if not isinstance(item.get("localId"), str) or not DIGEST_RE.fullmatch(item["localId"]):
            fail(f"{role}/{platform}.localId 必须是 sha256 内容 ID")
        validate_archive_record(item.get("archive"), role, platform, artifact_dir)
    if seen != list(PLATFORMS):
        fail(f"{role}.platforms 必须按 linux/amd64、linux/arm64 排列")
    return value


def validate_release_document(document: object, artifact_dir: pathlib.Path) -> dict[str, Any]:
    if not isinstance(document, dict) or set(document) != RELEASE_KEYS:
        fail(f"多架构发布索引字段必须精确匹配 {RELEASE_FORMAT}")
    if document.get("format") != RELEASE_FORMAT:
        fail(f"多架构发布索引 format 必须是 {RELEASE_FORMAT}")
    version = document.get("version")
    if not isinstance(version, str) or not VERSION_RE.fullmatch(version):
        fail("多架构发布版本格式不受支持")
    if document.get("status") != "candidate":
        fail("M5-02 多架构发布只能标记 candidate")
    parse_timestamp(document.get("createdAt"), "createdAt")
    deployment = document.get("deployment")
    if not isinstance(deployment, dict) or set(deployment) != DEPLOYMENT_KEYS:
        fail("deployment 字段不完整或包含未知字段")
    expected_deployment = {
        "repository": "soyia/copper_deploy",
        "legacyReleaseFormat": "copper-release/v1",
        "legacyDeliveryFormat": "copper-delivery/v1",
        "backupFormat": "copper-backup/v1",
        "upgradeStateFormat": "copper-upgrade-state/v1",
    }
    for key, expected in expected_deployment.items():
        if deployment.get(key) != expected:
            fail(f"deployment.{key} 必须是 {expected}")
    if not isinstance(deployment.get("commit"), str) or not COMMIT_RE.fullmatch(deployment["commit"]):
        fail("deployment.commit 必须是 40 位 Git 提交")
    platforms = document.get("platforms")
    expected_platforms = [
        {"platform": "linux/amd64", "status": "certified"},
        {"platform": "linux/arm64", "status": "candidate"},
    ]
    if platforms != expected_platforms or any(set(item) != RELEASE_PLATFORM_KEYS for item in platforms):
        fail("平台状态必须固定为 amd64 certified、arm64 candidate")
    components = document.get("components")
    if not isinstance(components, list) or [item.get("role") for item in components if isinstance(item, dict)] != list(ALL_ROLES):
        fail("发布组件必须按 web、backend、mysql 精确排列")
    for component in components:
        validate_component(component, artifact_dir)
    return document


def write_json(path: pathlib.Path, document: object, mode: int = 0o444) -> None:
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


def command_validate_app(args: argparse.Namespace) -> None:
    resolved, document = validate_app_bom(
        pathlib.Path(args.bom), pathlib.Path(args.artifact_dir) if args.artifact_dir else None
    )
    print(f"format={document['format']}")
    print(f"role={document['role']}")
    print(f"image={document['image']}")
    print(f"sha256={sha256_file(resolved)}")


def command_assemble(args: argparse.Namespace) -> None:
    if not VERSION_RE.fullmatch(args.version):
        fail("--version 格式不受支持")
    created_at = parse_timestamp(args.created_at, "--created-at")
    if not COMMIT_RE.fullmatch(args.deploy_commit):
        fail("--deploy-commit 必须是 40 位 Git 提交")
    app_inputs: list[tuple[pathlib.Path, dict[str, Any]]] = []
    for role, value in zip(APP_ROLES, (args.web_bom, args.backend_bom), strict=True):
        resolved, document = validate_app_bom(pathlib.Path(value))
        if document["role"] != role:
            fail(f"--{role}-bom 角色不匹配")
        app_inputs.append((resolved, document))
    if not re.fullmatch(r"8\.4\.[0-9]+", args.mysql_version):
        fail("--mysql-version 必须是 MySQL 8.4 LTS 补丁版本")
    if not IMAGE_RE.fullmatch(args.mysql_image) or ":latest@" in args.mysql_image:
        fail("--mysql-image 必须是禁止 latest 的不可变顶层 manifest")
    mysql_manifest_digest = args.mysql_image.rsplit("@", 1)[1]
    mysql_platforms = [parse_mysql_platform(value) for value in args.mysql_platform]
    if [item[0] for item in mysql_platforms] != list(PLATFORMS):
        fail("--mysql-platform 必须按 linux/amd64、linux/arm64 各提供一次")

    output = pathlib.Path(args.output_dir).resolve()
    if output.exists() or output.is_symlink():
        fail(f"输出目录已存在，拒绝覆盖：{output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = pathlib.Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    try:
        components: list[dict[str, Any]] = []
        for resolved, bom in app_inputs:
            component = component_from_app_bom(bom)
            for platform in component["platforms"]:
                source = resolved.parent / platform["archive"]["name"]
                shutil.copyfile(secure_file(source, "应用离线归档"), partial / platform["archive"]["name"])
            components.append(component)

        mysql_component = {
            "role": "mysql",
            "source": {
                "kind": "registry",
                "repository": "docker.io/library/mysql",
                "revision": args.mysql_version,
                "workflowRun": None,
            },
            "image": args.mysql_image,
            "manifestDigest": mysql_manifest_digest,
            "platforms": [],
        }
        for platform, digest, local_id, source in mysql_platforms:
            arch = platform.split("/", 1)[1]
            expected_name = f"copper-mysql-{arch}.tar.gz"
            resolved = secure_file(source, f"mysql/{platform} 离线归档")
            if resolved.name != expected_name:
                fail(f"mysql/{platform} 归档必须命名为 {expected_name}")
            target = partial / expected_name
            shutil.copyfile(resolved, target)
            mysql_component["platforms"].append({
                "platform": platform,
                "digest": digest,
                "localId": local_id,
                "archive": {
                    "name": expected_name,
                    "bytes": target.stat().st_size,
                    "sha256": sha256_file(target),
                },
            })
        components.append(mysql_component)
        document = {
            "format": RELEASE_FORMAT,
            "version": args.version,
            "status": "candidate",
            "createdAt": created_at,
            "deployment": {
                "repository": "soyia/copper_deploy",
                "commit": args.deploy_commit,
                "legacyReleaseFormat": "copper-release/v1",
                "legacyDeliveryFormat": "copper-delivery/v1",
                "backupFormat": "copper-backup/v1",
                "upgradeStateFormat": "copper-upgrade-state/v1",
            },
            "platforms": [
                {"platform": "linux/amd64", "status": "certified"},
                {"platform": "linux/arm64", "status": "candidate"},
            ],
            "components": components,
        }
        validate_release_document(document, partial)
        index = partial / f"copper-multiarch-release-{args.version}.json"
        write_json(index, document)
        names = sorted(item.name for item in partial.iterdir() if item.is_file())
        sums = "".join(f"{sha256_file(partial / name)}  {name}\n" for name in names)
        sums_path = partial / "SHA256SUMS"
        sums_path.write_text(sums, encoding="utf-8", newline="\n")
        os.chmod(sums_path, 0o444)
        for item in partial.iterdir():
            if item.is_file():
                os.chmod(item, 0o444)
        os.replace(partial, output)
    except Exception:
        shutil.rmtree(partial, ignore_errors=True)
        raise
    print(f"release={output / f'copper-multiarch-release-{args.version}.json'}")
    print(f"sha256={sha256_file(output / f'copper-multiarch-release-{args.version}.json')}")
    print("arm64Status=candidate")


def command_validate_release(args: argparse.Namespace) -> None:
    resolved, document = parse_json(pathlib.Path(args.index), "多架构发布索引")
    artifact_dir = pathlib.Path(args.artifact_dir).resolve() if args.artifact_dir else resolved.parent
    validate_release_document(document, artifact_dir)
    expected_files = {
        resolved.name,
        "SHA256SUMS",
        *(f"copper-{role}-{arch}.tar.gz" for role in ALL_ROLES for arch in ("amd64", "arm64")),
    }
    actual_files: set[str] = set()
    for item in artifact_dir.iterdir():
        if item.is_symlink() or not item.is_file():
            fail(f"多架构发布目录只能包含普通文件：{item.name}")
        actual_files.add(item.name)
    if actual_files != expected_files:
        fail(f"多架构发布目录文件集合不匹配：expected={sorted(expected_files)}, actual={sorted(actual_files)}")
    sums = secure_file(artifact_dir / "SHA256SUMS", "SHA256SUMS").read_text(encoding="utf-8").splitlines()
    expected_sum_names = sorted(expected_files - {"SHA256SUMS"})
    parsed_names: list[str] = []
    for line in sums:
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._+-]{0,191})", line)
        if not match:
            fail("SHA256SUMS 存在非法行")
        digest, name = match.groups()
        parsed_names.append(name)
        if sha256_file(secure_file(artifact_dir / name, name)) != digest:
            fail(f"SHA256SUMS 校验失败：{name}")
    if parsed_names != expected_sum_names:
        fail("SHA256SUMS 文件集合或顺序不正确")
    print(f"format={document['format']}")
    print(f"version={document['version']}")
    print(f"sha256={sha256_file(resolved)}")
    print("platforms=linux/amd64,linux/arm64")
    print("arm64Status=candidate")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)
    validate_app = commands.add_parser("validate-app", help="校验 Web/Backend Actions 多架构 BOM")
    validate_app.add_argument("--bom", required=True)
    validate_app.add_argument("--artifact-dir")
    validate_app.set_defaults(handler=command_validate_app)

    assemble = commands.add_parser("assemble", help="组装 M5 多架构候选发行目录")
    assemble.add_argument("--version", required=True)
    assemble.add_argument("--created-at", required=True)
    assemble.add_argument("--deploy-commit", required=True)
    assemble.add_argument("--web-bom", required=True)
    assemble.add_argument("--backend-bom", required=True)
    assemble.add_argument("--mysql-version", required=True)
    assemble.add_argument("--mysql-image", required=True)
    assemble.add_argument("--mysql-platform", action="append", required=True)
    assemble.add_argument("--output-dir", required=True)
    assemble.set_defaults(handler=command_assemble)

    validate_release = commands.add_parser("validate-release", help="校验完整多架构候选发行目录")
    validate_release.add_argument("--index", required=True)
    validate_release.add_argument("--artifact-dir")
    validate_release.set_defaults(handler=command_validate_release)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        args.handler(args)
    except (ContractError, FileNotFoundError, PermissionError, OSError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
