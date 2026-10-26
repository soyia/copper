#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

bundle_dir="${1:-}"
[[ -n "$bundle_dir" ]] || { echo "用法：sudo ./scripts/import-offline-images.sh IMAGE_BUNDLE_DIR" >&2; exit 2; }
bundle_dir="$(cd "$bundle_dir" && pwd)"

require_root
validate_tools
require_command sha256sum
[[ -f "$bundle_dir/SHA256SUMS" ]] || die "镜像离线包缺少 SHA256SUMS"
[[ -f "$bundle_dir/copper-images-amd64.env" ]] || die "镜像离线包缺少 copper-images-amd64.env"
[[ -f "$bundle_dir/copper-images-amd64.tar.gz" ]] || die "镜像离线包缺少镜像归档"

(
  cd "$bundle_dir"
  sha256sum -c SHA256SUMS
)
docker load --input "$bundle_dir/copper-images-amd64.tar.gz"

# shellcheck disable=SC1090
source "$bundle_dir/copper-images-amd64.env"
[[ "${COPPER_IMAGE_DELIVERY:-}" == "offline" ]] || die "镜像清单不是离线交付模式"
images=("${COPPER_WEB_IMAGE:-}" "${COPPER_BACKEND_IMAGE:-}" "${COPPER_MYSQL_IMAGE:-}")
for image in "${images[@]}"; do
  [[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]] || die "离线清单包含非不可变镜像 ID：$image"
  docker image inspect "$image" >/dev/null 2>&1 || die "导入后缺少镜像：$image"
  architecture="$(docker image inspect --format '{{.Architecture}}' "$image")"
  [[ "$architecture" == "amd64" ]] || die "导入镜像架构不是 amd64：$image ($architecture)"
done

note "三个 linux/amd64 不可变镜像均已离线导入并验证"
note "请把 copper-images-amd64.env 中的 COPPER_IMAGE_DELIVERY 和三个镜像 ID 写入 /etc/copper/copper.env"
