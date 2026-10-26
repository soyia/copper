#!/usr/bin/env bash
set -Eeuo pipefail

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID is required}"
: "${COPPER_IMAGE_ARTIFACT_PREFIX:?COPPER_IMAGE_ARTIFACT_PREFIX is required}"
: "${COPPER_IMAGE_WORKFLOW_FILE:?COPPER_IMAGE_WORKFLOW_FILE is required}"

keep_successful_versions="${COPPER_KEEP_SUCCESSFUL_VERSIONS:-2}"
if [[ ! "$keep_successful_versions" =~ ^[1-9][0-9]*$ ]]; then
  echo "COPPER_KEEP_SUCCESSFUL_VERSIONS must be a positive integer" >&2
  exit 2
fi
current_build_successful="${COPPER_CURRENT_BUILD_SUCCESSFUL:-true}"
if [[ "$current_build_successful" != "true" && "$current_build_successful" != "false" ]]; then
  echo "COPPER_CURRENT_BUILD_SUCCESSFUL must be true or false" >&2
  exit 2
fi
include_current_run="${COPPER_INCLUDE_CURRENT_RUN:-true}"
if [[ "$include_current_run" != "true" && "$include_current_run" != "false" ]]; then
  echo "COPPER_INCLUDE_CURRENT_RUN must be true or false" >&2
  exit 2
fi

contains_run_id() {
  local wanted="$1"
  shift
  local existing
  for existing in "$@"; do
    [[ "$existing" == "$wanted" ]] && return 0
  done
  return 1
}

# The cleanup job runs before GitHub marks the current workflow completed. Keep
# the current run only when its manifest completed; failed runs must lose every
# partial image archive they uploaded before failing.
keep_run_ids=()
if [[ "$include_current_run" == "true" && "$current_build_successful" == "true" ]]; then
  keep_run_ids+=("$GITHUB_RUN_ID")
fi
while IFS= read -r run_id; do
  [[ -n "$run_id" ]] || continue
  contains_run_id "$run_id" "${keep_run_ids[@]:-}" && continue
  keep_run_ids+=("$run_id")
  [[ "${#keep_run_ids[@]}" -ge "$keep_successful_versions" ]] && break
done < <(
  gh api --method GET \
    "repos/$GITHUB_REPOSITORY/actions/workflows/$COPPER_IMAGE_WORKFLOW_FILE/runs" \
    -f status=success \
    -f per_page=100 \
    --jq '.workflow_runs[].id'
)

# Never delete artifacts belonging to a queued or running workflow. This makes
# concurrent manual release runs safe even though they use the same prefixes.
while IFS= read -r run_id; do
  [[ -n "$run_id" ]] || continue
  if [[ "$include_current_run" == "true" && "$run_id" == "$GITHUB_RUN_ID" && "$current_build_successful" == "false" ]]; then
    continue
  fi
  contains_run_id "$run_id" "${keep_run_ids[@]:-}" || keep_run_ids+=("$run_id")
done < <(
  gh api --method GET \
    "repos/$GITHUB_REPOSITORY/actions/workflows/$COPPER_IMAGE_WORKFLOW_FILE/runs" \
    -f per_page=100 \
    --jq '.workflow_runs[] | select(.status != "completed") | .id'
)

deleted_count=0
deleted_bytes=0
while IFS=$'\t' read -r artifact_id artifact_name run_id artifact_bytes; do
  [[ -n "$artifact_id" ]] || continue
  case "$artifact_name" in
    "${COPPER_IMAGE_ARTIFACT_PREFIX}platform-"*|"${COPPER_IMAGE_ARTIFACT_PREFIX}multiarch-"*) ;;
    *) continue ;;
  esac

  if [[ -z "$run_id" || "$run_id" == "0" ]]; then
    echo "保留无法确认来源运行的镜像产物：$artifact_name ($artifact_id)"
    continue
  fi
  contains_run_id "$run_id" "${keep_run_ids[@]:-}" && continue

  gh api --method DELETE "repos/$GITHUB_REPOSITORY/actions/artifacts/$artifact_id"
  deleted_count=$((deleted_count + 1))
  deleted_bytes=$((deleted_bytes + artifact_bytes))
  echo "已删除旧镜像产物：$artifact_name ($artifact_id)"
done < <(
  gh api --method GET --paginate \
    "repos/$GITHUB_REPOSITORY/actions/artifacts" \
    -f per_page=100 \
    --jq '.artifacts[] | [.id, .name, (.workflow_run.id // 0), .size_in_bytes] | @tsv'
)

echo "镜像产物清理完成：保留最近 $keep_successful_versions 个成功版本，失败运行不留大包，并保护其他进行中的运行；删除 $deleted_count 项，共 $deleted_bytes 字节。"
