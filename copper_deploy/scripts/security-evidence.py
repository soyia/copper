#!/usr/bin/env python3
"""Build and verify release-bound Copper security evidence bundles."""

from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import json
import os
import pathlib
import re
import stat
import tarfile
import tempfile
from collections import Counter
from typing import Any


FORMAT = "copper-security/v1"
EXCEPTION_FORMAT = "copper-security-exceptions/v1"
HASH_RE = re.compile(r"[0-9a-f]{64}")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")
SAFE_NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,191}")
ROLES = (
    "source-web", "source-backend", "source-deploy",
    "image-web", "image-backend", "image-mysql",
)
SECRET_ROLES = ("source-web", "source-backend", "source-deploy", "release-artifacts")
SEVERITIES = ("Critical", "High", "Medium", "Low", "Negligible", "Unknown")


class SecurityEvidenceError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise SecurityEvidenceError(message)


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def iso(value: dt.datetime) -> str:
    return value.astimezone(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_time(value: object, label: str) -> dt.datetime:
    if not isinstance(value, str):
        fail(f"{label} 必须是 UTC 时间")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        fail(f"{label} 不是有效 ISO-8601 时间")
    if parsed.tzinfo is None:
        fail(f"{label} 必须包含时区")
    return parsed.astimezone(dt.timezone.utc)


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


def parse_env(path: pathlib.Path, label: str) -> dict[str, str]:
    resolved = secure_file(path, label)
    values: dict[str, str] = {}
    for line_number, raw in enumerate(resolved.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"{label} 第 {line_number} 行格式不正确")
        key, value = line.split("=", 1)
        if key in values:
            fail(f"{label} 存在重复字段：{key}")
        values[key] = value
    return values


def load_json(path: pathlib.Path, label: str) -> Any:
    resolved = secure_file(path, label)
    try:
        return json.loads(resolved.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        fail(f"{label} 不是有效 UTF-8 JSON：{error}")


def record(path: pathlib.Path) -> dict[str, object]:
    return {"file": path.name, "bytes": path.stat().st_size, "sha256": sha256_file(path)}


def required_policy(values: dict[str, str]) -> None:
    required = {
        "COPPER_SECURITY_EVIDENCE_FORMAT", "COPPER_SECURITY_POLICY_VERSION",
        "COPPER_SECURITY_DEFAULT_OWNER", "COPPER_SBOM_FORMAT", "COPPER_SBOM_SPEC_VERSION",
        "COPPER_SYFT_VERSION", "COPPER_SYFT_IMAGE", "COPPER_GRYPE_VERSION", "COPPER_GRYPE_IMAGE",
        "COPPER_GITLEAKS_VERSION", "COPPER_GITLEAKS_IMAGE", "COPPER_RC_BLOCK_SEVERITIES",
        "COPPER_STABLE_BLOCK_SEVERITIES", "COPPER_CRITICAL_FIX_HOURS", "COPPER_HIGH_FIX_DAYS",
        "COPPER_MEDIUM_FIX_DAYS", "COPPER_LOW_FIX_DAYS", "COPPER_MAX_EXCEPTION_DAYS",
        "COPPER_MAX_CRITICAL_EXCEPTION_DAYS", "COPPER_GRYPE_DB_MAX_AGE_HOURS",
    }
    missing = sorted(required - set(values))
    if missing:
        fail(f"安全策略缺少字段：{','.join(missing)}")
    if values["COPPER_SECURITY_EVIDENCE_FORMAT"] != FORMAT:
        fail(f"安全策略格式必须是 {FORMAT}")
    for field in ("COPPER_SYFT_IMAGE", "COPPER_GRYPE_IMAGE", "COPPER_GITLEAKS_IMAGE"):
        if not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", values[field]):
            fail(f"{field} 必须使用不可变镜像摘要")


def validate_sbom(path: pathlib.Path, image_role: bool) -> dict[str, object]:
    document = load_json(path, f"SBOM {path.name}")
    if not isinstance(document, dict) or document.get("bomFormat") != "CycloneDX":
        fail(f"{path.name} 不是 CycloneDX SBOM")
    if not isinstance(document.get("specVersion"), str):
        fail(f"{path.name} 缺少 CycloneDX specVersion")
    components = document.get("components", [])
    if not isinstance(components, list):
        fail(f"{path.name} components 不是数组")
    if image_role and not components:
        fail(f"镜像 SBOM 不得为空：{path.name}")
    return {**record(path), "componentCount": len(components), "specVersion": document["specVersion"]}


def validate_grype_database(document: Any, generated_at: dt.datetime, max_age_hours: int) -> dict[str, object]:
    if not isinstance(document, dict):
        fail("Grype DB 状态必须是 JSON 对象")
    if document.get("valid") is not True or document.get("error") not in {None, ""}:
        fail("Grype 漏洞数据库状态无效")
    schema = document.get("schemaVersion")
    if not isinstance(schema, str) or not schema.strip():
        fail("Grype 漏洞数据库缺少 schemaVersion")
    built = parse_time(document.get("built"), "Grype 漏洞数据库 built")
    age = generated_at - built
    if age < dt.timedelta(minutes=-5):
        fail("Grype 漏洞数据库构建时间晚于扫描时间")
    if age > dt.timedelta(hours=max_age_hours):
        fail(f"Grype 漏洞数据库已超过最长 {max_age_hours} 小时")
    return {
        "schemaVersion": schema,
        "built": iso(built),
        "ageSeconds": max(0, int(age.total_seconds())),
        "maxAgeHours": max_age_hours,
    }


def findings(path: pathlib.Path, role: str, generated_at: dt.datetime, policy: dict[str, str]) -> tuple[list[dict[str, Any]], dict[str, int]]:
    document = load_json(path, f"漏洞报告 {path.name}")
    if not isinstance(document, dict) or not isinstance(document.get("matches"), list):
        fail(f"{path.name} 不是受支持的 Grype JSON 报告")
    unique: dict[tuple[str, str, str, str], dict[str, Any]] = {}
    counts: Counter[str] = Counter()
    for item in document["matches"]:
        if not isinstance(item, dict):
            continue
        vulnerability = item.get("vulnerability") if isinstance(item.get("vulnerability"), dict) else {}
        artifact = item.get("artifact") if isinstance(item.get("artifact"), dict) else {}
        identifier = str(vulnerability.get("id", "UNKNOWN"))
        severity = str(vulnerability.get("severity", "Unknown")).title()
        if severity not in SEVERITIES:
            severity = "Unknown"
        package = str(artifact.get("name", "unknown"))
        installed = str(artifact.get("version", "unknown"))
        key = (identifier, package, installed, severity)
        if key in unique:
            continue
        fix = vulnerability.get("fix") if isinstance(vulnerability.get("fix"), dict) else {}
        versions = fix.get("versions") if isinstance(fix.get("versions"), list) else []
        if severity == "Critical":
            due = generated_at + dt.timedelta(hours=int(policy["COPPER_CRITICAL_FIX_HOURS"]))
        elif severity == "High":
            due = generated_at + dt.timedelta(days=int(policy["COPPER_HIGH_FIX_DAYS"]))
        elif severity == "Medium":
            due = generated_at + dt.timedelta(days=int(policy["COPPER_MEDIUM_FIX_DAYS"]))
        else:
            due = generated_at + dt.timedelta(days=int(policy["COPPER_LOW_FIX_DAYS"]))
        unique[key] = {
            "vulnerabilityId": identifier,
            "artifactRole": role,
            "packageName": package,
            "installedVersion": installed,
            "severity": severity,
            "fixedVersions": [str(version) for version in versions],
            "owner": policy["COPPER_SECURITY_DEFAULT_OWNER"],
            "remediationDueAt": iso(due),
        }
        counts[severity] += 1
    return sorted(unique.values(), key=lambda item: (SEVERITIES.index(item["severity"]), item["vulnerabilityId"], item["packageName"])), dict(counts)


def load_exceptions(path: pathlib.Path | None, release_version: str, generated_at: dt.datetime, policy: dict[str, str]) -> tuple[list[dict[str, Any]], dict[str, object] | None, list[pathlib.Path]]:
    if path is None:
        return [], None, []
    document = load_json(path, "漏洞例外")
    if not isinstance(document, dict) or set(document) != {"format", "releaseVersion", "exceptions"}:
        fail("漏洞例外顶层字段不正确")
    if document["format"] != EXCEPTION_FORMAT or document["releaseVersion"] != release_version:
        fail("漏洞例外格式或发布版本不匹配")
    if not isinstance(document["exceptions"], list):
        fail("漏洞例外 exceptions 必须是数组")
    required = {
        "vulnerabilityId", "artifactRole", "packageName", "installedVersion", "severity",
        "disposition", "owner", "reason", "compensatingControl", "expiresAt",
        "evidenceFile", "evidenceSha256",
    }
    result: list[dict[str, Any]] = []
    evidence_paths: dict[str, pathlib.Path] = {}
    for index, item in enumerate(document["exceptions"]):
        if not isinstance(item, dict) or set(item) != required:
            fail(f"漏洞例外第 {index + 1} 项字段不完整或包含未知字段")
        if item["artifactRole"] not in ROLES or item["severity"] not in SEVERITIES:
            fail(f"漏洞例外第 {index + 1} 项角色或严重度不正确")
        if item["disposition"] not in {"FALSE_POSITIVE", "RISK_ACCEPTED"}:
            fail(f"漏洞例外第 {index + 1} 项 disposition 不受支持")
        for field in ("vulnerabilityId", "packageName", "installedVersion", "owner", "reason", "compensatingControl"):
            if not isinstance(item[field], str) or not item[field].strip() or len(item[field]) > 500:
                fail(f"漏洞例外第 {index + 1} 项 {field} 不正确")
        evidence_name = item["evidenceFile"]
        evidence_sha = item["evidenceSha256"]
        if not isinstance(evidence_name, str) or pathlib.PurePosixPath(evidence_name).name != evidence_name \
                or evidence_name in {".", ".."}:
            fail(f"漏洞例外第 {index + 1} 项 evidenceFile 必须是同目录普通文件名")
        if not isinstance(evidence_sha, str) or not HASH_RE.fullmatch(evidence_sha):
            fail(f"漏洞例外第 {index + 1} 项 evidenceSha256 不正确")
        evidence_path = secure_file(path.parent / evidence_name, f"漏洞例外证据 {evidence_name}")
        if sha256_file(evidence_path) != evidence_sha:
            fail(f"漏洞例外第 {index + 1} 项证据 SHA-256 不一致")
        evidence_paths[evidence_name] = evidence_path
        expiry = parse_time(item["expiresAt"], f"漏洞例外第 {index + 1} 项 expiresAt")
        max_days = int(policy["COPPER_MAX_CRITICAL_EXCEPTION_DAYS"]) if item["severity"] == "Critical" else int(policy["COPPER_MAX_EXCEPTION_DAYS"])
        if expiry <= generated_at or expiry > generated_at + dt.timedelta(days=max_days):
            fail(f"漏洞例外第 {index + 1} 项已过期或超过最长 {max_days} 天")
        result.append(item)
    evidence_files = [evidence_paths[name] for name in sorted(evidence_paths)]
    return result, {
        **record(secure_file(path, "漏洞例外")),
        "evidenceFiles": [record(item) for item in evidence_files],
    }, evidence_files


def exception_for(finding: dict[str, Any], exceptions: list[dict[str, Any]]) -> dict[str, Any] | None:
    keys = ("vulnerabilityId", "artifactRole", "packageName", "installedVersion", "severity")
    matches = [item for item in exceptions if all(item[key] == finding[key] for key in keys)]
    if len(matches) > 1:
        fail(f"漏洞例外重复：{finding['artifactRole']} {finding['vulnerabilityId']} {finding['packageName']}")
    return matches[0] if matches else None


def evidence_document(manifest_path: pathlib.Path, policy_path: pathlib.Path, reports_dir: pathlib.Path, exceptions_path: pathlib.Path | None) -> tuple[dict[str, Any], list[pathlib.Path]]:
    manifest = parse_env(manifest_path, "发布清单")
    policy = parse_env(policy_path, "安全策略")
    required_policy(policy)
    version = manifest.get("COPPER_RELEASE_VERSION", "")
    channel = manifest.get("COPPER_RELEASE_CHANNEL", "")
    if not version or channel not in {"rc", "stable"}:
        fail("发布清单缺少版本或渠道")
    for field in ("COPPER_DEPLOY_COMMIT", "COPPER_WEB_SOURCE_COMMIT", "COPPER_BACKEND_SOURCE_COMMIT"):
        if not COMMIT_RE.fullmatch(manifest.get(field, "")):
            fail(f"发布清单 {field} 不正确")

    generated_at = utc_now()
    exceptions, exception_record, exception_evidence_files = load_exceptions(
        exceptions_path, version, generated_at, policy
    )
    sboms: list[dict[str, Any]] = []
    vulnerability_scans: list[dict[str, Any]] = []
    all_findings: list[dict[str, Any]] = []
    files: list[pathlib.Path] = []
    for role in ROLES:
        sbom_path = reports_dir / f"sbom-{role}.cdx.json"
        report_path = reports_dir / f"vulnerability-{role}.json"
        sboms.append({"role": role, **validate_sbom(sbom_path, role.startswith("image-"))})
        role_findings, counts = findings(report_path, role, generated_at, policy)
        vulnerability_scans.append({"role": role, **record(report_path), "counts": counts})
        all_findings.extend(role_findings)
        files.extend((sbom_path, report_path))

    secret_scans: list[dict[str, Any]] = []
    secret_findings = 0
    for role in SECRET_ROLES:
        path = reports_dir / f"secrets-{role}.json"
        document = load_json(path, f"秘密扫描 {path.name}")
        if not isinstance(document, list):
            fail(f"{path.name} 不是 Gitleaks JSON 数组")
        secret_findings += len(document)
        secret_scans.append({"role": role, **record(path), "findingCount": len(document)})
        files.append(path)

    db_status_path = reports_dir / "grype-db-status.json"
    db_status = load_json(db_status_path, "Grype DB 状态")
    db_validation = validate_grype_database(
        db_status, generated_at, int(policy["COPPER_GRYPE_DB_MAX_AGE_HOURS"])
    )
    files.append(db_status_path)
    provenance_path = reports_dir / "image-provenance.json"
    provenance = load_json(provenance_path, "镜像扫描来源")
    if not isinstance(provenance, dict) or provenance.get("format") != "copper-image-scan-provenance/v1" \
            or not isinstance(provenance.get("images"), list):
        fail("镜像扫描来源格式不正确")
    expected_references = {
        "web": manifest.get("COPPER_WEB_IMAGE"),
        "backend": manifest.get("COPPER_BACKEND_IMAGE"),
        "mysql": manifest.get("COPPER_MYSQL_IMAGE"),
    }
    seen_roles: set[str] = set()
    for item in provenance["images"]:
        if not isinstance(item, dict) or set(item) != {
            "role", "publishedReference", "scannedReference", "localId", "architecture", "source", "sourceEvidenceSha256",
        }:
            fail("镜像扫描来源字段不完整或包含未知字段")
        role = item.get("role")
        if role not in expected_references or role in seen_roles or item.get("publishedReference") != expected_references[role]:
            fail("镜像扫描来源未精确绑定发布清单")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", str(item.get("localId", ""))) or item.get("architecture") != "amd64":
            fail("镜像扫描来源内容 ID 或架构不正确")
        if item.get("source") not in {"registry", "ci-preloaded", "verified-preloaded"}:
            fail("镜像扫描来源类型不受支持")
        evidence_sha = item.get("sourceEvidenceSha256")
        if item["source"] in {"ci-preloaded", "verified-preloaded"} \
                and not HASH_RE.fullmatch(str(evidence_sha or "")):
            fail("预加载镜像缺少来源证据 SHA-256")
        if item["source"] == "registry" and evidence_sha is not None:
            fail("registry 镜像不应伪造预加载证据")
        seen_roles.add(role)
    if seen_roles != set(expected_references):
        fail("镜像扫描来源必须精确包含 web、backend、mysql")
    files.append(provenance_path)

    blocked_severities = {
        item.strip() for item in policy[
            "COPPER_STABLE_BLOCK_SEVERITIES" if channel == "stable" else "COPPER_RC_BLOCK_SEVERITIES"
        ].split(",") if item.strip()
    }
    blockers: list[dict[str, str]] = []
    accepted: list[dict[str, str]] = []
    for finding in all_findings:
        exception = exception_for(finding, exceptions)
        if exception:
            accepted.append({
                "vulnerabilityId": finding["vulnerabilityId"],
                "artifactRole": finding["artifactRole"],
                "packageName": finding["packageName"],
                "disposition": exception["disposition"],
                "owner": exception["owner"],
                "expiresAt": exception["expiresAt"],
                "evidenceFile": exception["evidenceFile"],
                "evidenceSha256": exception["evidenceSha256"],
            })
        elif finding["severity"] in blocked_severities:
            blockers.append({
                "vulnerabilityId": finding["vulnerabilityId"],
                "artifactRole": finding["artifactRole"],
                "packageName": finding["packageName"],
                "severity": finding["severity"],
            })

    if secret_findings:
        fail(f"秘密扫描发现 {secret_findings} 项；报告已脱敏，但禁止生成可发布安全证据")
    if blockers:
        summary = ", ".join(f"{item['artifactRole']}:{item['vulnerabilityId']}" for item in blockers[:5])
        fail(f"存在 {len(blockers)} 项未处置的发布阻断漏洞：{summary}")

    release = {
        "version": version,
        "channel": channel,
        "manifest": record(manifest_path),
        "deployCommit": manifest["COPPER_DEPLOY_COMMIT"],
        "webSourceCommit": manifest["COPPER_WEB_SOURCE_COMMIT"],
        "backendSourceCommit": manifest["COPPER_BACKEND_SOURCE_COMMIT"],
        "webImage": manifest.get("COPPER_WEB_IMAGE"),
        "backendImage": manifest.get("COPPER_BACKEND_IMAGE"),
        "mysqlImage": manifest.get("COPPER_MYSQL_IMAGE"),
    }
    document = {
        "format": FORMAT,
        "status": "PASS",
        "generatedAt": iso(generated_at),
        "release": release,
        "policy": {
            **record(policy_path),
            "version": policy["COPPER_SECURITY_POLICY_VERSION"],
            "owner": policy["COPPER_SECURITY_DEFAULT_OWNER"],
            "toolImages": {
                "syft": policy["COPPER_SYFT_IMAGE"],
                "grype": policy["COPPER_GRYPE_IMAGE"],
                "gitleaks": policy["COPPER_GITLEAKS_IMAGE"],
            },
            "grypeDbMaxAgeHours": int(policy["COPPER_GRYPE_DB_MAX_AGE_HOURS"]),
        },
        "exceptions": exception_record,
        "sboms": sboms,
        "secretScans": secret_scans,
        "vulnerabilityScans": vulnerability_scans,
        "findings": all_findings,
        "acceptedExceptions": accepted,
        "grypeDatabase": {**record(db_status_path), **db_validation},
        "imageProvenance": {**record(provenance_path), "images": provenance["images"]},
        "gate": {"status": "PASS", "secretFindings": 0, "blockingFindings": 0},
    }
    if exceptions_path:
        files.append(exceptions_path)
        files.extend(exception_evidence_files)
    return document, files


def write_json(path: pathlib.Path, document: Any) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as writer:
            json.dump(document, writer, ensure_ascii=False, indent=2, sort_keys=True)
            writer.write("\n")
            writer.flush()
            os.fsync(writer.fileno())
        os.chmod(temporary, 0o444)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def add_tar_file(archive: tarfile.TarFile, path: pathlib.Path, arcname: str) -> None:
    info = tarfile.TarInfo(arcname)
    info.size = path.stat().st_size
    info.mode = 0o444
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = 0
    with path.open("rb") as reader:
        archive.addfile(info, reader)


def build(args: argparse.Namespace) -> None:
    manifest_path = secure_file(pathlib.Path(args.manifest), "发布清单")
    policy_path = secure_file(pathlib.Path(args.policy), "安全策略")
    reports_dir = pathlib.Path(args.reports_dir).resolve()
    if reports_dir.is_symlink() or not reports_dir.is_dir():
        fail("报告目录不存在、不是目录或是符号链接")
    exceptions_path = secure_file(pathlib.Path(args.exceptions), "漏洞例外") if args.exceptions else None
    document, report_files = evidence_document(manifest_path, policy_path, reports_dir, exceptions_path)
    evidence_path = reports_dir / "security-evidence.json"
    if evidence_path.exists() or evidence_path.is_symlink():
        fail("报告目录已存在 security-evidence.json，拒绝覆盖")
    write_json(evidence_path, document)
    output = pathlib.Path(args.output).resolve()
    if output.exists() or output.is_symlink():
        fail("安全证据包输出已存在，拒绝覆盖")
    output.parent.mkdir(parents=True, exist_ok=True)
    prefix = f"copper-security-{document['release']['version']}"
    include = [manifest_path, policy_path, *report_files, evidence_path]
    seen: set[str] = set()
    with output.open("xb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w") as archive:
                for path in sorted(include, key=lambda item: item.name):
                    if path.name in seen:
                        continue
                    seen.add(path.name)
                    add_tar_file(archive, path, f"{prefix}/{path.name}")
    os.chmod(output, 0o444)
    verify_archive(output)
    print(json.dumps({"bundle": str(output), "sha256": sha256_file(output), "status": "PASS"}, ensure_ascii=False))


def validate_evidence_structure(evidence: Any) -> None:
    if not isinstance(evidence, dict) or evidence.get("format") != FORMAT or evidence.get("status") != "PASS":
        fail("安全证据主文件格式或状态不正确")

    def valid_record(item: Any, label: str) -> None:
        if not isinstance(item, dict):
            fail(f"{label} 记录不是对象")
        name = item.get("file")
        digest = item.get("sha256")
        size = item.get("bytes")
        if not isinstance(name, str) or pathlib.PurePosixPath(name).name != name or name in {".", ".."}:
            fail(f"{label} 文件名不正确")
        if not isinstance(digest, str) or not HASH_RE.fullmatch(digest):
            fail(f"{label} SHA-256 不正确")
        if not isinstance(size, int) or size < 0:
            fail(f"{label} 字节数不正确")

    def role_records(section: str, expected: set[str]) -> list[dict[str, Any]]:
        items = evidence.get(section)
        if not isinstance(items, list) or len(items) != len(expected):
            fail(f"安全证据 {section} 数量不正确")
        roles: set[str] = set()
        for item in items:
            if not isinstance(item, dict) or not isinstance(item.get("role"), str):
                fail(f"安全证据 {section} 角色不正确")
            valid_record(item, f"{section}/{item['role']}")
            roles.add(item["role"])
        if roles != expected:
            fail(f"安全证据 {section} 角色集合不正确")
        return items

    release = evidence.get("release")
    policy = evidence.get("policy")
    gate = evidence.get("gate")
    if not isinstance(release, dict) or not isinstance(policy, dict) or not isinstance(gate, dict):
        fail("安全证据缺少 release、policy 或 gate")
    valid_record(release.get("manifest"), "发布清单")
    valid_record(policy, "安全策略")
    valid_record(evidence.get("grypeDatabase"), "Grype 数据库状态")
    valid_record(evidence.get("imageProvenance"), "镜像来源")
    if gate.get("status") != "PASS" or gate.get("secretFindings") != 0 or gate.get("blockingFindings") != 0:
        fail("安全证据 gate 不是无秘密、无阻断项的 PASS")

    role_records("sboms", set(ROLES))
    secret_records = role_records("secretScans", set(SECRET_ROLES))
    if any(item.get("findingCount") != 0 for item in secret_records):
        fail("安全证据包含秘密发现")
    role_records("vulnerabilityScans", set(ROLES))
    if not isinstance(evidence.get("findings"), list) or not isinstance(evidence.get("acceptedExceptions"), list):
        fail("安全证据 findings 或 acceptedExceptions 不是数组")

    provenance = evidence["imageProvenance"].get("images")
    if not isinstance(provenance, list) or {item.get("role") for item in provenance if isinstance(item, dict)} != {"web", "backend", "mysql"}:
        fail("安全证据镜像来源角色不完整")
    exceptions = evidence.get("exceptions")
    if exceptions is not None:
        valid_record(exceptions, "漏洞例外")
        evidence_files = exceptions.get("evidenceFiles")
        if not isinstance(evidence_files, list) or not evidence_files:
            fail("漏洞例外缺少原始证据文件")
        for item in evidence_files:
            valid_record(item, "漏洞例外原始证据")


def verify_archive(path: pathlib.Path) -> dict[str, Any]:
    resolved = secure_file(path, "安全证据包")
    with tarfile.open(resolved, "r:gz") as archive:
        members = archive.getmembers()
        if not members or any(not member.isfile() for member in members):
            fail("安全证据包只能包含普通文件")
        roots = {pathlib.PurePosixPath(member.name).parts[0] for member in members}
        if len(roots) != 1:
            fail("安全证据包必须只有一个顶层目录")
        names = [pathlib.PurePosixPath(member.name).name for member in members]
        if len(names) != len(set(names)) or "security-evidence.json" not in names:
            fail("安全证据包存在重复文件名或缺少主证据")
        evidence_member = next(member for member in members if member.name.endswith("/security-evidence.json"))
        evidence = json.load(archive.extractfile(evidence_member))
        validate_evidence_structure(evidence)
        indexed = {}
        for section in ("sboms", "secretScans", "vulnerabilityScans"):
            for item in evidence.get(section, []):
                indexed[item["file"]] = item["sha256"]
        for key in ("manifest",):
            item = evidence["release"][key]
            indexed[item["file"]] = item["sha256"]
        indexed[evidence["policy"]["file"]] = evidence["policy"]["sha256"]
        indexed[evidence["grypeDatabase"]["file"]] = evidence["grypeDatabase"]["sha256"]
        indexed[evidence["imageProvenance"]["file"]] = evidence["imageProvenance"]["sha256"]
        if evidence.get("exceptions"):
            indexed[evidence["exceptions"]["file"]] = evidence["exceptions"]["sha256"]
            for item in evidence["exceptions"].get("evidenceFiles", []):
                indexed[item["file"]] = item["sha256"]
        member_map = {pathlib.PurePosixPath(member.name).name: member for member in members}
        expected_names = {*indexed, "security-evidence.json"}
        if set(member_map) != expected_names:
            unexpected = sorted(set(member_map) - expected_names)
            missing = sorted(expected_names - set(member_map))
            fail(f"安全证据包文件集合不精确：额外={unexpected} 缺少={missing}")
        for name, digest in indexed.items():
            if name not in member_map:
                fail(f"安全证据包缺少绑定文件：{name}")
            content = archive.extractfile(member_map[name]).read()
            if hashlib.sha256(content).hexdigest() != digest:
                fail(f"安全证据包文件摘要不一致：{name}")
        return evidence


def verify(args: argparse.Namespace) -> None:
    evidence = verify_archive(pathlib.Path(args.bundle))
    print(json.dumps({
        "format": evidence["format"],
        "version": evidence["release"]["version"],
        "status": evidence["status"],
        "sha256": sha256_file(pathlib.Path(args.bundle)),
    }, ensure_ascii=False))


def provenance(args: argparse.Namespace) -> None:
    manifest = parse_env(pathlib.Path(args.manifest), "发布清单")
    expected = {
        "web": manifest.get("COPPER_WEB_IMAGE"),
        "backend": manifest.get("COPPER_BACKEND_IMAGE"),
        "mysql": manifest.get("COPPER_MYSQL_IMAGE"),
    }
    images: list[dict[str, Any]] = []
    seen: set[str] = set()
    for value in args.image:
        parts = value.split(",", 6)
        if len(parts) != 7:
            fail("--image 必须是 ROLE,PUBLISHED,SCANNED,LOCAL_ID,ARCH,SOURCE,EVIDENCE_SHA_OR_DASH")
        role, published, scanned, local_id, architecture, source, evidence = parts
        if role not in expected or role in seen or published != expected[role]:
            fail("--image 角色重复或未绑定发布清单")
        evidence_value = None if evidence == "-" else evidence
        if source in {"ci-preloaded", "verified-preloaded"} \
                and not HASH_RE.fullmatch(str(evidence_value or "")):
            fail("预加载镜像必须提供证据 SHA-256")
        if source == "registry" and evidence_value is not None:
            fail("registry 镜像的证据参数必须是 -")
        if source not in {"registry", "ci-preloaded", "verified-preloaded"}:
            fail("镜像扫描来源类型不受支持")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", local_id) or architecture != "amd64":
            fail("--image 内容 ID 或架构不正确")
        images.append({
            "role": role, "publishedReference": published, "scannedReference": scanned,
            "localId": local_id, "architecture": architecture, "source": source,
            "sourceEvidenceSha256": evidence_value,
        })
        seen.add(role)
    if seen != set(expected):
        fail("--image 必须精确包含 web、backend、mysql")
    output = pathlib.Path(args.output).resolve()
    if output.exists() or output.is_symlink():
        fail("镜像扫描来源输出已存在，拒绝覆盖")
    output.parent.mkdir(parents=True, exist_ok=True)
    write_json(output, {"format": "copper-image-scan-provenance/v1", "images": images})


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    build_parser = commands.add_parser("build")
    build_parser.add_argument("--manifest", required=True)
    build_parser.add_argument("--policy", required=True)
    build_parser.add_argument("--reports-dir", required=True)
    build_parser.add_argument("--exceptions")
    build_parser.add_argument("--output", required=True)
    build_parser.set_defaults(handler=build)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--bundle", required=True)
    verify_parser.set_defaults(handler=verify)
    provenance_parser = commands.add_parser("provenance")
    provenance_parser.add_argument("--manifest", required=True)
    provenance_parser.add_argument("--image", action="append", required=True)
    provenance_parser.add_argument("--output", required=True)
    provenance_parser.set_defaults(handler=provenance)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        args.handler(args)
        return 0
    except (OSError, UnicodeError, json.JSONDecodeError, tarfile.TarError, SecurityEvidenceError) as error:
        print(f"错误：{error}", file=os.sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
