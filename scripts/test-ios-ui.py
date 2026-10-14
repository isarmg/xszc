#!/usr/bin/env python3
"""Run native UI tests and supply the external PhotoKit change they observe."""
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import shutil
import subprocess
import sys
import time


def main():
    simulator, result_bundle = sys.argv[1:]
    fixture_dir = Path(os.environ["RUNNER_TEMP"]) / "xszc-layout-photos"
    refreshed_photo = fixture_dir / "layout-auto-refresh.png"
    shutil.copyfile(fixture_dir / "layout-photo-1.png", refreshed_photo)
    command = [
        "xcodebuild", "-project", "clients/ios/Xszc.xcodeproj",
        "-scheme", "Xszc", "-sdk", "iphonesimulator",
        "-destination", f"id={simulator}",
        "-derivedDataPath", "clients/ios/DerivedData",
        "-only-testing:XszcUITests", "-resultBundlePath", result_bundle,
        "-collect-test-diagnostics", "never", "-parallel-testing-enabled", "NO", "test-without-building",
    ]
    live_path = Path(os.environ["RUNNER_TEMP"]) / "xszc-ios-live.log"
    with live_path.open("w") as live_output, subprocess.Popen([
        "xcrun", "simctl", "spawn", simulator, "log", "stream", "--level", "debug",
        "--style", "compact", "--predicate", 'subsystem == "org.sarmg.xszc"',
    ], stdout=live_output, stderr=subprocess.STDOUT) as live:
        try:
            return run_tests(command, simulator, refreshed_photo)
        finally:
            live.terminate()
            try:
                live.wait(timeout=5)
            except subprocess.TimeoutExpired:
                live.kill()
                live.wait()


def run_tests(command, simulator, refreshed_photo):
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, bufsize=1) as test, ThreadPoolExecutor(max_workers=1) as imports:
        imported = None

        def import_photo():
            started = time.monotonic()
            print("iOS live photo import started", flush=True)
            try:
                subprocess.run(["xcrun", "simctl", "addmedia", simulator,
                                str(refreshed_photo)], check=True, timeout=30)
                print(f"iOS live photo import finished after {time.monotonic() - started:.3f}s", flush=True)
            except BaseException:
                print(f"iOS live photo import failed after {time.monotonic() - started:.3f}s", flush=True)
                test.terminate()
                raise

        try:
            for line in test.stdout:
                print(line, end="", flush=True)
                if line.strip() == "XSZC_UI_READY_FOR_PHOTO_CHANGE" and imported is None:
                    # Keep draining XCTest output while PhotoKit imports the fixture.
                    imported = imports.submit(import_photo)
            if imported is not None:
                imported.result()
            return test.wait()
        except BaseException:
            test.terminate()
            test.wait()
            raise


if __name__ == "__main__":
    sys.exit(main())
