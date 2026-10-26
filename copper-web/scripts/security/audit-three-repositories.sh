#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage:
  audit-three-repositories.sh WEB_REPO BACKEND_REPO DEPLOY_REPO REPORT_JSON [RELEASE_CANDIDATE_DIR]

All repositories must be full, non-shallow Git clones of the GitHub authority.
The optional release candidate directory is scanned before an asset is published.
The report is chmod 0600 and contains no matching content or original paths.
EOF
}

if [[ "$#" -lt 4 || "$#" -gt 5 ]]; then
  usage >&2
  exit 2
fi

web_repo="$1"
backend_repo="$2"
deploy_repo="$3"
report_json="$4"
release_candidate="${5:-}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/../.." && pwd)"

args=(
  --repo "copper_web=$web_repo"
  --repo "copper_backend=$backend_repo"
  --repo "copper_deploy=$deploy_repo"
  --config "$project_root/config/m6-confidentiality-audit.json"
  --report "$report_json"
  --scope all
)
if [[ -n "$release_candidate" ]]; then
  args+=(--candidate-dir "release_assets=$release_candidate")
fi

exec node "$script_dir/audit-repositories.mjs" "${args[@]}"
