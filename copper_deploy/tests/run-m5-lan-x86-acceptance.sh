#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/lib.sh"

require_root
state_root="${COPPER_M5_LAN_STATE_ROOT:-/opt/copper-m5-04-acceptance}"
runtime="$state_root/runtime"
evidence="$state_root/evidence"
state_file="$runtime/state.env"
network_file="$runtime/network.env"
env_file=/etc/copper/copper.env
site_id=copper-m5-04-acceptance
project=copper-m504
namespace=copper-m5-lan-client
allowed_client=192.168.250.2
denied_client=192.168.250.3
dns_server=192.168.250.2
initial_bind=192.168.250.1
changed_bind=192.168.250.4
unit=/etc/systemd/system/copper-m5-lan-acceptance-network.service
dropin=/etc/systemd/system/copper.service.d/m5-lan-acceptance.conf

die_acceptance() { echo "错误：$*" >&2; exit 1; }

assert_platform() {
  [[ "$(uname -m)" == "x86_64" ]] || die_acceptance "M5-04 正向验收只允许真实 x86_64"
  # shellcheck disable=SC1091
  source /etc/os-release
  [[ "$ID" == "ubuntu" && "$VERSION_ID" == "24.04" ]] || die_acceptance "只允许 Ubuntu 24.04"
}

load_state() {
  [[ -f "$state_file" && ! -L "$state_file" ]] || die_acceptance "缺少验收状态"
  # shellcheck disable=SC1090
  source "$state_file"
  [[ "$DOMAIN" =~ ^[a-z0-9.-]+$ ]] || die_acceptance "域名状态无效"
}

write_network_state() {
  local bind_ip="$1"
  printf 'DOMAIN=%q\nBIND_IP=%q\n' "$DOMAIN" "$bind_ip" > "$network_file"
  chmod 0600 "$network_file"
}

client_curl() {
  ip netns exec "$namespace" curl --interface "$allowed_client" --cacert "$runtime/ca.pem" \
    --fail --silent --show-error --max-time 15 "$@"
}

wait_ready() {
  for _ in $(seq 1 120); do
    if client_curl "https://$DOMAIN/readyz" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  return 1
}

assert_cookie_session() {
  client_curl -b "$runtime/cookie.txt" "https://$DOMAIN/api/auth/me" > "$evidence/auth-me-$1.json"
  jq -e '.code == 0 and .data.username != null' "$evidence/auth-me-$1.json" >/dev/null
}

assert_ports() {
  local bind_ip="$1"
  ip netns exec "$namespace" python3 - "$bind_ip" "$allowed_client" <<'PY'
import socket,sys
target,source=sys.argv[1:]
def connects(port):
    value=socket.socket(); value.settimeout(2); value.bind((source,0))
    try: value.connect((target,port)); return True
    except OSError: return False
    finally: value.close()
assert connects(443)
assert connects(80)
assert not connects(3306)
assert not connects(8191)
assert not connects(18080)
PY
  set +e
  ip netns exec "$namespace" curl --interface "$denied_client" --cacert "$runtime/ca.pem" \
    --silent --show-error --max-time 3 "https://$DOMAIN/healthz" >/dev/null 2>&1
  denied_code=$?
  set -e
  [[ "$denied_code" -ne 0 ]] || die_acceptance "非允许来源仍可访问 443"
  set +e
  ip netns exec "$namespace" curl --interface "$allowed_client" --silent --show-error --max-time 5 \
    "https://$DOMAIN/healthz" >/dev/null 2>&1
  untrusted_code=$?
  set -e
  [[ "$untrusted_code" -ne 0 ]] || die_acceptance "未导入客户 CA 的客户端未被证书门槛阻断"
  redirect="$(ip netns exec "$namespace" curl --interface "$allowed_client" --silent --show-error --max-time 5 \
    -o /dev/null -w '%{http_code} %{redirect_url}' "http://$DOMAIN/healthz")"
  [[ "$redirect" == "308 https://$DOMAIN/healthz" ]] || die_acceptance "80 未精确重定向到 HTTPS：$redirect"
}

assert_compose_boundaries() {
  local bind_ip="$1"
  compose_cmd config --format json > "$evidence/compose-$bind_ip.json"
  python3 - "$evidence/compose-$bind_ip.json" "$bind_ip" <<'PY'
import json,sys
x=json.load(open(sys.argv[1],encoding="utf-8")); bind=sys.argv[2]
web=x["services"]["web"]
actual={(str(p.get("host_ip")),int(p["published"]),p["protocol"]) for p in web["ports"]}
assert actual=={(bind,80,"tcp"),(bind,443,"tcp"),(bind,443,"udp")}
for name in ("mysql","backend"):
    assert not x["services"][name].get("ports")
PY
  for service in mysql backend web; do
    container="$(compose_cmd ps -q "$service")"
    [[ -n "$container" && "$(docker inspect --format '{{.State.Health.Status}}' "$container")" == healthy ]] \
      || die_acceptance "$service 未健康"
  done
}

certificate_serial() {
  ip netns exec "$namespace" openssl s_client -connect "$1:443" -servername "$DOMAIN" -CAfile "$runtime/ca.pem" </dev/null 2>/dev/null \
    | openssl x509 -noout -serial | cut -d= -f2
}

generate_certificates() {
  local work
  work="$(mktemp -d /tmp/copper-m5-lan-ca.XXXXXX)"
  chmod 0700 "$work"
  openssl req -x509 -newkey rsa:2048 -nodes -days 730 -subj '/CN=Copper M5 LAN Acceptance Root' \
    -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign' \
    -keyout "$work/ca.key" -out "$runtime/ca.pem" >/dev/null 2>&1
  for generation in initial rotated; do
    openssl req -newkey rsa:2048 -nodes -subj "/CN=$DOMAIN" -addext "subjectAltName=DNS:$DOMAIN" \
      -keyout "$runtime/$generation.key" -out "$work/$generation.csr" >/dev/null 2>&1
    printf 'subjectAltName=DNS:%s\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$DOMAIN" \
      > "$work/$generation.ext"
    openssl x509 -req -in "$work/$generation.csr" -CA "$runtime/ca.pem" -CAkey "$work/ca.key" \
      -CAcreateserial -days 365 -extfile "$work/$generation.ext" -out "$runtime/$generation.pem" >/dev/null 2>&1
  done
  rm -rf "$work"
  chmod 0600 "$runtime/initial.key" "$runtime/rotated.key" "$runtime/initial.pem" "$runtime/rotated.pem"
  chmod 0644 "$runtime/ca.pem"
  [[ -z "$(find /etc/copper /var/lib/copper -type f -name '*ca*key*' -print 2>/dev/null || true)" ]] \
    || die_acceptance "客户 CA 私钥进入 Copper 正式目录"
}

install_stage() {
  [[ $# -eq 4 ]] || die_acceptance "install 需要 DOMAIN WEB_IMAGE BACKEND_IMAGE MYSQL_IMAGE"
  DOMAIN="$1"; local web_image="$2" backend_image="$3" mysql_image="$4"
  [[ "$DOMAIN" =~ ^[a-z0-9.-]+$ ]] || die_acceptance "域名无效"
  for image in "$web_image" "$backend_image" "$mysql_image"; do
    [[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]] || die_acceptance "完整验收只接受已校验离线镜像 ID"
    docker image inspect "$image" >/dev/null
  done
  [[ ! -e /etc/copper && ! -e /var/lib/copper && ! -e /var/backups/copper ]] || die_acceptance "正式路径必须为空"
  [[ ! -e "$state_root" ]] || die_acceptance "验收状态目录已存在"
  install -d -o root -g root -m 0700 "$runtime" "$evidence"
  printf 'DOMAIN=%q\n' "$DOMAIN" > "$state_file"; chmod 0600 "$state_file"
  write_network_state "$initial_bind"
  install -o root -g root -m 0644 "$ROOT_DIR/tests/copper-m5-lan-acceptance-network.service" "$unit"
  install -d -o root -g root -m 0755 "$(dirname "$dropin")"
  printf '[Unit]\nRequires=copper-m5-lan-acceptance-network.service\nAfter=copper-m5-lan-acceptance-network.service\n' > "$dropin"
  chmod 0644 "$dropin"
  systemctl daemon-reload
  systemctl enable --now copper-m5-lan-acceptance-network.service >/dev/null

  "$ROOT_DIR/copperctl" host preflight --json > "$evidence/host-preflight-fresh.json"
  jq -e '.status == "PASS" and .writeAllowed == true' "$evidence/host-preflight-fresh.json" >/dev/null
  set +e; "$ROOT_DIR/copperctl" install > "$evidence/first-install.txt" 2>&1; first_code=$?; set -e
  [[ "$first_code" -eq 3 && -f "$env_file" ]] || die_acceptance "首次 install 未按配置门槛退出"

  "$ROOT_DIR/copper-key-offline" generate --output-dir "$runtime/offline-key" --site-id "$site_id" \
    --customer-reference m5-04-lan-acceptance >/dev/null
  recipient="$(tr -d '\r\n' < "$runtime/offline-key/recipient.txt")"
  python3 - "$env_file" "$DOMAIN" "$recipient" "$web_image" "$backend_image" "$mysql_image" <<'PY'
import pathlib,sys
path=pathlib.Path(sys.argv[1])
values={
"COPPER_SITE_ID":"copper-m5-04-acceptance","COPPER_PROJECT_NAME":"copper-m504",
"COPPER_RELEASE_VERSION":"0.4.0-m5-rc.1","COPPER_DEPLOY_MODE":"lan","COPPER_DOMAIN":sys.argv[2],
"COPPER_TLS_MODE":"lan","COPPER_LAN_BIND_IP":"192.168.250.1","COPPER_LAN_INTERFACE":"copperlan0",
"COPPER_LAN_DNS_SERVER":"192.168.250.2","COPPER_LAN_ALLOWED_CIDRS":"192.168.250.1/32,192.168.250.2/32",
"COPPER_LAN_ADDRESS_RESERVED":"yes","COPPER_LAN_DOMAIN_CONTROL_CONFIRMED":"yes","COPPER_LAN_NAME_RESOLUTION":"dns",
"COPPER_IMAGE_DELIVERY":"offline","COPPER_WEB_IMAGE":sys.argv[4],"COPPER_BACKEND_IMAGE":sys.argv[5],
"COPPER_MYSQL_IMAGE":sys.argv[6],"COPPER_BACKUP_AGE_RECIPIENT":sys.argv[3],"COPPER_BACKUP_TARGETS":"local",
"COPPER_BACKUP_ALERT_MODE":"local","COPPER_MONITOR_ALERT_MODE":"local"}
result=[]
for line in path.read_text().splitlines():
 key=line.split("=",1)[0] if "=" in line and not line.lstrip().startswith("#") else ""
 result.append(f"{key}={values[key]}" if key in values else line)
path.write_text("\n".join(result)+"\n")
PY
  generate_certificates
  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" lan certificate install \
    --fullchain "$runtime/initial.pem" --private-key "$runtime/initial.key" --trust-anchor "$runtime/ca.pem" \
    > "$evidence/certificate-install.txt"
  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" install > "$evidence/install.txt" 2>&1
  wait_ready || die_acceptance "LAN 完整站点未 ready"
  assert_compose_boundaries "$initial_bind"
  assert_ports "$initial_bind"
  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" lan status --json > "$evidence/lan-status-installed.json"
  initial_serial="$(certificate_serial "$initial_bind")"; printf '%s\n' "$initial_serial" > "$runtime/initial-serial"

  credential=/etc/copper/credentials/initial-admin.txt
  username="$(awk -F= '$1=="username"{print $2}' "$credential")"
  initial_password="$(awk -F= '$1=="password"{print $2}' "$credential")"
  new_password="M504!$(openssl rand -hex 18)aZ9"
  client_curl -D "$evidence/login-headers.txt" -c "$runtime/cookie.txt" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg username "$username" --arg password "$initial_password" '{username:$username,password:$password}')" \
    "https://$DOMAIN/api/auth/login" >/dev/null
  grep -i '^set-cookie:' "$evidence/login-headers.txt" | grep -qi 'Secure'
  grep -i '^set-cookie:' "$evidence/login-headers.txt" | grep -qi 'HttpOnly'
  grep -i '^set-cookie:' "$evidence/login-headers.txt" | grep -qi 'SameSite=Strict'
  printf '{"formatVersion":"copper-m5-lan-cookie/v1","secure":true,"httpOnly":true,"sameSiteStrict":true}\n' \
    > "$evidence/cookie-security.json"
  rm -f "$evidence/login-headers.txt"
  client_curl -b "$runtime/cookie.txt" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg currentPassword "$initial_password" --arg newPassword "$new_password" '{currentPassword:$currentPassword,newPassword:$newPassword}')" \
    "https://$DOMAIN/api/auth/change-password" >/dev/null
  set +e
  client_curl -b "$runtime/cookie.txt" "https://$DOMAIN/api/auth/me" > "$evidence/auth-me-after-password-change.json" 2>&1
  old_session_code=$?
  set -e
  [[ "$old_session_code" -ne 0 ]] || die_acceptance "改密后旧会话未被撤销"
  client_curl -D "$evidence/relogin-headers.txt" -c "$runtime/cookie.txt" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg username "$username" --arg password "$new_password" '{username:$username,password:$password}')" \
    "https://$DOMAIN/api/auth/login" >/dev/null
  rm -f "$evidence/relogin-headers.txt"
  printf '%s\n%s\n' "$username" "$new_password" > "$runtime/admin-password"; chmod 0600 "$runtime/admin-password"
  unset initial_password new_password
  [[ ! -e "$credential" ]] || die_acceptance "首次管理员凭据未销毁"
  assert_cookie_session installed
  handover="$(sha256sum "$runtime/offline-key/handover-record.json" | awk '{print $1}')"
  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" backup key register --handover-sha256 "$handover" \
    --customer-confirmed-by 'M5-04 acceptance' --witness-confirmed-by 'M5-04 witness' > "$evidence/key-register.json"
  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" doctor > "$evidence/doctor-installed.txt"
  iptables-save > "$evidence/iptables-installed.txt"
  systemctl show copper.service copper-m5-lan-acceptance-network.service --property=Id,ActiveState,SubState,Result \
    > "$evidence/systemd-installed.txt"
  echo "m5_04_install=PASS; next=sudo reboot"
}

after_reboot_stage() {
  load_state
  systemctl is-active --quiet copper-m5-lan-acceptance-network.service
  for _ in $(seq 1 120); do systemctl is-active --quiet copper.service && break; sleep 2; done
  systemctl is-active --quiet copper.service
  wait_ready || die_acceptance "重启后 LAN 站点未自动 ready"
  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" lan status --json > "$evidence/lan-status-after-reboot.json"
  assert_compose_boundaries "$initial_bind"
  assert_ports "$initial_bind"
  assert_cookie_session after-reboot
  iptables-save > "$evidence/iptables-after-reboot.txt"
  systemctl show copper.service copper-m5-lan-acceptance-network.service --property=Id,ActiveState,SubState,Result \
    > "$evidence/systemd-after-reboot.txt"
  echo "m5_04_reboot=PASS"
}

change_and_rotate_stage() {
  load_state
  systemctl stop copper.service
  python3 - "$env_file" <<'PY'
import pathlib
p=pathlib.Path(__import__('sys').argv[1]); text=p.read_text()
text=text.replace('COPPER_LAN_BIND_IP=192.168.250.1','COPPER_LAN_BIND_IP=192.168.250.4')
text=text.replace('COPPER_LAN_ALLOWED_CIDRS=192.168.250.1/32,192.168.250.2/32','COPPER_LAN_ALLOWED_CIDRS=192.168.250.4/32,192.168.250.2/32')
p.write_text(text)
PY
  write_network_state "$changed_bind"
  systemctl restart copper-m5-lan-acceptance-network.service
  systemctl start copper.service
  wait_ready || die_acceptance "地址受控变化后站点未 ready"
  assert_compose_boundaries "$changed_bind"
  assert_ports "$changed_bind"
  assert_cookie_session address-changed
  set +e
  ip netns exec "$namespace" curl --interface "$allowed_client" --cacert "$runtime/ca.pem" \
    --silent --max-time 3 --resolve "$DOMAIN:443:$initial_bind" "https://$DOMAIN/healthz" >/dev/null 2>&1
  old_address_code=$?
  set -e
  [[ "$old_address_code" -ne 0 ]] || die_acceptance "旧地址仍在提供业务"

  old_serial="$(certificate_serial "$changed_bind")"
  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" lan certificate install \
    --fullchain "$runtime/rotated.pem" --private-key "$runtime/rotated.key" --trust-anchor "$runtime/ca.pem" --reload \
    > "$evidence/certificate-rotate.txt"
  new_serial="$(certificate_serial "$changed_bind")"
  [[ -n "$old_serial" && -n "$new_serial" && "$old_serial" != "$new_serial" ]] || die_acceptance "证书轮换序列号未变化"
  assert_cookie_session certificate-rotated
  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" lan status --json > "$evidence/lan-status-final.json"
  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" security secrets audit > "$evidence/security-secrets-final.json"
  [[ -d /etc/copper/tls/archive && "$(stat -c '%U:%G %a' /etc/copper/tls/archive)" == 'root:root 700' ]] \
    || die_acceptance "证书轮换归档权限错误"
  iptables-save > "$evidence/iptables-final.txt"
  echo "m5_04_address_and_certificate_change=PASS"
}

post_change_artifacts_stage() {
  load_state
  wait_ready || die_acceptance "地址与证书变化后的站点未 ready"
  assert_cookie_session before-export
  client_curl -b "$runtime/cookie.txt" -H 'Content-Type: application/json' \
    --data '{"confirmFullExport":true}' "https://$DOMAIN/api/business-exports" \
    > "$evidence/business-export-create.json"
  export_id="$(jq -er '.data.id' "$evidence/business-export-create.json")"
  export_status=""
  for _ in $(seq 1 120); do
    client_curl -b "$runtime/cookie.txt" "https://$DOMAIN/api/business-exports" \
      > "$evidence/business-export-list.json"
    export_status="$(jq -r --arg id "$export_id" '.data.items[] | select(.id == $id) | .status' "$evidence/business-export-list.json")"
    [[ "$export_status" != "FAILED" ]] || die_acceptance "地址与证书变化后的完整导出失败"
    [[ "$export_status" == "COMPLETED" ]] && break
    sleep 2
  done
  [[ "$export_status" == "COMPLETED" ]] || die_acceptance "地址与证书变化后的完整导出超时"
  client_curl -b "$runtime/cookie.txt" -D "$evidence/business-export-download-headers.txt" \
    -o "$evidence/business-export.zip" "https://$DOMAIN/api/business-exports/$export_id/download"
  expected_export_sha="$(jq -er --arg id "$export_id" '.data.items[] | select(.id == $id) | .archiveSha256' "$evidence/business-export-list.json")"
  actual_export_sha="$(sha256sum "$evidence/business-export.zip" | awk '{print $1}')"
  header_export_sha="$(awk -F': ' 'tolower($1)=="x-content-sha256" {gsub("\\r", "", $2); print tolower($2)}' "$evidence/business-export-download-headers.txt")"
  [[ "$expected_export_sha" == "$actual_export_sha" && "$header_export_sha" == "$actual_export_sha" ]] \
    || die_acceptance "完整导出下载 SHA-256 不一致"
  printf '%s  business-export.zip\n' "$actual_export_sha" > "$evidence/business-export-download.sha256"
  rm -f "$evidence/business-export.zip"

  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" backup create --purpose manual > "$evidence/backup-after-change.txt"
  archive="$(jq -er '.archive' /var/backups/copper/last-success.json)"
  [[ "$archive" == /* ]] || archive="/var/backups/copper/$archive"
  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" backup verify "$archive" > "$evidence/backup-verify-after-change.json"
  "$ROOT_DIR/copper-key-offline" verify-backup --identity "$runtime/offline-key/copper-age-identity.txt" \
    --archive "$archive" --receipt "$runtime/offline-key/restore-receipt.json" \
    > "$evidence/offline-backup-verify-after-change.json"
  COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" backup key record-restore-test \
    "$runtime/offline-key/restore-receipt.json" > "$evidence/restore-proof-after-change.json"
  wait_ready || die_acceptance "变更后的加密备份完成后站点未恢复"
  assert_cookie_session after-backup
  assert_compose_boundaries "$changed_bind"
  assert_ports "$changed_bind"
  echo "m5_04_export_download_and_backup=PASS"
}

cleanup_stage() {
  load_state
  printf 'DELETE %s\n' "$site_id" | COPPER_ENV_FILE="$env_file" "$ROOT_DIR/scripts/permanently-delete-data.sh" --site-id "$site_id" \
    > "$evidence/permanent-delete.txt"
  systemctl disable --now copper-m5-lan-acceptance-network.service >/dev/null 2>&1 || true
  rm -f "$unit" "$dropin"
  rmdir /etc/systemd/system/copper.service.d >/dev/null 2>&1 || true
  systemctl daemon-reload
  if [[ -L /usr/local/sbin/copperctl && "$(readlink -f /usr/local/sbin/copperctl)" == "$ROOT_DIR/copperctl" ]]; then
    unlink /usr/local/sbin/copperctl
  fi
  [[ ! -e /etc/copper && ! -e /var/lib/copper && ! -e /var/backups/copper && ! -e /var/lib/copper-restores ]] \
    || die_acceptance "正式数据路径清理不完整"
  ! iptables -S COPPER-LAN >/dev/null 2>&1 || die_acceptance "LAN 防火墙链未清理"
  ! ip netns list | grep -q "$namespace" || die_acceptance "验收网络命名空间未清理"
  (
    cd "$evidence"
    sha256sum ./* > SHA256SUMS
  )
  rm -rf "$runtime"
  chmod -R go-rwx "$state_root"
  [[ ! -e "$runtime" ]] || die_acceptance "验收运行秘密未销毁"
  echo "m5_04_cleanup=PASS; evidence=$evidence"
}

assert_platform
case "${1:-}" in
  install) shift; install_stage "$@" ;;
  after-reboot) shift; after_reboot_stage "$@" ;;
  change-and-rotate) shift; change_and_rotate_stage "$@" ;;
  artifacts) shift; post_change_artifacts_stage "$@" ;;
  cleanup) shift; cleanup_stage "$@" ;;
  *) die_acceptance "用法：$0 install DOMAIN WEB_IMAGE BACKEND_IMAGE MYSQL_IMAGE | after-reboot | change-and-rotate | artifacts | cleanup" ;;
esac
