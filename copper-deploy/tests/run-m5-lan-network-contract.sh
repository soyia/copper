#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/lib.sh"

require_root
require_command docker
require_command ip
require_command openssl
require_command resolvectl
require_command python3

ENV_FILE="${1:-}"
[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || die "用法：$0 /path/to/isolated-lan.env"
export COPPER_ENV_FILE="$ENV_FILE"

namespace=copper-m5-lan-contract
host_interface="$(required_env_value COPPER_LAN_INTERFACE)"
bind_ip="$(required_env_value COPPER_LAN_BIND_IP)"
dns_ip="$(required_env_value COPPER_LAN_DNS_SERVER)"
domain="$(required_env_value COPPER_DOMAIN)"
etc_dir="$(required_env_value COPPER_ETC_DIR)"
var_dir="$(required_env_value COPPER_VAR_DIR)"
backup_dir="$(backup_dir_value)"
restore_root="$(restore_root_value)"
runtime="${COPPER_M5_LAN_CONTRACT_RUNTIME:-/tmp/copper-m5-lan-contract}"
cert_work="$runtime/cert-work"
before_all="$runtime/containers-all-before"
before_running="$runtime/containers-running-before"

[[ "$host_interface" == "copperlan0" && "$bind_ip" == "192.168.250.1" && "$dns_ip" == "192.168.250.2" ]] \
  || die "隔离合同固定使用 copperlan0/192.168.250.1 与 DNS 192.168.250.2"
[[ "$etc_dir" == /tmp/copper-* || "$etc_dir" == /opt/copper-* ]] || die "隔离配置目录必须位于 /tmp/copper-* 或 /opt/copper-*"
[[ "$var_dir" == /tmp/copper-* || "$var_dir" == /opt/copper-* ]] || die "隔离数据目录必须位于 /tmp/copper-* 或 /opt/copper-*"
[[ ! -e "$etc_dir" && ! -e "$var_dir" && ! -e "$backup_dir" && ! -e "$restore_root" ]] \
  || die "隔离 LAN 合同目录已存在，拒绝覆盖"
! ip netns list | awk '{print $1}' | grep -Fxq "$namespace" || die "隔离网络命名空间已存在"
! iptables -S COPPER-LAN >/dev/null 2>&1 || die "主机已有 COPPER-LAN 链，拒绝覆盖"

cleanup() {
  set +e
  COPPER_ENV_FILE="$ENV_FILE" "$ROOT_DIR/scripts/lan-firewall.sh" remove >/dev/null 2>&1
  resolvectl revert "$host_interface" >/dev/null 2>&1
  ip netns pids "$namespace" 2>/dev/null | xargs -r kill >/dev/null 2>&1
  ip netns del "$namespace" >/dev/null 2>&1
  ip link del "$host_interface" >/dev/null 2>&1
  rm -rf "$etc_dir" "$var_dir" "$backup_dir" "$restore_root"
  rm -rf "$cert_work"
}
trap cleanup EXIT

install -d -o root -g root -m 0700 "$runtime"
docker ps -aq | sort > "$before_all"
docker ps -q | sort > "$before_running"

ip netns add "$namespace"
ip link add "$host_interface" type veth peer name eth0 netns "$namespace"
ip address add "$bind_ip/24" dev "$host_interface"
ip link set "$host_interface" up
ip -n "$namespace" link set lo up
ip -n "$namespace" address add "$dns_ip/24" dev eth0
ip -n "$namespace" address add 192.168.250.3/24 dev eth0
ip -n "$namespace" link set eth0 up

ip netns exec "$namespace" sh -c "python3 '$ROOT_DIR/tests/lan-test-dns-server.py' --listen '$dns_ip' --domain '$domain' --answer '$bind_ip' >'$runtime/dns.log' 2>&1 &"
for _ in $(seq 1 30); do
  if ip netns exec "$namespace" python3 - "$dns_ip" <<'PY'
import socket,sys
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.settimeout(0.2)
try:
    s.sendto(b"probe",(sys.argv[1],53)); print("ready")
except OSError:
    raise SystemExit(1)
PY
  then
    break
  fi
  sleep 0.2
done
resolvectl dns "$host_interface" "$dns_ip"
resolvectl domain "$host_interface" "~$(cut -d. -f2- <<< "$domain")"
resolvectl default-route "$host_interface" no
for _ in $(seq 1 30); do
  if getent ahostsv4 "$domain" | awk '{print $1}' | sort -u | grep -Fxq "$bind_ip"; then
    break
  fi
  sleep 0.2
done

install -d -o root -g root -m 0700 "$cert_work"
openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=$domain" \
  -addext "subjectAltName=DNS:$domain" -keyout "$cert_work/selfsigned.key" -out "$cert_work/selfsigned.pem" >/dev/null 2>&1
set +e
COPPER_ENV_FILE="$ENV_FILE" "$ROOT_DIR/scripts/lan-certificate.sh" install \
  --fullchain "$cert_work/selfsigned.pem" --private-key "$cert_work/selfsigned.key" \
  --trust-anchor "$cert_work/selfsigned.pem" > "$runtime/selfsigned.out" 2> "$runtime/selfsigned.err"
selfsigned_code=$?
set -e
[[ "$selfsigned_code" -ne 0 ]] || die "单张自签名叶证书未被阻断"
grep -q "禁止单张自签名叶证书" "$runtime/selfsigned.err"

openssl req -x509 -newkey rsa:2048 -nodes -days 730 -subj '/CN=Copper M5 Test Root CA' \
  -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -keyout "$cert_work/ca.key" -out "$cert_work/ca.pem" >/dev/null 2>&1
openssl req -newkey rsa:1024 -nodes -subj "/CN=$domain" -addext "subjectAltName=DNS:$domain" \
  -keyout "$cert_work/weak-rsa.key" -out "$cert_work/weak-rsa.csr" >/dev/null 2>&1
printf 'subjectAltName=DNS:%s\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$domain" \
  > "$cert_work/weak-rsa.ext"
openssl x509 -req -in "$cert_work/weak-rsa.csr" -CA "$cert_work/ca.pem" -CAkey "$cert_work/ca.key" \
  -CAcreateserial -days 365 -extfile "$cert_work/weak-rsa.ext" -out "$cert_work/weak-rsa.pem" >/dev/null 2>&1
set +e
COPPER_ENV_FILE="$ENV_FILE" "$ROOT_DIR/scripts/lan-certificate.sh" install \
  --fullchain "$cert_work/weak-rsa.pem" --private-key "$cert_work/weak-rsa.key" \
  --trust-anchor "$cert_work/ca.pem" > "$runtime/weak-rsa.out" 2> "$runtime/weak-rsa.err"
weak_rsa_code=$?
set -e
[[ "$weak_rsa_code" -ne 0 ]] || die "RSA 1024 弱私钥未被阻断"
grep -q 'RSA 私钥强度不足 2048 bit' "$runtime/weak-rsa.err"

openssl req -newkey rsa:2048 -nodes -subj "/CN=$domain" -addext "subjectAltName=DNS:$domain" \
  -keyout "$cert_work/sha1.key" -out "$cert_work/sha1.csr" >/dev/null 2>&1
printf 'subjectAltName=DNS:%s\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$domain" \
  > "$cert_work/sha1.ext"
openssl x509 -req -sha1 -in "$cert_work/sha1.csr" -CA "$cert_work/ca.pem" -CAkey "$cert_work/ca.key" \
  -days 365 -extfile "$cert_work/sha1.ext" -out "$cert_work/sha1.pem" >/dev/null 2>&1
set +e
COPPER_ENV_FILE="$ENV_FILE" "$ROOT_DIR/scripts/lan-certificate.sh" install \
  --fullchain "$cert_work/sha1.pem" --private-key "$cert_work/sha1.key" \
  --trust-anchor "$cert_work/ca.pem" > "$runtime/sha1.out" 2> "$runtime/sha1.err"
sha1_code=$?
set -e
[[ "$sha1_code" -ne 0 ]] || die "SHA-1 弱签名叶证书未被阻断"
grep -q 'MD5 或 SHA-1 弱签名算法' "$runtime/sha1.err"

openssl req -newkey rsa:2048 -nodes -subj "/CN=$domain" -addext "subjectAltName=DNS:$domain" \
  -keyout "$cert_work/leaf.key" -out "$cert_work/leaf.csr" >/dev/null 2>&1
printf 'subjectAltName=DNS:%s\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$domain" \
  > "$cert_work/leaf.ext"
openssl x509 -req -in "$cert_work/leaf.csr" -CA "$cert_work/ca.pem" -CAkey "$cert_work/ca.key" \
  -CAcreateserial -days 365 -extfile "$cert_work/leaf.ext" -out "$cert_work/leaf.pem" >/dev/null 2>&1

COPPER_ENV_FILE="$ENV_FILE" "$ROOT_DIR/scripts/lan-certificate.sh" install \
  --fullchain "$cert_work/leaf.pem" --private-key "$cert_work/leaf.key" \
  --trust-anchor "$cert_work/ca.pem" > "$runtime/certificate-install.out"
[[ "$(stat -c '%u:%g %a' "$etc_dir/tls/lan-fullchain.pem")" == "0:10001 640" ]]
[[ "$(stat -c '%u:%g %a' "$etc_dir/tls/lan-privkey.pem")" == "0:10001 640" ]]
[[ "$(stat -c '%u:%g %a' "$etc_dir/tls/lan-ca.pem")" == "0:0 644" ]]
[[ ! -e "$etc_dir/tls/ca.key" ]]

"$ROOT_DIR/scripts/lan-access.py" preflight --env-file "$ENV_FILE" --json > "$runtime/preflight.json"
python3 - "$runtime/preflight.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1],encoding="utf-8"))
assert x["status"]=="PASS" and x["writeAllowed"] is True
assert x["dnsServer"]=="192.168.250.2"
assert all(i["status"]=="PASS" for i in x["checks"])
PY

COPPER_ENV_FILE="$ENV_FILE" "$ROOT_DIR/scripts/lan-firewall.sh" apply > "$runtime/firewall-apply.out"
COPPER_ENV_FILE="$ENV_FILE" "$ROOT_DIR/scripts/lan-firewall.sh" status
"$ROOT_DIR/scripts/lan-access.py" status --env-file "$ENV_FILE" --json > "$runtime/status.json"
python3 - "$runtime/status.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1],encoding="utf-8"))
assert x["status"]=="PASS" and any(i["id"]=="firewall.runtime" and i["status"]=="PASS" for i in x["checks"])
PY

docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/compose.yaml" -f "$ROOT_DIR/compose.lan.yaml" config --format json \
  > "$runtime/compose.json"
python3 - "$runtime/compose.json" "$bind_ip" <<'PY'
import json,sys
x=json.load(open(sys.argv[1],encoding="utf-8")); bind=sys.argv[2]
web=x["services"]["web"]
actual={(str(p.get("host_ip")),int(p["published"]),p["protocol"]) for p in web["ports"]}
assert actual=={(bind,80,"tcp"),(bind,443,"tcp"),(bind,443,"udp")}
assert not x["services"]["mysql"].get("ports") and not x["services"]["backend"].get("ports")
PY

docker ps -aq | sort > "$runtime/containers-all-after"
docker ps -q | sort > "$runtime/containers-running-after"
cmp "$before_all" "$runtime/containers-all-after"
cmp "$before_running" "$runtime/containers-running-after"

COPPER_ENV_FILE="$ENV_FILE" "$ROOT_DIR/scripts/lan-firewall.sh" remove >/dev/null
! iptables -S COPPER-LAN >/dev/null 2>&1
echo "m5_lan_network_contract=PASS"
echo "evidence=$runtime"
