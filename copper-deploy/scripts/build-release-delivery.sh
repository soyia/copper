#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib.sh"

mode="${1:-}"
manifest="${2:-}"
output_dir="${3:-}"
base_index="${4:-}"
[[ "$mode" == "online" || "$mode" == "offline" || "$mode" == "offline-delta" ]] \
  || { echo "用法：scripts/build-release-delivery.sh online|offline RELEASE_MANIFEST OUTPUT_DIR；或 offline-delta RELEASE_MANIFEST OUTPUT_DIR BASE_INDEX" >&2; exit 2; }
[[ -n "$manifest" && -n "$output_dir" ]] \
  || { echo "用法：scripts/build-release-delivery.sh online|offline RELEASE_MANIFEST OUTPUT_DIR；或 offline-delta RELEASE_MANIFEST OUTPUT_DIR BASE_INDEX" >&2; exit 2; }
if [[ "$mode" == "offline-delta" ]]; then
  [[ -n "$base_index" && -f "$base_index" && ! -L "$base_index" ]] \
    || die "offline-delta 必须提供来源版本的普通离线交付索引"
  base_index="$(cd "$(dirname "$base_index")" && pwd)/$(basename "$base_index")"
  [[ "$("$SCRIPT_DIR/delivery-contract.py" validate "$base_index" | awk -F= '$1 == "mode" {print $2}')" != "online" ]] \
    || die "offline-delta 来源必须是带本机镜像身份的离线交付索引"
elif [[ -n "$base_index" ]]; then
  die "$mode 构建不接受第四个来源索引参数"
fi

require_command python3
require_command sha256sum
require_command install
require_command cp
[[ -f "$manifest" && ! -L "$manifest" ]] || die "发布清单必须是普通文件且不能是符号链接"
manifest="$(cd "$(dirname "$manifest")" && pwd)/$(basename "$manifest")"
[[ ! -e "$output_dir" && ! -L "$output_dir" ]] || die "输出路径已存在，拒绝覆盖：$output_dir"

manifest_value() {
  awk -F= -v wanted="$1" '$1 == wanted {print substr($0, index($0, "=") + 1); exit}' "$manifest"
}

"$SCRIPT_DIR/release-contract.py" validate-manifest "$manifest" >/dev/null
version="$(manifest_value COPPER_RELEASE_VERSION)"
[[ -n "$version" ]] || die "发布清单缺少产品版本"

mkdir -p "$(dirname "$output_dir")"
parent="$(cd "$(dirname "$output_dir")" && pwd)"
partial="$(mktemp -d "$parent/.copper-delivery-$version.XXXXXX")"
cleanup() {
  rm -rf "$partial"
}
trap cleanup EXIT
chmod 0755 "$partial"

copy_bound_file() {
  local name="$1" source
  [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,191}$ ]] || die "发布清单包含不安全文件名：$name"
  source="$(dirname "$manifest")/$name"
  [[ -f "$source" && ! -L "$source" ]] || die "发布清单绑定文件不存在或不是普通文件：$name"
  cp "$source" "$partial/$name"
}

cp "$manifest" "$partial/$(basename "$manifest")"
copy_bound_file "$(manifest_value COPPER_DEPLOY_ARCHIVE)"
copy_bound_file "$(manifest_value COPPER_UPGRADE_MATRIX_FILE)"
copy_bound_file "$(manifest_value COPPER_RELEASE_NOTES_FILE)"
copy_bound_file "$(manifest_value COPPER_ACME_ARCHIVE)"
for role in WEB BACKEND; do
  provenance_name="$(manifest_value "COPPER_${role}_BUILD_PROVENANCE_FILE")"
  [[ -z "$provenance_name" ]] || copy_bound_file "$provenance_name"
done

image_archive=""
if [[ "$mode" == "offline" || "$mode" == "offline-delta" ]]; then
  require_root
  require_command docker
  require_command gzip
  validate_tools
  archive_id_tool="$SCRIPT_DIR/oci-archive-image-id.py"
  [[ -x "$archive_id_tool" ]] || die "缺少 OCI 归档镜像 ID 工具 $archive_id_tool"
  published_images=(
    "$(manifest_value COPPER_WEB_IMAGE)"
    "$(manifest_value COPPER_BACKEND_IMAGE)"
    "$(manifest_value COPPER_MYSQL_IMAGE)"
  )
  runtime_images=("${published_images[@]}")
  image_roles=(web backend mysql)
  local_ids=()
  overrides=(
    "${COPPER_DELIVERY_TEST_WEB_IMAGE:-}"
    "${COPPER_DELIVERY_TEST_BACKEND_IMAGE:-}"
    "${COPPER_DELIVERY_TEST_MYSQL_IMAGE:-}"
  )
  if [[ -n "${overrides[0]}${overrides[1]}${overrides[2]}" ]]; then
    [[ "${COPPER_TEST_MODE:-0}" == "1" ]] || die "测试镜像覆盖只允许 COPPER_TEST_MODE=1"
    for index in 0 1 2; do
      [[ -n "${overrides[$index]}" ]] || die "测试镜像覆盖必须同时提供 Web、Backend、MySQL"
      runtime_images[$index]="${overrides[$index]}"
    done
  fi

  inspect_runtime_image() {
    local image="$1"
    shift
    if [[ "${COPPER_TEST_MODE:-0}" == "1" && -n "${overrides[0]}" ]]; then
      docker image inspect "$@" "$image"
    else
      docker image inspect --platform linux/amd64 "$@" "$image"
    fi
  }

  preloaded_requested=("" "" "")
  configure_preloaded_image() {
    local index="$1" role="$2" role_upper="$3"
    local image_var="COPPER_DELIVERY_PRELOADED_${role_upper}_IMAGE"
    local archive_var="COPPER_DELIVERY_PRELOADED_${role_upper}_ARCHIVE"
    local evidence_var="COPPER_DELIVERY_PRELOADED_${role_upper}_EVIDENCE"
    local runtime="${!image_var:-}"
    local archive="${!archive_var:-}"
    local evidence="${!evidence_var:-}"
    local requested="$runtime$archive$evidence" expected_local_id
    [[ -n "$requested" ]] || return 0
    [[ "${COPPER_TEST_MODE:-0}" != "1" ]] || die "正式 $role 预加载工件禁止测试模式"
    [[ -n "$runtime" && -f "$archive" && ! -L "$archive" && -f "$evidence" && ! -L "$evidence" ]] \
      || die "$role 预加载工件必须同时提供 IMAGE、ARCHIVE、EVIDENCE"
    expected_local_id="$(python3 - "$evidence" "$archive" "$manifest" "$runtime" "$role_upper" "$SCRIPT_DIR" <<'PY'
import hashlib, json, pathlib, sys, tarfile
sys.path.insert(0, sys.argv[6])
from build_provenance import validate_role
evidence_path, archive_path, manifest_path = map(pathlib.Path, sys.argv[1:4])
runtime = sys.argv[4]
role = sys.argv[5]
evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
values = {}
for line in manifest_path.read_text(encoding="utf-8").splitlines():
    if line and not line.startswith("#") and "=" in line:
        key, value = line.split("=", 1)
        values[key] = value
expected = {
    "sourceCommit": values.get(f"COPPER_{role}_SOURCE_COMMIT"),
    "image": values.get(f"COPPER_{role}_IMAGE"),
    "platform": values.get(f"COPPER_{role}_PLATFORM"),
}
if role in {"WEB", "BACKEND"}:
    expected.update(validate_role(manifest_path, values, role))
else:
    expected["workflowRun"] = values.get(f"COPPER_{role}_WORKFLOW_RUN")
if set(evidence) != {*expected, "archiveSha256"}:
    raise SystemExit(f"{role} 预加载证据字段不正确")
for key, value in expected.items():
    if str(evidence.get(key)) != str(value):
        raise SystemExit(f"{role} 预加载证据未绑定发布清单：{key}")
digest = hashlib.sha256()
with archive_path.open("rb") as reader:
    for chunk in iter(lambda: reader.read(1024 * 1024), b""):
        digest.update(chunk)
if digest.hexdigest() != evidence["archiveSha256"]:
    raise SystemExit(f"{role} 预加载归档 SHA-256 与 CI 证据不一致")
with tarfile.open(archive_path, "r:gz") as archive:
    index_member = archive.getmember("index.json")
    if not index_member.isfile() or index_member.size > 1024 * 1024:
        raise SystemExit(f"{role} 工件 index.json 不安全")
    image_index = json.load(archive.extractfile(index_member))
    matches = [
        item for item in image_index.get("manifests", [])
        if item.get("annotations", {}).get("io.containerd.image.name") == runtime
    ]
    if len(matches) != 1:
        raise SystemExit(f"{role} 工件不包含指定的唯一运行标签")
    local_id = matches[0].get("digest", "")
    if not isinstance(local_id, str) or not local_id.startswith("sha256:") or len(local_id) != 71:
        raise SystemExit(f"{role} 工件 OCI manifest 摘要不正确")
    manifest_name = "blobs/sha256/" + local_id.removeprefix("sha256:")
    manifest_member = archive.getmember(manifest_name)
    if not manifest_member.isfile() or manifest_member.size > 16 * 1024 * 1024:
        raise SystemExit(f"{role} 工件 OCI manifest 对象不安全")
    actual = "sha256:" + hashlib.sha256(archive.extractfile(manifest_member).read()).hexdigest()
    if actual != local_id:
        raise SystemExit(f"{role} 工件 OCI manifest 摘要不一致")
    if expected.get("buildImageId", local_id) != local_id:
        raise SystemExit(f"{role} 本地构建 imageId 与归档实际镜像不一致")
    print(local_id)
PY
    )"
    note "导入构建来源校验绑定的 $role amd64 工件"
    docker load --input "$archive" >/dev/null
    [[ "$(inspect_runtime_image "$runtime" --format '{{.Id}}')" == "$expected_local_id" ]] \
      || die "$role 预加载运行镜像与 CI 归档配置对象不一致"
    runtime_images[$index]="$runtime"
    preloaded_requested[$index]=1
  }

  configure_preloaded_image 0 Web WEB
  configure_preloaded_image 1 Backend BACKEND

  for index in 0 1 2; do
    if [[ "${COPPER_TEST_MODE:-0}" != "1" || -z "${overrides[$index]}" ]]; then
      if [[ -n "${preloaded_requested[$index]}" ]]; then
        :
      elif docker image inspect --platform linux/amd64 "${published_images[$index]}" >/dev/null 2>&1 \
        && [[ "$(docker image inspect --platform linux/amd64 --format '{{.Architecture}}' "${published_images[$index]}")" == "amd64" ]]; then
        note "复用本机已按发布摘要校验的 amd64 镜像：${published_images[$index]}"
      else
        note "拉取 linux/amd64 不可变镜像：${published_images[$index]}"
        docker pull --platform linux/amd64 "${published_images[$index]}"
      fi
    fi
    inspect_runtime_image "${runtime_images[$index]}" >/dev/null 2>&1 \
      || die "无法读取待打包镜像：${runtime_images[$index]}"
    architecture="$(inspect_runtime_image "${runtime_images[$index]}" --format '{{.Architecture}}')"
    if [[ "${COPPER_TEST_MODE:-0}" != "1" ]]; then
      [[ "$architecture" == "amd64" ]] || die "正式离线介质只能包含 amd64 镜像"
    fi
  done

  all_image_archive="$partial/.copper-images-$version-all-amd64.tar.gz"
  if [[ "${COPPER_TEST_MODE:-0}" == "1" && -n "${overrides[0]}" ]]; then
    docker save "${runtime_images[@]}" | gzip -n > "$all_image_archive"
  else
    docker save --platform linux/amd64 "${runtime_images[@]}" | gzip -n > "$all_image_archive"
  fi
  [[ -s "$all_image_archive" ]] || die "离线镜像归档为空"
  mapfile -t archive_images < <(python3 "$archive_id_tool" "$all_image_archive" "${runtime_images[@]}")
  [[ "${#archive_images[@]}" -eq 3 ]] || die "OCI 归档没有返回三个镜像 ID"
  local_ids=()
  for image_index in 0 1 2; do
    IFS=$'\t' read -r reference archive_id archive_architecture <<< "${archive_images[$image_index]}"
    [[ "$reference" == "${runtime_images[$image_index]}" && "$archive_architecture" == "amd64" \
      && "$archive_id" =~ ^sha256:[0-9a-f]{64}$ ]] \
      || die "OCI 归档镜像身份不正确：${runtime_images[$image_index]}"
    local_ids+=("$archive_id")
  done

  image_archive="$partial/copper-images-$version-amd64.tar.gz"
  if [[ "$mode" == "offline" ]]; then
    mv "$all_image_archive" "$image_archive"
  else
    declare -A base_local_ids=()
    while IFS=$'\t' read -r role _reference _platform local_id; do
      [[ "$role" == "web" || "$role" == "backend" || "$role" == "mysql" ]] \
        || die "来源交付索引包含未知镜像角色"
      [[ "$local_id" =~ ^sha256:[0-9a-f]{64}$ ]] || die "来源交付索引缺少本机镜像身份：$role"
      base_local_ids[$role]="$local_id"
    done < <("$SCRIPT_DIR/delivery-contract.py" images "$base_index")
    [[ "${#base_local_ids[@]}" -eq 3 ]] || die "来源交付索引镜像身份不完整"
    changed_runtime_images=()
    changed_roles=()
    for image_index in 0 1 2; do
      role="${image_roles[$image_index]}"
      if [[ "${local_ids[$image_index]}" == "${base_local_ids[$role]}" ]]; then
        note "差异升级复用服务器现有 $role 镜像：${local_ids[$image_index]}"
      else
        changed_runtime_images+=("${runtime_images[$image_index]}")
        changed_roles+=("$role")
      fi
    done
    rm -f "$all_image_archive"
    if [[ "${#changed_runtime_images[@]}" -gt 0 ]]; then
      note "差异升级只打包变化镜像：${changed_roles[*]}"
      if [[ "${COPPER_TEST_MODE:-0}" == "1" && -n "${overrides[0]}" ]]; then
        docker save "${changed_runtime_images[@]}" | gzip -n > "$image_archive"
      else
        docker save --platform linux/amd64 "${changed_runtime_images[@]}" | gzip -n > "$image_archive"
      fi
      [[ -s "$image_archive" ]] || die "差异升级镜像归档为空"
      python3 "$archive_id_tool" "$image_archive" "${changed_runtime_images[@]}" >/dev/null
    else
      image_archive=""
      note "三张目标镜像均与来源版本一致；差异升级包不携带镜像"
    fi
  fi
fi

if [[ "$mode" == "offline-delta" ]]; then
  base_version="$("$SCRIPT_DIR/delivery-contract.py" validate "$base_index" | awk -F= '$1 == "version" {print $2}')"
  index="$partial/copper-upgrade-delivery-offline-delta-$base_version-to-$version.json"
  generate_args=(generate-delta --manifest "$partial/$(basename "$manifest")" --base-index "$base_index" --output "$index")
  for image_index in 0 1 2; do
    generate_args+=(--image-id "${image_roles[$image_index]},${local_ids[$image_index]}")
  done
  [[ -z "$image_archive" ]] || generate_args+=(--image-archive "$image_archive")
else
  index="$partial/copper-delivery-$mode-$version.json"
  generate_args=(generate --manifest "$partial/$(basename "$manifest")" --mode "$mode" --output "$index")
  if [[ -n "$image_archive" ]]; then
    generate_args+=(--image-archive "$image_archive")
    for image_index in 0 1 2; do
      generate_args+=(--image-id "${image_roles[$image_index]},${local_ids[$image_index]}")
    done
  fi
fi
"$SCRIPT_DIR/delivery-contract.py" "${generate_args[@]}" >/dev/null
"$SCRIPT_DIR/delivery-contract.py" validate "$index" >/dev/null

find "$partial" -type f -exec chmod 0444 {} +
mv "$partial" "$output_dir"
trap - EXIT
index="$output_dir/$(basename "$index")"
note "$mode 交付介质已生成：$output_dir"
note "交付索引：$index"
note "请通过独立可信渠道交接索引 SHA-256：$(sha256sum "$index" | awk '{print $1}')"
