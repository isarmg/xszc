#!/usr/bin/env python3
"""Require an actual, complete, nonempty XCTest result, without fixed counts.

The workflow selects entire test bundles, never individual test names. This
checks executed results, not Swift source spelling. It does not prove that an
unwired test target was included; project/scheme and workflow checks own that.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys


def validate(summary):
    if not isinstance(summary, dict):
        raise ValueError('XCTest summary must be an object')
    counts = {}
    for key in ('totalTestCount', 'passedTests', 'failedTests', 'skippedTests'):
        value = summary.get(key)
        if type(value) is not int or value < 0:
            raise ValueError(f'XCTest summary has no valid {key}: {value!r}')
        counts[key] = value
    if summary.get('result') != 'Passed':
        raise ValueError(f"XCTest run did not pass: {summary.get('result')!r}")
    if counts['totalTestCount'] == 0:
        raise ValueError('XCTest run executed no tests')
    if counts['failedTests'] or counts['skippedTests']:
        raise ValueError(f'XCTest run contains failed/skipped tests: {counts}')
    if counts['passedTests'] != counts['totalTestCount']:
        raise ValueError(f'XCTest run has missing or non-passing results: {counts}')
    # Expected failures still represent unverified behavior, not a green gate.
    expected = summary.get('expectedFailures', 0)
    if type(expected) is not int or expected != 0:
        raise ValueError(f'XCTest run contains expected failures: {expected!r}')
    return counts['passedTests']


def check(bundle):
    if not bundle.is_dir():
        raise ValueError(f'XCTest result bundle is missing: {bundle}')
    result = subprocess.run(
        ['xcrun', 'xcresulttool', 'get', 'test-results', 'summary', '--path', str(bundle)],
        check=True, capture_output=True, text=True, timeout=60)
    # Emit the actual report so rejected results remain diagnosable in CI logs.
    print(result.stdout, flush=True)
    return validate(json.loads(result.stdout))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    args = parser.parse_args()
    try:
        passed = check(args.bundle)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f'iOS result verification failed: {error}', file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr, file=sys.stderr)
        return 1
    print(f'iOS result verified: {passed} passed; no skipped or failed tests')
    return 0


if __name__ == '__main__':
    sys.exit(main())
