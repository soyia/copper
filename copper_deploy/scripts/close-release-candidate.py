#!/usr/bin/env python3
"""Build real amd64 inputs, stage them for review, then package an offline RC.

Run Docker commands only on the authorized Ubuntu 24.04 amd64 release factory.
No Git push/tag, GHCR publication, customer deployment or local Mac Docker.
"""
from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import signal
import socket
import subprocess
import tarfile
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.4.0-rc.11"
SHA = re.compile(r"[0-9a-f]{40}")
HASH = re.compile(r"[0-9a-f]{64}")
IMAGE = re.compile(r"[^\s@]+@sha256:[0-9a-f]{64}")


def run(argv, *, cwd=None, output=None, check=True):
    if output:
        with Path(output).open("wb") as stream:
            result = subprocess.run([str(v) for v in argv], cwd=cwd, stdout=stream, stderr=subprocess.PIPE)
    else:
        result = subprocess.run([str(v) for v in argv], cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode:
        error = result.stderr.decode(errors="replace") if isinstance(result.stderr, bytes) else result.stderr
        raise RuntimeError(f"{argv[0]} failed: {error[-3000:]}")
    return result


def sha256(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def write_json(path, document):
    Path(path).write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")


def git(path, *args):
    return run(["git", "-C", path, *args]).stdout.strip()


def checkout(path, expected):
    path = Path(path).resolve()
    if not SHA.fullmatch(expected) or not (path / ".git").is_dir():
        raise ValueError("use an independent exact Git checkout and full commit SHA")
    if git(path, "rev-parse", "HEAD") != expected or git(path, "status", "--porcelain"):
        raise ValueError("checkout is dirty or not the requested commit")
    return path


def reviewed_main(path, expected, role):
    remote = git(path, "remote", "get-url", "origin")
    repository = {"web": "copper_web", "backend": "copper_backend", "deploy": "copper_deploy"}[role]
    if not re.fullmatch(r"(?:git@github\.com:|https://github\.com/)soyia/" + repository + r"(?:\.git)?", remote):
        raise ValueError("origin must be the exact " + role + " Copper GitHub repository")
    actual = git(path, "ls-remote", "--exit-code", "origin", "refs/heads/main").split()
    if actual != [expected, "refs/heads/main"] or git(path, "rev-parse", "origin/main") != expected:
        raise ValueError("reviewed source differs from current GitHub main; fetch and review before retrying")


def platform():
    release = dict(line.split("=", 1) for line in Path("/etc/os-release").read_text().splitlines() if "=" in line) if Path("/etc/os-release").exists() else {}
    if os.uname().machine != "x86_64" or release.get("ID", "").strip('"') != "ubuntu" or release.get("VERSION_ID", "").strip('"') != "24.04":
        raise ValueError("Docker build/package requires authorized Ubuntu 24.04 x86_64; never start Mac Docker")
    if any(os.environ.get(name) for name in ("DOCKER_HOST", "DOCKER_CONTEXT", "BUILDX_BUILDER", "BUILDKIT_HOST")):
        raise ValueError("unset Docker/BuildKit endpoint overrides on the dedicated factory")
    context = json.loads(run(["docker", "context", "inspect"]).stdout)
    if len(context) != 1 or context[0].get("Endpoints", {}).get("docker", {}).get("Host") != "unix:///var/run/docker.sock":
        raise ValueError("release factory requires its local system Docker socket")
    server = run(["docker", "version", "--format", "{{.Server.Version}}|{{.Server.Arch}}"] ).stdout.strip().split("|")
    if len(server) != 2 or not server[0].startswith("29.") or server[1] not in {"amd64", "x86_64"}:
        raise ValueError("requires actual Docker Engine 29 amd64")
    if not run(["docker", "compose", "version", "--short"]).stdout.strip().lstrip("v").startswith("5."):
        raise ValueError("requires Compose Plugin 5.x")
    if "containerd" not in run(["docker", "info", "--format", "{{json .DriverStatus}}"] ).stdout:
        raise ValueError("requires Docker 29 containerd image store; do not change an existing daemon")
    if run(["ss", "-H", "-ltn", "sport = :8191"]).stdout.strip():
        raise ValueError("release factory must not own a host 8191 listener")
    return server[0]


def inspect(reference):
    document = json.loads(run(["docker", "image", "inspect", reference]).stdout)
    if not isinstance(document, list) or len(document) != 1:
        raise ValueError("image identity is ambiguous")
    return document[0]


def image_identity(document, *, repository, commit, run_id):
    labels = document.get("Config", {}).get("Labels") or {}
    references = [v for v in document.get("RepoDigests", []) if v.startswith(repository + "@sha256:")]
    if document.get("Architecture") != "amd64" or document.get("Os") != "linux" or labels.get("org.opencontainers.image.revision") != commit or labels.get("copper.release.run_id") != run_id or len(references) != 1 or not IMAGE.fullmatch(references[0]):
        raise ValueError("actual image platform, revision, ownership or RepoDigest does not match")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", document.get("Id", "")):
        raise ValueError("actual Docker image ID is invalid")
    return references[0], document["Id"]


def optional_inspect(argv, *missing):
    result = run(argv, check=False)
    if result.returncode == 0:
        return result
    if any(message in result.stderr for message in missing):
        return None
    raise RuntimeError("cannot establish resource identity: " + result.stderr[-1000:])


def clean_owned(state):
    """Remove only this run's resources; unknown state always fails closed."""
    state["cleanup"] = "FAILED"
    run_id = state.get("runId", "")
    if not re.fullmatch(r"[0-9a-f]{12}", run_id):
        raise ValueError("cleanup state has an invalid run ID")
    registry = "copper-closure-registry-" + run_id
    if state.get("registry") != registry or state.get("builder") != "copper-closure-" + run_id:
        raise ValueError("cleanup state names are outside this run")
    run(["docker", "info", "--format", "{{.ServerVersion}}"])
    observed = optional_inspect(["docker", "inspect", registry], "No such object", "No such container")
    if observed is not None:
        container = json.loads(observed.stdout)[0]
        if (container.get("Config", {}).get("Labels") or {}).get("copper.release.run_id") != run_id:
            raise ValueError("refuse to remove a registry not owned by this run")
        run(["docker", "rm", "-f", "-v", registry])
    builder = run(["docker", "buildx", "inspect", state["builder"]], check=False)
    if builder.returncode == 0:
        text = builder.stdout
        if (not re.search(r"^Name:\s*" + re.escape(state["builder"]) + r"\s*$", text, re.M)
                or not re.search(r"^Driver:\s*docker-container\s*$", text, re.M)
                or 'env.COPPER_RELEASE_RUN_ID="' + run_id + '"' not in text
                or "Error:" in text):
            raise ValueError("refuse to remove a builder without its driver ownership marker")
        run(["docker", "buildx", "rm", "--force", state["builder"]])
    elif not re.search(r"(no builder .* found|no builder .* exists)", builder.stderr, re.I):
        raise RuntimeError("cannot establish whether the owned builder exists: " + builder.stderr[-1000:])
    for tag in state.get("tags", []):
        if not re.fullmatch(r"127\.0\.0\.1:[0-9]+/copper-(web|backend|mysql):" + run_id, tag):
            raise ValueError("cleanup image name is outside this run")
        result = optional_inspect(["docker", "image", "inspect", tag], "No such image", "No such object")
        if result is None:
            continue
        image = json.loads(result.stdout)[0]
        if (image.get("Config", {}).get("Labels") or {}).get("copper.release.run_id") != run_id:
            raise ValueError("refuse to remove an image not owned by this run")
        run(["docker", "image", "rm", "-f", image["Id"]])
    state["cleanup"] = "PASS"


def registry_start(state, image, address=None):
    if address is None:
        # Reserve an explicit candidate so Docker cannot choose host 8191.
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        if port == 8191:
            raise ValueError("refuse reserved port 8191")
        address = "127.0.0.1:" + str(port)
    if not re.fullmatch(r"127\.0\.0\.1:([0-9]+)", address) or not 1024 <= int(address.split(":")[1]) <= 65535 or address.endswith(":8191"):
        raise ValueError("unsafe loopback registry address")
    run(["docker", "pull", image])
    # A race or occupied port fails without stopping the existing owner.
    run(["docker", "run", "-d", "--name", state["registry"], "--label", "copper.release.run_id=" + state["runId"], "--publish", address + ":5000", image])
    observed = run(["docker", "port", state["registry"], "5000/tcp"]).stdout.strip()
    if observed != address:
        raise ValueError("actual registry binding differs from the requested loopback address")
    return address


def import_image(archive, record, run_id):
    run(["docker", "load", "--input", archive])
    actual = inspect(record["localTag"])
    if actual.get("Id") != record["importedImageId"]:
        raise ValueError("Docker 29 loaded image ID differs from verified OCI manifest ID")
    run(["docker", "push", record["localTag"]])
    reference, image_id = image_identity(inspect(record["localTag"]), repository=record["localTag"].rsplit(":", 1)[0], commit=record["sourceCommit"], run_id=run_id)
    if reference != record["reference"] or image_id != record["importedImageId"]:
        raise ValueError("imported image differs from the built archive/digest")
    return image_id


def make_archive(tag, output, work):
    raw = work / "image.tar"
    run(["docker", "save", "--platform", "linux/amd64", tag], output=raw)
    with raw.open("rb") as source, Path(output).open("wb") as target:
        with gzip.GzipFile(filename="", mode="wb", fileobj=target, mtime=0) as compressed:
            shutil.copyfileobj(source, compressed)
    raw.unlink()
    row = run(["python3", ROOT / "scripts/oci-archive-image-id.py", output, tag]).stdout.strip().split("\t")
    if len(row) != 3 or row[0] != tag or row[2] != "amd64":
        raise ValueError("saved OCI archive did not resolve to one amd64 Docker 29 image")
    return row[1]


def candidate_matrix(schema):
    return {"format": "copper-upgrade-matrix/v1", "targetVersion": VERSION, "targetSchema": str(schema), "sources": [], "freshInstallOnly": True}


def build_inputs(args):
    docker_version = platform()
    repos = {role: checkout(getattr(args, role + "_repo"), getattr(args, role + "_commit")) for role in ("web", "backend", "deploy")}
    if ROOT.resolve() != repos["deploy"]:
        raise ValueError("execute the closure script from the exact reviewed Deploy checkout")
    for role, repo in repos.items():
        reviewed_main(repo, getattr(args, role + "_commit"), role)
    if args.version != VERSION or not IMAGE.fullmatch(args.registry_image) or not IMAGE.fullmatch(args.buildkit_image):
        raise ValueError("rc.11 requires pinned registry and BuildKit tool image digests")
    output = Path(args.output).resolve()
    if output.exists():
        raise ValueError("output already exists; preserve successful inputs or use cleanup for an interrupted state")
    acme = Path(args.acme_archive).resolve()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,191}\.tar\.gz", acme.name) or not HASH.fullmatch(args.acme_sha256) or not acme.is_file() or acme.is_symlink() or sha256(acme) != args.acme_sha256:
        raise ValueError("actual ACME archive is missing or does not match its reviewed digest")
    output.parent.mkdir(parents=True, exist_ok=True)
    run_id = uuid.uuid4().hex[:12]
    state_path = output.parent / ("closure-state-" + run_id + ".json")
    state = {"format": "copper-release-build-state/v1", "runId": run_id, "registry": "copper-closure-registry-" + run_id, "builder": "copper-closure-" + run_id, "tags": []}
    write_json(state_path, state)
    os.chmod(state_path, 0o600)
    with tempfile.TemporaryDirectory(prefix=".copper-closure-" + run_id + "-", dir=output.parent) as temporary:
        work = Path(temporary)
        result = work / "result"
        (result / "release").mkdir(parents=True)
        (result / "images").mkdir()
        (result / "preloaded").mkdir()
        built_at = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        records = {}
        try:
            address = registry_start(state, args.registry_image)
            run(["docker", "buildx", "create", "--name", state["builder"], "--driver", "docker-container", "--driver-opt", "image=" + args.buildkit_image, "--driver-opt", "env.COPPER_RELEASE_RUN_ID=" + run_id])
            run(["docker", "buildx", "inspect", "--bootstrap", state["builder"]])
            for role in ("web", "backend", "mysql"):
                repo = repos["deploy" if role == "mysql" else role]
                commit = git(repo, "rev-parse", "HEAD")
                context = work / (role + "-source")
                context.mkdir()
                archive = work / (role + ".tar")
                run(["git", "-C", repo, "archive", "--format=tar", "HEAD"], output=archive)
                with tarfile.open(archive) as package:
                    for member in package.getmembers():
                        if member.issym() or member.islnk() or not (member.isfile() or member.isdir()) or Path(member.name).is_absolute() or ".." in Path(member.name).parts:
                            raise ValueError("Git build archive contains an unsafe path or link")
                    package.extractall(context)
                repository = address + "/copper-" + role
                tag = repository + ":" + run_id
                state["tags"].append(tag)
                write_json(state_path, state)
                dockerfile = "images/mysql/Dockerfile" if role == "mysql" else "Dockerfile"
                command = ["docker", "buildx", "build", "--builder", state["builder"], "--platform", "linux/amd64", "--provenance=false", "--sbom=false", "--load", "--tag", tag, "--label", "org.opencontainers.image.revision=" + commit, "--label", "copper.release.run_id=" + run_id, "--build-arg", "COPPER_VERSION=" + VERSION, "--build-arg", "COPPER_REVISION=" + commit, "--build-arg", "COPPER_BUILD_TIME=" + built_at, "--file", dockerfile, "."]
                run(command, cwd=context)
                run(["docker", "push", tag])
                published, producer_id = image_identity(inspect(tag), repository=repository, commit=commit, run_id=run_id)
                image_archive = result / "images" / ("copper-" + role + "-amd64.tar.gz")
                imported_id = make_archive(tag, image_archive, work)
                # Producer inspect may be a config ID; prove the actual load ID.
                run(["docker", "image", "rm", "-f", producer_id])
                image_id = import_image(image_archive, {"localTag": tag, "sourceCommit": commit, "reference": published, "importedImageId": imported_id}, run_id)
                provenance = {"format": "copper-local-build/v1", "role": role, "sourceCommit": commit, "buildId": run_id, "builtAt": built_at, "platform": "linux/amd64", "image": published, "imageId": image_id, "builder": "authorized-ubuntu24-amd64-" + run_id, "command": " ".join(command), "tools": {"docker": docker_version, "buildkitImage": args.buildkit_image, "registryImage": args.registry_image}}
                provenance_path = result / "release" / (role + "-local-build.json")
                write_json(provenance_path, provenance)
                evidence = {"sourceCommit": commit, "image": published, "platform": "linux/amd64", "archiveSha256": sha256(image_archive)}
                if role != "mysql":
                    evidence.update({"buildId": run_id, "buildProvenanceSha256": sha256(provenance_path), "buildImageId": image_id})
                else:
                    evidence["sourceCommit"] = None  # existing MySQL security-preload contract
                    evidence["workflowRun"] = None
                write_json(result / "preloaded" / (role + ".json"), evidence)
                records[role] = {"reference": published, "localTag": tag, "producerImageId": producer_id, "imageId": image_id, "importedImageId": imported_id, "archive": str(image_archive.relative_to(result)), "archiveSha256": sha256(image_archive), "provenanceFile": provenance_path.name, "provenanceSha256": sha256(provenance_path), "sourceCommit": commit}
            mysql_version = run(["docker", "run", "--rm", "--network", "none", "--entrypoint", "mysql", records["mysql"]["localTag"], "--version"]).stdout
            match = re.search(r"\b8\.4\.[0-9]+\b", mysql_version)
            if not match:
                raise ValueError("actual built runtime is not MySQL 8.4")
            migration_files = sorted((repos["backend"] / "src/main/resources/db/migration").glob("V*__*.sql"), key=lambda p: int(p.name.split("__", 1)[0][1:]))
            migration_manifest = result / "release/migrations.sha256"
            migration_manifest.write_text("".join(f"{sha256(p)}  {p.name}\n" for p in migration_files))
            schema = max(int(p.name.split("__", 1)[0][1:]) for p in migration_files)
            notes = ROOT / "releases" / VERSION / "RELEASE_NOTES.md"
            shutil.copy2(notes, result / "release/RELEASE_NOTES.md")
            write_json(result / "release/upgrade-matrix.json", candidate_matrix(schema))
            shutil.copy2(acme, result / "release" / acme.name)
            values = {"COPPER_RELEASE_VERSION": VERSION, "COPPER_RELEASE_CHANNEL": "rc", "COPPER_RELEASED_AT": built_at, "COPPER_MYSQL_IMAGE": records["mysql"]["reference"], "COPPER_MYSQL_VERSION": match.group(), "COPPER_MYSQL_PLATFORM": "linux/amd64", "COPPER_SUPPORTED_OS": "ubuntu-24.04", "COPPER_SUPPORTED_ARCH": "amd64", "COPPER_MIN_CPU": "2", "COPPER_MIN_MEMORY_GIB": "4", "COPPER_MIN_FREE_DISK_GIB": "20", "COPPER_DATABASE_SCHEMA_VERSION": str(schema), "COPPER_MIGRATION_SET_SHA256": sha256(migration_manifest), "COPPER_BACKUP_FORMAT": "copper-backup/v1", "COPPER_EXPORT_FORMAT": "copper-export/v1", "COPPER_VALIDATION_LEVEL": "L1", "COPPER_RELEASE_NOTES_SHA256": sha256(result / "release/RELEASE_NOTES.md"), "COPPER_ACME_ARCHIVE": acme.name, "COPPER_ACME_ARCHIVE_SHA256": args.acme_sha256}
            for role in ("web", "backend"):
                for field, value in (("IMAGE", records[role]["reference"]), ("SOURCE_COMMIT", records[role]["sourceCommit"]), ("PLATFORM", "linux/amd64"), ("BUILD_PROVENANCE_FILE", records[role]["provenanceFile"]), ("BUILD_PROVENANCE_SHA256", records[role]["provenanceSha256"])):
                    values["COPPER_" + role.upper() + "_" + field] = value
            (result / "release/components.env").write_text("".join(f"{k}={v}\n" for k, v in values.items()))
            write_json(result / "BUILD_INPUTS.json", {"format": "copper-release-build-inputs/v1", "version": VERSION, "runId": run_id, "status": "REAL_IMAGES_BUILT_PACKAGE_PENDING", "deployBuildCommit": args.deploy_commit, "tools": {"registryImage": args.registry_image, "buildkitImage": args.buildkit_image}, "acmeArchive": acme.name, "images": records, "temporaryRegistryIsCustomerDependency": False, "directUpgradeSources": []})
        finally:
            try:
                clean_owned(state)
            finally:
                write_json(state_path, state)
        shutil.copy2(state_path, result / "BUILD_CLEANUP.json")
        files = {str(p.relative_to(result)): sha256(p) for p in result.rglob("*") if p.is_file()}
        write_json(result / "INPUT_CHECKSUMS.json", files)
        result.rename(output)
    print(json.dumps({"status": "BUILD_INPUTS_READY_PACKAGE_PENDING", "output": str(output), "registryRemoved": True, "builderRemoved": True}))


def load_inputs(path):
    root = Path(path).resolve()
    checksums = json.loads((root / "INPUT_CHECKSUMS.json").read_text())
    if not isinstance(checksums, dict) or any(p.is_symlink() for p in root.rglob("*")):
        raise ValueError("build inputs must contain only regular files/directories")
    for name, digest in checksums.items():
        relative = Path(name)
        file = root / relative
        if relative.is_absolute() or ".." in relative.parts or file.is_symlink() or not file.is_file() or sha256(file) != digest:
            raise ValueError("build inputs are missing, unsafe or have changed")
    actual = {str(p.relative_to(root)) for p in root.rglob("*") if p.is_file()}
    if actual != set(checksums) | {"INPUT_CHECKSUMS.json"}:
        raise ValueError("build input file set is not closed")
    doc = json.loads((root / "BUILD_INPUTS.json").read_text())
    if doc.get("format") != "copper-release-build-inputs/v1" or doc.get("version") != VERSION:
        raise ValueError("unexpected build input format/version")
    if not re.fullmatch(r"[0-9a-f]{12}", doc.get("runId", "")) or not SHA.fullmatch(doc.get("deployBuildCommit", "")) or set(doc.get("images", {})) != {"web", "backend", "mysql"}:
        raise ValueError("build inputs lack exact source/run identities")
    if not IMAGE.fullmatch(doc.get("tools", {}).get("registryImage", "")) or not IMAGE.fullmatch(doc.get("tools", {}).get("buildkitImage", "")):
        raise ValueError("build tool images must remain digest-pinned")
    acme_name = doc.get("acmeArchive", "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,191}\.tar\.gz", acme_name) or not (root / "release" / acme_name).is_file():
        raise ValueError("ACME archive path is not a safe build input")
    addresses = set()
    for role, record in doc["images"].items():
        tag_pattern = r"(127\.0\.0\.1:([0-9]+))/copper-" + role + ":" + doc["runId"]
        tag = re.fullmatch(tag_pattern, record.get("localTag", ""))
        if not tag or not 1024 <= int(tag[2]) <= 65535 or int(tag[2]) == 8191:
            raise ValueError("build input image is outside its owned loopback namespace")
        addresses.add(tag[1])
        repository = record["localTag"].rsplit(":", 1)[0]
        if not IMAGE.fullmatch(record.get("reference", "")) or not record["reference"].startswith(repository + "@") or not SHA.fullmatch(record.get("sourceCommit", "")):
            raise ValueError("build input image/source identity is invalid")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", record.get("imageId", "")) or record["imageId"] != record.get("importedImageId"):
            raise ValueError("build input is missing actual verified import ID")
        if record.get("archive") != "images/copper-" + role + "-amd64.tar.gz" or record.get("provenanceFile") != role + "-local-build.json":
            raise ValueError("build input archive/provenance path is unsafe")
        if checksums.get(record["archive"]) != record.get("archiveSha256") or checksums.get("release/" + record["provenanceFile"]) != record.get("provenanceSha256"):
            raise ValueError("build input image/provenance digest binding differs")
    if len(addresses) != 1:
        raise ValueError("build inputs refer to different temporary registries")
    return root, doc


def stage(args):
    root, _ = load_inputs(args.inputs)
    target = ROOT / "releases" / VERSION
    target.mkdir(parents=True, exist_ok=True)
    for source in (root / "release").iterdir():
        if source.name == "mysql-local-build.json" or source.name.endswith(".tar.gz"):
            continue  # evidence/cache stays outside Git; package receives the reviewed ACME source.
        destination = target / source.name
        if destination.exists() and destination.read_bytes() != source.read_bytes():
            raise ValueError("refuse to overwrite an existing candidate artifact: " + source.name)
        shutil.copy2(source, destination)
    print("STAGED_FOR_REVIEW_COMMIT_AND_FAST_FORWARD_SYNC; no commit, push or tag was performed")


def validated_security_exceptions(value):
    """Use the existing gate; preserve the file and adjacent evidence identities."""
    if not value:
        return None
    security = runpy.run_path(str(ROOT / "scripts/security-evidence.py"))
    path = security["secure_file"](Path(value).expanduser(), "漏洞例外")
    policy = security["parse_env"](ROOT / "config/security-policy.env", "安全策略")
    security["required_policy"](policy)
    security["load_exceptions"](path, VERSION, security["utc_now"](), policy)
    return path


def package(args):
    exceptions = validated_security_exceptions(getattr(args, "security_exceptions", None))
    platform()
    inputs, doc = load_inputs(args.inputs)
    if git(ROOT, "status", "--porcelain") or git(ROOT, "rev-parse", "HEAD") != git(ROOT, "rev-parse", "origin/main"):
        raise ValueError("packaging requires clean reviewed origin/main; never fake or move that reference")
    reviewed_main(ROOT, git(ROOT, "rev-parse", "HEAD"), "deploy")
    run(["git", "-C", ROOT, "merge-base", "--is-ancestor", doc["deployBuildCommit"], "HEAD"])
    changed = git(ROOT, "diff", "--name-only", doc["deployBuildCommit"], "HEAD").splitlines()
    if any(not name.startswith("releases/" + VERSION + "/") and name != "config/release-policy.env" for name in changed):
        raise ValueError("Deploy runtime changed after image build; build new inputs instead")
    repos = {role: checkout(getattr(args, role + "_repo"), doc["images"][role]["sourceCommit"]) for role in ("web", "backend")}
    for source in (inputs / "release").iterdir():
        if source.name.endswith(".tar.gz") or source.name == "mysql-local-build.json":
            continue
        if not (ROOT / "releases" / VERSION / source.name).is_file() or sha256(source) != sha256(ROOT / "releases" / VERSION / source.name):
            raise ValueError("reviewed rc.11 files do not match actual build inputs")
    output = Path(args.output).resolve()
    if output.exists():
        raise ValueError("package output already exists; candidate bytes are immutable")
    env = os.environ.copy()
    env["COPPER_ACME_ARCHIVE_SOURCE"] = str(inputs / "release" / doc["acmeArchive"])
    env["COPPER_SECURITY_ALLOW_PULL"] = "1"
    for role, record in doc["images"].items():
        for prefix in ("COPPER_SECURITY_PRELOADED_", "COPPER_DELIVERY_PRELOADED_"):
            env[prefix + role.upper() + "_IMAGE"] = record["localTag"]
            env[prefix + role.upper() + "_ARCHIVE"] = str(inputs / record["archive"])
            env[prefix + role.upper() + "_EVIDENCE"] = str(inputs / "preloaded" / (role + ".json"))
        env["COPPER_SECURITY_PRELOADED_" + role.upper() + "_SOURCE"] = "verified-preloaded"
    # Existing fail-closed tooling checks source, full history, current vulnerability
    # DB, archive contents, image revisions and Docker 29 actual import IDs.
    output.parent.mkdir(parents=True, exist_ok=True)
    state = {"format": "copper-release-build-state/v1", "runId": doc["runId"], "registry": "copper-closure-registry-" + doc["runId"], "builder": "copper-closure-" + doc["runId"], "tags": [v["localTag"] for v in doc["images"].values()]}
    state_path = output.parent / ("package-state-" + doc["runId"] + ".json")
    write_json(state_path, state)
    with tempfile.TemporaryDirectory(prefix=".rc9-package-", dir=output.parent) as temporary:
        work = Path(temporary)
        try:
            address = doc["images"]["web"]["localTag"].split("/", 1)[0]
            registry_start(state, doc["tools"]["registryImage"], address)
            for record in doc["images"].values():
                import_image(inputs / record["archive"], record, doc["runId"])
            secure_args = [str(ROOT / "scripts/build-secure-release.sh"), "--candidate", VERSION, str(work / "release"), str(repos["web"]), str(repos["backend"])]
            if exceptions is not None:
                secure_args.append(str(exceptions))
            subprocess.run(secure_args, env=env, check=True)
            subprocess.run([str(ROOT / "scripts/build-release-delivery.sh"), "offline", str(work / "release" / ("copper-release-" + VERSION + ".env")), str(work / "offline")], env=env, check=True)
            (work / "result").mkdir()
            (work / "release").rename(work / "result/release")
            (work / "offline").rename(work / "result/offline")
            write_json(work / "result/PACKAGE_STATUS.json", {"format": "copper-offline-candidate/v1", "version": VERSION, "status": "PACKAGED_INSTALL_RESTORE_ACCEPTANCE_PENDING", "rightsEngineering": "NEW_SBOM_LICENSE_NOTICE_RECONCILIATION_REQUIRED", "buildInputsSha256": sha256(inputs / "BUILD_INPUTS.json"), "inputChecksumsSha256": sha256(inputs / "INPUT_CHECKSUMS.json"), "deployBuildCommit": doc["deployBuildCommit"], "deployCommit": git(ROOT, "rev-parse", "HEAD"), "temporaryRegistryIsCustomerDependency": False, "publishedToGHCR": False, "publicReleaseCreated": False, "directUpgradeSources": []})
        finally:
            try:
                clean_owned(state)
            finally:
                write_json(state_path, state)
        shutil.copy2(state_path, work / "result/PACKAGE_CLEANUP.json")
        write_json(work / "result/PACKAGE_CHECKSUMS.json", {str(p.relative_to(work / "result")): sha256(p) for p in (work / "result").rglob("*") if p.is_file()})
        # Cleanup is part of success. Failed runs never publish a ready directory.
        (work / "result").rename(output)
    print("OFFLINE_PACKAGE_READY_INSTALL_RESTORE_ACCEPTANCE_PENDING")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    build = sub.add_parser("build-inputs")
    build.add_argument("--version", default=VERSION)
    for role in ("web", "backend", "deploy"):
        build.add_argument("--" + role + "-repo", required=True)
        build.add_argument("--" + role + "-commit", required=True)
    for name in ("registry-image", "buildkit-image", "acme-archive", "acme-sha256", "output"):
        build.add_argument("--" + name, required=True)
    staged = sub.add_parser("stage-inputs")
    staged.add_argument("--inputs", required=True)
    packed = sub.add_parser("package")
    for name in ("inputs", "web-repo", "backend-repo", "output"):
        packed.add_argument("--" + name, required=True)
    packed.add_argument("--security-exceptions", help="显式、已审核的例外 JSON；同目录证据保持原名，沿用安全门槛全部校验")
    cleanup = sub.add_parser("cleanup")
    cleanup.add_argument("--state", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    def interrupted(*_):
        raise KeyboardInterrupt("received termination signal")
    signal.signal(signal.SIGTERM, interrupted)
    try:
        if args.action == "build-inputs":
            build_inputs(args)
        elif args.action == "stage-inputs":
            stage(args)
        elif args.action == "package":
            package(args)
        else:
            platform()
            state = json.loads(Path(args.state).read_text())
            clean_owned(state)
            write_json(args.state, state)
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError, KeyboardInterrupt) as error:
        parser.exit(1, f"Candidate closure stopped: {error}\n")


if __name__ == "__main__":
    main()
