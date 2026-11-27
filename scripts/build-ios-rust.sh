#!/usr/bin/env bash
set -euo pipefail

slice="${1:-all}"
[[ $# -le 1 ]] || { echo 'Expected at most one slice argument' >&2; exit 2; }
case "$slice" in
  all|simulator|device) ;;
  *) echo 'Expected all, simulator, or device' >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/clients/ios/Vendor"
rm -rf "$VENDOR/AnaRust.xcframework"
mkdir -p "$VENDOR/device/headers" "$VENDOR/simulator/headers"

cd "$ROOT"
# Match the Swift application and test deployment target for both native slices.
export IPHONEOS_DEPLOYMENT_TARGET=27.0
libraries=()
if [[ "$slice" == all || "$slice" == device ]]; then
  cargo build -p ana-mobile --release --locked --target aarch64-apple-ios
  cp crates/mobile-ffi/include/ana_ffi_v1.h crates/mobile-ffi/include/module.modulemap "$VENDOR/device/headers/"
  libraries+=(-library target/aarch64-apple-ios/release/libana_mobile.a -headers "$VENDOR/device/headers/")
fi
if [[ "$slice" == all || "$slice" == simulator ]]; then
  cargo build -p ana-mobile --release --locked --target aarch64-apple-ios-sim
  cp crates/mobile-ffi/include/ana_ffi_v1.h crates/mobile-ffi/include/module.modulemap "$VENDOR/simulator/headers/"
  libraries+=(-library target/aarch64-apple-ios-sim/release/libana_mobile.a -headers "$VENDOR/simulator/headers/")
fi

xcodebuild -create-xcframework "${libraries[@]}" -output "$VENDOR/AnaRust.xcframework"
