#!/usr/bin/env bash
set -euo pipefail
src=$1
dst=$2
nbits=${3:-8}
mode=${4:-uniform}
here=$(cd "$(dirname "$0")" && pwd)
python=${PYTHON:-python3}
rm -rf "$dst" "$dst-assets"
mkdir -p "$dst"
cp -r "$src/MelSpectrogram.mlpackage" "$dst/"
for part in TextDecoder AudioEncoder; do
  "$python" "$here/quantize.py" "$src/$part.mlpackage" "$dst/$part.mlpackage" "$nbits" "$mode"
done
cp "$src/config.json" "$src/generation_config.json" "$dst/"
python3 "$here/manifest.py" pack "$dst" "$dst-assets"
du -sh "$dst" "$dst-assets"
