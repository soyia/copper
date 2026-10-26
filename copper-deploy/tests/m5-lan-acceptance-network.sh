#!/usr/bin/env bash
set -Eeuo pipefail

state_root="${COPPER_M5_LAN_STATE_ROOT:-/opt/copper-m5-04-acceptance}"
state_file="$state_root/runtime/network.env"
[[ -f "$state_file" && ! -L "$state_file" ]] || { echo "missing $state_file" >&2; exit 1; }
# shellcheck disable=SC1090
source "$state_file"

namespace=copper-m5-lan-client
host_interface=copperlan0
dns_server=192.168.250.2
allowed_client=192.168.250.2
denied_client=192.168.250.3
resolver_dir="/etc/netns/$namespace"
dns_pid_file="$state_root/runtime/dns.pid"
dns_program="${COPPER_M5_LAN_RELEASE_ROOT:-/opt/copper-m5-04-release}/tests/lan-test-dns-server.py"

cleanup_network() {
  set +e
  if [[ -f "$dns_pid_file" ]]; then
    kill "$(< "$dns_pid_file")" >/dev/null 2>&1
  fi
  ip netns pids "$namespace" 2>/dev/null | xargs -r kill >/dev/null 2>&1
  resolvectl revert "$host_interface" >/dev/null 2>&1
  ip netns del "$namespace" >/dev/null 2>&1
  ip link del "$host_interface" >/dev/null 2>&1
  rm -rf "$resolver_dir"
  rm -f "$dns_pid_file"
}

start_network() {
  cleanup_network
  [[ "$BIND_IP" == "192.168.250.1" || "$BIND_IP" == "192.168.250.4" ]] || { echo "unsafe bind IP" >&2; exit 1; }
  [[ "$DOMAIN" =~ ^[a-z0-9.-]+$ ]] || { echo "unsafe domain" >&2; exit 1; }
  [[ -x "$dns_program" ]] || { echo "missing DNS test program" >&2; exit 1; }
  ip netns add "$namespace"
  ip link add "$host_interface" type veth peer name eth0 netns "$namespace"
  ip address add "$BIND_IP/24" dev "$host_interface"
  ip link set "$host_interface" up
  ip -n "$namespace" link set lo up
  ip -n "$namespace" address add "$allowed_client/24" dev eth0
  ip -n "$namespace" address add "$denied_client/24" dev eth0
  ip -n "$namespace" link set eth0 up
  install -d -o root -g root -m 0755 "$resolver_dir"
  printf 'nameserver %s\noptions timeout:1 attempts:2\n' "$dns_server" > "$resolver_dir/resolv.conf"
  ip netns exec "$namespace" sh -c "python3 '$dns_program' --listen '$dns_server' --domain '$DOMAIN' --answer '$BIND_IP' </dev/null >'$state_root/runtime/dns.log' 2>&1 & echo \$! >'$dns_pid_file'"
  resolvectl dns "$host_interface" "$dns_server"
  resolvectl domain "$host_interface" "~$(cut -d. -f2- <<< "$DOMAIN")"
  resolvectl default-route "$host_interface" no
  resolvectl flush-caches
  for _ in $(seq 1 50); do
    if getent ahostsv4 "$DOMAIN" | awk '{print $1}' | sort -u | grep -Fxq "$BIND_IP"; then
      return 0
    fi
    sleep 0.2
  done
  echo "controlled DNS did not resolve $DOMAIN to $BIND_IP" >&2
  return 1
}

case "${1:-}" in
  start) start_network ;;
  stop) cleanup_network ;;
  restart) cleanup_network; start_network ;;
  *) echo "usage: $0 start|stop|restart" >&2; exit 2 ;;
esac
