#!/usr/bin/env python3
"""Exercise cross-step preparation and failure propagation with real subprocesses."""
from datetime import datetime, timezone
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

SOURCE = Path(__file__).with_name('ci-ios-prepare.sh')


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'scripts').mkdir()
        (self.root / 'bin').mkdir()
        self.script = self.root / 'scripts/ci-ios-prepare.sh'
        shutil.copyfile(SOURCE, self.script)
        self.write('bin/brew', '''#!/usr/bin/env bash
set -eu
echo "brew:$*"
if [[ "${BLOCK_BREW:-0}" == 1 ]]; then
  until [[ -f "$RUNNER_TEMP/release" ]]; do sleep 0.01; done
fi
exit "${BREW_CODE:-0}"
''')
        self.write('bin/xcodegen', '''#!/usr/bin/env bash
printf '%s\\n' "${XCODEGEN_VERSION:-Version: 2.46.0}"
''')
        self.write('scripts/test-ios-system-directory.sh', '''#!/usr/bin/env bash
echo sandbox-executed
touch "$RUNNER_TEMP/sandbox-ran"
exit "${SANDBOX_CODE:-0}"
''')
        self.env = dict(os.environ, RUNNER_TEMP=str(self.root),
                        PATH=str(self.root / 'bin') + os.pathsep + os.environ['PATH'])

    def write(self, path, text):
        target = self.root / path
        target.write_text(text)
        target.chmod(0o755)

    def run_step(self, step, *args):
        return subprocess.run(['bash', str(self.script), step, *args], env=self.env,
                              capture_output=True, text=True, timeout=5)

    def test_start_returns_before_worker_and_wait_joins_across_shells(self):
        self.env['BLOCK_BREW'] = '1'
        try:
            self.assertEqual(self.run_step('start').returncode, 0)
            self.assertFalse((self.root / 'xszc-ios-prepare.status').exists())
        finally:
            (self.root / 'release').touch()
        result = self.run_step('wait')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('brew:install xcodegen', result.stdout)
        self.assertIn('sandbox-executed', result.stdout)

    def assert_timestamps(self, output, labels, earliest, latest):
        stamps = []
        lines = output.splitlines()
        for label in labels:
            matches = [line for line in lines if line.endswith(' ' + label)]
            self.assertEqual(len(matches), 1, (label, output))
            stamp = datetime.strptime(matches[0].split(' ', 1)[0],
                                      '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)
            self.assertLessEqual(earliest, stamp)
            self.assertLessEqual(stamp, latest)
            stamps.append(stamp)
        self.assertEqual(stamps, sorted(stamps))
        positions = [output.index(' ' + label) for label in labels]
        self.assertEqual(positions, sorted(positions))

    def test_tools_log_real_utc_phase_timestamps(self):
        # Logs are replayed later by wait; timestamps must come from the worker.
        self.env['TZ'] = 'Pacific/Honolulu'
        earliest = datetime.now(timezone.utc).replace(microsecond=0)
        result = self.run_step('worker')
        latest = datetime.now(timezone.utc)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_timestamps(result.stdout, [
            'iOS tools worker entered', 'iOS brew started', 'iOS brew finished',
            'iOS Swift sandbox started', 'iOS Swift sandbox finished',
        ], earliest, latest)
        self.assertLess(result.stdout.index('iOS brew started'), result.stdout.index('brew:install'))
        self.assertLess(result.stdout.index('sandbox-executed'), result.stdout.index('iOS Swift sandbox finished'))

    def test_media_logs_wait_start_and_original_result_on_success_or_failure(self):
        self.media_stubs()
        for code in ('0', '8'):
            with self.subTest(code=code):
                self.env['BOOT_CODE'] = code
                earliest = datetime.now(timezone.utc).replace(microsecond=0)
                result = self.run_step('worker', 'media')
                self.assertEqual(result.returncode, int(code), result.stderr)
                self.assert_timestamps(result.stdout, [
                    'iOS media worker entered', 'iOS simulator wait started',
                    f'iOS simulator wait finished: exit {code}',
                ], earliest, datetime.now(timezone.utc))
                self.assertEqual('media-executed' in result.stdout, code == '0')

    def test_brew_failure_preserves_code_and_stops_later_work(self):
        self.env['BREW_CODE'] = '7'
        self.run_step('start')
        result = self.run_step('wait')
        self.assertEqual(result.returncode, 7)
        self.assertIn('iOS brew started', result.stdout)
        self.assertNotIn('iOS brew finished', result.stdout)
        self.assertFalse((self.root / 'sandbox-ran').exists())

    def test_wrong_xcodegen_is_rejected_before_sandbox(self):
        self.env['XCODEGEN_VERSION'] = 'Version: 2.45.0'
        self.run_step('start')
        self.assertNotEqual(self.run_step('wait').returncode, 0)
        self.assertFalse((self.root / 'sandbox-ran').exists())

    def test_sandbox_failure_preserves_code_and_log(self):
        self.env['SANDBOX_CODE'] = '9'
        self.run_step('start')
        result = self.run_step('wait')
        self.assertEqual(result.returncode, 9)
        self.assertIn('sandbox-executed', result.stdout)
        self.assertIn('iOS Swift sandbox started', result.stdout)
        self.assertNotIn('iOS Swift sandbox finished', result.stdout)

    def test_malformed_result_fails_closed(self):
        (self.root / 'xszc-ios-prepare.status').write_text('not-a-status')
        (self.root / 'xszc-ios-prepare.log').write_text('probe log')
        self.assertNotEqual(self.run_step('wait').returncode, 0)

    def media_stubs(self):
        self.env['XSZC_TEST_SIMULATOR'] = 'test-device'
        self.write('scripts/ci-ios-simulator.py', """import os, sys
from pathlib import Path
assert sys.argv[1:] == ['wait']
print('joined existing boot', flush=True)
Path(os.environ['RUNNER_TEMP'], 'boot-joined').touch()
sys.exit(int(os.environ.get('BOOT_CODE', '0')))
""")
        self.write('scripts/seed-ios-ui.sh', """#!/bin/bash
set -eu
test -f "$RUNNER_TEMP/boot-joined"
test "$1" = test-device && test "$2" = media
echo media-executed
touch "$RUNNER_TEMP/media-ran"
exit "${MEDIA_CODE:-0}"
""")

    def test_media_wait_joins_boot_before_import_and_preserves_failure(self):
        self.media_stubs()
        for code in ('0', '7'):
            with self.subTest(code=code):
                self.env['MEDIA_CODE'] = code
                self.assertEqual(self.run_step('start', 'media').returncode, 0)
                result = self.run_step('wait', 'media')
                self.assertEqual(result.returncode, int(code), result.stderr)
                self.assertLess(result.stdout.index('joined existing boot'), result.stdout.index('media-executed'))
        self.assertFalse((self.root / 'sandbox-ran').exists())

    def test_boot_failure_prevents_media_and_retains_exit_code(self):
        self.media_stubs()
        self.env['BOOT_CODE'] = '8'
        self.run_step('start', 'media')
        self.assertEqual(self.run_step('wait', 'media').returncode, 8)
        self.assertFalse((self.root / 'media-ran').exists())

    def test_media_missing_result_is_bounded_failure(self):
        self.script.write_text(self.script.read_text().replace('deadline=$((SECONDS + budget))', 'deadline=$SECONDS'))
        (self.root / 'xszc-ios-media.log').write_text('worker missing')
        result = self.run_step('wait', 'media')
        self.assertEqual(result.returncode, 124)
        self.assertIn('worker missing', result.stderr)

    def test_media_timeout_reports_failure(self):
        self.media_stubs()
        self.write('scripts/seed-ios-ui.sh', '#!/bin/bash\nsleep 0.5\ntouch "$RUNNER_TEMP/import-finished"\n')
        self.assertIn('worker.wait(timeout=1080)', self.script.read_text())
        self.script.write_text(self.script.read_text().replace('worker.wait(timeout=1080)', 'worker.wait(timeout=0.1)'))
        self.run_step('start', 'media')
        result = self.run_step('wait', 'media')
        self.assertEqual(result.returncode, 124)
        self.assertIn('exceeded 18 minutes', result.stdout)
        time.sleep(0.6)
        self.assertFalse((self.root / 'import-finished').exists())

    def test_media_budget_includes_boot_join(self):
        self.media_stubs()
        self.write('scripts/ci-ios-simulator.py', 'import time\ntime.sleep(30)\n')
        self.script.write_text(self.script.read_text().replace('worker.wait(timeout=1080)', 'worker.wait(timeout=0.1)'))
        self.run_step('start', 'media')
        result = self.run_step('wait', 'media')
        self.assertEqual(result.returncode, 124)
        self.assertIn('exceeded 18 minutes', result.stdout)
        self.assertFalse((self.root / 'media-ran').exists())

    def test_media_malformed_result_fails_closed(self):
        (self.root / 'xszc-ios-media.status').write_text('not-a-status')
        (self.root / 'xszc-ios-media.log').write_text('media log')
        self.assertNotEqual(self.run_step('wait', 'media').returncode, 0)


if __name__ == '__main__':
    unittest.main()
