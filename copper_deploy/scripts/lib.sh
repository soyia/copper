#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${COPPER_ENV_FILE:-/etc/copper/copper.env}"

die() {
  echo "错误：$*" >&2
  exit 1
}

note() {
  echo "Copper：$*"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令 $1"
}

require_root() {
  [[ "$EUID" -eq 0 ]] || die "部署命令需要读取受限配置和密钥，请使用 sudo 执行"
}

require_supported_platform() {
  if [[ "${COPPER_TEST_MODE:-0}" == "1" ]]; then
    note "测试模式：跳过 Ubuntu 24.04 x86_64 平台限制"
    return
  fi

  [[ -r /etc/os-release ]] || die "无法读取 /etc/os-release；M1 仅支持 Ubuntu Server 24.04 LTS"
  local platform_id platform_version architecture
  platform_id="$(. /etc/os-release && printf '%s' "${ID:-}")"
  platform_version="$(. /etc/os-release && printf '%s' "${VERSION_ID:-}")"
  architecture="$(uname -m)"
  [[ "$platform_id" == "ubuntu" && "$platform_version" == "24.04" ]] || die "M1 仅支持 Ubuntu Server 24.04 LTS，当前为 ${platform_id:-unknown} ${platform_version:-unknown}"
  [[ "$architecture" == "x86_64" ]] || die "M1 仅支持 amd64/x86_64，当前为 $architecture"
}

env_value() {
  local key="$1"
  [[ -f "$ENV_FILE" ]] || return 1
  awk -F= -v wanted="$key" '
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    {
      key=$1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
      if (key == wanted) {
        sub(/^[^=]*=/, "")
        gsub(/\r$/, "")
        gsub(/^[[:space:]]+|[[:space:]]+$/, "")
        print
        exit
      }
    }
  ' "$ENV_FILE"
}

required_env_value() {
  local value
  value="$(env_value "$1" || true)"
  [[ -n "$value" ]] || die "$ENV_FILE 缺少 $1"
  printf '%s' "$value"
}

backup_dir_value() {
  local value
  value="$(env_value COPPER_BACKUP_DIR || true)"
  printf '%s' "${value:-/var/backups/copper}"
}

restore_root_value() {
  local value
  value="$(env_value COPPER_RESTORE_ROOT || true)"
  printf '%s' "${value:-/var/lib/copper-restores}"
}

verify_sha256_sidecar() {
  local file="$1" sidecar="$2" expected_name="$3" expected_hash recorded_name extra actual_hash
  [[ -f "$file" && -f "$sidecar" ]] || die "SHA-256 校验目标或校验文件不存在"
  [[ "$(wc -l < "$sidecar")" -eq 1 ]] || die "SHA-256 校验文件格式不正确：$sidecar"
  read -r expected_hash recorded_name extra < "$sidecar"
  [[ "$expected_hash" =~ ^[0-9a-f]{64}$ && "$recorded_name" == "$expected_name" && -z "${extra:-}" ]] \
    || die "SHA-256 校验文件内容不正确：$sidecar"
  actual_hash="$(sha256sum "$file" | awk '{print $1}')"
  [[ "$actual_hash" == "$expected_hash" ]] || die "SHA-256 校验失败：$file"
  echo "$expected_name: OK"
}

assert_safe_paths() {
  local etc_dir var_dir backup_dir restore_root test_mode
  etc_dir="$(required_env_value COPPER_ETC_DIR)"
  var_dir="$(required_env_value COPPER_VAR_DIR)"
  backup_dir="$(backup_dir_value)"
  restore_root="$(restore_root_value)"
  test_mode="${COPPER_TEST_MODE:-0}"

  if [[ "$test_mode" == "1" ]]; then
    if [[ "$etc_dir" == "/etc/copper" && "$var_dir" == "/var/lib/copper" ]]; then
      [[ "${COPPER_PERSISTENT_REBOOT_TEST:-0}" == "1" ]] || die "测试模式使用正式持久目录时必须显式设置 COPPER_PERSISTENT_REBOOT_TEST=1"
      [[ "$ENV_FILE" == "/etc/copper/copper.env" ]] || die "持久化重启测试配置文件必须是 /etc/copper/copper.env"
      [[ "$backup_dir" == "/var/backups/copper" ]] || die "持久化测试 COPPER_BACKUP_DIR 必须是 /var/backups/copper"
    else
      [[ "$etc_dir" == /tmp/copper-* || "$etc_dir" == /opt/copper-* ]] || die "测试模式 COPPER_ETC_DIR 必须位于 /tmp/copper-* 或 /opt/copper-*，或使用完整正式目录对 /etc/copper 与 /var/lib/copper"
      [[ "$var_dir" == /tmp/copper-* || "$var_dir" == /opt/copper-* ]] || die "测试模式 COPPER_VAR_DIR 必须位于 /tmp/copper-* 或 /opt/copper-*，或使用完整正式目录对 /etc/copper 与 /var/lib/copper"
      [[ "$backup_dir" == /tmp/copper-* || "$backup_dir" == /opt/copper-* ]] || die "测试模式 COPPER_BACKUP_DIR 必须位于 /tmp/copper-* 或 /opt/copper-*"
      [[ "$restore_root" == "/var/lib/copper-restores" || "$restore_root" == /tmp/copper-* || "$restore_root" == /opt/copper-* ]] || die "测试模式 COPPER_RESTORE_ROOT 必须是默认正式路径或位于 /tmp/copper-*、/opt/copper-*"
      [[ "$ENV_FILE" == "$etc_dir"/* ]] || die "测试模式配置文件必须位于 COPPER_ETC_DIR 内"
    fi
  else
    [[ "$etc_dir" == "/etc/copper" ]] || die "生产 COPPER_ETC_DIR 必须是 /etc/copper"
    [[ "$var_dir" == "/var/lib/copper" ]] || die "生产 COPPER_VAR_DIR 必须是 /var/lib/copper"
    [[ "$backup_dir" == "/var/backups/copper" ]] || die "生产 COPPER_BACKUP_DIR 必须是 /var/backups/copper"
    [[ "$restore_root" == "/var/lib/copper-restores" ]] || die "生产 COPPER_RESTORE_ROOT 必须是 /var/lib/copper-restores"
    [[ "$ENV_FILE" == "/etc/copper/copper.env" ]] || die "生产配置文件必须是 /etc/copper/copper.env"
  fi

  [[ "$restore_root" != "$var_dir" && "$restore_root" != "$var_dir"/* && "$var_dir" != "$restore_root"/* ]] \
    || die "COPPER_RESTORE_ROOT 与 COPPER_VAR_DIR 不能互相包含"
  [[ "$restore_root" != "$backup_dir" && "$restore_root" != "$backup_dir"/* && "$backup_dir" != "$restore_root"/* ]] \
    || die "COPPER_RESTORE_ROOT 与 COPPER_BACKUP_DIR 不能互相包含"
}

validate_image_reference() {
  local name="$1" value="$2" delivery="${3:-registry}"
  if [[ "${COPPER_ALLOW_MUTABLE_IMAGES:-0}" == "1" && "${COPPER_TEST_MODE:-0}" == "1" ]]; then
    [[ "$value" != *:latest ]] || die "$name 即使在测试模式也禁止 latest"
    return
  fi
  if [[ "$value" =~ @sha256:[0-9a-f]{64}$ ]]; then
    return
  fi
  if [[ "$delivery" == "offline" && "$value" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    return
  fi
  die "$name 必须使用 registry @sha256 摘要；离线交付只允许经过离线清单校验的 sha256 镜像 ID"
}

validate_tools() {
  require_command docker
  require_command openssl
  require_command awk

  docker info >/dev/null 2>&1 || die "Docker Engine 不可用或当前用户无权访问"
  local engine_version compose_version
  engine_version="$(docker version --format '{{.Server.Version}}')"
  compose_version="$(docker compose version --short)"
  [[ "$engine_version" == 29.* ]] || die "M1 仅支持 Docker Engine 29.x，当前为 $engine_version"
  [[ "$compose_version" == 5.* ]] || die "M1 仅支持 Docker Compose Plugin 5.x，当前为 $compose_version"
}

ocr_env_value() {
  local value
  value="$(env_value "$1" || true)"
  printf '%s' "${value:-${2:-}}"
}

validate_ocr_configuration() {
  python3 "$ROOT_DIR/scripts/ocr-config.py" config \
    --enabled "$(ocr_env_value COPPER_OCR_ENABLED false)" \
    --endpoint "$(ocr_env_value COPPER_OCR_ENDPOINT)" \
    --network "$(ocr_env_value COPPER_OCR_NETWORK)" \
    --engine "$(ocr_env_value COPPER_OCR_ENGINE PADDLEOCR_LOCAL)" \
    --model "$(ocr_env_value COPPER_OCR_MODEL PP-OCRv6-medium)" \
    --timeout "$(ocr_env_value COPPER_OCR_TIMEOUT_SECONDS 120)"
}

validate_ocr_network() {
  [[ "$(ocr_env_value COPPER_OCR_ENABLED false)" == "true" ]] || return 0
  validate_ocr_configuration || return 1
  local network inspection
  network="$(ocr_env_value COPPER_OCR_NETWORK)"
  inspection="$(docker network inspect "$network" 2>/dev/null)" \
    || { echo "错误：OCR 私网不存在；先创建独立 internal 网络，或将 COPPER_OCR_ENABLED=false 后继续人工流程" >&2; return 1; }
  printf '%s' "$inspection" | python3 "$ROOT_DIR/scripts/ocr-config.py" network --name "$network"
}

validate_environment() {
  [[ -f "$ENV_FILE" ]] || die "未找到 $ENV_FILE，请先执行 scripts/prepare-host.sh"
  assert_safe_paths
  validate_ocr_configuration || die "OCR 配置无效；修正可选配置，或设为 false 并清空无效地址/网络，恢复示例中的引擎、模型与超时"

  local site_id domain mode tls_mode delivery web_image backend_image mysql_image etc_dir restore_port
  site_id="$(required_env_value COPPER_SITE_ID)"
  domain="$(required_env_value COPPER_DOMAIN)"
  mode="$(required_env_value COPPER_DEPLOY_MODE)"
  tls_mode="$(env_value COPPER_TLS_MODE || true)"
  tls_mode="${tls_mode:-domain}"
  delivery="$(env_value COPPER_IMAGE_DELIVERY || true)"
  delivery="${delivery:-registry}"
  web_image="$(required_env_value COPPER_WEB_IMAGE)"
  backend_image="$(required_env_value COPPER_BACKEND_IMAGE)"
  mysql_image="$(required_env_value COPPER_MYSQL_IMAGE)"
  etc_dir="$(required_env_value COPPER_ETC_DIR)"
  restore_port="$(env_value COPPER_RESTORE_PROXY_PORT || true)"
  restore_port="${restore_port:-18080}"

  [[ "$site_id" =~ ^[a-z0-9][a-z0-9-]{2,31}$ ]] || die "COPPER_SITE_ID 只能使用 3-32 位小写字母、数字和短横线"
  [[ "$mode" == "standard" || "$mode" == "external-proxy" || "$mode" == "lan" ]] || die "COPPER_DEPLOY_MODE 仅允许 standard、external-proxy 或 lan"
  [[ "$tls_mode" == "domain" || "$tls_mode" == "ip" || "$tls_mode" == "lan" ]] || die "COPPER_TLS_MODE 仅允许 domain、ip 或 lan"
  if [[ "$tls_mode" == "ip" ]]; then
    [[ "$mode" == "standard" ]] || die "公网 IP 证书模式仅支持 standard 部署模式"
    command -v python3 >/dev/null 2>&1 || die "COPPER_TLS_MODE=ip 需要系统自带 python3 完成公网地址校验和首次证书签发"
    python3 -c 'import ipaddress, sys; value = ipaddress.ip_address(sys.argv[1]); raise SystemExit(0 if value.version == 4 and value.is_global and not value.is_multicast and not value.is_reserved else 1)' "$domain" >/dev/null 2>&1 || die "COPPER_TLS_MODE=ip 时 COPPER_DOMAIN 必须填写固定公网 IPv4 地址"
  else
    [[ "$domain" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$ && "$domain" == *.* && "$domain" != *..* && "$domain" != *".-"* && "$domain" != *"-."* ]] || die "COPPER_DOMAIN 必须是域名且不能包含协议、路径或非法标签边界"
    [[ "$domain" != "example.com" && "$domain" != *.example.com ]] || die "COPPER_DOMAIN 仍是示例域名，请填写客户真实域名"
  fi
  if [[ "$mode" == "lan" || "$tls_mode" == "lan" ]]; then
    [[ "$mode" == "lan" && "$tls_mode" == "lan" ]] || die "LAN 部署必须同时设置 COPPER_DEPLOY_MODE=lan 和 COPPER_TLS_MODE=lan"
    [[ "$(required_env_value COPPER_LAN_ADDRESS_RESERVED)" == "yes" ]] || die "LAN 正式模式必须先完成固定地址或 DHCP 保留，并设置 COPPER_LAN_ADDRESS_RESERVED=yes"
    [[ "$(required_env_value COPPER_LAN_DOMAIN_CONTROL_CONFIRMED)" == "yes" ]] || die "LAN 正式模式必须确认客户控制域名，并设置 COPPER_LAN_DOMAIN_CONTROL_CONFIRMED=yes"
    [[ "$(required_env_value COPPER_LAN_NAME_RESOLUTION)" == "dns" ]] || die "LAN 正式模式只接受受控 DNS；mDNS 和 hosts 只能排障"
    [[ -n "$(required_env_value COPPER_LAN_BIND_IP)" && -n "$(required_env_value COPPER_LAN_INTERFACE)" \
       && -n "$(required_env_value COPPER_LAN_DNS_SERVER)" && -n "$(required_env_value COPPER_LAN_ALLOWED_CIDRS)" ]] \
      || die "LAN 模式缺少绑定地址、网卡、受控 DNS 服务器或允许网段"
  fi
  [[ "$delivery" == "registry" || "$delivery" == "offline" ]] || die "COPPER_IMAGE_DELIVERY 仅允许 registry 或 offline"
  [[ "$restore_port" =~ ^[0-9]+$ && "$restore_port" -ge 1024 && "$restore_port" -le 65535 ]] || die "COPPER_RESTORE_PROXY_PORT 必须是 1024-65535 的端口"
  [[ "$restore_port" != "8191" ]] || die "COPPER_RESTORE_PROXY_PORT 禁止使用正式 Backend 端口 8191"
  [[ "$web_image" != *REPLACE* && "$backend_image" != *REPLACE* ]] || die "前后端镜像仍是占位值，请填写发布清单中的不可变摘要"

  validate_image_reference COPPER_WEB_IMAGE "$web_image" "$delivery"
  validate_image_reference COPPER_BACKEND_IMAGE "$backend_image" "$delivery"
  validate_image_reference COPPER_MYSQL_IMAGE "$mysql_image" "$delivery"

  local remote_provider remote_enabled cloudflared_image cloudflare_hostname cloudflare_token_file cloudflare_metrics_port cloudflare_token_value cloudflare_token_real cloudflare_token_parent
  remote_provider="$(env_value COPPER_REMOTE_ACCESS_PROVIDER || true)"
  remote_provider="${remote_provider:-none}"
  remote_enabled="$(env_value COPPER_REMOTE_ACCESS_ENABLED || true)"
  remote_enabled="${remote_enabled:-no}"
  [[ "$remote_provider" == "none" || "$remote_provider" == "cloudflare" ]] \
    || die "COPPER_REMOTE_ACCESS_PROVIDER 一期仅允许 none 或 cloudflare"
  [[ "$remote_enabled" == "yes" || "$remote_enabled" == "no" ]] \
    || die "COPPER_REMOTE_ACCESS_ENABLED 仅允许 yes 或 no"
  [[ "$remote_provider" != "none" || "$remote_enabled" == "no" ]] \
    || die "未配置远程访问提供商时不能启用远程访问"
  if [[ "$remote_provider" == "cloudflare" ]]; then
    [[ "$mode" == "lan" && "$tls_mode" == "lan" ]] \
      || die "Cloudflare Tunnel 一期只支持 COPPER_DEPLOY_MODE=lan 且 COPPER_TLS_MODE=lan"
    cloudflared_image="$(required_env_value COPPER_CLOUDFLARED_IMAGE)"
    cloudflare_hostname="$(required_env_value COPPER_CLOUDFLARE_HOSTNAME)"
    cloudflare_token_file="$(required_env_value COPPER_CLOUDFLARE_TOKEN_FILE)"
    cloudflare_metrics_port="$(env_value COPPER_CLOUDFLARE_METRICS_PORT || true)"
    cloudflare_metrics_port="${cloudflare_metrics_port:-20241}"
    [[ "$cloudflare_hostname" == "$domain" ]] \
      || die "COPPER_CLOUDFLARE_HOSTNAME 必须与 COPPER_DOMAIN 相同，LAN 与互联网访问才保持同源"
    validate_image_reference COPPER_CLOUDFLARED_IMAGE "$cloudflared_image" "registry"
    [[ "$cloudflared_image" == "cloudflare/cloudflared@sha256:4f6655284ab3d252b7f28fedb19fe6c8fc82ee5b1295c20ac74d475e5398a52d" ]] \
      || die "COPPER_CLOUDFLARED_IMAGE 必须使用当前发布已审核的 cloudflared 摘要"
    [[ "$cloudflare_metrics_port" =~ ^[0-9]+$ && "$cloudflare_metrics_port" -ge 1024 && "$cloudflare_metrics_port" -le 65535 ]] \
      || die "COPPER_CLOUDFLARE_METRICS_PORT 必须是 1024-65535 的端口"
    case "$cloudflare_metrics_port" in
      3306|8080|8191|"$restore_port") die "COPPER_CLOUDFLARE_METRICS_PORT 与 Copper 保留端口冲突" ;;
    esac
    [[ -d "$etc_dir/remote-access" && ! -L "$etc_dir/remote-access" \
       && -d "$etc_dir/remote-access/cloudflare" && ! -L "$etc_dir/remote-access/cloudflare" ]] \
      || die "Cloudflare Tunnel token 目录必须是 root 管理的普通目录且不能是符号链接"
    [[ -f "$cloudflare_token_file" && ! -L "$cloudflare_token_file" ]] \
      || die "缺少普通文件 Cloudflare Tunnel token：$cloudflare_token_file"
    cloudflare_token_real="$(readlink -f -- "$cloudflare_token_file")"
    cloudflare_token_parent="$(readlink -f -- "$etc_dir/remote-access/cloudflare")"
    [[ "$cloudflare_token_real" == "$cloudflare_token_parent/"* ]] \
      || die "Cloudflare Tunnel token 必须位于 $cloudflare_token_parent/"
    [[ "$(wc -l < "$cloudflare_token_file")" -eq 1 ]] \
      || die "Cloudflare Tunnel token 必须是单行文件"
    cloudflare_token_value="$(tr -d '\n' < "$cloudflare_token_file")"
    [[ "${#cloudflare_token_value}" -ge 100 && "$cloudflare_token_value" == eyJ* ]] \
      || die "Cloudflare Tunnel token 长度异常，请重新配置"
    [[ ! "$cloudflare_token_value" =~ [[:space:]] ]] \
      || die "Cloudflare Tunnel token 不能包含空白字符"
    if [[ "${COPPER_TEST_MODE:-0}" != "1" ]]; then
      [[ "$(stat -c '%u:%g %a' "$cloudflare_token_file")" == "0:0 600" ]] \
        || die "Cloudflare Tunnel token 必须为 root:root 0600"
    fi
  fi

  local secret
  for secret in copper_db_password mysql_root_password; do
    [[ -s "$etc_dir/secrets/$secret" ]] || die "缺少密钥文件 $etc_dir/secrets/$secret"
  done
  [[ "$(wc -c < "$etc_dir/secrets/copper_db_password")" -ge 33 ]] || die "数据库业务口令长度不足"
}

compose_cmd() {
  local mode tls_mode remote_provider remote_enabled
  mode="$(required_env_value COPPER_DEPLOY_MODE)"
  tls_mode="$(env_value COPPER_TLS_MODE || true)"
  tls_mode="${tls_mode:-domain}"
  local args=(docker compose --env-file "$ENV_FILE" -p "$(required_env_value COPPER_PROJECT_NAME)" -f "$ROOT_DIR/compose.yaml")
  if [[ "$mode" == "external-proxy" ]]; then
    args+=(-f "$ROOT_DIR/compose.external-proxy.yaml")
  fi
  if [[ "$mode" == "lan" ]]; then
    args+=(-f "$ROOT_DIR/compose.lan.yaml")
  fi
  if [[ "$tls_mode" == "ip" ]]; then
    args+=(-f "$ROOT_DIR/compose.ip-tls.yaml")
  fi
  remote_provider="$(env_value COPPER_REMOTE_ACCESS_PROVIDER || true)"
  remote_provider="${remote_provider:-none}"
  remote_enabled="$(env_value COPPER_REMOTE_ACCESS_ENABLED || true)"
  remote_enabled="${remote_enabled:-no}"
  if [[ "$remote_provider" == "cloudflare" ]]; then
    args+=(-f "$ROOT_DIR/compose.remote-access-cloudflare.yaml")
    if [[ "$remote_enabled" == "yes" ]]; then
      args+=(--profile remote-access-cloudflare)
    fi
  fi
  if [[ "$(ocr_env_value COPPER_OCR_ENABLED false)" == "true" ]]; then
    args+=(-f "$ROOT_DIR/compose.ocr.yaml")
    case "${1:-}" in
      up|start|restart) validate_ocr_network || return 1 ;;
    esac
  fi
  (
    # Compose gives exported variables precedence over --env-file. The validated
    # file must also control actual paths/network/image interpolation in stages.
    local inherited_name
    while IFS= read -r inherited_name; do
      case "$inherited_name" in COPPER_*) unset "$inherited_name" ;; esac
    done < <(compgen -e)
    "${args[@]}" "$@"
  )
}
