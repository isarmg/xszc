#!/usr/bin/env bash
set -euo pipefail

# A fresh, dedicated CI AVD; never targets a connected physical device.
sdk_root="${ANDROID_HOME:?Android SDK required}"
avd_name="xszc-api37-${GITHUB_RUN_ID:?CI run ID required}-${GITHUB_RUN_ATTEMPT:-1}"
# New SDK tools and emulator releases can choose different default AVD homes.
# Pin both to this run's isolated directory; do not repurpose the user's HOME.
avd_storage="$(mktemp -d "${RUNNER_TEMP:?}/xszc-avd.XXXXXX")"
export ANDROID_USER_HOME="$avd_storage"
export ANDROID_AVD_HOME="$avd_storage/avd"
mkdir -p "$ANDROID_AVD_HOME"
serial=emulator-5554
emulator_pid=""
cleanup() {
  if [[ -n "$emulator_pid" ]]; then
    # QEMU can acknowledge emu kill without exiting on hosted runners.
    # Bound both the adb request and process shutdown before reaping the child.
    timeout --kill-after=2s 10s "$sdk_root/platform-tools/adb" -s "$serial" emu kill || true
    kill "$emulator_pid" 2>/dev/null || true
    for ((attempt=0; attempt<10; attempt++)); do
      kill -0 "$emulator_pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$emulator_pid" 2>/dev/null; then
      echo 'Emulator did not exit after SIGTERM; stopping this CI child with SIGKILL.'
      kill -KILL "$emulator_pid" 2>/dev/null || true
    fi
    wait "$emulator_pid" 2>/dev/null || true
  fi
  timeout --kill-after=2s 15s avdmanager delete avd -n "$avd_name" || true
}
trap cleanup EXIT
test -r /dev/kvm && test -w /dev/kvm
printf 'no\n' | avdmanager create avd -n "$avd_name" -k 'system-images;android-37.0;google_apis;x86_64'
test -f "$ANDROID_AVD_HOME/$avd_name.ini"
"$sdk_root/emulator/emulator" -list-avds | grep -Fx "$avd_name"
"$sdk_root/emulator/emulator" -avd "$avd_name" -port 5554 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"${RUNNER_TEMP:?}/xszc-emulator.log" 2>&1 &
emulator_pid=$!
booted=false
boot_deadline=$((SECONDS + 360))
while ((SECONDS < boot_deadline)); do
  kill -0 "$emulator_pid" 2>/dev/null || { tail -100 "$RUNNER_TEMP/xszc-emulator.log"; exit 1; }
  if [[ "$(timeout --kill-after=2s 5s "$sdk_root/platform-tools/adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    booted=true
    break
  fi
  sleep 3
done
if [[ "$booted" != true ]]; then
  tail -100 "$RUNNER_TEMP/xszc-emulator.log"
  echo 'Android emulator did not finish booting within six minutes.' >&2
  exit 1
fi
test "$(timeout --kill-after=2s 10s "$sdk_root/platform-tools/adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" = 37
ANDROID_SERIAL="$serial" timeout --kill-after=30s 10m gradle -p clients/android -PxszcEmulatorTests=true connectedDebugAndroidTest
