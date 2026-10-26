#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

ACME_VERSION="3.1.3"
ACME_ARCHIVE_SHA256="efd12b265252f8875269960b6b31830731ccce2b3e6ff8e7ecfbee21fde35ab4"
ACME_HOME="/opt/copper/acme-client"
HTTP_PID=""
WEB_WAS_RUNNING=0

cleanup() {
  if [[ -n "$HTTP_PID" ]] && kill -0 "$HTTP_PID" 2>/dev/null; then
    kill "$HTTP_PID" 2>/dev/null || true
    wait "$HTTP_PID" 2>/dev/null || true
  fi
  if [[ "$WEB_WAS_RUNNING" == "1" ]]; then
    compose_cmd up -d web --wait --wait-timeout 120 >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

tls_mode() {
  local value
  value="$(env_value COPPER_TLS_MODE || true)"
  echo "${value:-domain}"
}

require_ip_mode() {
  [[ "$(tls_mode)" == "ip" ]] || die "此命令仅用于 COPPER_TLS_MODE=ip"
}

acme_paths() {
  etc_dir="$(required_env_value COPPER_ETC_DIR)"
  var_dir="$(required_env_value COPPER_VAR_DIR)"
  public_ip="$(required_env_value COPPER_DOMAIN)"
  acme_config="$etc_dir/acme"
  tls_dir="$etc_dir/tls"
  webroot="$var_dir/acme-webroot"
  acme_log="$var_dir/logs/ip-tls-renewal.log"
}

ensure_acme_client() {
  local archive actual_hash temporary
  archive="$(env_value COPPER_ACME_ARCHIVE || true)"
  archive="${archive:-/opt/copper/offline/acme.sh-$ACME_VERSION.tar.gz}"
  [[ -f "$archive" ]] || die "缺少公网 IP 证书客户端归档 $archive"
  actual_hash="$(sha256sum "$archive" | awk '{print $1}')"
  [[ "$actual_hash" == "$ACME_ARCHIVE_SHA256" ]] || die "acme.sh 归档 SHA-256 不匹配，拒绝执行"

  if [[ -x "$ACME_HOME/acme.sh" && -f "$ACME_HOME/.archive.sha256" && "$(<"$ACME_HOME/.archive.sha256")" == "$actual_hash" ]]; then
    return
  fi

  temporary="$(mktemp -d)"
  trap 'rm -rf "${temporary:-}"; cleanup' EXIT
  tar -xzf "$archive" -C "$temporary" --strip-components=1
  [[ -x "$temporary/acme.sh" ]] || die "acme.sh 归档内容不完整"
  rm -rf "$ACME_HOME"
  install -d -o root -g root -m 0700 "$ACME_HOME"
  cp -a "$temporary"/. "$ACME_HOME"/
  printf '%s\n' "$actual_hash" > "$ACME_HOME/.archive.sha256"
  chmod -R go-rwx "$ACME_HOME"
  rm -rf "$temporary"
  trap cleanup EXIT
  note "已安装并校验 acme.sh $ACME_VERSION"
}

prepare_paths() {
  install -d -o root -g root -m 0700 "$acme_config"
  install -d -o root -g 10001 -m 0750 "$tls_dir" "$webroot"
  install -d -o 10001 -g 10001 -m 0750 "$(dirname "$acme_log")"
  touch "$acme_log"
  chown root:root "$acme_log"
  chmod 0600 "$acme_log"
}

certificate_usable() {
  [[ -s "$tls_dir/fullchain.pem" && -s "$tls_dir/privkey.pem" ]] &&
    openssl x509 -in "$tls_dir/fullchain.pem" -noout -checkend 43200 >/dev/null 2>&1
}

managed_certificate_usable() {
  local managed_dir="$acme_config/${public_ip}_ecc"
  [[ -s "$managed_dir/fullchain.cer" && -s "$managed_dir/$public_ip.key" ]] &&
    openssl x509 -in "$managed_dir/fullchain.cer" -noout -checkend 43200 >/dev/null 2>&1
}

stop_web_for_bootstrap() {
  local service_id
  service_id="$(compose_cmd ps -q web 2>/dev/null || true)"
  if [[ -n "$service_id" && "$(docker inspect --format '{{.State.Running}}' "$service_id" 2>/dev/null || true)" == "true" ]]; then
    WEB_WAS_RUNNING=1
    compose_cmd stop web >/dev/null
  fi
}

start_temporary_http() {
  require_command python3
  stop_web_for_bootstrap
  python3 -m http.server 80 --bind 0.0.0.0 --directory "$webroot" >>"$acme_log" 2>&1 &
  HTTP_PID=$!
  local attempt
  for attempt in {1..20}; do
    curl --fail --silent --max-time 1 http://127.0.0.1/ >/dev/null 2>&1 && return
    sleep 0.25
  done
  die "无法启动临时 HTTP-01 校验入口，请检查宿主机 80 端口"
}

install_certificate() {
  "$ACME_HOME/acme.sh" \
    --home "$ACME_HOME" \
    --config-home "$acme_config" \
    --install-cert \
    --domain "$public_ip" \
    --ecc \
    --cert-file "$tls_dir/cert.pem" \
    --key-file "$tls_dir/privkey.pem" \
    --ca-file "$tls_dir/chain.pem" \
    --fullchain-file "$tls_dir/fullchain.pem" \
    --reloadcmd "/usr/local/sbin/copperctl tls reload" >>"$acme_log" 2>&1
  chown root:10001 "$tls_dir"/*.pem
  chmod 0640 "$tls_dir"/*.pem
  openssl x509 -in "$tls_dir/fullchain.pem" -noout -checkend 43200 >/dev/null 2>&1 || die "新证书有效期不足 12 小时"
}

init_certificate() {
  require_ip_mode
  require_command curl
  require_command openssl
  require_command sha256sum
  require_command tar
  acme_paths
  prepare_paths
  ensure_acme_client
  if certificate_usable; then
    note "保留现有有效公网 IP 证书"
    return
  fi
  if managed_certificate_usable; then
    install_certificate
    note "已从受限 ACME 存储恢复现有有效公网 IP 证书"
    return
  fi

  start_temporary_http
  if ! "$ACME_HOME/acme.sh" \
    --home "$ACME_HOME" \
    --config-home "$acme_config" \
    --server letsencrypt \
    --issue \
    --certificate-profile shortlived \
    --days 4 \
    --keylength ec-256 \
    --domain "$public_ip" \
    --webroot "$webroot" >>"$acme_log" 2>&1; then
    die "公网 IP 证书签发失败，请查看 $acme_log"
  fi
  install_certificate
  note "公网 IP 证书已签发并安装，私钥仅对 root 和 Web 容器用户可读"
}

renew_certificate() {
  require_ip_mode
  acme_paths
  prepare_paths
  ensure_acme_client
  "$ACME_HOME/acme.sh" \
    --home "$ACME_HOME" \
    --config-home "$acme_config" \
    --cron >>"$acme_log" 2>&1
  certificate_usable || die "公网 IP 证书缺失或有效期不足 12 小时"
  note "公网 IP 证书续期检查完成"
}

reload_web() {
  require_ip_mode
  acme_paths
  certificate_usable || die "证书缺失或有效期不足 12 小时，拒绝重载 Web"
  chown root:10001 "$tls_dir"/*.pem
  chmod 0640 "$tls_dir"/*.pem
  local service_id
  service_id="$(compose_cmd ps -q web 2>/dev/null || true)"
  if [[ -z "$service_id" ]]; then
    note "证书已安装；Web 尚未创建，将在正式启动时加载证书"
    return
  fi
  compose_cmd restart web >/dev/null
  compose_cmd up -d web --wait --wait-timeout 120 >/dev/null
  note "Web 已重新加载公网 IP 证书"
}

install_timer() {
  require_ip_mode
  local service_file timer_file
  service_file="$(mktemp)"
  timer_file="$(mktemp)"
  trap 'rm -f "${service_file:-}" "${timer_file:-}"; cleanup' EXIT
  cat >"$service_file" <<'EOF'
[Unit]
Description=Renew Copper short-lived public IP certificate
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/copperctl tls renew
EOF
  cat >"$timer_file" <<'EOF'
[Unit]
Description=Daily Copper public IP certificate renewal check

[Timer]
OnCalendar=daily
RandomizedDelaySec=4h
Persistent=true

[Install]
WantedBy=timers.target
EOF
  install -o root -g root -m 0644 "$service_file" /etc/systemd/system/copper-ip-tls-renew.service
  install -o root -g root -m 0644 "$timer_file" /etc/systemd/system/copper-ip-tls-renew.timer
  rm -f "$service_file" "$timer_file"
  trap cleanup EXIT
  systemctl daemon-reload
  systemctl enable --now copper-ip-tls-renew.timer >/dev/null
  note "已启用公网 IP 证书每日自动续期检查"
}

disable_timer() {
  systemctl disable --now copper-ip-tls-renew.timer >/dev/null 2>&1 || true
  rm -f /etc/systemd/system/copper-ip-tls-renew.service /etc/systemd/system/copper-ip-tls-renew.timer
  systemctl daemon-reload
  note "公网 IP 证书自动续期计时器已禁用并移除"
}

status_certificate() {
  require_ip_mode
  acme_paths
  certificate_usable || die "公网 IP 证书缺失或有效期不足 12 小时"
  openssl x509 -in "$tls_dir/fullchain.pem" -noout -subject -issuer -dates -ext subjectAltName
  systemctl is-enabled copper-ip-tls-renew.timer
  systemctl is-active copper-ip-tls-renew.timer
}

require_root
validate_environment

case "${1:-}" in
  init) init_certificate ;;
  renew) renew_certificate ;;
  reload) reload_web ;;
  install-timer) install_timer ;;
  disable-timer) disable_timer ;;
  status) status_certificate ;;
  *) die "用法：copperctl tls <init|renew|reload|install-timer|disable-timer|status>" ;;
esac
