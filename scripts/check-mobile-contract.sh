#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail() {
    echo "mobile ABI v1 contract gate: $*" >&2
    exit 1
}

test ! -e crates/mobile-ffi/include/xszc.h \
    || fail "the unversioned C header still exists"
test -f crates/mobile-ffi/include/xszc_ffi_v1.h \
    || fail "the current ABI 1 C header is missing"
test ! -e crates/mobile-ffi/include/xszc_v0_2_r1.h \
    || fail "the removed NUL-string C ABI header remains"
if rg -n 'xszc_v0_2_r1|NativeBridgeV02|withCString|@_silgen_name' crates/mobile-ffi/src clients/ios/Xszc/RustClient.swift clients/android/app/src/main; then
    fail "a removed mobile ABI implementation or caller remains"
fi
python3 scripts/check-mobile-header.py
for required_ios_property in \
    'NSPhotoLibraryUsageDescription:' \
    'NSPhotoLibraryAddUsageDescription:' \
    'BGTaskSchedulerPermittedIdentifiers:'; do
    grep -q -F "$required_ios_property" clients/ios/project.yml \
        || fail "the generated iOS Info.plist contract is missing: $required_ios_property"
done

if grep -R -I -n -E 'Java_org_sarmg_xszc_NativeBridge_|native(Open|Close|Needs|Enqueue|Next|MarkUpload|MarkPart|MarkComplete|MarkFailed|Stats)([^[:alnum:]_]|$)' \
    crates/mobile-ffi/src clients/ios/Xszc clients/android/app/src/main; then
    fail "a non-current unversioned native ABI entry point remains"
fi

if grep -R -I -n -E 'com[.]example|Java_com_example_' \
    crates/mobile-ffi/src clients/android/app/src clients/android/app/build.gradle.kts clients/ios; then
    fail "a development placeholder application identity remains"
fi

for required in \
    'xszc-mobile-v1' \
    'client-v1.sqlite' \
    'backup-staging-v1' \
    'xszc_open_v1' \
    'Java_org_sarmg_xszc_NativeBridgeV1_open' \
    'org.sarmg.xszc'; do
    # All platform clients live below clients/; keeping this gate on the
    # canonical paths makes directory drift fail visibly in CI.
    grep -R -I -q -F "$required" crates/mobile-ffi crates/client-core clients/android clients/ios \
        || fail "required mobile ABI v1 contract marker is missing: $required"
done

grep -q -F 'XSZC_ANDROID_SIGNING_PKCS12_BASE64' .github/workflows/release.yml \
    || fail "the formal Android release does not require the current PKCS#12 Secret"
grep -q -F '0cfc2811d48cdeab3e6d857029d879e001ab9531c06784b4d48d15a847771421' \
    .github/workflows/release.yml \
    || fail "the formal Android release does not pin the current certificate fingerprint"
grep -q -F 'assembleRelease' .github/workflows/release.yml \
    || fail "the formal Android release is not a signed release APK build"
python3 scripts/check-signing-secret-references.py .github/workflows/release.yml
if grep -q -E 'assembleDebug|app-debug[.]apk' .github/workflows/release.yml; then
    fail "the formal release workflow still contains a debug Android signing path"
fi

for workflow in .github/workflows/build.yml .github/workflows/release.yml; do
    grep -q -F 'bash scripts/verify-ios-toolchain.sh' "$workflow" \
        || fail "$workflow must verify stable Xcode 27 and iOS 27 SDKs"
    grep -q -F 'python3 scripts/package-ios-ipa.py' "$workflow" \
        || fail "$workflow must package the device app as an IPA"
    grep -q -F 'path: dist/*.ipa' "$workflow" \
        || fail "$workflow must upload the IPA artifact"
    grep -q -F 'runs-on: xcode-27' "$workflow" \
        || fail "$workflow must use the reviewed Xcode 27 image"
    grep -q -F 'DEVELOPER_DIR: /Applications/Xcode_27.app/Contents/Developer' "$workflow" \
        || fail "$workflow must select the stable Xcode 27 application"
    grep -q -F 'sdkmanager "platforms;android-37.0" "build-tools;37.0.0"' "$workflow" \
        || fail "$workflow must install the stable Android 37 SDK and build tools"
done
for android_setting in 'compileSdk = 37' 'minSdk = 37' 'targetSdk = 37' 'buildToolsVersion = "37.0.0"'; do
    grep -q -F "$android_setting" clients/android/app/build.gradle.kts \
        || fail "the Android application must use the current stable API: $android_setting"
done
grep -q -F 'iOS: "27.0"' clients/ios/project.yml \
    || fail "the iOS application and tests must require iOS 27.0"
grep -q -F 'export IPHONEOS_DEPLOYMENT_TARGET=27.0' scripts/build-ios-rust.sh \
    || fail "the Rust iOS slices must require iOS 27.0"
if grep -q -F 'unsigned.tar.gz' .github/workflows/release.yml; then
    fail "iOS releases must publish IPA files rather than app tarballs"
fi

# Source contract only: pair with the Rust retained-client queue regression.
# This does not execute BGTask/URLSession cancellation timing on an iOS device.
python3 - <<'PYIOS'
from pathlib import Path

source = Path("clients/ios/Xszc/BackupCoordinator.swift").read_text()
start = source.index("catch {", source.index("do { try await upload.submit"))
cleanup = source.index('try store.client.transfer(["op": "fail_upload"', start)
end = source.index("processed += 1", cleanup)
before_cleanup = source[start:cleanup]
after_cleanup = source[cleanup:end]
assert "throw error" not in before_cleanup, "Upload cancellation must record retry before propagating"
assert "throw error" in after_cleanup, "Upload cancellation must still propagate after queue cleanup"
assert "case .superseded = uploadError" in before_cleanup, "Superseded handling must remain distinct"
print("iOS upload cancellation source contract passed (native lifecycle not executed)")
PYIOS

# Source-backed preview fallback checks; native UI execution is a separate gate.
python3 scripts/test-cloud-preview-fallback.py

# Filter metadata follows each first-page refresh; native UI coverage is in CloudGalleryInstrumentedTest.
python3 - <<'PYMETADATA'
from pathlib import Path
source = Path("clients/android/app/src/main/java/org/sarmg/xszc/CloudGalleryScreen.kt").read_text()
page = source.split("suspend fun loadPage(reset: Boolean)", 1)[1].split("    fun load(reset:", 1)[0]
metadata = page.split("if (reset) scope.launch {", 1)[1]
assert page.index("assets = (assets + page.items)") < page.index("source.albums()")
for kind in ("albums", "devices", "tags"):
    assert f"val latest = source.{kind}()\n                    if (requestGeneration != generation) return@launch\n                    {kind} = latest" in metadata
assert metadata.count("catch (error: CancellationException) { throw error }") >= 3
assert metadata.count("catch (_: Exception)") == 3
assert source.count("source.albums()") == source.count("source.devices()") == source.count("source.tags()") == 1
assert "onRefresh = { if (!selecting) load(true) }" in source
print("Android filter metadata refresh source contract passed (native UI not executed)")
PYMETADATA

echo "mobile ABI v1 and state-epoch static gate passed"
