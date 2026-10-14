#!/usr/bin/env python3
"""Overlap the CI simulator's cold boot with toolchain and native builds."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def select_device(devices):
    runtimes = sorted(
        (r for r in devices if r.startswith('com.apple.CoreSimulator.SimRuntime.iOS-27-0')),
        key=lambda r: tuple(map(int, r.rsplit('.iOS-', 1)[1].split('-'))), reverse=True)
    return next(d['udid'] for r in runtimes for d in devices[r]
                if d['name'].startswith('iPhone') and d.get('isAvailable'))


def boot(udid, status):
    started = time.monotonic()
    try:
        result = subprocess.run(['xcrun', 'simctl', 'boot', udid], timeout=600)
        code = result.returncode
    except subprocess.TimeoutExpired:
        code = 124
    except OSError as error:
        print(error, flush=True)
        code = 127
    print(f'simctl boot completed in {time.monotonic() - started:.1f}s: exit {code}', flush=True)
    temporary = status.with_suffix('.tmp')
    temporary.write_text(json.dumps({'udid': udid, 'returncode': code}))
    temporary.replace(status)
    return code


def wait(udid, status):
    deadline = time.monotonic() + 660
    while not status.exists():
        if time.monotonic() >= deadline:
            raise TimeoutError('simulator boot worker did not report its result')
        time.sleep(1)
    result = json.loads(status.read_text())
    if result['udid'] != udid:
        raise ValueError('simulator boot result belongs to a different device')
    if result['returncode'] != 0:
        raise subprocess.CalledProcessError(result['returncode'], ['xcrun', 'simctl', 'boot', udid])
    subprocess.run(['xcrun', 'simctl', 'bootstatus', udid, '-b'], check=True, timeout=360)


def main():
    root = Path(os.environ['RUNNER_TEMP'])
    status = root / 'xszc-simulator-boot.json'
    log = root / 'xszc-simulator-boot.log'
    if sys.argv[1] == 'boot':
        return boot(sys.argv[2], status)
    if sys.argv[1] == 'start':
        started = time.monotonic()
        devices = json.loads(subprocess.check_output(
            ['xcrun', 'simctl', 'list', 'devices', 'available', '-j'], timeout=360))['devices']
        udid = select_device(devices)
        print(f'Selected {udid} in {time.monotonic() - started:.1f}s', flush=True)
        status.unlink(missing_ok=True)
        with open(os.environ['GITHUB_ENV'], 'a') as env:
            env.write(f'XSZC_TEST_SIMULATOR={udid}\n')
        with log.open('w') as output:
            subprocess.Popen([sys.executable, str(Path(__file__).resolve()), 'boot', udid],
                             stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                             start_new_session=True)
        return 0
    if sys.argv[1] == 'wait':
        try:
            wait(os.environ['XSZC_TEST_SIMULATOR'], status)
        finally:
            if log.exists():
                print(log.read_text(), flush=True)
        return 0
    raise ValueError('expected start, boot, or wait')


if __name__ == '__main__':
    sys.exit(main())
