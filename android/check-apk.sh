#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
listing=$(unzip -l "$apk")
missing=0
need() {
    if ! grep -q " $1\$" <<< "$listing"; then
        echo "missing from $apk: $1" >&2
        missing=1
    fi
}
for abi in arm64-v8a x86_64; do
    need "lib/$abi/libozen_whisper.so"
    need "lib/$abi/libwhisper.so"
    need "lib/$abi/libggml.so"
    need "lib/$abi/libggml-base.so"
    need "lib/$abi/libc++_shared.so"
done
for variant in armv8.0_1 armv8.2_1 armv8.2_2 armv8.6_1 armv9.0_1 armv9.2_1 armv9.2_2; do
    need "lib/arm64-v8a/libggml-cpu-android_$variant.so"
done
for variant in x64 sse42 sandybridge ivybridge piledriver haswell skylakex cannonlake cascadelake icelake cooperlake zen4 alderlake sapphirerapids; do
    need "lib/x86_64/libggml-cpu-$variant.so"
done
exit $missing
