#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

if [[ "$#" -ne 3 ]]; then
  echo "用法：sudo scripts/export-mysql-platform.sh MYSQL_IMAGE PLATFORM OUTPUT_DIR" >&2
  exit 2
fi

mysql_image="$1"
platform="$2"
output_dir="$3"

require_root
require_command docker
require_command gzip
require_command jq
require_command sha256sum
require_command stat
docker info >/dev/null 2>&1 || die "Docker Engine 不可用"

[[ "$mysql_image" =~ ^(docker\.io/library/)?mysql:[0-9]+\.[0-9]+\.[0-9]+@sha256:[0-9a-f]{64}$ ]] \
  || die "MySQL 必须使用固定补丁版本和顶层 manifest digest"
[[ "$mysql_image" != *latest* ]] || die "禁止导出 latest"

case "$platform" in
  linux/amd64)
    arch="amd64"
    [[ "$(uname -m)" == "x86_64" ]] || die "linux/amd64 只能在真实 x86_64 主机导出"
    ;;
  linux/arm64)
    arch="arm64"
    [[ "$(uname -m)" == "aarch64" || "$(uname -m)" == "arm64" ]] \
      || die "linux/arm64 只能在真实 ARM64 主机导出"
    ;;
  *)
    die "平台只允许 linux/amd64 或 linux/arm64"
    ;;
esac

if [[ -e "$output_dir" ]] && [[ -n "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
  die "输出目录不为空，拒绝覆盖：$output_dir"
fi
mkdir -p "$output_dir"
chmod 0700 "$output_dir"

raw="$(mktemp)"
trap 'rm -f "$raw"' EXIT
docker buildx imagetools inspect --raw "$mysql_image" > "$raw"
[[ "$(jq -r '.mediaType // empty' "$raw")" == "application/vnd.oci.image.index.v1+json" ]] \
  || die "MySQL 顶层引用不是 OCI 多架构索引"

child_count="$(jq --arg arch "$arch" '[.manifests[] | select(.platform.os == "linux" and .platform.architecture == $arch)] | length' "$raw")"
[[ "$child_count" == "1" ]] || die "MySQL 索引必须且只能包含一个 $platform 子镜像"
child_digest="$(jq -r --arg arch "$arch" '.manifests[] | select(.platform.os == "linux" and .platform.architecture == $arch) | .digest' "$raw")"
[[ "$child_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || die "无法解析 $platform 子 digest"

child_image="${mysql_image%@*}@$child_digest"
delivery_tag="copper-mysql-m5-${arch}:delivery"
note "拉取并导出 $platform：$child_image"
docker pull --platform "$platform" "$child_image"
docker tag "$child_image" "$delivery_tag"
local_id="$(docker image inspect --format '{{.Id}}' "$delivery_tag")"
architecture="$(docker image inspect --format '{{.Architecture}}' "$delivery_tag")"
[[ "$local_id" =~ ^sha256:[0-9a-f]{64}$ && "$architecture" == "$arch" ]] \
  || die "本地 MySQL 镜像身份与 $platform 不一致"

archive="copper-mysql-$arch.tar.gz"
docker save "$delivery_tag" | gzip -n > "$output_dir/$archive"
archive_sha="$(sha256sum "$output_dir/$archive" | awk '{print $1}')"
archive_bytes="$(stat --format '%s' "$output_dir/$archive")"
top_digest="${mysql_image##*@}"

jq -n \
  --arg format copper-mysql-platform/v1 \
  --arg mysqlImage "$mysql_image" \
  --arg manifestDigest "$top_digest" \
  --arg platform "$platform" \
  --arg platformDigest "$child_digest" \
  --arg localId "$local_id" \
  --arg archiveName "$archive" \
  --arg archiveSha256 "$archive_sha" \
  --argjson archiveBytes "$archive_bytes" \
  '{format:$format,mysqlImage:$mysqlImage,manifestDigest:$manifestDigest,platform:$platform,platformDigest:$platformDigest,localId:$localId,archive:{name:$archiveName,bytes:$archiveBytes,sha256:$archiveSha256}}' \
  > "$output_dir/mysql-platform-$arch.json"
(
  cd "$output_dir"
  sha256sum "$archive" "mysql-platform-$arch.json" > "SHA256SUMS-$arch"
)
chmod 0444 "$output_dir/$archive" "$output_dir/mysql-platform-$arch.json" "$output_dir/SHA256SUMS-$arch"

printf 'platform=%s\n' "$platform"
printf 'platformDigest=%s\n' "$child_digest"
printf 'localId=%s\n' "$local_id"
printf 'archive=%s\n' "$output_dir/$archive"
