#!/usr/bin/env python3
"""Keep asynchronous CI boot failure handling and device identity strict."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('simulator', Path(__file__).with_name('ci-ios-simulator.py'))
simulator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(simulator)


class SimulatorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.status = Path(self.temporary.name) / 'boot.json'
        self.log = Path(self.temporary.name) / 'boot.log'

    def status_for(self, udid='same-device', code=0):
        self.status.write_text(json.dumps({'udid': udid, 'returncode': code}))

    def test_selects_available_reviewed_runtime(self):
        self.assertEqual(simulator.select_device({
            'com.apple.CoreSimulator.SimRuntime.iOS-26-0': [{'udid': 'wrong', 'name': 'iPhone', 'isAvailable': True}],
            'com.apple.CoreSimulator.SimRuntime.iOS-27-0': [
                {'udid': 'unavailable', 'name': 'iPhone', 'isAvailable': False},
                {'udid': 'same-device', 'name': 'iPhone 17', 'isAvailable': True}]}), 'same-device')

    def test_start_persists_device_and_detaches_worker(self):
        env = Path(self.temporary.name) / 'env'
        devices = {'devices': {'com.apple.CoreSimulator.SimRuntime.iOS-27-0': [
            {'udid': 'same-device', 'name': 'iPhone 17', 'isAvailable': True}]}}
        with patch.dict(simulator.os.environ, {'RUNNER_TEMP': self.temporary.name, 'GITHUB_ENV': str(env)}), \
             patch.object(simulator.sys, 'argv', ['helper', 'start']), \
             patch.object(simulator.subprocess, 'check_output', return_value=json.dumps(devices).encode()), \
             patch.object(simulator.subprocess, 'Popen') as launch:
            self.assertEqual(simulator.main(), 0)
        self.assertEqual(env.read_text(), 'XSZC_TEST_SIMULATOR=same-device\n')
        self.assertEqual(launch.call_args.args[0][-2:], ['boot', 'same-device'])
        self.assertTrue(launch.call_args.kwargs['start_new_session'])
        self.assertEqual(launch.call_args.kwargs['stdin'], subprocess.DEVNULL)

    def test_boot_retains_real_failure(self):
        with patch.object(simulator.subprocess, 'run', return_value=subprocess.CompletedProcess([], 7)):
            self.assertEqual(simulator.boot('same-device', self.status), 7)
        self.assertEqual(json.loads(self.status.read_text()), {'udid': 'same-device', 'returncode': 7})

    def test_boot_timeout_is_failure(self):
        with patch.object(simulator.subprocess, 'run', side_effect=subprocess.TimeoutExpired('boot', 600)):
            self.assertEqual(simulator.boot('same-device', self.status), 124)

    def test_readiness_uses_same_device_and_bounded_wait(self):
        self.status_for()
        with patch.object(simulator.subprocess, 'run') as run:
            simulator.wait('same-device', self.status)
        run.assert_called_once_with(['xcrun', 'simctl', 'bootstatus', 'same-device', '-b'], check=True, timeout=360)

    def test_failed_boot_is_not_masked_by_readiness(self):
        self.status_for(code=7)
        with patch.object(simulator.subprocess, 'run') as run:
            with self.assertRaises(subprocess.CalledProcessError) as error:
                simulator.wait('same-device', self.status)
        self.assertEqual(error.exception.returncode, 7)
        run.assert_not_called()

    def test_wait_command_preserves_boot_failure_status(self):
        with patch.dict(simulator.os.environ, {'RUNNER_TEMP': self.temporary.name, 'XSZC_TEST_SIMULATOR': 'same-device'}), \
             patch.object(simulator.sys, 'argv', ['helper', 'wait']), \
             patch.object(simulator, 'wait', side_effect=subprocess.CalledProcessError(7, ['boot'])):
            self.assertEqual(simulator.main(), 7)

    def test_mismatched_device_fails(self):
        self.status_for(udid='other-device')
        with self.assertRaises(ValueError):
            simulator.wait('same-device', self.status)

    def test_missing_worker_result_is_bounded(self):
        with patch.object(simulator.time, 'monotonic', side_effect=[0, 661]):
            with self.assertRaises(TimeoutError):
                simulator.wait('same-device', self.status)


if __name__ == '__main__':
    unittest.main()
