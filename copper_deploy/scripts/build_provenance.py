"""Validate honest local build provenance while retaining historical Actions identities."""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import pathlib
import re


def validate_role(manifest: pathlib.Path, values: dict[str, str], role: str) -> dict[str, str]:
    """Return the identity fields shared by preloaded-image evidence consumers."""
    prefix = f"COPPER_{role}_"
    workflow = values.get(prefix + "WORKFLOW_RUN")
    name = values.get(prefix + "BUILD_PROVENANCE_FILE")
    digest = values.get(prefix + "BUILD_PROVENANCE_SHA256")
    if not name and not digest:
        if not workflow or not re.fullmatch(r"[1-9][0-9]*", workflow):
            raise ValueError(f"{role} 必须有真实 Actions Run 或摘要绑定的本地构建证据")
        return {"workflowRun": workflow}
    if workflow:
        raise ValueError(f"{role} 本地构建不得伪填或复用 Actions Run")
    if not name or pathlib.PurePosixPath(name).name != name or name in {".", ".."}:
        raise ValueError(f"{role} 本地构建证据必须是同目录普通文件")
    if not digest or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError(f"{role} 本地构建证据缺少 SHA-256")
    path = manifest.parent / name
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ValueError(f"{role} 本地构建证据不存在或不安全")
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError(f"{role} 本地构建证据 SHA-256 不一致")
    document = json.loads(raw)
    required = {"format", "role", "sourceCommit", "buildId", "builtAt", "platform", "image", "imageId", "builder", "command", "tools"}
    if not isinstance(document, dict) or set(document) != required:
        raise ValueError(f"{role} 本地构建证据字段不正确")
    if document["format"] != "copper-local-build/v1" or document["role"] != role.lower():
        raise ValueError(f"{role} 本地构建证据格式或角色错误")
    for key, field in (("sourceCommit", "SOURCE_COMMIT"), ("image", "IMAGE"), ("platform", "PLATFORM")):
        if document[key] != values.get(prefix + field):
            raise ValueError(f"{role} 本地构建证据未绑定发布清单：{key}")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", str(document["buildId"])):
        raise ValueError(f"{role} 本地 buildId 不安全")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", str(document["imageId"])):
        raise ValueError(f"{role} 本地 imageId 必须是实际内容摘要")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", str(document["builtAt"])):
        raise ValueError(f"{role} 本地 builtAt 必须是 UTC 时间")
    dt.datetime.strptime(document["builtAt"], "%Y-%m-%dT%H:%M:%SZ")
    for key in ("builder", "command"):
        if not isinstance(document[key], str) or not document[key].strip() or len(document[key]) > 4096:
            raise ValueError(f"{role} 本地 {key} 缺失")
    tools = document["tools"]
    if not isinstance(tools, dict) or not tools or any(not isinstance(k, str) or not isinstance(v, str) or not k or not v for k, v in tools.items()):
        raise ValueError(f"{role} 本地构建工具版本缺失")
    return {"buildId": document["buildId"], "buildProvenanceSha256": digest, "buildImageId": document["imageId"]}
