#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
ocr_root="${COPPER_OCR_HOME:-$repo_root/data/local/ocr}"
venv_dir="$ocr_root/venv"
ocr_host="${COPPER_OCR_HOST:-127.0.0.1}"
ocr_port="${COPPER_OCR_PORT:-8080}"
export PADDLE_PDX_CACHE_HOME="${PADDLE_PDX_CACHE_HOME:-$ocr_root/cache}"
export PADDLE_PDX_MODEL_SOURCE="${PADDLE_PDX_MODEL_SOURCE:-BOS}"

if [[ ! -x "$venv_dir/bin/paddlex" ]]; then
  echo "PaddleOCR is not installed. Run scripts/ocr/install-local-paddleocr.sh first." >&2
  exit 1
fi

mkdir -p "$PADDLE_PDX_CACHE_HOME"

exec "$venv_dir/bin/paddlex" \
  --serve \
  --pipeline OCR \
  --device cpu \
  --host "$ocr_host" \
  --port "$ocr_port"
