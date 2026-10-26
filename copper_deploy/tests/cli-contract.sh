#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

help_output="$(./copperctl --help)"
grep -q 'admin init' <<< "$help_output"
grep -q 'backup create' <<< "$help_output"
grep -q 'stop.*保留全部客户数据' <<< "$help_output"
grep -q 'backup status' <<< "$help_output"
grep -q 'backup key' <<< "$help_output"
grep -q 'backup verify' <<< "$help_output"
grep -q 'backup prune' <<< "$help_output"
grep -q 'backup schedule' <<< "$help_output"
grep -q 'backup restore stage' <<< "$help_output"
grep -q 'backup restore switch' <<< "$help_output"
grep -q 'backup restore rollback' <<< "$help_output"
grep -q 'release validate' <<< "$help_output"
grep -q 'release stage online' <<< "$help_output"
grep -q 'release stage offline' <<< "$help_output"
grep -q 'release status' <<< "$help_output"
grep -q 'upgrade preflight' <<< "$help_output"
grep -q 'upgrade prepare' <<< "$help_output"
grep -q 'upgrade status' <<< "$help_output"
grep -q 'tls <命令>' <<< "$help_output"
grep -q 'security secrets audit' <<< "$help_output"
grep -q 'security secrets rotate-database' <<< "$help_output"
grep -q 'security release verify' <<< "$help_output"
grep -q 'security egress status' <<< "$help_output"
grep -q 'monitor check' <<< "$help_output"
grep -q 'monitor status' <<< "$help_output"
grep -q 'monitor history' <<< "$help_output"
grep -q 'monitor schedule' <<< "$help_output"
grep -q 'operations status' <<< "$help_output"
grep -q 'operations checklist' <<< "$help_output"
grep -q 'lan preflight' <<< "$help_output"
grep -q 'lan status' <<< "$help_output"
grep -q 'lan certificate' <<< "$help_output"
grep -q 'lan firewall' <<< "$help_output"
grep -q 'remote-access cloudflare configure' <<< "$help_output"
grep -q 'remote-access cloudflare status' <<< "$help_output"
grep -q 'remote-access cloudflare rotate-token' <<< "$help_output"
grep -q 'remote-access cloudflare remove' <<< "$help_output"
grep -q 'storage enroll' <<< "$help_output"
grep -q '退出码' <<< "$help_output"

[[ "$(./copperctl version)" == "copperctl 0.12.0-m5" ]]

installer_help="$(./copper-install --help)"
grep -q '一站式安装' <<< "$installer_help"
grep -q 'continue' <<< "$installer_help"
grep -q 'repair' <<< "$installer_help"
grep -q 'change-domain' <<< "$installer_help"
grep -q 'status' <<< "$installer_help"

set +e
./copperctl unknown >/tmp/copperctl-cli-out 2>/tmp/copperctl-cli-err
unknown_status=$?
./copperctl logs unknown >/tmp/copperctl-cli-out 2>/tmp/copperctl-cli-err
logs_status=$?
./copperctl logs web backend >/tmp/copperctl-cli-out 2>/tmp/copperctl-cli-err
multiple_logs_status=$?
set -e

[[ "$unknown_status" -eq 2 ]]
[[ "$logs_status" -eq 2 ]]
[[ "$multiple_logs_status" -eq 2 ]]
rm -f /tmp/copperctl-cli-out /tmp/copperctl-cli-err

echo "cli_contract=passed"
