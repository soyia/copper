#!/usr/bin/env bash
set -Eeuo pipefail

delivery_index="${1:-}"
index_sha256="${2:-}"
domain="${3:-}"
base="${4:-/opt/copper-m4-07}"
[[ -f "$delivery_index" && ! -L "$delivery_index" && "$index_sha256" =~ ^[0-9a-f]{64}$ \
   && "$domain" =~ ^[A-Za-z0-9][A-Za-z0-9.-]{1,251}[A-Za-z0-9]$ ]] || {
  echo "用法：sudo tests/run-m4-final-x86-acceptance.sh DELIVERY_INDEX INDEX_SHA256 DOMAIN [OUTPUT_ROOT]" >&2
  exit 2
}
[[ "$EUID" -eq 0 ]] || { echo "错误：M4-07 最终验收必须使用 sudo" >&2; exit 1; }
[[ "$(uname -m)" == "x86_64" ]] || { echo "错误：M4-07 只支持 x86_64" >&2; exit 1; }
. /etc/os-release
[[ "$ID/$VERSION_ID" == "ubuntu/24.04" ]] || { echo "错误：M4-07 只支持 Ubuntu 24.04" >&2; exit 1; }

for command in docker python3 curl openssl age age-keygen zstd iptables sha256sum tar awk sed ss; do
  command -v "$command" >/dev/null 2>&1 || { echo "错误：缺少 $command" >&2; exit 1; }
done
[[ "$(sha256sum "$delivery_index" | awk '{print $1}')" == "$index_sha256" ]] \
  || { echo "错误：离线交付索引 SHA-256 不匹配" >&2; exit 1; }
for port in 80 443 8191; do
  if ss -lnt | awk '{print $4}' | grep -Eq "(^|:|\])${port}$"; then
    echo "错误：宿主机端口 $port 已被占用；最终验收不得覆盖现有服务" >&2
    exit 1
  fi
done

run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
runtime="$base/runtime-$run_id"
evidence="$base/evidence-$run_id"
etc_dir="/etc/copper"
var_dir="/var/lib/copper"
backup_dir="/var/backups/copper"
restore_root="/var/lib/copper-restores"
offline_key="$runtime/offline-key"
cookie="$runtime/admin-cookie.txt"
project="copper-m407-${run_id,,}"
env_file="$etc_dir/copper.env"
delivery_index="$(cd "$(dirname "$delivery_index")" && pwd)/$(basename "$delivery_index")"
delivery_dir="$(dirname "$delivery_index")"
firewall_comment="copper-m407-$run_id"
firewall_active=0
site_started=0
success=0
production_paths_created=0
deploy_root=""

cleanup() {
  local exit_code=$?
  set +e
  if [[ "$success" == "1" && "${COPPER_M4_FINAL_KEEP_RUNNING:-0}" == "1" ]]; then
    echo "M4-07 站点按要求保持运行：$domain"
    echo "运行目录：$runtime"
    echo "证据目录：$evidence"
    return
  fi
  if [[ "$firewall_active" == "1" ]]; then
    iptables -D DOCKER-USER -m comment --comment "$firewall_comment" -j RETURN >/dev/null 2>&1 || true
    iptables -D DOCKER-USER -i "$edge_bridge" -m conntrack --ctstate NEW -m comment --comment "$firewall_comment" -j REJECT >/dev/null 2>&1 || true
  fi
  if [[ "$site_started" == "1" && -n "$deploy_root" && -f "$env_file" ]]; then
    env COPPER_ENV_FILE="$env_file" "$deploy_root/copperctl" uninstall >/dev/null 2>&1 \
      || env COPPER_ENV_FILE="$env_file" "$deploy_root/copperctl" down >/dev/null 2>&1 \
      || true
    site_started=0
  fi
  if [[ "$production_paths_created" == "1" ]]; then
    if [[ "$success" == "1" && -n "$deploy_root" && -f "$env_file" ]]; then
      if printf 'DELETE copper-m407-final\n' | env COPPER_ENV_FILE="$env_file" \
        "$deploy_root/scripts/permanently-delete-data.sh" --site-id copper-m407-final >/dev/null 2>&1; then
        production_paths_created=0
      fi
    fi
    if [[ "$production_paths_created" == "1" ]]; then
      rm -rf --one-file-system -- "$var_dir" "$etc_dir" "$backup_dir" "$restore_root"
    fi
  fi
  rm -rf --one-file-system -- "$runtime"
  exit "$exit_code"
}
trap cleanup EXIT

for production_path in "$etc_dir" "$var_dir" "$backup_dir" "$restore_root"; do
  [[ ! -e "$production_path" && ! -L "$production_path" ]] || {
    echo "错误：空白生产路径已存在，拒绝覆盖：$production_path" >&2
    exit 1
  }
done
install -d -o root -g root -m 0700 "$base" "$runtime" "$evidence"
install -d -o root -g root -m 0700 "$etc_dir" "$var_dir" "$backup_dir" "$restore_root"
production_paths_created=1

version="$(python3 - "$delivery_index" <<'PY'
import json, pathlib, sys
doc=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
version=doc.get("version")
if not isinstance(version,str): raise SystemExit("交付索引缺少版本")
print(version)
PY
)"
archive="$delivery_dir/copper-deploy-$version.tar.gz"
[[ -f "$archive" && ! -L "$archive" ]] || { echo "错误：交付介质缺少部署归档" >&2; exit 1; }
tar -xzf "$archive" -C "$runtime"
deploy_root="$(find "$runtime" -maxdepth 1 -type d -name 'copper-deploy-*' -print -quit)"
[[ -n "$deploy_root" && -x "$deploy_root/copperctl" ]] || { echo "错误：正式部署归档结构不正确" >&2; exit 1; }
manifest="$delivery_dir/copper-release-$version.env"
[[ -f "$manifest" && ! -L "$manifest" ]] || { echo "错误：交付介质缺少正式发布清单" >&2; exit 1; }
"$deploy_root/scripts/release-contract.py" validate-manifest "$manifest" > "$evidence/release-validate.txt"

"$deploy_root/copper-key-offline" generate --output-dir "$offline_key" \
  --site-id "copper-m407-$run_id" --customer-reference "m4-07-final-x86-acceptance" \
  > "$evidence/key-generation.json"
recipient="$(tr -d '\r\n' < "$offline_key/recipient.txt")"
handover_sha="$(sha256sum "$offline_key/handover-record.json" | awk '{print $1}')"
install -o root -g root -m 0600 "$deploy_root/config/copper.env.example" "$env_file"

python3 - "$env_file" "$project" "$version" "$domain" "$etc_dir" "$var_dir" "$backup_dir" "$restore_root" "$recipient" <<'PY'
import pathlib, sys
path=pathlib.Path(sys.argv[1])
values={
 "COPPER_SITE_ID":"copper-m407-final",
 "COPPER_PROJECT_NAME":sys.argv[2],
 "COPPER_RELEASE_VERSION":sys.argv[3],
 "COPPER_DEPLOY_MODE":"standard",
 "COPPER_DOMAIN":sys.argv[4],
 "COPPER_TLS_MODE":"domain",
 "COPPER_IMAGE_DELIVERY":"offline",
 "COPPER_ETC_DIR":sys.argv[5],
 "COPPER_VAR_DIR":sys.argv[6],
 "COPPER_BACKUP_DIR":sys.argv[7],
 "COPPER_RESTORE_ROOT":sys.argv[8],
 "COPPER_BACKUP_AGE_RECIPIENT":sys.argv[9],
 "COPPER_BACKUP_TARGETS":"local",
 "COPPER_BACKUP_ALERT_MODE":"local",
 "COPPER_MONITOR_ALERT_MODE":"webhook",
 "COPPER_MONITOR_ALERT_WEBHOOK_URL_FILE":f"{sys.argv[5]}/notification-credentials/monitor_webhook_url",
 "COPPER_MONITOR_ALERT_HMAC_KEY_FILE":f"{sys.argv[5]}/notification-credentials/monitor_webhook_hmac_key",
}
result=[]; seen=set()
for line in path.read_text(encoding="utf-8").splitlines():
 key=line.split("=",1)[0] if "=" in line and not line.lstrip().startswith("#") else ""
 if key in values:
  result.append(f"{key}={values[key]}"); seen.add(key)
 else: result.append(line)
for key,value in values.items():
 if key not in seen: result.append(f"{key}={value}")
path.write_text("\n".join(result)+"\n",encoding="utf-8")
PY

# Initial published references are needed only to validate the release contract before READY supplies local IDs.
for key in COPPER_WEB_IMAGE COPPER_BACKEND_IMAGE COPPER_MYSQL_IMAGE; do
  value="$(awk -F= -v wanted="$key" '$1==wanted {print substr($0,index($0,"=")+1)}' "$manifest")"
  sed -i.bak -E "s#^${key}=.*#${key}=${value}#" "$env_file"
  rm -f "$env_file.bak"
done
chmod 0600 "$env_file"

export COPPER_ENV_FILE="$env_file"
"$deploy_root/copperctl" release stage offline "$delivery_index" "$index_sha256" > "$evidence/release-stage.txt"
ready="$var_dir/releases/$version/ready.json"
"$deploy_root/copperctl" release status "$version" --json > "$evidence/release-status.json"
python3 - "$env_file" "$manifest" "$ready" <<'PY'
import json, pathlib, sys
env_path, manifest_path, ready_path=map(pathlib.Path,sys.argv[1:4])
def values(path):
 out={}
 for line in path.read_text(encoding="utf-8").splitlines():
  if line and not line.startswith("#") and "=" in line:
   key,value=line.split("=",1); out[key]=value
 return out
manifest=values(manifest_path); ready=json.loads(ready_path.read_text(encoding="utf-8"))
if ready.get("status")!="READY" or ready.get("mode")!="offline": raise SystemExit("正式离线 READY 无效")
keys={"web":"COPPER_WEB_IMAGE","backend":"COPPER_BACKEND_IMAGE","mysql":"COPPER_MYSQL_IMAGE"}
local={}
for image in ready.get("images",[]):
 role=image.get("role"); key=keys.get(role)
 if not key or image.get("reference")!=manifest.get(key): raise SystemExit(f"READY 未绑定发布摘要：{role}")
 local[key]=image.get("localId")
if set(local)!=set(keys.values()): raise SystemExit("READY 镜像角色不完整")
lines=[]
for line in env_path.read_text(encoding="utf-8").splitlines():
 key=line.split("=",1)[0] if "=" in line and not line.startswith("#") else ""
 lines.append(f"{key}={local[key]}" if key in local else line)
env_path.write_text("\n".join(lines)+"\n",encoding="utf-8")
PY

install -d -o root -g root -m 0700 "$etc_dir/notification-credentials"
printf '%s\n' 'https://192.0.2.1/copper-monitor' > "$etc_dir/notification-credentials/monitor_webhook_url"
openssl rand -hex 32 > "$etc_dir/notification-credentials/monitor_webhook_hmac_key"
chmod 0600 "$etc_dir/notification-credentials/"*

install_started="$(date +%s)"
site_started=1
"$deploy_root/copperctl" install > "$evidence/install.txt"
install_seconds="$(( $(date +%s) - install_started ))"

tls_ready=0
for _ in $(seq 1 90); do
  if curl --fail --silent --show-error --max-time 8 --resolve "$domain:443:127.0.0.1" "https://$domain/healthz" >/dev/null 2>&1; then
    tls_ready=1; break
  fi
  sleep 2
done
[[ "$tls_ready" == "1" ]] || { echo "错误：真实域名 TLS 未在 180 秒内就绪" >&2; exit 1; }
curl --fail --silent --show-error --resolve "$domain:443:127.0.0.1" "https://$domain/readyz" > "$evidence/https-readyz.txt"
openssl s_client -connect 127.0.0.1:443 -servername "$domain" -verify_return_error </dev/null 2> "$evidence/tls-verify.err" \
  | openssl x509 -noout -subject -issuer -dates -ext subjectAltName > "$evidence/tls-certificate.txt"
grep -Fq "DNS:$domain" "$evidence/tls-certificate.txt"
public_headers="$runtime/public-security-headers.txt"
curl --fail --silent --show-error --resolve "$domain:443:127.0.0.1" -D "$public_headers" -o /dev/null "https://$domain/"
python3 - "$public_headers" "$evidence/public-security-headers.json" <<'PY'
import json, pathlib, sys
headers = {}
for line in pathlib.Path(sys.argv[1]).read_text(encoding="iso-8859-1").splitlines():
    if ":" not in line:
        continue
    key, value = line.split(":", 1)
    headers[key.strip().lower()] = value.strip()
expected = {
    "content-security-policy": "default-src 'self'; base-uri 'self'; connect-src 'self'; font-src 'self' data:; form-action 'self'; frame-ancestors 'self'; img-src 'self' data: blob:; object-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'",
    "strict-transport-security": "max-age=31536000; includeSubDomains",
    "x-content-type-options": "nosniff",
    "x-frame-options": "SAMEORIGIN",
    "referrer-policy": "same-origin",
    "permissions-policy": "camera=(), geolocation=(), microphone=()",
}
checks = {key: headers.get(key) == value for key, value in expected.items()}
if not all(checks.values()):
    raise SystemExit("公网安全响应头不完整")
pathlib.Path(sys.argv[2]).write_text(
    json.dumps({"formatVersion": "copper-public-security-headers/v1", "checks": checks}, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
rm -f "$public_headers"

credential_file="$etc_dir/credentials/initial-admin.txt"
username="$(awk -F= '$1=="username" {print $2}' "$credential_file")"
initial_password="$(awk -F= '$1=="password" {print $2}' "$credential_file")"
new_password="M407!$(openssl rand -hex 18)aZ9"
login_headers="$runtime/login-headers.txt"
curl --fail --silent --show-error --resolve "$domain:443:127.0.0.1" -D "$login_headers" -o /dev/null \
  -c "$cookie" -H 'Content-Type: application/json' \
  --data "{\"username\":\"$username\",\"password\":\"$initial_password\"}" \
  "https://$domain/api/auth/login"
python3 - "$login_headers" "$evidence/cookie-security.json" <<'PY'
import json, pathlib, sys
headers=pathlib.Path(sys.argv[1]).read_text(encoding="iso-8859-1")
cookie=next((line for line in headers.splitlines() if line.lower().startswith("set-cookie:")),"")
checks={"secure":"; Secure" in cookie,"httpOnly":"; HttpOnly" in cookie,"sameSiteStrict":"SameSite=Strict" in cookie}
if not all(checks.values()): raise SystemExit("会话 Cookie 安全属性不完整")
pathlib.Path(sys.argv[2]).write_text(json.dumps({"formatVersion":"copper-cookie-acceptance/v1","checks":checks},sort_keys=True)+"\n",encoding="utf-8")
PY
curl --fail --silent --show-error --resolve "$domain:443:127.0.0.1" -b "$cookie" \
  -H 'Content-Type: application/json' --data "{\"currentPassword\":\"$initial_password\",\"newPassword\":\"$new_password\"}" \
  "https://$domain/api/auth/change-password" >/dev/null
rm -f "$cookie" "$login_headers"
curl --fail --silent --show-error --resolve "$domain:443:127.0.0.1" -c "$cookie" \
  -H 'Content-Type: application/json' --data "{\"username\":\"$username\",\"password\":\"$new_password\"}" \
  "https://$domain/api/auth/login" >/dev/null
if [[ "${COPPER_M4_FINAL_KEEP_RUNNING:-0}" == "1" ]]; then
  browser_credential="$runtime/browser-credential.txt"
  printf 'username=%s\npassword=%s\n' "$username" "$new_password" > "$browser_credential"
  chmod 0600 "$browser_credential"
fi
unset initial_password new_password
[[ ! -e "$credential_file" ]] || { echo "错误：首次管理员凭据使用后未销毁" >&2; exit 1; }

"$deploy_root/copperctl" security egress status --json > "$evidence/egress-before-isolation.json"
edge_network="${project}_edge"
edge_id="$(docker network inspect --format '{{.Id}}' "$edge_network")"
edge_bridge="$(docker network inspect --format '{{index .Options "com.docker.network.bridge.name"}}' "$edge_network")"
edge_bridge="${edge_bridge:-br-${edge_id:0:12}}"
iptables -I DOCKER-USER 1 -i "$edge_bridge" -m conntrack --ctstate NEW -m comment --comment "$firewall_comment" -j REJECT
firewall_active=1
iptables -L DOCKER-USER -v -n --line-numbers > "$evidence/egress-firewall.txt"
"$deploy_root/copperctl" security egress status --network-isolated --json > "$evidence/egress-isolated.json"
python3 - "$evidence/egress-isolated.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1],encoding="utf-8"))
assert x["publicInternetBlocked"] and x["coreBusiness"]=="AVAILABLE" and x["offlineReady"]
assert x["vendorControl"]=={"licenseHeartbeat":False,"mandatoryTelemetry":False,"remoteKillSwitch":False,"officialServiceEndpoints":[]}
assert "tls.acme" in x["degradedCapabilities"]
PY
web_container="$(docker compose --env-file "$env_file" -f "$deploy_root/compose.yaml" ps -q web)"
if docker exec "$web_container" wget -T 5 -q -O /dev/null https://example.com > "$evidence/egress-probe.out" 2> "$evidence/egress-probe.err"; then
  echo "错误：Web 容器在断网门槛下仍可访问公共互联网" >&2
  exit 1
fi

api_post() {
  local endpoint="$1" payload="$2" output="$3" code
  code="$(curl --silent --show-error --max-time 40 --resolve "$domain:443:127.0.0.1" \
    --output "$output" --write-out '%{http_code}' -b "$cookie" -H 'Content-Type: application/json' \
    --data "$payload" "https://$domain/api$endpoint")"
  [[ "$code" == "200" ]] || { echo "错误：POST $endpoint 返回 HTTP $code" >&2; cat "$output" >&2; return 1; }
  python3 - "$output" <<'PY'
import json,sys
x=json.load(open(sys.argv[1],encoding="utf-8")); assert x.get("code")==0
PY
}
api_get() {
  local endpoint="$1" output="$2"
  curl --fail --silent --show-error --max-time 40 --resolve "$domain:443:127.0.0.1" \
    -b "$cookie" "https://$domain/api$endpoint" > "$output"
  python3 - "$output" <<'PY'
import json,sys
x=json.load(open(sys.argv[1],encoding="utf-8")); assert x.get("code")==0
PY
}
response_id() {
  python3 - "$1" <<'PY'
import json,sys
print(json.load(open(sys.argv[1],encoding="utf-8"))["data"]["id"])
PY
}

business_started="$(date +%s)"
today="$(date +%F)"
api_post /partners "{\"typeMask\":3,\"entityType\":\"COMPANY\",\"name\":\"M4-07 离线验收往来单位\",\"status\":\"ACTIVE\",\"customerStatus\":\"ACTIVE\",\"supplierStatus\":\"ACTIVE\"}" "$evidence/partner-create.json"
partner_id="$(response_id "$evidence/partner-create.json")"
api_post /addresses "{\"partnerId\":$partner_id,\"name\":\"验收地址\",\"addressType\":\"DELIVERY\",\"detail\":\"M4-07 synthetic address\",\"contactPerson\":\"验收联系人\",\"phone\":\"00000000000\",\"firstChoice\":true,\"status\":\"ENABLED\"}" "$evidence/address-create.json"
address_id="$(response_id "$evidence/address-create.json")"
api_post /product-categories "{\"parentId\":0,\"name\":\"M4-07验收分类\",\"sortNo\":999,\"status\":\"ENABLED\"}" "$evidence/category-create.json"
category_id="$(response_id "$evidence/category-create.json")"
api_post /product-series "{\"categoryId\":$category_id,\"name\":\"M4-07验收系列\",\"aliasNames\":\"\",\"packageUnitCategoryId\":0,\"priceUnitCategoryId\":0,\"status\":\"ENABLED\"}" "$evidence/series-create.json"
series_id="$(response_id "$evidence/series-create.json")"
api_post /products "{\"productSeriesId\":$series_id,\"sku\":\"M407-$run_id\",\"name\":\"M4-07 紫铜大盘管\",\"spec\":\"synthetic\",\"unit\":\"kg\",\"defaultPrice\":80,\"status\":\"ENABLED\",\"fieldValues\":[]}" "$evidence/product-create.json"
product_id="$(response_id "$evidence/product-create.json")"
api_post /warehouses "{\"name\":\"M4-07隔离仓\",\"location\":\"synthetic\",\"status\":\"ENABLED\"}" "$evidence/warehouse-create.json"
warehouse_id="$(response_id "$evidence/warehouse-create.json")"
api_post /purchase-orders "{\"supplierId\":$partner_id,\"businessDate\":\"$today\",\"sourceType\":\"OFFLINE_ACCEPTANCE\",\"memo\":\"M4-07\",\"items\":[{\"productId\":$product_id,\"quantity\":10,\"quantityUnit\":\"kg\",\"priceUnit\":\"kg\",\"unitPrice\":50}]}" "$evidence/purchase-create.json"
purchase_id="$(response_id "$evidence/purchase-create.json")"
api_post "/purchase-orders/$purchase_id/confirm" '{}' "$evidence/purchase-confirm.json"
api_get "/purchase-orders/$purchase_id" "$evidence/purchase-detail.json"
purchase_item_id="$(python3 - "$evidence/purchase-detail.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1],encoding="utf-8"))["data"]["items"][0]["id"])
PY
)"
api_post /inbounds "{\"purchaseOrderId\":$purchase_id,\"supplierId\":$partner_id,\"warehouseId\":$warehouse_id,\"businessDate\":\"$today\",\"memo\":\"M4-07\",\"items\":[{\"productId\":$product_id,\"purchaseOrderItemId\":$purchase_item_id,\"batchNo\":\"M407-$run_id\",\"quantity\":10,\"quantityUnit\":\"kg\",\"priceUnit\":\"kg\",\"unitCost\":50}]}" "$evidence/inbound-create.json"
inbound_id="$(response_id "$evidence/inbound-create.json")"
api_post "/inbounds/$inbound_id/submit" '{"operatorName":"M4-07验收"}' "$evidence/inbound-submit.json"
api_post "/inbounds/$inbound_id/audit" '{"approved":true,"reason":"","operatorName":"M4-07验收"}' "$evidence/inbound-audit.json"
api_get "/inbounds/$inbound_id" "$evidence/inbound-detail.json"
batch_id="$(python3 - "$evidence/inbound-detail.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1],encoding="utf-8"))["data"]["items"][0]["inventoryBatchId"])
PY
)"
api_post /supplier-payments "{\"supplierId\":$partner_id,\"inboundId\":$inbound_id,\"amount\":500,\"paymentMethod\":\"BANK\",\"paidDate\":\"$today\",\"memo\":\"M4-07\"}" "$evidence/supplier-payment.json"
api_post /sales-orders "{\"title\":\"M4-07离线销售\",\"customerId\":$partner_id,\"addressId\":$address_id,\"businessDate\":\"$today\",\"paymentTerm\":\"POSTPAID\",\"shipmentPaymentPolicy\":\"NONE\",\"memo\":\"M4-07\",\"items\":[{\"productId\":$product_id,\"quantity\":4,\"quantityUnit\":\"kg\",\"priceUnit\":\"kg\",\"unitPrice\":80}]}" "$evidence/sales-create.json"
sales_id="$(response_id "$evidence/sales-create.json")"
api_post "/sales-orders/$sales_id/confirm" '{}' "$evidence/sales-confirm.json"
api_get "/sales-orders/$sales_id" "$evidence/sales-detail.json"
sales_item_id="$(python3 - "$evidence/sales-detail.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1],encoding="utf-8"))["data"]["items"][0]["id"])
PY
)"
api_post /outbounds "{\"salesOrderId\":$sales_id,\"customerId\":$partner_id,\"addressId\":$address_id,\"warehouseId\":$warehouse_id,\"businessDate\":\"$today\",\"sourceChannel\":\"OFFLINE_ACCEPTANCE\",\"entryMode\":\"REALTIME\",\"completenessStatus\":\"COMPLETE\",\"pricePending\":false,\"confirmNow\":true,\"idempotencyKey\":\"m407-$run_id\",\"memo\":\"M4-07\",\"items\":[{\"salesOrderItemId\":$sales_item_id,\"batchId\":$batch_id,\"quantity\":4,\"quantityUnit\":\"kg\",\"priceUnit\":\"kg\",\"unitPrice\":80}]}" "$evidence/outbound-create.json"
outbound_id="$(response_id "$evidence/outbound-create.json")"
api_post /payments "{\"customerId\":$partner_id,\"outboundId\":$outbound_id,\"amount\":320,\"paymentMethod\":\"BANK\",\"receivedDate\":\"$today\",\"memo\":\"M4-07\"}" "$evidence/customer-payment.json"
api_get "/inbounds/$inbound_id" "$evidence/inbound-final.json"
api_get "/sales-orders/$sales_id" "$evidence/sales-final.json"
api_get "/outbounds/$outbound_id" "$evidence/outbound-final.json"
api_get "/inventory/batches?productId=$product_id&warehouseId=$warehouse_id&availableOnly=true" "$evidence/inventory-query.json"
api_get "/payables?supplierId=$partner_id" "$evidence/payables-query.json"
api_get "/receivables?customerId=$partner_id" "$evidence/receivables-query.json"
api_get "/payments?customerId=$partner_id&outboundId=$outbound_id" "$evidence/payments-query.json"
api_get "/supplier-payments?supplierId=$partner_id&inboundId=$inbound_id" "$evidence/supplier-payments-query.json"
python3 - "$evidence/inbound-final.json" "$evidence/sales-final.json" "$evidence/outbound-final.json" \
  "$evidence/inventory-query.json" "$evidence/payables-query.json" "$evidence/receivables-query.json" \
  "$evidence/payments-query.json" "$evidence/supplier-payments-query.json" <<'PY'
import decimal,json,sys
docs=[json.load(open(p,encoding="utf-8"))["data"] for p in sys.argv[1:]]
inbound,sales,outbound,inventory,payables,receivables,payments,supplier_payments=docs
amount=lambda value: decimal.Decimal(str(value))
assert inbound["status"]=="STOCKED" and amount(inbound["paidAmount"])==decimal.Decimal("500")
assert inbound["paymentStatus"]=="PAID"
assert sales["status"] in {"SHIPPED","COMPLETED"}
assert sales["paymentStatus"]=="PAID" and amount(sales["receivedAmount"])==decimal.Decimal("320")
assert outbound["paymentStatus"]=="PAID" and amount(outbound["paidAmount"])==decimal.Decimal("320")
assert len(inventory)==1 and decimal.Decimal(str(inventory[0]["quantityAvailable"]))==decimal.Decimal("6")
assert any(amount(x["unpaidAmount"])==decimal.Decimal("0") for x in payables)
assert any(amount(x["unpaidAmount"])==decimal.Decimal("0") for x in receivables)
assert any(amount(x["amount"])==decimal.Decimal("320") for x in payments)
assert any(amount(x["amount"])==decimal.Decimal("500") for x in supplier_payments)
PY
business_seconds="$(( $(date +%s) - business_started ))"

# Before the first backup, the local monitor must report the recoverability gap and the blocked webhook must fail explicitly.
set +e
"$deploy_root/copperctl" monitor check --json > "$evidence/third-party-degraded.json"
monitor_degraded_exit=$?
set -e
[[ "$monitor_degraded_exit" -eq 1 ]]
python3 - "$evidence/third-party-degraded.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1],encoding="utf-8"))
assert x["notification"]["mode"]=="webhook" and x["notification"]["result"]=="FAILED"
assert x["notification"]["errorCode"]=="DELIVERY_FAILED"
PY
curl --fail --silent --show-error --resolve "$domain:443:127.0.0.1" "https://$domain/readyz" > "$evidence/ready-after-third-party-failure.txt"

"$deploy_root/copperctl" backup key register --handover-sha256 "$handover_sha" \
  --customer-confirmed-by "M4-07 customer" --witness-confirmed-by "M4-07 witness" > "$evidence/key-register.json"
backup_started="$(date +%s)"
"$deploy_root/copperctl" backup create --purpose manual > "$evidence/backup-create.txt"
backup_seconds="$(( $(date +%s) - backup_started ))"
archive_value="$(python3 - "$backup_dir/last-success.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1],encoding="utf-8"))["archive"])
PY
)"
[[ "$archive_value" == /* ]] || archive_value="$backup_dir/$archive_value"
"$deploy_root/copperctl" backup verify "$archive_value" > "$evidence/backup-verify.json"
"$deploy_root/copper-key-offline" verify-backup --identity "$offline_key/copper-age-identity.txt" \
  --archive "$archive_value" --receipt "$offline_key/restore-receipt.json" > "$evidence/offline-backup-verify.json"
"$deploy_root/copperctl" backup key record-restore-test "$offline_key/restore-receipt.json" > "$evidence/restore-proof-register.json"
"$deploy_root/copperctl" backup status --json > "$evidence/backup-status.json"
"$deploy_root/copperctl" operations status --json > "$evidence/operations-status.json"
"$deploy_root/copperctl" security secrets audit --json > "$evidence/secrets-audit.json"
"$deploy_root/copperctl" doctor > "$evidence/doctor.txt"

mysql_query() {
  printf '%s\n' "$1" | docker compose --env-file "$env_file" -f "$deploy_root/compose.yaml" exec -T mysql sh -c \
    'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot -D copper_v2 --batch --raw --skip-column-names'
}
mysql_query "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;" > "$evidence/schema-version.txt"
mysql_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE';" > "$evidence/table-count.txt"
mysql_query "SELECT COUNT(*) FROM security_audit_log WHERE event_id IS NOT NULL;" > "$evidence/security-audit-count.txt"
mysql_query "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE();" > "$evidence/database-foreign-key-count.txt"
[[ "$(tr -d '[:space:]' < "$evidence/schema-version.txt")" == "5" ]]
[[ "$(tr -d '[:space:]' < "$evidence/table-count.txt")" == "90" ]]
[[ "$(tr -d '[:space:]' < "$evidence/security-audit-count.txt")" -gt 0 ]]

docker compose --env-file "$env_file" -f "$deploy_root/compose.yaml" ps -a > "$evidence/compose-ps.txt"
curl --fail --silent --show-error --resolve "$domain:443:127.0.0.1" "https://$domain/readyz" > "$evidence/final-readyz.txt"
iptables -L DOCKER-USER -v -n --line-numbers > "$evidence/final-egress-firewall.txt"
{
  printf 'format=copper-m4-final-acceptance/v1\n'
  printf 'run_id=%s\n' "$run_id"
  printf 'version=%s\n' "$version"
  printf 'domain=%s\n' "$domain"
  printf 'install_seconds=%s\n' "$install_seconds"
  printf 'offline_business_seconds=%s\n' "$business_seconds"
  printf 'backup_seconds=%s\n' "$backup_seconds"
  printf 'schema=5\n'
  printf 'tables=90\n'
  printf 'host_8191=unused\n'
  printf 'public_internet=blocked_for_runtime\n'
  printf 'core_business=passed\n'
  printf 'third_party_degradation=passed\n'
  printf 'credentials_destroyed=passed\n'
  printf 'public_security_headers=passed\n'
} > "$evidence/summary.env"
find "$evidence" -type f ! -name SHA256SUMS -printf '%P\0' | LC_ALL=C sort -z \
  | xargs -0 -r -I{} sha256sum "$evidence/{}" | sed "s#  $evidence/#  #" > "$evidence/SHA256SUMS"
(cd "$evidence" && sha256sum -c SHA256SUMS >/dev/null)
chmod 0600 "$evidence"/*
rm -f "$cookie"
success=1
echo "m4_07_final_x86_acceptance=passed"
echo "evidence=$evidence"
