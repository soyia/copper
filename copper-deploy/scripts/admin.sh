#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

[[ "${1:-}" == "init" ]] || { echo "错误：admin 只支持 init" >&2; exit 2; }
shift
show=0
if [[ "${1:-}" == "--show" ]]; then
  show=1
  shift
fi
[[ $# -eq 0 ]] || { echo "错误：admin init 只接受 --show" >&2; exit 2; }

require_root
validate_tools
validate_environment

etc_dir="$(required_env_value COPPER_ETC_DIR)"
credential_file="$etc_dir/credentials/initial-admin.txt"
backend_id="$(compose_cmd ps -q backend 2>/dev/null || true)"

[[ -n "$backend_id" ]] || die "Backend 尚未创建；请先运行 copperctl up"
backend_health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$backend_id")"
[[ "$backend_health" == "healthy" ]] || die "Backend 当前状态为 $backend_health；请先运行 copperctl doctor"

if [[ ! -e "$credential_file" ]]; then
  note "没有待交付的首次管理员凭据文件"
  echo "如果管理员已经完成首次改密，这是正常状态；凭据文件已由 Copper 自动销毁。"
  echo "如果尚未完成首次登录，请运行 copperctl logs backend 和 copperctl doctor，不要手工重建管理员。"
  exit 0
fi

[[ -f "$credential_file" && ! -L "$credential_file" ]] || die "首次管理员凭据路径不是普通文件，拒绝读取"
[[ "$(sed -n '1p' "$credential_file")" == "COPPER_INITIAL_ADMIN_V1" ]] || die "首次管理员凭据文件格式异常，拒绝读取"
mode="$(stat -c '%a' "$credential_file")"
[[ "$mode" == "600" ]] || die "首次管理员凭据权限为 $mode，应为 600；请修复权限后重试"
owner="$(stat -c '%u:%g' "$credential_file")"
[[ "$owner" == "10001:10001" ]] || die "首次管理员凭据属主为 $owner，应为 10001:10001；请运行 copperctl doctor"

username="$(awk -F= '$1 == "username" {print $2; exit}' "$credential_file")"
created_at="$(awk -F= '$1 == "createdAt" {sub(/^[^=]*=/, ""); print; exit}' "$credential_file")"
[[ -n "$username" ]] || die "首次管理员凭据缺少用户名"

if [[ "$show" == "1" ]]; then
  [[ -t 1 ]] || die "--show 只允许在可信交互终端执行，禁止重定向到日志或流水线"
  echo "警告：以下内容只应交给客户授权管理员，并立即存入密码管理器。"
  echo
  sed -n '/^username=/p;/^password=/p;/^mustChangePassword=/p' "$credential_file"
  echo
  echo "请立即通过 HTTPS 登录并修改密码；修改成功后再次运行 copperctl admin init，确认本文件已销毁。"
  exit 0
fi

echo "首次管理员凭据已生成并等待安全交付："
echo "  用户名：$username"
echo "  生成时间：${created_at:-未知}"
echo "  文件：$credential_file（权限 600）"
echo
echo "下一步：在客户本人可见的可信终端运行 sudo copperctl admin init --show。"
echo "不要截图、发群、写入工单或复制到 shell 历史；首次改密成功后文件会由 Backend 自动销毁。"
