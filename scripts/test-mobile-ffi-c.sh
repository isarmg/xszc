#!/usr/bin/env bash
set -euo pipefail
ulimit -c 0
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
python3 scripts/check-mobile-header.py
CARGO_INCREMENTAL=0 cargo build --locked -p xszc-mobile
target_dir="$(cargo metadata --locked --no-deps --format-version 1 | python3 -c 'import json,sys; print(json.load(sys.stdin)["target_directory"])')"
scratch="$(mktemp -d)"
trap 'rm -rf -- "$scratch"' EXIT
cc -std=c17 -Wall -Wextra -Werror -I crates/mobile-ffi/include \
  crates/mobile-ffi/tests/abi.c -L "$target_dir/debug" -lxszc_mobile \
  -Wl,-rpath,"$target_dir/debug" -o "$scratch/abi"
scratch="$(cd "$scratch" && pwd -P)"
if [[ "$(uname -s)" == Darwin ]]; then
  # Both probes create client-v1.sqlite. Keep the ABI probe fresh so its
  # rejected revision must leave no database behind.
  mkdir -m 700 "$scratch/core-container" "$scratch/ffi-container"
  cat > "$scratch/test.sb" <<EOF
(version 1)
(allow default)
(deny file-read* (literal "$scratch"))
EOF
  cargo build --locked -p xszc-core --example sandbox_database
  sandbox-exec -f "$scratch/test.sb" "$target_dir/debug/examples/sandbox_database" "$scratch/core-container"
  sandbox-exec -f "$scratch/test.sb" "$scratch/abi" "$scratch/ffi-container"
else
  "$scratch/abi" "$scratch"
fi
if [[ "$(uname -s)" == Darwin ]]; then
  symbols="$(nm -gU "$target_dir/debug/libxszc_mobile.dylib")"
else
  symbols="$(nm -D --defined-only "$target_dir/debug/libxszc_mobile.so")"
fi
if grep -E 'xszc_v0_2_r1|xcsc_ffi_.*_v[2-9]|panicProbe' <<< "$symbols"; then
  echo "removed FFI symbols remain" >&2
  exit 1
fi
grep -q 'xcsc_ffi_result_free_v1' <<< "$symbols"
