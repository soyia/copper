#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/lib.sh"

require_root
state_root="${COPPER_M5_03_STATE_ROOT:-/opt/copper-m5-03-acceptance}"
runtime="$state_root/runtime"
evidence="$state_root/evidence"
state_file="$runtime/state.env"
secret_file="$runtime/admin-password"
cookie="$runtime/cookie.txt"
offline_key="$runtime/offline-key"
env_file=/etc/copper/copper.env
site_id=copper-m5-03-acceptance
project=copper-m503

die_acceptance() {
  echo "错误：$*" >&2
  exit 1
}

assert_x86_ubuntu() {
  [[ "$(uname -m)" == "x86_64" ]] || die_acceptance "M5-03 正向生命周期只允许真实 x86_64 主机"
  # shellcheck disable=SC1091
  source /etc/os-release
  [[ "$ID" == "ubuntu" && "$VERSION_ID" == "24.04" ]] || die_acceptance "只允许 Ubuntu 24.04"
}

load_state() {
  [[ -f "$state_file" && ! -L "$state_file" ]] || die_acceptance "缺少 root-only 验收状态：$state_file"
  # shellcheck disable=SC1090
  source "$state_file"
  [[ "${DOMAIN:-}" =~ ^[A-Za-z0-9.-]+$ ]] || die_acceptance "验收域名状态无效"
}

curl_ready() {
  for _ in $(seq 1 120); do
    if curl --fail --silent --show-error --max-time 20 --resolve "$DOMAIN:443:127.0.0.1" \
      "https://$DOMAIN/readyz" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

assert_login() {
  local username password
  [[ -f "$secret_file" && "$(stat -c '%U:%G %a' "$secret_file")" == "root:root 600" ]] \
    || die_acceptance "测试管理员口令文件缺失或权限错误"
  username="$(sed -n '1p' "$secret_file")"
  password="$(sed -n '2p' "$secret_file")"
  rm -f "$cookie"
  curl --fail --silent --show-error --max-time 20 --resolve "$DOMAIN:443:127.0.0.1" \
    -c "$cookie" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg username "$username" --arg password "$password" '{username:$username,password:$password}')" \
    "https://$DOMAIN/api/auth/login" >/dev/null
  chmod 0600 "$cookie"
}

assert_service_boundaries() {
  local compose=(docker compose --env-file "$env_file" -f "$ROOT_DIR/compose.yaml")
  local service container_id
  for service in mysql backend web; do
    container_id="$("${compose[@]}" ps -q "$service")"
    [[ -n "$container_id" ]] || die_acceptance "$service 容器不存在"
    [[ "$(docker inspect --format '{{.State.Health.Status}}' "$container_id")" == "healthy" ]] \
      || die_acceptance "$service 未通过健康检查"
    docker inspect "$container_id" > "$evidence/container-$service.json"
  done
  python3 - "$evidence/container-mysql.json" "$evidence/container-backend.json" "$evidence/container-web.json" <<'PY'
import json
import sys

mysql, backend, web = (json.load(open(path, encoding="utf-8"))[0] for path in sys.argv[1:])
for name, item in (("mysql", mysql), ("backend", backend)):
    if item["HostConfig"].get("PortBindings") not in (None, {}):
        raise SystemExit(f"{name} 不得映射宿主机端口")
ports = web["HostConfig"].get("PortBindings") or {}
if set(ports) != {"80/tcp", "443/tcp", "443/udp"}:
    raise SystemExit("Web 宿主机端口集合必须精确为 80/443 TCP 与 443 UDP")
PY
}

write_stage() {
  local stage="$1"
  python3 - "$evidence/$stage.json" "$stage" <<'PY'
import datetime
import json
import pathlib
import sys

pathlib.Path(sys.argv[1]).write_text(json.dumps({
    "format": "copper-m5-local-host-lifecycle/v1",
    "stage": sys.argv[2],
    "status": "PASS",
    "recordedAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
}, sort_keys=True) + "\n", encoding="utf-8")
PY
}

install_stage() {
  [[ $# -eq 4 ]] || die_acceptance "用法：install DOMAIN WEB_IMAGE BACKEND_IMAGE MYSQL_IMAGE"
  local domain="$1" web_image="$2" backend_image="$3" mysql_image="$4"
  local image_delivery
  [[ "$domain" =~ ^[A-Za-z0-9.-]+$ ]] || die_acceptance "域名格式无效"
  if [[ "$web_image" =~ @sha256:[0-9a-f]{64}$ && "$backend_image" =~ @sha256:[0-9a-f]{64}$ && "$mysql_image" =~ @sha256:[0-9a-f]{64}$ ]]; then
    image_delivery=registry
  elif [[ "$web_image" =~ ^sha256:[0-9a-f]{64}$ && "$backend_image" =~ ^sha256:[0-9a-f]{64}$ && "$mysql_image" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    image_delivery=offline
  else
    die_acceptance "三个镜像必须全部使用仓库 @sha256 摘要，或全部使用已验证离线内容 ID"
  fi
  [[ ! -e /etc/copper && ! -e /var/lib/copper && ! -e /var/backups/copper ]] \
    || die_acceptance "正向生命周期要求空白正式路径"
  install -d -o root -g root -m 0700 "$runtime" "$evidence"
  printf 'DOMAIN=%q\n' "$domain" > "$state_file"
  chmod 0600 "$state_file"
  DOMAIN="$domain"

  "$ROOT_DIR/copperctl" host preflight --json > "$evidence/preflight-fresh.json"
  jq -e '.status == "PASS" and .writeAllowed == true' "$evidence/preflight-fresh.json" >/dev/null
  [[ ! -e /etc/copper && ! -e /var/lib/copper ]] || die_acceptance "只读预检写入了正式路径"

  set +e
  "$ROOT_DIR/copperctl" install > "$evidence/first-install.txt" 2>&1
  first_code=$?
  set -e
  [[ "$first_code" -eq 3 && -f "$env_file" ]] || die_acceptance "首次 install 未按配置门槛退出"

  "$ROOT_DIR/copper-key-offline" generate --output-dir "$offline_key" \
    --site-id "$site_id" --customer-reference "m5-03-x86-lifecycle" >/dev/null
  local recipient
  recipient="$(tr -d '\r\n' < "$offline_key/recipient.txt")"
  python3 - "$env_file" "$domain" "$recipient" "$image_delivery" "$web_image" "$backend_image" "$mysql_image" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
values = {
    "COPPER_SITE_ID": "copper-m5-03-acceptance",
    "COPPER_PROJECT_NAME": "copper-m503",
    "COPPER_RELEASE_VERSION": "0.4.0-m5-rc.1",
    "COPPER_DEPLOY_MODE": "standard",
    "COPPER_DOMAIN": sys.argv[2],
    "COPPER_TLS_MODE": "domain",
    "COPPER_IMAGE_DELIVERY": sys.argv[4],
    "COPPER_BACKUP_AGE_RECIPIENT": sys.argv[3],
    "COPPER_BACKUP_TARGETS": "local",
    "COPPER_BACKUP_ALERT_MODE": "local",
    "COPPER_MONITOR_ALERT_MODE": "local",
    "COPPER_WEB_IMAGE": sys.argv[5],
    "COPPER_BACKEND_IMAGE": sys.argv[6],
    "COPPER_MYSQL_IMAGE": sys.argv[7],
}
result = []
for line in path.read_text(encoding="utf-8").splitlines():
    key = line.split("=", 1)[0] if "=" in line and not line.lstrip().startswith("#") else ""
    result.append(f"{key}={values[key]}" if key in values else line)
path.write_text("\n".join(result) + "\n", encoding="utf-8")
PY

  "$ROOT_DIR/copperctl" install > "$evidence/install.txt" 2>&1
  systemctl is-enabled --quiet copper.service
  systemctl is-active --quiet copper.service
  [[ "$(stat -c '%U:%G %a' /etc/copper/host-preflight.json)" == "root:root 600" ]] \
    || die_acceptance "持久预检报告权限错误"
  curl_ready || die_acceptance "安装后业务未进入 ready"
  assert_service_boundaries

  local credential_file=/etc/copper/credentials/initial-admin.txt
  [[ -f "$credential_file" ]] || die_acceptance "首次管理员一次性凭据未生成"
  local username initial_password new_password
  username="$(awk -F= '$1=="username" {print $2}' "$credential_file")"
  initial_password="$(awk -F= '$1=="password" {print $2}' "$credential_file")"
  new_password="M503!$(openssl rand -hex 18)aZ9"
  curl --fail --silent --show-error --max-time 20 --resolve "$DOMAIN:443:127.0.0.1" \
    -c "$cookie" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg username "$username" --arg password "$initial_password" '{username:$username,password:$password}')" \
    "https://$DOMAIN/api/auth/login" >/dev/null
  curl --fail --silent --show-error --max-time 20 --resolve "$DOMAIN:443:127.0.0.1" \
    -b "$cookie" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg currentPassword "$initial_password" --arg newPassword "$new_password" '{currentPassword:$currentPassword,newPassword:$newPassword}')" \
    "https://$DOMAIN/api/auth/change-password" >/dev/null
  printf '%s\n%s\n' "$username" "$new_password" > "$secret_file"
  chmod 0600 "$secret_file"
  unset initial_password new_password
  rm -f "$cookie"
  [[ ! -e "$credential_file" ]] || die_acceptance "首次管理员凭据改密后未销毁"
  assert_login

  local handover_sha
  handover_sha="$(sha256sum "$offline_key/handover-record.json" | awk '{print $1}')"
  "$ROOT_DIR/copperctl" backup key register --handover-sha256 "$handover_sha" \
    --customer-confirmed-by "M5-03 acceptance" --witness-confirmed-by "M5-03 witness" \
    > "$evidence/key-register.json"
  "$ROOT_DIR/copperctl" backup create --purpose manual > "$evidence/backup-create.txt"
  local archive
  archive="$(jq -r '.archive' /var/backups/copper/last-success.json)"
  [[ "$archive" == /* ]] || archive="/var/backups/copper/$archive"
  "$ROOT_DIR/copperctl" backup verify "$archive" > "$evidence/backup-verify.json"
  "$ROOT_DIR/copper-key-offline" verify-backup --identity "$offline_key/copper-age-identity.txt" \
    --archive "$archive" --receipt "$offline_key/restore-receipt.json" \
    > "$evidence/offline-backup-verify.json"
  "$ROOT_DIR/copperctl" backup key record-restore-test "$offline_key/restore-receipt.json" \
    > "$evidence/restore-receipt-register.json"
  sha256sum /etc/copper/secrets/copper_db_password /etc/copper/secrets/mysql_root_password \
    > "$runtime/secret-hashes-before-reinstall.txt"
  "$ROOT_DIR/copperctl" doctor > "$evidence/doctor-before-reboot.txt" || die_acceptance "doctor 存在失败项"
  write_stage installed
  sync
  echo "m5_03_install=passed; next=sudo reboot"
}

after_reboot_stage() {
  [[ $# -eq 0 ]] || die_acceptance "after-reboot 不接受额外参数"
  load_state
  systemctl is-enabled --quiet copper.service
  for _ in $(seq 1 120); do
    if systemctl is-active --quiet copper.service; then
      break
    fi
    if systemctl is-failed --quiet copper.service; then
      die_acceptance "重启后 copper.service 进入 failed"
    fi
    sleep 2
  done
  systemctl is-active --quiet copper.service
  curl_ready || die_acceptance "重启后业务未自动进入 ready"
  assert_service_boundaries
  assert_login
  [[ ! -e /etc/copper/credentials/initial-admin.txt ]] || die_acceptance "重启后一次性凭据异常恢复"
  systemctl show copper.service --property=ActiveState,SubState,Result > "$evidence/systemd-after-reboot.txt"
  "$ROOT_DIR/copperctl" monitor check --json > "$evidence/monitor-after-reboot.json"
  jq -e '.overallLevel == "NORMAL" and .layers.liveness.level == "NORMAL" and .layers.readiness.level == "NORMAL" and .layers.writable.level == "NORMAL" and .layers.recoverable.level == "NORMAL"' \
    "$evidence/monitor-after-reboot.json" >/dev/null
  write_stage reboot-recovered
  sync
  echo "m5_03_reboot_recovery=passed"
}

reinstall_stage() {
  [[ $# -eq 0 ]] || die_acceptance "reinstall 不接受额外参数"
  load_state
  "$ROOT_DIR/copperctl" uninstall > "$evidence/uninstall.txt"
  [[ -d /var/lib/copper/mysql && -f "$env_file" && -d /var/backups/copper ]] \
    || die_acceptance "普通卸载未保留数据、配置或备份"
  [[ ! -e /etc/systemd/system/copper.service ]] || die_acceptance "普通卸载未移除自启动 unit"
  [[ -z "$(docker ps -aq --filter label=com.docker.compose.project=$project)" ]] \
    || die_acceptance "普通卸载仍遗留 Copper 容器"
  "$ROOT_DIR/copperctl" host preflight --json > "$evidence/preflight-reinstall.json"
  jq -e '.status == "PASS" and .writeAllowed == true and any(.checks[]; .id == "data.existingState" and .observed == "managed-reinstall")' \
    "$evidence/preflight-reinstall.json" >/dev/null
  "$ROOT_DIR/copperctl" install > "$evidence/reinstall.txt" 2>&1
  sha256sum -c "$runtime/secret-hashes-before-reinstall.txt" > "$evidence/secret-hashes-reinstall.txt"
  curl_ready || die_acceptance "重装后业务未进入 ready"
  assert_service_boundaries
  assert_login
  [[ ! -e /etc/copper/credentials/initial-admin.txt ]] || die_acceptance "重装覆盖了管理员凭据生命周期"
  local archive
  archive="$(jq -r '.archive' /var/backups/copper/last-success.json)"
  [[ "$archive" == /* ]] || archive="/var/backups/copper/$archive"
  "$ROOT_DIR/copperctl" backup verify "$archive" > "$evidence/backup-verify-after-reinstall.json"
  write_stage data-reattached
  sync
  echo "m5_03_reinstall_data_retention=passed"
}

cleanup_stage() {
  [[ $# -eq 0 ]] || die_acceptance "cleanup 不接受额外参数"
  load_state
  rm -f "$cookie"
  printf 'DELETE %s\n' "$site_id" | "$ROOT_DIR/scripts/permanently-delete-data.sh" --site-id "$site_id" \
    > "$evidence/permanent-delete.txt"
  [[ ! -e /etc/copper && ! -e /var/lib/copper && ! -e /var/backups/copper ]] \
    || die_acceptance "验收数据清理不完整"
  [[ ! -e /etc/systemd/system/copper.service ]] || die_acceptance "验收自启动 unit 清理不完整"
  rm -rf "$runtime"
  write_stage cleanup
  (
    cd "$evidence"
    sha256sum ./* > SHA256SUMS
  )
  chmod -R go-rwx "$state_root"
  echo "m5_03_cleanup=passed; evidence=$evidence"
}

assert_x86_ubuntu
case "${1:-}" in
  install) shift; install_stage "$@" ;;
  after-reboot) shift; after_reboot_stage "$@" ;;
  reinstall) shift; reinstall_stage "$@" ;;
  cleanup) shift; cleanup_stage "$@" ;;
  *) die_acceptance "用法：$0 install DOMAIN WEB_IMAGE BACKEND_IMAGE MYSQL_IMAGE | after-reboot | reinstall | cleanup" ;;
esac
