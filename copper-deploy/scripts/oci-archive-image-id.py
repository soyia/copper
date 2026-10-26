#!/usr/bin/env python3
"""Resolve import-stable image IDs from a Docker/OCI image archive.

Docker 29 may expose a producer-side config digest through ``image inspect``
while ``docker load`` addresses the imported image by the OCI manifest digest.
The delivery contract therefore derives runtime IDs from the archive index and
verifies the referenced manifest and config objects before publishing them.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tarfile
from pathlib import Path


DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
MAX_INDEX_BYTES = 1024 * 1024
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
MAX_CONFIG_BYTES = 16 * 1024 * 1024


def fail(message: str) -> None:
    raise SystemExit(f"错误：{message}")


def regular_member(archive: tarfile.TarFile, name: str, maximum: int) -> tarfile.TarInfo:
    members = [member for member in archive.getmembers() if member.name == name]
    if len(members) != 1 or not members[0].isfile() or members[0].size > maximum:
        fail(f"镜像归档成员不存在、不唯一或不安全：{name}")
    return members[0]


def read_json(archive: tarfile.TarFile, member: tarfile.TarInfo, label: str) -> dict[str, object]:
    handle = archive.extractfile(member)
    if handle is None:
        fail(f"无法读取{label}")
    try:
        document = json.load(handle)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"{label}不是有效 JSON：{error}")
    if not isinstance(document, dict):
        fail(f"{label}必须是 JSON 对象")
    return document


def verified_blob(
    archive: tarfile.TarFile, digest: str, maximum: int, label: str
) -> tuple[tarfile.TarInfo, bytes]:
    if not DIGEST_RE.fullmatch(digest):
        fail(f"{label}摘要格式不正确")
    member = regular_member(
        archive, f"blobs/sha256/{digest.removeprefix('sha256:')}", maximum
    )
    handle = archive.extractfile(member)
    if handle is None:
        fail(f"无法读取{label}对象")
    content = handle.read()
    actual = "sha256:" + hashlib.sha256(content).hexdigest()
    if actual != digest:
        fail(f"{label}对象 SHA-256 不匹配")
    return member, content


def resolve(archive_path: Path, references: list[str]) -> list[tuple[str, str, str]]:
    if not archive_path.is_file() or archive_path.is_symlink():
        fail("镜像归档不存在、是链接或不是普通文件")
    if not references or len(references) != len(set(references)):
        fail("镜像引用必须非空且不得重复")

    try:
        archive = tarfile.open(archive_path, "r:*")
    except (tarfile.TarError, OSError) as error:
        fail(f"无法打开镜像归档：{error}")
    with archive:
        index = read_json(
            archive,
            regular_member(archive, "index.json", MAX_INDEX_BYTES),
            "index.json",
        )
        manifests = index.get("manifests")
        if not isinstance(manifests, list):
            fail("index.json 缺少 manifests 数组")

        result: list[tuple[str, str, str]] = []
        for reference in references:
            matches = []
            for item in manifests:
                if not isinstance(item, dict):
                    continue
                annotations = item.get("annotations")
                if isinstance(annotations, dict) and annotations.get(
                    "io.containerd.image.name"
                ) == reference:
                    matches.append(item)
            if not matches:
                # Docker 29 saves digest-addressed images without a name
                # annotation. Only an exact requested manifest digest can
                # replace that absent name; tags never receive a fallback.
                pinned = re.fullmatch(r"[^\s@]+@(sha256:[0-9a-f]{64})", reference)
                if pinned:
                    matches = [
                        item for item in manifests
                        if isinstance(item, dict) and item.get("digest") == pinned.group(1)
                    ]
            if len(matches) != 1:
                fail(f"镜像归档不包含指定的唯一运行引用：{reference}")

            manifest_digest = matches[0].get("digest")
            if not isinstance(manifest_digest, str):
                fail(f"镜像 manifest 摘要缺失：{reference}")
            _, manifest_bytes = verified_blob(
                archive, manifest_digest, MAX_MANIFEST_BYTES, f"{reference} manifest"
            )
            try:
                manifest = json.loads(manifest_bytes)
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                fail(f"镜像 manifest 不是有效 JSON：{reference}：{error}")
            config = manifest.get("config") if isinstance(manifest, dict) else None
            config_digest = config.get("digest") if isinstance(config, dict) else None
            if not isinstance(config_digest, str):
                fail(f"镜像 manifest 缺少 config 摘要：{reference}")
            _, config_bytes = verified_blob(
                archive, config_digest, MAX_CONFIG_BYTES, f"{reference} config"
            )
            try:
                config_document = json.loads(config_bytes)
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                fail(f"镜像 config 不是有效 JSON：{reference}：{error}")
            architecture = (
                config_document.get("architecture")
                if isinstance(config_document, dict)
                else None
            )
            if architecture != "amd64":
                fail(f"镜像归档架构不是 amd64：{reference} ({architecture})")
            result.append((reference, manifest_digest, architecture))
        return result


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Resolve import-stable OCI manifest IDs from an image archive"
    )
    parser.add_argument("archive")
    parser.add_argument("references", nargs="+")
    args = parser.parse_args()
    for reference, digest, architecture in resolve(Path(args.archive), args.references):
        print(f"{reference}\t{digest}\t{architecture}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BrokenPipeError:
        sys.exit(1)
