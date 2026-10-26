#!/usr/bin/env python3
"""Bridge verified rc.11 local build/package bytes to the existing guided medium.

This script never invokes Docker or publishes a release. A generated medium still
requires installation and full-site recovery acceptance on the authorized host.
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
from pathlib import Path, PurePosixPath
import re
import runpy
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.4.0-rc.11"
CANDIDATE_DOCKER_MANIFEST = ROOT / "releases" / VERSION / "docker-ubuntu-amd64.env"
ROLES = ("web", "backend", "mysql")
HASH = re.compile(r"[0-9a-f]{64}")
SHA = re.compile(r"[0-9a-f]{40}")
GUIDED = runpy.run_path(str(ROOT / "scripts/build-guided-installer-medium.py"))
OCI = runpy.run_path(str(ROOT / "scripts/oci-archive-image-id.py"))
RELEASE = runpy.run_path(str(ROOT / "scripts/release-contract.py"))
SECURITY = runpy.run_path(str(ROOT / "scripts/security-evidence.py"))
DELIVERY = runpy.run_path(str(ROOT / "scripts/delivery-contract.py"))


def read_json(path):
    return json.loads(Path(path).read_text())


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def sha256(path):
    return GUIDED["sha256_file"](Path(path))


def safe_relative(name):
    value = PurePosixPath(name)
    if not isinstance(name, str) or not name or value.is_absolute() or ".." in value.parts or value.as_posix() != name or "\\" in name:
        raise ValueError("inventory path is not a canonical relative path")
    return value


def files(root):
    found = set()
    for path in root.rglob("*"):
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise ValueError("candidate contains links or special files")
        if path.is_file():
            found.add(path.relative_to(root).as_posix())
    return found


def closed_inventory(directory, inventory):
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("candidate directory must be a real directory")
    root = directory.resolve()
    actual = files(root)
    sums = read_json(root / inventory)
    if not isinstance(sums, dict) or not sums or inventory in sums:
        raise ValueError("candidate checksum inventory is invalid or self-referential")
    for name, digest in sums.items():
        path = root.joinpath(*safe_relative(name).parts)
        if not isinstance(digest, str) or not HASH.fullmatch(digest) or name not in actual or sha256(path) != digest:
            raise ValueError("candidate checksum mismatch: " + name)
    if actual != set(sums) | {inventory}:
        raise ValueError("candidate file inventory is not closed")
    return root, sums


def validate_docker_bundle(directory, reviewed_manifest):
    root = Path(directory)
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Docker bundle must be a real directory")
    root = root.resolve()
    actual = files(root)
    sums = {}
    for line in (root / "SHA256SUMS").read_text().splitlines():
        fields = line.split("  ")
        if len(fields) != 2 or not HASH.fullmatch(fields[0]):
            raise ValueError("Docker SHA256SUMS is malformed")
        name = fields[1].removeprefix("./")
        safe_relative(name)
        if name in sums:
            raise ValueError("Docker SHA256SUMS contains duplicate paths")
        sums[name] = fields[0]
    manifest = root / "docker-ubuntu-amd64.env"
    if sha256(manifest) != sha256(reviewed_manifest):
        raise ValueError("Docker manifest differs from the reviewed manifest")
    values = GUIDED["parse_env"](manifest)
    if values.get("COPPER_DOCKER_TARGET_OS") != "ubuntu-24.04" or values.get("COPPER_DOCKER_TARGET_ARCH") != "amd64" or not values.get("COPPER_DOCKER_ENGINE_VERSION", "").startswith("29.") or not values.get("COPPER_DOCKER_COMPOSE_VERSION", "").startswith("5."):
        raise ValueError("Docker bundle platform or version is unsupported")
    expected = {"docker-ubuntu-amd64.env"}
    for index in range(1, 6):
        prefix = f"COPPER_DOCKER_PACKAGE_{index}_"
        name, digest = values.get(prefix + "NAME", ""), values.get(prefix + "SHA256", "")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+:~_-]*_amd64\.deb", name) or not HASH.fullmatch(digest) or sums.get(name) != digest or not values.get(prefix + "URL", "").startswith("https://download.docker.com/linux/ubuntu/"):
            raise ValueError("Docker package is not bound to the reviewed manifest")
        expected.add(name)
    if set(sums) != expected or actual != expected | {"SHA256SUMS"}:
        raise ValueError("Docker bundle is incomplete or its file inventory is not closed")
    for name, digest in sums.items():
        if sha256(root / name) != digest:
            raise ValueError("Docker package checksum mismatch: " + name)
    return root


def validate_candidates(inputs_path, package_path):
    inputs, _ = closed_inventory(inputs_path, "INPUT_CHECKSUMS.json")
    package, _ = closed_inventory(package_path, "PACKAGE_CHECKSUMS.json")
    doc = read_json(inputs / "BUILD_INPUTS.json")
    status = read_json(package / "PACKAGE_STATUS.json")
    if doc.get("format") != "copper-release-build-inputs/v1" or doc.get("version") != VERSION or doc.get("status") != "REAL_IMAGES_BUILT_PACKAGE_PENDING" or set(doc.get("images", {})) != set(ROLES) or not re.fullmatch(r"[0-9a-f]{12}", doc.get("runId", "")) or not SHA.fullmatch(doc.get("deployBuildCommit", "")):
        raise ValueError("requires exact rc.11 real local build inputs")
    if status.get("format") != "copper-offline-candidate/v1" or status.get("version") != VERSION or status.get("status") != "PACKAGED_INSTALL_RESTORE_ACCEPTANCE_PENDING" or not SHA.fullmatch(status.get("deployCommit", "")) or status.get("publishedToGHCR") is not False or status.get("publicReleaseCreated") is not False:
        raise ValueError("requires a successful unpublished rc.11 package")
    if status.get("buildInputsSha256") != sha256(inputs / "BUILD_INPUTS.json") or status.get("inputChecksumsSha256") != sha256(inputs / "INPUT_CHECKSUMS.json") or status.get("deployBuildCommit") != doc["deployBuildCommit"]:
        raise ValueError("package is not bound to this frozen build input set")
    for path in (inputs / "BUILD_CLEANUP.json", package / "PACKAGE_CLEANUP.json"):
        state = read_json(path)
        if state.get("cleanup") != "PASS" or state.get("runId") != doc["runId"]:
            raise ValueError("candidate resource cleanup is not verified")
    manifest = package / "release" / ("copper-release-" + VERSION + ".env")
    _, values, _, matrix = RELEASE["load_manifest"](manifest, ROOT / "config/release-manifest-v1.fields")
    if values["COPPER_RELEASE_VERSION"] != VERSION or values["COPPER_DEPLOY_COMMIT"] != status["deployCommit"] or matrix["sources"] or doc.get("directUpgradeSources") != [] or status.get("directUpgradeSources") != []:
        raise ValueError("candidate release identity or supported upgrade sources differ")
    offline_index = package / "offline" / ("copper-delivery-offline-" + VERSION + ".json")
    _, offline = DELIVERY["validate_index"](offline_index)
    if offline.get("version") != VERSION or offline.get("mode") != "offline" or offline["manifest"]["sha256"] != sha256(manifest):
        raise ValueError("offline package differs from the secure release manifest")
    offline_images = {row["role"]: row for row in offline["images"]}
    if set(offline_images) != set(ROLES):
        raise ValueError("offline package requires exactly three current images")
    if sha256(inputs / "release/migrations.sha256") != values["COPPER_MIGRATION_SET_SHA256"]:
        raise ValueError("migration inventory differs from the secure package")
    security = package / "release" / ("copper-security-" + VERSION + ".tar.gz")
    sidecar = Path(str(security) + ".sha256")
    if not security.is_file() or sidecar.read_text().split() != [sha256(security), security.name]:
        raise ValueError("current candidate security evidence is missing or mismatched")
    security_evidence = SECURITY["verify_archive"](security)
    security_release = security_evidence["release"]
    expected_security = {"version": VERSION, "deployCommit": status["deployCommit"], "webSourceCommit": values["COPPER_WEB_SOURCE_COMMIT"], "backendSourceCommit": values["COPPER_BACKEND_SOURCE_COMMIT"], "webImage": values["COPPER_WEB_IMAGE"], "backendImage": values["COPPER_BACKEND_IMAGE"], "mysqlImage": values["COPPER_MYSQL_IMAGE"]}
    if any(security_release.get(k) != v for k, v in expected_security.items()) or security_release["manifest"]["sha256"] != sha256(manifest):
        raise ValueError("security evidence belongs to a different candidate")
    acme = inputs / "release" / values["COPPER_ACME_ARCHIVE"]
    if sha256(acme) != values["COPPER_ACME_ARCHIVE_SHA256"]:
        raise ValueError("ACME bytes are not bound to this package")
    for role in ROLES:
        record = doc["images"][role]
        upper = role.upper()
        if record.get("archive") != f"images/copper-{role}-amd64.tar.gz" or record.get("provenanceFile") != role + "-local-build.json" or not SHA.fullmatch(record.get("sourceCommit", "")):
            raise ValueError("candidate image path or source commit is invalid")
        if offline_images[role]["reference"] != record["reference"] or offline_images[role]["localId"] != record["imageId"]:
            raise ValueError("offline package image differs from actual build inputs")
        archive = inputs / record["archive"]
        provenance_file = inputs / "release" / record["provenanceFile"]
        if sha256(archive) != record.get("archiveSha256") or sha256(provenance_file) != record.get("provenanceSha256"):
            raise ValueError("image/provenance hash binding differs")
        provenance = read_json(provenance_file)
        expected = {"format": "copper-local-build/v1", "role": role, "sourceCommit": record["sourceCommit"], "buildId": doc["runId"], "image": record["reference"], "imageId": record["imageId"], "platform": "linux/amd64"}
        if any(provenance.get(k) != v for k, v in expected.items()):
            raise ValueError("local build provenance is not bound to its actual image")
        if values["COPPER_" + upper + "_IMAGE"] != record["reference"] or (role != "mysql" and values["COPPER_" + upper + "_SOURCE_COMMIT"] != record["sourceCommit"]):
            raise ValueError("package uses different image/source bytes from build inputs")
        if role == "mysql" and record["sourceCommit"] != doc["deployBuildCommit"]:
            raise ValueError("MySQL image source differs from Deploy build commit")
        rows = OCI["resolve"](archive, [record["localTag"]])
        if rows != [(record["localTag"], record["imageId"], "amd64")] or record.get("importedImageId") != record["imageId"]:
            raise ValueError("archive import identity differs from actual build evidence")
        with tarfile.open(archive, "r:*") as image:
            _, manifest_bytes = OCI["verified_blob"](image, record["imageId"], OCI["MAX_MANIFEST_BYTES"], role + " manifest")
            image_manifest = json.loads(manifest_bytes)
            _, config_bytes = OCI["verified_blob"](image, image_manifest["config"]["digest"], OCI["MAX_CONFIG_BYTES"], role + " config")
        config = json.loads(config_bytes)
        labels = config.get("config", {}).get("Labels") or {}
        if config.get("os") != "linux" or labels.get("org.opencontainers.image.revision") != record["sourceCommit"] or labels.get("copper.release.run_id") != doc["runId"]:
            raise ValueError("archive labels/platform differ from exact source/run")
        if role != "mysql" and sha256(package / "release" / record["provenanceFile"]) != record["provenanceSha256"]:
            raise ValueError("packaged local provenance differs from build inputs")
    return inputs, package, doc, status, values


def run_guided(source, output):
    subprocess.run([sys.executable, str(ROOT / "scripts/build-guided-installer-medium.py"), "--source-medium", str(source), "--output", str(output)], check=True)


def candidate_docker_manifest(path):
    supplied = Path(path)
    if supplied.is_symlink() or not supplied.is_file() or sha256(supplied) != sha256(CANDIDATE_DOCKER_MANIFEST):
        raise ValueError("Docker manifest must match this candidate's reviewed runtime packages; older bundles are not accepted")
    return supplied.resolve()


def build(args):
    reviewed_manifest = candidate_docker_manifest(args.docker_manifest)
    inputs, package, doc, status, release = validate_candidates(args.inputs, args.package)
    bundle = validate_docker_bundle(args.docker_bundle, reviewed_manifest)
    commit = GUIDED["git"]("rev-parse", "HEAD")
    if commit != status["deployCommit"] or GUIDED["git"]("status", "--porcelain") or commit != GUIDED["git"]("rev-parse", "refs/remotes/origin/main"):
        raise ValueError("requires the same clean reviewed origin/main used by the secure package")
    output = Path(args.output).absolute()
    if output.exists() or output.is_symlink() or (args.archive and (Path(str(output) + ".tar.gz").exists() or Path(str(output) + ".tar.gz.sha256").exists())):
        raise ValueError("candidate output already exists; never overwrite frozen bytes")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".copper-guided-candidate-", dir=output.parent) as temporary:
        work = Path(temporary)
        source = work / "source"
        (source / "images").mkdir(parents=True)
        (source / "tools").mkdir()
        values = {"COPPER_RELEASE_VERSION": VERSION, "COPPER_DEPLOY_COMMIT": commit}
        manifest = {"format": "copper-m7-zero-support-medium/v1", "status": "INTERNAL_REHEARSAL_ONLY", "version": VERSION, "platform": "linux/amd64", "deploy": {"repository": "soyia/copper_deploy", "commit": commit}}
        for role, record in doc["images"].items():
            shutil.copy2(inputs / record["archive"], source / record["archive"])
            for field, value in {"SOURCE_COMMIT": record["sourceCommit"], "SOURCE_IMAGE": record["reference"], "TAG": record["localTag"], "IMAGE": record["importedImageId"], "ARCHIVE": record["archive"], "BUILD_PROVENANCE_FILE": "evidence/build-inputs/" + record["provenanceFile"], "BUILD_PROVENANCE_SHA256": record["provenanceSha256"]}.items():
                values["COPPER_" + role.upper() + "_" + field] = value
            manifest[role] = dict(record)
        GUIDED["write_env"](source / "OPERATOR_VALUES.env", values)
        write_json(source / "RELEASE-MANIFEST.json", manifest)
        (source / "INTERNAL_REHEARSAL_ONLY.txt").write_text("INTERNAL_REHEARSAL_ONLY\nINSTALL_RESTORE_ACCEPTANCE_PENDING\nNo public release or customer delivery.\n")
        shutil.copy2(ROOT / "copper-key-offline", source / "tools/copper-key-offline")
        GUIDED["regenerate_sums"](source)
        medium = work / "medium"
        run_guided(source, medium)
        shutil.copytree(package / "release", medium / "evidence/candidate-release")
        # The existing guided verifier reserves every basename SHA256SUMS. Keep
        # the original release list as source evidence under a distinct name.
        (medium / "evidence/candidate-release/SHA256SUMS").rename(medium / "evidence/candidate-release/SOURCE_RELEASE_SHA256SUMS")
        evidence = medium / "evidence/build-inputs"
        evidence.mkdir(parents=True)
        for name in ("BUILD_INPUTS.json", "BUILD_CLEANUP.json", "INPUT_CHECKSUMS.json"):
            shutil.copy2(inputs / name, evidence / name)
        for record in doc["images"].values():
            shutil.copy2(inputs / "release" / record["provenanceFile"], evidence / record["provenanceFile"])
        shutil.copy2(inputs / "release/migrations.sha256", evidence / "migrations.sha256")
        for name in ("PACKAGE_STATUS.json", "PACKAGE_CLEANUP.json", "PACKAGE_CHECKSUMS.json"):
            shutil.copy2(package / name, medium / "evidence" / name)
        (medium / "offline").mkdir()
        # Bundle its own SHA256SUMS inside a hashed archive so the outer guided
        # inventory covers every byte without a second reserved checksum name.
        with (medium / "offline/docker-ubuntu-amd64.tar.gz").open("wb") as target:
            with gzip.GzipFile(filename="", mode="wb", fileobj=target, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w|") as archive:
                    archive.add(bundle, arcname="docker-ubuntu-amd64")
        shutil.copy2(inputs / "release" / release["COPPER_ACME_ARCHIVE"], medium / "offline" / release["COPPER_ACME_ARCHIVE"])
        write_json(medium / "GUIDED_CANDIDATE_STATUS.json", {"format": "copper-guided-candidate/v1", "version": VERSION, "scope": "INTERNAL_REHEARSAL_ONLY", "status": "INSTALL_RESTORE_ACCEPTANCE_PENDING", "deployCommit": commit, "buildInputsInventorySha256": sha256(inputs / "INPUT_CHECKSUMS.json"), "packageInventorySha256": sha256(package / "PACKAGE_CHECKSUMS.json"), "dockerBundleInventorySha256": sha256(bundle / "SHA256SUMS"), "images": doc["images"], "networklessHostInstallationAccepted": False, "publicReleaseCreated": False, "directUpgradeSources": []})
        with (medium / "START-HERE.md").open("a") as guide:
            guide.write("\n## 本轮候选证据与依赖\n\n本轮 rc.11 介质仅供内部演练，真实安装、整站备份恢复与新备份验收尚未完成。\n当前安全包与发布清单在 `evidence/candidate-release/`，本地构建来源在 `evidence/build-inputs/`。\n最终介质一律使用根目录 SHA256SUMS 校验；保留的 INPUT_CHECKSUMS/PACKAGE_CHECKSUMS/SOURCE_RELEASE_SHA256SUMS 只是原目录布局的来源旁证，复验这些清单须使用原产物目录。\nDocker 官方离线包在 `offline/docker-ubuntu-amd64.tar.gz`，先解压，再由部署目录的 `scripts/install-offline-ubuntu.sh` 校验安装；\nACME 归档在 `offline/`。现有引导安装仍会访问 Ubuntu 软件源及证书服务；本包不宣称完全断网安装。\n")
        GUIDED["regenerate_sums"](medium)
        # Verify the final closed inventory without importing images or starting Docker.
        installer = runpy.run_path(str(ROOT / "copper-install"))
        installer["require_media_values"](GUIDED["parse_env"](medium / "OPERATOR_VALUES.env"))
        installer["verify_media"](medium, GUIDED["parse_env"](medium / "OPERATOR_VALUES.env"))
        listed = installer["parse_sha256_sums"](medium / "SHA256SUMS", medium)
        if set(listed) != {medium / name for name in files(medium) - {"SHA256SUMS"}}:
            raise ValueError("final guided inventory omitted a file")
        medium.rename(output)
    if args.archive:
        try:
            GUIDED["create_outer_archive"](output)
        except BaseException:
            # A partial archive is never an accepted frozen artifact.
            Path(str(output) + ".tar.gz").unlink(missing_ok=True)
            Path(str(output) + ".tar.gz.sha256").unlink(missing_ok=True)
            shutil.rmtree(output)
            raise
    print(json.dumps({"status": "INSTALL_RESTORE_ACCEPTANCE_PENDING", "output": str(output), "actualDockerUsed": False}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("inputs", "package", "docker-bundle", "output"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--docker-manifest", default=str(CANDIDATE_DOCKER_MANIFEST))
    parser.add_argument("--archive", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    def interrupted(*_):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    try:
        build(args)
    except KeyboardInterrupt:
        parser.exit(130, "Guided candidate bridge stopped: interrupted; no acceptance is claimed\n")
    except (ValueError, OSError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Guided candidate bridge stopped: {error}\n")


if __name__ == "__main__":
    main()
