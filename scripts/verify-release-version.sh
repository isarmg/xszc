#!/usr/bin/env bash
set -euo pipefail

release_tag="${1:-${GITHUB_REF_NAME:-}}"

if ! [[ "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Release tag must be a semantic version such as v1.1.0 (received: ${release_tag:-<empty>})." >&2
  exit 1
fi

release_version="${release_tag#v}"
distribution_version="$(tr -d '\r\n' < VERSION)"
cargo_version="$({
  awk '
    /^\[workspace\.package\]$/ { in_workspace_package = 1; next }
    /^\[/ { in_workspace_package = 0 }
    in_workspace_package && /^version[[:space:]]*=/ {
      gsub(/^[^"]*"|".*$/, "")
      print
      exit
    }
  ' Cargo.toml
})"
ios_version="$(sed -n 's/^[[:space:]]*MARKETING_VERSION:[[:space:]]*"\([^"]*\)"/\1/p' clients/ios/project.yml | head -n 1)"
state_version="$(sed -n 's/^pub const MOBILE_APPLICATION_VERSION: &str = "\([^"]*\)";/\1/p' crates/client-core/src/lib.rs)"
abi_revision="$(sed -n 's/^#define XCSC_FFI_ABI_REVISION \([0-9]*\)u$/\1/p' crates/mobile-ffi/include/xszc_ffi_v1.h)"

if [[ -z "$cargo_version" || -z "$ios_version" ]]; then
  echo "Unable to read the Cargo or iOS project version." >&2
  exit 1
fi

if ! [[ "$release_version" == "$distribution_version" && "$release_version" == "$ios_version" && "$release_version" == "$cargo_version" ]]; then
  echo "Release versions do not match:" >&2
  echo "  tag:   $release_version" >&2
  echo "  VERSION: $distribution_version" >&2
  echo "  Rust software: $cargo_version" >&2
  echo "  iOS:   $ios_version" >&2
  exit 1
fi

# Software releases do not rename persistent data or the ABI contract.
if ! [[ "$state_version" == "1.0.0" && "$abi_revision" == "1" ]]; then
  echo "The exact current mobile state/ABI contract changed; review and publish its new current identity explicitly. Nonmatching data must be preserved and rejected." >&2
  exit 1
fi

tag_commit="$(git rev-parse --verify "refs/tags/$release_tag^{commit}")" || {
  echo "Release tag does not exist locally: $release_tag" >&2
  exit 1
}
head_commit="$(git rev-parse --verify HEAD)"
if [[ "$tag_commit" != "$head_commit" ]]; then
  echo "Checked-out source is not the exact commit named by $release_tag." >&2
  exit 1
fi
if [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
  echo "Release checkout contains uncommitted or untracked content." >&2
  exit 1
fi

echo "Release version $release_version and source revision $head_commit are consistent."
