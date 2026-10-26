#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

CHAIN=COPPER-LAN

require_lan_configuration() {
  [[ "$(required_env_value COPPER_DEPLOY_MODE)" == "lan" ]] || die "LAN 防火墙仅用于 COPPER_DEPLOY_MODE=lan"
  [[ "$(required_env_value COPPER_TLS_MODE)" == "lan" ]] || die "LAN 防火墙要求 COPPER_TLS_MODE=lan"
  bind_ip="$(required_env_value COPPER_LAN_BIND_IP)"
  lan_interface="$(required_env_value COPPER_LAN_INTERFACE)"
  cidr_text="$(required_env_value COPPER_LAN_ALLOWED_CIDRS)"
  cidr_output="$(python3 - "$bind_ip" "$cidr_text" <<'PY'
import ipaddress
import sys
parents=[ipaddress.ip_network(v) for v in ("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16")]
address=ipaddress.ip_address(sys.argv[1])
if address.version != 4 or not any(address in item for item in parents):
    raise SystemExit(3)
networks=[]
for raw in sys.argv[2].split(","):
    network=ipaddress.ip_network(raw.strip(), strict=True)
    if network.version != 4 or not any(network.subnet_of(parent) for parent in parents):
        raise SystemExit(3)
    networks.append(str(network))
if not networks or not any(address in ipaddress.ip_network(item) for item in networks):
    raise SystemExit(3)
print("\n".join(sorted(set(networks))))
PY
  )" || die "COPPER_LAN_BIND_IP 或 COPPER_LAN_ALLOWED_CIDRS 不是受控 RFC1918 范围"
  [[ -n "$cidr_output" ]] || die "COPPER_LAN_ALLOWED_CIDRS 不能为空"
  mapfile -t allowed_cidrs <<< "$cidr_output"
  [[ -d "/sys/class/net/$lan_interface" ]] || die "LAN 网卡不存在：$lan_interface"
}

check_rule() {
  iptables -w 5 -C "$@" >/dev/null 2>&1
}

append_access_rule() {
  local cidr="$1" protocol="$2" port="$3"
  iptables -w 5 -A "$CHAIN" -i "$lan_interface" -s "$cidr" -p "$protocol" \
    -m conntrack --ctorigdst "$bind_ip" --ctorigdstport "$port" -j RETURN
}

append_response_rule() {
  local cidr="$1" protocol="$2" port="$3"
  iptables -w 5 -A "$CHAIN" -o "$lan_interface" -d "$cidr" -p "$protocol" \
    -m conntrack --ctstate ESTABLISHED,RELATED --ctorigdst "$bind_ip" --ctorigdstport "$port" -j RETURN
}

append_drop_rule() {
  local protocol="$1" port="$2"
  iptables -w 5 -A "$CHAIN" -p "$protocol" \
    -m conntrack --ctorigdst "$bind_ip" --ctorigdstport "$port" -j DROP
}

apply_rules() {
  require_command iptables
  require_lan_configuration
  iptables -w 5 -S DOCKER-USER >/dev/null 2>&1 || die "Docker 未提供 DOCKER-USER 防火墙链；禁止只依赖可能被 Docker 绕过的 UFW 规则"
  iptables -w 5 -N "$CHAIN" >/dev/null 2>&1 || true
  iptables -w 5 -F "$CHAIN"
  for cidr in "${allowed_cidrs[@]}"; do
    append_access_rule "$cidr" tcp 443
    append_access_rule "$cidr" udp 443
    append_access_rule "$cidr" tcp 80
    append_response_rule "$cidr" tcp 443
    append_response_rule "$cidr" udp 443
    append_response_rule "$cidr" tcp 80
  done
  append_drop_rule tcp 443
  append_drop_rule udp 443
  append_drop_rule tcp 80
  iptables -w 5 -A "$CHAIN" -j RETURN
  check_rule DOCKER-USER -j "$CHAIN" || iptables -w 5 -I DOCKER-USER 1 -j "$CHAIN"
  verify_rules
  note "LAN 防火墙已收口：仅 $lan_interface 上的 ${allowed_cidrs[*]} 可访问 $bind_ip 的 443；80 只用于 HTTPS 重定向"
}

verify_rules() {
  require_command iptables
  require_lan_configuration
  check_rule DOCKER-USER -j "$CHAIN" || die "DOCKER-USER 未挂接 $CHAIN"
  for cidr in "${allowed_cidrs[@]}"; do
    for pair in tcp:443 udp:443 tcp:80; do
      protocol="${pair%%:*}"
      port="${pair##*:}"
      check_rule "$CHAIN" -i "$lan_interface" -s "$cidr" -p "$protocol" \
        -m conntrack --ctorigdst "$bind_ip" --ctorigdstport "$port" -j RETURN \
        || die "$CHAIN 缺少 $cidr $protocol/$port 放行规则"
      check_rule "$CHAIN" -o "$lan_interface" -d "$cidr" -p "$protocol" \
        -m conntrack --ctstate ESTABLISHED,RELATED --ctorigdst "$bind_ip" --ctorigdstport "$port" -j RETURN \
        || die "$CHAIN 缺少 $cidr $protocol/$port 受控回程规则"
    done
  done
  for pair in tcp:443 udp:443 tcp:80; do
    protocol="${pair%%:*}"
    port="${pair##*:}"
    check_rule "$CHAIN" -p "$protocol" \
      -m conntrack --ctorigdst "$bind_ip" --ctorigdstport "$port" -j DROP \
      || die "$CHAIN 缺少 $protocol/$port 拒绝规则"
  done
}

remove_rules() {
  require_command iptables
  while check_rule DOCKER-USER -j "$CHAIN"; do
    iptables -w 5 -D DOCKER-USER -j "$CHAIN"
  done
  if iptables -w 5 -S "$CHAIN" >/dev/null 2>&1; then
    iptables -w 5 -F "$CHAIN"
    iptables -w 5 -X "$CHAIN"
  fi
  note "已移除 Copper LAN 专用防火墙规则；未修改 SSH、UFW 默认策略或其他容器规则"
}

require_root
case "${1:-status}" in
  apply)
    [[ $# -eq 1 ]] || die "lan firewall apply 不接受额外参数"
    apply_rules
    ;;
  status)
    [[ $# -eq 1 || ( $# -eq 2 && "$2" == "--quiet" ) ]] || die "lan firewall status 只接受 --quiet"
    verify_rules
    [[ "${2:-}" == "--quiet" ]] || note "LAN 防火墙规则完整"
    ;;
  remove)
    [[ $# -eq 1 ]] || die "lan firewall remove 不接受额外参数"
    remove_rules
    ;;
  *) die "lan firewall 只支持 apply、status 或 remove" ;;
esac
