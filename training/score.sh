#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source env.sh
BASE=$(ls -d "$HF_HUB_CACHE"/models--ivrit-ai--whisper-large-v3-turbo/snapshots/* | head -1)
[ -f "$1/preprocessor_config.json" ] || cp "$BASE/preprocessor_config.json" "$1/"
venv/bin/ct2-transformers-converter --model "$1" --output_dir "models/$2-ct2" --quantization float16 \
  --copy_files tokenizer.json preprocessor_config.json --force > /dev/null 2>&1
HF_HUB_OFFLINE=1 venv/bin/python exam.py "models/$2-ct2" "$2" 2>&1 | grep -v Warning
