#!/usr/bin/env python3
"""Generate and validate Copper M3 release manifests and upgrade matrices."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import sys
import tempfile
from typing import Any

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from build_provenance import validate_role


MANIFEST_FORMAT = "copper-release/v1"
MATRIX_FORMAT = "copper-upgrade-matrix/v1"
FUTURE_VERSION_RE = re.compile(
    r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-rc\.[1-9][0-9]*)?"
)
SOURCE_VERSION_RE = re.compile(
    r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?:rc\.[1-9][0-9]*|m[1-9][0-9]*-rc\.[1-9][0-9]*))?"
)
HASH_RE = re.compile(r"[0-9a-f]{64}")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")
SAFE_KEY_RE = re.compile(r"COPPER_[A-Z0-9_]+")
SAFE_VALUE_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/@,+-]*")
IMAGE_RE = re.compile(r"[^\s:@]+(?:/[^\s:@]+)*(?::[^\s@]+)?@sha256:[0-9a-f]{64}")
LEGACY_IMAGE_ID_RE = re.compile(r"sha256:[0-9a-f]{64}")
TIMESTAMP_RE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
SECRET_NAME_RE = re.compile(r"(?:PASSWORD|SECRET|PRIVATE|TOKEN|CREDENTIAL|IDENTITY|ACCESS_KEY)")
BUILD_IDENTITY_FIELDS = {
    f"COPPER_{role}_{field}"
    for role in ("WEB", "BACKEND")
    for field in ("WORKFLOW_RUN", "BUILD_PROVENANCE_FILE", "BUILD_PROVENANCE_SHA256")
}

MATRIX_KEYS = {"format", "targetVersion", "targetSchema", "sources"}
SOURCE_KEYS = {"version", "schema", "mode", "downtimeClass", "rollbackMode"}
UPGRADE_MODES = {"image-only", "in-place", "retained-sidecar"}
DOWNTIME_CLASSES = {"none", "brief-restart", "maintenance-window", "extended-maintenance"}
ROLLBACK_MODES = {
    "image-only",
    "retained-sidecar",
    "verified-backup",
    "retained-sidecar-or-verified-backup",
}


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


def secure_regular_file(path: pathlib.Path, label: str) -> pathlib.Path:
    resolved = path.resolve(strict=True)
    if path.is_symlink() or not resolved.is_file():
        fail(f"{label} 必须是普通文件且不能是符号链接：{path}")
    return resolved


def atomic_write(path: pathlib.Path, content: str, mode: int = 0o644) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as writer:
            writer.write(content)
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def required_fields(path: pathlib.Path) -> list[str]:
    resolved = secure_regular_file(path, "发布清单字段定义")
    fields = [line.strip() for line in resolved.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(fields) < 30 or len(set(fields)) != len(fields) or any(not SAFE_KEY_RE.fullmatch(item) for item in fields):
        fail("发布清单字段定义必须至少包含 30 个唯一 COPPER_ 必填字段")
    return fields


def validate_bound_file(manifest_path: pathlib.Path, values: dict[str, str], name_field: str, hash_field: str) -> None:
    filename = values[name_field]
    if pathlib.PurePosixPath(filename).name != filename or filename in {".", ".."}:
        fail(f"{name_field} 必须是与发布清单同目录的普通文件名")
    if not HASH_RE.fullmatch(values[hash_field]):
        fail(f"{hash_field} 必须是小写 SHA-256")
    artifact = manifest_path.parent / filename
    if artifact.is_symlink() or not artifact.is_file():
        fail(f"{name_field} 指向的发布物不存在或不是普通文件")
    if sha256_file(artifact) != values[hash_field]:
        fail(f"{name_field} 指向的发布物 SHA-256 与清单不一致")


def parse_env(path: pathlib.Path, label: str) -> dict[str, str]:
    resolved = secure_regular_file(path, label)
    result: dict[str, str] = {}
    for number, original in enumerate(resolved.read_text(encoding="utf-8").splitlines(), start=1):
        line = original.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"{label} 第 {number} 行不是 KEY=VALUE")
        key, value = line.split("=", 1)
        if not SAFE_KEY_RE.fullmatch(key):
            fail(f"{label} 第 {number} 行字段名不安全：{key}")
        if key in result:
            fail(f"{label} 存在重复字段：{key}")
        if not value or not SAFE_VALUE_RE.fullmatch(value):
            fail(f"{label} 字段 {key} 为空或包含不安全字符")
        if SECRET_NAME_RE.search(key):
            fail(f"{label} 禁止包含密钥类字段：{key}")
        if "AGE-SECRET-KEY-" in value or "BEGIN_PRIVATE_KEY" in value.replace("-", "_"):
            fail(f"{label} 字段 {key} 疑似包含私钥")
        result[key] = value
    return result


def parse_timestamp(value: str, field: str) -> None:
    if not TIMESTAMP_RE.fullmatch(value):
        fail(f"{field} 必须是 UTC RFC3339 秒级时间")
    try:
        parsed = dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)
    except ValueError as exc:
        fail(f"{field} 不是有效时间：{exc}")
    if parsed > dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=5):
        fail(f"{field} 不能位于未来")


def positive_integer(value: str, field: str, allow_zero: bool = False) -> int:
    if not re.fullmatch(r"0|[1-9][0-9]*", value):
        fail(f"{field} 必须是无前导零的整数")
    number = int(value)
    if number < (0 if allow_zero else 1):
        fail(f"{field} 必须大于{ '或等于零' if allow_zero else '零' }")
    return number


def version_key(value: str) -> tuple[int, int, int, int, int]:
    base, separator, suffix = value.partition("-")
    major, minor, patch = (int(item) for item in base.split("."))
    if not separator:
        return major, minor, patch, 2, 0
    if suffix.startswith("rc."):
        return major, minor, patch, 1, int(suffix.split(".", 1)[1])
    # Historical milestone versions are immutable pre-release identities.
    milestone, release = suffix.split("-rc.", 1)
    return major, minor, patch, 0, int(milestone[1:]) * 1_000_000 + int(release)


def validate_matrix_document(document: Any) -> dict[str, Any]:
    if not isinstance(document, dict) or set(document) not in (MATRIX_KEYS, MATRIX_KEYS | {"freshInstallOnly"}):
        fail("升级矩阵顶层字段必须精确匹配 copper-upgrade-matrix/v1")
    if document.get("format") != MATRIX_FORMAT:
        fail(f"升级矩阵 format 必须是 {MATRIX_FORMAT}")
    target_version = document.get("targetVersion")
    target_schema = document.get("targetSchema")
    sources = document.get("sources")
    if not isinstance(target_version, str) or not FUTURE_VERSION_RE.fullmatch(target_version):
        fail("升级矩阵 targetVersion 不是 M3 起允许的新版本格式")
    if not isinstance(target_schema, str):
        fail("升级矩阵 targetSchema 必须是字符串整数")
    positive_integer(target_schema, "targetSchema", allow_zero=True)
    if not isinstance(sources, list):
        fail("升级矩阵 sources 必须是数组")
    if "freshInstallOnly" in document:
        if document["freshInstallOnly"] is not True or sources or "-rc." not in target_version:
            fail("freshInstallOnly 只允许 RC 候选显式为 true 且 sources 为空")
    elif not sources:
        fail("升级矩阵必须逐项列出至少一个已实测来源；仅全新安装 RC 可显式声明 freshInstallOnly")
    seen: set[tuple[str, str]] = set()
    for index, source in enumerate(sources):
        if not isinstance(source, dict) or set(source) != SOURCE_KEYS:
            fail(f"sources[{index}] 字段不完整或包含未知字段")
        version = source.get("version")
        schema = source.get("schema")
        if not isinstance(version, str) or not SOURCE_VERSION_RE.fullmatch(version):
            fail(f"sources[{index}].version 格式不受支持")
        if not isinstance(schema, str):
            fail(f"sources[{index}].schema 必须是字符串整数")
        positive_integer(schema, f"sources[{index}].schema", allow_zero=True)
        identity = (version, schema)
        if identity in seen:
            fail(f"升级矩阵重复来源：{version}/schema-{schema}")
        seen.add(identity)
        if source.get("mode") not in UPGRADE_MODES:
            fail(f"sources[{index}].mode 不受支持")
        if source.get("downtimeClass") not in DOWNTIME_CLASSES:
            fail(f"sources[{index}].downtimeClass 不受支持")
        if source.get("rollbackMode") not in ROLLBACK_MODES:
            fail(f"sources[{index}].rollbackMode 不受支持")
        if version_key(version) >= version_key(target_version):
            fail(f"sources[{index}].version 必须严格早于 targetVersion")
        if schema != target_schema and (
            source.get("mode") == "image-only" or source.get("rollbackMode") == "image-only"
        ):
            fail(f"sources[{index}] 存在 schema 变化时不得声明 image-only 升级或回退")
    return document


def load_matrix(path: pathlib.Path) -> tuple[pathlib.Path, dict[str, Any]]:
    resolved = secure_regular_file(path, "升级矩阵")
    try:
        document = json.loads(resolved.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        fail(f"升级矩阵不是有效 UTF-8 JSON：{exc}")
    return resolved, validate_matrix_document(document)


def resolve_matrix(manifest_path: pathlib.Path, values: dict[str, str], override: pathlib.Path | None) -> pathlib.Path:
    filename = values.get("COPPER_UPGRADE_MATRIX_FILE", "")
    if pathlib.PurePosixPath(filename).name != filename or filename in {".", ".."}:
        fail("COPPER_UPGRADE_MATRIX_FILE 必须是与发布清单同目录的普通文件名")
    expected = (manifest_path.parent / filename).resolve()
    selected = override.resolve() if override else expected
    if selected != expected:
        fail("--matrix 必须与 COPPER_UPGRADE_MATRIX_FILE 解析到同一个文件")
    return selected


def validate_manifest_values(
    manifest_path: pathlib.Path,
    values: dict[str, str],
    fields_path: pathlib.Path,
    matrix_override: pathlib.Path | None = None,
) -> tuple[pathlib.Path, dict[str, Any]]:
    fields = required_fields(fields_path)
    missing = [field for field in fields if field not in values]
    if missing:
        fail(f"发布清单缺少必填字段：{','.join(missing)}")
    unknown = sorted(set(values) - set(fields) - BUILD_IDENTITY_FIELDS)
    if unknown:
        fail(f"copper-release/v1 禁止未定义字段：{','.join(unknown)}")
    if values["COPPER_RELEASE_MANIFEST_FORMAT"] != MANIFEST_FORMAT:
        fail(f"COPPER_RELEASE_MANIFEST_FORMAT 必须是 {MANIFEST_FORMAT}")
    version = values["COPPER_RELEASE_VERSION"]
    if not FUTURE_VERSION_RE.fullmatch(version):
        fail("COPPER_RELEASE_VERSION 不是 M3 起允许的新版本格式")
    channel = values["COPPER_RELEASE_CHANNEL"]
    if channel not in {"rc", "stable"} or ("-rc." in version) != (channel == "rc"):
        fail("COPPER_RELEASE_CHANNEL 与版本后缀不一致")
    parse_timestamp(values["COPPER_RELEASED_AT"], "COPPER_RELEASED_AT")
    if values["COPPER_DEPLOY_TAG"] != f"v{version}":
        fail("COPPER_DEPLOY_TAG 必须精确等于 v 加产品版本")
    for field in ("COPPER_DEPLOY_COMMIT", "COPPER_WEB_SOURCE_COMMIT", "COPPER_BACKEND_SOURCE_COMMIT"):
        if not COMMIT_RE.fullmatch(values[field]):
            fail(f"{field} 必须是 40 位 Git 提交 SHA")
    for field in ("COPPER_WEB_IMAGE", "COPPER_BACKEND_IMAGE", "COPPER_MYSQL_IMAGE"):
        if not IMAGE_RE.fullmatch(values[field]):
            fail(f"{field} 必须是 registry@sha256 不可变镜像引用")
    for role in ("WEB", "BACKEND"):
        try:
            validate_role(manifest_path, values, role)
        except (ValueError, OSError) as error:
            fail(str(error))
    for field in ("COPPER_WEB_PLATFORM", "COPPER_BACKEND_PLATFORM", "COPPER_MYSQL_PLATFORM"):
        if values[field] != "linux/amd64":
            fail(f"{field} 当前只允许 linux/amd64")
    if not re.fullmatch(r"8\.4\.[0-9]+", values["COPPER_MYSQL_VERSION"]):
        fail("COPPER_MYSQL_VERSION 当前必须是 MySQL 8.4 LTS 补丁版本")
    if values["COPPER_SUPPORTED_OS"] != "ubuntu-24.04" or values["COPPER_SUPPORTED_ARCH"] != "amd64":
        fail("M3 当前只支持 ubuntu-24.04/amd64")
    for field in ("COPPER_MIN_CPU", "COPPER_MIN_MEMORY_GIB", "COPPER_MIN_FREE_DISK_GIB"):
        positive_integer(values[field], field)
    positive_integer(values["COPPER_DATABASE_SCHEMA_VERSION"], "COPPER_DATABASE_SCHEMA_VERSION", allow_zero=True)
    for field in ("COPPER_MIGRATION_SET_SHA256", "COPPER_UPGRADE_MATRIX_SHA256", "COPPER_RELEASE_NOTES_SHA256"):
        if not HASH_RE.fullmatch(values[field]):
            fail(f"{field} 必须是小写 SHA-256")
    if values["COPPER_BACKUP_FORMAT"] != "copper-backup/v1":
        fail("COPPER_BACKUP_FORMAT 必须是 copper-backup/v1")
    if values["COPPER_EXPORT_FORMAT"] != "copper-export/v1":
        fail("COPPER_EXPORT_FORMAT 必须是 copper-export/v1")
    if values["COPPER_VALIDATION_LEVEL"] not in {"L1", "L2", "L3", "L4"}:
        fail("COPPER_VALIDATION_LEVEL 只允许 L1-L4")

    validate_bound_file(manifest_path, values, "COPPER_DEPLOY_ARCHIVE", "COPPER_DEPLOY_ARCHIVE_SHA256")
    validate_bound_file(manifest_path, values, "COPPER_RELEASE_NOTES_FILE", "COPPER_RELEASE_NOTES_SHA256")
    validate_bound_file(manifest_path, values, "COPPER_ACME_ARCHIVE", "COPPER_ACME_ARCHIVE_SHA256")

    matrix_path = resolve_matrix(manifest_path, values, matrix_override)
    resolved_matrix, matrix = load_matrix(matrix_path)
    if sha256_file(resolved_matrix) != values["COPPER_UPGRADE_MATRIX_SHA256"]:
        fail("升级矩阵实际 SHA-256 与发布清单不一致")
    if matrix["targetVersion"] != version:
        fail("升级矩阵 targetVersion 与发布清单版本不一致")
    if matrix["targetSchema"] != values["COPPER_DATABASE_SCHEMA_VERSION"]:
        fail("升级矩阵 targetSchema 与发布清单 schema 不一致")
    return resolved_matrix, matrix


def load_manifest(
    path: pathlib.Path,
    fields_path: pathlib.Path,
    matrix_override: pathlib.Path | None = None,
) -> tuple[pathlib.Path, dict[str, str], pathlib.Path, dict[str, Any]]:
    resolved = secure_regular_file(path, "发布清单")
    values = parse_env(resolved, "发布清单")
    matrix_path, matrix = validate_manifest_values(resolved, values, fields_path, matrix_override)
    return resolved, values, matrix_path, matrix


def source_spec(value: str) -> dict[str, str]:
    parts = value.split(",")
    if len(parts) != 5:
        fail("--source 必须是 VERSION,SCHEMA,MODE,DOWNTIME_CLASS,ROLLBACK_MODE")
    return dict(zip(("version", "schema", "mode", "downtimeClass", "rollbackMode"), parts, strict=True))


def command_generate_matrix(args: argparse.Namespace) -> None:
    output = pathlib.Path(args.output).resolve()
    document = {
        "format": MATRIX_FORMAT,
        "targetVersion": args.target_version,
        "targetSchema": args.target_schema,
        "sources": [source_spec(item) for item in (args.source or [])],
    }
    if args.fresh_install_only:
        document["freshInstallOnly"] = True
    validate_matrix_document(document)
    atomic_write(output, json.dumps(document, ensure_ascii=False, indent=2, sort_keys=False) + "\n")
    print(f"matrix={output}")
    print(f"sha256={sha256_file(output)}")


def command_validate_matrix(args: argparse.Namespace) -> None:
    path, document = load_matrix(pathlib.Path(args.matrix))
    print(f"format={document['format']}")
    print(f"target_version={document['targetVersion']}")
    print(f"target_schema={document['targetSchema']}")
    print(f"source_count={len(document['sources'])}")
    print(f"sha256={sha256_file(path)}")


def command_generate_manifest(args: argparse.Namespace) -> None:
    output = pathlib.Path(args.output).resolve()
    values = parse_env(pathlib.Path(args.values), "发布清单输入值")
    for assignment in args.set:
        if "=" not in assignment:
            fail("--set 必须是 KEY=VALUE")
        key, value = assignment.split("=", 1)
        if not SAFE_KEY_RE.fullmatch(key) or not value or not SAFE_VALUE_RE.fullmatch(value):
            fail("--set 包含不安全的字段名或值")
        values[key] = value
    matrix_input, matrix = load_matrix(pathlib.Path(args.matrix))
    matrix_name = args.matrix_name or matrix_input.name
    if pathlib.PurePosixPath(matrix_name).name != matrix_name:
        fail("--matrix-name 必须是普通文件名")
    values["COPPER_RELEASE_MANIFEST_FORMAT"] = MANIFEST_FORMAT
    values["COPPER_UPGRADE_MATRIX_FILE"] = matrix_name
    values["COPPER_UPGRADE_MATRIX_SHA256"] = sha256_file(matrix_input)
    fields = required_fields(pathlib.Path(args.fields))
    missing = [field for field in fields if field not in values]
    if missing:
        fail(f"发布清单输入值缺少：{','.join(missing)}")
    expected_matrix = output.parent / matrix_name
    if expected_matrix.resolve() != matrix_input.resolve():
        fail("生成清单前必须先把矩阵放到输出目录，并使 --matrix-name 与文件名一致")
    validate_manifest_values(output, values, pathlib.Path(args.fields))
    ordered = fields + sorted(set(values) - set(fields))
    content = "".join(f"{field}={values[field]}\n" for field in ordered)
    atomic_write(output, content)
    print(f"manifest={output}")
    print(f"sha256={sha256_file(output)}")
    print(f"target_version={matrix['targetVersion']}")


def command_validate_manifest(args: argparse.Namespace) -> None:
    manifest, values, matrix_path, matrix = load_manifest(
        pathlib.Path(args.manifest), pathlib.Path(args.fields), pathlib.Path(args.matrix) if args.matrix else None
    )
    print(f"format={values['COPPER_RELEASE_MANIFEST_FORMAT']}")
    print(f"target_version={values['COPPER_RELEASE_VERSION']}")
    print(f"target_schema={values['COPPER_DATABASE_SCHEMA_VERSION']}")
    print(f"manifest_sha256={sha256_file(manifest)}")
    print(f"matrix={matrix_path}")
    print(f"matrix_sha256={sha256_file(matrix_path)}")
    print(f"source_count={len(matrix['sources'])}")


def command_find_source(args: argparse.Namespace) -> None:
    manifest, values, matrix_path, matrix = load_manifest(
        pathlib.Path(args.manifest), pathlib.Path(args.fields)
    )
    matches = [
        item for item in matrix["sources"]
        if item["version"] == args.source_version and item["schema"] == args.source_schema
    ]
    if len(matches) != 1:
        fail(f"目标发布未明确允许来源 {args.source_version}/schema-{args.source_schema}")
    output = {
        "manifest": str(manifest),
        "manifestSha256": sha256_file(manifest),
        "matrix": str(matrix_path),
        "matrixSha256": sha256_file(matrix_path),
        "targetVersion": values["COPPER_RELEASE_VERSION"],
        "targetSchema": values["COPPER_DATABASE_SCHEMA_VERSION"],
        "minimumCpu": int(values["COPPER_MIN_CPU"]),
        "minimumMemoryGiB": int(values["COPPER_MIN_MEMORY_GIB"]),
        "minimumFreeDiskGiB": int(values["COPPER_MIN_FREE_DISK_GIB"]),
        "mysqlVersion": values["COPPER_MYSQL_VERSION"],
        "supportedOs": values["COPPER_SUPPORTED_OS"],
        "supportedArch": values["COPPER_SUPPORTED_ARCH"],
        "artifacts": {
            "deployArchive": {
                "path": str((manifest.parent / values["COPPER_DEPLOY_ARCHIVE"]).resolve()),
                "sha256": values["COPPER_DEPLOY_ARCHIVE_SHA256"],
            },
            "releaseNotes": {
                "path": str((manifest.parent / values["COPPER_RELEASE_NOTES_FILE"]).resolve()),
                "sha256": values["COPPER_RELEASE_NOTES_SHA256"],
            },
            "acmeArchive": {
                "path": str((manifest.parent / values["COPPER_ACME_ARCHIVE"]).resolve()),
                "sha256": values["COPPER_ACME_ARCHIVE_SHA256"],
            },
        },
        "source": matches[0],
    }
    print(json.dumps(output, ensure_ascii=False, separators=(",", ":")))


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description="Copper M3 release contract helper")
    root.add_argument(
        "--fields",
        default=str(pathlib.Path(__file__).resolve().parent.parent / "config/release-manifest-v1.fields"),
    )
    subparsers = root.add_subparsers(dest="command", required=True)

    generate_matrix = subparsers.add_parser("generate-matrix")
    generate_matrix.add_argument("--target-version", required=True)
    generate_matrix.add_argument("--target-schema", required=True)
    matrix_mode = generate_matrix.add_mutually_exclusive_group(required=True)
    matrix_mode.add_argument("--source", action="append")
    matrix_mode.add_argument("--fresh-install-only", action="store_true")
    generate_matrix.add_argument("--output", required=True)
    generate_matrix.set_defaults(handler=command_generate_matrix)

    validate_matrix = subparsers.add_parser("validate-matrix")
    validate_matrix.add_argument("matrix")
    validate_matrix.set_defaults(handler=command_validate_matrix)

    generate_manifest = subparsers.add_parser("generate-manifest")
    generate_manifest.add_argument("--values", required=True)
    generate_manifest.add_argument("--matrix", required=True)
    generate_manifest.add_argument("--matrix-name")
    generate_manifest.add_argument("--set", action="append", default=[])
    generate_manifest.add_argument("--output", required=True)
    generate_manifest.set_defaults(handler=command_generate_manifest)

    validate_manifest = subparsers.add_parser("validate-manifest")
    validate_manifest.add_argument("manifest")
    validate_manifest.add_argument("--matrix")
    validate_manifest.set_defaults(handler=command_validate_manifest)

    find_source = subparsers.add_parser("find-source")
    find_source.add_argument("manifest")
    find_source.add_argument("--source-version", required=True)
    find_source.add_argument("--source-schema", required=True)
    find_source.set_defaults(handler=command_find_source)
    return root


def main() -> int:
    try:
        args = parser().parse_args()
        args.handler(args)
        return 0
    except (ContractError, OSError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
