#!/usr/bin/env python3
"""Exercise slice builds, the exact aggregate shell, and full test wiring."""
import itertools
import sys
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = (ROOT / '.github/workflows/build.yml').read_text()


def job(name):
    return re.search(r'^  ' + name + r':\n(.*?)(?=^  \w+:\n|\Z)',
                     WORKFLOW, re.M | re.S).group(1)


class SliceTests(unittest.TestCase):
    def run_build(self, *args, failure='0'):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'scripts').mkdir()
            (root / 'bin').mkdir()
            headers = root / 'crates/mobile-ffi/include'
            headers.mkdir(parents=True)
            for name in ['xszc_ffi_v1.h', 'module.modulemap']:
                (headers / name).write_text(name)
            script = root / 'scripts/build-ios-rust.sh'
            shutil.copyfile(ROOT / 'scripts/build-ios-rust.sh', script)
            for name in ['cargo', 'xcodebuild']:
                command = root / 'bin' / name
                command.write_text('#!/bin/bash\n'
                                   'printf "%s\\n" "$(basename "$0"):$*" >> "$PROBE_LOG"\n'
                                   'if [[ "$(basename "$0")" == cargo ]]; then exit "$FAIL_CARGO"; fi\n')
                command.chmod(0o755)
            log = root / 'calls'
            env = dict(os.environ, PATH=str(root / 'bin') + os.pathsep + os.environ['PATH'],
                       PROBE_LOG=str(log), FAIL_CARGO=failure)
            result = subprocess.run(['bash', str(script), *args], env=env,
                                    capture_output=True, text=True, timeout=5)
            return result.returncode, log.read_text() if log.exists() else ''

    def test_each_slice_builds_and_packages_only_its_target(self):
        for mode, target in [('device', 'aarch64-apple-ios'),
                             ('simulator', 'aarch64-apple-ios-sim')]:
            with self.subTest(mode=mode):
                code, log = self.run_build(mode)
                self.assertEqual(code, 0)
                lines = log.splitlines()
                self.assertEqual(lines[0], 'cargo:build -p xszc-mobile --release --locked --target ' + target)
                self.assertEqual(len(lines), 2)
                self.assertEqual(lines[1].count('-library '), 1)
                self.assertIn('target/' + target + '/release/libxszc_mobile.a', lines[1])
                self.assertIn('-create-xcframework', lines[1])

    def test_default_and_explicit_all_keep_both_slices(self):
        for args in [(), ('all',)]:
            code, log = self.run_build(*args)
            self.assertEqual(code, 0)
            self.assertEqual(log.count('cargo:build'), 2)
            self.assertEqual(log.count('-library '), 2)

    def test_invalid_arguments_fail_before_build(self):
        for args in [('invalid',), ('device', 'extra')]:
            code, log = self.run_build(*args)
            self.assertNotEqual(code, 0)
            self.assertEqual(log, '')

    def test_build_failure_stops_packaging(self):
        code, log = self.run_build('device', failure='7')
        self.assertEqual(code, 7)
        self.assertNotIn('xcodebuild:', log)


class SeedTimingTests(unittest.TestCase):
    def run_seed(self, fail=''):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'bin').mkdir()
            assets = root / 'clients/android/app/src/androidTest/assets'
            assets.mkdir(parents=True)
            (assets / 'video-preview.mp4').write_bytes(b'test-video')
            command = root / 'bin/xcrun'
            command.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['PROBE_LOG'], 'a') as log:
    log.write(json.dumps(args) + "\\n")
if args[:4] == ['simctl', 'list', 'devices', 'booted']:
    print(json.dumps({'devices': {'test': [{'udid': 'test-device'}]}}))
if args[:2] == ['simctl', 'addmedia'] and any('gallery-page-' in arg for arg in args):
    sys.exit(7 if os.environ['FAIL_IMPORT'] else 0)
''')
            command.chmod(0o755)
            log = root / 'calls'
            env = dict(os.environ, PATH=str(root / 'bin') + os.pathsep + os.environ['PATH'],
                       RUNNER_TEMP=str(root), PROBE_LOG=str(log), FAIL_IMPORT=fail)
            result = subprocess.run(['bash', str(ROOT / 'scripts/seed-ios-ui.sh'), 'test-device'],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=15)
            import json
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            return result, calls

    def test_timing_preserves_all_inputs_and_import_order(self):
        result, calls = self.run_seed()
        self.assertEqual(result.returncode, 0, result.stderr)
        imports = [call[3:] for call in calls if call[:2] == ['simctl', 'addmedia']]
        self.assertEqual(len(imports), 10)
        self.assertEqual([Path(p).name for p in imports[0]],
                         ['gallery-page-%03d.png' % i for i in range(1, 302)])
        self.assertEqual(Path(imports[1][0]).name, 'layout-video.mp4')
        self.assertEqual([Path(group[0]).name for group in imports[2:]],
                         ['layout-photo-%d.png' % i for i in range(8, 0, -1)])
        for phase in ['generate-photos', 'bootstatus', 'import-gallery-pages', 'import-video',
                      'reset-photos-privacy', *['import-photo-%d' % i for i in range(8, 0, -1)]]:
            self.assertRegex(result.stdout, 'iOS fixture phase ' + phase + r' finished after \d+s: exit 0')

    def test_import_failure_reports_status_and_stops_remaining_phases(self):
        result, calls = self.run_seed(fail='yes')
        self.assertEqual(result.returncode, 7)
        self.assertIn('exit 7', result.stdout)
        self.assertEqual(sum(call[:2] == ['simctl', 'addmedia'] for call in calls), 1)
        self.assertNotIn('import-video started', result.stdout)


class LivePhotoImportTests(unittest.TestCase):
    def test_real_runner_logs_import_success_and_failure_without_changing_tests(self):
        for status in (0, 7):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                xcrun = root / 'xcrun'
                xcrun.write_text('#!/bin/sh\nprintf "%s\\n" "$*" > "$IMPORT_ARGS"\nexit ' + str(status) + '\n')
                xcrun.chmod(0o755)
                # Exercise run_tests itself with a child producer and real pipe;
                # substitute only the unavailable simulator command.
                producer = root / 'producer.py'
                producer.write_text('import time\nprint("XSZC_UI_READY_FOR_PHOTO_CHANGE", flush=True)\ntime.sleep(0.2)\n')
                driver = root / 'driver.py'
                driver.write_text(
                    'import importlib.util, pathlib, sys\n'
                    'spec = importlib.util.spec_from_file_location("ui", ' + repr(str(ROOT / 'scripts/test-ios-ui.py')) + ')\n'
                    'ui = importlib.util.module_from_spec(spec); spec.loader.exec_module(ui)\n'
                    'sys.exit(ui.run_tests([sys.executable, ' + repr(str(producer)) + '], "test-device", pathlib.Path("fixture.png")))\n')
                env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                           IMPORT_ARGS=str(root / 'args'))
                result = subprocess.run([sys.executable, str(driver)], env=env,
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode == 0, status == 0)
                self.assertEqual((root / 'args').read_text().strip(),
                                 'simctl addmedia test-device fixture.png')
                self.assertIn('iOS live photo import started', result.stdout)
                self.assertIn('iOS live photo import ' + ('finished' if status == 0 else 'failed') + ' after', result.stdout)


class WorkflowTests(unittest.TestCase):
    def test_aggregate_requires_both_exact_success_results(self):
        aggregate = job('ios')
        self.assertIn('name: iOS unsigned IPA\n', aggregate)
        self.assertIn('needs: [ios_simulator, ios_device]', aggregate)
        self.assertIn('if: ${{ always() }}', aggregate)
        self.assertIn('SIMULATOR_RESULT: ${{ needs.ios_simulator.result }}', aggregate)
        self.assertIn('DEVICE_RESULT: ${{ needs.ios_device.result }}', aggregate)
        command = aggregate.split('        run: |\n')[1]
        command = '\n'.join(line[10:] for line in command.splitlines())
        for simulator, device in itertools.product(['success', 'failure', 'cancelled', 'skipped', ''], repeat=2):
            env = dict(os.environ, SIMULATOR_RESULT=simulator, DEVICE_RESULT=device)
            result = subprocess.run(['bash', '-e', '-c', command], env=env)
            self.assertEqual(result.returncode == 0, simulator == device == 'success')

    def test_runner_allowlist_rejects_wrong_and_unreviewed_mac_jobs(self):
        mutations = [
            WORKFLOW.replace('  ios_simulator:', '  unreviewed_mac:'),
            WORKFLOW.replace('runs-on: xcode-27', 'runs-on: macos-latest', 1),
            WORKFLOW.replace('runs-on: ubuntu-24.04', 'runs-on: xcode-27', 1),
        ]
        for workflow in mutations:
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                shutil.copytree(ROOT / '.github/workflows', root / '.github/workflows')
                (root / '.github/workflows/build.yml').write_text(workflow)
                subprocess.run(['git', 'init', '-q', str(root)], check=True)
                subprocess.run(['git', '-C', str(root), 'add', '.github'], check=True)
                result = subprocess.run(['bash', str(ROOT / 'scripts/check-workflow-supply-chain.sh')],
                                        cwd=root, capture_output=True, text=True, timeout=5)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('workflow supply-chain check failed', result.stderr)
                self.assertRegex(result.stderr, 'reviewed xcode-27 image|must use ubuntu-24.04')

    def test_lanes_keep_complete_tests_and_separate_cache_writes(self):
        simulator, device = job('ios_simulator'), job('ios_device')
        for lane, mode in [(simulator, 'simulator'), (device, 'device')]:
            self.assertIn('runs-on: xcode-27', lane)
            self.assertNotIn('    needs:', lane)
            self.assertIn('./scripts/build-ios-rust.sh ' + mode, lane)
            self.assertIn('shared-key: ios27-' + mode + '-v1', lane)
            if mode == 'device':
                self.assertIn('key: ios-toolchain-v2-device-', lane)
            else:
                self.assertNotIn('uses: actions/cache@', lane)
                self.assertNotIn('~/.rustup/toolchains/', lane)
            self.assertIn('uses: dtolnay/rust-toolchain@', lane)
            self.assertIn('toolchain: 1.99.0', lane)
            self.assertNotIn('restore-keys:', lane)
            self.assertIn('RUSTUP_TOOLCHAIN: 1.99.0', lane)
            target = 'aarch64-apple-ios-sim' if mode == 'simulator' else 'aarch64-apple-ios'
            self.assertIn('targets: ' + target + '\n', lane)
            self.assertNotIn('components:', lane)
            self.assertIn('verify-ios-rust-toolchain.sh ' + mode + '\n', lane)
            for gate in ['verify-ios-rust-toolchain.sh', 'check-xcsc-source.py',
                         'verify-ios-toolchain.sh', 'ci-ios-prepare.sh wait', 'test-mobile-ffi-c.sh']:
                self.assertIn(gate, lane)
        self.assertIn('-only-testing:XszcTests ', simulator)
        self.assertNotIn('-skip-testing:', simulator)
        self.assertIn('python3 scripts/test-ios-ui.py ', simulator)
        ui_runner = (ROOT / 'scripts/test-ios-ui.py').read_text()
        self.assertIn('"-only-testing:XszcUITests"', ui_runner)
        self.assertNotIn('-skip-testing:', ui_runner)
        self.assertIn('seed-ios-ui.sh', simulator)
        self.assertNotIn('ci-ios-simulator.py', device)
        self.assertNotIn('package-ios-ipa.py', simulator)
        self.assertIn('package-ios-ipa.py', device)
        self.assertIn('CODE_SIGNING_ALLOWED=NO', device)
        for folder, expected in [('XszcTests', 61), ('XszcUITests', 9)]:
            methods = sum(len(re.findall(r'\bfunc test\w+\s*\(', p.read_text()))
                          for p in (ROOT / 'clients/ios' / folder).glob('*.swift'))
            self.assertEqual(methods, expected)
        self.assertIn('python3 scripts/test-ci-ios-lanes.py', job('rust'))
        self.assertIn('components: rustfmt,clippy', job('rust'))
        self.assertIn('cargo fmt --all -- --check', job('rust'))
        self.assertIn('cargo clippy --workspace --all-targets --locked -- -D warnings', job('rust'))


if __name__ == '__main__':
    unittest.main()
