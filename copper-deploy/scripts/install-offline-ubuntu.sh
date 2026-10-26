#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

bundle_dir="${1:-}"
[[ -n "$bundle_dir" ]] || { echo "用法：sudo ./scripts/install-offline-ubuntu.sh DOCKER_BUNDLE_DIR" >&2; exit 2; }
bundle_dir="$(cd "$bundle_dir" && pwd)"

unset COPPER_TEST_MODE
require_root
require_supported_platform
require_command sha256sum
require_command dpkg
require_command dpkg-deb
require_command systemctl

[[ -f "$bundle_dir/SHA256SUMS" ]] || die "Docker 离线包缺少 SHA256SUMS"
[[ -f "$bundle_dir/docker-ubuntu-amd64.env" ]] || die "Docker 离线包缺少版本清单"
(
  cd "$bundle_dir"
  sha256sum -c SHA256SUMS
)

# shellcheck disable=SC1090
source "$bundle_dir/docker-ubuntu-amd64.env"
[[ "${COPPER_DOCKER_TARGET_OS:-}" == "ubuntu-24.04" ]] || die "Docker 离线包操作系统不是 Ubuntu 24.04"
[[ "${COPPER_DOCKER_TARGET_ARCH:-}" == "amd64" ]] || die "Docker 离线包架构不是 amd64"

packages=()
for index in 1 2 3 4 5; do
  name_var="COPPER_DOCKER_PACKAGE_${index}_NAME"
  package="$bundle_dir/${!name_var:-}"
  [[ -f "$package" && ! -L "$package" ]] || die "Docker 离线包缺少第 $index 个 DEB"
  [[ "$(dpkg-deb -f "$package" Architecture)" == "amd64" ]] || die "$(basename "$package") 不是 amd64 包"
  packages+=("$package")
done

for dependency in init-system-helpers iptables nftables libc6 libsystemd0; do
  dpkg-query -W -f='${db:Status-Status}' "$dependency" 2>/dev/null | grep -qx installed \
    || die "基础系统缺少依赖 $dependency；请先从可信 Ubuntu 24.04 介质补齐，离线脚本不会访问软件仓库"
done

if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1 \
      && [[ "$(docker version --format '{{.Server.Version}}')" == 29.* ]] \
      && [[ "$(docker compose version --short 2>/dev/null || true)" == 5.* ]]; then
    note "Docker Engine 与 Compose 已符合 M1 基线；没有覆盖现有安装"
    exit 0
  fi
  die "检测到不符合 M1 基线或不可用的既有 Docker；不会自动覆盖"
fi

if ! dpkg -i "${packages[@]}"; then
  die "离线 DEB 安装失败；请保存上方依赖错误并补齐离线包，不要改用 curl | sh"
fi

systemctl enable --now docker
validate_tools
note "已从离线包安装 Docker Engine $(docker version --format '{{.Server.Version}}') 与 Compose $(docker compose version --short)"
