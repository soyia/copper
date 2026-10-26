#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
ocr_root="${COPPER_OCR_HOME:-$repo_root/data/local/ocr}"
python_bin="${COPPER_OCR_PYTHON:-python3}"
venv_dir="$ocr_root/venv"
export PADDLE_PDX_CACHE_HOME="${PADDLE_PDX_CACHE_HOME:-$ocr_root/cache}"

mkdir -p "$ocr_root" "$PADDLE_PDX_CACHE_HOME"

if [[ ! -x "$venv_dir/bin/python" ]]; then
  "$python_bin" -m venv "$venv_dir"
fi

"$venv_dir/bin/python" -m pip install \
  paddlepaddle==3.3.1
"$venv_dir/bin/python" -m pip install \
  paddleocr==3.7.0 \
  paddlex==3.7.2
"$venv_dir/bin/paddlex" --install serving

"$venv_dir/bin/python" -c \
  "import paddle, paddleocr, paddlex; print('PaddlePaddle', paddle.__version__); print('PaddleOCR', paddleocr.__version__); print('PaddleX', paddlex.__version__)"
