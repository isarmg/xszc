#!/usr/bin/env bash
set -euo pipefail

[[ $# == 1 ]] || { echo 'Expected simulator or device lane' >&2; exit 2; }
case "$1" in
  simulator) platform_target=aarch64-apple-ios-sim ;;
  device) platform_target=aarch64-apple-ios ;;
  *) echo 'Expected simulator or device lane' >&2; exit 2 ;;
esac
# This override also prevents the repository's developer components from being
# installed implicitly by unqualified cargo commands in these build-only lanes.
[[ "${RUSTUP_TOOLCHAIN:-}" == 1.99.0 ]] || {
  echo 'The iOS lane must explicitly select RUSTUP_TOOLCHAIN=1.99.0' >&2
  exit 1
}

# Check the running compiler even after an exact cache hit; never trust its key.
identity="$(rustc --version --verbose)"
printf '%s\n' "$identity"
for expected in \
  'release: 1.99.0' \
  'commit-hash: b940084d7eb6a299eb4bfeb8e34901bc051e7ac4' \
  'host: aarch64-apple-darwin'; do
  grep -Fqx "$expected" <<< "$identity" || {
    echo "The iOS Rust compiler differs from the reviewed identity: $expected" >&2
    exit 1
  }
done
targets="$(rustup target list --installed --toolchain 1.99.0)"
for target in aarch64-apple-darwin "$platform_target"; do
  grep -Fqx "$target" <<< "$targets" || {
    echo "The iOS Rust toolchain is missing $target" >&2
    exit 1
  }
done
