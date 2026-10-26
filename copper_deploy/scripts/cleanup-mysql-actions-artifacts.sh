#!/usr/bin/env bash
set -Eeuo pipefail

# Delete only completed Copper MySQL runtime archives older than the configured
# successful-version limit. Active/queued runs and unknown run states are always
# protected. Small audit evidence and every non-matching artifact are untouched.

repository="${GITHUB_REPOSITORY:-}"
current_run_id="${GITHUB_RUN_ID:-0}"
keep_successful="${COPPER_ARTIFACT_KEEP_SUCCESSFUL:-2}"
dry_run="${COPPER_ARTIFACT_CLEANUP_DRY_RUN:-0}"

[[ "$repository" == "soyia/copper_deploy" ]] || {
  echo "error: artifact cleanup is restricted to soyia/copper_deploy" >&2
  exit 2
}
[[ "$current_run_id" =~ ^[0-9]+$ ]] || { echo "error: invalid GITHUB_RUN_ID" >&2; exit 2; }
[[ "$keep_successful" =~ ^[0-9]+$ && "$keep_successful" -le 2 ]] \
  || { echo "error: COPPER_ARTIFACT_KEEP_SUCCESSFUL must be 0-2" >&2; exit 2; }
[[ "$dry_run" == "0" || "$dry_run" == "1" ]] \
  || { echo "error: COPPER_ARTIFACT_CLEANUP_DRY_RUN must be 0 or 1" >&2; exit 2; }
command -v gh >/dev/null || { echo "error: gh is required" >&2; exit 1; }

records="$(mktemp)"
trap 'rm -f "$records"' EXIT
gh api --paginate "repos/$repository/actions/artifacts?per_page=100" \
  --jq '.artifacts[] | select(.expired == false) | [.created_at, (.id | tostring), .name, (.workflow_run.id | tostring)] | @tsv' \
  | LC_ALL=C sort -r > "$records"

kept=0
deleted=0
protected=0
while IFS=$'\t' read -r created_at artifact_id artifact_name workflow_run_id; do
  [[ "$artifact_name" =~ ^copper-mysql-runtime-amd64-[0-9a-f]{40}$ ]] || continue
  [[ "$artifact_id" =~ ^[0-9]+$ && "$workflow_run_id" =~ ^[0-9]+$ ]] || {
    echo "warning: protecting malformed artifact record: $artifact_name" >&2
    protected=$((protected + 1))
    continue
  }
  if [[ "$workflow_run_id" == "$current_run_id" ]]; then
    echo "protect current run artifact: $artifact_name"
    protected=$((protected + 1))
    continue
  fi

  if ! run_state="$(gh api "repos/$repository/actions/runs/$workflow_run_id" --jq '[.status, (.conclusion // "")] | @tsv')"; then
    echo "warning: cannot verify run $workflow_run_id; protecting $artifact_name" >&2
    protected=$((protected + 1))
    continue
  fi
  IFS=$'\t' read -r run_status run_conclusion <<< "$run_state"
  if [[ "$run_status" != "completed" ]]; then
    echo "protect active run artifact: $artifact_name ($run_status)"
    protected=$((protected + 1))
    continue
  fi
  if [[ "$run_conclusion" == "success" && "$kept" -lt "$keep_successful" ]]; then
    echo "keep successful artifact: $artifact_name ($created_at)"
    kept=$((kept + 1))
    continue
  fi

  if [[ "$dry_run" == "1" ]]; then
    echo "would delete artifact: $artifact_name id=$artifact_id conclusion=$run_conclusion"
  else
    gh api --method DELETE "repos/$repository/actions/artifacts/$artifact_id"
    echo "deleted artifact: $artifact_name id=$artifact_id conclusion=$run_conclusion"
  fi
  deleted=$((deleted + 1))
done < "$records"

echo "artifact_cleanup kept_successful=$kept protected=$protected deleted_or_planned=$deleted dry_run=$dry_run"
