#!/usr/bin/env python3
"""Build and verify release-bound third-party rights and SBOM evidence."""

from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import subprocess
import sys
import tarfile
import tempfile
from typing import Any

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from build_provenance import validate_role


FORMAT = "copper-third-party-rights/v1"
DISCOVERY_FORMAT = "copper-rights-discovery/v1"
POLICY_FORMAT = "copper-third-party-rights-policy/v1"
LEDGER_FORMAT = "copper-third-party-rights-ledger/v1"
INVENTORY_FORMAT = "copper-content-inventory/v1"
EXCEPTION_FORMAT = "copper-rights-exceptions/v1"
SBOM_ROLES = (
    "source-web", "source-backend", "source-deploy",
    "image-web", "image-backend", "image-mysql",
)
SOURCE_ROLES = ("source-web", "source-backend", "source-deploy")
COMMIT_FIELDS = {
    "source-web": "COPPER_WEB_SOURCE_COMMIT",
    "source-backend": "COPPER_BACKEND_SOURCE_COMMIT",
    "source-deploy": "COPPER_DEPLOY_COMMIT",
}
IMAGE_FIELDS = {
    "image-web": "COPPER_WEB_IMAGE",
    "image-backend": "COPPER_BACKEND_IMAGE",
    "image-mysql": "COPPER_MYSQL_IMAGE",
}
HASH_RE = re.compile(r"[0-9a-f]{64}")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")
SAFE_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:+@/-]{0,255}")
SAFE_FILENAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,191}")
UNKNOWN_LICENSES = {"", "UNKNOWN", "NOASSERTION", "NONE", "UNLICENSED"}
RIGHTS_STATUSES = {"ENGINEERING_APPROVED", "REVIEW_REQUIRED", "BLOCKED"}
EXCEPTION_DISPOSITIONS = {"WRITTEN_AUTHORIZATION_CONFIRMED", "COPPER_OWNED_CONFIRMED"}


class RightsEvidenceError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise RightsEvidenceError(message)


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


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


def secure_directory(path: pathlib.Path, label: str) -> pathlib.Path:
    if path.is_symlink() or not path.is_dir():
        fail(f"{label} 必须是目录且不能是符号链接：{path}")
    return path.resolve()


def load_json(path: pathlib.Path, label: str) -> Any:
    resolved = secure_file(path, label)
    try:
        return json.loads(resolved.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        fail(f"{label} 不是有效 UTF-8 JSON：{error}")


def parse_env(path: pathlib.Path, label: str) -> dict[str, str]:
    resolved = secure_file(path, label)
    values: dict[str, str] = {}
    for number, raw in enumerate(resolved.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"{label} 第 {number} 行不是 KEY=VALUE")
        key, value = line.split("=", 1)
        if key in values:
            fail(f"{label} 存在重复字段：{key}")
        values[key] = value
    return values


def parse_time(value: object, label: str) -> dt.datetime:
    if not isinstance(value, str):
        fail(f"{label} 必须是 ISO-8601 时间")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        fail(f"{label} 不是有效 ISO-8601 时间")
    if parsed.tzinfo is None:
        fail(f"{label} 必须包含时区")
    return parsed.astimezone(dt.timezone.utc)


def iso(value: dt.datetime) -> str:
    return value.astimezone(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def exact_keys(document: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(document, dict) or set(document) != expected:
        fail(f"{label} 字段必须精确为：{','.join(sorted(expected))}")
    return document


def write_json(path: pathlib.Path, document: Any, mode: int = 0o444) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as writer:
            json.dump(document, writer, ensure_ascii=False, indent=2, sort_keys=True)
            writer.write("\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def write_text(path: pathlib.Path, content: str, mode: int = 0o444) -> None:
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


def load_security_module() -> Any:
    path = pathlib.Path(__file__).resolve().parent / "security-evidence.py"
    spec = importlib.util.spec_from_file_location("copper_security_evidence", path)
    if spec is None or spec.loader is None:
        fail("无法加载 M4 安全证据验证器")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_policy(path: pathlib.Path) -> dict[str, Any]:
    document = exact_keys(
        load_json(path, "第三方权利策略"),
        {
            "format", "version", "owner", "legalReviewIssue", "maxExceptionDays",
            "requiredSbomRoles", "contentPathPrefixes", "standaloneAssetExtensions",
            "contentExtensions", "buildInputFiles",
        },
        "第三方权利策略",
    )
    if document["format"] != POLICY_FORMAT or not isinstance(document["version"], int):
        fail(f"第三方权利策略必须使用 {POLICY_FORMAT} 且 version 为整数")
    if not isinstance(document["owner"], str) or not document["owner"].strip():
        fail("第三方权利策略 owner 不能为空")
    if not isinstance(document["legalReviewIssue"], str) or "copper_web/issues/60" not in document["legalReviewIssue"]:
        fail("第三方权利策略必须绑定 M6-05 法律复核 Issue")
    if not isinstance(document["maxExceptionDays"], int) or not 1 <= document["maxExceptionDays"] <= 90:
        fail("maxExceptionDays 必须在 1 至 90 之间")
    if document["requiredSbomRoles"] != list(SBOM_ROLES):
        fail("requiredSbomRoles 必须按合同顺序精确包含六个 M4 SBOM 角色")
    for field in ("contentPathPrefixes", "standaloneAssetExtensions", "contentExtensions", "buildInputFiles"):
        values = document[field]
        if not isinstance(values, list) or not values or any(not isinstance(item, str) or not item for item in values):
            fail(f"第三方权利策略 {field} 必须是非空字符串数组")
        if len(values) != len(set(values)):
            fail(f"第三方权利策略 {field} 不能重复")
    return document


def validate_manifest(path: pathlib.Path) -> dict[str, str]:
    values = parse_env(path, "发布清单")
    required = {
        "COPPER_RELEASE_VERSION", "COPPER_RELEASED_AT", "COPPER_DEPLOY_COMMIT",
        "COPPER_WEB_SOURCE_COMMIT", "COPPER_BACKEND_SOURCE_COMMIT",
        "COPPER_WEB_IMAGE", "COPPER_BACKEND_IMAGE", "COPPER_MYSQL_IMAGE",
    }
    missing = sorted(required - set(values))
    if missing:
        fail(f"发布清单缺少 M6-03 绑定字段：{','.join(missing)}")
    for field in ("COPPER_DEPLOY_COMMIT", "COPPER_WEB_SOURCE_COMMIT", "COPPER_BACKEND_SOURCE_COMMIT"):
        if not COMMIT_RE.fullmatch(values[field]):
            fail(f"发布清单 {field} 必须是 40 位 Git 提交")
    for field in ("COPPER_WEB_IMAGE", "COPPER_BACKEND_IMAGE", "COPPER_MYSQL_IMAGE"):
        if not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", values[field]):
            fail(f"发布清单 {field} 必须是不可变镜像摘要")
    parse_time(values["COPPER_RELEASED_AT"], "COPPER_RELEASED_AT")
    return values


def local_build_provenances(
    manifest_path: pathlib.Path, manifest: dict[str, str]
) -> tuple[list[pathlib.Path], set[str]]:
    """Include digest-bound local factory tools without inventing Actions runs."""
    paths: list[pathlib.Path] = []
    references: set[str] = set()
    for role in ("WEB", "BACKEND"):
        prefix = f"COPPER_{role}_BUILD_PROVENANCE_"
        if not manifest.get(prefix + "FILE") and not manifest.get(prefix + "SHA256"):
            continue  # Historical Actions releases retain their existing contract.
        try:
            validate_role(manifest_path, manifest, role)
        except (ValueError, OSError) as error:
            fail(f"本地构建来源验证失败：{error}")
        path = manifest_path.parent / manifest[prefix + "FILE"]
        document = load_json(path, "本地构建来源")
        for key in ("buildkitImage", "registryImage"):
            if key not in document["tools"]:
                continue  # Other honest local build methods need neither image.
            reference = document["tools"][key]
            if not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", reference):
                fail(f"本地构建工具 {key} 必须使用不可变镜像摘要")
            references.add(reference)
        paths.append(path)
    return paths, references


def git(repo: pathlib.Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo), *args], check=False, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True,
    )
    if result.returncode:
        fail(f"Git 命令失败（{repo}）：{result.stderr.strip()}")
    return result.stdout


def asset_category(path: pathlib.PurePosixPath) -> str:
    suffix = path.suffix.lower()
    if suffix in {".woff", ".woff2", ".ttf", ".otf", ".eot"}:
        return "font"
    if suffix in {".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp", ".avif"}:
        return "image-or-icon"
    if suffix in {".mp3", ".wav", ".ogg", ".mp4", ".webm"}:
        return "audio-or-video"
    if suffix in {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"}:
        return "document"
    return "text-or-structured-content"


def extract_build_references(path: pathlib.PurePosixPath, content: str) -> list[str]:
    references: set[str] = set()
    if path.name.startswith("Dockerfile"):
        for raw in content.splitlines():
            line = raw.strip()
            syntax = re.match(r"#\s*syntax=([^\s]+)", line, flags=re.IGNORECASE)
            if syntax:
                references.add(syntax.group(1))
            image = re.match(r"FROM\s+(?:--platform=[^\s]+\s+)?([^\s]+)", line, flags=re.IGNORECASE)
            if image:
                references.add(image.group(1))
            package_manager = re.search(r"corepack\s+prepare\s+([^\s]+)", line)
            if package_manager:
                references.add(package_manager.group(1))
    elif path.name == "package.json":
        try:
            package = json.loads(content)
        except json.JSONDecodeError:
            fail(f"构建输入不是有效 package.json：{path}")
        manager = package.get("packageManager") if isinstance(package, dict) else None
        if isinstance(manager, str) and manager:
            references.add(manager)
    elif path.name.endswith(".env"):
        for raw in content.splitlines():
            if raw.startswith(("COPPER_SYFT_IMAGE=", "COPPER_GRYPE_IMAGE=", "COPPER_GITLEAKS_IMAGE=")):
                references.add(raw.split("=", 1)[1])
    elif path.name.endswith((".yaml", ".yml")):
        for raw in content.splitlines():
            image = re.match(r"\s*image:\s*([^\s#]+)", raw)
            if image:
                reference = image.group(1)
                variable_default = re.fullmatch(r"\$\{[A-Za-z_][A-Za-z0-9_]*:-([^}]+)}", reference)
                if variable_default:
                    references.add(variable_default.group(1))
                elif not reference.startswith("${"):
                    references.add(reference)
            action = re.match(r"\s*(?:-\s*)?uses:\s*([^\s#]+)", raw)
            if action:
                references.add(action.group(1))
    return sorted(references)


def inventory_repository(
    role: str, repo: pathlib.Path, expected_commit: str, policy: dict[str, Any]
) -> dict[str, Any]:
    resolved = secure_directory(repo, role)
    if git(resolved, "rev-parse", "--is-inside-work-tree").strip() != "true":
        fail(f"{role} 不是 Git 仓库")
    if git(resolved, "rev-parse", "HEAD").strip() != expected_commit:
        fail(f"{role} HEAD 未绑定发布清单提交")
    if git(resolved, "status", "--porcelain"):
        fail(f"{role} 工作区不干净，禁止生成内容资产清单")
    tracked = git(resolved, "ls-files", "-z").split("\0")
    prefixes = tuple(policy["contentPathPrefixes"])
    standalone = set(policy["standaloneAssetExtensions"])
    content_extensions = set(policy["contentExtensions"])
    build_names = set(policy["buildInputFiles"])
    assets: list[dict[str, Any]] = []
    build_inputs: list[dict[str, Any]] = []
    for name in sorted(item for item in tracked if item):
        pure = pathlib.PurePosixPath(name)
        if pure.is_absolute() or ".." in pure.parts:
            fail(f"{role} 包含不安全跟踪路径：{name}")
        path = resolved / pure
        if path.is_symlink() or not path.is_file():
            fail(f"{role} 跟踪资产必须是普通文件且不能是符号链接：{name}")
        suffix = pure.suffix.lower()
        under_content_root = any(name.startswith(prefix) for prefix in prefixes)
        if suffix in standalone or (under_content_root and suffix in content_extensions):
            assets.append({
                "path": name,
                "category": asset_category(pure),
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            })
        is_build_input = (
            pure.name in build_names
            or pure.name.startswith("Dockerfile")
            or (name.startswith(".github/workflows/") and suffix in {".yml", ".yaml"})
        )
        if is_build_input:
            try:
                content = path.read_text(encoding="utf-8")
            except UnicodeError:
                fail(f"{role} 构建输入必须是 UTF-8：{name}")
            build_inputs.append({
                "path": name,
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
                "references": extract_build_references(pure, content),
            })
    return {
        "role": role,
        "commit": expected_commit,
        "assetCount": len(assets),
        "assets": assets,
        "buildInputs": build_inputs,
    }


def inventory(args: argparse.Namespace) -> None:
    manifest = validate_manifest(pathlib.Path(args.manifest))
    policy = validate_policy(pathlib.Path(args.policy))
    repositories = []
    for role, raw_path in (
        ("source-web", args.web_repo),
        ("source-backend", args.backend_repo),
        ("source-deploy", args.deploy_repo),
    ):
        repositories.append(inventory_repository(
            role, pathlib.Path(raw_path), manifest[COMMIT_FIELDS[role]], policy
        ))
    output = pathlib.Path(args.output).resolve()
    if output.exists() or output.is_symlink():
        fail("内容资产清单输出已存在，拒绝覆盖")
    document = {
        "format": INVENTORY_FORMAT,
        "releaseVersion": manifest["COPPER_RELEASE_VERSION"],
        "repositories": repositories,
    }
    write_json(output, document)
    print(json.dumps({
        "format": INVENTORY_FORMAT,
        "assetCount": sum(item["assetCount"] for item in repositories),
        "output": str(output),
        "sha256": sha256_file(output),
    }, ensure_ascii=False))


def validate_inventory(document: Any, manifest: dict[str, str]) -> dict[str, Any]:
    exact_keys(document, {"format", "releaseVersion", "repositories"}, "内容资产清单")
    if document["format"] != INVENTORY_FORMAT or document["releaseVersion"] != manifest["COPPER_RELEASE_VERSION"]:
        fail("内容资产清单格式或发布版本不正确")
    repositories = document["repositories"]
    if not isinstance(repositories, list) or len(repositories) != 3:
        fail("内容资产清单必须精确包含三仓")
    roles: set[str] = set()
    for repository in repositories:
        exact_keys(repository, {"role", "commit", "assetCount", "assets", "buildInputs"}, "仓库资产清单")
        role = repository["role"]
        if role not in SOURCE_ROLES or role in roles or repository["commit"] != manifest[COMMIT_FIELDS[role]]:
            fail("内容资产清单仓库角色重复或提交未绑定发布清单")
        roles.add(role)
        assets = repository["assets"]
        if not isinstance(assets, list) or repository["assetCount"] != len(assets):
            fail(f"{role} assetCount 与 assets 不一致")
        seen_paths: set[str] = set()
        for asset in assets:
            exact_keys(asset, {"path", "category", "bytes", "sha256"}, f"{role} 资产")
            path = asset["path"]
            pure = pathlib.PurePosixPath(str(path))
            if not isinstance(path, str) or pure.is_absolute() or ".." in pure.parts or path in seen_paths:
                fail(f"{role} 资产路径不安全或重复：{path}")
            if not isinstance(asset["bytes"], int) or asset["bytes"] < 0 or not HASH_RE.fullmatch(str(asset["sha256"])):
                fail(f"{role} 资产大小或摘要不正确：{path}")
            seen_paths.add(path)
        if not isinstance(repository["buildInputs"], list):
            fail(f"{role} buildInputs 必须是数组")
        for item in repository["buildInputs"]:
            exact_keys(item, {"path", "bytes", "sha256", "references"}, f"{role} 构建输入")
            if not isinstance(item["references"], list) or any(not isinstance(value, str) for value in item["references"]):
                fail(f"{role} 构建输入 references 必须是字符串数组")
            if len(item["references"]) != len(set(item["references"])):
                fail(f"{role} 构建输入 references 不能重复")
            if not HASH_RE.fullmatch(str(item["sha256"])) or not isinstance(item["bytes"], int):
                fail(f"{role} 构建输入摘要或大小不正确")
    if roles != set(SOURCE_ROLES):
        fail("内容资产清单三仓角色不完整")
    return document


def security_bundle(path: pathlib.Path, manifest: dict[str, str]) -> tuple[dict[str, Any], dict[str, bytes]]:
    security = load_security_module()
    try:
        evidence = security.verify_archive(path)
    except Exception as error:  # SecurityEvidenceError belongs to dynamically loaded module.
        fail(f"M4 安全证据包校验失败：{error}")
    release = evidence["release"]
    expected = {
        "version": manifest["COPPER_RELEASE_VERSION"],
        "deployCommit": manifest["COPPER_DEPLOY_COMMIT"],
        "webSourceCommit": manifest["COPPER_WEB_SOURCE_COMMIT"],
        "backendSourceCommit": manifest["COPPER_BACKEND_SOURCE_COMMIT"],
        "webImage": manifest["COPPER_WEB_IMAGE"],
        "backendImage": manifest["COPPER_BACKEND_IMAGE"],
        "mysqlImage": manifest["COPPER_MYSQL_IMAGE"],
    }
    if any(release.get(key) != value for key, value in expected.items()):
        fail("M4 安全证据包未精确绑定当前发布清单")
    sboms: dict[str, bytes] = {}
    with tarfile.open(secure_file(path, "M4 安全证据包"), "r:gz") as archive:
        members = {pathlib.PurePosixPath(item.name).name: item for item in archive.getmembers()}
        for record in evidence["sboms"]:
            member = members.get(record["file"])
            if member is None:
                fail(f"M4 安全证据缺少 SBOM：{record['role']}")
            content = archive.extractfile(member).read()
            if sha256_bytes(content) != record["sha256"]:
                fail(f"M4 安全证据 SBOM 摘要不一致：{record['role']}")
            sboms[record["role"]] = content
    if set(sboms) != set(SBOM_ROLES):
        fail("M4 安全证据必须包含六个 SBOM 角色")
    return evidence, sboms


def component_key(component: Any, role: str, index: int) -> str:
    if not isinstance(component, dict):
        fail(f"{role} SBOM components[{index}] 不是对象")
    purl = component.get("purl")
    if isinstance(purl, str) and purl.strip():
        return purl.strip()
    kind = component.get("type")
    name = component.get("name")
    version = component.get("version")
    if not all(isinstance(item, str) and item.strip() for item in (kind, name, version)):
        fail(f"{role} SBOM components[{index}] 缺少 purl 或 type/name/version；权利未知时失败关闭")
    return f"{kind}:{name}@{version}"


def sbom_components(sboms: dict[str, bytes]) -> tuple[dict[str, set[str]], dict[str, dict[str, Any]]]:
    by_role: dict[str, set[str]] = {}
    details: dict[str, dict[str, Any]] = {}
    for role in SBOM_ROLES:
        try:
            document = json.loads(sboms[role])
        except (UnicodeError, json.JSONDecodeError) as error:
            fail(f"{role} SBOM 不是有效 JSON：{error}")
        if not isinstance(document, dict) or document.get("bomFormat") != "CycloneDX" \
                or document.get("specVersion") != "1.6" or not isinstance(document.get("components"), list):
            fail(f"{role} 必须是 CycloneDX 1.6 SBOM")
        keys: set[str] = set()
        for index, component in enumerate(document["components"]):
            # Syft emits one CycloneDX `file` component for every file it sees in
            # a container layer. Those records are not independently
            # redistributable packages: package/application components own their
            # provenance, while Copper content assets are inventoried separately.
            # Requiring ledger rows for `/etc/*` or `/bin/*` would create false,
            # unresolvable third-party-component findings.
            if isinstance(component, dict) and component.get("type") == "file":
                continue
            key = component_key(component, role, index)
            keys.add(key)
            licenses: list[str] = []
            for item in component.get("licenses", []) if isinstance(component.get("licenses"), list) else []:
                if isinstance(item, dict) and isinstance(item.get("expression"), str):
                    licenses.append(item["expression"])
                elif isinstance(item, dict) and isinstance(item.get("license"), dict):
                    value = item["license"].get("id") or item["license"].get("name")
                    if isinstance(value, str):
                        licenses.append(value)
            current = details.setdefault(key, {
                "componentKey": key,
                "name": component.get("name"),
                "version": component.get("version"),
                "purl": component.get("purl"),
                "types": set(),
                "sbomLicenses": set(),
                "roles": set(),
            })
            component_type = component.get("type")
            if isinstance(component_type, str) and component_type:
                current["types"].add(component_type)
            current["roles"].add(role)
            current["sbomLicenses"].update(licenses)
        by_role[role] = keys
    return by_role, details


def discovery_document(
    manifest: dict[str, str], security_evidence: dict[str, Any], sboms: dict[str, bytes], security_sha256: str,
) -> dict[str, Any]:
    """Create an assignable, fail-closed worklist from a verified M4 bundle.

    This is deliberately not a rights ledger and does not infer a licence,
    rights holder, source, or redistribution conclusion.  It only preserves
    what the exact SBOM did (or did not) assert so each real component can be
    researched without turning missing metadata into an approval.
    """
    _, details = sbom_components(sboms)
    components: list[dict[str, Any]] = []
    metadata_missing = 0
    for key in sorted(details):
        detail = details[key]
        licenses = sorted({
            value for value in detail["sbomLicenses"]
            if isinstance(value, str) and value.upper() not in UNKNOWN_LICENSES
        })
        status = (
            "SBOM_LICENSE_METADATA_MISSING"
            if not licenses else "SOURCE_HOLDER_LICENSE_TEXT_REVIEW_REQUIRED"
        )
        if status == "SBOM_LICENSE_METADATA_MISSING":
            metadata_missing += 1
        components.append({
            "componentKey": key,
            "name": detail["name"],
            "version": detail["version"],
            "purl": detail["purl"],
            "types": sorted(detail["types"]),
            "roles": sorted(detail["roles"]),
            "sbomLicenses": licenses,
            "reviewStatus": status,
        })
    return {
        "format": DISCOVERY_FORMAT,
        "status": "REVIEW_REQUIRED",
        "release": {
            "version": manifest["COPPER_RELEASE_VERSION"],
            "deployCommit": manifest["COPPER_DEPLOY_COMMIT"],
            "webSourceCommit": manifest["COPPER_WEB_SOURCE_COMMIT"],
            "backendSourceCommit": manifest["COPPER_BACKEND_SOURCE_COMMIT"],
            "images": {role: manifest[field] for role, field in IMAGE_FIELDS.items()},
            "manifestSha256": None,
        },
        "securityEvidence": {
            "format": security_evidence["format"],
            "status": security_evidence["status"],
            "bundleSha256": security_sha256,
        },
        "counts": {
            "components": len(components),
            "sbomLicenseMetadataMissing": metadata_missing,
        },
        "components": components,
        "gate": {
            "status": "REVIEW_REQUIRED",
            "rightsLedgerGenerated": False,
            "legalReviewRequired": True,
        },
    }


def discover(args: argparse.Namespace) -> None:
    manifest_path = secure_file(pathlib.Path(args.manifest), "发布清单")
    manifest = validate_manifest(manifest_path)
    security_path = secure_file(pathlib.Path(args.security_bundle), "M4 安全证据包")
    security_evidence, sboms = security_bundle(security_path, manifest)
    document = discovery_document(manifest, security_evidence, sboms, sha256_file(security_path))
    document["release"]["manifestSha256"] = sha256_file(manifest_path)
    output = pathlib.Path(args.output).resolve()
    if output.exists() or output.is_symlink():
        fail("权利发现清单输出已存在，拒绝覆盖")
    write_json(output, document)
    print(json.dumps({
        "format": DISCOVERY_FORMAT,
        "status": document["status"],
        "components": document["counts"]["components"],
        "sbomLicenseMetadataMissing": document["counts"]["sbomLicenseMetadataMissing"],
        "output": str(output),
        "sha256": sha256_file(output),
    }, ensure_ascii=False))


def validate_review(review: Any, label: str) -> dict[str, Any]:
    exact_keys(review, {"owner", "reviewedAt", "status"}, label)
    if not isinstance(review["owner"], str) or not review["owner"].strip():
        fail(f"{label} owner 不能为空")
    parse_time(review["reviewedAt"], f"{label}.reviewedAt")
    if review["status"] != "ENGINEERING_REVIEWED":
        fail(f"{label} status 必须是 ENGINEERING_REVIEWED")
    return review


RIGHT_ENTRY_KEYS = {
    "id", "source", "rightsHolders", "licenseExpression", "licenseTextFile", "usage",
    "redistributionStatus", "attribution", "notice", "evidenceSummary", "reviewedBy", "reviewedAt",
}


def validate_common_rights(item: Any, extra: set[str], label: str) -> dict[str, Any]:
    exact_keys(item, RIGHT_ENTRY_KEYS | extra, label)
    identifier = item["id"]
    if not isinstance(identifier, str) or not SAFE_ID_RE.fullmatch(identifier):
        fail(f"{label}.id 不安全")
    for field in ("source", "usage", "evidenceSummary", "reviewedBy"):
        if not isinstance(item[field], str) or not item[field].strip():
            fail(f"{label}.{field} 不能为空")
    holders = item["rightsHolders"]
    if not isinstance(holders, list) or not holders or any(not isinstance(value, str) or not value.strip() for value in holders):
        fail(f"{label}.rightsHolders 必须是非空字符串数组")
    expression = item["licenseExpression"]
    if not isinstance(expression, str) or expression.upper() in UNKNOWN_LICENSES:
        fail(f"{label}.licenseExpression 权利未知，失败关闭")
    license_file = item["licenseTextFile"]
    if not isinstance(license_file, str) or not SAFE_FILENAME_RE.fullmatch(license_file):
        fail(f"{label}.licenseTextFile 必须是 licenses 目录中的安全文件名")
    status = item["redistributionStatus"]
    if status not in RIGHTS_STATUSES:
        fail(f"{label}.redistributionStatus 不受支持")
    for field in ("attribution", "notice"):
        if not isinstance(item[field], str) or not item[field].strip():
            fail(f"{label}.{field} 必须明确填写要求或明确写明无额外要求")
    if not isinstance(item["reviewedAt"], str):
        fail(f"{label}.reviewedAt 必须是时间")
    parse_time(item["reviewedAt"], f"{label}.reviewedAt")
    return item


def validate_ledger(document: Any, version: str, legal_issue: str) -> dict[str, Any]:
    exact_keys(
        document,
        {"format", "releaseVersion", "engineeringReview", "legalReview", "componentRights", "assetRights", "toolRights"},
        "第三方权利台账",
    )
    if document["format"] != LEDGER_FORMAT or document["releaseVersion"] != version:
        fail("第三方权利台账格式或发布版本不正确")
    validate_review(document["engineeringReview"], "engineeringReview")
    legal = exact_keys(document["legalReview"], {"status", "issue"}, "legalReview")
    if legal["status"] != "REQUIRED" or legal["issue"] != legal_issue:
        fail("第三方权利台账必须明确保留 M6-05 法律复核，不得声明法律结论")
    component_ids: set[str] = set()
    for item in document["componentRights"]:
        validate_common_rights(item, {"componentKey", "roles", "relationship"}, "componentRights")
        if item["id"] in component_ids:
            fail(f"componentRights id 重复：{item['id']}")
        component_ids.add(item["id"])
        if not isinstance(item["componentKey"], str) or not item["componentKey"].strip():
            fail("componentRights.componentKey 不能为空")
        roles = item["roles"]
        if not isinstance(roles, list) or not roles or any(role not in SBOM_ROLES for role in roles):
            fail("componentRights.roles 必须是非空 SBOM 角色数组")
        if len(roles) != len(set(roles)) or item["relationship"] not in {"direct", "transitive", "bundled"}:
            fail("componentRights.roles 重复或 relationship 不受支持")
    asset_ids: set[str] = set()
    for item in document["assetRights"]:
        validate_common_rights(item, {"repositoryRole", "pathPrefix"}, "assetRights")
        if item["id"] in asset_ids:
            fail(f"assetRights id 重复：{item['id']}")
        asset_ids.add(item["id"])
        if item["repositoryRole"] not in SOURCE_ROLES:
            fail("assetRights.repositoryRole 不受支持")
        prefix = item["pathPrefix"]
        pure = pathlib.PurePosixPath(str(prefix))
        if not isinstance(prefix, str) or not prefix or pure.is_absolute() or ".." in pure.parts:
            fail("assetRights.pathPrefix 不安全")
    tool_ids: set[str] = set()
    for item in document["toolRights"]:
        validate_common_rights(item, {"reference", "category"}, "toolRights")
        if item["id"] in tool_ids:
            fail(f"toolRights id 重复：{item['id']}")
        tool_ids.add(item["id"])
        if not isinstance(item["reference"], str) or not item["reference"].strip():
            fail("toolRights.reference 不能为空")
        if item["category"] not in {
            "base-image", "build-image", "runtime-image", "build-tool", "scanner", "delivery-tool",
        }:
            fail("toolRights.category 不受支持")
    return document


def load_exceptions(
    path: pathlib.Path | None, version: str, generated_at: dt.datetime, max_days: int
) -> tuple[dict[tuple[str, str], dict[str, Any]], list[pathlib.Path], dict[str, Any] | None]:
    if path is None:
        return {}, [], None
    resolved = secure_file(path, "权利例外")
    document = exact_keys(load_json(resolved, "权利例外"), {"format", "releaseVersion", "exceptions"}, "权利例外")
    if document["format"] != EXCEPTION_FORMAT or document["releaseVersion"] != version:
        fail("权利例外格式或发布版本不正确")
    loaded: dict[tuple[str, str], dict[str, Any]] = {}
    evidence_files: list[pathlib.Path] = []
    for item in document["exceptions"]:
        exact_keys(item, {
            "subjectType", "subjectId", "disposition", "owner", "reason", "expiresAt",
            "evidenceSummary", "evidenceFile", "evidenceSha256",
        }, "权利例外项")
        if item["subjectType"] not in {"component", "asset", "tool"} or item["disposition"] not in EXCEPTION_DISPOSITIONS:
            fail("权利例外 subjectType 或 disposition 不受支持")
        if not isinstance(item["subjectId"], str) or not SAFE_ID_RE.fullmatch(item["subjectId"]):
            fail("权利例外 subjectId 不安全")
        key = (item["subjectType"], item["subjectId"])
        if key in loaded:
            fail(f"权利例外重复：{key[0]} {key[1]}")
        for field in ("owner", "reason", "evidenceSummary"):
            if not isinstance(item[field], str) or not item[field].strip():
                fail(f"权利例外 {field} 不能为空")
        expires = parse_time(item["expiresAt"], "权利例外 expiresAt")
        if expires <= generated_at or expires > generated_at + dt.timedelta(days=max_days):
            fail(f"权利例外必须在未来且不超过 {max_days} 天")
        filename = item["evidenceFile"]
        if not isinstance(filename, str) or not SAFE_FILENAME_RE.fullmatch(filename):
            fail("权利例外 evidenceFile 必须是同目录安全文件名")
        evidence = secure_file(resolved.parent / filename, "权利例外脱敏证据")
        if not HASH_RE.fullmatch(str(item["evidenceSha256"])) or sha256_file(evidence) != item["evidenceSha256"]:
            fail("权利例外脱敏证据摘要不一致")
        loaded[key] = item
        evidence_files.append(evidence)
    return loaded, evidence_files, document


def selected_exception(
    entry: dict[str, Any], subject_type: str, exceptions: dict[tuple[str, str], dict[str, Any]]
) -> dict[str, Any] | None:
    exception = exceptions.get((subject_type, entry["id"]))
    if entry["redistributionStatus"] == "BLOCKED":
        fail(f"{subject_type} {entry['id']} 明确 BLOCKED；例外不得制造再发布权")
    if entry["redistributionStatus"] == "REVIEW_REQUIRED" and exception is None:
        fail(f"{subject_type} {entry['id']} 权利待复核且无合格例外")
    if entry["redistributionStatus"] == "ENGINEERING_APPROVED" and exception is not None:
        fail(f"{subject_type} {entry['id']} 已通过工程复核，不应附加例外")
    return exception


def match_rights(
    component_roles: dict[str, set[str]], inventory_doc: dict[str, Any], ledger: dict[str, Any],
    exceptions: dict[tuple[str, str], dict[str, Any]], additional_references: set[str],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    component_entries: list[dict[str, Any]] = []
    by_key: dict[str, list[dict[str, Any]]] = {}
    for item in ledger["componentRights"]:
        by_key.setdefault(item["componentKey"], []).append(item)
    for key, roles in sorted(component_roles.items()):
        matches = [item for item in by_key.get(key, []) if roles.issubset(set(item["roles"]))]
        if len(matches) != 1:
            fail(f"SBOM 组件 {key} 缺少唯一权利台账覆盖；角色={','.join(sorted(roles))}")
        item = matches[0]
        exception = selected_exception(item, "component", exceptions)
        component_entries.append({**item, "coveredRoles": sorted(roles), "exception": exception})
    used_component_ids = {item["id"] for item in component_entries}
    all_component_ids = {item["id"] for item in ledger["componentRights"]}
    if used_component_ids != all_component_ids:
        fail(f"componentRights 存在未被本次 SBOM 使用的记录：{sorted(all_component_ids - used_component_ids)}")

    asset_entries: list[dict[str, Any]] = []
    for repository in inventory_doc["repositories"]:
        role = repository["role"]
        for asset in repository["assets"]:
            matches = [
                item for item in ledger["assetRights"]
                if item["repositoryRole"] == role and asset["path"].startswith(item["pathPrefix"])
            ]
            if len(matches) != 1:
                fail(f"内容资产 {role}:{asset['path']} 缺少唯一权利台账覆盖")
            item = matches[0]
            exception = selected_exception(item, "asset", exceptions)
            asset_entries.append({
                "repositoryRole": role,
                **asset,
                "rightsId": item["id"],
                "licenseExpression": item["licenseExpression"],
                "licenseTextFile": item["licenseTextFile"],
                "redistributionStatus": item["redistributionStatus"],
                "exception": exception,
            })
    used_asset_ids = {item["rightsId"] for item in asset_entries}
    all_asset_ids = {item["id"] for item in ledger["assetRights"]}
    if used_asset_ids != all_asset_ids:
        fail(f"assetRights 存在未被本次内容清单使用的记录：{sorted(all_asset_ids - used_asset_ids)}")

    references: set[str] = set(additional_references)
    for repository in inventory_doc["repositories"]:
        for build_input in repository["buildInputs"]:
            references.update(build_input["references"])
    tool_entries: list[dict[str, Any]] = []
    by_reference: dict[str, list[dict[str, Any]]] = {}
    for item in ledger["toolRights"]:
        by_reference.setdefault(item["reference"], []).append(item)
    for reference in sorted(references):
        matches = by_reference.get(reference, [])
        if len(matches) != 1:
            fail(f"构建/基础镜像引用 {reference} 缺少唯一权利台账覆盖")
        item = matches[0]
        exception = selected_exception(item, "tool", exceptions)
        tool_entries.append({**item, "exception": exception})
    used_tool_ids = {item["id"] for item in tool_entries}
    all_tool_ids = {item["id"] for item in ledger["toolRights"]}
    if used_tool_ids != all_tool_ids:
        fail(f"toolRights 存在未被本次构建引用使用的记录：{sorted(all_tool_ids - used_tool_ids)}")
    return component_entries, asset_entries, tool_entries


def normalize_license(value: str) -> str:
    normalized = re.sub(r"[^A-Z0-9.+-]", "", value.upper())
    aliases = {
        "MITLICENSE": "MIT",
        "APACHELICENSE2.0": "APACHE-2.0",
        "APACHE2.0": "APACHE-2.0",
        "BSD3CLAUSE": "BSD-3-CLAUSE",
        "BSD2CLAUSE": "BSD-2-CLAUSE",
    }
    return aliases.get(normalized, normalized)


def assert_sbom_license_consistency(component: dict[str, Any], detail: dict[str, Any]) -> None:
    declared = {
        normalize_license(value)
        for value in detail["sbomLicenses"]
        if isinstance(value, str) and value.upper() not in UNKNOWN_LICENSES
    }
    expression = normalize_license(component["licenseExpression"])
    if declared and not any(value in expression or expression in value for value in declared):
        fail(
            f"组件 {component['componentKey']} 的 SBOM 许可证 {sorted(declared)} "
            f"与台账 {component['licenseExpression']} 冲突"
        )


def record(path: pathlib.Path, archive_path: str) -> dict[str, Any]:
    return {"path": archive_path, "bytes": path.stat().st_size, "sha256": sha256_file(path)}


def bytes_record(content: bytes, archive_path: str) -> dict[str, Any]:
    return {"path": archive_path, "bytes": len(content), "sha256": sha256_bytes(content)}


def add_tar_bytes(archive: tarfile.TarFile, content: bytes, name: str) -> None:
    info = tarfile.TarInfo(name)
    info.size = len(content)
    info.mode = 0o444
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = 0
    archive.addfile(info, fileobj=__import__("io").BytesIO(content))


def notice_markdown(
    version: str, components: list[dict[str, Any]], tools: list[dict[str, Any]], ledger: dict[str, Any]
) -> str:
    lines = [
        "# Copper 第三方组件与 NOTICE 输入",
        "",
        f"> 产品版本：`{version}`",
        "> 状态：M6-03 工程台账输入；不是 Copper 最终 LICENSE 或法律意见。",
        "> 所有许可证兼容、双授权、贡献和商标结论仍须 M6-05 知识产权律师复核。",
        "",
        "## 软件组件",
        "",
    ]
    for item in sorted(components, key=lambda value: value["componentKey"]):
        lines.extend([
            f"### {item['componentKey']}",
            "",
            f"- 使用角色：{', '.join(item['coveredRoles'])}",
            f"- 关系：{item['relationship']}",
            f"- 权利人：{'; '.join(item['rightsHolders'])}",
            f"- 许可证表达式：`{item['licenseExpression']}`",
            f"- 许可证文本：`licenses/{item['licenseTextFile']}`",
            f"- 来源：{item['source']}",
            f"- 使用方式：{item['usage']}",
            f"- 归属：{item['attribution'] or '无额外归属文本'}",
            f"- NOTICE：{item['notice'] or '无额外 NOTICE 文本'}",
            "",
        ])
    lines.extend(["## 基础镜像与构建工具", ""])
    for item in sorted(tools, key=lambda value: value["reference"]):
        lines.extend([
            f"### {item['reference']}",
            "",
            f"- 类别：{item['category']}",
            f"- 权利人：{'; '.join(item['rightsHolders'])}",
            f"- 许可证表达式：`{item['licenseExpression']}`",
            f"- 许可证文本：`licenses/{item['licenseTextFile']}`",
            f"- 来源：{item['source']}",
            f"- 归属：{item['attribution'] or '无额外归属文本'}",
            f"- NOTICE：{item['notice'] or '无额外 NOTICE 文本'}",
            "",
        ])
    lines.extend(["## 内容资产", ""])
    for item in sorted(
        ledger["assetRights"], key=lambda value: (value["repositoryRole"], value["pathPrefix"])
    ):
        lines.extend([
            f"### {item['repositoryRole']}:{item['pathPrefix']}",
            "",
            f"- 权利人：{'; '.join(item['rightsHolders'])}",
            f"- 许可证表达式：`{item['licenseExpression']}`",
            f"- 许可证文本：`licenses/{item['licenseTextFile']}`",
            f"- 来源：{item['source']}",
            f"- 使用方式：{item['usage']}",
            f"- 归属：{item['attribution']}",
            f"- NOTICE：{item['notice']}",
            "",
        ])
    lines.extend([
        "内容资产逐文件摘要和对应 rightsId 位于 `rights-evidence.json`。",
        "",
        "本文件不收录私密授权书正文，只收录可公开的证据摘要和经校验的脱敏证据摘要。",
        "",
        f"工程复核：{ledger['engineeringReview']['owner']} / {ledger['engineeringReview']['reviewedAt']}",
        f"法律复核门槛：{ledger['legalReview']['issue']}",
        "",
    ])
    return "\n".join(lines)


def validate_license_files(
    ledger: dict[str, Any], license_dir: pathlib.Path
) -> dict[str, pathlib.Path]:
    resolved = secure_directory(license_dir, "许可证文本目录")
    required = {
        item["licenseTextFile"]
        for section in ("componentRights", "assetRights", "toolRights")
        for item in ledger[section]
    }
    files: dict[str, pathlib.Path] = {}
    for filename in sorted(required):
        path = secure_file(resolved / filename, f"许可证文本 {filename}")
        if path.stat().st_size == 0:
            fail(f"许可证文本不能为空：{filename}")
        files[filename] = path
    return files


def build(args: argparse.Namespace) -> None:
    manifest_path = secure_file(pathlib.Path(args.manifest), "发布清单")
    manifest = validate_manifest(manifest_path)
    policy_path = secure_file(pathlib.Path(args.policy), "第三方权利策略")
    policy = validate_policy(policy_path)
    inventory_path = secure_file(pathlib.Path(args.inventory), "内容资产清单")
    inventory_doc = validate_inventory(load_json(inventory_path, "内容资产清单"), manifest)
    ledger_path = secure_file(pathlib.Path(args.ledger), "第三方权利台账")
    ledger = validate_ledger(
        load_json(ledger_path, "第三方权利台账"),
        manifest["COPPER_RELEASE_VERSION"], policy["legalReviewIssue"],
    )
    generated_at = parse_time(manifest["COPPER_RELEASED_AT"], "COPPER_RELEASED_AT")
    exception_path = secure_file(pathlib.Path(args.exceptions), "权利例外") if args.exceptions else None
    exceptions, exception_files, _ = load_exceptions(
        exception_path, manifest["COPPER_RELEASE_VERSION"], generated_at, policy["maxExceptionDays"]
    )
    security_path = secure_file(pathlib.Path(args.security_bundle), "M4 安全证据包")
    security_evidence, sboms = security_bundle(security_path, manifest)
    by_role, component_details = sbom_components(sboms)
    component_roles: dict[str, set[str]] = {}
    for role, keys in by_role.items():
        for key in keys:
            component_roles.setdefault(key, set()).add(role)
    additional_references = {
        manifest["COPPER_MYSQL_IMAGE"],
        *security_evidence["policy"]["toolImages"].values(),
    }
    provenance_paths, local_tools = local_build_provenances(manifest_path, manifest)
    additional_references.update(local_tools)
    acme_archive = manifest.get("COPPER_ACME_ARCHIVE")
    acme_hash = manifest.get("COPPER_ACME_ARCHIVE_SHA256")
    if acme_archive or acme_hash:
        if not isinstance(acme_archive, str) or not SAFE_FILENAME_RE.fullmatch(acme_archive) \
                or not isinstance(acme_hash, str) or not HASH_RE.fullmatch(acme_hash):
            fail("发布清单 ACME 归档名称或 SHA-256 不正确")
        additional_references.add(f"{acme_archive}@sha256:{acme_hash}")
    components, assets, tools = match_rights(
        component_roles, inventory_doc, ledger, exceptions, additional_references
    )
    used_exceptions = {
        (kind, item["id"])
        for kind, items in (("component", components), ("tool", tools))
        for item in items if item.get("exception")
    }
    used_exceptions.update(
        ("asset", item["rightsId"])
        for item in assets if item.get("exception")
    )
    if used_exceptions != set(exceptions):
        unused = sorted(set(exceptions) - used_exceptions)
        fail(f"权利例外存在未使用或无法匹配项：{unused}")
    license_files = validate_license_files(ledger, pathlib.Path(args.license_dir))

    output = pathlib.Path(args.output).resolve()
    if output.exists() or output.is_symlink():
        fail("第三方权利证据包输出已存在，拒绝覆盖")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".copper-rights-", dir=output.parent) as temporary_name:
        temporary = pathlib.Path(temporary_name)
        notice_path = temporary / "THIRD_PARTY_NOTICES.md"
        write_text(notice_path, notice_markdown(manifest["COPPER_RELEASE_VERSION"], components, tools, ledger))
        evidence_path = temporary / "rights-evidence.json"
        file_records = [
            record(manifest_path, f"release/{manifest_path.name}"),
            record(policy_path, f"policy/{policy_path.name}"),
            record(inventory_path, f"inventory/{inventory_path.name}"),
            record(ledger_path, f"ledger/{ledger_path.name}"),
            record(notice_path, "THIRD_PARTY_NOTICES.md"),
        ]
        for path in provenance_paths:
            file_records.append(record(path, f"release/{path.name}"))
        for role in SBOM_ROLES:
            file_records.append(bytes_record(sboms[role], f"sbom/sbom-{role}.cdx.json"))
        for filename, path in sorted(license_files.items()):
            file_records.append(record(path, f"licenses/{filename}"))
        if exception_path is not None:
            file_records.append(record(exception_path, f"exceptions/{exception_path.name}"))
            for path in exception_files:
                file_records.append(record(path, f"exceptions/{path.name}"))
        component_output = []
        for item in components:
            detail = component_details[item["componentKey"]]
            assert_sbom_license_consistency(item, detail)
            component_output.append({
                **item,
                "sbomLicenses": sorted(detail["sbomLicenses"]),
                "name": detail["name"],
                "version": detail["version"],
                "purl": detail["purl"],
            })
        document = {
            "format": FORMAT,
            "status": "ENGINEERING_PASS",
            "legalReview": {"status": "REQUIRED", "issue": policy["legalReviewIssue"]},
            "generatedAt": iso(generated_at),
            "release": {
                "version": manifest["COPPER_RELEASE_VERSION"],
                "deployCommit": manifest["COPPER_DEPLOY_COMMIT"],
                "webSourceCommit": manifest["COPPER_WEB_SOURCE_COMMIT"],
                "backendSourceCommit": manifest["COPPER_BACKEND_SOURCE_COMMIT"],
                "images": {role: manifest[field] for role, field in IMAGE_FIELDS.items()},
                "manifestSha256": sha256_file(manifest_path),
            },
            "securityEvidence": {
                "format": security_evidence["format"],
                "status": security_evidence["status"],
                "bundleSha256": sha256_file(security_path),
                "toolImages": security_evidence["policy"]["toolImages"],
            },
            "engineeringReview": ledger["engineeringReview"],
            "counts": {
                "sboms": len(sboms),
                "components": len(component_output),
                "assets": len(assets),
                "tools": len(tools),
                "exceptions": len(exceptions),
            },
            "components": component_output,
            "assets": assets,
            "tools": tools,
            "exceptions": list(exceptions.values()),
            "files": sorted(file_records, key=lambda item: item["path"]),
            "gate": {
                "status": "ENGINEERING_PASS",
                "unknownRights": 0,
                "licenseConflicts": 0,
                "missingAttributionOrAuthorization": 0,
                "legalReviewRequired": True,
            },
        }
        file_paths = [item["path"] for item in document["files"]]
        if len(file_paths) != len(set(file_paths)):
            fail("第三方权利证据内部文件路径冲突")
        write_json(evidence_path, document)
        prefix = f"copper-rights-{manifest['COPPER_RELEASE_VERSION']}"
        contents: dict[str, bytes] = {"rights-evidence.json": evidence_path.read_bytes()}
        for item in document["files"]:
            archive_path = item["path"]
            if archive_path.startswith("sbom/"):
                role = archive_path.removeprefix("sbom/sbom-").removesuffix(".cdx.json")
                contents[archive_path] = sboms[role]
            elif archive_path.startswith("licenses/"):
                contents[archive_path] = license_files[pathlib.PurePosixPath(archive_path).name].read_bytes()
            elif archive_path.startswith("exceptions/"):
                name = pathlib.PurePosixPath(archive_path).name
                path = exception_path if exception_path and exception_path.name == name else next(
                    value for value in exception_files if value.name == name
                )
                contents[archive_path] = path.read_bytes()
            elif archive_path.startswith("release/"):
                contents[archive_path] = (manifest_path.parent / pathlib.PurePosixPath(archive_path).name).read_bytes()
            elif archive_path.startswith("policy/"):
                contents[archive_path] = policy_path.read_bytes()
            elif archive_path.startswith("inventory/"):
                contents[archive_path] = inventory_path.read_bytes()
            elif archive_path.startswith("ledger/"):
                contents[archive_path] = ledger_path.read_bytes()
            elif archive_path == "THIRD_PARTY_NOTICES.md":
                contents[archive_path] = notice_path.read_bytes()
            else:
                fail(f"内部错误：未知证据文件路径 {archive_path}")
        descriptor, temporary_name = tempfile.mkstemp(prefix=f".{output.name}.", dir=output.parent)
        os.close(descriptor)
        temporary_output = pathlib.Path(temporary_name)
        temporary_output.unlink()
        try:
            with temporary_output.open("xb") as raw:
                with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                    with tarfile.open(fileobj=compressed, mode="w") as archive:
                        for name in sorted(contents):
                            add_tar_bytes(archive, contents[name], f"{prefix}/{name}")
            os.chmod(temporary_output, 0o444)
            verify_archive(temporary_output)
            os.replace(temporary_output, output)
        finally:
            temporary_output.unlink(missing_ok=True)
    print(json.dumps({
        "format": FORMAT,
        "status": "ENGINEERING_PASS",
        "legalReview": "REQUIRED",
        "output": str(output),
        "sha256": sha256_file(output),
    }, ensure_ascii=False))


def validate_record(item: Any, label: str) -> None:
    exact_keys(item, {"path", "bytes", "sha256"}, label)
    path = item["path"]
    pure = pathlib.PurePosixPath(str(path))
    if not isinstance(path, str) or pure.is_absolute() or ".." in pure.parts or str(pure) != path:
        fail(f"{label}.path 不安全")
    if not isinstance(item["bytes"], int) or item["bytes"] < 0 or not HASH_RE.fullmatch(str(item["sha256"])):
        fail(f"{label} 字节数或 SHA-256 不正确")


def validate_evidence(document: Any) -> dict[str, Any]:
    expected = {
        "format", "status", "legalReview", "generatedAt", "release", "securityEvidence",
        "engineeringReview", "counts", "components", "assets", "tools", "exceptions", "files", "gate",
    }
    exact_keys(document, expected, "第三方权利主证据")
    if document["format"] != FORMAT or document["status"] != "ENGINEERING_PASS":
        fail("第三方权利主证据格式或状态不正确")
    legal = document["legalReview"]
    if not isinstance(legal, dict) or set(legal) != {"status", "issue"} \
            or legal.get("status") != "REQUIRED" or "copper_web/issues/60" not in str(legal.get("issue")):
        fail("第三方权利主证据不得省略 M6-05 法律复核")
    release = document["release"]
    if not isinstance(release, dict) or set(release) != {
        "version", "deployCommit", "webSourceCommit", "backendSourceCommit", "images", "manifestSha256",
    } or not isinstance(release.get("version"), str) or not release["version"]:
        fail("第三方权利主证据 release 字段不完整或包含未知字段")
    for field in ("deployCommit", "webSourceCommit", "backendSourceCommit"):
        if not COMMIT_RE.fullmatch(str(release.get(field, ""))):
            fail(f"第三方权利主证据 release.{field} 不正确")
    if not HASH_RE.fullmatch(str(release.get("manifestSha256", ""))):
        fail("第三方权利主证据发布清单摘要不正确")
    images = release.get("images")
    if not isinstance(images, dict) or set(images) != set(IMAGE_FIELDS):
        fail("第三方权利主证据镜像角色不完整")
    if any(not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", str(value)) for value in images.values()):
        fail("第三方权利主证据镜像未使用不可变摘要")
    security = document["securityEvidence"]
    if not isinstance(security, dict) or set(security) != {"format", "status", "bundleSha256", "toolImages"} \
            or security.get("format") != "copper-security/v1" \
            or security.get("status") != "PASS" or not HASH_RE.fullmatch(str(security.get("bundleSha256", ""))):
        fail("第三方权利主证据未绑定有效 M4 安全证据")
    tool_images = security["toolImages"]
    if not isinstance(tool_images, dict) or set(tool_images) != {"syft", "grype", "gitleaks"} \
            or any(not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", str(value)) for value in tool_images.values()):
        fail("第三方权利主证据未绑定三种不可变 M4 扫描工具")
    validate_review(document["engineeringReview"], "engineeringReview")
    gate = document["gate"]
    if gate != {
        "status": "ENGINEERING_PASS", "unknownRights": 0, "licenseConflicts": 0,
        "missingAttributionOrAuthorization": 0, "legalReviewRequired": True,
    }:
        fail("第三方权利 gate 不满足失败关闭合同")
    counts = document["counts"]
    sections = {"components": "components", "assets": "assets", "tools": "tools", "exceptions": "exceptions"}
    if not isinstance(counts, dict) or counts.get("sboms") != 6:
        fail("第三方权利主证据 SBOM 数量必须是 6")
    for count_field, section in sections.items():
        if not isinstance(document[section], list) or counts.get(count_field) != len(document[section]):
            fail(f"第三方权利主证据 {count_field} 计数不一致")
    if set(counts) != {"sboms", *sections}:
        fail("第三方权利主证据 counts 字段不精确")

    license_files: set[str] = set()
    component_ids: set[str] = set()
    for item in document["components"]:
        output_keys = RIGHT_ENTRY_KEYS | {
            "componentKey", "roles", "relationship", "coveredRoles", "exception",
            "sbomLicenses", "name", "version", "purl",
        }
        exact_keys(item, output_keys, "第三方组件证据")
        validate_common_rights(
            {key: item[key] for key in RIGHT_ENTRY_KEYS | {"componentKey", "roles", "relationship"}},
            {"componentKey", "roles", "relationship"}, "第三方组件证据",
        )
        if item["id"] in component_ids or item["redistributionStatus"] == "BLOCKED":
            fail("第三方组件证据 id 重复或包含 BLOCKED")
        component_ids.add(item["id"])
        if item["redistributionStatus"] == "REVIEW_REQUIRED" and not isinstance(item["exception"], dict):
            fail("REVIEW_REQUIRED 第三方组件缺少例外")
        if not isinstance(item["coveredRoles"], list) or not set(item["coveredRoles"]).issubset(set(item["roles"])):
            fail("第三方组件 coveredRoles 未被台账 roles 覆盖")
        if not isinstance(item["sbomLicenses"], list):
            fail("第三方组件 sbomLicenses 必须是数组")
        license_files.add(item["licenseTextFile"])

    asset_paths: set[tuple[str, str]] = set()
    for item in document["assets"]:
        exact_keys(item, {
            "repositoryRole", "path", "category", "bytes", "sha256", "rightsId",
            "licenseExpression", "licenseTextFile", "redistributionStatus", "exception",
        }, "第三方内容资产证据")
        identity = (item["repositoryRole"], item["path"])
        if item["repositoryRole"] not in SOURCE_ROLES or identity in asset_paths \
                or not HASH_RE.fullmatch(str(item["sha256"])) or not isinstance(item["bytes"], int):
            fail("第三方内容资产角色、路径、大小或摘要不正确")
        asset_paths.add(identity)
        if item["redistributionStatus"] == "BLOCKED" or item["redistributionStatus"] not in RIGHTS_STATUSES:
            fail("第三方内容资产包含 BLOCKED 或未知状态")
        if item["redistributionStatus"] == "REVIEW_REQUIRED" and not isinstance(item["exception"], dict):
            fail("REVIEW_REQUIRED 内容资产缺少例外")
        if not isinstance(item["licenseTextFile"], str) or not SAFE_FILENAME_RE.fullmatch(item["licenseTextFile"]):
            fail("第三方内容资产许可证文本文件名不正确")
        license_files.add(item["licenseTextFile"])

    tool_ids: set[str] = set()
    for item in document["tools"]:
        output_keys = RIGHT_ENTRY_KEYS | {"reference", "category", "exception"}
        exact_keys(item, output_keys, "第三方构建工具证据")
        validate_common_rights(
            {key: item[key] for key in RIGHT_ENTRY_KEYS | {"reference", "category"}},
            {"reference", "category"}, "第三方构建工具证据",
        )
        if item["id"] in tool_ids or item["redistributionStatus"] == "BLOCKED":
            fail("第三方构建工具证据 id 重复或包含 BLOCKED")
        tool_ids.add(item["id"])
        if item["redistributionStatus"] == "REVIEW_REQUIRED" and not isinstance(item["exception"], dict):
            fail("REVIEW_REQUIRED 构建工具缺少例外")
        license_files.add(item["licenseTextFile"])

    if not all(isinstance(item, dict) for item in document["exceptions"]):
        fail("第三方权利 exceptions 必须是对象数组")
    files = document["files"]
    if not isinstance(files, list) or not files:
        fail("第三方权利主证据 files 不能为空")
    paths: set[str] = set()
    for item in files:
        validate_record(item, "第三方权利证据文件")
        if item["path"] in paths:
            fail(f"第三方权利证据文件重复：{item['path']}")
        paths.add(item["path"])
    required_paths = {"THIRD_PARTY_NOTICES.md", *{f"sbom/sbom-{role}.cdx.json" for role in SBOM_ROLES}}
    required_paths.update(f"licenses/{filename}" for filename in license_files)
    if not required_paths.issubset(paths):
        fail("第三方权利证据缺少 NOTICE 或六份 SBOM")
    return document


def verify_archive(path: pathlib.Path) -> dict[str, Any]:
    resolved = secure_file(path, "第三方权利证据包")
    with tarfile.open(resolved, "r:gz") as archive:
        members = archive.getmembers()
        if not members or any(not item.isfile() for item in members):
            fail("第三方权利证据包只能包含普通文件")
        roots: set[str] = set()
        relative_members: dict[str, tarfile.TarInfo] = {}
        for member in members:
            pure = pathlib.PurePosixPath(member.name)
            if pure.is_absolute() or ".." in pure.parts or len(pure.parts) < 2:
                fail("第三方权利证据包路径不安全")
            roots.add(pure.parts[0])
            relative = str(pathlib.PurePosixPath(*pure.parts[1:]))
            if relative in relative_members:
                fail(f"第三方权利证据包路径重复：{relative}")
            relative_members[relative] = member
        if len(roots) != 1 or "rights-evidence.json" not in relative_members:
            fail("第三方权利证据包必须只有一个根且包含主证据")
        document = json.loads(archive.extractfile(relative_members["rights-evidence.json"]).read())
        validate_evidence(document)
        indexed = {item["path"]: item for item in document["files"]}
        if set(relative_members) != {"rights-evidence.json", *indexed}:
            extra = sorted(set(relative_members) - {"rights-evidence.json", *indexed})
            missing = sorted({"rights-evidence.json", *indexed} - set(relative_members))
            fail(f"第三方权利证据包文件集合不精确：额外={extra} 缺少={missing}")
        for name, item in indexed.items():
            content = archive.extractfile(relative_members[name]).read()
            if len(content) != item["bytes"] or sha256_bytes(content) != item["sha256"]:
                fail(f"第三方权利证据文件摘要不一致：{name}")
    return document


def verify(args: argparse.Namespace) -> None:
    document = verify_archive(pathlib.Path(args.bundle))
    print(json.dumps({
        "format": document["format"],
        "version": document["release"]["version"],
        "status": document["status"],
        "legalReview": document["legalReview"]["status"],
        "sha256": sha256_file(pathlib.Path(args.bundle)),
    }, ensure_ascii=False))


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="Copper M6-03 third-party rights evidence contract")
    commands = result.add_subparsers(dest="command", required=True)
    inventory_parser = commands.add_parser("inventory")
    inventory_parser.add_argument("--manifest", required=True)
    inventory_parser.add_argument("--policy", required=True)
    inventory_parser.add_argument("--web-repo", required=True)
    inventory_parser.add_argument("--backend-repo", required=True)
    inventory_parser.add_argument("--deploy-repo", required=True)
    inventory_parser.add_argument("--output", required=True)
    inventory_parser.set_defaults(handler=inventory)
    discover_parser = commands.add_parser(
        "discover", help="从已验证 M4 SBOM 生成待人工复核的组件权利工作清单"
    )
    discover_parser.add_argument("--manifest", required=True)
    discover_parser.add_argument("--security-bundle", required=True)
    discover_parser.add_argument("--output", required=True)
    discover_parser.set_defaults(handler=discover)
    build_parser = commands.add_parser("build")
    build_parser.add_argument("--manifest", required=True)
    build_parser.add_argument("--policy", required=True)
    build_parser.add_argument("--security-bundle", required=True)
    build_parser.add_argument("--inventory", required=True)
    build_parser.add_argument("--ledger", required=True)
    build_parser.add_argument("--license-dir", required=True)
    build_parser.add_argument("--exceptions")
    build_parser.add_argument("--output", required=True)
    build_parser.set_defaults(handler=build)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--bundle", required=True)
    verify_parser.set_defaults(handler=verify)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        args.handler(args)
        return 0
    except (OSError, UnicodeError, json.JSONDecodeError, tarfile.TarError, RightsEvidenceError) as error:
        print(f"错误：{error}", file=os.sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
