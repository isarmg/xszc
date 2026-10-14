#!/usr/bin/env python3
"""Run identity gates against command substitutes, including concurrent probes."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
STUB = r'''
import os
from pathlib import Path
import sys
import time
name = Path(sys.argv[0]).name
mode = os.environ.get('PROBE_CASE', '')
if name in ('xcodebuild', 'xcrun'):
    key = 'xcode' if name == 'xcodebuild' else sys.argv[2]
    root = Path(os.environ['PROBE_ROOT'])
    (root / key).touch()
    deadline = time.monotonic() + 3
    while not all((root / n).exists() for n in ('xcode', 'iphoneos', 'iphonesimulator')):
        if time.monotonic() > deadline:
            print('probes were serialized', file=sys.stderr)
            sys.exit(9)
        time.sleep(0.01)
    if mode == key + '-failure':
        print('probe failed: ' + key, file=sys.stderr)
        sys.exit(7)
    if key == 'xcode':
        print('Xcode 27.0\nBuild version ' + ('wrong' if mode == 'wrong-xcode' else '27A266a'))
    else:
        print('27.0beta1' if mode == key + '-beta' else '27.0')
elif name == 'rustc':
    print('release: ' + ('1.98.0' if mode == 'wrong-release' else '1.99.0'))
    print('commit-hash: ' + ('wrong' if mode == 'wrong-commit' else 'b940084d7eb6a299eb4bfeb8e34901bc051e7ac4'))
    print('host: ' + ('x86_64-apple-darwin' if mode == 'wrong-host' else 'aarch64-apple-darwin'))
elif name == 'rustup':
    if sys.argv[1] == 'target':
        if mode != 'missing-host-target': print('aarch64-apple-darwin')
        if mode not in ('missing-device', 'simulator-only'): print('aarch64-apple-ios')
        if mode not in ('missing-simulator', 'device-only'): print('aarch64-apple-ios-sim')
    else:
        if mode != 'missing-clippy': print('clippy-aarch64-apple-darwin')
        print('rustfmt-aarch64-apple-darwin')
else:
    sys.exit(99)
'''


class ToolchainTests(unittest.TestCase):
    def run_gate(self, script, mode='', args=(), toolchain='1.99.0'):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for command in ('xcodebuild', 'xcrun', 'rustc', 'rustup'):
                path = root / command
                path.write_text('#!' + sys.executable + '\n' + STUB)
                path.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       PROBE_ROOT=str(root), PROBE_CASE=mode, RUSTUP_TOOLCHAIN=toolchain)
            return subprocess.run(['bash', str(ROOT / script), *args], env=env,
                                  capture_output=True, text=True, timeout=10)

    def test_sdk_probes_run_concurrently_and_valid_identity_passes(self):
        result = self.run_gate('verify-ios-toolchain.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('iphoneos SDK: 27.0', result.stdout)
        self.assertIn('iphonesimulator SDK: 27.0', result.stdout)

    def test_each_probe_failure_preserves_exit_code(self):
        for probe in ('xcode', 'iphoneos', 'iphonesimulator'):
            with self.subTest(probe=probe):
                result = self.run_gate('verify-ios-toolchain.sh', probe + '-failure')
                self.assertEqual(result.returncode, 7, result.stderr)
                self.assertIn('probe failed: ' + probe, result.stderr)

    def test_wrong_xcode_or_unstable_sdk_is_rejected(self):
        for mode in ('wrong-xcode', 'iphoneos-beta', 'iphonesimulator-beta'):
            with self.subTest(mode=mode):
                self.assertNotEqual(self.run_gate('verify-ios-toolchain.sh', mode).returncode, 0)

    def test_each_lane_requires_only_host_and_its_platform_target(self):
        for lane in ['simulator', 'device']:
            result = self.run_gate('verify-ios-rust-toolchain.sh', lane + '-only', args=(lane,))
            self.assertEqual(result.returncode, 0, result.stderr)
            result = self.run_gate('verify-ios-rust-toolchain.sh', 'missing-clippy', args=(lane,))
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_cached_rust_identity_and_required_targets_fail_closed(self):
        for lane in ['simulator', 'device']:
            for mode in ('wrong-release', 'wrong-commit', 'wrong-host', 'missing-host-target', 'missing-' + lane):
                with self.subTest(lane=lane, mode=mode):
                    result = self.run_gate('verify-ios-rust-toolchain.sh', mode, args=(lane,))
                    self.assertNotEqual(result.returncode, 0, result.stderr)

    def test_invalid_lane_and_unpinned_environment_fail_closed(self):
        for args in [(), ('all',), ('invalid',), ('device', 'extra')]:
            self.assertNotEqual(self.run_gate('verify-ios-rust-toolchain.sh', args=args).returncode, 0)
        for toolchain in ['', 'stable', '1.98.0']:
            self.assertNotEqual(self.run_gate('verify-ios-rust-toolchain.sh', args=('device',),
                                             toolchain=toolchain).returncode, 0)


if __name__ == '__main__':
    unittest.main()
