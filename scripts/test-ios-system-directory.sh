#!/usr/bin/env bash
set -euo pipefail
scratch="$(mktemp -d /private/var/tmp/ana-path.XXXXXX)"
trap 'rm -rf -- "$scratch"' EXIT
mkdir -m 700 "$scratch/container"
swiftc -o "$scratch/directory-test" clients/ios/Ana/BackupDirectory.swift clients/ios/Tests/BackupDirectoryMain.swift
cat > "$scratch/test.sb" <<PROFILE
(version 1)
(allow default)
(deny file-read* (literal "$scratch"))
PROFILE
sandbox-exec -f "$scratch/test.sb" "$scratch/directory-test" "$scratch/container"
