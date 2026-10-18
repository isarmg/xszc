#!/usr/bin/env python3
"""Independent source guards for gallery privacy, lifecycle and UI test setup.

Selection interleavings execute the production Kotlin/Swift selection state in
LocalGallerySelectionTest and LocalGallerySelectionTests on their real runtimes.
"""
import argparse
from pathlib import Path
import re
import unittest

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
args, remaining = parser.parse_known_args()
ANDROID = args.root / 'clients/android/app/src/main/java/org/sarmg/xszc/LocalGridScreen.kt'
IOS = args.root / 'clients/ios/Xszc/LocalGalleryScreen.swift'


def block(source, marker):
    start = source.index('{', source.index(marker)) + 1
    depth = 1
    for index in range(start, len(source)):
        if source[index] == '{':
            depth += 1
        elif source[index] == '}':
            depth -= 1
            if depth == 0:
                return source[start:index]
    raise AssertionError(f'Unclosed function: {marker}')


class SelectionWiringTests(unittest.TestCase):
    def test_only_preview_requests_advance_preview_request_generation(self):
        for path, marker, increment in (
            (ANDROID, 'fun openEntry', 'if (!select) previewGeneration++'),
            (IOS, 'func openEntry', 'if !selecting { previewGeneration += 1 }'),
        ):
            with self.subTest(platform=path):
                source = path.read_text()
                opened = block(source, marker)
                self.assertIn(increment, opened)
                self.assertLess(opened.index(increment), opened.index('details.resolve'))
                self.assertNotIn('previewGeneration', block(source, 'fun toggle' if path == ANDROID else 'func toggle'))

    def test_android_submission_schedules_before_leaving_io(self):
        body = block(ANDROID.read_text(), 'fun submitBackup')
        io = block(body, 'withContext(Dispatchers.IO)')
        self.assertLess(io.index('LocalCatalog.persist'), io.index('BackupScheduler.enqueueNow'))
        self.assertEqual(body.count('BackupScheduler.enqueueNow'), 1)
        self.assertNotIn('resetSelection()', io)
        self.assertNotIn('onSubmitted()', io)

    def test_android_cancel_and_submission_use_reset(self):
        source = ANDROID.read_text()
        self.assertIn('onClick = { resetSelection() }, modifier = Modifier.testTag("gallery.cancel")', source)
        self.assertIn('resetSelection(); onSubmitted()', source)

    def test_ios_navigation_and_filter_changes_invalidate_pending_entries(self):
        source = IOS.read_text()
        leaving = block(source, '.onDisappear {\n            isVisible')
        self.assertIn('entryGeneration += 1; selection.invalidate(); details.stop()', leaving)
        self.assertIn('entryGeneration += 1', block(source, 'func filtersChanged'))


class PhotoObserverLifecycleTests(unittest.TestCase):
    def test_tracking_initializes_without_directory_readiness_and_preserves_pending_changes(self):
        source = IOS.read_text()
        ensure = block(source, 'func ensureTracking()')
        self.assertIn('guard assets == nil else { return false }', ensure)
        # Execute the source guard for both lifecycle orderings. The fetch calls
        # are substituted; this is a source-level regression, not PhotoKit QA.
        predicate = re.search(r'guard (.*?) else', ensure).group(1)
        assignments = re.findall(r'^        (assets|collections|libraryAlbums) = ', ensure, re.M)
        self.assertEqual(assignments, ['assets', 'collections', 'libraryAlbums'])
        self.assertNotRegex(ensure, r'(updated|removed|fullScan|albumsChanged)\s*=')
        self.assertIn('return true', ensure)
        for cached_directory in (False, True):
            state = dict(assets=None, collections=None, libraryAlbums=None,
                         updated={'pending': 'asset'}, removed={'deleted'},
                         fullScan=True, albumsChanged=True)
            def run_ensure():
                if not eval(predicate.replace('nil', 'None'), {'__builtins__': {}}, state):
                    return False
                for name in assignments:
                    state[name] = object()
                return True
            with self.subTest(cached_directory=cached_directory):
                startedTracking = run_ensure()
                self.assertTrue(startedTracking)
                # A scene-phase load may already have a persisted directory;
                # that cannot suppress the initial reconciliation.
                full_prefix = re.search(r'let full = (.*?) \|\| status', source).group(1)
                self.assertTrue(eval(full_prefix.replace('||', ' or '),
                                     {'__builtins__': {}},
                                     dict(scan=not cached_directory, startedTracking=startedTracking)))
                first_assets = state['assets']
                self.assertFalse(run_ensure())
                self.assertIs(state['assets'], first_assets)
                self.assertEqual(state['updated'], {'pending': 'asset'})
                self.assertEqual(state['removed'], {'deleted'})
                self.assertTrue(state['fullScan'])
                self.assertTrue(state['albumsChanged'])
        load = block(source, 'private func load(scan:')
        self.assertIn('let startedTracking = photoChanges.ensureTracking()', load)
        self.assertLess(load.index('guard status == .authorized || status == .limited'),
                        load.index('photoChanges.ensureTracking()'))
        self.assertLess(load.index('photoChanges.ensureTracking()'), load.index('try await readDirectory()'))
        self.assertNotIn('firstLoad', load[:load.index('photoChanges.ensureTracking()')])
        self.assertIn('let full = scan || startedTracking || status == .limited || change.fullScan', load)
        self.assertIn('queuedScan = queuedScan || scan || startedTracking', load)
        self.assertIn('await load(scan: firstLoad)', source)
        self.assertNotIn('trackLibrary()', source)


class LoginInputSynchronizationTests(unittest.TestCase):
    def test_first_password_input_waits_for_visible_field_and_keyboard_without_retry(self):
        source = (args.root / 'clients/ios/XszcUITests/NavigationTests.swift').read_text()
        body = block(source, 'func testLoginRemainsReachableFromGalleryAndSettings')
        sync = body[body.index('let password ='):body.index('screenshot("account-login-keyboard")')]
        self.assertIn('password.exists && password.isHittable', sync)
        self.assertIn('XCTWaiter.wait(for: [passwordReady], timeout: 5)', sync)
        self.assertEqual(sync.count('.tap()'), 1)
        self.assertIn('password.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()', sync)
        self.assertNotRegex(sync, r'\b(for|while)\b(?!:| \[)')
        self.assertNotIn('value(forKey:', sync)
        self.assertNotIn('hasKeyboardFocus', sync)
        keyboard = 'app.keyboards.firstMatch.waitForExistence(timeout: 5)'
        typed = 'password.typeText("test-instance-code")'
        self.assertLess(sync.index('.tap()'), sync.index(keyboard))
        self.assertLess(sync.index(keyboard), sync.index('XCTAssertTrue(keyboardReady'))
        self.assertLess(sync.index('XCTAssertTrue(keyboardReady'), sync.index(typed))
        self.assertLess(sync.index('inputHierarchy.lifetime = .keepAlways'), sync.index(typed))
        self.assertIn('account-keyboard-missing', sync)
        self.assertIn('account-password-not-hittable', sync)
        self.assertIn('XCTAssertFalse(app.buttons["account.submit"].isEnabled)', sync)
        self.assertIn('app.textFields["account.server"].typeText("http://invalid.example")', body)
        self.assertIn('app.secureTextFields["pairing.authorization-code"].typeText("test-instance-code")', body)


class UIScrollProbeTests(unittest.TestCase):
    def test_missing_tiles_do_not_resolve_hittability_before_scrolling(self):
        source = (args.root / 'clients/ios/XszcUITests/NavigationTests.swift').read_text()

        class Tile:
            def __init__(self, exists, hittable):
                self.exists = exists
                self.hittable = hittable

            @property
            def isHittable(self):
                if not self.exists:
                    raise AssertionError('Missing tiles must not trigger implicit AX resolution retries')
                return self.hittable

        for function, name in (
            ('func testVideoOpensFullScreenPausedAndSupportsSeekingSpeedAndBackupSelection', 'tile'),
            ('func testCompleteDirectoryScrollsPastPageBoundariesAndKeepsPositionAcrossRefresh', 'older'),
        ):
            body = block(source, function)
            loop = block(body, 'for _ in 0..<30')
            predicate = re.search(r'if (.*?) \{ break \}', loop).group(1)
            self.assertEqual(predicate, f'{name}.exists && {name}.isHittable')
            self.assertIn('swipeUp(velocity: .fast)', loop)
            self.assertIn(f'XCTAssertTrue({name}.isHittable', body)
            for exists, hittable, expected in ((False, False, False), (True, False, False), (True, True, True)):
                with self.subTest(function=function, exists=exists, hittable=hittable):
                    result = eval(predicate.replace('&&', ' and '), {'__builtins__': {}},
                                  {name: Tile(exists, hittable)})
                    self.assertEqual(result, expected)


class CatalogLifecycleTests(unittest.TestCase):
    def test_whole_scan_and_patch_use_same_store_lock(self):
        source = IOS.read_text()
        store = (args.root / 'clients/ios/Xszc/TransferStore.swift').read_text()
        session = block(store, 'func withCatalogMutation<T>')
        self.assertLess(session.index('catalogMutationLock.lock()'), session.index('Task.checkCancellation()'))
        self.assertIn('defer { catalogMutationLock.unlock() }', session)
        self.assertLess(session.index('Task.checkCancellation()'), session.index('return try body()'))
        for name in ['scan', 'patch']:
            body = block(source, f'static func {name}(store:')
            self.assertIn('return try store.withCatalogMutation {', body)
            self.assertIn('try Task.checkCancellation()', body)
        scan = block(source, 'static func scan(store:')
        self.assertIn('try Task.checkCancellation()\n            try store.gallery(["op": "finish_catalog"])', scan)

    def test_retired_load_cannot_publish_or_enqueue_replacement_load(self):
        source = IOS.read_text()
        disappear = block(source, '.onDisappear {\n            isVisible')
        for text in ['isVisible = false; loadGeneration += 1', 'catalogTask?.cancel()',
                     'queuedRefresh = false; queuedScan = false', 'photoChanges.requireFullScan()']:
            self.assertIn(text, disappear)
        finish = block(source, 'private func finishLoad(')
        self.assertEqual(finish.count('generation == loadGeneration'), 2)
        self.assertEqual(finish.count('profile == coordinator.profile'), 2)
        load = block(source, 'private func load(scan:')
        self.assertIn('query.profile == coordinator.profile && !Task.isCancelled', load)
        self.assertIn('onCancel: { worker.cancel() }', load)
        self.assertIn('catalogTask = worker', load)
        self.assertIn('guard alive() else { return }\n                if let nextAlbums', load)
        self.assertIn('defer { finishLoad(generation: generation, profile: query.profile) }', load)
        cancelled = block(load, 'catch is CancellationError')
        self.assertNotIn('if alive()', cancelled)
        self.assertIn('if generation == loadGeneration, query.profile == coordinator.profile { photoChanges.requireFullScan() }', cancelled)


class CatalogDiagnosticsTests(unittest.TestCase):
    def test_diagnostics_are_debug_only_and_avoid_media_identifiers(self):
        source = block(IOS.read_text(), 'static func scan(store:')
        debug = re.findall(r'#if DEBUG\n(.*?)#endif', source, re.S)
        self.assertTrue(debug)
        release = re.sub(r'#if DEBUG\n.*?#endif\n', '', source, flags=re.S)
        self.assertNotIn('log.debug', release)
        self.assertNotIn('systemUptime', release)
        for line in source.splitlines():
            if 'log.debug' in line:
                if '\\(' in line:
                    self.assertEqual(line.count('\\('), line.count('privacy: .public'))
                for sensitive in ('localIdentifier', 'originalFilename', 'descriptor', 'name)', 'path'):
                    self.assertNotIn(sensitive, line)
        # Catalog filename accuracy, pagination and external PhotoKit changes
        # execute in NavigationTests, with a bounded wait for the seeded library.
        # Exact API spelling, log cadence and batch size are implementation
        # choices; the privacy/release-log checks above remain independent.


if __name__ == '__main__':
    unittest.main(argv=[__file__, *remaining])
