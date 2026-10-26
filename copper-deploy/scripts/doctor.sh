#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

pass_count=0
warn_count=0
fail_count=0
config_ok=0

pass() {
  pass_count=$((pass_count + 1))
  echo "[通过] $1"
}

warn() {
  warn_count=$((warn_count + 1))
  echo "[提醒] $1"
  echo "       下一步：$2"
}

fail() {
  fail_count=$((fail_count + 1))
  echo "[失败] $1"
  echo "       下一步：$2"
}

echo "Copper 自检"
echo "配置：$ENV_FILE"
echo

if [[ "$EUID" -eq 0 ]]; then
  pass "当前以 root 权限执行"
else
  fail "当前权限不足，无法读取受限配置、密钥和容器状态" "使用 sudo copperctl doctor 重新执行"
fi

platform_error=""
if [[ "${COPPER_TEST_MODE:-0}" == "1" ]]; then
  warn "当前为隔离测试模式，已跳过正式平台限制" "正式交付必须在 Ubuntu Server 24.04 LTS x86_64 重新执行 doctor"
elif platform_error="$(require_supported_platform 2>&1)"; then
  pass "操作系统与架构符合 Ubuntu 24.04 LTS amd64 基线"
else
  fail "${platform_error#错误：}" "换用 Ubuntu Server 24.04 LTS x86_64；ARM64 仅允许隔离测试"
fi

for command_name in docker openssl awk curl tar sha256sum age zstd python3 flock; do
  if command -v "$command_name" >/dev/null 2>&1; then
    pass "已安装 $command_name"
  else
    fail "缺少命令 $command_name" "安装该命令后重新运行 doctor"
  fi
done

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  pass "Docker Engine 正常运行"
  engine_version="$(docker version --format '{{.Server.Version}}' 2>/dev/null || true)"
  compose_version="$(docker compose version --short 2>/dev/null || true)"
  if [[ "$engine_version" == 29.* ]]; then
    pass "Docker Engine 版本为 $engine_version"
  else
    fail "Docker Engine 版本为 ${engine_version:-未知}，不符合 29.x 基线" "安装 M1 发布说明指定的 Docker Engine 29.x"
  fi
  if [[ "$compose_version" == 5.* ]]; then
    pass "Docker Compose Plugin 版本为 $compose_version"
  else
    fail "Docker Compose Plugin 版本为 ${compose_version:-未知}，不符合 5.x 基线" "安装 M1 发布说明指定的 Compose Plugin 5.x"
  fi
else
  fail "Docker Engine 不可用" "运行 systemctl status docker；启动 Docker 后重新检查"
fi

if [[ -f "$ENV_FILE" ]]; then
  owner_mode="$(stat -c '%U:%G %a' "$ENV_FILE" 2>/dev/null || true)"
  if [[ "$owner_mode" == "root:root 600" ]]; then
    pass "配置文件属主和权限为 root:root 600"
  else
    fail "配置文件权限为 ${owner_mode:-无法读取}" "运行 chown root:root '$ENV_FILE' && chmod 600 '$ENV_FILE'"
  fi
  validation_error=""
  if validation_error="$(validate_environment 2>&1)"; then
    config_ok=1
    pass "站点配置、目录边界、镜像摘要和密钥通过校验"
    if [[ "$(ocr_env_value COPPER_OCR_ENABLED false)" == "true" ]]; then
      if ocr_error="$(validate_ocr_network 2>&1)"; then
        pass "可选 OCR 使用独立 internal 私网"
      else
        fail "${ocr_error#错误：}" "先修复独立 OCR 私网；不使用识别时将 COPPER_OCR_ENABLED=false"
      fi
    fi
  else
    fail "${validation_error#错误：}" "修正 $ENV_FILE 后运行 sudo copperctl doctor"
  fi
else
  fail "未找到配置文件 $ENV_FILE" "在发布目录运行 sudo ./copperctl install 生成模板，然后填写真实域名和镜像摘要"
fi

if [[ "${COPPER_TEST_MODE:-0}" != "1" ]]; then
  etc_dir_for_preflight="$(env_value COPPER_ETC_DIR 2>/dev/null || true)"
  etc_dir_for_preflight="${etc_dir_for_preflight:-/etc/copper}"
  preflight_report="$etc_dir_for_preflight/host-preflight.json"
  if [[ -f "$preflight_report" ]]; then
    preflight_permission="$(stat -c '%U:%G %a' "$preflight_report" 2>/dev/null || true)"
    preflight_status="$(python3 - "$preflight_report" <<'PY'
import json
import sys
try:
    value = json.load(open(sys.argv[1], encoding="utf-8"))
    valid = (
        value.get("format") == "copper-local-host-preflight/v1"
        and value.get("profile") == "ubuntu-24.04-x86_64"
        and value.get("status") == "PASS"
        and value.get("writeAllowed") is True
    )
    print("PASS" if valid else "")
except (OSError, ValueError, TypeError):
    print("")
PY
)"
    if [[ "$preflight_permission" == "root:root 600" && "$preflight_status" == "PASS" ]]; then
      pass "本地主机预检报告为 PASS 且权限为 root:root 600"
    else
      fail "本地主机预检报告无效、未通过或权限不正确" "运行 sudo copperctl host preflight --json；确认通过后重新执行 install 保存报告"
    fi
  else
    warn "尚无 M5 本地主机预检报告" "运行 sudo copperctl host preflight --json，并在维护窗口重新执行 install 保存报告"
  fi

  if systemctl is-enabled --quiet copper.service 2>/dev/null && systemctl is-active --quiet copper.service 2>/dev/null; then
    pass "Copper 主服务已启用 Ubuntu 开机自启动且处于 active"
  else
    warn "Copper 主服务尚未完成 Ubuntu 开机自启动" "运行 sudo copperctl host autostart install；再执行一次 Ubuntu 重启验收"
  fi
fi

if [[ "$config_ok" == "1" ]]; then
  var_dir="$(required_env_value COPPER_VAR_DIR)"
  backup_dir="$(backup_dir_value)"
  restore_root="$(restore_root_value)"
  available_kb="$(df -Pk "$var_dir" 2>/dev/null | awk 'END {print $(NF-2)}')"
  if [[ "$available_kb" =~ ^[0-9]+$ && "$available_kb" -ge 5242880 ]]; then
    pass "数据盘剩余空间不少于 5 GiB"
  else
    fail "数据盘剩余空间不足 5 GiB 或无法读取" "扩容或清理非 Copper 文件；不要直接删除 /var/lib/copper 内容"
  fi

  if [[ -d "$backup_dir" && "$(stat -c '%U:%G %a' "$backup_dir" 2>/dev/null)" == "root:root 700" ]]; then
    pass "备份目录为 root:root 700"
  else
    fail "备份目录不存在或权限不正确：$backup_dir" "重新运行 sudo ./copperctl install"
  fi

  if [[ -d "$restore_root" && "$(stat -c '%U:%G %a' "$restore_root" 2>/dev/null)" == "root:root 700" ]]; then
    pass "旁路恢复根目录为 root:root 700"
  else
    fail "旁路恢复根目录不存在或权限不正确：$restore_root" "重新运行 sudo ./copperctl install"
  fi

  restore_port="$(env_value COPPER_RESTORE_PROXY_PORT || true)"
  restore_port="${restore_port:-18080}"
  if [[ "$restore_port" =~ ^[0-9]+$ && "$restore_port" -ge 1024 && "$restore_port" -le 65535 && "$restore_port" != "8191" ]]; then
    pass "默认旁路恢复端口 $restore_port 符合隔离要求"
  else
    fail "默认旁路恢复端口不安全：$restore_port" "设置 1024-65535 且不为 8191 的 COPPER_RESTORE_PROXY_PORT"
  fi

  backup_recipient="$(env_value COPPER_BACKUP_AGE_RECIPIENT || true)"
  if [[ "$backup_recipient" =~ ^age1[0-9a-z]{20,}$ ]]; then
    pass "已配置客户 age 备份公钥；服务器不需要保存解密私钥"
  else
    fail "未配置合法的 COPPER_BACKUP_AGE_RECIPIENT" "由客户生成并离线保管 age 私钥，只把 age1 开头的公钥写入 $ENV_FILE"
  fi
  key_registry="$var_dir/backup-keys/registry.json"
  if [[ -f "$key_registry" && "$(stat -c '%U:%G %a' "$key_registry" 2>/dev/null)" == "root:root 600" ]]; then
    if "$ROOT_DIR/scripts/backup-key.sh" assert-ready >/dev/null 2>&1; then
      pass "客户备份公钥已登记，双人交接记录与当前配置一致"
    else
      fail "客户备份密钥登记簿与当前配置不一致" "运行 sudo copperctl backup key status；按轮换流程修复，禁止直接覆盖登记簿"
    fi
  else
    fail "尚无权限正确的客户备份密钥登记簿" "完成离线生成和双人交接后运行 sudo copperctl backup key register"
  fi
  schedule_time="$(env_value COPPER_BACKUP_DAILY_TIME || true)"
  schedule_time="${schedule_time:-02:30}"
  random_delay="$(env_value COPPER_BACKUP_RANDOM_DELAY_MINUTES || true)"
  random_delay="${random_delay:-30}"
  if [[ "$schedule_time" =~ ^([01][0-9]|2[0-3]):([0-5][0-9])$ && "$random_delay" =~ ^[0-9]+$ && "$random_delay" -le 1440 ]]; then
    pass "备份计划为每日 $schedule_time，随机延迟 0-${random_delay} 分钟"
  else
    fail "备份计划时间或随机延迟不合法" "设置 COPPER_BACKUP_DAILY_TIME=HH:MM 和 0-1440 分钟随机延迟"
  fi
  alert_mode="$(env_value COPPER_BACKUP_ALERT_MODE || true)"
  alert_mode="${alert_mode:-local}"
  if [[ "$alert_mode" == "local" ]]; then
    warn "备份告警仅保存在本机受限状态和 systemd" "如需异机通知，配置 root-only Webhook URL/HMAC 文件并设置 COPPER_BACKUP_ALERT_MODE=webhook"
  elif [[ "$alert_mode" == "webhook" ]]; then
    alert_url_file="$(env_value COPPER_BACKUP_ALERT_WEBHOOK_URL_FILE || true)"
    alert_hmac_file="$(env_value COPPER_BACKUP_ALERT_HMAC_KEY_FILE || true)"
    if [[ -n "$alert_url_file" && -n "$alert_hmac_file" && \
          "$(stat -c '%U:%G %a' "$alert_url_file" 2>/dev/null)" == "root:root 600" && \
          "$(stat -c '%U:%G %a' "$alert_hmac_file" 2>/dev/null)" == "root:root 600" && \
          "$(wc -c < "$alert_hmac_file" 2>/dev/null || printf 0)" -ge 33 ]]; then
      pass "备份 Webhook URL 与 HMAC 密钥使用 root-only 文件保存"
    else
      fail "备份 Webhook 凭据不存在、权限不是 600 或 HMAC 密钥过短" "在 notification-credentials 下创建 root:root 600 文件，禁止把 URL/密钥直接写入环境值"
    fi
  else
    fail "COPPER_BACKUP_ALERT_MODE=$alert_mode 不受支持" "只使用 local 或 webhook"
  fi
  job_status="$var_dir/backup-jobs/status.json"
  if [[ -f "$job_status" ]]; then
    if [[ "$(stat -c '%U:%G %a' "$job_status" 2>/dev/null)" == "root:root 600" ]]; then
      pass "备份任务状态文件权限正确，可追踪最近结果与连续失败"
    else
      fail "备份任务状态文件权限不正确" "应为 root:root 600；禁止放宽状态和告警信息权限"
    fi
  else
    warn "尚无备份任务状态" "完成密钥登记后运行 sudo copperctl backup create"
  fi
  for retention_name in COPPER_BACKUP_KEEP_DAILY COPPER_BACKUP_KEEP_WEEKLY COPPER_BACKUP_KEEP_MONTHLY; do
    retention_value="$(env_value "$retention_name" || true)"
    if [[ -z "$retention_value" || "$retention_value" =~ ^[0-9]+$ ]]; then
      pass "$retention_name 保留数量有效（空值使用安全默认值）"
    else
      fail "$retention_name 不是非负整数" "修正 $ENV_FILE 后先运行 copperctl backup prune --dry-run"
    fi
  done
  if [[ -f "$backup_dir/last-success.json" && "$(stat -c '%U:%G %a' "$backup_dir/last-success.json" 2>/dev/null)" == "root:root 600" ]]; then
    pass "最近成功备份状态文件存在且权限正确"
  else
    warn "尚无权限正确的最近成功备份状态" "完成配置后运行 sudo copperctl backup create，并把备份复制到客户控制的异机介质"
  fi
  if systemctl is-enabled --quiet copper-backup.timer 2>/dev/null && systemctl is-active --quiet copper-backup.timer 2>/dev/null; then
    pass "每周全量/每日增量一致性备份计时器已启用"
  else
    warn "每周全量/每日增量备份计时器尚未启用" "先完成一次手工全量备份验证，再运行 sudo copperctl backup schedule install"
  fi

  declare -a permission_checks=(
    "$var_dir/mysql|999:999 700|MySQL 数据目录"
    "$var_dir/files|10001:10001 750|附件目录"
    "$var_dir/time-safety|0:10001 750|可信时间状态目录"
    "$(required_env_value COPPER_ETC_DIR)/credentials|10001:10001 700|管理员凭据目录"
    "$(required_env_value COPPER_ETC_DIR)/secrets/copper_db_password|10001:10001 400|数据库业务口令"
    "$(required_env_value COPPER_ETC_DIR)/secrets/mysql_root_password|0:0 400|MySQL root 口令"
  )
  for permission_check in "${permission_checks[@]}"; do
    check_path="${permission_check%%|*}"
    check_rest="${permission_check#*|}"
    expected_permission="${check_rest%%|*}"
    check_label="${check_rest#*|}"
    actual_permission="$(stat -c '%u:%g %a' "$check_path" 2>/dev/null || true)"
    if [[ "$actual_permission" == "$expected_permission" ]]; then
      pass "$check_label 权限正确"
    else
      fail "$check_label 权限为 ${actual_permission:-无法读取}，应为 $expected_permission" "重新运行 sudo ./copperctl install；不要使用 chmod -R 777"
    fi
  done

  tls_mode="$(env_value COPPER_TLS_MODE || true)"
  tls_mode="${tls_mode:-domain}"
  if [[ "$tls_mode" == "ip" ]]; then
    etc_dir="$(required_env_value COPPER_ETC_DIR)"
    domain="$(required_env_value COPPER_DOMAIN)"
    declare -a tls_permission_checks=(
      "$etc_dir/tls|0:10001 750|公网 IP 证书目录"
      "$etc_dir/tls/fullchain.pem|0:10001 640|公网 IP 证书链"
      "$etc_dir/tls/privkey.pem|0:10001 640|公网 IP 证书私钥"
    )
    for permission_check in "${tls_permission_checks[@]}"; do
      check_path="${permission_check%%|*}"
      check_rest="${permission_check#*|}"
      expected_permission="${check_rest%%|*}"
      check_label="${check_rest#*|}"
      actual_permission="$(stat -c '%u:%g %a' "$check_path" 2>/dev/null || true)"
      if [[ "$actual_permission" == "$expected_permission" ]]; then
        pass "$check_label 权限正确"
      else
        fail "$check_label 权限为 ${actual_permission:-无法读取}，应为 $expected_permission" "运行 sudo copperctl tls init，禁止放宽私钥权限"
      fi
    done
    if openssl x509 -in "$etc_dir/tls/fullchain.pem" -noout -checkend 43200 >/dev/null 2>&1 &&
       openssl x509 -in "$etc_dir/tls/fullchain.pem" -noout -ext subjectAltName 2>/dev/null | grep -Fq "IP Address:$domain"; then
      pass "公网 IP 证书 SAN 正确且有效期不少于 12 小时"
    else
      fail "公网 IP 证书无效、SAN 不匹配或即将到期" "运行 sudo copperctl tls renew；失败时查看受限续期日志"
    fi
    if systemctl is-enabled --quiet copper-ip-tls-renew.timer && systemctl is-active --quiet copper-ip-tls-renew.timer; then
      pass "公网 IP 证书自动续期计时器已启用且正在等待"
    else
      fail "公网 IP 证书自动续期计时器未正常运行" "运行 sudo copperctl tls install-timer"
    fi
  elif [[ "$tls_mode" == "lan" ]]; then
    if "$SCRIPT_DIR/lan-access.py" status --env-file "$ENV_FILE" --json >/dev/null 2>&1; then
      pass "LAN 固定地址、受控 DNS、客户 CA 证书和 Docker 防火墙均通过"
    else
      fail "LAN 安全基线未通过" "运行 sudo copperctl lan status --json，按失败项修复；不得改用 HTTP 或公网端口转发"
    fi
  fi

  compose_error=""
  if compose_error="$(compose_cmd config --quiet 2>&1)"; then
    pass "Compose 最终配置有效"
  else
    fail "Compose 配置无效：$compose_error" "修正配置或发布文件后重新运行 doctor"
  fi

  all_services_healthy=1
  for service_name in mysql backend web; do
    service_id="$(compose_cmd ps -q "$service_name" 2>/dev/null || true)"
    if [[ -z "$service_id" ]]; then
      fail "$service_name 容器不存在" "运行 sudo copperctl up"
      all_services_healthy=0
      continue
    fi
    service_state="$(docker inspect --format '{{.State.Status}}' "$service_id" 2>/dev/null || true)"
    service_health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$service_id" 2>/dev/null || true)"
    if [[ "$service_state" == "running" && "$service_health" == "healthy" ]]; then
      pass "$service_name 容器正在运行且健康"
    else
      fail "$service_name 状态为 ${service_state:-未知}/${service_health:-未知}" "运行 sudo copperctl logs $service_name；修复后执行 sudo copperctl up"
      all_services_healthy=0
    fi
  done

  for internal_service in mysql backend; do
    service_id="$(compose_cmd ps -q "$internal_service" 2>/dev/null || true)"
    [[ -n "$service_id" ]] || continue
    port_bindings="$(docker inspect --format '{{json .HostConfig.PortBindings}}' "$service_id" 2>/dev/null || true)"
    if [[ "$port_bindings" == "{}" || "$port_bindings" == "null" ]]; then
      pass "$internal_service 没有宿主机端口映射"
    else
      fail "$internal_service 存在宿主机端口映射：$port_bindings" "停止发布并恢复官方 compose.yaml；数据库和 Backend 禁止对外暴露"
    fi
  done

  mode="$(required_env_value COPPER_DEPLOY_MODE)"
  domain="$(required_env_value COPPER_DOMAIN)"
  if [[ "$all_services_healthy" == "1" && "$mode" == "external-proxy" ]]; then
    proxy_port="$(env_value COPPER_PROXY_PORT || true)"
    proxy_port="${proxy_port:-8080}"
    if curl --fail --silent --show-error --max-time 5 "http://127.0.0.1:$proxy_port/readyz" >/dev/null 2>&1; then
      pass "回环代理入口 readiness 正常"
    else
      fail "回环代理入口 127.0.0.1:$proxy_port 不可用" "检查客户反向代理、Web 日志和 COPPER_PROXY_PORT"
    fi
    warn "外部代理模式的公网 TLS 不能只由容器内部证明" "从客户网络访问 https://$domain/healthz 并检查证书链"
  elif [[ "$all_services_healthy" == "1" && "$mode" == "lan" ]]; then
    lan_bind_ip="$(required_env_value COPPER_LAN_BIND_IP)"
    lan_ca="$(required_env_value COPPER_ETC_DIR)/tls/lan-ca.pem"
    if curl --fail --silent --show-error --max-time 8 --cacert "$lan_ca" \
      --resolve "$domain:443:$lan_bind_ip" "https://$domain/healthz" >/dev/null 2>&1; then
      pass "LAN HTTPS、域名、客户 CA 信任链与健康入口验证正常"
      warn "服务器自检不能替代客户端信任验收" "在同一 LAN 的桌面和移动设备导入客户根 CA，并访问 https://$domain/healthz"
    else
      fail "无法通过客户 CA 验证 LAN HTTPS 入口" "运行 sudo copperctl lan status；检查证书 SAN、Caddy 日志、私网地址和防火墙"
    fi
  elif [[ "$all_services_healthy" == "1" ]]; then
    if [[ "${COPPER_TEST_MODE:-0}" == "1" && ( "$domain" == "localhost" || "$domain" == *.localhost ) ]] \
      && curl --fail --silent --show-error --max-time 8 --insecure \
        --resolve "$domain:443:127.0.0.1" "https://$domain/healthz" >/dev/null 2>&1; then
      pass "隔离测试 HTTPS 入口验证正常"
    elif [[ "$tls_mode" == "ip" ]] && curl --fail --silent --show-error --max-time 8 --connect-to "$domain:443:127.0.0.1:443" "https://$domain/healthz" >/dev/null 2>&1; then
      pass "公网 IP 证书与本机 HTTPS 入口验证正常"
      warn "本机自检不能证明云侧公网 443 已放通" "从客户网络访问 https://$domain/healthz 并检查证书链"
    elif [[ "$tls_mode" != "ip" ]] && curl --fail --silent --show-error --max-time 8 "https://$domain/healthz" >/dev/null 2>&1; then
      pass "公开 HTTPS 与证书验证正常"
    else
      if [[ "$tls_mode" == "ip" ]]; then
        fail "无法通过 HTTPS 访问公网 IP $domain" "检查云安全组 80/443、IP 证书有效期、系统时间和 Caddy 日志"
      else
        fail "无法通过 HTTPS 访问 $domain" "检查 DNS A/AAAA、云安全组 80/443、系统时间和 Caddy 日志"
      fi
    fi
  fi

  remote_provider="$(env_value COPPER_REMOTE_ACCESS_PROVIDER || true)"
  remote_provider="${remote_provider:-none}"
  remote_enabled="$(env_value COPPER_REMOTE_ACCESS_ENABLED || true)"
  remote_enabled="${remote_enabled:-no}"
  if [[ "$remote_provider" == "cloudflare" && "$remote_enabled" == "yes" ]]; then
    if "$SCRIPT_DIR/remote-access-cloudflare.py" --env-file "$ENV_FILE" --root "$ROOT_DIR" status --json >/dev/null 2>&1; then
      pass "Cloudflare 远程访问的 origin、connector 和 Access 默认拒绝均通过"
    else
      warn "Cloudflare 远程访问已启用但未通过完整健康检查；LAN 核心业务不受影响" \
        "运行 sudo copperctl remote-access cloudflare status；必要时执行 disable 立即关闭公网入口"
    fi
  elif [[ "$remote_provider" == "cloudflare" ]]; then
    pass "Cloudflare 远程访问已配置但保持停用，LAN 核心业务独立运行"
  else
    pass "未启用可选互联网远程访问，LAN 核心业务不依赖公共互联网"
  fi

  credential_file="$(required_env_value COPPER_ETC_DIR)/credentials/initial-admin.txt"
  if [[ -e "$credential_file" ]]; then
    warn "首次管理员凭据仍待交付和改密" "在可信终端运行 sudo copperctl admin init；交付后立即首次登录改密"
  else
    pass "没有遗留的首次管理员一次性凭据文件"
  fi

  secret_audit_error=""
  if secret_audit_error="$("$SCRIPT_DIR/security-secrets.py" audit --env-file "$ENV_FILE" 2>&1)"; then
    pass "生产秘密未进入配置值、进程参数或不安全权限文件"
  else
    fail "生产秘密生命周期检查未通过" "运行 sudo copperctl security secrets audit，并按失败项轮换或修复权限"
  fi
fi

echo
echo "自检汇总：通过 $pass_count，提醒 $warn_count，失败 $fail_count"
if [[ "$fail_count" -gt 0 ]]; then
  echo "结论：站点未达到可交付状态。请按每个失败项的“下一步”处理。"
  exit 1
fi
echo "结论：未发现阻断项。"
