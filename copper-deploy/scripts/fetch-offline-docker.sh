#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${1:-$ROOT_DIR/releases/0.1.0-m1-rc.2/docker-ubuntu-amd64.env}"
output_dir="${2:-$ROOT_DIR/dist/docker-ubuntu-amd64}"

[[ -f "$manifest" ]] || { echo "错误：找不到 Docker 离线清单 $manifest" >&2; exit 2; }
for command_name in curl sha256sum; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "错误：缺少命令 $command_name" >&2; exit 1; }
done

# shellcheck disable=SC1090
source "$manifest"
[[ "${COPPER_DOCKER_TARGET_OS:-}" == "ubuntu-24.04" ]] || { echo "错误：只支持 Ubuntu 24.04" >&2; exit 1; }
[[ "${COPPER_DOCKER_TARGET_ARCH:-}" == "amd64" ]] || { echo "错误：只支持 amd64" >&2; exit 1; }

if [[ -e "$output_dir" ]] && [[ -n "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
  echo "错误：输出目录不为空，拒绝覆盖：$output_dir" >&2
  exit 1
fi

mkdir -p "$output_dir"
cp "$manifest" "$output_dir/docker-ubuntu-amd64.env"
(
  cd "$output_dir"
  sha256sum docker-ubuntu-amd64.env > SHA256SUMS
)

for index in 1 2 3 4 5; do
  name_var="COPPER_DOCKER_PACKAGE_${index}_NAME"
  url_var="COPPER_DOCKER_PACKAGE_${index}_URL"
  sha_var="COPPER_DOCKER_PACKAGE_${index}_SHA256"
  name="${!name_var:-}"
  url="${!url_var:-}"
  expected_sha="${!sha_var:-}"
  [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9.+:~_-]*_amd64\.deb$ ]] || { echo "错误：第 $index 个包名不合法" >&2; exit 1; }
  [[ "$url" == https://download.docker.com/linux/ubuntu/* ]] || { echo "错误：第 $index 个包不是 Docker 官方 HTTPS 地址" >&2; exit 1; }
  [[ "$expected_sha" =~ ^[0-9a-f]{64}$ ]] || { echo "错误：第 $index 个包缺少合法 SHA-256" >&2; exit 1; }

  echo "下载 $name"
  curl -fL --retry 3 --output "$output_dir/$name.part" "$url"
  actual_sha="$(sha256sum "$output_dir/$name.part" | awk '{print $1}')"
  [[ "$actual_sha" == "$expected_sha" ]] || { echo "错误：$name 的 SHA-256 不匹配" >&2; exit 1; }
  mv "$output_dir/$name.part" "$output_dir/$name"
  printf '%s  %s\n' "$expected_sha" "$name" >> "$output_dir/SHA256SUMS"
done

echo "Docker 离线安装包已生成：$output_dir"
