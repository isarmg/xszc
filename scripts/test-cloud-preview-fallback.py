#!/usr/bin/env python3
"""Source-backed thumbnail/preview control-flow regressions; no mobile SDK.

Extract the production thumbnail error handlers, evaluate their continuation
policy against deterministic request outcomes, and verify the primary request is
outside that handler. This is not Kotlin/Swift compilation, network execution,
or native UI testing. --before-dir runs against saved pre-fix source snapshots.
"""
import argparse
from pathlib import Path
import re
import unittest

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument('--before-dir', type=Path)
args, remaining = parser.parse_known_args()


def block(source, marker):
    start = source.index('{', source.index(marker)) + 1
    depth = 1
    for end in range(start, len(source)):
        depth += (source[end] == '{') - (source[end] == '}')
        if not depth:
            return source[start:end]
    raise AssertionError(f'Unclosed source block: {marker}')


class Contract:
    def __init__(self, platform):
        self.platform = platform
        path = args.root / ('clients/ios/Xszc/CloudGalleryScreen.swift' if platform == 'ios'
                           else 'clients/android/app/src/main/java/org/sarmg/xszc/CloudGalleryScreen.kt')
        if args.before_dir:
            path = args.before_dir / f'before-{platform}.{"swift" if platform == "ios" else "kt"}'
        self.source = path.read_text()
        if platform == 'ios':
            view = block(self.source, 'struct CloudImage: View')
            self.thumbnail = block(view, 'if let thumbnail = asset.thumbnail')
            assert view.index('resource: primary, preview: true') > view.index(self.thumbnail) + len(self.thumbnail)
            handler = block(self.thumbnail, '} catch') if '} catch' in self.thumbnail else ''
            match = re.search(r'if (.*?)\s*\{\s*throw error', handler, re.S)
            self.condition = match.group(1).strip() if match else None
        else:
            view = block(self.source, 'internal fun RemoteImage(')
            self.view = view
            guard = re.search(r'if \((.*?)\) asset.resources.firstOrNull \{ it.role == "thumbnail" \}\?\.let', view)
            assert guard, 'Missing progressive-thumbnail guard'
            self.progressive_guard = guard.group(1)
            assert 'if (resource != null && api != null)' in view
            assert 'asset.resources.firstOrNull { it.role != "thumbnail" }' in view
            assert 'if (preview && asset.mediaKind != "video") asset.resources.firstOrNull { it.role == "primary" }' in view
            # Select the .let body, not the preceding firstOrNull predicate.
            self.thumbnail = block(view, '}?.let')
            assert view.index('profile, resource, preview)') > view.index(self.thumbnail) + len(self.thumbnail)
            self.condition = None

    def continues(self, *, preview=True, video=False, primary=True, cancelled=False):
        if self.platform == 'android':
            guard = self.progressive_guard.replace('asset.mediaKind != "video"', 'not video').replace('&&', ' and ')
            assert re.fullmatch(r'[a-z\s]+', guard), guard
            eligible = eval(guard, {'__builtins__': {}}, dict(preview=preview, video=video))
            if not eligible or not primary:
                return False  # No progressive-placeholder path on these screens.
            if cancelled:
                return not bool(re.search(r'catch \(e: kotlinx.coroutines.CancellationException\)\s*\{\s*throw e\s*\}', self.thumbnail)) if 'catch' in self.thumbnail else False
            return bool(re.search(r'catch \(_: Exception\)\s*\{\s*\}', self.thumbnail))
        if self.condition is None:
            return False  # Thumbnail failure reaches the enclosing view catch.
        condition = self.condition.replace('asset.mediaKind == "video"', 'video')
        condition = condition.replace('asset.primary == nil', 'missing_primary')
        condition = condition.replace('Task.isCancelled', 'cancelled').replace('error is CancellationError', 'cancelled')
        condition = condition.replace('!preview', 'not preview').replace('||', ' or ')
        assert re.fullmatch(r'[a-z_\s]+', condition), condition
        return not eval(condition, {'__builtins__': {}}, dict(preview=preview, video=video, missing_primary=not primary, cancelled=cancelled))

    def run(self, thumbnail='ok', full='ok', **state):
        calls, image = [], None
        if thumbnail != 'absent':
            calls.append('thumbnail')
            if thumbnail == 'ok':
                image = 'thumbnail'
            elif not self.continues(cancelled=thumbnail == 'cancelled', **state):
                return calls, image
        calls.append('primary-preview')
        if full == 'ok':
            image = 'primary'
        return calls, image


class PreviewFallback(unittest.TestCase):
    def each(self):
        for platform in ('android', 'ios'):
            yield platform, Contract(platform)

    def test_independent_thumbnail_timeout_still_requests_preview(self):
        for platform, contract in self.each():
            with self.subTest(platform=platform):
                self.assertEqual((['thumbnail', 'primary-preview'], 'primary'), contract.run(thumbnail='timeout'))

    def test_thumbnail_http_failure_still_requests_preview(self):
        for platform, contract in self.each():
            with self.subTest(platform=platform):
                self.assertEqual((['thumbnail', 'primary-preview'], 'primary'), contract.run(thumbnail='http-error'))

    def test_thumbnail_cancel_does_not_start_full_request(self):
        for platform, contract in self.each():
            with self.subTest(platform=platform):
                self.assertEqual((['thumbnail'], None), contract.run(thumbnail='cancelled'))

    def test_android_outer_handler_propagates_cancellation_without_ui_error(self):
        contract = Contract('android')
        tail = contract.view.split('bitmap = RemoteImageCache.load(context, api, profile, resource, preview)', 1)[1]
        self.assertRegex(tail, r'catch \(e: kotlinx.coroutines.CancellationException\)\s*\{\s*throw e\s*\}\s*catch \(e: Exception\)')

    def test_normal_progressive_preview(self):
        for platform, contract in self.each():
            with self.subTest(platform=platform):
                self.assertEqual((['thumbnail', 'primary-preview'], 'primary'), contract.run())

    def test_full_failure_keeps_successful_thumbnail(self):
        for platform, contract in self.each():
            with self.subTest(platform=platform):
                self.assertEqual((['thumbnail', 'primary-preview'], 'thumbnail'), contract.run(full='error'))

    def test_both_fail_leave_placeholder(self):
        for platform, contract in self.each():
            with self.subTest(platform=platform):
                self.assertEqual((['thumbnail', 'primary-preview'], None), contract.run(thumbnail='timeout', full='error'))

    def test_absent_thumbnail_does_not_block_primary(self):
        for platform, contract in self.each():
            with self.subTest(platform=platform):
                self.assertEqual((['primary-preview'], 'primary'), contract.run(thumbnail='absent'))

    def test_grid_video_and_missing_primary_errors_are_not_suppressed(self):
        for platform, contract in self.each():
            for state in ({'preview': False}, {'video': True}, {'primary': False}):
                with self.subTest(platform=platform, state=state):
                    self.assertFalse(contract.continues(**state))


unittest.main(argv=[__file__, *remaining])
