#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/clients/ios/Vendor"
rm -rf "$VENDOR/XszcRust.xcframework"
mkdir -p "$VENDOR/device/headers" "$VENDOR/simulator/headers"

cd "$ROOT"
# Match the Swift application and test deployment target for both native slices.
export IPHONEOS_DEPLOYMENT_TARGET=27.0
cargo build -p xszc-mobile --release --locked --target aarch64-apple-ios
cargo build -p xszc-mobile --release --locked --target aarch64-apple-ios-sim
cp crates/mobile-ffi/include/xszc_ffi_v1.h crates/mobile-ffi/include/module.modulemap "$VENDOR/device/headers/"
cp crates/mobile-ffi/include/xszc_ffi_v1.h crates/mobile-ffi/include/module.modulemap "$VENDOR/simulator/headers/"

xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libxszc_mobile.a \
  -headers "$VENDOR/device/headers" \
  -library target/aarch64-apple-ios-sim/release/libxszc_mobile.a \
  -headers "$VENDOR/simulator/headers" \
  -output "$VENDOR/XszcRust.xcframework"
