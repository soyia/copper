#!/usr/bin/env python3
"""Build a closed, internal-rehearsal guided installer medium.

The script deliberately reuses only already-verified image archives from a
source M7 medium.  It replaces the Deploy archive, adds the one-entry installer,
updates public provenance, and regenerates the closed SHA-256 inventory.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
from typing import Any


ROOT = pathlib.Path(__file__).resolve().parent.parent
INSTALLER_VERSION = "0.4.5"
REQUIRED_SOURCE_PATHS = (
    "OPERATOR_VALUES.env",
    "RELEASE-MANIFEST.json",
    "INTERNAL_REHEARSAL_ONLY.txt",
    "tools/copper-key-offline",
    "images/copper-web-amd64.tar.gz",
    "images/copper-backend-amd64.tar.gz",
    "images/copper-mysql-amd64.tar.gz",
)


def fail(message: str) -> None:
    raise SystemExit(f"错误：{message}")


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git(*arguments: str, capture: bool = True) -> str:
    result = subprocess.run(
        ["git", *arguments], cwd=ROOT, text=True,
        stdout=subprocess.PIPE if capture else None,
        check=True,
    )
    return (result.stdout or "").strip()


def parse_env(path: pathlib.Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"{path} 第 {number} 行格式不正确")
        key, value = line.split("=", 1)
        if key in result:
            fail(f"{path} 重复包含 {key}")
        result[key] = value
    return result


def write_env(path: pathlib.Path, values: dict[str, str]) -> None:
    groups = (
        ("COPPER_GUIDED_INSTALLER_VERSION", "COPPER_RELEASE_VERSION", "COPPER_DEPLOY_COMMIT", "COPPER_DEPLOY_ARCHIVE"),
        ("COPPER_WEB_SOURCE_COMMIT", "COPPER_WEB_WORKFLOW_RUN", "COPPER_WEB_SOURCE_IMAGE", "COPPER_WEB_TAG", "COPPER_WEB_IMAGE", "COPPER_WEB_ARCHIVE"),
        ("COPPER_BACKEND_SOURCE_COMMIT", "COPPER_BACKEND_WORKFLOW_RUN", "COPPER_BACKEND_SOURCE_IMAGE", "COPPER_BACKEND_TAG", "COPPER_BACKEND_IMAGE", "COPPER_BACKEND_ARCHIVE"),
        ("COPPER_MYSQL_SOURCE_COMMIT", "COPPER_MYSQL_WORKFLOW_RUN", "COPPER_MYSQL_SOURCE_IMAGE", "COPPER_MYSQL_TAG", "COPPER_MYSQL_IMAGE", "COPPER_MYSQL_ARCHIVE"),
    )
    lines: list[str] = []
    used: set[str] = set()
    for group in groups:
        if lines:
            lines.append("")
        for key in group:
            if key in values:
                lines.append(f"{key}={values[key]}")
                used.add(key)
    for key in sorted(set(values) - used):
        lines.append(f"{key}={values[key]}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def verify_source(source: pathlib.Path) -> None:
    for relative in REQUIRED_SOURCE_PATHS:
        path = source / relative
        if not path.is_file() or path.is_symlink():
            fail(f"来源介质缺少普通文件 {relative}")
    sums = source / "SHA256SUMS"
    if not sums.is_file():
        fail("来源介质缺少 SHA256SUMS")
    for number, raw in enumerate(sums.read_text(encoding="utf-8").splitlines(), 1):
        try:
            expected, relative = raw.split("  ", 1)
        except ValueError:
            fail(f"来源 SHA256SUMS 第 {number} 行格式不正确")
        relative = relative.removeprefix("./")
        if not pathlib.PurePosixPath(relative).parts or ".." in pathlib.PurePosixPath(relative).parts:
            fail("来源 SHA256SUMS 路径不安全")
        path = source / relative
        if not path.is_file() or sha256_file(path) != expected:
            fail(f"来源介质校验失败：{relative}")


def copy_source_assets(source: pathlib.Path, output: pathlib.Path) -> None:
    for relative in REQUIRED_SOURCE_PATHS:
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / relative, target)
    evidence = source / "evidence"
    if evidence.is_dir():
        allowed = {
            "copper-backend-image-bom.json",
            "copper-backend-platform-amd64.json",
            "copper-web-image-bom.json",
            "copper-web-platform-amd64.json",
        }
        for name in sorted(allowed):
            source_file = evidence / name
            if source_file.is_file() and not source_file.is_symlink():
                target = output / "evidence" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source_file, target)


def resolve_imported_image_id(archive: pathlib.Path, reference: str) -> str:
    """Derive the Docker 29 import-stable ID from the archive itself."""
    resolver = ROOT / "scripts/oci-archive-image-id.py"
    result = subprocess.run(
        [sys.executable, str(resolver), str(archive), reference],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "无法解析镜像包").strip()
        fail(f"无法从镜像包推导导入摘要：{archive.name}：{detail}")
    lines = [line for line in result.stdout.splitlines() if line.strip()]
    if len(lines) != 1:
        fail(f"镜像包没有返回唯一导入摘要：{archive.name}")
    fields = lines[0].split("\t")
    if len(fields) != 3 or fields[0] != reference or fields[2] != "amd64":
        fail(f"镜像包返回的引用或架构不一致：{archive.name}")
    digest = fields[1]
    if not digest.startswith("sha256:") or len(digest) != 71:
        fail(f"镜像包返回的导入摘要格式不正确：{archive.name}")
    return digest


def reconcile_image_metadata(
    output: pathlib.Path, values: dict[str, str], manifest: dict[str, Any]
) -> None:
    """Bind runtime IDs to verified OCI manifests instead of producer config IDs."""
    for role in ("web", "backend", "mysql"):
        upper = role.upper()
        archive_value = values.get(f"COPPER_{upper}_ARCHIVE", "")
        reference = values.get(f"COPPER_{upper}_TAG", "")
        if not archive_value or not reference:
            fail(f"来源介质缺少 {role} 镜像包或不可变标签")
        relative = pathlib.PurePosixPath(archive_value)
        if relative.is_absolute() or ".." in relative.parts:
            fail(f"来源介质包含不安全的 {role} 镜像路径")
        archive = output.joinpath(*relative.parts)
        if not archive.is_file() or archive.is_symlink():
            fail(f"来源介质缺少普通文件 {archive_value}")
        imported_id = resolve_imported_image_id(archive, reference)
        values[f"COPPER_{upper}_IMAGE"] = imported_id

        section = manifest.get(role)
        if not isinstance(section, dict):
            fail(f"发布清单缺少 {role} 镜像证据")
        section["importedImageId"] = imported_id

        evidence_path = output / "evidence" / f"copper-{role}-platform-amd64.json"
        if not evidence_path.exists():
            continue
        evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
        if not isinstance(evidence, dict) or evidence.get("role") != role:
            fail(f"{role} 平台证据角色不一致")
        archive_evidence = evidence.get("archive")
        if not isinstance(archive_evidence, dict):
            fail(f"{role} 平台证据缺少镜像包信息")
        if archive_evidence.get("name") != archive.name or archive_evidence.get("sha256") != sha256_file(archive):
            fail(f"{role} 平台证据与实际镜像包不一致")
        # Keep the producer-side localId for provenance and publish the
        # independently derived Docker 29 import ID as a separate fact.
        evidence["importedImageId"] = imported_id
        evidence_path.write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )


def create_deploy_archive(output: pathlib.Path, commit: str) -> pathlib.Path:
    archive = output / f"copper-deploy-{commit}.tar.gz"
    with tempfile.NamedTemporaryFile(suffix=".tar") as raw:
        subprocess.run(["git", "archive", "--format=tar", "HEAD", "-o", raw.name], cwd=ROOT, check=True)
        raw.flush()
        with open(raw.name, "rb") as source, archive.open("wb") as destination:
            with gzip.GzipFile(filename="", mode="wb", fileobj=destination, mtime=0) as compressed:
                shutil.copyfileobj(source, compressed)
    return archive


def start_here(release_version: str) -> str:
    return f"""# Copper 一站式安装器 {INSTALLER_VERSION}

> `INTERNAL_REHEARSAL_ONLY`：本介质只用于内部安装演练，不得交付客户或公开发布。

适用环境：全新 Ubuntu Server 24.04 x86_64，至少 2 CPU、4 GiB 内存、20 GiB 可用磁盘。

## 第 0-5 分钟：准备

1. 一个只供本次测试使用、已指向服务器公网 IPv4 的域名，或直接使用该服务器的固定公网 IPv4；
2. Ubuntu 的公网 IP、登录用户名和 SSH 私钥文件（例如 AWS `.pem`）；
3. 一处 Mac 主私钥目录和一处第二副本目录，推荐把第二份放在 U 盘或外接盘；
4. 云防火墙开放 TCP 80/443 和 UDP 443，不开放 3306、8191、18080。

## 第 5-20 分钟：从 Mac 安装

在本目录运行：

```bash
./copper-install-from-mac
```

它会生成并备份恢复私钥、核对域名/IP、上传介质并安装；连接中断后再次运行会询问是否继续。客户恢复私钥不会上传服务器。安装成功后程序会询问是否立即进入首次登录和数据保护，默认选择继续，不需要记住第二条命令。

上传前会逐张核对服务器是否已经保存完全相同的 Web、Backend、MySQL 运行文件；相同文件直接复用，只上传缺少或发生变化的组件。空白服务器仍会自动上传完整所需文件。

如果介质已经位于 Ubuntu 服务器，也可以在服务器本目录运行：

```bash
sudo ./copper-install
```

安装器会依次检查介质、服务器、磁盘、端口和访问地址，显示完整计划后才写入。域名模式检查 DNS；公网 IPv4 模式核对地址一致并自动启用正式 IP 短期证书及每日续期，不依赖 DNS。IP 模式需要介质 offline/ 中经固定 SHA-256 校验的 ACME 客户端，安装后会持久保存。安全证书未取得时会暂停，不会把不安全站点标记为完成。私网地址和 IPv6 暂不支持；现有地址涉及域名/IP 模式迁移时会安全停止。

## 第 20-25 分钟：首次登录

安装完成后，在 Ubuntu 服务器运行：

```bash
sudo copper-maintain
```

选择“首次管理员登录”。授权管理员本人查看一次性密码后：

1. 打开安装器显示的 HTTPS 登录地址；
2. 登录并按页面要求修改密码；
3. 使用新密码重新登录；
4. 回到维护菜单选择“复查首次登录”，确认一次性密码文件已销毁。

不要截图、发群或把一次性密码复制到聊天。

## 第 25-40 分钟：完成数据保护

回到 Mac 的本介质目录运行：

```bash
./copper-manage-from-mac
```

选择“完成数据保护”。程序会复用安装时保存的站点、SSH 私钥和恢复密钥位置，自动登记交接、创建并下载加密整站备份、核对 SHA-256、离线解密验证、复制到第二位置并启用每日备份。正式交接必须由两名真实人员签署；内部演练身份不会冒充正式签署。

## 日常维护

- Ubuntu 服务器：`sudo copper-maintain`
- Mac：`./copper-manage-from-mac`
- 可选互联网远程访问：在 Mac 菜单选择站点，再选择“互联网远程访问（Cloudflare，可选）”；后续按序号配置、启用、检查、轮换、停用或移除，不复制底层命令。
- 完整导出：登录 Copper 后打开“完整数据导出”，文件名和完整 SHA-256 均可直接复制。

已有站点收到新版本时，不要重新运行安装或覆盖数据。在 Mac 入口选择“升级 Copper”，选择发布方提供的标准离线升级目录或 `.tar.gz`，再粘贴发布方单独提供的 64 位完整校验码。发布方提供组件差异包时，只上传发生变化的 Web、Backend 或 MySQL，未变化组件按服务器不可变摘要复用。程序会断点上传、创建升级前备份、隔离迁移并提示人工切换；确认新版本数据正确后，会自动完成升级后备份、离线验证和第二位置复制。只有这些门槛完成，才允许接受升级并释放旧版本回滚副本。

需要停止使用或迁移机器时，在 Mac 入口选择“停止使用、重新启用或彻底移除站点”：

- 普通卸载只停止服务，完整保留数据库、图片、配置、密钥和备份；
- 彻底移除会先创建新的加密整站备份，下载、离线验证并复制到第二位置；只有服务器登记的最近备份与客户验证回执完全一致，才允许输入站点代号确认删除；
- 成功后自动检查目录、断链、容器、镜像、网络和 systemd 残留，并在 Mac 和第二位置保存卸载回执；Ubuntu、Docker、SSH、DNS、客户私钥及已下载备份不删除。

已经彻底移除后需要在原服务器或另一台全新服务器恢复时，仍运行：

```bash
./copper-manage-from-mac
```

选择“从整站备份恢复已删除的站点”。程序会核对卸载回执、两份恢复私钥、两份整站备份和离线验证回执，自动安装空白站点并先做隔离恢复；正式切换仍要求操作者亲自在服务器提示中输入完整确认文字。恢复成功后空白站点作为回滚点保留，不会被自动删除。

服务器安装状态、继续、同版本修复和换域名仍可使用：

```bash
sudo copper-install status
sudo copper-install continue
sudo copper-install repair
sudo copper-install change-domain
```

`repair` 只修复当前同一产品版本 `{release_version}`，保留数据和随机密钥。不同版本升级仍须使用升级流程；彻底移除只允许从 Mac 数据保护向导进入，普通安装和修复不会删除账套。

## 当前仍需另行配置的正式交付项

- Mac/U 盘复制是按需异机副本；无人值守异机备份仍需客户提供 NAS 或 S3 兼容存储。
- 服务器本机通知不是异机告警；正式交付仍需客户提供 Webhook 接收地址。
- 正式双人交接必须由客户确认人与独立见证人真实签署，安装器不会代签。
"""


def regenerate_sums(output: pathlib.Path) -> None:
    entries: list[str] = []
    for path in sorted(output.rglob("*")):
        if not path.is_file() or path.is_symlink() or path.name == "SHA256SUMS":
            continue
        relative = path.relative_to(output).as_posix()
        entries.append(f"{sha256_file(path)}  ./{relative}")
    (output / "SHA256SUMS").write_text("\n".join(entries) + "\n", encoding="utf-8")


def create_outer_archive(output: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path]:
    archive = output.parent / f"{output.name}.tar.gz"
    with archive.open("wb") as destination:
        with gzip.GzipFile(filename="", mode="wb", fileobj=destination, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w|") as package:
                package.add(output, arcname=output.name, recursive=True)
    sidecar = pathlib.Path(str(archive) + ".sha256")
    sidecar.write_text(f"{sha256_file(archive)}  {archive.name}\n", encoding="utf-8")
    return archive, sidecar


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 Copper 一站式安装内部演练介质")
    parser.add_argument("--source-medium", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--archive", action="store_true", help="同时生成外层 tar.gz 和校验侧车")
    arguments = parser.parse_args()
    source = pathlib.Path(arguments.source_medium).expanduser().resolve()
    output = pathlib.Path(arguments.output).expanduser().resolve()
    if output.exists():
        fail(f"输出目录已存在：{output}")
    if git("status", "--porcelain"):
        fail("工作区不干净；安装介质必须来自已经提交的精确代码")
    commit = git("rev-parse", "HEAD")
    origin_main = git("rev-parse", "refs/remotes/origin/main")
    if commit != origin_main:
        fail("安装介质只能从已同步的 GitHub origin/main 精确提交生成")
    verify_source(source)
    output.mkdir(parents=True, mode=0o755)
    try:
        copy_source_assets(source, output)
        values = parse_env(output / "OPERATOR_VALUES.env")
        release_version = values.get("COPPER_RELEASE_VERSION", "")
        if not release_version:
            fail("来源介质缺少 COPPER_RELEASE_VERSION")
        deploy_archive = create_deploy_archive(output, commit)
        values.update(
            {
                "COPPER_GUIDED_INSTALLER_VERSION": INSTALLER_VERSION,
                "COPPER_DEPLOY_COMMIT": commit,
                "COPPER_DEPLOY_ARCHIVE": deploy_archive.name,
                "COPPER_WEB_ARCHIVE": "images/copper-web-amd64.tar.gz",
                "COPPER_BACKEND_ARCHIVE": "images/copper-backend-amd64.tar.gz",
                "COPPER_MYSQL_ARCHIVE": "images/copper-mysql-amd64.tar.gz",
            }
        )
        shutil.copy2(ROOT / "copper-install", output / "copper-install")
        os.chmod(output / "copper-install", 0o755)
        shutil.copy2(ROOT / "copper-install-from-mac", output / "copper-install-from-mac")
        os.chmod(output / "copper-install-from-mac", 0o755)
        shutil.copy2(ROOT / "copper-manage-from-mac", output / "copper-manage-from-mac")
        os.chmod(output / "copper-manage-from-mac", 0o755)
        shutil.copy2(ROOT / "copper-upgrade-from-mac", output / "copper-upgrade-from-mac")
        os.chmod(output / "copper-upgrade-from-mac", 0o755)
        shutil.copy2(ROOT / "copper-recover-from-mac", output / "copper-recover-from-mac")
        os.chmod(output / "copper-recover-from-mac", 0o755)
        shutil.copy2(ROOT / "copper-key-offline", output / "tools/copper-key-offline")
        os.chmod(output / "tools/copper-key-offline", 0o755)
        (output / "START-HERE.md").write_text(start_here(release_version), encoding="utf-8")
        manifest_path = output / "RELEASE-MANIFEST.json"
        manifest: dict[str, Any] = json.loads(manifest_path.read_text(encoding="utf-8"))
        reconcile_image_metadata(output, values, manifest)
        write_env(output / "OPERATOR_VALUES.env", values)
        manifest["deploy"] = {"repository": "soyia/copper_deploy", "commit": commit}
        manifest["installer"] = {
            "name": "copper-guided-installer",
            "version": INSTALLER_VERSION,
            "entry": "copper-install",
            "capabilities": ["fresh-install", "resume", "same-version-repair", "domain-change", "certificate-wait", "mac-remote-install", "exact-image-cache-reuse", "local-key-backup", "server-maintenance-menu", "mac-data-protection", "guided-remote-access", "offline-restore-rehearsal", "data-preserving-uninstall", "verified-backup-site-removal", "guided-disaster-recovery", "guided-offline-upgrade", "component-delta-upgrade", "post-upgrade-offline-verification", "upgrade-acceptance"],
        }
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        regenerate_sums(output)
        print(f"安装介质：{output}")
        print(f"介质校验：{output / 'SHA256SUMS'}")
        if arguments.archive:
            archive, sidecar = create_outer_archive(output)
            print(f"传输包：{archive}")
            print(f"传输校验：{sidecar}")
    except Exception:
        shutil.rmtree(output, ignore_errors=True)
        raise
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
