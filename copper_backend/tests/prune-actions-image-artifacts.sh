#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temp_root="$(mktemp -d)"
trap 'rm -rf "$temp_root"' EXIT

mkdir -p "$temp_root/bin"
cat > "$temp_root/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$MOCK_CALLS"
case "$*" in
  *"actions/workflows/publish-m1-image.yml/runs"*"status=success"*)
    printf '%s\n' 900 800 700
    ;;
  *"actions/workflows/publish-m1-image.yml/runs"*)
    printf '%s\n' 1000 1100
    ;;
  *"--method GET --paginate"*"actions/artifacts"*)
    printf '1\tcopper-backend-platform-amd64-current\t1000\t100\n'
    printf '2\tcopper-backend-multiarch-previous\t900\t200\n'
    printf '3\tcopper-backend-platform-arm64-old\t800\t300\n'
    printf '4\tcopper-backend-multiarch-old\t700\t400\n'
    printf '5\tcopper-backend-platform-amd64-active\t1100\t500\n'
    printf '6\tcopper-backend-confidentiality-audit-old\t700\t600\n'
    printf '7\tcopper-backend-platform-amd64-unknown\t0\t700\n'
    printf '8\tcopper-web-platform-amd64-old\t700\t800\n'
    ;;
  *"--method DELETE"*)
    ;;
  *)
    echo "unexpected gh invocation: $*" >&2
    exit 1
    ;;
esac
MOCK
chmod +x "$temp_root/bin/gh"

export MOCK_CALLS="$temp_root/calls.log"
export PATH="$temp_root/bin:$PATH"
export GITHUB_REPOSITORY="soyia/copper_backend"
export GITHUB_RUN_ID="1000"
export COPPER_IMAGE_ARTIFACT_PREFIX="copper-backend-"
export COPPER_IMAGE_WORKFLOW_FILE="publish-m1-image.yml"
export COPPER_KEEP_SUCCESSFUL_VERSIONS="2"
export COPPER_CURRENT_BUILD_SUCCESSFUL="true"
export COPPER_INCLUDE_CURRENT_RUN="true"

output="$(bash "$repo_root/scripts/ci/prune-actions-image-artifacts.sh")"
grep -Fq 'actions/artifacts/3' "$MOCK_CALLS"
grep -Fq 'actions/artifacts/4' "$MOCK_CALLS"
if grep -Eq 'actions/artifacts/(1|2|5|6|7|8)([^0-9]|$)' "$MOCK_CALLS"; then
  echo "cleanup deleted a protected or out-of-scope artifact" >&2
  exit 1
fi
grep -Fq '删除 2 项，共 700 字节' <<<"$output"

: > "$MOCK_CALLS"
export COPPER_CURRENT_BUILD_SUCCESSFUL="false"
output="$(bash "$repo_root/scripts/ci/prune-actions-image-artifacts.sh")"
grep -Fq 'actions/artifacts/1' "$MOCK_CALLS"
grep -Fq 'actions/artifacts/4' "$MOCK_CALLS"
if grep -Eq 'actions/artifacts/(2|3|5|6|7|8)([^0-9]|$)' "$MOCK_CALLS"; then
  echo "failed-run cleanup deleted a previous success, active run, or out-of-scope artifact" >&2
  exit 1
fi
grep -Fq '删除 2 项，共 500 字节' <<<"$output"

: > "$MOCK_CALLS"
export GITHUB_RUN_ID="2000"
export COPPER_CURRENT_BUILD_SUCCESSFUL="false"
export COPPER_INCLUDE_CURRENT_RUN="false"
output="$(bash "$repo_root/scripts/ci/prune-actions-image-artifacts.sh")"
grep -Fq 'actions/artifacts/4' "$MOCK_CALLS"
if grep -Eq 'actions/artifacts/(1|2|3|5|6|7|8)([^0-9]|$)' "$MOCK_CALLS"; then
  echo "scheduled cleanup deleted a retained success, active run, or out-of-scope artifact" >&2
  exit 1
fi
grep -Fq '删除 1 项，共 400 字节' <<<"$output"

echo "Actions image artifact retention test passed"
