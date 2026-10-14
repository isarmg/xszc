#!/usr/bin/env bash
set -euo pipefail

# These independent read-only queries can each initialize slow Xcode services.
# Keep every command and identity check, but do not serialize their latency.
probe_dir="$(mktemp -d)"
trap 'rm -rf "$probe_dir"' EXIT
xcodebuild -version > "$probe_dir/xcode" 2> "$probe_dir/xcode.err" &
xcode_pid=$!
xcrun --sdk iphoneos --show-sdk-version > "$probe_dir/iphoneos" 2> "$probe_dir/iphoneos.err" &
device_pid=$!
xcrun --sdk iphonesimulator --show-sdk-version > "$probe_dir/iphonesimulator" 2> "$probe_dir/iphonesimulator.err" &
simulator_pid=$!
status=0
wait "$xcode_pid" || status=$?
wait "$device_pid" || status=$?
wait "$simulator_pid" || status=$?
cat "$probe_dir/xcode.err" "$probe_dir/iphoneos.err" "$probe_dir/iphonesimulator.err" >&2
if ((status != 0)); then
  exit "$status"
fi
xcode_version="$(cat "$probe_dir/xcode")"
printf '%s\n' "$xcode_version"
if ! grep -q -E '^Xcode 27[.]0([.]|$)' <<< "$xcode_version"; then
  echo 'iOS builds require the stable Xcode 27.0 release.' >&2
  exit 1
fi
if ! grep -q -F -x 'Build version 27A266a' <<< "$xcode_version"; then
  echo 'iOS builds require the reviewed stable Xcode 27.0 build 27A266a.' >&2
  exit 1
fi
for sdk in iphoneos iphonesimulator; do
  sdk_version="$(cat "$probe_dir/$sdk")"
  printf '%s SDK: %s\n' "$sdk" "$sdk_version"
  if [[ ! "$sdk_version" =~ ^27[.]0([.][0-9]+)?$ ]]; then
    echo "The $sdk SDK must be the stable iOS 27.0 SDK." >&2
    exit 1
  fi
done
