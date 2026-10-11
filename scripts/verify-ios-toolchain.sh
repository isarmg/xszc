#!/usr/bin/env bash
set -euo pipefail

xcode_version="$(xcodebuild -version)"
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
  sdk_version="$(xcrun --sdk "$sdk" --show-sdk-version)"
  printf '%s SDK: %s\n' "$sdk" "$sdk_version"
  if [[ ! "$sdk_version" =~ ^27[.]0([.][0-9]+)?$ ]]; then
    echo "The $sdk SDK must be the stable iOS 27.0 SDK." >&2
    exit 1
  fi
done
