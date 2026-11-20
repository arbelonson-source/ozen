#!/bin/bash
set -u
APP=com.arbelonson.ozen
STALL_MINUTES=${STALL_MINUTES:-20}
POLL_SECONDS=${POLL_SECONDS:-30}
OUT=app/build/instrument.txt
STACKS=app/build/stacks.txt

if [ "$SELECTORS" = all ]; then
  filter="-e package $APP -e notPackage $APP.whisper,$APP.tour"
else
  filter="-e class $SELECTORS"
fi

adb uninstall $APP.test > /dev/null 2>&1
adb uninstall $APP > /dev/null 2>&1
./gradlew -q :app:installDebug :app:installDebugAndroidTest -Pandroid.injected.build.abi=x86_64 || exit $?

for model in "$HOME"/ozen-models/*.bin; do
  [ -f "$model" ] || continue
  adb push "$model" /data/local/tmp/ && adb shell "run-as $APP sh -c 'mkdir -p files && cp /data/local/tmp/$(basename "$model") files/' && rm /data/local/tmp/$(basename "$model")" || exit 1
done
for clip in "$HOME"/ozen-reading/clip.wav "$HOME"/ozen-reading/clip.txt; do
  [ -f "$clip" ] || continue
  adb push "$clip" /data/local/tmp/ && adb shell "run-as $APP sh -c 'mkdir -p files/device-test && cp /data/local/tmp/$(basename "$clip") files/device-test/' && rm /data/local/tmp/$(basename "$clip")" || exit 1
done
{ adb shell "run-as $APP ls -l files" || echo "no files were copied into the app"; }
adb logcat -G 64M || echo "::warning::the log buffer stays at its default size, so logcat.txt keeps only the last minutes"

dump_stacks() {
  pid=$(timeout 30 adb shell pidof $APP | tr -d '\r' | awk '{print $1}')
  if [ -z "$pid" ]; then
    echo "the app process was not found" > "$STACKS"
    return
  fi
  timeout 120 adb shell debuggerd -j "$pid" > "$STACKS" 2>&1
  if [ ! -s "$STACKS" ] || grep -q "not found" "$STACKS"; then
    timeout 30 adb root > /dev/null 2>&1
    timeout 30 adb shell kill -3 "$pid"
    sleep 5
    trace=$(timeout 30 adb shell 'ls -t /data/anr/* 2>/dev/null | head -n 1' | tr -d '\r')
    if [ -n "$trace" ]; then
      timeout 60 adb shell cat "$trace" > "$STACKS" 2>&1
    else
      echo "no thread dump could be taken (debuggerd -j and SIGQUIT both gave nothing)" > "$STACKS"
    fi
  fi
  {
    echo
    echo "=== native stacks (debuggerd) ==="
    timeout 120 adb shell debuggerd "$pid" 2>&1
  } >> "$STACKS"
}

mkdir -p app/build
rm -f "$STACKS"
timeout "${INSTRUMENT_MINUTES}m" adb shell am instrument -w -r $filter $APP.test/$APP.OzenTestRunner > "$OUT" &
run=$!
stalled=0
last=$(wc -c < "$OUT")
idle=0
stall_limit=$((STALL_MINUTES * 60))
while kill -0 "$run" 2> /dev/null; do
  sleep "$POLL_SECONDS"
  now=$(wc -c < "$OUT")
  if [ "$now" != "$last" ]; then
    last=$now
    idle=0
    continue
  fi
  idle=$((idle + POLL_SECONDS))
  if [ "$idle" -ge "$stall_limit" ] && kill -0 "$run" 2> /dev/null; then
    stalled=1
    dump_stacks
    echo "::error::no test output for $STALL_MINUTES minutes; thread stacks in stacks.txt"
    kill "$run" 2> /dev/null
    break
  fi
done
wait "$run"
status=$?
[ $status = 124 ] && echo "::error::the tests ran past $INSTRUMENT_MINUTES minutes and were stopped"
[ $stalled = 1 ] && [ $status = 0 ] && status=1
adb logcat -d > app/build/logcat.txt
exit $status
