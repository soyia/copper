#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

bash -n copperctl copper-maintain scripts/lib.sh scripts/prepare-host.sh scripts/compose.sh \
  scripts/permanently-delete-data.sh scripts/doctor.sh scripts/admin.sh scripts/backup.sh \
  scripts/backup-schedule.sh scripts/backup-key.sh scripts/backup-job.sh scripts/backup-status.sh \
  scripts/verification-sql.sh scripts/monitor.sh scripts/monitor-schedule.sh scripts/operations.sh scripts/time-safety.sh \
  scripts/egress-audit.sh \
  scripts/restore.sh \
  scripts/mysql-entrypoint.sh \
  scripts/bootstrap-ubuntu.sh scripts/m1-07-preflight.sh scripts/build-release.sh \
  scripts/fetch-offline-docker.sh scripts/install-offline-ubuntu.sh \
  scripts/export-offline-images.sh scripts/import-offline-images.sh \
  scripts/build-release-delivery.sh scripts/release-delivery.sh \
  scripts/ip-tls.sh scripts/lan-certificate.sh scripts/lan-firewall.sh scripts/security-scan.sh scripts/build-secure-release.sh \
  scripts/build-rights-evidence.sh
bash -n scripts/upgrade.sh scripts/host-autostart.sh
bash -n scripts/cleanup-mysql-actions-artifacts.sh
bash -n tests/m2-backup-runtime.sh
bash -n tests/incremental-mysql-runtime.sh
bash -n tests/m2-restore-runtime.sh
bash -n tests/run-m2-restore-local-database.sh
bash -n tests/run-m2-storage-local-database.sh tests/run-m2-key-lifecycle-local-database.sh
bash -n tests/m3-version-policy.sh
bash -n tests/run-m3-upgrade-preflight-local-database.sh
bash -n tests/run-m3-upgrade-local-database.sh
bash -n tests/run-m3-delivery-local-database.sh
bash -n tests/run-m3-final-x86-acceptance.sh
bash -n scripts/export-mysql-platform.sh
bash -n tests/run-m5-multiarch-host-acceptance.sh
bash -n tests/run-m5-local-host-lifecycle-x86-acceptance.sh
bash -n tests/run-m5-storage-migration-x86-acceptance.sh
bash -n tests/run-m5-lan-network-contract.sh
bash -n tests/m5-lan-acceptance-network.sh tests/run-m5-lan-x86-acceptance.sh
bash -n tests/run-m4-monitoring-x86-acceptance.sh tests/run-m4-operations-x86-acceptance.sh
bash -n tests/run-m4-fault-recovery-x86-acceptance.sh tests/run-m4-final-x86-acceptance.sh
bash -n tests/remote-access-lib-contract.sh
python3 -m py_compile copper-install copper-install-from-mac copper-manage-from-mac copper-upgrade-from-mac copper-recover-from-mac copper-key-offline scripts/build-guided-installer-medium.py scripts/backup-chain.py scripts/backup-key.py scripts/backup-job.py scripts/storage-target.py scripts/fault-evidence.py scripts/monitor.py scripts/operations.py scripts/time-safety.py \
  scripts/release-contract.py scripts/build_provenance.py scripts/delivery-contract.py scripts/upgrade-state.py scripts/upgrade-run.py scripts/upgrade-env.py scripts/upgrade-control-plane.py \
  scripts/multiarch-contract.py scripts/oci-archive-image-id.py scripts/local-host-preflight.py scripts/lan-access.py \
  scripts/log-redact.py scripts/security-secrets.py scripts/security-evidence.py scripts/rights-evidence.py scripts/security-rotate-database.py scripts/egress-audit.py scripts/remote-access-cloudflare.py \
  scripts/source-rehearsal.py \
  tests/backup-alert-receiver.py tests/test_backup_chain.py tests/test_release_contract.py tests/test_delivery_contract.py \
  tests/test_upgrade_state.py tests/test_upgrade_run.py tests/test_log_redact.py \
  tests/test_security_secrets.py tests/test_security_evidence.py tests/test_rights_evidence.py tests/test_security_rotate_database.py \
  tests/test_fault_evidence.py tests/test_monitor.py tests/test_operations.py tests/test_time_safety.py tests/test_egress_audit.py \
  tests/test_local_host_preflight.py tests/test_lan_access.py tests/test_storage_target.py tests/lan-test-dns-server.py tests/test_remote_access_cloudflare.py \
  tests/test_source_rehearsal.py tests/test_oci_archive_image_id.py tests/test_guided_installer.py tests/test_mac_remote_installer.py tests/test_operator_tools.py tests/test_mac_guided_upgrade.py tests/test_disaster_recovery.py
python3 -m py_compile tests/test_erp_backup_restore_coverage.py scripts/ocr-config.py tests/test_optional_ocr.py

grep -Fq 'Run fail-closed confidentiality audit' .github/workflows/verify-m6-confidentiality.yml
grep -Fq 'Record audit evidence digest in the durable job log' .github/workflows/verify-m6-confidentiality.yml
grep -A6 -F 'Upload redacted audit evidence' .github/workflows/verify-m6-confidentiality.yml | grep -Fq 'continue-on-error: true'
grep -Fq 'scripts/oci-archive-image-id.py' .github/workflows/transfer-m7-internal-mysql.yml
grep -Fq 'importedImageId:$importedImageId' .github/workflows/transfer-m7-internal-mysql.yml
grep -Fq 'Offline imported image ID does not match the reviewed M7 allowlist' .github/workflows/transfer-m7-internal-mysql.yml

test -x copper-install
test -x copper-install-from-mac
test -x copper-manage-from-mac
test -x copper-upgrade-from-mac
test -x copper-recover-from-mac
test -x copper-maintain
test -x scripts/build-guided-installer-medium.py
test -x scripts/remote-access-cloudflare.py
test -x scripts/cleanup-mysql-actions-artifacts.sh
test -x scripts/backup-chain.py
test -x tests/incremental-mysql-runtime.sh
grep -Fq 'INTERNAL_REHEARSAL_ONLY' copper-install
grep -Fq '我已设置好，重新检测' copper-install
grep -Fq '更换域名或服务器 IP' copper-install
grep -Fq '不同版本请使用受支持的升级流程' copper-install
grep -Fq 'AGE-SECRET-KEY' copper-install
grep -Fq 'WAITING_FOR_CERTIFICATE' copper-install
grep -Fq '私钥不会写入安装答案、安装介质或服务器' copper-install-from-mac
grep -Fq 'BatchMode=yes' copper-install-from-mac
grep -Fq 'TRANSFER_ATTEMPTS = 3' copper-install-from-mac
grep -Fq 'put -a' copper-install-from-mac
grep -Fq 'ServerAliveInterval=15' copper-install-from-mac
grep -Fq 'Copper Installer/unfinished-install.json' docs/M7_GUIDED_INSTALLER.md
grep -Fq '是否继续上次安装？' copper-install-from-mac
grep -Fq '继续首次登录和数据保护：./copper-manage-from-mac' copper-install-from-mac
grep -Fq '是否现在继续首次登录和数据保护？[Y/n]' copper-install-from-mac
grep -Fq '完成数据保护' copper-maintain
grep -Fq 'INSTALLER_VERSION = "0.4.5"' copper-install
grep -Fq 'INSTALLER_VERSION = "0.4.5"' copper-install-from-mac
grep -Fq 'INSTALLER_VERSION = "0.4.5"' scripts/build-guided-installer-medium.py
grep -Fq 'validate_managed_request' copper-install
grep -Fq 'verify_completed_installation' copper-install
grep -Fq '本次不会保存站点记录，也不会进入首次登录向导' copper-install-from-mac
grep -Fq 'check_remote_installation_boundary' copper-install-from-mac
grep -Fq 'backup restore stage' copper-manage-from-mac
grep -Fq '创建并离线验证整站备份后，彻底移除站点' copper-manage-from-mac
grep -Fq 'UNINSTALLED_DATA_PRESERVED' copper-install
grep -Fq 'remove-site' copper-install
grep -Fq 'serverResidueCheck' copper-manage-from-mac
if grep -Fq 'backup restore switch' copper-manage-from-mac; then
  echo '普通 Mac 管理入口不得一键覆盖正式数据' >&2
  exit 1
fi
grep -Fq 'backup restore switch' copper-recover-from-mac
grep -Fq '从整站备份恢复已删除的站点' copper-manage-from-mac
grep -Fq '升级 Copper（自动备份、迁移、切换和失败保护）' copper-manage-from-mac
grep -Fq 'upgrade accept' copper-upgrade-from-mac
grep -Fq 'accept-upgrade' scripts/restore.sh
grep -Fq 'Copper 一站式安装与维护入口' docs/M7_GUIDED_INSTALLER.md
grep -Fq '普通安装、继续和修复仍没有“覆盖安装”' docs/M7_GUIDED_INSTALLER.md

grep -q 'internal: true' compose.yaml
grep -q 'condition: service_healthy' compose.yaml
grep -q 'COPPER_DB_PASSWORD_FILE: /run/secrets/copper_db_password' compose.yaml
grep -q 'source: ./scripts/mysql-entrypoint.sh' compose.yaml
grep -Fq 'unset MYSQL_PASSWORD MYSQL_ROOT_PASSWORD' scripts/mysql-entrypoint.sh
grep -Fq 'END { if (replaced != 1) exit 92 }' scripts/mysql-entrypoint.sh
grep -q 'COPPER_COOKIE_SECURE: "true"' compose.yaml
grep -q 'COPPER_COOKIE_SAME_SITE: Strict' compose.yaml
grep -q -- '--log-bin=/var/lib/mysql/binlog' compose.yaml
grep -q -- '--binlog-format=ROW' compose.yaml
grep -q -- '--binlog-row-image=FULL' compose.yaml
grep -q -- '--binlog-expire-logs-seconds=2592000' compose.yaml
grep -Fq '^copper-mysql-runtime-amd64-[0-9a-f]{40}$' scripts/cleanup-mysql-actions-artifacts.sh
grep -Fq 'COPPER_ARTIFACT_KEEP_SUCCESSFUL must be 0-2' scripts/cleanup-mysql-actions-artifacts.sh
grep -Fq 'actions: write' .github/workflows/publish-mysql-runtime.yml
grep -Fq 'COPPER_ARTIFACT_KEEP_SUCCESSFUL: '\''2'\''' .github/workflows/cleanup-mysql-actions-artifacts.yml
grep -q -- '--general-log=OFF' compose.yaml
grep -q -- '--slow-query-log=OFF' compose.yaml
grep -q 'LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE: 20MB' compose.yaml
grep -q 'LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY: "7"' compose.yaml
grep -q 'LOGGING_LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP: 200MB' compose.yaml
grep -q 'COPPER_TIME_SAFETY_FILE: /run/copper/time-safety/time-safety.json' compose.yaml
grep -q 'source: ${COPPER_VAR_DIR:-/var/lib/copper}/time-safety' compose.yaml
grep -Fq '将同一备份包发布到已配置目标：$target_names' scripts/backup.sh
if grep -Fq '将同一备份包发布到已配置的本地、NAS 和 S3 兼容目标' scripts/backup.sh; then
  echo 'backup.sh 不得把未配置的 NAS/S3 描述为已发布目标' >&2
  exit 1
fi
grep -A3 'source: ${COPPER_VAR_DIR:-/var/lib/copper}/time-safety' compose.yaml | grep -q 'read_only: true'
grep -q 'root -g 10001 -m 0750 "$var_dir/time-safety"' scripts/prepare-host.sh
grep -q 'install -d -o root -g 10001 -m 0750 "$var_dir/time-safety"' scripts/time-safety.sh
grep -q 'time-safety.sh' scripts/storage-capacity.sh
grep -q 'time-safety.sh' scripts/monitor.sh
grep -q 'source: .*/mysql' compose.yaml
grep -q 'source: .*/files' compose.yaml
grep -q 'source: .*/backups' compose.yaml
grep -q 'published: "80"' compose.yaml
grep -q 'published: "443"' compose.yaml
grep -q 'host_ip: 127.0.0.1' compose.external-proxy.yaml
grep -q 'ports: !override' compose.external-proxy.yaml
grep -q 'ports: !override' compose.lan.yaml
grep -q 'host_ip: ${COPPER_LAN_BIND_IP' compose.lan.yaml
grep -q 'profiles:' compose.remote-access-cloudflare.yaml
grep -q 'remote-access-cloudflare' compose.remote-access-cloudflare.yaml
grep -q 'host_ip: 127.0.0.1' compose.remote-access-cloudflare.yaml
grep -q 'target: 20241' compose.remote-access-cloudflare.yaml
grep -q -- '--token-file' compose.remote-access-cloudflare.yaml
grep -q '/run/secrets/cloudflare_tunnel_token' compose.remote-access-cloudflare.yaml
grep -q 'file: ${COPPER_CLOUDFLARE_TOKEN_FILE' compose.remote-access-cloudflare.yaml
grep -q 'cap_drop:' compose.remote-access-cloudflare.yaml
grep -q 'user: "0:0"' compose.remote-access-cloudflare.yaml
grep -q 'no-new-privileges:true' compose.remote-access-cloudflare.yaml
grep -A3 '^    networks:' compose.remote-access-cloudflare.yaml | grep -q 'edge'
if grep -A3 '^    networks:' compose.remote-access-cloudflare.yaml | grep -q 'data'; then
  echo 'cloudflared 不得加入内部 data 网络' >&2
  exit 1
fi
if grep -Eq 'published:.*(80|443|3306|8191)' compose.remote-access-cloudflare.yaml; then
  echo 'cloudflared 不得发布 Copper 业务或内部端口' >&2
  exit 1
fi
if grep -Eq '(^|[[:space:]])token:' compose.remote-access-cloudflare.yaml; then
  echo 'cloudflared token 不得进入 Compose 环境或参数值' >&2
  exit 1
fi
grep -q 'tls /etc/copper/tls/lan-fullchain.pem /etc/copper/tls/lan-privkey.pem' config/Caddyfile.lan
grep -Fq 'redir https://{$COPPER_SITE_ADDRESS}{uri} 308' config/Caddyfile.lan
grep -Fq 'http://{$COPPER_SITE_ADDRESS}:8080' config/Caddyfile.lan
grep -q 'compose.lan.yaml' scripts/lib.sh
grep -q 'compose.remote-access-cloudflare.yaml' scripts/lib.sh
grep -q 'DOCKER-USER' scripts/lan-firewall.sh
grep -q 'cidr_output=.*python3' scripts/lan-firewall.sh
grep -q '^append_response_rule()' scripts/lan-firewall.sh
grep -q -- '--ctstate ESTABLISHED,RELATED' scripts/lan-firewall.sh
grep -q '^restart_web_with_certificate()' scripts/lan-certificate.sh
grep -q 'compose_cmd restart web' scripts/lan-certificate.sh
grep -q '0.0.0.0/0' tests/test_lan_access.py
grep -q '正式多人 LAN 禁止单张自签名叶证书' scripts/lan-certificate.sh
grep -q 'basicConstraints CA:TRUE' scripts/lan-certificate.sh
grep -q 'RSA 私钥强度不足 2048 bit' scripts/lan-certificate.sh
grep -q 'http://127.0.0.1:8081' config/Caddyfile
grep -q 'try_files {path} /index.html' config/Caddyfile.common
grep -q '@api path /api /api/\*' config/Caddyfile.common
grep -q 'format filter' config/Caddyfile.common
grep -q 'request>headers>Authorization delete' config/Caddyfile.common
grep -q 'request>headers>Cookie delete' config/Caddyfile.common
grep -q 'request>remote_ip ip_mask' config/Caddyfile.common
grep -Fq 'Content-Security-Policy "default-src '\''self'\''' config/Caddyfile.common
grep -Fq 'Strict-Transport-Security "max-age=31536000; includeSubDomains"' config/Caddyfile.common
grep -q 'tls /etc/copper/tls/fullchain.pem /etc/copper/tls/privkey.pem' config/Caddyfile.ip-tls
grep -q 'default_sni {$COPPER_SITE_ADDRESS}' config/Caddyfile.ip-tls
grep -q 'certificate-profile shortlived' scripts/ip-tls.sh
grep -q 'copper-ip-tls-renew.timer' scripts/ip-tls.sh
grep -q 'COPPER_TLS_MODE' scripts/lib.sh
grep -q 'compose.ip-tls.yaml' scripts/lib.sh
grep -q 'COPPER_PERSISTENT_REBOOT_TEST:-0' scripts/lib.sh
grep -q '测试模式使用正式旁路目录时必须显式设置 COPPER_PERSISTENT_REBOOT_TEST=1' scripts/restore.sh
grep -q '^unset COPPER_TEST_MODE$' scripts/bootstrap-ubuntu.sh
grep -q '^unset COPPER_TEST_MODE$' scripts/m1-07-preflight.sh
grep -q 'COPPER_M1_STOPPED_BACKUP_V1' scripts/backup.sh
grep -q 'copper-backup/v1' scripts/backup.sh
grep -q 'payload.tar.zst.age' scripts/backup.sh
grep -q 'COPPER_BACKUP_AGE_RECIPIENT' config/copper.env.example
grep -q 'COPPER_BACKUP_ALERT_MODE' config/copper.env.example
grep -q 'backup status' copperctl
grep -q 'backup key' copperctl
grep -q 'storage enroll' copperctl
grep -q 'COPPER_BACKUP_EXTERNAL_DIR' config/copper.env.example
grep -q 'copper-storage-identity/v1' scripts/storage-target.py
grep -q 'release validate' copperctl
grep -q 'release stage online' copperctl
grep -q 'release stage offline' copperctl
grep -q 'release status' copperctl
grep -q 'security secrets audit' copperctl
grep -q 'security secrets rotate-database' copperctl
grep -q 'security release verify' copperctl
grep -q 'security egress status' copperctl
grep -q 'copper-egress-policy/v1' config/egress-policy.json
grep -q 'officialServiceEndpoints.*\[\]' config/egress-policy.json
grep -q 'remote-access cloudflare configure' copperctl
grep -q 'remote-access cloudflare rotate-token' copperctl
grep -q 'REMOVE-CLOUDFLARE' copperctl
grep -q '互联网远程访问（Cloudflare，可选）' copper-maintain
grep -q '互联网远程访问（Cloudflare，可选）' copper-manage-from-mac
grep -q 'sudo copper-maintain remote-access' copper-manage-from-mac
grep -q '完成后返回“互联网远程访问”维护菜单' scripts/remote-access-cloudflare.py
grep -q 'run_menu_action "启用并验证"' copper-maintain
grep -q 'guided-remote-access' scripts/build-guided-installer-medium.py
if grep -Fq 'sudo copperctl remote-access' docs/M5_CLOUDFLARE_REMOTE_ACCESS.md; then
  echo "普通用户手册不得要求复制 Cloudflare 底层子命令" >&2
  exit 1
fi
grep -q 'COPPER_REMOTE_ACCESS_PROVIDER=none' config/copper.env.example
grep -q 'cloudflare/cloudflared@sha256:4f6655284ab3d252b7f28fedb19fe6c8fc82ee5b1295c20ac74d475e5398a52d' config/copper.env.example
grep -q 'remote-access.credentials' scripts/security-secrets.py
grep -q 'remote-access.cloudflare' config/egress-policy.json
grep -q 'remote-access-cloudflare.py.*--env-file' scripts/permanently-delete-data.sh
grep -q 'Tunnel token' docs/M5_CLOUDFLARE_REMOTE_ACCESS.md
grep -q 'copper-backup/v2.*不包含 Tunnel token' docs/M5_CLOUDFLARE_REMOTE_ACCESS.md
grep -q 'Cloudflare Tunnel 可选互联网入口' docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md
grep -q 'Internal rehearsal / Deferred after 1.0' docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md
grep -q 'monitor check' copperctl
grep -q 'monitor schedule' copperctl
grep -q 'operations status' copperctl
grep -q 'operations checklist' copperctl
grep -q 'host preflight' copperctl
grep -q 'host autostart' copperctl
grep -q 'host-preflight.json' copperctl
grep -q 'COPPER_MONITOR_ALERT_MODE' config/copper.env.example
grep -q 'copper-delivery/v1' scripts/delivery-contract.py
grep -q 'copper-upgrade-delivery/v1' scripts/delivery-contract.py
grep -q 'copper-delivery-ready/v1' scripts/delivery-contract.py
grep -q 'release stage offline-delta' copperctl
grep -q 'remote_reusable_image_archives' copper-install-from-mac
grep -q 'assert-manifest.*release-root' scripts/upgrade.sh
grep -Fq 'required_memory_bytes=$((min_memory_gib * 950000 * 1024))' scripts/upgrade.sh
grep -Fq '"$memory_kib" -ge 3800000' scripts/m1-07-preflight.sh
test "$(python3 -c 'import json; print(json.load(open("config/local-host-policy.json"))["profiles"]["ubuntu-24.04-x86_64"]["minimumMemoryBytes"])')" \
  = "$((3800000 * 1024))"
grep -q 'docker pull --platform linux/amd64' scripts/release-delivery.sh
grep -q 'docker load --input' scripts/release-delivery.sh
grep -q -- '--verify-images' scripts/release-delivery.sh
grep -q '^require_supported_platform >&2$' scripts/release-delivery.sh
grep -q 'upgrade preflight' copperctl
grep -q 'upgrade prepare' copperctl
grep -q 'upgrade migrate' copperctl
grep -q 'upgrade switch' copperctl
grep -q '配置和维护程序' copperctl
grep -q 'MIGRATION_VALIDATED' scripts/upgrade.sh
grep -q 'RECOVERY_FAILED' scripts/upgrade.sh
grep -q 'INSERT INTO system_upgrade_audit' scripts/restore.sh
grep -q "status='SWITCHED'" scripts/restore.sh
grep -q '目标镜像尚未交付到本机，M3-03 禁止隐式拉取' scripts/restore.sh
grep -q -- '--pull never' scripts/restore.sh
grep -q 'assert-ready --require-restore-test' scripts/upgrade.sh
grep -q 'backup verify' scripts/upgrade.sh
grep -q 'PREPARED' scripts/upgrade-state.py
grep -q 'assert-ready' scripts/backup-job.sh
grep -q 'assert-ready --require-restore-test' scripts/backup-schedule.sh
grep -q 'backup-key.sh.*audit' scripts/backup-schedule.sh
grep -q '只允许在可信交互终端执行' scripts/admin.sh
grep -q '自检汇总' scripts/doctor.sh
grep -q -- '--resolve "$domain:443:127.0.0.1"' scripts/doctor.sh
grep -q 'backup restore' copperctl
grep -q 'COPPER_RESTORE_ROOT' config/copper.env.example
grep -q 'restore_root' scripts/permanently-delete-data.sh
grep -q 'compare-verification' scripts/restore.sh
grep -q 'compare-verification.*|| return 1' scripts/restore.sh
grep -q 'status=VALIDATED' scripts/restore.sh
grep -q 'status=SWITCHED' scripts/restore.sh
grep -q 'status=ROLLED_BACK' scripts/restore.sh

if grep -n -E 'apt-cache madison .*awk .*exit' scripts/bootstrap-ubuntu.sh; then
  echo "Docker 版本选择不得提前退出管道，否则 pipefail 会把 apt-cache 的 SIGPIPE 视为安装失败" >&2
  exit 1
fi

if grep -R -n -E '(^|[^[:alnum:]])(root/123456|password123|:latest)([^[:alnum:]]|$)' -- compose.yaml config; then
  echo "发现弱默认值或 latest" >&2
  exit 1
fi

if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if git ls-files | grep -E '(^|/)(secrets|runtime)/|(^|/)\.env$|\.pem$|\.key$'; then
    echo "发现不应提交的密钥或运行数据" >&2
    exit 1
  fi
fi

test -x scripts/prepare-host.sh
test -x scripts/compose.sh
test -x scripts/permanently-delete-data.sh
test -x copperctl
test -x scripts/doctor.sh
test -x scripts/admin.sh
test -x scripts/backup.sh
test -x scripts/backup-schedule.sh
test -x scripts/backup-key.sh
test -x scripts/backup-key.py
test -x scripts/backup-job.sh
test -x scripts/backup-job.py
test -x scripts/storage-target.py
test -x scripts/backup-status.sh
test -x copper-key-offline
test -x scripts/backup-metadata.py
test -s scripts/verification-sql.sh
test -x scripts/restore.sh
test -x scripts/restore-metadata.py
test -s tests/test_restore_metadata.py
grep -Fq 'assert-core-foreign-keys "$schema_version" "$fk_metadata"' scripts/restore.sh
grep -Fq 'CORE_FOREIGN_KEY_SCHEMA_VERSION = 67' scripts/restore-metadata.py
test -s tests/test_backup_key_lifecycle.py
test -s tests/test_backup_job.py
test -x scripts/bootstrap-ubuntu.sh
test -x scripts/m1-07-preflight.sh
test -x scripts/build-release.sh
test -x scripts/fetch-offline-docker.sh
test -x scripts/install-offline-ubuntu.sh
test -x scripts/export-offline-images.sh
test -x scripts/import-offline-images.sh
test -x scripts/ip-tls.sh
test -x scripts/lan-access.py
test -x scripts/lan-certificate.sh
test -x scripts/lan-firewall.sh
test -x scripts/release-contract.py
test -x scripts/delivery-contract.py
test -x scripts/multiarch-contract.py
test -x scripts/export-mysql-platform.sh
test -x scripts/local-host-preflight.py
test -x scripts/host-autostart.sh
test -s config/local-host-policy.json
test -s config/systemd/copper.service
test -s tests/test_local_host_preflight.py
test -s tests/test_lan_access.py
test -x tests/lan-test-dns-server.py
test -s docs/M5_LOCAL_HOST_INSTALLATION.md
test -s docs/M5_LOCAL_HOST_ACCEPTANCE_REPORT.md
test -s docs/M5_STORAGE_BACKUP_MIGRATION.md
test -s docs/M5_STORAGE_MIGRATION_ACCEPTANCE_REPORT.md
grep -q '"ubuntu-24.04-x86_64"' config/local-host-policy.json
if grep -qiE 'windows-11|wsl2|docker desktop' config/local-host-policy.json scripts/local-host-preflight.py scripts/host-autostart.sh; then
  echo 'M5 本地主机安装器不得包含 Windows/WSL2/Docker Desktop 运行路径' >&2
  exit 1
fi
grep -q '"/var/lib/copper"' config/local-host-policy.json
grep -q '"nfs"' config/local-host-policy.json
grep -q '"cifs"' config/local-host-policy.json
grep -q '"writeAllowed"' scripts/local-host-preflight.py
grep -q 'ExecStart=/usr/local/sbin/copperctl up' config/systemd/copper.service
grep -q 'ExecStop=/usr/local/sbin/copperctl down' config/systemd/copper.service
grep -q 'Requires=docker.service' config/systemd/copper.service
grep -q 'Windows 电脑可以作为虚拟化宿主机' docs/M5_LOCAL_HOST_INSTALLATION.md
grep -q '不支持 Windows 原生、WSL2 或 Docker Desktop 安装' docs/INSTALL.md
grep -q 'OCI manifest digest' docs/INSTALL.md
grep -q '不得只复制主包或把主包另行改名' docs/M2_KEY_LIFECYCLE.md
grep -q '/run/copper-restore-identity.txt' docs/M2_RESTORE.md
grep -q 'VOLATILE_IDENTITY_DESTROYED' docs/M2_RESTORE.md
test -x tests/run-m5-multiarch-host-acceptance.sh
test -x tests/run-m5-local-host-lifecycle-x86-acceptance.sh
test -x tests/run-m5-storage-migration-x86-acceptance.sh
test -x tests/run-m5-lan-network-contract.sh
test -x tests/m5-lan-acceptance-network.sh
test -x tests/run-m5-lan-x86-acceptance.sh
test -s tests/copper-m5-lan-acceptance-network.service
grep -q 'assert_cookie_session certificate-rotated' tests/run-m5-lan-x86-acceptance.sh
grep -q 'assert not connects(3306)' tests/run-m5-lan-x86-acceptance.sh
grep -q 'docker buildx imagetools inspect --raw' scripts/export-mysql-platform.sh
grep -q 'docker pull --platform' scripts/export-mysql-platform.sh
grep -q '只能在真实 x86_64 主机导出' scripts/export-mysql-platform.sh
grep -q '只能在真实 ARM64 主机导出' scripts/export-mysql-platform.sh
if grep -q 'docker image inspect --platform' scripts/export-mysql-platform.sh; then
  echo 'export-mysql-platform.sh 使用了不兼容的 docker image inspect --platform' >&2
  exit 1
fi
grep -q 'validate-release --index' tests/run-m5-multiarch-host-acceptance.sh
grep -q '不得映射宿主机端口' tests/run-m5-multiarch-host-acceptance.sh
grep -q '首次管理员凭据使用后未销毁' tests/run-m5-multiarch-host-acceptance.sh
grep -q 'backup verify' tests/run-m5-multiarch-host-acceptance.sh
grep -q 'producerLocalId' tests/run-m5-multiarch-host-acceptance.sh
grep -q 'runtimeId' tests/run-m5-multiarch-host-acceptance.sh
grep -q 'preflight-fresh.json' tests/run-m5-local-host-lifecycle-x86-acceptance.sh
grep -q 'managed-reinstall' tests/run-m5-local-host-lifecycle-x86-acceptance.sh
grep -q 'systemctl is-enabled --quiet copper.service' tests/run-m5-local-host-lifecycle-x86-acceptance.sh
grep -q 'backup verify' tests/run-m5-local-host-lifecycle-x86-acceptance.sh
grep -q 'backup key record-restore-test' tests/run-m5-local-host-lifecycle-x86-acceptance.sh
grep -q 'physical_external_and_remote_nas_required_for_issue_close=true' tests/run-m5-storage-migration-x86-acceptance.sh
grep -q '\.overallLevel == "NORMAL"' tests/run-m5-local-host-lifecycle-x86-acceptance.sh
test -x scripts/build-release-delivery.sh
test -x scripts/release-delivery.sh
test -x scripts/oci-archive-image-id.py
grep -q 'oci-archive-image-id.py' scripts/build-release-delivery.sh
grep -q 'oci-archive-image-id.py' scripts/export-offline-images.sh
test -x scripts/upgrade-state.py
test -x scripts/upgrade-run.py
test -x scripts/upgrade-env.py
test -x scripts/upgrade-control-plane.py
grep -q 'upgrade-control-plane.py' scripts/upgrade.sh
grep -q 'upgrade-control-plane.py' scripts/restore.sh
test -x scripts/upgrade.sh
test -x scripts/log-redact.py
test -x scripts/security-secrets.py
test -x scripts/security-evidence.py
test -x scripts/rights-evidence.py
test -x scripts/security-rotate-database.py
test -x scripts/egress-audit.sh
test -x scripts/egress-audit.py
test -x scripts/operations.sh
test -x scripts/operations.py
test -x scripts/mysql-entrypoint.sh
test -x scripts/security-scan.sh
test -x scripts/security-export-image.sh
test -x scripts/build-secure-release.sh
test -x scripts/build-rights-evidence.sh
test -x tests/cli-contract.sh
test -x tests/backup-contract.sh
test -x tests/m2-backup-runtime.sh
test -x tests/m2-restore-runtime.sh
test -x tests/run-m2-restore-local-database.sh
test -x tests/run-m2-key-lifecycle-local-database.sh
test -x tests/m3-version-policy.sh
test -x tests/run-m3-upgrade-preflight-local-database.sh
test -x tests/run-m3-upgrade-local-database.sh
test -x tests/run-m3-delivery-local-database.sh
test -x tests/run-m3-final-x86-acceptance.sh
test -x tests/run-m4-monitoring-x86-acceptance.sh
test -x tests/run-m4-operations-x86-acceptance.sh
test -x tests/run-m4-final-x86-acceptance.sh
test -s tests/test_egress_audit.py
test -s tests/test_release_contract.py
test -s tests/test_delivery_contract.py
test -s tests/test_multiarch_contract.py
test -s tests/test_upgrade_state.py
test -s tests/test_upgrade_run.py
test -s tests/test_log_redact.py
test -s tests/test_security_secrets.py
test -s tests/test_security_evidence.py
test -s tests/test_rights_evidence.py
test -s tests/test_security_rotate_database.py
test -s tests/test_monitor.py
test -s tests/test_operations.py
test -x tests/backup-alert-receiver.py
test -s config/systemd/copper-backup.service
test -s config/systemd/copper-backup.timer
test -s config/systemd/copper-monitor.service
test -s config/systemd/copper-monitor.timer
test -s config/operations-policy.json
grep -q '^FixedRandomDelay=true$' config/systemd/copper-backup.timer
grep -q '^LogRateLimitIntervalSec=30s$' config/systemd/copper-backup.service
grep -q '^LogRateLimitIntervalSec=30s$' config/systemd/copper-monitor.service

if command -v systemd-analyze >/dev/null 2>&1 && [[ -x /usr/local/sbin/copperctl ]]; then
  systemd-analyze verify config/systemd/copper-backup.service config/systemd/copper-backup.timer \
    config/systemd/copper-monitor.service config/systemd/copper-monitor.timer
fi

test -s docs/INSTALL.md
test -s docs/TROUBLESHOOTING.md
test -s docs/M1_OPERATIONS_ACCEPTANCE.md
test -s docs/M1_END_TO_END_ACCEPTANCE.md
test -s docs/M2_BACKUP.md
test -s docs/M2_BACKUP_ACCEPTANCE.md
test -s docs/M2_RESTORE.md
test -s docs/M2_RESTORE_ACCEPTANCE.md
test -s docs/M2_KEY_LIFECYCLE.md
test -s docs/M2_KEY_HANDOVER.md
test -s docs/M2_KEY_ALERT_ACCEPTANCE.md
test -s docs/M3_VERSIONING_AND_COMPATIBILITY.md
test -s releases/0.4.0-rc.11/RELEASE_NOTES.md
test -s releases/0.4.0-rc.11/CLOSURE_PLAN.json
cmp -s releases/0.4.0-rc.10/docker-ubuntu-amd64.env releases/0.4.0-rc.11/docker-ubuntu-amd64.env
grep -Fq 'VERSION = "0.4.0-rc.11"' scripts/close-release-candidate.py
grep -Fq 'VERSION = "0.4.0-rc.11"' scripts/build-guided-medium-from-candidate.py
test -s docs/M3_UPGRADE_PREPARATION.md
test -s docs/M3_UPGRADE_STATE_MACHINE.md
test -s docs/M3_UPGRADE_STATE_MACHINE_ACCEPTANCE.md
test -s docs/M3_RELEASE_DELIVERY.md
test -s docs/M3_RELEASE_DELIVERY_ACCEPTANCE.md
test -s docs/M3_FINAL_X86_ACCEPTANCE.md
test -s docs/M3_FINAL_X86_ACCEPTANCE_REPORT.md
test -s config/release-policy.env
test -s config/release-manifest-v1.fields
test -s config/security-policy.env
test -s config/security-exceptions.example.json
test -s config/third-party-rights-policy.json
test -s config/third-party-rights-ledger.example.json
test -s config/rights-exceptions.example.json
test -s config/gitleaksignore
test -s SECURITY.md
test -s docs/M4_SECURITY_SUPPLY_CHAIN.md
test -s docs/M4_MONITORING.md
test -s docs/M4_MONITORING_ACCEPTANCE_REPORT.md
test -s docs/M4_OPERATIONS_RPO_RTO.md
test -s docs/M4_OPERATIONS_ACCEPTANCE_REPORT.md
test -s docs/M4_FINAL_X86_ACCEPTANCE.md
test -s docs/M5_MULTIARCH_IMAGE_RELEASE.md
test -s docs/M6_THIRD_PARTY_RIGHTS_SBOM.md
test -s docs/M6_THIRD_PARTY_RIGHTS_ACCEPTANCE_REPORT.md
test -s docs/M6_THIRD_PARTY_RIGHTS_CURRENT_STATUS.json
grep -Eq '^COPPER_SYFT_IMAGE=.+@sha256:[0-9a-f]{64}$' config/security-policy.env
grep -Eq '^COPPER_GRYPE_IMAGE=.+@sha256:[0-9a-f]{64}$' config/security-policy.env
grep -Eq '^COPPER_GITLEAKS_IMAGE=.+@sha256:[0-9a-f]{64}$' config/security-policy.env
test "$(wc -l < config/gitleaksignore | tr -d ' ')" = "8"
grep -Eq '^[0-9a-f]{40}:src/main/java/com/soyia/copperv2/security/BootstrapCredentialService.java:generic-api-key:27$' config/gitleaksignore
grep -Fqx '4eec4ecb668790a8f982b007918a258986c8c8cf:src/test/java/com/soyia/copperv2/config/SensitiveLogSanitizerTest.java:generic-api-key:13' config/gitleaksignore
grep -Eq '^1f588de9d54352cedef6dbff0992e86256236e91:tests/run-m2-storage-local-database.sh:generic-api-key:537$' config/gitleaksignore
grep -Fqx '/source/copper-deploy-0.2.0-rc.1.tar.gz!copper-deploy-0.2.0-rc.1/tests/run-m2-storage-local-database.sh:generic-api-key:537' config/gitleaksignore
grep -Fqx '/source/acme.sh-3.1.3.tar.gz!acme.sh-3.1.3/dnsapi/dns_active24.sh:generic-api-key:13' config/gitleaksignore
grep -Fqx '/source/acme.sh-3.1.3.tar.gz!acme.sh-3.1.3/dnsapi/dns_active24.sh:generic-api-key:14' config/gitleaksignore
grep -Fqx '/source/acme.sh-3.1.3.tar.gz!acme.sh-3.1.3/notify/gotify.sh:generic-api-key:6' config/gitleaksignore
grep -q 'copper-security/v1' scripts/security-evidence.py
grep -q 'copper-third-party-rights/v1' scripts/rights-evidence.py
grep -q 'legalReviewRequired' scripts/rights-evidence.py
grep -q 'BLOCKED.*例外不得制造再发布权' scripts/rights-evidence.py
grep -q 'delivery-tool' scripts/rights-evidence.py
grep -q 'uses:' docs/M6_THIRD_PARTY_RIGHTS_SBOM.md
grep -q 'M6-05' docs/M6_THIRD_PARTY_RIGHTS_SBOM.md
grep -q 'ENGINEERING_PASS' docs/M6_THIRD_PARTY_RIGHTS_ACCEPTANCE_REPORT.md
grep -q 'M6-05' docs/M6_THIRD_PARTY_RIGHTS_ACCEPTANCE_REPORT.md
grep -q '"status": "ENGINEERING_PASS_LEGAL_REVIEW_REQUIRED"' docs/M6_THIRD_PARTY_RIGHTS_CURRENT_STATUS.json
test -s releases/0.4.0-rc.6/rights-evidence.json
grep -q 'AGE-SECRET-KEY-' scripts/security-secrets.py
grep -q 'security_audit_log' scripts/security-rotate-database.py
grep -q 'build-secure-release.sh' docs/M4_SECURITY_SUPPLY_CHAIN.md
grep -Fq 'git -C "${source_repositories[$index]}" archive --format=tar HEAD' scripts/security-scan.sh
grep -Fq '(cd "$result" && sha256sum "$(basename "$bundle")" > "$(basename "$bundle").sha256")' scripts/security-scan.sh
grep -q '源码 SBOM 只扫描上述提交的 `git archive` 快照' docs/M4_SECURITY_SUPPLY_CHAIN.md
grep -q '不提供未加密备份模式' docs/M2_KEY_LIFECYCLE.md
grep -q '告警发送失败不会把已经成功的本地备份改判失败' docs/M2_KEY_LIFECYCLE.md
test -s releases/0.1.0-m1-rc.1/components.env
test -s releases/0.1.0-m1-rc.2/components.env
test -s releases/0.1.0-m1-rc.2/docker-ubuntu-amd64.env
test -s releases/0.1.0-m1-rc.3/components.env
test -s releases/0.1.0-m1-rc.3/docker-ubuntu-amd64.env
test -s releases/0.1.0-m2-rc.1/components.env
test -s releases/0.1.0-m2-rc.2/components.env
test -s releases/0.2.0-rc.1/components.env
test -s releases/0.2.0-rc.1/upgrade-matrix.json
test -s releases/0.2.0-rc.1/migrations.sha256
test -s releases/0.2.0-rc.1/RELEASE_NOTES.md
test -s releases/0.3.0-rc.2/components.env
test -s releases/0.3.0-rc.2/upgrade-matrix.json
test -s releases/0.3.0-rc.2/migrations.sha256
test -s releases/0.3.0-rc.2/RELEASE_NOTES.md
test -s releases/0.3.0-rc.2/security-exceptions.json
test -s releases/0.3.0-rc.2/govulncheck-gosu.json.gz
test -s releases/0.3.0-rc.3/components.env
test -s releases/0.3.0-rc.3/upgrade-matrix.json
test -s releases/0.3.0-rc.3/migrations.sha256
test -s releases/0.3.0-rc.3/RELEASE_NOTES.md
test -s releases/0.3.0-rc.3/security-exceptions.json
test -s releases/0.3.0-rc.3/govulncheck-gosu.json.gz
grep -q '^COPPER_RELEASE_VERSION=0.3.0-rc.2$' releases/0.3.0-rc.2/components.env
grep -q '^COPPER_WEB_SOURCE_COMMIT=425acf48f9679280199b657436f8cd74e67f9809$' releases/0.3.0-rc.2/components.env
grep -q '^COPPER_BACKEND_SOURCE_COMMIT=3e15d4200613facdb01fbcda6cd540898d79e47b$' releases/0.3.0-rc.2/components.env
grep -q '^COPPER_WEB_IMAGE=ghcr.io/soyia/copper_web@sha256:4c7097fe8b75d64e696af8930186d9d9d01b7fe93f20825fffa29481593154c8$' releases/0.3.0-rc.2/components.env
grep -q '^COPPER_BACKEND_IMAGE=ghcr.io/soyia/copper_backend@sha256:68115e2c0c01277fbc23d19795c1ad75e3a2b5e6dff09f9c9aaa8ac92d3a4e5f$' releases/0.3.0-rc.2/components.env
grep -q '^COPPER_DATABASE_SCHEMA_VERSION=5$' releases/0.3.0-rc.2/components.env
grep -q '^800e0ea3bc9785ce2964fa7842c4279dbf49396b47edad97431c527e56f173c0  V5__security_session_audit.sql$' releases/0.3.0-rc.2/migrations.sha256
grep -q '"targetSchema": "5"' releases/0.3.0-rc.2/upgrade-matrix.json
grep -q '"mode": "retained-sidecar"' releases/0.3.0-rc.2/upgrade-matrix.json
grep -q '^COPPER_RELEASE_VERSION=0.3.0-rc.3$' releases/0.3.0-rc.3/components.env
grep -q '^COPPER_WEB_SOURCE_COMMIT=425acf48f9679280199b657436f8cd74e67f9809$' releases/0.3.0-rc.3/components.env
grep -q '^COPPER_BACKEND_SOURCE_COMMIT=652fe45fc4d60deef4afb36b75693d56acb3fed6$' releases/0.3.0-rc.3/components.env
grep -q '^COPPER_WEB_IMAGE=ghcr.io/soyia/copper_web@sha256:4c7097fe8b75d64e696af8930186d9d9d01b7fe93f20825fffa29481593154c8$' releases/0.3.0-rc.3/components.env
grep -q '^COPPER_BACKEND_IMAGE=ghcr.io/soyia/copper_backend@sha256:4d2160c37fa095e88dd750f9b1a2315a147a1d6cf0cc84a63d659683a7c3f83e$' releases/0.3.0-rc.3/components.env
grep -q '^COPPER_DATABASE_SCHEMA_VERSION=5$' releases/0.3.0-rc.3/components.env
grep -q '^800e0ea3bc9785ce2964fa7842c4279dbf49396b47edad97431c527e56f173c0  V5__security_session_audit.sql$' releases/0.3.0-rc.3/migrations.sha256
grep -q '"targetSchema": "5"' releases/0.3.0-rc.3/upgrade-matrix.json
grep -q '"mode": "retained-sidecar"' releases/0.3.0-rc.3/upgrade-matrix.json
grep -q '^## 功能变化$' releases/0.3.0-rc.3/RELEASE_NOTES.md
grep -q '^## 数据库影响$' releases/0.3.0-rc.3/RELEASE_NOTES.md
grep -q '^## 已知问题$' releases/0.3.0-rc.3/RELEASE_NOTES.md
grep -q '^## 升级步骤$' releases/0.3.0-rc.3/RELEASE_NOTES.md
grep -q '^## 停机与回退$' releases/0.3.0-rc.3/RELEASE_NOTES.md
grep -q '^## 功能变化$' releases/0.2.0-rc.1/RELEASE_NOTES.md
grep -q '^## 数据库影响$' releases/0.2.0-rc.1/RELEASE_NOTES.md
grep -q '^## 已知问题$' releases/0.2.0-rc.1/RELEASE_NOTES.md
grep -q '^## 升级步骤$' releases/0.2.0-rc.1/RELEASE_NOTES.md
grep -q '^## 停机与回退$' releases/0.2.0-rc.1/RELEASE_NOTES.md
grep -q '^COPPER_DATABASE_SCHEMA_VERSION=4$' releases/0.2.0-rc.1/components.env
grep -q '^COPPER_VALIDATION_LEVEL=L4$' releases/0.2.0-rc.1/components.env
grep -q 'CANDIDATE-NOT-A-RELEASE.json' scripts/build-release.sh
test -f config/m6-confidentiality-audit.json
test -f scripts/security/audit-repositories.mjs
grep -q 'audit-repositories.mjs' scripts/build-release.sh
grep -Fq "GITHUB_SERVICE_NOREPLY = ['noreply', 'github.com'].join('@')" scripts/security/audit-repositories.mjs
grep -q 'gate strict' scripts/build-release.sh
grep -Fq 'audit_scope="all"' scripts/build-release.sh
grep -Fq 'audit_scope="current"' scripts/build-release.sh
grep -Fq -- '--scope "$audit_scope"' scripts/build-release.sh
grep -Fq '"confidentialityAuditScope":"current-tree"' scripts/build-release.sh
grep -Fq '"formalHistoryGate":"FORMAL_RELEASE_BLOCKED"' scripts/build-release.sh
grep -q 'fetch-depth: 0' .github/workflows/verify-m6-confidentiality.yml
grep -q 'gate strict' .github/workflows/verify-m6-confidentiality.yml
grep -Fqx 'releases/**/govulncheck-gosu.json.gz export-ignore' .gitattributes
grep -q '^append_relative_checksum()' scripts/build-secure-release.sh
grep -Fq 'append_relative_checksum "$release_dir/$(basename "$bundle")"' scripts/build-secure-release.sh
grep -Fq 'append_relative_checksum "$release_dir/$(basename "$sidecar")"' scripts/build-secure-release.sh
test -f docs/M6_SOURCE_REHEARSAL.md
grep -Fq 'INTERNAL_REHEARSAL_ONLY' scripts/source-rehearsal.py
grep -Fq 'FORMAL_RELEASE_BLOCKED' scripts/source-rehearsal.py
test -s releases/0.4.0-rc.1/copper-source-rehearsal-0.4.0-rc.1.env
grep -q '^COPPER_DEPLOY_COMMIT=700829cd586dc29b7f0b99dc0401bd79629ae44c$' releases/0.4.0-rc.1/copper-source-rehearsal-0.4.0-rc.1.env
test -s releases/0.4.0-rc.4/components.env
test -s releases/0.4.0-rc.4/upgrade-matrix.json
test -s releases/0.4.0-rc.4/migrations.sha256
test -s releases/0.4.0-rc.4/RELEASE_NOTES.md
grep -q '^COPPER_RELEASE_VERSION=0.4.0-rc.4$' releases/0.4.0-rc.4/components.env
grep -q '^COPPER_DATABASE_SCHEMA_VERSION=7$' releases/0.4.0-rc.4/components.env
grep -q '^b203aac6c77b480e2fa31b6006fc953f2d4e59d98c5c48cfc78371153af2a975  V6__copper_bar_preset_dimensions.sql$' releases/0.4.0-rc.4/migrations.sha256
grep -q '^dbf98be5f010b9f8cab39f066ab2dfd2a5c78cd1b0c67381f360eead7a1448eb  V7__outbound_draft_safety.sql$' releases/0.4.0-rc.4/migrations.sha256
grep -q '"version": "0.4.0-rc.3"' releases/0.4.0-rc.4/upgrade-matrix.json
grep -q '"schema": "7"' releases/0.4.0-rc.4/upgrade-matrix.json
grep -q '"targetSchema": "7"' releases/0.4.0-rc.4/upgrade-matrix.json
grep -q '"mode": "retained-sidecar"' releases/0.4.0-rc.4/upgrade-matrix.json
test -s releases/0.4.0-rc.5/components.env
test -s releases/0.4.0-rc.5/upgrade-matrix.json
test -s releases/0.4.0-rc.5/migrations.sha256
test -s releases/0.4.0-rc.5/RELEASE_NOTES.md
grep -q '^COPPER_RELEASE_VERSION=0.4.0-rc.5$' releases/0.4.0-rc.5/components.env
grep -q '^COPPER_WEB_SOURCE_COMMIT=11aacb843d5ab2641d8510492fb92766da99efbc$' releases/0.4.0-rc.5/components.env
grep -q '^COPPER_BACKEND_SOURCE_COMMIT=b9bdef89ad6ec8628f34760200d090b942dc0b58$' releases/0.4.0-rc.5/components.env
grep -q '^COPPER_DATABASE_SCHEMA_VERSION=7$' releases/0.4.0-rc.5/components.env
grep -q '"version": "0.4.0-rc.4"' releases/0.4.0-rc.5/upgrade-matrix.json
grep -q '"targetVersion": "0.4.0-rc.5"' releases/0.4.0-rc.5/upgrade-matrix.json
grep -q 'INTERNAL_REHEARSAL_ONLY' releases/0.4.0-rc.5/RELEASE_NOTES.md
test -s releases/0.4.0-rc.6/components.env
test -s releases/0.4.0-rc.6/upgrade-matrix.json
test -s releases/0.4.0-rc.6/migrations.sha256
test -s releases/0.4.0-rc.6/RELEASE_NOTES.md
grep -q '^COPPER_RELEASE_VERSION=0.4.0-rc.6$' releases/0.4.0-rc.6/components.env
grep -q '^COPPER_WEB_SOURCE_COMMIT=c1b3dbb4a9aad586629da11c22c2fb52461d8d9c$' releases/0.4.0-rc.6/components.env
grep -q '^COPPER_BACKEND_SOURCE_COMMIT=c6511e62ea6996355b21dfc3e99c02c56a231185$' releases/0.4.0-rc.6/components.env
grep -q '^COPPER_DATABASE_SCHEMA_VERSION=19$' releases/0.4.0-rc.6/components.env
grep -q '^ecb377e7773ca411da80ab929ddf71904e1dfa6f432337fa01284495e49cd2c2  V19__tax_invoice_reconciliation.sql$' releases/0.4.0-rc.6/migrations.sha256
grep -q '"version": "0.4.0-rc.5"' releases/0.4.0-rc.6/upgrade-matrix.json
grep -q '"targetVersion": "0.4.0-rc.6"' releases/0.4.0-rc.6/upgrade-matrix.json
grep -q '"targetSchema": "19"' releases/0.4.0-rc.6/upgrade-matrix.json
grep -q 'INTERNAL_REHEARSAL_ONLY' releases/0.4.0-rc.6/RELEASE_NOTES.md
test -s docs/M7_04_UPGRADE_RECOVERY_CONTINUITY_EVIDENCE.md
grep -q 'INTERNAL_REHEARSAL_ONLY' docs/M7_04_UPGRADE_RECOVERY_CONTINUITY_EVIDENCE.md
grep -q 'FORMAL_RELEASE_BLOCKED' docs/M7_04_UPGRADE_RECOVERY_CONTINUITY_EVIDENCE.md
test -s docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md
grep -q 'FIRST_RELEASE_CLOUD_ONLY' docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md
grep -q '当前没有任何正式 `Certified` 环境' docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md
grep -q 'Candidate（1.0 首发拟支持）' docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md
grep -q 'not planned for 1.0' docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md
grep -q 'M7-02 内部工程链已由真实 AWS 安装/恢复/修复证据和自动操作者连续模拟收口' docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md
grep -q 'FORMAL_RELEASE_BLOCKED' docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md

grep -q '^COPPER_SUPPORTED_ARCH=amd64$' releases/0.1.0-m1-rc.1/components.env
grep -q '^COPPER_RELEASE_STATUS=acceptance-candidate$' releases/0.1.0-m1-rc.1/components.env
grep -Eq '^COPPER_WEB_IMAGE=ghcr.io/soyia/copper_web@sha256:[0-9a-f]{64}$' releases/0.1.0-m1-rc.1/components.env
grep -Eq '^COPPER_BACKEND_IMAGE=ghcr.io/soyia/copper_backend@sha256:[0-9a-f]{64}$' releases/0.1.0-m1-rc.1/components.env
grep -Eq '^COPPER_DOCKER_PACKAGE_5_SHA256=[0-9a-f]{64}$' releases/0.1.0-m1-rc.2/docker-ubuntu-amd64.env
grep -q 'docker pull --platform linux/amd64' scripts/export-offline-images.sh
grep -q 'docker load --input' scripts/import-offline-images.sh
grep -q 'dpkg -i' scripts/install-offline-ubuntu.sh
grep -q 'COPPER_IMAGE_DELIVERY=offline' scripts/export-offline-images.sh
grep -q 'OFFLINE_IMAGE_KEYS' scripts/delivery-contract.py
grep -Fq 'COPPER_DELIVERY_PRELOADED_${role_upper}_EVIDENCE' scripts/build-release-delivery.sh
grep -q 'configure_preloaded_image 0 Web WEB' scripts/build-release-delivery.sh
grep -q 'configure_preloaded_image 1 Backend BACKEND' scripts/build-release-delivery.sh
grep -q '^etc_dir="/etc/copper"$' tests/run-m4-final-x86-acceptance.sh
grep -q '^var_dir="/var/lib/copper"$' tests/run-m4-final-x86-acceptance.sh
grep -q '^backup_dir="/var/backups/copper"$' tests/run-m4-final-x86-acceptance.sh
grep -q '^restore_root="/var/lib/copper-restores"$' tests/run-m4-final-x86-acceptance.sh
grep -q '"COPPER_DEPLOY_MODE":"standard"' tests/run-m4-final-x86-acceptance.sh
grep -q 'deploy_mode == "standard" and tls_mode == "domain"' scripts/egress-audit.py
grep -q '"activation": "standard-domain-tls"' config/egress-policy.json
grep -q 'monitor-schedule.sh.*disable' scripts/permanently-delete-data.sh
grep -q 'host-autostart.sh.*disable' scripts/permanently-delete-data.sh
grep -q 'backup-schedule.sh.*disable' scripts/permanently-delete-data.sh
grep -q 'ip-tls.sh.*disable-timer' scripts/permanently-delete-data.sh
grep -q 'backup-schedule.sh.*disable' copperctl
grep -q 'ip-tls.sh.*disable-timer' copperctl
if grep -q 'api_post /supplier-payments .*purchaseOrderId.*inboundId' tests/run-m4-final-x86-acceptance.sh; then
  echo "供应商付款验收不得同时传采购订单和入库单，避免重复指定核销来源" >&2
  exit 1
fi
if grep -q 'api_post /payments .*salesOrderId.*outboundId' tests/run-m4-final-x86-acceptance.sh; then
  echo "客户回款验收不得同时传销售订单和出库单，避免重复指定核销来源" >&2
  exit 1
fi
grep -q 'inbound-final.json' tests/run-m4-final-x86-acceptance.sh
grep -q 'sales-final.json' tests/run-m4-final-x86-acceptance.sh
grep -q 'outbound-final.json' tests/run-m4-final-x86-acceptance.sh
grep -Fq 'sales["paymentStatus"]=="PAID"' tests/run-m4-final-x86-acceptance.sh
grep -Fq 'outbound["paymentStatus"]=="PAID"' tests/run-m4-final-x86-acceptance.sh
grep -q -- '--ready' scripts/upgrade-env.py
grep -q 'sha256:\[0-9a-f\]' scripts/lib.sh
grep -q 'sha256sum docker-ubuntu-amd64.env' scripts/fetch-offline-docker.sh
grep -q '^COPPER_RELEASE_VERSION=0.1.0-m1-rc.3$' releases/0.1.0-m1-rc.3/components.env
grep -q '^COPPER_ACME_VERSION=3.1.3$' releases/0.1.0-m1-rc.3/components.env
grep -q '^COPPER_ACME_ARCHIVE_SHA256=efd12b265252f8875269960b6b31830731ccce2b3e6ff8e7ecfbee21fde35ab4$' releases/0.1.0-m1-rc.3/components.env
grep -q '^COPPER_RELEASE_VERSION=0.1.0-m2-rc.1$' releases/0.1.0-m2-rc.1/components.env
grep -q '^COPPER_RELEASE_STATUS=acceptance-candidate$' releases/0.1.0-m2-rc.1/components.env
grep -q '^COPPER_SUPPORTED_ARCH=amd64$' releases/0.1.0-m2-rc.1/components.env
grep -q '^COPPER_WEB_SOURCE_COMMIT=03525f65cbde5f24843566d9c5f14e79dbc6e904$' releases/0.1.0-m2-rc.1/components.env
grep -q '^COPPER_BACKEND_SOURCE_COMMIT=d099cc7760d568bf0ddd3393c341203205b6eef4$' releases/0.1.0-m2-rc.1/components.env
grep -q '^COPPER_WEB_IMAGE=ghcr.io/soyia/copper_web@sha256:098ad42a89de5c3890ab78548c966adcf0b2f4ebe98ef3c569ba3539ab3d153c$' releases/0.1.0-m2-rc.1/components.env
grep -q '^COPPER_BACKEND_IMAGE=ghcr.io/soyia/copper_backend@sha256:06b97662116d74b45604ea7607517e98abb644332658c49f8144d933c11124cc$' releases/0.1.0-m2-rc.1/components.env
grep -q '^COPPER_RELEASE_VERSION=0.1.0-m2-rc.2$' releases/0.1.0-m2-rc.2/components.env
grep -q '^COPPER_RELEASE_STATUS=acceptance-candidate$' releases/0.1.0-m2-rc.2/components.env
grep -q '^COPPER_SUPPORTED_ARCH=amd64$' releases/0.1.0-m2-rc.2/components.env
grep -q '^COPPER_WEB_IMAGE=ghcr.io/soyia/copper_web@sha256:098ad42a89de5c3890ab78548c966adcf0b2f4ebe98ef3c569ba3539ab3d153c$' releases/0.1.0-m2-rc.2/components.env
grep -q '^COPPER_BACKEND_IMAGE=ghcr.io/soyia/copper_backend@sha256:06b97662116d74b45604ea7607517e98abb644332658c49f8144d933c11124cc$' releases/0.1.0-m2-rc.2/components.env

bash -c 'source scripts/lib.sh; validate_image_reference TEST sha256:0000000000000000000000000000000000000000000000000000000000000000 offline'
if bash -c 'source scripts/lib.sh; validate_image_reference TEST sha256:0000000000000000000000000000000000000000000000000000000000000000 registry' >/dev/null 2>&1; then
  echo "registry 模式错误接受了离线镜像 ID" >&2
  exit 1
fi

tests/m3-version-policy.sh
tests/remote-access-lib-contract.sh

bash -n tests/run-m4-fault-recovery-x86-acceptance.sh

echo "static_contract=passed"
