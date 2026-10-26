#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
run_id="$(date -u +%Y%m%d%H%M%S)-$$"
suffix="$(printf '%s' "$run_id" | tr -cd '0-9' | tail -c 12)"
database="copper_erp_lite_${suffix}"
db_user="erp_lite_${suffix}"
runtime="$(mktemp -d "${TMPDIR:-/tmp}/copper-erp-lite-local.XXXXXX")"

mysql_root() {
  docker exec -i vm-mysql sh -lc '
    export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
    if [ -n "$MYSQL_ROOT_PASSWORD_FILE" ]; then
      export MYSQL_PWD="$(cat "$MYSQL_ROOT_PASSWORD_FILE")"
    fi
    exec mysql --default-character-set=utf8mb4 -uroot "$@"
  ' sh "$@"
}

cleanup() {
  set +e
  printf 'DROP DATABASE IF EXISTS `%s`; DROP USER IF EXISTS '\''%s'\''@'\''%%'\'';\n' \
    "$database" "$db_user" | mysql_root >/dev/null 2>&1
  rm -rf "$runtime"
}
trap cleanup EXIT INT TERM

[[ "$(hostname)" == "local-database" || "$(hostname)" == "local-ubuntu" \
   || "$(hostname)" == "localhost.localdomain" ]] \
  || { echo "ERROR: 该验收脚本只能在隔离验收机执行" >&2; exit 1; }
docker inspect vm-mysql >/dev/null
if [[ -x /usr/lib/jvm/java-21-openjdk/bin/java ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
  export PATH="$JAVA_HOME/bin:$PATH"
fi
java -version

db_password="$(openssl rand -hex 24)"
{
  printf 'CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n' "$database"
  printf 'CREATE USER '\''%s'\''@'\''%%'\'' IDENTIFIED BY '\''%s'\'';\n' "$db_user" "$db_password"
  printf 'GRANT ALL PRIVILEGES ON `%s`.* TO '\''%s'\''@'\''%%'\''; FLUSH PRIVILEGES;\n' \
    "$database" "$db_user"
} | mysql_root >/dev/null

cd "$repo_root"
COPPER_ERP_LITE_DB_URL="jdbc:mysql://127.0.0.1:3306/${database}?useSSL=false&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
COPPER_ERP_LITE_DB_USERNAME="$db_user" \
COPPER_ERP_LITE_DB_PASSWORD="$db_password" \
  bash tests/run-lightweight-trading-operations-mysql.sh

printf '%s\n' "ERP_LITE_LOCAL_DATABASE=PASS database=${database} cleanup=scheduled"
