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
need "assets/silero-v6.2.0-ggml.bin"
for variant in armv8.0_1 armv8.2_1 armv8.2_2 armv8.6_1 armv9.0_1 armv9.2_1 armv9.2_2; do
    need "lib/arm64-v8a/libggml-cpu-android_$variant.so"
done
for variant in x64 sse42 sandybridge ivybridge piledriver haswell skylakex cannonlake cascadelake icelake cooperlake zen4 alderlake sapphirerapids; do
    need "lib/x86_64/libggml-cpu-$variant.so"
done
if [[ $apk == *release* ]]; then
    dex=$(awk '/ classes[0-9]*\.dex$/ { total += $1 } END { print total + 0 }' <<< "$listing")
    if (( dex > 12 * 1024 * 1024 )); then
        echo "$apk carries $(( dex / 1024 / 1024 )) MB of code: the release build is not shrunk" >&2
        missing=1
    fi
    if (( $(unzip -p "$apk" 'classes*.dex' | grep -ac 'Lcom/arbelonson/ozen/whisper/WhisperCpp;') == 0 )); then
        echo "$apk lost the WhisperCpp class name its native code is bound to" >&2
        missing=1
    fi
fi
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}}
aapt2=$(ls "$sdk"/build-tools/*/aapt2 | sort -V | tail -1)
permissions=$("$aapt2" dump permissions "$apk")
for permission in INTERNET RECORD_AUDIO FOREGROUND_SERVICE_MICROPHONE POST_NOTIFICATIONS; do
    if ! grep -q "name='android.permission.$permission'" <<< "$permissions"; then
        echo "$apk does not ask for android.permission.$permission" >&2
        missing=1
    fi
done
exit $missing
