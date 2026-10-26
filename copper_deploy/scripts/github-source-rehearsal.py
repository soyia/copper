#!/usr/bin/env python3
"""Exercise a fresh three-repository GitHub source clone for M6-06.

The tool deliberately produces only INTERNAL_REHEARSAL_ONLY evidence.  It does
not make a private repository public, create a release, or claim that an
authenticated internal clone is an anonymous/public reproduction.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
from typing import Any


FORMAT = "copper-github-source-rehearsal/v1"
PURPOSE = "INTERNAL_REHEARSAL_ONLY"
FORMAL_RELEASE_STATUS = "FORMAL_RELEASE_BLOCKED"
FORMAL_BLOCKERS = ("M6-02", "M6-03", "M6-05")
BRANCH = "main"
ROLE_NAMES = ("web", "backend", "deploy")
GITHUB_SOURCES = {
    "web": "https://github.com/soyia/copper_web.git",
    "backend": "https://github.com/soyia/copper_backend.git",
    "deploy": "https://github.com/soyia/copper_deploy.git",
}
COMMIT_RE = re.compile(r"[0-9a-f]{40}")
GITHUB_URL_RE = re.compile(
    r"(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$"
)
TOKEN_RE = re.compile(r"(?:gh[pous]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+)")


class RehearsalError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise RehearsalError(message)


def run_git(*args: str, cwd: pathlib.Path | None = None) -> str:
    result = subprocess.run(
        ["git", *( ["-C", str(cwd)] if cwd else [] ), *args],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode:
        fail(f"Git 命令失败：{' '.join(args[:2])}：{result.stderr.strip()}")
    return result.stdout.strip()


def secure_existing_directory(value: str, label: str) -> pathlib.Path:
    path = pathlib.Path(value)
    if path.is_symlink() or not path.is_dir():
        fail(f"{label} 必须是已有普通目录且不能是符号链接：{path}")
    return path.resolve()


def secure_new_path(value: str, label: str) -> pathlib.Path:
    path = pathlib.Path(value)
    if not path.is_absolute():
        fail(f"{label} 必须为绝对路径：{path}")
    if path.exists() or path.is_symlink():
        fail(f"{label} 已存在，拒绝覆盖：{path}")
    parent = path.parent
    if parent.is_symlink() or not parent.is_dir():
        fail(f"{label} 的父目录必须是已有普通目录：{parent}")
    return path


def secure_existing_file(value: str, label: str) -> pathlib.Path:
    path = pathlib.Path(value)
    if path.is_symlink() or not path.is_file():
        fail(f"{label} 必须是普通文件且不能是符号链接：{path}")
    return path.resolve()


def canonical_github_url(value: str) -> str:
    match = GITHUB_URL_RE.fullmatch(value)
    if not match:
        fail("来源只能是 github.com 的 HTTPS 或 SSH Git URL，且不得包含凭据")
    return f"https://github.com/{match.group(1).lower()}/{match.group(2).lower()}.git"


def canonical_test_url(value: str) -> str:
    if not value.startswith("file://"):
        fail("测试模式来源必须是 file:// URL")
    resolved = pathlib.Path(value.removeprefix("file://")).resolve()
    if not resolved.is_dir() or resolved.is_symlink():
        fail("测试模式 file:// 来源必须是普通目录")
    return f"file://{resolved}"


def parse_test_sources(values: list[str]) -> dict[str, str]:
    sources: dict[str, str] = {}
    for value in values:
        if "=" not in value:
            fail("--repository 必须为 role=URL")
        role, url = value.split("=", 1)
        if role not in ROLE_NAMES or role in sources:
            fail("测试来源角色必须且只能为 web、backend、deploy 各一次")
        sources[role] = canonical_test_url(url)
    if set(sources) != set(ROLE_NAMES):
        fail("测试模式必须为 web、backend、deploy 提供三个来源")
    return sources


def source_specifications(args: argparse.Namespace) -> tuple[dict[str, str], str]:
    values = getattr(args, "repository", [])
    if args.test_mode:
        return parse_test_sources(values), "LOCAL_TEST_ONLY"
    if values:
        fail("正式内部演练不允许覆盖 GitHub 权威来源")
    return ({role: canonical_github_url(url) for role, url in GITHUB_SOURCES.items()}, "AUTHENTICATED_GITHUB_INTERNAL")


def inspect_repository(repo: pathlib.Path, role: str, expected_origin: str, source_mode: str) -> dict[str, str]:
    if run_git("rev-parse", "--is-inside-work-tree", cwd=repo) != "true":
        fail(f"{role} 克隆结果不是 Git 仓库")
    if run_git("symbolic-ref", "--quiet", "--short", "HEAD", cwd=repo) != BRANCH:
        fail(f"{role} 未停留在 {BRANCH} 分支")
    if run_git("status", "--porcelain", cwd=repo):
        fail(f"{role} 克隆后工作区不干净")
    if run_git("remote", cwd=repo).splitlines() != ["origin"]:
        fail(f"{role} 只能保留 origin 一个远程")
    actual_origin = run_git("remote", "get-url", "origin", cwd=repo)
    normalized = canonical_test_url(actual_origin) if source_mode == "LOCAL_TEST_ONLY" else canonical_github_url(actual_origin)
    if normalized != expected_origin:
        fail(f"{role} origin 不等于 GitHub 权威来源")
    if run_git("submodule", "status", cwd=repo):
        fail(f"{role} 不允许含有 Git 子模块")
    if run_git("ls-files", "--stage", "--", ".gitmodules", cwd=repo):
        fail(f"{role} 不允许跟踪 .gitmodules")
    commit = run_git("rev-parse", "HEAD", cwd=repo)
    tree = run_git("rev-parse", "HEAD^{tree}", cwd=repo)
    if not COMMIT_RE.fullmatch(commit) or not COMMIT_RE.fullmatch(tree):
        fail(f"{role} Git 提交或树摘要不正确")
    return {
        "role": role,
        "path": f"sources/copper_{role}",
        "origin": expected_origin,
        "branch": BRANCH,
        "commit": commit,
        "tree": tree,
    }


def reject_credential_text(value: str, label: str) -> None:
    if TOKEN_RE.search(value):
        fail(f"{label} 不能包含 GitHub 凭据")


def canonical_json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def write_new_report(path: pathlib.Path, value: dict[str, Any]) -> None:
    if path.exists() or path.is_symlink():
        fail(f"报告已存在，拒绝覆盖：{path}")
    reject_credential_text(json.dumps(value, ensure_ascii=False), "报告")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(canonical_json(value))


def clone(args: argparse.Namespace) -> None:
    workspace = secure_new_path(args.workspace, "工作目录")
    report = secure_new_path(args.report, "报告")
    sources, source_mode = source_specifications(args)
    workspace.mkdir(mode=0o700)
    records: list[dict[str, str]] = []
    try:
        sources_root = workspace / "sources"
        sources_root.mkdir(mode=0o700)
        for role in ROLE_NAMES:
            target = sources_root / f"copper_{role}"
            run_git("clone", "--no-tags", "--single-branch", "--branch", BRANCH, sources[role], str(target))
            records.append(inspect_repository(target, role, sources[role], source_mode))
        report_value: dict[str, Any] = {
            "format": FORMAT,
            "purpose": PURPOSE,
            "formalRelease": FORMAL_RELEASE_STATUS,
            "formalBlockers": list(FORMAL_BLOCKERS),
            "sourceMode": source_mode,
            "branch": BRANCH,
            "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
            "repositories": records,
        }
        write_new_report(report, report_value)
    except Exception:
        shutil.rmtree(workspace, ignore_errors=True)
        raise
    print(canonical_json(report_value).decode("utf-8"), end="")


def load_report(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(secure_existing_file(str(path), "报告").read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail(f"报告不是合法 JSON：{error}")
    if not isinstance(value, dict):
        fail("报告根节点必须为对象")
    reject_credential_text(json.dumps(value, ensure_ascii=False), "报告")
    return value


def verify(args: argparse.Namespace) -> None:
    workspace = secure_existing_directory(args.workspace, "工作目录")
    report = load_report(pathlib.Path(args.report))
    sources, source_mode = source_specifications(args)
    if report.get("format") != FORMAT or report.get("purpose") != PURPOSE:
        fail("报告格式或内部演练声明不正确")
    if report.get("formalRelease") != FORMAL_RELEASE_STATUS or report.get("formalBlockers") != list(FORMAL_BLOCKERS):
        fail("报告正式发布阻断状态不正确")
    if report.get("sourceMode") != source_mode or report.get("branch") != BRANCH:
        fail("报告来源模式或分支不正确")
    records = report.get("repositories")
    if not isinstance(records, list) or len(records) != len(ROLE_NAMES):
        fail("报告仓库记录不完整")
    by_role = {item.get("role"): item for item in records if isinstance(item, dict)}
    if set(by_role) != set(ROLE_NAMES):
        fail("报告仓库角色不正确")
    verified: list[dict[str, str]] = []
    for role in ROLE_NAMES:
        current = inspect_repository(workspace / "sources" / f"copper_{role}", role, sources[role], source_mode)
        recorded = by_role[role]
        if any(recorded.get(key) != current[key] for key in ("role", "path", "origin", "branch", "commit", "tree")):
            fail(f"{role} 当前克隆与报告不一致")
        verified.append(current)
    print(canonical_json({
        "format": FORMAT,
        "purpose": PURPOSE,
        "formalRelease": FORMAL_RELEASE_STATUS,
        "sourceMode": source_mode,
        "repositories": verified,
        "verification": "PASS",
    }).decode("utf-8"), end="")


def main() -> int:
    parser = argparse.ArgumentParser(description="Copper M6-06 GitHub source rehearsal")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("clone", "verify"):
        command = commands.add_parser(name)
        command.add_argument("--workspace", required=True)
        command.add_argument("--report", required=True)
        command.add_argument("--test-mode", action="store_true")
        command.add_argument("--repository", action="append", default=[], metavar="ROLE=URL")
    args = parser.parse_args()
    try:
        if args.command == "clone":
            clone(args)
        else:
            verify(args)
    except RehearsalError as error:
        print(f"M6-06 GitHub source rehearsal BLOCK: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
