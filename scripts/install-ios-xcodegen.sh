#!/usr/bin/env bash
set -euo pipefail

version=2.46.0
digest=4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806
destination="${RUNNER_TEMP:?RUNNER_TEMP is required}/ana-xcodegen"
scratch="$(mktemp -d "$RUNNER_TEMP/ana-xcodegen-download.XXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT

archive="$scratch/xcodegen.zip"
curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
  --retry 2 --max-time 90 --output "$archive" \
  "https://github.com/yonaskolb/XcodeGen/releases/download/$version/xcodegen.zip"
actual="$(shasum -a 256 "$archive")"
if [[ "${actual%% *}" != "$digest" ]]; then
  echo "XcodeGen $version archive checksum mismatch" >&2
  exit 1
fi

unzip -q "$archive" -d "$scratch"
bundle="$scratch/xcodegen"
test -x "$bundle/bin/xcodegen"
test -d "$bundle/share/xcodegen/SettingPresets"
reported_version="$("$bundle/bin/xcodegen" --version)"
if [[ "$reported_version" != "Version: $version" ]]; then
  printf 'Expected XcodeGen %s, got %s\n' "$version" "$reported_version" >&2
  exit 1
fi

if [[ -e "$destination" || -L "$destination" ]]; then
  echo 'XcodeGen install destination is already in use' >&2
  exit 1
fi
mv "$bundle" "$destination"
