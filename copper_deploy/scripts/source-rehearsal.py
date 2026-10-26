#!/usr/bin/env python3
"""Build and verify a deterministic Copper internal source rehearsal package.

This tool intentionally does not create a customer delivery artefact.  It exists
to exercise the three-repository source hand-off and offline verification chain
before M6-02, M6-03 and M6-05 allow an external delivery.
"""

from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import io
import json
import os
import pathlib
import re
import subprocess
import tarfile
import tempfile
from typing import Any


FORMAT = "copper-source-rehearsal/v1"
FILES_FORMAT = "copper-source-rehearsal-files/v1"
PURPOSE = "INTERNAL_REHEARSAL_ONLY"
FORMAL_RELEASE_STATUS = "FORMAL_RELEASE_BLOCKED"
FORMAL_BLOCKERS = ("M6-02", "M6-03", "M6-05")
HASH_RE = re.compile(r"[0-9a-f]{64}")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")
VERSION_RE = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-rc\.[1-9][0-9]*)?")
MAX_CONTROL_FILE_BYTES = 16 * 1024 * 1024
ROLE_SPECS = (
    ("source-web", "copper_web", "COPPER_WEB_SOURCE_COMMIT"),
    ("source-backend", "copper_backend", "COPPER_BACKEND_SOURCE_COMMIT"),
    ("source-deploy", "copper_deploy", "COPPER_DEPLOY_COMMIT"),
)


class RehearsalError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise RehearsalError(message)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def secure_file(path: pathlib.Path, label: str) -> pathlib.Path:
    if path.is_symlink() or not path.is_file():
        fail(f"{label} 必须是普通文件且不能是符号链接：{path}")
    return path.resolve()


def secure_directory(path: pathlib.Path, label: str) -> pathlib.Path:
    if path.is_symlink() or not path.is_dir():
        fail(f"{label} 必须是目录且不能是符号链接：{path}")
    return path.resolve()


def safe_relative(value: str, label: str) -> pathlib.PurePosixPath:
    pure = pathlib.PurePosixPath(value)
    if not value or pure.is_absolute() or ".." in pure.parts or any(part in {"", "."} for part in pure.parts):
        fail(f"{label} 不是安全相对路径：{value!r}")
    return pure


def git(repo: pathlib.Path, *args: str, text: bool = True) -> str | bytes:
    result = subprocess.run(
        ["git", "-C", str(repo), *args], check=False, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=text,
    )
    if result.returncode:
        error = result.stderr if text else result.stderr.decode("utf-8", "replace")
        fail(f"Git 命令失败（{repo}）：{error.strip()}")
    return result.stdout


def parse_env(path: pathlib.Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw in enumerate(secure_file(path, "发布清单").read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"发布清单第 {number} 行不是 KEY=VALUE")
        key, value = line.split("=", 1)
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in values:
            fail(f"发布清单第 {number} 行字段非法或重复：{key}")
        values[key] = value
    return values


def parse_epoch(value: str) -> int:
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        fail("COPPER_RELEASED_AT 必须是带时区的 ISO-8601 时间")
    if parsed.tzinfo is None:
        fail("COPPER_RELEASED_AT 必须包含时区")
    return int(parsed.timestamp())


def validate_manifest(path: pathlib.Path) -> tuple[pathlib.Path, dict[str, str], int]:
    resolved = secure_file(path, "发布清单")
    values = parse_env(resolved)
    required = {
        "COPPER_RELEASE_VERSION", "COPPER_RELEASED_AT", "COPPER_WEB_SOURCE_COMMIT",
        "COPPER_BACKEND_SOURCE_COMMIT", "COPPER_DEPLOY_COMMIT", "COPPER_WEB_IMAGE",
        "COPPER_BACKEND_IMAGE", "COPPER_MYSQL_IMAGE", "COPPER_MIGRATION_SET_SHA256",
        "COPPER_DATABASE_SCHEMA_VERSION", "COPPER_SUPPORTED_OS", "COPPER_SUPPORTED_ARCH",
    }
    missing = sorted(required - set(values))
    if missing:
        fail(f"发布清单缺少 M6-04 绑定字段：{','.join(missing)}")
    if not VERSION_RE.fullmatch(values["COPPER_RELEASE_VERSION"]):
        fail("COPPER_RELEASE_VERSION 不符合产品版本格式")
    for _, _, field in ROLE_SPECS:
        if not COMMIT_RE.fullmatch(values[field]):
            fail(f"{field} 必须为 40 位 Git 提交")
    for field in ("COPPER_WEB_IMAGE", "COPPER_BACKEND_IMAGE", "COPPER_MYSQL_IMAGE"):
        if not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", values[field]):
            fail(f"{field} 必须为不可变镜像摘要")
    if not HASH_RE.fullmatch(values["COPPER_MIGRATION_SET_SHA256"]):
        fail("COPPER_MIGRATION_SET_SHA256 必须为小写 SHA-256")
    if not values["COPPER_DATABASE_SCHEMA_VERSION"].isdigit():
        fail("COPPER_DATABASE_SCHEMA_VERSION 必须为整数")
    if values["COPPER_SUPPORTED_OS"] != "ubuntu-24.04" or values["COPPER_SUPPORTED_ARCH"] != "amd64":
        fail("M6-04 内部演练目前只支持 ubuntu-24.04/amd64")
    return resolved, values, parse_epoch(values["COPPER_RELEASED_AT"])


def forbidden_source(path: pathlib.PurePosixPath, content: bytes) -> str | None:
    name = path.name.lower()
    if name == ".env" or (name.startswith(".env.") and not name.endswith(".example")):
        return "环境文件不能进入内部源码演练包"
    if path.suffix.lower() in {".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".bak", ".backup", ".dump"}:
        return "疑似凭据或备份文件不能进入内部源码演练包"
    private_key_prefix = b"-----BEGIN "
    private_key_headers = tuple(
        private_key_prefix + kind + b"PRIVATE KEY-----"
        for kind in (b"", b"RSA ", b"EC ", b"DSA ", b"OPENSSH ")
    )
    if any(header in content for header in private_key_headers):
        return "私钥内容不能进入内部源码演练包"
    return None


def repository_payload(
    role: str, directory_name: str, commit_field: str, raw_repo: str, manifest: dict[str, str]
) -> tuple[dict[str, Any], dict[str, tuple[bytes, int]], list[dict[str, Any]]]:
    repo = secure_directory(pathlib.Path(raw_repo), role)
    if git(repo, "rev-parse", "--is-inside-work-tree").strip() != "true":
        fail(f"{role} 不是 Git 仓库")
    expected_commit = manifest[commit_field]
    if git(repo, "rev-parse", "HEAD").strip() != expected_commit:
        fail(f"{role} HEAD 未精确绑定发布清单提交")
    if git(repo, "status", "--porcelain").strip():
        fail(f"{role} 工作区不干净，拒绝从未评审修改生成演练包")
    tree = str(git(repo, "rev-parse", f"{expected_commit}^{{tree}}")).strip()
    raw_entries = git(repo, "ls-tree", "-r", "-z", expected_commit, text=False)
    assert isinstance(raw_entries, bytes)
    payload: dict[str, tuple[bytes, int]] = {}
    records: list[dict[str, Any]] = []
    for raw_entry in raw_entries.split(b"\0"):
        if not raw_entry:
            continue
        try:
            header, raw_name = raw_entry.split(b"\t", 1)
            mode, object_type, object_id = header.decode("ascii").split(" ")
            name = raw_name.decode("utf-8")
        except (ValueError, UnicodeDecodeError) as error:
            fail(f"{role} 包含无法解析的 Git 树条目：{error}")
        relative = safe_relative(name, f"{role} 跟踪路径")
        if object_type != "blob" or mode not in {"100644", "100755"} or not COMMIT_RE.fullmatch(object_id):
            fail(f"{role} 仅允许普通可执行/不可执行文件：{name}")
        content = git(repo, "cat-file", "blob", object_id, text=False)
        assert isinstance(content, bytes)
        blocked = forbidden_source(relative, content)
        if blocked:
            fail(f"{role} 跟踪文件 {name} 被拒绝：{blocked}")
        package_path = f"sources/{directory_name}/{relative.as_posix()}"
        payload[package_path] = (content, 0o755 if mode == "100755" else 0o644)
        records.append({
            "path": package_path,
            "bytes": len(content),
            "sha256": sha256_bytes(content),
            "mode": 0o755 if mode == "100755" else 0o644,
        })
    if not records:
        fail(f"{role} 没有可打包的已跟踪文件")
    return ({
        "role": role,
        "path": f"sources/{directory_name}",
        "commit": expected_commit,
        "tree": tree,
        "fileCount": len(records),
    }, payload, records)


def notice(release_version: str) -> bytes:
    return f"""# Copper 内部源码交付演练包

状态：`{PURPOSE}`
候选版本：`{release_version}`

本归档仅用于 Copper 内部验证源码快照、空白环境构建、部署、升级、备份、恢复与导出流程。
它不是客户源码包、公开发布物、GitHub Release 或开源许可证授予；不得发送给客户或第三方。

正式客户交付/公开发布状态：`{FORMAL_RELEASE_STATUS}`
仍需收口：M6-02 历史与隐私审计、M6-03 权利台账和 M6-05 知识产权律师复核。
""".encode("utf-8")


def write_tar_gz(output: pathlib.Path, files: dict[str, tuple[bytes, int]], epoch: int) -> None:
    if output.exists() or output.is_symlink():
        fail(f"输出已存在，拒绝覆盖：{output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    parent = secure_directory(output.parent, "输出目录")
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{output.name}.", dir=parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as raw, gzip.GzipFile(
            filename="", mode="wb", fileobj=raw, mtime=epoch
        ) as compressed, tarfile.open(fileobj=compressed, mode="w", format=tarfile.GNU_FORMAT) as archive:
            for name in sorted(files):
                content, mode = files[name]
                safe_relative(name, "归档路径")
                member = tarfile.TarInfo(name=name)
                member.size = len(content)
                member.mode = mode
                member.mtime = epoch
                member.uid = 0
                member.gid = 0
                member.uname = ""
                member.gname = ""
                archive.addfile(member, io.BytesIO(content))
        os.chmod(temporary, 0o600)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)


def build(args: argparse.Namespace) -> None:
    manifest_path, manifest, epoch = validate_manifest(pathlib.Path(args.manifest))
    output = pathlib.Path(args.output).resolve()
    if output.suffixes[-2:] != [".tar", ".gz"]:
        fail("内部源码演练包必须使用 .tar.gz 后缀")
    input_map = {
        "source-web": args.web_repo,
        "source-backend": args.backend_repo,
        "source-deploy": args.deploy_repo,
    }
    repositories: list[dict[str, Any]] = []
    files: dict[str, tuple[bytes, int]] = {}
    payload_records: list[dict[str, Any]] = []
    for role, directory_name, commit_field in ROLE_SPECS:
        repository, repository_files, records = repository_payload(
            role, directory_name, commit_field, input_map[role], manifest
        )
        repositories.append(repository)
        files.update(repository_files)
        payload_records.extend(records)

    manifest_name = manifest_path.name
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,191}", manifest_name):
        fail("发布清单文件名不安全")
    manifest_content = manifest_path.read_bytes()
    release_path = f"release/{manifest_name}"
    files[release_path] = (manifest_content, 0o444)
    payload_records.append({
        "path": release_path,
        "bytes": len(manifest_content),
        "sha256": sha256_bytes(manifest_content),
        "mode": 0o444,
    })
    notice_content = notice(manifest["COPPER_RELEASE_VERSION"])
    files["REHEARSAL-NOTICE.md"] = (notice_content, 0o444)
    payload_records.append({
        "path": "REHEARSAL-NOTICE.md",
        "bytes": len(notice_content),
        "sha256": sha256_bytes(notice_content),
        "mode": 0o444,
    })
    payload_records.sort(key=lambda item: str(item["path"]))
    files_document = {"format": FILES_FORMAT, "files": payload_records}
    files_content = canonical_json(files_document)
    provenance = {
        "format": FORMAT,
        "purpose": PURPOSE,
        "formalRelease": {"status": FORMAL_RELEASE_STATUS, "blockers": list(FORMAL_BLOCKERS)},
        "release": {
            "version": manifest["COPPER_RELEASE_VERSION"],
            "manifestPath": release_path,
            "manifestSha256": sha256_bytes(manifest_content),
            "migrationSetSha256": manifest["COPPER_MIGRATION_SET_SHA256"],
            "databaseSchema": manifest["COPPER_DATABASE_SCHEMA_VERSION"],
            "platform": f"{manifest['COPPER_SUPPORTED_OS']}/{manifest['COPPER_SUPPORTED_ARCH']}",
            "images": {
                "web": manifest["COPPER_WEB_IMAGE"],
                "backend": manifest["COPPER_BACKEND_IMAGE"],
                "mysql": manifest["COPPER_MYSQL_IMAGE"],
            },
        },
        "repositories": repositories,
        "payloadManifest": "files.json",
        "notice": "REHEARSAL-NOTICE.md",
        "sourceDateEpoch": epoch,
    }
    provenance_content = canonical_json(provenance)
    files["files.json"] = (files_content, 0o444)
    files["provenance.json"] = (provenance_content, 0o444)
    checksummed = ("REHEARSAL-NOTICE.md", release_path, "files.json", "provenance.json")
    checksums = "".join(f"{sha256_bytes(files[name][0])}  {name}\n" for name in sorted(checksummed)).encode("utf-8")
    files["SHA256SUMS"] = (checksums, 0o444)
    write_tar_gz(output, files, epoch)
    print(json.dumps({
        "format": FORMAT,
        "purpose": PURPOSE,
        "formalRelease": FORMAL_RELEASE_STATUS,
        "releaseVersion": manifest["COPPER_RELEASE_VERSION"],
        "output": str(output),
        "sha256": sha256_bytes(output.read_bytes()),
    }, ensure_ascii=False, sort_keys=True))


def member_bytes(archive: tarfile.TarFile, member: tarfile.TarInfo, label: str) -> bytes:
    if member.size > MAX_CONTROL_FILE_BYTES:
        fail(f"{label} 超过允许大小")
    stream = archive.extractfile(member)
    if stream is None:
        fail(f"无法读取 {label}")
    return stream.read()


def load_json(content: bytes, label: str) -> dict[str, Any]:
    try:
        parsed = json.loads(content.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"{label} 不是有效 UTF-8 JSON：{error}")
    if not isinstance(parsed, dict):
        fail(f"{label} 必须为 JSON 对象")
    return parsed


def exact_keys(document: dict[str, Any], expected: set[str], label: str) -> None:
    if set(document) != expected:
        fail(f"{label} 字段必须精确为：{','.join(sorted(expected))}")


def validate_file_record(record: object) -> dict[str, Any]:
    if not isinstance(record, dict):
        fail("files.json 包含非对象条目")
    exact_keys(record, {"path", "bytes", "sha256", "mode"}, "files.json 条目")
    path = record.get("path")
    if not isinstance(path, str):
        fail("files.json.path 必须为字符串")
    safe_relative(path, "files.json.path")
    if not isinstance(record.get("bytes"), int) or record["bytes"] < 0:
        fail("files.json.bytes 必须为非负整数")
    if not isinstance(record.get("sha256"), str) or not HASH_RE.fullmatch(record["sha256"]):
        fail("files.json.sha256 必须为小写 SHA-256")
    if record.get("mode") not in {0o444, 0o644, 0o755}:
        fail("files.json.mode 只能为 0444、0644 或 0755")
    return record


def parse_checksum(content: bytes) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = content.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        fail(f"SHA256SUMS 不是 UTF-8：{error}")
    for raw in lines:
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", raw)
        if not match:
            fail("SHA256SUMS 行格式不正确")
        digest, path = match.groups()
        safe_relative(path, "SHA256SUMS 路径")
        if path in values:
            fail("SHA256SUMS 包含重复路径")
        values[path] = digest
    return values


def verify(args: argparse.Namespace) -> None:
    bundle = secure_file(pathlib.Path(args.bundle), "内部源码演练包")
    if bundle.suffixes[-2:] != [".tar", ".gz"]:
        fail("内部源码演练包必须使用 .tar.gz 后缀")
    try:
        with tarfile.open(bundle, "r:gz") as archive:
            members: dict[str, tarfile.TarInfo] = {}
            for member in archive.getmembers():
                safe_relative(member.name, "归档成员")
                if not member.isfile() or member.issym() or member.islnk() or member.name in members:
                    fail(f"归档包含非普通或重复成员：{member.name}")
                members[member.name] = member
            for required in ("REHEARSAL-NOTICE.md", "provenance.json", "files.json", "SHA256SUMS"):
                if required not in members:
                    fail(f"归档缺少必要文件：{required}")
            controls = {name: member_bytes(archive, members[name], name) for name in (
                "REHEARSAL-NOTICE.md", "provenance.json", "files.json", "SHA256SUMS"
            )}
            provenance = load_json(controls["provenance.json"], "provenance.json")
            exact_keys(provenance, {
                "format", "purpose", "formalRelease", "release", "repositories", "payloadManifest",
                "notice", "sourceDateEpoch",
            }, "provenance.json")
            if provenance["format"] != FORMAT or provenance["purpose"] != PURPOSE:
                fail("provenance.json 未标记为内部源码演练")
            formal = provenance["formalRelease"]
            if formal != {"status": FORMAL_RELEASE_STATUS, "blockers": list(FORMAL_BLOCKERS)}:
                fail("provenance.json 正式发布阻断状态不正确")
            if provenance["payloadManifest"] != "files.json" or provenance["notice"] != "REHEARSAL-NOTICE.md":
                fail("provenance.json 控制文件路径不正确")
            if not isinstance(provenance["sourceDateEpoch"], int) or provenance["sourceDateEpoch"] < 0:
                fail("provenance.json sourceDateEpoch 不正确")
            release = provenance["release"]
            if not isinstance(release, dict):
                fail("provenance.json release 不正确")
            exact_keys(release, {
                "version", "manifestPath", "manifestSha256", "migrationSetSha256", "databaseSchema",
                "platform", "images",
            }, "provenance.json release")
            if not isinstance(release["manifestPath"], str) or release["manifestPath"] not in members:
                fail("归档缺少发布清单")
            manifest_content = member_bytes(archive, members[release["manifestPath"]], "发布清单")
            if sha256_bytes(manifest_content) != release["manifestSha256"]:
                fail("发布清单 SHA-256 不匹配")
            manifest_values = parse_env_from_bytes(manifest_content)
            validate_embedded_manifest(manifest_values, release, provenance["repositories"])

            files_document = load_json(controls["files.json"], "files.json")
            exact_keys(files_document, {"format", "files"}, "files.json")
            if files_document["format"] != FILES_FORMAT or not isinstance(files_document["files"], list):
                fail("files.json 格式不正确")
            records = [validate_file_record(record) for record in files_document["files"]]
            paths = [str(record["path"]) for record in records]
            if len(paths) != len(set(paths)):
                fail("files.json 包含重复路径")
            expected_members = set(paths) | {"files.json", "provenance.json", "SHA256SUMS"}
            if set(members) != expected_members:
                fail("归档成员集合与 files.json 不一致")
            for record in records:
                name = str(record["path"])
                content = member_bytes(archive, members[name], name)
                if len(content) != record["bytes"] or sha256_bytes(content) != record["sha256"]:
                    fail(f"归档成员字节数或 SHA-256 不匹配：{name}")
                if members[name].mode & 0o777 != record["mode"]:
                    fail(f"归档成员权限不匹配：{name}")
            checksums = parse_checksum(controls["SHA256SUMS"])
            expected_checks = {"REHEARSAL-NOTICE.md", release["manifestPath"], "files.json", "provenance.json"}
            if set(checksums) != expected_checks:
                fail("SHA256SUMS 覆盖范围不正确")
            for name, digest in checksums.items():
                content = member_bytes(archive, members[name], name)
                if sha256_bytes(content) != digest:
                    fail(f"SHA256SUMS 校验失败：{name}")
            if PURPOSE.encode("utf-8") not in controls["REHEARSAL-NOTICE.md"]:
                fail("内部演练声明缺失")
    except (tarfile.TarError, OSError) as error:
        fail(f"无法读取内部源码演练包：{error}")
    print(json.dumps({
        "format": FORMAT,
        "purpose": PURPOSE,
        "formalRelease": FORMAL_RELEASE_STATUS,
        "bundle": str(bundle),
        "sha256": sha256_bytes(bundle.read_bytes()),
        "status": "PASS",
    }, ensure_ascii=False, sort_keys=True))


def parse_env_from_bytes(content: bytes) -> dict[str, str]:
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as error:
        fail(f"归档发布清单不是 UTF-8：{error}")
    values: dict[str, str] = {}
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"归档发布清单第 {number} 行不是 KEY=VALUE")
        key, value = line.split("=", 1)
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in values:
            fail(f"归档发布清单字段非法或重复：{key}")
        values[key] = value
    return values


def validate_embedded_manifest(
    values: dict[str, str], release: dict[str, Any], repositories: object
) -> None:
    required = {"COPPER_RELEASE_VERSION", "COPPER_MIGRATION_SET_SHA256", "COPPER_DATABASE_SCHEMA_VERSION",
                "COPPER_SUPPORTED_OS", "COPPER_SUPPORTED_ARCH", "COPPER_WEB_IMAGE", "COPPER_BACKEND_IMAGE",
                "COPPER_MYSQL_IMAGE", *(field for _, _, field in ROLE_SPECS)}
    if not required <= set(values):
        fail("归档发布清单缺少 M6-04 绑定字段")
    if values["COPPER_RELEASE_VERSION"] != release["version"] or not VERSION_RE.fullmatch(release["version"]):
        fail("归档发布版本不一致")
    if values["COPPER_MIGRATION_SET_SHA256"] != release["migrationSetSha256"] or not HASH_RE.fullmatch(release["migrationSetSha256"]):
        fail("归档迁移集合摘要不一致")
    if values["COPPER_DATABASE_SCHEMA_VERSION"] != release["databaseSchema"]:
        fail("归档数据库 schema 不一致")
    if release["platform"] != f"{values['COPPER_SUPPORTED_OS']}/{values['COPPER_SUPPORTED_ARCH']}":
        fail("归档平台不一致")
    expected_images = {"web": values["COPPER_WEB_IMAGE"], "backend": values["COPPER_BACKEND_IMAGE"], "mysql": values["COPPER_MYSQL_IMAGE"]}
    if release["images"] != expected_images:
        fail("归档镜像摘要不一致")
    if not isinstance(repositories, list) or len(repositories) != len(ROLE_SPECS):
        fail("归档仓库清单不正确")
    by_role = {item.get("role"): item for item in repositories if isinstance(item, dict)}
    if set(by_role) != {role for role, _, _ in ROLE_SPECS}:
        fail("归档仓库角色不正确")
    for role, directory_name, commit_field in ROLE_SPECS:
        item = by_role[role]
        exact_keys(item, {"role", "path", "commit", "tree", "fileCount"}, f"仓库 {role}")
        if item["path"] != f"sources/{directory_name}" or item["commit"] != values[commit_field]:
            fail(f"归档仓库 {role} 未绑定发布清单")
        if not COMMIT_RE.fullmatch(item["tree"]) or not isinstance(item["fileCount"], int) or item["fileCount"] <= 0:
            fail(f"归档仓库 {role} 元数据不正确")


def main() -> int:
    parser = argparse.ArgumentParser(description="Copper M6-04 internal source rehearsal package")
    commands = parser.add_subparsers(dest="command", required=True)
    build_command = commands.add_parser("build", help="生成 INTERNAL_REHEARSAL_ONLY 三仓源码快照")
    build_command.add_argument("--manifest", required=True)
    build_command.add_argument("--web-repo", required=True)
    build_command.add_argument("--backend-repo", required=True)
    build_command.add_argument("--deploy-repo", required=True)
    build_command.add_argument("--output", required=True)
    build_command.set_defaults(handler=build)
    verify_command = commands.add_parser("verify", help="离线校验内部源码演练包")
    verify_command.add_argument("--bundle", required=True)
    verify_command.set_defaults(handler=verify)
    args = parser.parse_args()
    try:
        args.handler(args)
    except RehearsalError as error:
        print(f"错误：{error}", file=os.sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
