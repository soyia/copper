#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

require_lan_mode() {
  [[ "$(required_env_value COPPER_DEPLOY_MODE)" == "lan" && "$(required_env_value COPPER_TLS_MODE)" == "lan" ]] \
    || die "LAN 证书命令要求 COPPER_DEPLOY_MODE=lan 且 COPPER_TLS_MODE=lan"
  domain="$(required_env_value COPPER_DOMAIN)"
  etc_dir="$(required_env_value COPPER_ETC_DIR)"
  tls_dir="$etc_dir/tls"
}

validate_sources() {
  local fullchain="$1" private_key="$2" trust_anchor="$3" certificate_public key_public subject issuer key_info key_bits signature
  for source_file in "$fullchain" "$private_key" "$trust_anchor"; do
    [[ -f "$source_file" && ! -L "$source_file" ]] || die "证书输入必须是普通文件且不能是符号链接：$source_file"
  done
  openssl x509 -in "$fullchain" -noout -checkhost "$domain" >/dev/null 2>&1 || die "叶证书 SAN 不包含 $domain"
  openssl x509 -in "$fullchain" -noout -checkend 2592000 >/dev/null 2>&1 || die "叶证书有效期不足 30 天"
  openssl x509 -in "$trust_anchor" -noout -checkend 2592000 >/dev/null 2>&1 || die "客户根 CA 有效期不足 30 天"
  openssl x509 -in "$trust_anchor" -noout -ext basicConstraints 2>/dev/null | grep -Fq 'CA:TRUE' \
    || die "信任锚不是 basicConstraints CA:TRUE 的客户根 CA"
  certificate_public="$(openssl x509 -in "$fullchain" -noout -pubkey)"
  key_public="$(openssl pkey -in "$private_key" -pubout 2>/dev/null)" || die "无法读取私钥"
  [[ "$certificate_public" == "$key_public" ]] || die "叶证书与私钥不匹配"
  key_info="$(openssl pkey -in "$private_key" -pubout -text_pub -noout 2>/dev/null)" || die "无法检查私钥强度"
  key_bits="$(sed -n '1s/.*(\([0-9][0-9]*\) bit.*/\1/p' <<< "$key_info")"
  if grep -Fq 'ASN1 OID:' <<< "$key_info"; then
    [[ "$key_bits" =~ ^[0-9]+$ && "$key_bits" -ge 256 ]] || die "EC 私钥强度不足 256 bit"
  elif grep -Fq 'Modulus:' <<< "$key_info"; then
    [[ "$key_bits" =~ ^[0-9]+$ && "$key_bits" -ge 2048 ]] || die "RSA 私钥强度不足 2048 bit"
  else
    die "私钥算法不在当前受控范围；只接受 RSA>=2048 或 EC>=256"
  fi
  signature="$(openssl x509 -in "$fullchain" -noout -text | sed -n '/Signature Algorithm:/{s/^[[:space:]]*Signature Algorithm: //;p;q;}')"
  [[ -n "$signature" && ! "$signature" =~ [Mm][Dd]5 && ! "$signature" =~ [Ss][Hh][Aa]1 ]] \
    || die "叶证书使用空、MD5 或 SHA-1 弱签名算法"
  subject="$(openssl x509 -in "$fullchain" -noout -subject -nameopt RFC2253 | sed 's/^subject=//')"
  issuer="$(openssl x509 -in "$fullchain" -noout -issuer -nameopt RFC2253 | sed 's/^issuer=//')"
  [[ -n "$subject" && "$subject" != "$issuer" ]] || die "正式多人 LAN 禁止单张自签名叶证书；请用客户根 CA 签发叶证书"
  openssl verify -CAfile "$trust_anchor" -untrusted "$fullchain" "$fullchain" >/dev/null 2>&1 \
    || die "证书链不能由提供的客户 CA 信任锚验证"
}

install_files() {
  local fullchain="$1" private_key="$2" trust_anchor="$3" archive timestamp
  previous_archive=""
  install -d -o root -g 10001 -m 0750 "$tls_dir"
  install -d -o root -g root -m 0700 "$tls_dir/archive"
  if [[ -e "$tls_dir/lan-fullchain.pem" || -e "$tls_dir/lan-privkey.pem" || -e "$tls_dir/lan-ca.pem" ]]; then
    timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
    archive="$(mktemp -d "$tls_dir/archive/${timestamp}.XXXXXX")"
    chown root:root "$archive"
    chmod 0700 "$archive"
    previous_archive="$archive"
    for existing in lan-fullchain.pem lan-privkey.pem lan-ca.pem; do
      [[ -f "$tls_dir/$existing" ]] && install -o root -g root -m 0600 "$tls_dir/$existing" "$archive/$existing"
    done
  fi
  install -o root -g 10001 -m 0640 "$fullchain" "$tls_dir/lan-fullchain.pem.new"
  install -o root -g 10001 -m 0640 "$private_key" "$tls_dir/lan-privkey.pem.new"
  install -o root -g root -m 0644 "$trust_anchor" "$tls_dir/lan-ca.pem.new"
  mv -f "$tls_dir/lan-fullchain.pem.new" "$tls_dir/lan-fullchain.pem"
  mv -f "$tls_dir/lan-privkey.pem.new" "$tls_dir/lan-privkey.pem"
  mv -f "$tls_dir/lan-ca.pem.new" "$tls_dir/lan-ca.pem"
}

restart_web_with_certificate() {
  compose_cmd restart web
  compose_cmd up -d --remove-orphans --wait --wait-timeout 240 web
}

install_certificate() {
  local fullchain="" private_key="" trust_anchor="" reload=0
  shift
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --fullchain) fullchain="${2:-}"; shift 2 ;;
      --private-key) private_key="${2:-}"; shift 2 ;;
      --trust-anchor) trust_anchor="${2:-}"; shift 2 ;;
      --reload) reload=1; shift ;;
      *) die "未知 LAN 证书参数：$1" ;;
    esac
  done
  [[ -n "$fullchain" && -n "$private_key" && -n "$trust_anchor" ]] \
    || die "用法：lan certificate install --fullchain FILE --private-key FILE --trust-anchor FILE [--reload]"
  validate_sources "$fullchain" "$private_key" "$trust_anchor"
  install_files "$fullchain" "$private_key" "$trust_anchor"
  "$SCRIPT_DIR/lan-access.py" preflight --env-file "$ENV_FILE" >/dev/null \
    || die "证书已安全保存，但 LAN 预检仍未通过；请运行 copperctl lan preflight"
  if [[ "$reload" == "1" ]]; then
    if ! restart_web_with_certificate; then
      if [[ -n "${previous_archive:-}" && -f "$previous_archive/lan-fullchain.pem" && -f "$previous_archive/lan-privkey.pem" && -f "$previous_archive/lan-ca.pem" ]]; then
        install -o root -g 10001 -m 0640 "$previous_archive/lan-fullchain.pem" "$tls_dir/lan-fullchain.pem"
        install -o root -g 10001 -m 0640 "$previous_archive/lan-privkey.pem" "$tls_dir/lan-privkey.pem"
        install -o root -g root -m 0644 "$previous_archive/lan-ca.pem" "$tls_dir/lan-ca.pem"
        restart_web_with_certificate \
          || die "新证书加载失败，旧证书已恢复但 Web 仍未健康；保持停止并检查日志"
        die "新证书加载失败，已恢复旧证书和 Web；修复新证书后再轮换"
      fi
      die "首次 LAN 证书加载失败；证书文件已保留供诊断，禁止降级 HTTP"
    fi
  fi
  fingerprint="$(openssl x509 -in "$tls_dir/lan-ca.pem" -noout -fingerprint -sha256 | cut -d= -f2)"
  note "LAN 证书已安装并通过域名、私钥、信任链和 30 天有效期门槛；客户 CA SHA-256：$fingerprint"
}

certificate_status() {
  "$SCRIPT_DIR/lan-access.py" preflight --env-file "$ENV_FILE" "${@:2}"
}

require_root
require_command openssl
require_lan_mode
case "${1:-status}" in
  install) install_certificate "$@" ;;
  status) certificate_status "$@" ;;
  *) die "lan certificate 只支持 install 或 status" ;;
esac
