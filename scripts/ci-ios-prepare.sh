#!/usr/bin/env bash
# Overlap independent XcodeGen/Swift preparation with Rust toolchain extraction.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
status="$RUNNER_TEMP/xszc-ios-prepare.status"
log="$RUNNER_TEMP/xszc-ios-prepare.log"
case "${1:-}" in
  start)
    rm -f "$status" "$status.tmp"
    nohup bash "$root/scripts/ci-ios-prepare.sh" worker </dev/null >"$log" 2>&1 &
    ;;
  worker)
    # Report the original failure even when a preparation command exits early.
    trap 'code=$?; echo "iOS preparation finished after ${SECONDS}s: exit $code"; printf "%s\n" "$code" > "$status.tmp"; mv "$status.tmp" "$status"' EXIT
    cd "$root"
    brew install xcodegen
    test "$(xcodegen --version)" = 'Version: 2.46.0'
    echo "Reviewed XcodeGen ready after ${SECONDS}s"
    bash scripts/test-ios-system-directory.sh
    ;;
  wait)
    deadline=$((SECONDS + 900))
    while [[ ! -f "$status" ]]; do
      if ((SECONDS >= deadline)); then
        cat "$log" >&2
        echo 'iOS preparation did not report its result within 15 minutes' >&2
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
