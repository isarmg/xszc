#!/usr/bin/env bash
set -euo pipefail
ulimit -c 0
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
command -v javac >/dev/null
command -v java >/dev/null
CARGO_INCREMENTAL=0 cargo build --locked -p ana-mobile --features jni-host-tests
target_dir="$(cargo metadata --locked --no-deps --format-version 1 | python3 -c 'import json,sys; print(json.load(sys.stdin)["target_directory"])')"
scratch="$(mktemp -d)"
trap 'rm -rf -- "$scratch"' EXIT
javac -encoding UTF-8 -d "$scratch" crates/mobile-ffi/tests/jni/NativeBridgeV1.java
TMPDIR="$scratch/unavailable-system-temp" java -Djava.library.path="$target_dir/debug" -cp "$scratch" \
  org.sarmg.ana.NativeBridgeV1 "$scratch" 2>&1 | tee "$scratch/jni.log"
# Python is already required above. A missing optional search utility must not
# silently skip this privacy assertion; log read errors must also fail the step.
python3 - "$scratch/jni.log" <<'PY'
from pathlib import Path
import sys

if b"private-secret" in Path(sys.argv[1]).read_bytes():
    raise SystemExit("JNI leaked a private input or panic payload")
PY
