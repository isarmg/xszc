#!/usr/bin/env bash
# Overlap toolchain preparation and simulator fixtures with native builds.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
kind="${2:-tools}"
case "$kind" in
  tools) name=prepare; budget=900 ;;
  media) name=media; budget=1080 ;;
  *) echo 'Expected tools or media' >&2; exit 2 ;;
esac
status="$RUNNER_TEMP/xszc-ios-$name.status"
log="$RUNNER_TEMP/xszc-ios-$name.log"
case "${1:-}" in
  start)
    rm -f "$status" "$status.tmp"
    nohup bash "$root/scripts/ci-ios-prepare.sh" worker "$kind" </dev/null >"$log" 2>&1 &
    ;;
  worker)
    # Report the original failure even when a preparation command exits early.
    trap 'code=$?; echo "iOS preparation finished after ${SECONDS}s: exit $code"; printf "%s\n" "$code" > "$status.tmp"; mv "$status.tmp" "$status"' EXIT
    printf '%s iOS %s worker entered\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$kind"
    cd "$root"
    if [[ "$kind" == media ]]; then
      # One 18-minute budget covers joining boot and importing fixtures.
      # Stop the entire process group on timeout, including any pending boot join.
      python3 - <<'PYTHON'
import os, signal, subprocess, sys
command = r'''
date -u '+%Y-%m-%dT%H:%M:%SZ iOS simulator wait started'
python3 scripts/ci-ios-simulator.py wait
code=$?
printf '%s iOS simulator wait finished: exit %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$code"
if [ "$code" -ne 0 ]; then exit "$code"; fi
bash scripts/seed-ios-ui.sh "$XSZC_TEST_SIMULATOR" media
'''
worker = subprocess.Popen(['bash', '-c', command], start_new_session=True)
try:
    code = worker.wait(timeout=1080)
except subprocess.TimeoutExpired:
    os.killpg(worker.pid, signal.SIGKILL)
    worker.wait()
    print('iOS media preparation exceeded 18 minutes', flush=True)
    code = 124
sys.exit(code)
PYTHON
    else
      date -u '+%Y-%m-%dT%H:%M:%SZ iOS brew started'
      brew install xcodegen
      date -u '+%Y-%m-%dT%H:%M:%SZ iOS brew finished'
      test "$(xcodegen --version)" = 'Version: 2.46.0'
      echo "Reviewed XcodeGen ready after ${SECONDS}s"
      date -u '+%Y-%m-%dT%H:%M:%SZ iOS Swift sandbox started'
      bash scripts/test-ios-system-directory.sh
      date -u '+%Y-%m-%dT%H:%M:%SZ iOS Swift sandbox finished'
    fi
    ;;
  wait)
    deadline=$((SECONDS + budget))
    while [[ ! -f "$status" ]]; do
      if ((SECONDS >= deadline)); then
        cat "$log" >&2
        echo "iOS $kind preparation did not report its result within ${budget}s" >&2
        exit 124
      fi
      sleep 1
    done
    cat "$log"
    code="$(cat "$status")"
    [[ "$code" =~ ^[0-9]+$ ]] && ((code <= 255)) || {
      echo 'Invalid iOS preparation result' >&2
      exit 1
    }
    exit "$code"
    ;;
  *) echo 'Expected start, worker, or wait' >&2; exit 2 ;;
esac
