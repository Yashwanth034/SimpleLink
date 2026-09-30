#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-emulator-5554}"
CYCLES="${2:-40}"
PACKAGE="com.simplelink.app"
ACTIVITY="com.simplelink.app/.MainActivity"

if ! [[ "$CYCLES" =~ ^[0-9]+$ ]] || (( CYCLES < 1 || CYCLES > 500 )); then
  echo "cycles must be an integer between 1 and 500" >&2
  exit 2
fi

if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
  echo "device unavailable: $SERIAL" >&2
  exit 3
fi

if ! adb -s "$SERIAL" shell pm path "$PACKAGE" >/dev/null 2>&1; then
  echo "SimpleLink is not installed on $SERIAL" >&2
  exit 4
fi

adb -s "$SERIAL" logcat -c
total_ms=0
max_ms=0

for ((i=1; i<=CYCLES; i++)); do
  adb -s "$SERIAL" shell am force-stop "$PACKAGE"

  output="$(
    adb -s "$SERIAL" shell am start -W -n "$ACTIVITY" 2>&1
  )"

  if ! grep -q '^Status: ok' <<<"$output"; then
    echo "launch failed on cycle $i" >&2
    echo "$output" >&2
    exit 5
  fi

  startup_ms="$(
    awk -F': ' '/^TotalTime:/ {print $2; exit}' <<<"$output" | tr -d '\r'
  )"
  startup_ms="${startup_ms:-0}"
  total_ms=$((total_ms + startup_ms))
  if (( startup_ms > max_ms )); then max_ms="$startup_ms"; fi

  if ! adb -s "$SERIAL" shell pidof "$PACKAGE" >/dev/null 2>&1; then
    echo "process missing after launch cycle $i" >&2
    exit 6
  fi

  if (( i % 10 == 0 || i == CYCLES )); then
    echo "ANDROID_SOAK launch $i/$CYCLES"
  fi
done

adb -s "$SERIAL" shell am force-stop "$PACKAGE"

crashes="$(
  adb -s "$SERIAL" logcat -d -v brief 2>/dev/null |
    grep -E "FATAL EXCEPTION|ANR in $PACKAGE|Process: $PACKAGE" || true
)"

if [[ -n "$crashes" ]]; then
  echo "SimpleLink crash/ANR detected:" >&2
  echo "$crashes" >&2
  exit 7
fi

avg_ms=$((total_ms / CYCLES))
echo "PASS android startup soak cycles=$CYCLES avg_start_ms=$avg_ms max_start_ms=$max_ms crashes=0"
