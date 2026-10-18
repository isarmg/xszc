#!/usr/bin/env python3
import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('release_ci', ROOT / 'scripts/check-release-ci.py')
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)
SHA = 'a' * 40
REPO = 'sarmg/xszc'


def run(run_id=10, **changes):
    return dict(id=run_id, head_sha=SHA, event='push', status='completed',
                conclusion='success', run_attempt=1,
                head_repository={'full_name': REPO}) | changes


class ReleaseCITests(unittest.TestCase):
    def check(self, runs=None, jobs=None, current=None):
        runs = [run()] if runs is None else runs
        jobs = [dict(name=name, status='completed', conclusion='success')
                for name in sorted(ci.REQUIRED_JOBS)] if jobs is None else jobs
        calls = []

        def get(path):
            calls.append(path)
            if '/workflows/' in path:
                self.assertIn('head_sha=' + SHA, path)
                self.assertNotIn('status=success', path)
                return {'workflow_runs': copy.deepcopy(runs)}
            if '/jobs?' in path:
                self.assertIn('filter=latest', path)
                page = int(path.rsplit('page=', 1)[1])
                return {'jobs': copy.deepcopy(jobs[(page - 1) * 100:page * 100])}
            return copy.deepcopy(current or max(runs, key=lambda item: item['id']))

        result = ci.require_ci(get, REPO, SHA)
        return result, calls

    def test_exact_commit_complete_run_succeeds(self):
        self.assertEqual(self.check()[0], 10)

    def test_no_run_wrong_sha_pr_and_foreign_repository_fail(self):
        for candidate in ([], [run(head_sha='b' * 40)], [run(event='pull_request')],
                          [run(head_repository={'full_name': 'fork/xszc'})]):
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                self.check(runs=candidate)

    def test_newer_failure_or_pending_run_cannot_borrow_old_success(self):
        for status, conclusion in [('completed', 'failure'), ('completed', 'cancelled'),
                                   ('completed', 'skipped'), ('in_progress', None)]:
            with self.subTest(conclusion=conclusion), self.assertRaises(ValueError):
                self.check(runs=[run(), run(11, status=status, conclusion=conclusion)])

    def test_required_jobs_must_all_execute_successfully(self):
        good = [dict(name=name, status='completed', conclusion='success')
                for name in sorted(ci.REQUIRED_JOBS)]
        for name in ci.REQUIRED_JOBS:
            for conclusion in ('skipped', 'cancelled', 'failure', None):
                jobs = copy.deepcopy(good)
                next(job for job in jobs if job['name'] == name)['conclusion'] = conclusion
                with self.subTest(name=name, conclusion=conclusion), self.assertRaises(ValueError):
                    self.check(jobs=jobs)
            with self.assertRaises(ValueError):
                self.check(jobs=[job for job in good if job['name'] != name])

    def test_jobs_are_paginated(self):
        extra = [dict(name='other-' + str(i), status='completed', conclusion='success') for i in range(100)]
        required = [dict(name=name, status='completed', conclusion='success') for name in ci.REQUIRED_JOBS]
        _, calls = self.check(jobs=extra + required)
        self.assertTrue(any('page=2' in path for path in calls))

    def test_concurrent_rerun_or_changed_head_is_rejected(self):
        for current in [run(run_attempt=2), run(status='in_progress', conclusion=None), run(head_sha='b' * 40)]:
            with self.subTest(current=current), self.assertRaises(ValueError):
                self.check(current=current)

    def test_release_depends_on_ci_and_all_checkouts_use_immutable_sha(self):
        release = (ROOT / '.github/workflows/release.yml').read_text()
        validate = release.split('  validate:', 1)[1].split('\n  android:', 1)[0]
        self.assertIn('actions: read', validate)
        self.assertIn('python3 scripts/check-release-ci.py --sha "$(git rev-parse HEAD)"', validate)
        self.assertIn('needs: validate', release.split('\n  android:', 1)[1].split('\n  ios:', 1)[0])
        self.assertIn('needs: validate', release.split('\n  ios:', 1)[1].split('\n  publish:', 1)[0])
        self.assertEqual(release.count('ref: ${{ github.sha }}'), 4)
        self.assertNotIn('ref: ${{ env.RELEASE_TAG }}', release)


if __name__ == '__main__':
    unittest.main()
