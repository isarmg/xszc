#!/usr/bin/env bash
set -euo pipefail

# Check the running compiler even after an exact cache hit; never trust its key.
identity="$(rustc +1.99.0 --version --verbose)"
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
for target in aarch64-apple-ios aarch64-apple-ios-sim; do
  grep -Fqx "$target" <<< "$targets" || {
    echo "The iOS Rust toolchain is missing $target" >&2
    exit 1
  }
done
components="$(rustup component list --installed --toolchain 1.99.0)"
for component in clippy-aarch64-apple-darwin rustfmt-aarch64-apple-darwin; do
  grep -Fqx "$component" <<< "$components" || {
    echo "The iOS Rust toolchain is missing $component" >&2
    exit 1
  }
done
