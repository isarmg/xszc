#!/usr/bin/env python3
"""Source-bound page/optional-metadata regressions; no Swift or iOS SDK.

Extract production statement order and the albums task boundary, then exercise
that scheduling model with deterministic async gates. This is not execution of
Swift, URLSession, cache storage or SwiftUI. --before-file accepts an original
BackupCoordinator.swift snapshot so the same independence checks demonstrate red.
"""
import argparse
import asyncio
from pathlib import Path
import re
import unittest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument('--before-file', type=Path)
args, remaining = parser.parse_known_args()


def block(source, marker):
    start = source.index('{', source.index(marker)) + 1
    depth = 1
    for end in range(start, len(source)):
        depth += (source[end] == '{') - (source[end] == '}')
        if depth == 0:
            return source[start:end]
    raise AssertionError(f'Unclosed source block: {marker}')


class Contract:
    def __init__(self):
        path = args.before_file or args.root / 'clients/ios/Xszc/BackupCoordinator.swift'
        source = path.read_text()
        body = block(source, 'func loadLibraryPage(first: Bool = false) async')
        assert '@MainActor\nfinal class BackupCoordinator' in source
        assert 'guard first || (!libraryLoading && nextCursor != nil)' in body
        assert 'defer { if generation == libraryGeneration { libraryLoading = false } }' in body
        assert body.index('libraryLoading = true') < body.index('let page = try await')
        assert 'remoteAssets = []; nextCursor = nil; seenCursors = []' in block(source, 'func refreshLibrary(')
        self.background = 'if first { Task {' in body
        album_call = 'await connection.albums()'
        assert body.count(album_call) == 1
        if self.background:
            optional = block(body, 'if first { Task {')
            task = block(optional, 'Task {')
            assert album_call in task and 'let albums = try?' in task
            assert 'let tags = try? await connection.tags()' in task
            assert 'if let albums { remoteAlbums = albums }' in task
            assert 'if let tags { remoteTags = tags }' in task
            assert task.index('await connection.tags()') < task.index('identity == profile')
            assert 'identity == profile, generation == libraryGeneration else { return }' in task
            assert 'remoteAlbums = albums' in task
            assert task.index(album_call) < task.index('identity == profile') < task.index('remoteAlbums = albums')
            assert not re.search(r'\b(?:libraryLoading|libraryError|status|remoteAssets|nextCursor)\s*=', task)
            main = body.replace(optional, '')
            assert album_call not in main and 'remoteAlbums = albums' not in main
            assert 'remoteTags = tags' not in main
            assert '.value' not in main and 'async let' not in main
            metadata_marker = 'if first { Task {'
        else:
            assert 'let albums = first ? (try? await connection.albums()) : nil' in body
            assert 'if let albums { remoteAlbums = albums }' in body
            assert 'Task {' not in body and 'async let' not in body
            metadata_marker = 'let albums = first ?'
        markers = {
            'page': 'let page = try await connection.loadTimelinePage(',
            'metadata': metadata_marker,
            'append': 'remoteAssets.append(contentsOf: page.items.filter { ids.insert($0.id).inserted })',
            'publish': 'nextCursor = page.nextCursor; library = connection; libraryError = nil',
        }
        self.steps = sorted(markers, key=lambda step: body.index(markers[step]))
        assert self.steps.index('page') < self.steps.index('append') < self.steps.index('publish')
        if self.background:
            assert body.index('status = "') < body.index(metadata_marker)
            assert self.steps.index('publish') < self.steps.index('metadata')

    async def load(self, fixture, state, *, first=True, cached=False):
        # A fixed, current profile/query and valid unique page are the scope.
        if not first and (state['loading'] or state['cursor'] is None):
            return
        if first:
            state.update(assets=[], cursor=None, error=None)
        state['loading'] = True

        async def metadata():
            try:
                albums = await fixture.albums()
            except OSError:
                albums = None  # Production try? leaves the previous options intact.
            try:
                tags = await fixture.tags()
            except OSError:
                tags = None
            if albums is not None:
                state['albums'] = albums
            if tags is not None:
                state['tags'] = tags

        try:
            for step in self.steps:
                if step == 'page':
                    page = await fixture.page(cached=cached)
                elif step == 'metadata' and first:
                    if self.background:
                        fixture.jobs.append(asyncio.create_task(metadata()))
                    else:
                        await metadata()
                elif step == 'append':
                    state['assets'] += page['items']
                elif step == 'publish':
                    state.update(cursor=page['cursor'], error=None, cached=page['cached'])
        finally:
            state['loading'] = False


class Fixture:
    def __init__(self, *, failed=False, immediate=False, tag_failed=False):
        self.failed = failed
        self.tag_failed = tag_failed
        self.started, self.release = asyncio.Event(), asyncio.Event()
        if immediate:
            self.release.set()
        self.pages = self.album_reads = 0
        self.jobs = []

    async def page(self, *, cached=False):
        self.pages += 1
        return dict(items=[f'photo-{self.pages}'], cursor=f'page-{self.pages + 1}', cached=cached)

    async def albums(self):
        self.album_reads += 1
        self.started.set()
        await self.release.wait()
        if self.failed:
            raise OSError('ordinary metadata network failure')
        return ['updated album']

    async def tags(self):
        if self.tag_failed:
            raise OSError('ordinary tag metadata network failure')
        return ['updated tag']

    async def finish(self):
        self.release.set()
        while self.jobs:
            jobs, self.jobs = self.jobs, []
            await asyncio.gather(*jobs)


class GalleryMetadataTests(unittest.IsolatedAsyncioTestCase):
    async def start(self, *, failed=False, immediate=False, cached=False, tag_failed=False):
        fixture = Fixture(failed=failed, immediate=immediate, tag_failed=tag_failed)
        self.addAsyncCleanup(fixture.finish)
        state = dict(assets=['previous photo'], cursor='old', error=None, loading=False, albums=['known album'], tags=['known tag'])
        task = asyncio.create_task(Contract().load(fixture, state, cached=cached))
        fixture.jobs.append(task)
        await asyncio.wait_for(fixture.started.wait(), 1)
        return fixture, state, task

    async def test_slow_metadata_does_not_hold_photo_cursor_or_loading(self):
        fixture, state, task = await self.start()
        self.assertTrue(task.done(), 'The page method still waits for optional metadata')
        self.assertEqual(state['assets'], ['photo-1'])
        self.assertEqual(state['cursor'], 'page-2')
        self.assertFalse(state['loading'])
        self.assertIsNone(state['error'])
        self.assertEqual(state['albums'], ['known album'])

    async def test_cached_page_is_published_while_metadata_is_pending(self):
        fixture, state, task = await self.start(cached=True)
        self.assertTrue(task.done(), 'An already returned cache page is still held by metadata')
        self.assertTrue(state['cached'])
        self.assertEqual(state['assets'], ['photo-1'])
        self.assertFalse(state['loading'])

    async def test_next_page_can_load_while_first_metadata_is_pending(self):
        fixture, state, task = await self.start()
        await Contract().load(fixture, state, first=False)
        self.assertEqual(fixture.pages, 2, 'Metadata still blocks the next page guard')
        self.assertEqual(state['assets'], ['photo-1', 'photo-2'])
        self.assertFalse(state['loading'])
        self.assertEqual(fixture.album_reads, 1)

    async def test_slow_metadata_success_refreshes_options_without_losing_page(self):
        fixture, state, task = await self.start()
        await fixture.finish()
        self.assertEqual(state['albums'], ['updated album'])
        self.assertEqual(state['assets'], ['photo-1'])
        self.assertIsNone(state['error'])
        self.assertFalse(state['loading'])

    async def test_slow_metadata_failure_preserves_old_options_and_successful_page(self):
        fixture, state, task = await self.start(failed=True)
        await fixture.finish()
        self.assertEqual(state['albums'], ['known album'])
        self.assertEqual(state['assets'], ['photo-1'])
        self.assertIsNone(state['error'])
        self.assertFalse(state['loading'])

    async def test_album_failure_does_not_skip_tag_metadata(self):
        fixture, state, task = await self.start(failed=True)
        await fixture.finish()
        self.assertEqual(state['albums'], ['known album'])
        self.assertEqual(state['tags'], ['updated tag'])

    async def test_tag_failure_does_not_discard_successful_albums(self):
        fixture, state, task = await self.start(tag_failed=True)
        await fixture.finish()
        self.assertEqual(state['albums'], ['updated album'])
        self.assertEqual(state['tags'], ['known tag'])

    async def test_immediate_metadata_success_and_failure(self):
        for failed in (False, True):
            with self.subTest(failed=failed):
                fixture, state, task = await self.start(failed=failed, immediate=True)
                await fixture.finish()
                self.assertEqual(state['albums'], ['known album'] if failed else ['updated album'])
                self.assertEqual(state['assets'], ['photo-1'])
                self.assertIsNone(state['error'])
                self.assertFalse(state['loading'])


if __name__ == '__main__':
    unittest.main(argv=[__file__, *remaining], verbosity=2)
