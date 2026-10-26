#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

unset COPPER_TEST_MODE
require_root
require_supported_platform
require_command apt-get
require_command dpkg

if command -v docker >/dev/null 2>&1; then
  if ! docker info >/dev/null 2>&1; then
    die "检测到 Docker CLI，但 Docker Engine 不可用；请先由管理员确认现有安装，不会自动覆盖"
  fi
  current_engine="$(docker version --format '{{.Server.Version}}')"
  current_compose="$(docker compose version --short 2>/dev/null || true)"
  if [[ "$current_engine" == 29.* && "$current_compose" == 5.* ]]; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y age zstd python3
    note "Docker Engine 与 Compose 已符合 M1 基线；已确认 M2 备份工具 age、zstd、python3"
    exit 0
  fi
  die "检测到不符合 M1 基线的现有 Docker（Engine ${current_engine:-未知} / Compose ${current_compose:-未知}）；为避免破坏客户软件，不会自动卸载或覆盖"
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl age zstd python3

install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod 0644 /etc/apt/keyrings/docker.asc

. /etc/os-release
architecture="$(dpkg --print-architecture)"
codename="${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}"
[[ -n "$codename" ]] || die "无法确定 Ubuntu 发行代号"

printf '%s\n' \
  'Types: deb' \
  'URIs: https://download.docker.com/linux/ubuntu' \
  "Suites: $codename" \
  'Components: stable' \
  "Architectures: $architecture" \
  'Signed-By: /etc/apt/keyrings/docker.asc' \
  > /etc/apt/sources.list.d/docker.sources

apt-get update
engine_version="$(apt-cache madison docker-ce | awk '$3 ~ /^5:29\./ && !selected {print $3; selected=1}')"
cli_version="$(apt-cache madison docker-ce-cli | awk '$3 ~ /^5:29\./ && !selected {print $3; selected=1}')"
compose_version="$(apt-cache madison docker-compose-plugin | awk '$3 ~ /^5\./ && !selected {print $3; selected=1}')"
[[ -n "$engine_version" ]] || die "Docker 官方仓库当前没有可用的 Engine 29.x 包"
[[ -n "$cli_version" ]] || die "Docker 官方仓库当前没有可用的 CLI 29.x 包"
[[ -n "$compose_version" ]] || die "Docker 官方仓库当前没有可用的 Compose Plugin 5.x 包"

apt-get install -y \
  "docker-ce=$engine_version" \
  "docker-ce-cli=$cli_version" \
  containerd.io \
  docker-buildx-plugin \
  "docker-compose-plugin=$compose_version"

systemctl enable --now docker
validate_tools

note "已安装 Docker Engine $(docker version --format '{{.Server.Version}}') 与 Compose $(docker compose version --short)"
note "Docker 发布端口可能绕过 UFW；正式验收仍须核对云安全组、DOCKER-USER 链和外部端口探测"
