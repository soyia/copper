#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib.sh"

components="${1:-$ROOT_DIR/releases/0.1.0-m1-rc.2/components.env}"
output_dir="${2:-$ROOT_DIR/dist/copper-images-amd64}"

require_root
require_command docker
require_command gzip
require_command sha256sum
require_command python3
[[ -f "$components" ]] || die "找不到组件清单 $components"
archive_id_tool="$SCRIPT_DIR/oci-archive-image-id.py"
[[ -x "$archive_id_tool" ]] || die "缺少 OCI 归档镜像 ID 工具 $archive_id_tool"
docker info >/dev/null 2>&1 || die "Docker Engine 不可用"

# shellcheck disable=SC1090
source "$components"
source_images=("${COPPER_WEB_IMAGE:-}" "${COPPER_BACKEND_IMAGE:-}" "${COPPER_MYSQL_IMAGE:-}")
for image in "${source_images[@]}"; do
  [[ "$image" =~ @sha256:[0-9a-f]{64}$ ]] || die "组件清单包含非不可变镜像：$image"
done

if [[ -e "$output_dir" ]] && [[ -n "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
  die "输出目录不为空，拒绝覆盖：$output_dir"
fi
mkdir -p "$output_dir"

runtime_names=(COPPER_WEB_IMAGE COPPER_BACKEND_IMAGE COPPER_MYSQL_IMAGE)
for index in 0 1 2; do
  image="${source_images[$index]}"
  note "拉取 linux/amd64 镜像：$image"
  docker pull --platform linux/amd64 "$image"
  architecture="$(docker image inspect --platform linux/amd64 --format '{{.Architecture}}' "$image")"
  [[ "$architecture" == "amd64" ]] || die "镜像架构不是 amd64：$image ($architecture)"
done

archive="$output_dir/copper-images-amd64.tar.gz"
docker save --platform linux/amd64 "${source_images[@]}" | gzip -n > "$archive"
mapfile -t archive_images < <(python3 "$archive_id_tool" "$archive" "${source_images[@]}")
[[ "${#archive_images[@]}" -eq 3 ]] || die "OCI 归档没有返回三个镜像 ID"
runtime_images=()
for index in 0 1 2; do
  IFS=$'\t' read -r reference image_id architecture <<< "${archive_images[$index]}"
  [[ "$reference" == "${source_images[$index]}" && "$architecture" == "amd64" \
    && "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || die "OCI 归档镜像身份不正确：${source_images[$index]}"
  runtime_images+=("$image_id")
done
runtime_manifest="$output_dir/copper-images-amd64.env"
{
  printf 'COPPER_RELEASE_VERSION=%s\n' "${COPPER_RELEASE_VERSION:-unknown}"
  printf 'COPPER_IMAGE_DELIVERY=offline\n'
  for index in 0 1 2; do
    printf '%s=%s\n' "${runtime_names[$index]}" "${runtime_images[$index]}"
    printf '%s_SOURCE=%s\n' "${runtime_names[$index]}" "${source_images[$index]}"
  done
} > "$runtime_manifest"
(
  cd "$output_dir"
  sha256sum copper-images-amd64.env copper-images-amd64.tar.gz > SHA256SUMS
)

note "镜像离线包已生成：$output_dir"
note "私有仓库登录只用于拉取；现在应执行 docker logout ghcr.io"
