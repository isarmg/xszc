#!/usr/bin/env python3
"""Exercise cross-step preparation and failure propagation with real subprocesses."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
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

    def run_step(self, step):
        return subprocess.run(['bash', str(self.script), step], env=self.env,
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

    def test_brew_failure_preserves_code_and_stops_later_work(self):
        self.env['BREW_CODE'] = '7'
        self.run_step('start')
        self.assertEqual(self.run_step('wait').returncode, 7)
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

    def test_malformed_result_fails_closed(self):
        (self.root / 'xszc-ios-prepare.status').write_text('not-a-status')
        (self.root / 'xszc-ios-prepare.log').write_text('probe log')
        self.assertNotEqual(self.run_step('wait').returncode, 0)


if __name__ == '__main__':
    unittest.main()
