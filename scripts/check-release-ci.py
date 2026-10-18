#!/usr/bin/env python3
"""Require the latest complete Build and test run for the release's exact commit.

Only read GitHub Actions metadata; never dispatch, rerun, or accept a PR merge
run. A tag alone does not run build.yml, so finish branch CI before tagging.
"""
import argparse
import json
import os
import re
import sys
from urllib.parse import urlencode
from urllib.request import Request, urlopen


REQUIRED_JOBS = {
    'Rust workspace', 'Android APK', 'iOS simulator tests', 'iOS device IPA',
    'iOS unsigned IPA',
}


def require_ci(get, repository, source_sha):
    if not re.fullmatch(r'[0-9a-f]{40}', source_sha):
        raise ValueError('Release source must be a full commit SHA')
    base = f'/repos/{repository}/actions'
    query = urlencode({'head_sha': source_sha, 'per_page': 100})
    runs = get(f'{base}/workflows/build.yml/runs?{query}')['workflow_runs']
    runs = [run for run in runs if run['head_sha'] == source_sha
            and run['event'] in ('push', 'workflow_dispatch')
            and run.get('head_repository', {}).get('full_name') == repository]
    if not runs:
        raise ValueError(f'No branch Build and test run for {source_sha}; finish CI before tagging')
    # Never search past a newer failure/cancellation/in-progress run for an old green result.
    run = max(runs, key=lambda item: item['id'])
    if run['status'] != 'completed' or run['conclusion'] != 'success':
        raise ValueError(f"Latest Build and test run {run['id']} is {run['status']}/{run['conclusion']}")
    jobs = []
    page = 1
    while True:
        batch = get(f"{base}/runs/{run['id']}/jobs?filter=latest&per_page=100&page={page}")['jobs']
        jobs.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    for name in sorted(REQUIRED_JOBS):
        matches = [job for job in jobs if job['name'] == name]
        if len(matches) != 1 or matches[0]['status'] != 'completed' or matches[0]['conclusion'] != 'success':
            raise ValueError(f"Build and test run {run['id']} lacks a successful required job: {name}")
    # A rerun may begin while jobs are being read. Fail closed if the inspected
    # run's attempt or terminal state changed during the check.
    current = get(f"{base}/runs/{run['id']}")
    if (current['head_sha'] != source_sha or current['status'] != 'completed'
            or current['conclusion'] != 'success'
            or current['run_attempt'] != run['run_attempt']):
        raise ValueError('Build and test changed while release checks were being read; retry after CI finishes')
    return run['id']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sha', required=True)
    args = parser.parse_args()
    repository = os.environ['GITHUB_REPOSITORY']
    token = os.environ['GH_TOKEN']
    api = os.environ.get('GITHUB_API_URL', 'https://api.github.com').rstrip('/')

    def get(path):
        request = Request(api + path, headers={
            'Accept': 'application/vnd.github+json',
            'Authorization': 'Bearer ' + token,
            'X-GitHub-Api-Version': '2022-11-28',
        })
        with urlopen(request, timeout=30) as response:
            return json.load(response)

    run_id = require_ci(get, repository, args.sha)
    print(f'Release source {args.sha} passed all required jobs in Build and test run {run_id}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        print(f'Release CI check failed: {error}', file=sys.stderr)
        sys.exit(1)
