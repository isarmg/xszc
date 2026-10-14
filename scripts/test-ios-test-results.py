#!/usr/bin/env python3
"""Failure-path coverage for the actual-result gate; no Apple SDK required."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('ios_results', Path(__file__).with_name('check-ios-test-results.py'))
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


def report(count=1, **changes):
    return dict(result='Passed', totalTestCount=count, passedTests=count,
                failedTests=0, skippedTests=0, **changes)


class ResultTests(unittest.TestCase):
    def test_suite_growth_and_equivalent_refactors_do_not_require_count_updates(self):
        for count in (1, 9, 64, 65, 500):
            with self.subTest(count=count):
                self.assertEqual(CHECK.validate(report(count)), count)

    def test_empty_failed_skipped_and_partial_results_fail_closed(self):
        cases = [report(0)]
        for key, value in [('result', 'Failed'), ('result', 'Unknown'),
                           ('failedTests', 1), ('skippedTests', 1),
                           ('passedTests', 0), ('passedTests', 2),
                           ('expectedFailures', 1)]:
            data = report(); data[key] = value; cases.append(data)
        for data in cases:
            with self.subTest(data=data), self.assertRaises(ValueError):
                CHECK.validate(data)

    def test_missing_malformed_and_boolean_counts_are_not_success(self):
        for key in ('totalTestCount', 'passedTests', 'failedTests', 'skippedTests'):
            for bad in (None, '1', -1, True, 1.0):
                data = report(); data[key] = bad
                with self.subTest(key=key, bad=bad), self.assertRaises(ValueError):
                    CHECK.validate(data)
            data = report(); del data[key]
            with self.assertRaises(ValueError):
                CHECK.validate(data)
        for data in (None, [], {}):
            with self.assertRaises(ValueError):
                CHECK.validate(data)

    def test_reads_report_from_supplied_bundle_and_propagates_tool_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory)
            with mock.patch.object(CHECK.subprocess, 'run', return_value=
                                   subprocess.CompletedProcess([], 0, json.dumps(report(3)), '')) as run:
                self.assertEqual(CHECK.check(bundle), 3)
                run.assert_called_once_with(
                    ['xcrun', 'xcresulttool', 'get', 'test-results', 'summary', '--path', str(bundle)],
                    check=True, capture_output=True, text=True, timeout=60)
            for error in (subprocess.CalledProcessError(7, 'xcrun'),
                          subprocess.TimeoutExpired('xcrun', 60), FileNotFoundError('xcrun')):
                with mock.patch.object(CHECK.subprocess, 'run', side_effect=error), \
                     self.assertRaises(type(error)):
                    CHECK.check(bundle)
            with mock.patch.object(CHECK.subprocess, 'run', return_value=
                                   subprocess.CompletedProcess([], 0, 'not JSON', '')), \
                 self.assertRaises(ValueError):
                CHECK.check(bundle)
        with mock.patch.object(CHECK.subprocess, 'run') as run, self.assertRaises(ValueError):
            CHECK.check(bundle)
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
