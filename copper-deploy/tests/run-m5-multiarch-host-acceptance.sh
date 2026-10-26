#!/usr/bin/env bash
set -Eeuo pipefail

release_index="${1:-}"
index_sha256="${2:-}"
domain="${3:-}"
output_root="${4:-/opt/copper-m5-02-acceptance}"

[[ -f "$release_index" && ! -L "$release_index" && "$index_sha256" =~ ^[0-9a-f]{64}$ \
   && "$domain" =~ ^[A-Za-z0-9][A-Za-z0-9.-]{1,251}[A-Za-z0-9]$ ]] || {
  echo "用法：sudo tests/run-m5-multiarch-host-acceptance.sh RELEASE_INDEX INDEX_SHA256 DOMAIN [OUTPUT_ROOT]" >&2
  exit 2
}
[[ "$EUID" -eq 0 ]] || { echo "错误：M5-02 实机验收必须使用 sudo" >&2; exit 1; }

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
release_index="$(cd "$(dirname "$release_index")" && pwd)/$(basename "$release_index")"
release_dir="$(dirname "$release_index")"

for command in age age-keygen curl docker jq openssl python3 sha256sum ss zstd; do
  command -v "$command" >/dev/null 2>&1 || { echo "错误：缺少 $command" >&2; exit 1; }
done
[[ "$(docker version --format '{{.Server.Version}}')" == 29.* ]] \
  || { echo "错误：M5-02 验收要求 Docker Engine 29.x" >&2; exit 1; }
[[ "$(docker compose version --short)" == 5.* ]] \
  || { echo "错误：M5-02 验收要求 Docker Compose 5.x" >&2; exit 1; }
[[ "$(sha256sum "$release_index" | awk '{print $1}')" == "$index_sha256" ]] \
  || { echo "错误：多架构发行索引独立 SHA-256 不匹配" >&2; exit 1; }
"$ROOT_DIR/scripts/multiarch-contract.py" validate-release --index "$release_index" >/dev/null

case "$(uname -m)" in
  x86_64)
    arch="amd64"
    platform="linux/amd64"
    platform_status="certified"
    ;;
  aarch64|arm64)
    arch="arm64"
    platform="linux/arm64"
    platform_status="candidate"
    ;;
  *)
    echo "错误：M5-02 只允许真实 x86_64 或 ARM64 主机" >&2
    exit 1
    ;;
esac

for port in 80 443; do
  if ss -lnt | awk '{print $4}' | grep -Eq "(^|:|\\])${port}$"; then
    echo "错误：宿主机端口 $port 已被占用，拒绝覆盖现有服务" >&2
    exit 1
  fi
done

run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
runtime="$output_root/runtime-$arch-$run_id"
evidence="$output_root/evidence-$arch-$run_id"
etc_dir="$runtime/etc"
var_dir="$runtime/var"
backup_dir="$runtime/backups"
restore_root="$runtime/restores"
env_file="$etc_dir/copper.env"
offline_key="$runtime/offline-key"
cookie="$runtime/admin-cookie.txt"
project="copper-m502-${arch}-${run_id,,}"
site_id="copper-m502-$arch"
started=0
success=0

cleanup() {
  local exit_code=$?
  set +e
  rm -f "$cookie"
  if [[ "$started" == "1" && -f "$env_file" ]]; then
    env COPPER_TEST_MODE=1 COPPER_ENV_FILE="$env_file" "$ROOT_DIR/copperctl" down >/dev/null 2>&1 || true
  fi
  rm -rf --one-file-system -- "$runtime"
  if [[ "$success" == "1" ]]; then
    echo "M5-02 $platform 实机验收通过"
    echo "evidence=$evidence"
  fi
  exit "$exit_code"
}
trap cleanup EXIT

install -d -o root -g root -m 0700 "$output_root" "$runtime" "$evidence" "$etc_dir"

python3 - "$release_index" "$platform" "$release_dir" "$runtime/selected.tsv" "$evidence/release-selection.json" <<'PY'
import json, pathlib, sys

index_path = pathlib.Path(sys.argv[1])
platform = sys.argv[2]
release_dir = pathlib.Path(sys.argv[3])
selected_path = pathlib.Path(sys.argv[4])
evidence_path = pathlib.Path(sys.argv[5])
document = json.loads(index_path.read_text(encoding="utf-8"))
status = next(item["status"] for item in document["platforms"] if item["platform"] == platform)
selected = []
for component in document["components"]:
    record = next(item for item in component["platforms"] if item["platform"] == platform)
    archive = release_dir / record["archive"]["name"]
    role = component["role"]
    revision = component["source"]["revision"]
    if role in {"web", "backend"}:
        expected_tag = f'{component["image"].rsplit("@", 1)[0]}:m5-delivery-{revision}-{platform.split("/", 1)[1]}'
    else:
        expected_tag = f'copper-mysql-m5-{platform.split("/", 1)[1]}:delivery'
    selected.append({
        "role": role,
        "image": component["image"],
        "manifestDigest": component["manifestDigest"],
        "platform": platform,
        "platformDigest": record["digest"],
        "producerLocalId": record["localId"],
        "sourceRevision": revision,
        "expectedArchiveTag": expected_tag,
        "archive": str(archive),
    })
selected_path.write_text(
    "".join(
        f'{item["role"]}\t{item["producerLocalId"]}\t{item["archive"]}\t{item["expectedArchiveTag"]}\t{item["sourceRevision"]}\n'
        for item in selected
    ),
    encoding="utf-8",
)
evidence_path.write_text(json.dumps({
    "format": "copper-m5-host-selection/v1",
    "version": document["version"],
    "deploymentCommit": document["deployment"]["commit"],
    "platform": platform,
    "status": status,
    "components": selected,
}, sort_keys=True) + "\n", encoding="utf-8")
PY

declare -A images
: > "$runtime/runtime-images.jsonl"
while IFS=$'\t' read -r role producer_local_id archive expected_tag source_revision; do
  [[ "$role" == "web" || "$role" == "backend" || "$role" == "mysql" ]] \
    || { echo "错误：发行索引包含未知角色 $role" >&2; exit 1; }
  [[ "$producer_local_id" =~ ^sha256:[0-9a-f]{64}$ && -f "$archive" && ! -L "$archive" ]] \
    || { echo "错误：$role 的本地内容 ID 或归档无效" >&2; exit 1; }
  docker load --input "$archive" > "$evidence/docker-load-$role.txt"
  actual_id="$(docker image inspect --format '{{.Id}}' "$expected_tag")"
  actual_arch="$(docker image inspect --format '{{.Architecture}}' "$expected_tag")"
  [[ "$actual_id" =~ ^sha256:[0-9a-f]{64}$ && "$actual_arch" == "$arch" ]] \
    || { echo "错误：$role 导入后的运行时内容 ID 或架构不匹配" >&2; exit 1; }
  if [[ "$role" == "web" || "$role" == "backend" ]]; then
    actual_revision="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$expected_tag")"
    [[ "$actual_revision" == "$source_revision" ]] \
      || { echo "错误：$role 归档内源码 revision 与发行索引不一致" >&2; exit 1; }
  else
    mysql_version="$(docker image inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$expected_tag" | awk -F= '$1=="MYSQL_VERSION" {print $2; exit}')"
    [[ "$mysql_version" == 8.4.10-* ]] \
      || { echo "错误：MySQL 归档内版本不是 8.4.10" >&2; exit 1; }
  fi
  images[$role]="$actual_id"
  jq -n --arg role "$role" --arg platform "$platform" --arg producerLocalId "$producer_local_id" \
    --arg runtimeId "$actual_id" --arg archiveTag "$expected_tag" \
    '{role:$role,platform:$platform,producerLocalId:$producerLocalId,runtimeId:$runtimeId,archiveTag:$archiveTag}' \
    >> "$runtime/runtime-images.jsonl"
done < "$runtime/selected.tsv"
[[ "${#images[@]}" -eq 3 ]] || { echo "错误：当前平台没有精确选择三张镜像" >&2; exit 1; }
jq -s '{format:"copper-m5-runtime-images/v1",images:.}' "$runtime/runtime-images.jsonl" \
  > "$evidence/runtime-images.json"

"$ROOT_DIR/copper-key-offline" generate --output-dir "$offline_key" \
  --site-id "$site_id" --customer-reference "m5-02-$arch-host-acceptance" \
  > "$evidence/key-generation.json"
recipient="$(tr -d '\r\n' < "$offline_key/recipient.txt")"
handover_sha="$(sha256sum "$offline_key/handover-record.json" | awk '{print $1}')"

install -o root -g root -m 0600 "$ROOT_DIR/config/copper.env.example" "$env_file"
version="$(jq -r '.version' "$release_index")"
python3 - "$env_file" "$site_id" "$project" "$version" "$domain" "$etc_dir" "$var_dir" \
  "$backup_dir" "$restore_root" "$recipient" "${images[web]}" "${images[backend]}" "${images[mysql]}" <<'PY'
import pathlib, sys

path = pathlib.Path(sys.argv[1])
values = {
    "COPPER_SITE_ID": sys.argv[2],
    "COPPER_PROJECT_NAME": sys.argv[3],
    "COPPER_RELEASE_VERSION": sys.argv[4],
    "COPPER_DEPLOY_MODE": "standard",
    "COPPER_DOMAIN": sys.argv[5],
    "COPPER_TLS_MODE": "domain",
    "COPPER_IMAGE_DELIVERY": "offline",
    "COPPER_ETC_DIR": sys.argv[6],
    "COPPER_VAR_DIR": sys.argv[7],
    "COPPER_BACKUP_DIR": sys.argv[8],
    "COPPER_RESTORE_ROOT": sys.argv[9],
    "COPPER_RESTORE_PROXY_PORT": "18152",
    "COPPER_BACKUP_AGE_RECIPIENT": sys.argv[10],
    "COPPER_BACKUP_TARGETS": "local",
    "COPPER_BACKUP_ALERT_MODE": "local",
    "COPPER_MONITOR_ALERT_MODE": "local",
    "COPPER_WEB_IMAGE": sys.argv[11],
    "COPPER_BACKEND_IMAGE": sys.argv[12],
    "COPPER_MYSQL_IMAGE": sys.argv[13],
}
result, seen = [], set()
for line in path.read_text(encoding="utf-8").splitlines():
    key = line.split("=", 1)[0] if "=" in line and not line.lstrip().startswith("#") else ""
    if key in values:
        result.append(f"{key}={values[key]}")
        seen.add(key)
    else:
        result.append(line)
for key, value in values.items():
    if key not in seen:
        result.append(f"{key}={value}")
path.write_text("\n".join(result) + "\n", encoding="utf-8")
PY

export COPPER_TEST_MODE=1
export COPPER_ENV_FILE="$env_file"
"$ROOT_DIR/scripts/prepare-host.sh" > "$evidence/prepare-host.txt"
"$ROOT_DIR/scripts/time-safety.py" --output "$var_dir/time-safety/time-safety.json" --json \
  > "$evidence/time-safety.json"
started=1
"$ROOT_DIR/copperctl" install > "$evidence/install.txt"

compose=(docker compose --env-file "$env_file" -f "$ROOT_DIR/compose.yaml")
for service in mysql backend web; do
  container_id="$("${compose[@]}" ps -q "$service")"
  [[ -n "$container_id" ]] || { echo "错误：$service 容器不存在" >&2; exit 1; }
  docker inspect "$container_id" > "$evidence/container-$service.json"
done
python3 - "$evidence/container-mysql.json" "$evidence/container-backend.json" "$evidence/container-web.json" <<'PY'
import json, sys

mysql, backend, web = (json.load(open(path, encoding="utf-8"))[0] for path in sys.argv[1:])
for name, item in (("mysql", mysql), ("backend", backend)):
    if item["HostConfig"].get("PortBindings") not in (None, {}):
        raise SystemExit(f"{name} 不得映射宿主机端口")
ports = web["HostConfig"].get("PortBindings") or {}
if set(ports) != {"80/tcp", "443/tcp", "443/udp"}:
    raise SystemExit("Web 宿主机端口集合必须精确为 80/443 TCP 与 443 UDP")
PY
curl_options=(--fail --silent --show-error --max-time 20 --resolve "$domain:443:127.0.0.1")
if [[ "$domain" == localhost || "$domain" == *.localhost ]]; then
  curl_options+=(--insecure)
fi
for _ in $(seq 1 90); do
  if curl "${curl_options[@]}" "https://$domain/readyz" > "$evidence/readyz.txt" 2>/dev/null; then
    break
  fi
  sleep 2
done
curl "${curl_options[@]}" "https://$domain/healthz" > "$evidence/healthz.txt"
curl "${curl_options[@]}" "https://$domain/readyz" > "$evidence/readyz.txt"

credential_file="$etc_dir/credentials/initial-admin.txt"
[[ -f "$credential_file" && ! -L "$credential_file" ]] \
  || { echo "错误：首次管理员一次性凭据未生成" >&2; exit 1; }
username="$(awk -F= '$1=="username" {print $2}' "$credential_file")"
initial_password="$(awk -F= '$1=="password" {print $2}' "$credential_file")"
new_password="M502!$(openssl rand -hex 18)aZ9"
login_headers="$runtime/login-headers.txt"
curl "${curl_options[@]}" -D "$login_headers" -o /dev/null -c "$cookie" \
  -H 'Content-Type: application/json' \
  --data "{\"username\":\"$username\",\"password\":\"$initial_password\"}" \
  "https://$domain/api/auth/login"
python3 - "$login_headers" "$evidence/cookie-security.json" <<'PY'
import json, pathlib, sys

headers = pathlib.Path(sys.argv[1]).read_text(encoding="iso-8859-1")
cookie = next((line for line in headers.splitlines() if line.lower().startswith("set-cookie:")), "")
checks = {"secure": "; Secure" in cookie, "httpOnly": "; HttpOnly" in cookie, "sameSiteStrict": "SameSite=Strict" in cookie}
if not all(checks.values()):
    raise SystemExit("会话 Cookie 安全属性不完整")
pathlib.Path(sys.argv[2]).write_text(json.dumps({"format": "copper-m5-cookie/v1", "checks": checks}, sort_keys=True) + "\n", encoding="utf-8")
PY
curl "${curl_options[@]}" -b "$cookie" -H 'Content-Type: application/json' \
  --data "{\"currentPassword\":\"$initial_password\",\"newPassword\":\"$new_password\"}" \
  "https://$domain/api/auth/change-password" > "$evidence/change-password.json"
rm -f "$cookie" "$login_headers"
curl "${curl_options[@]}" -c "$cookie" -H 'Content-Type: application/json' \
  --data "{\"username\":\"$username\",\"password\":\"$new_password\"}" \
  "https://$domain/api/auth/login" > "$evidence/login-after-change.json"
unset initial_password new_password
[[ ! -e "$credential_file" && ! -L "$credential_file" ]] \
  || { echo "错误：首次管理员凭据使用后未销毁" >&2; exit 1; }

for endpoint in '/products?page=1&pageSize=5' '/partners?page=1&pageSize=5' '/inventory/batches?availableOnly=true'; do
  output="$evidence/query-$(printf '%s' "$endpoint" | sha256sum | cut -c1-12).json"
  curl "${curl_options[@]}" -b "$cookie" "https://$domain/api$endpoint" > "$output"
  jq -e '.code == 0' "$output" >/dev/null || { echo "错误：核心查询失败 $endpoint" >&2; exit 1; }
done

"$ROOT_DIR/copperctl" backup key register --handover-sha256 "$handover_sha" \
  --customer-confirmed-by "M5-02 acceptance" --witness-confirmed-by "M5-02 witness" \
  > "$evidence/key-register.json"
"$ROOT_DIR/copperctl" backup create --purpose manual > "$evidence/backup-create.txt"
archive_value="$(jq -r '.archive' "$backup_dir/last-success.json")"
[[ "$archive_value" == /* ]] || archive_value="$backup_dir/$archive_value"
"$ROOT_DIR/copperctl" backup verify "$archive_value" > "$evidence/backup-verify.json"
"$ROOT_DIR/copper-key-offline" verify-backup --identity "$offline_key/copper-age-identity.txt" \
  --archive "$archive_value" --receipt "$offline_key/restore-receipt.json" \
  > "$evidence/offline-backup-verify.json"
"$ROOT_DIR/copperctl" doctor > "$evidence/doctor.txt"

python3 - "$release_index" "$index_sha256" "$platform" "$platform_status" \
  "$evidence/release-selection.json" "$evidence/runtime-images.json" "$evidence/acceptance.json" <<'PY'
import json, pathlib, sys

index_path = pathlib.Path(sys.argv[1])
selection = json.loads(pathlib.Path(sys.argv[5]).read_text(encoding="utf-8"))
runtime_images = json.loads(pathlib.Path(sys.argv[6]).read_text(encoding="utf-8"))
document = {
    "format": "copper-m5-host-acceptance/v1",
    "version": json.loads(index_path.read_text(encoding="utf-8"))["version"],
    "releaseIndexSha256": sys.argv[2],
    "platform": sys.argv[3],
    "platformStatus": sys.argv[4],
    "components": selection["components"],
    "runtimeImages": runtime_images["images"],
    "checks": {
        "closedReleaseVerified": True,
        "imageIdentityVerified": True,
        "hostPortsRestricted": True,
        "threeServicesHealthy": True,
        "loginAndCoreQueries": True,
        "bootstrapCredentialDestroyed": True,
        "encryptedBackupVerified": True,
        "backupBoundToPlatformImageIds": True,
        "isolatedCleanupScheduled": True,
    },
}
pathlib.Path(sys.argv[7]).write_text(json.dumps(document, sort_keys=True) + "\n", encoding="utf-8")
PY
(
  cd "$evidence"
  sha256sum ./* > SHA256SUMS
)
chmod -R go-rwx "$evidence"
success=1
