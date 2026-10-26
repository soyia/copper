#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

manifest="${1:-}"
role="${2:-}"
output_dir="${3:-}"
[[ -n "$manifest" && -n "$role" && -n "$output_dir" ]] || {
  echo "用法：scripts/security-export-image.sh RELEASE_MANIFEST web|backend OUTPUT_DIR" >&2
  exit 2
}
case "$role" in
  web) role_upper=WEB ;;
  backend) role_upper=BACKEND ;;
  *) echo "错误：只允许导出带源码 revision 的 web 或 backend 发布镜像" >&2; exit 2 ;;
esac
for command_name in docker python3 gzip sha256sum mktemp; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "错误：缺少命令 $command_name" >&2; exit 1; }
done
[[ -f "$manifest" && ! -L "$manifest" ]] || { echo "错误：发布清单必须是普通文件且不能是符号链接" >&2; exit 1; }
manifest="$(cd "$(dirname "$manifest")" && pwd)/$(basename "$manifest")"
[[ ! -e "$output_dir" && ! -L "$output_dir" ]] || { echo "错误：输出路径已存在，拒绝覆盖" >&2; exit 1; }

manifest_value() {
  awk -F= -v wanted="$1" '$1 == wanted {print substr($0, index($0, "=") + 1); exit}' "$manifest"
}

version="$(manifest_value COPPER_RELEASE_VERSION)"
published="$(manifest_value "COPPER_${role_upper}_IMAGE")"
source_commit="$(manifest_value "COPPER_${role_upper}_SOURCE_COMMIT")"
workflow_run="$(manifest_value "COPPER_${role_upper}_WORKFLOW_RUN")"
platform="$(manifest_value "COPPER_${role_upper}_PLATFORM")"
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.[1-9][0-9]*)?$ \
   && "$published" =~ @sha256:[0-9a-f]{64}$ && "$source_commit" =~ ^[0-9a-f]{40}$ \
   && "$platform" == "linux/amd64" ]] || {
  echo "错误：发布清单中的 $role 镜像身份不完整" >&2
  exit 1
}

docker image inspect "$published" >/dev/null 2>&1 || { echo "错误：本机不存在发布清单绑定的 $role 镜像" >&2; exit 1; }
local_id="$(docker image inspect --format '{{.Id}}' "$published")"
architecture="$(docker image inspect --format '{{.Architecture}}' "$published")"
revision="$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$published")"
repo_digests="$(docker image inspect --format '{{json .RepoDigests}}' "$published")"
[[ "$local_id" =~ ^sha256:[0-9a-f]{64}$ && "$architecture" == "amd64" && "$revision" == "$source_commit" ]] || {
  echo "错误：本机 $role 镜像内容 ID、架构或源码 revision 与发布清单不一致" >&2
  exit 1
}
python3 - "$published" "$repo_digests" <<'PY'
import json, sys
published, raw = sys.argv[1:]
if published not in (json.loads(raw) or []):
    raise SystemExit("错误：本机镜像没有发布清单绑定的 RepoDigest")
PY

parent="$(cd "$(dirname "$output_dir")" && pwd)"
work="$(mktemp -d "$parent/.copper-preloaded-$role.XXXXXX")"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT
archive="$work/copper-$role-$version-amd64.tar.gz"
evidence="$work/copper-$role-$version-preloaded-evidence.json"
docker save "$local_id" | gzip -n > "$archive"
archive_sha="$(sha256sum "$archive" | awk '{print $1}')"
python3 - "$evidence" "$source_commit" "$workflow_run" "$published" "$platform" "$archive_sha" "$manifest" "$role_upper" "$SCRIPT_DIR" "$local_id" <<'PY'
import json, pathlib, sys
sys.path.insert(0, sys.argv[9])
from build_provenance import validate_role
path = pathlib.Path(sys.argv[1])
source_commit, workflow_run, image, platform, archive_sha = sys.argv[2:7]
manifest_path = pathlib.Path(sys.argv[7])
values = dict(line.split("=", 1) for line in manifest_path.read_text().splitlines() if line and not line.startswith("#") and "=" in line)
identity = validate_role(manifest_path, values, sys.argv[8])
if identity.get("buildImageId", sys.argv[10]) != sys.argv[10]:
    raise SystemExit("本地构建证据 imageId 与实际运行镜像不一致")
path.write_text(json.dumps({
    "archiveSha256": archive_sha,
    "image": image,
    "platform": platform,
    "sourceCommit": source_commit,
    **identity,
}, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
PY
chmod 0444 "$archive" "$evidence"
mv "$work" "$output_dir"
trap - EXIT
echo "preloaded_source=verified-preloaded"
echo "preloaded_image=$local_id"
echo "preloaded_archive=$output_dir/$(basename "$archive")"
echo "preloaded_evidence=$output_dir/$(basename "$evidence")"
