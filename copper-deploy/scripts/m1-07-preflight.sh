#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

unset COPPER_TEST_MODE
pass_count=0
fail_count=0

pass() {
  pass_count=$((pass_count + 1))
  echo "[通过] $1"
}

fail() {
  fail_count=$((fail_count + 1))
  echo "[失败] $1"
  echo "       下一步：$2"
}

echo "Copper M1-07 空白服务器预检"
echo

if [[ "$EUID" -eq 0 ]]; then
  pass "当前以 root 权限执行"
else
  fail "当前不是 root" "使用 sudo ./scripts/m1-07-preflight.sh"
fi

platform_error=""
if platform_error="$(require_supported_platform 2>&1)"; then
  pass "操作系统为 Ubuntu 24.04 LTS，架构为 x86_64"
else
  fail "${platform_error#错误：}" "更换 M1 支持的 Ubuntu Server 24.04 LTS x86_64 空白机"
fi

cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"
memory_kib="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
disk_kib="$(df -Pk / 2>/dev/null | awk 'END {print $(NF-2)}')"

if [[ "$cpu_count" =~ ^[0-9]+$ && "$cpu_count" -ge 2 ]]; then
  pass "CPU 为 $cpu_count 核，不少于最低 2 核"
else
  fail "CPU 仅 ${cpu_count:-未知} 核" "更换或扩容到至少 2 核；正式客户建议 4 核"
fi

if [[ "$memory_kib" =~ ^[0-9]+$ && "$memory_kib" -ge 3800000 ]]; then
  pass "内存报告值为 $memory_kib KiB，符合标称 4 GiB 云主机最低线"
else
  fail "内存不足 4 GiB 或无法读取" "更换或扩容到至少 4 GiB；正式客户建议 8 GiB"
fi

if [[ "$disk_kib" =~ ^[0-9]+$ && "$disk_kib" -ge 20971520 ]]; then
  pass "根分区可用空间不少于最低 20 GiB"
else
  fail "根分区可用空间不足 20 GiB 或无法读取" "扩容系统盘，并按附件与数据库增长量预留容量"
fi

tools_error=""
if tools_error="$(validate_tools 2>&1)"; then
  pass "Docker Engine 29.x 与 Compose Plugin 5.x 正常"
else
  fail "${tools_error#错误：}" "先运行 sudo ./scripts/bootstrap-ubuntu.sh，再重复预检"
fi

for path in /etc/copper /var/lib/copper /var/backups/copper; do
  if [[ ! -e "$path" ]]; then
    pass "空白机上不存在 $path"
  else
    fail "发现已有路径 $path" "停止 M1-07 空白机验收；确认归属后换用真正空白机，不要自动删除"
  fi
done

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  existing_services="$(docker ps -aq --filter label=com.docker.compose.project=copper 2>/dev/null || true)"
  if [[ -z "$existing_services" ]]; then
    pass "不存在 Copper Compose 容器"
  else
    fail "发现已有 Copper Compose 容器" "停止空白机验收并确认容器归属，不要覆盖或删除客户站点"
  fi
fi

if command -v ss >/dev/null 2>&1; then
  occupied_ports="$(ss -H -lntup 2>/dev/null | awk '$5 ~ /:80$|:443$/ {print}' || true)"
  if [[ -z "$occupied_ports" ]]; then
    pass "宿主机 TCP/UDP 80、443 未被占用"
  else
    fail "宿主机 80 或 443 已被占用" "运行 sudo ss -lntup 确认归属；不要直接停止未知客户服务"
  fi
else
  fail "缺少 ss 命令，无法检查端口" "安装 iproute2 后重复预检"
fi

echo
echo "预检汇总：通过 ${pass_count}，失败 ${fail_count}"
if [[ "$fail_count" -gt 0 ]]; then
  echo "结论：当前主机不能作为 M1-07 空白验收机。"
  exit 1
fi
echo "结论：主机本地前置条件通过；云安全组、DNS、TLS 和外部端口仍须在验收中确认。"
