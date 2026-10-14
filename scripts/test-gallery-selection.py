#!/usr/bin/env python3
"""Source-level selection interleaving regressions, without a mobile SDK.

Evaluate the production completion predicates against deterministic deferred-read
scenarios. This verifies the guard/reset contract, not Kotlin/Swift compilation,
Compose/SwiftUI rendering, or PhotoKit/MediaStore behavior.
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


class SourceContract:
    def __init__(self, platform):
        self.platform = platform
        self.source = (ANDROID if platform == 'android' else IOS).read_text()
        self.open = block(self.source, 'fun openEntry' if platform == 'android' else 'func openEntry')
        self.bulk = block(self.source, 'fun selectScope' if platform == 'android' else 'func selectScope')
        marker = 'fun resetSelection' if platform == 'android' else 'func resetSelection'
        self.reset = block(self.source, marker) if marker in self.source else ''
        self.toggle = block(self.source, 'fun toggle' if platform == 'android' else 'func toggle')

    def allows(self, kind, *, reset=False, reenter=False, filter_changed=False, toggle=False, pending=False):
        # All reads start with selection active and the default query. Suspend at
        # the detail-read await, apply a user event, then evaluate its real guard.
        epoch = 'selectionGeneration' if kind == 'bulk' else 'entryGeneration'
        generation = 0
        current = int(reset and epoch in self.reset) + int(toggle and epoch in self.toggle)
        current += int(pending and epoch == 'selectionGeneration' and
                       'if (select) selectionGeneration++' in self.open[:self.open.index('details.resolve')])
        body = self.bulk if kind == 'bulk' else self.open
        local_query = ('profile', None, None, False)
        namespace = dict(generation=generation, selectionGeneration=current,
                         entryGeneration=current, selecting=not reset or reenter,
                         isSelecting=not reset or reenter, query=local_query,
                         profile='profile', identity='profile', album=None,
                         kind='video' if filter_changed else None, unbacked=False,
                         LocalGalleryQuery=lambda *a, **k: tuple(a) if a else tuple(k.values()))
        if self.platform == 'android':
            if kind == 'bulk':
                predicate = re.search(r'if \(([^\n]+)\) selection = selection \+ chosen', body).group(1)
                rejected = False
            else:
                match = re.search(r'if \(([^\n]+)\) return@launch', body)
                if not match:
                    return True
                predicate = match.group(1)
                rejected = True
            predicate = predicate.replace('&&', ' and ').replace('||', ' or ')
        else:
            if kind == 'bulk':
                # Skip the pre-read guard; select the guard after detached work.
                predicate = re.findall(r'guard (.*?) else \{ return \}', body, re.S)[-1]
            else:
                predicate = re.search(r'guard (.*?) else \{ return \}', body, re.S).group(1)
            predicate = predicate.replace('coordinator.profile', 'profile')
            predicate = re.sub(r'LocalGalleryQuery\(profile: (.*?), album: (.*?), kind: (.*?), unbacked: (.*?)\)',
                               r'LocalGalleryQuery(\1, \2, \3, \4)', predicate)
            # Swift guard commas are AND, while constructor commas separate args.
            depth = 0
            translated = []
            for char in predicate:
                if char == '(':
                    depth += 1
                elif char == ')':
                    depth -= 1
                translated.append(' and ' if char == ',' and depth == 0 else char)
            predicate = ' '.join(''.join(translated).split())
            rejected = False
        result = bool(eval(predicate, {'__builtins__': {}}, namespace))
        return not result if rejected else result


    def allows_submission(self, *, reset=False, toggle=False, pending=False, failure=False):
        body = block(self.source, 'fun submitBackup')
        self_generation = int(reset and 'selectionGeneration++' in self.reset)
        self_generation += int(toggle and 'selectionGeneration++' in self.toggle)
        self_generation += int(pending and 'if (select) selectionGeneration++' in
                               self.open[:self.open.index('details.resolve')])
        assert 'val generation = selectionGeneration' in body[:body.index('withContext')]
        completion = body[body.index('BackupScheduler.enqueueNow'):body.index('} catch')]
        if failure:
            predicate = re.search(r'if \(([^\n]+)\) notice = e.message', body).group(1)
        else:
            predicate = re.search(r'if \(([^\n]+)\) \{ resetSelection\(\); onSubmitted\(\)', completion).group(1)
            # Scheduling A must remain outside the stale-UI guard.
            assert completion.index('BackupScheduler.enqueueNow') < completion.index('if (')
        return bool(eval(predicate, {'__builtins__': {}}, dict(
            generation=0, selectionGeneration=self_generation)))

    def allows_preview(self, request, current, selecting=False):
        if self.platform == 'android':
            match = re.search(r'else if \((previewRequest == [^\n]+)\) setPreview\(row\)', self.open)
            predicate = match.group(1).replace('&&', ' and ').replace('!selecting', 'not selecting') if match else 'True'
        else:
            match = re.search(r'guard (previewRequest == .*?) else \{ return \}', self.open)
            predicate = match.group(1).replace(', ', ' and ').replace('!isSelecting', 'not selecting') if match else 'True'
        return bool(eval(predicate, {'__builtins__': {}}, dict(
            previewRequest=request, previewGeneration=current, selecting=selecting)))


    def allows_failure(self, kind, *, reset=False, filter_changed=False, older_preview=False, select=False):
        body = self.bulk if kind == 'bulk' else self.open
        if self.platform == 'android':
            failure = block(body, 'catch (e: Exception)')
            match = re.search(r'if \((.*?)\) notice =', failure, re.S)
        else:
            failure = block(body, '} catch {')
            match = (re.search(r'if (.*?) \{\s*message =', failure, re.S) if kind == 'bulk'
                     else re.search(r'guard (.*?) else \{ return \}', failure, re.S))
        if not match:
            return True
        predicate = match.group(1).replace('coordinator.profile', 'profile')
        predicate = re.sub(r'LocalGalleryQuery\(profile: (.*?), album: (.*?), kind: (.*?), unbacked: (.*?)\)',
                           r'LocalGalleryQuery(\1, \2, \3, \4)', predicate)
        if self.platform == 'ios':
            depth = 0
            translated = []
            for char in predicate:
                if char == '(':
                    depth += 1
                elif char == ')':
                    depth -= 1
                translated.append(') and (' if char == ',' and depth == 0 else char)
            predicate = '(' + ''.join(translated) + ')'
        predicate = ' '.join(predicate.replace('&&', ' and ').replace('||', ' or ')
                             .replace('!isSelecting', 'not isSelecting').replace('!selecting', 'not selecting').split())
        namespace = dict(generation=0, selectionGeneration=int(reset), entryGeneration=int(reset),
                         query=('profile', None, None, False), profile='profile', identity='profile',
                         album=None, kind='video' if filter_changed else None, unbacked=False,
                         selecting=(select if self.platform == 'ios' and kind == 'entry' else not reset),
                         isSelecting=not reset if kind == 'bulk' or select else False, select=select,
                         previewRequest=1, previewGeneration=2 if older_preview else 1,
                         LocalGalleryQuery=lambda *a: tuple(a))
        if self.platform == 'android' and kind == 'entry':
            namespace['selecting'] = select and not reset
        return bool(eval(predicate, {'__builtins__': {}}, namespace))


class SelectionRegressionTests(unittest.TestCase):
    def test_android_bulk_cancel_does_not_repopulate_hidden_selection(self):
        self.assertFalse(SourceContract('android').allows('bulk', reset=True))

    def test_android_bulk_cancel_then_reenter_does_not_restore_old_selection(self):
        self.assertFalse(SourceContract('android').allows('bulk', reset=True, reenter=True))

    def test_android_bulk_new_individual_choice_wins(self):
        self.assertFalse(SourceContract('android').allows('bulk', toggle=True))

    def test_bulk_unchanged_selection_and_filters_continue(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                self.assertTrue(SourceContract(platform).allows('bulk'))

    def test_entry_cancel_does_not_reenter_selection(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                self.assertFalse(SourceContract(platform).allows('entry', reset=True))

    def test_entry_cancel_then_reenter_rejects_old_read(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                self.assertFalse(SourceContract(platform).allows('entry', reset=True, reenter=True))

    def test_entry_filter_change_rejects_old_read(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                self.assertFalse(SourceContract(platform).allows('entry', filter_changed=True))

    def test_entry_normal_and_parallel_individual_choices_continue(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                contract = SourceContract(platform)
                self.assertTrue(contract.allows('entry'))
                self.assertTrue(contract.allows('entry', toggle=True))

    def test_preview_b_wins_when_a_completes_later(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                contract = SourceContract(platform)
                self.assertTrue(contract.allows_preview(request=2, current=2))
                self.assertFalse(contract.allows_preview(request=1, current=2))

    def test_preview_a_cannot_flash_after_b_was_requested(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                self.assertFalse(SourceContract(platform).allows_preview(request=1, current=2))

    def test_close_prevents_deferred_preview_from_reopening(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                contract = SourceContract(platform)
                marker = 'fun setPreview' if platform == 'android' else 'func setPreview'
                self.assertTrue(marker in contract.source, 'Preview close must invalidate requests')
                close = block(contract.source, marker)
                self.assertTrue('previewGeneration' in close)
                self.assertFalse(contract.allows_preview(request=2, current=3))
                if platform == 'ios':
                    self.assertTrue('previewGeneration' in block(contract.source, 'func setVideoPreview'))
                    self.assertTrue('set: { setVideoPreview($0) }' in contract.source)

    def test_entering_selection_prevents_deferred_preview_from_opening(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                self.assertFalse(SourceContract(platform).allows_preview(request=1, current=1, selecting=True))

    def test_only_preview_requests_advance_preview_request_generation(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                contract = SourceContract(platform)
                increment = 'if (!select) previewGeneration++' if platform == 'android' else 'if !selecting { previewGeneration += 1 }'
                self.assertTrue(increment in contract.open)
                self.assertTrue('previewGeneration' not in contract.toggle)
                self.assertTrue(contract.open.index(increment) < contract.open.index('details.resolve'))

    def test_canceled_bulk_failure_is_not_displayed(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                self.assertFalse(SourceContract(platform).allows_failure('bulk', reset=True))

    def test_canceled_entry_failure_is_not_displayed(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                self.assertFalse(SourceContract(platform).allows_failure('entry', reset=True, select=True))

    def test_old_filter_entry_failure_is_not_displayed(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                self.assertFalse(SourceContract(platform).allows_failure('entry', filter_changed=True, select=True))

    def test_superseded_preview_failure_is_not_displayed(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                self.assertFalse(SourceContract(platform).allows_failure('entry', older_preview=True))

    def test_current_failures_remain_visible(self):
        for platform in ['android', 'ios']:
            with self.subTest(platform=platform):
                contract = SourceContract(platform)
                self.assertTrue(contract.allows_failure('bulk'))
                self.assertTrue(contract.allows_failure('entry', select=True))
                self.assertTrue(contract.allows_failure('entry'))

    def test_android_submission_schedules_before_leaving_io(self):
        # Source wiring only. Real coroutine dispatcher cancellation is covered by
        # the separate Kotlin extraction probe; this is not a WorkManager test.
        body = block(SourceContract('android').source, 'fun submitBackup')
        io = block(body, 'withContext(Dispatchers.IO)')
        self.assertLess(io.index('LocalCatalog.persist'), io.index('BackupScheduler.enqueueNow'))
        self.assertEqual(body.count('BackupScheduler.enqueueNow'), 1)
        self.assertNotIn('resetSelection()', io)
        self.assertNotIn('onSubmitted()', io)

    def test_android_submission_preserves_new_selection(self):
        contract = SourceContract('android')
        self.assertFalse(contract.allows_submission(reset=True, toggle=True))
        self.assertFalse(contract.allows_submission(toggle=True))

    def test_android_submission_finishes_before_cold_selection_resolves(self):
        contract = SourceContract('android')
        reset_by_submission = contract.allows_submission(pending=True)
        self.assertFalse(reset_by_submission)
        # B resumes after A. A must not reset entryGeneration and discard B.
        self.assertTrue(contract.allows('entry', reset=reset_by_submission))

    def test_android_submission_finishes_before_cold_selection_fails(self):
        contract = SourceContract('android')
        reset_by_submission = contract.allows_submission(pending=True)
        self.assertFalse(reset_by_submission)
        self.assertTrue(contract.allows_failure('entry', reset=reset_by_submission, select=True))
        self.assertFalse(contract.allows_submission(pending=True, failure=True))

    def test_android_pending_individual_intent_fences_bulk_completion(self):
        self.assertFalse(SourceContract('android').allows('bulk', pending=True))

    def test_android_pending_individual_intent_keeps_independent_reads(self):
        contract = SourceContract('android')
        self.assertNotIn('entryGeneration++', contract.open[:contract.open.index('details.resolve')])
        self.assertTrue(contract.allows('entry', pending=True))
        self.assertTrue(contract.allows('entry', pending=True, toggle=True))

    def test_android_submission_respects_cancel_without_reselection(self):
        self.assertFalse(SourceContract('android').allows_submission(reset=True))

    def test_android_unchanged_submission_still_navigates(self):
        self.assertTrue(SourceContract('android').allows_submission())

    def test_android_old_submission_error_does_not_replace_new_selection_notice(self):
        contract = SourceContract('android')
        self.assertFalse(contract.allows_submission(reset=True, toggle=True, failure=True))
        self.assertFalse(contract.allows_submission(reset=True, failure=True))

    def test_android_current_submission_error_still_allows_retry(self):
        contract = SourceContract('android')
        self.assertTrue(contract.allows_submission(failure=True))
        body = block(contract.source, 'fun submitBackup')
        failure = body[body.index('} catch'):body.index('finally')]
        self.assertNotIn('resetSelection', failure)
        self.assertIn('busy = false', body[body.index('finally'):])

    def test_android_cancel_and_submission_use_reset(self):
        source = SourceContract('android').source
        self.assertTrue('onClick = { resetSelection() }, modifier = Modifier.testTag("gallery.cancel")' in source, 'Cancel must invalidate pending selection')
        self.assertTrue('resetSelection(); onSubmitted()' in source, 'Successful submission must reset pending selection')

    def test_ios_navigation_and_filter_changes_invalidate_pending_entries(self):
        source = SourceContract('ios').source
        self.assertIn('entryGeneration += 1; selectionGeneration += 1; details.stop()', block(source, '.onDisappear {\n            isVisible'), 'Leaving the gallery must invalidate pending reads')
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
    def test_diagnostics_are_debug_only_numeric_and_batched(self):
        source = block(IOS.read_text(), 'static func scan(store:')
        debug = re.findall(r'#if DEBUG\n(.*?)#endif', source, re.S)
        self.assertTrue(debug)
        release = re.sub(r'#if DEBUG\n.*?#endif\n', '', source, flags=re.S)
        self.assertNotIn('log.debug', release)
        self.assertNotIn('systemUptime', release)
        self.assertIn('(index + 1) % 50 == 0 || index + 1 == fetch.count', source)
        self.assertIn('enumerationSeconds += ProcessInfo.processInfo.systemUptime - enumerationStarted', source)
        self.assertIn('filenameSeconds += ProcessInfo.processInfo.systemUptime - filenameStarted', source)
        self.assertEqual(source.count('catalog batch write started:'), 2)
        self.assertEqual(source.count('catalog batch write finished after'), 2)
        for line in source.splitlines():
            if 'log.debug' in line:
                if '\\(' in line:
                    self.assertEqual(line.count('\\('), line.count('privacy: .public'))
                for sensitive in ('localIdentifier', 'originalFilename', 'descriptor', 'name)', 'path'):
                    self.assertNotIn(sensitive, line)
        self.assertEqual(release.count('asset.extendedMetadata.originalFilename'), 1)
        self.assertEqual(release.count('try store.gallery(["op": "catalog", "items": page])'), 2)
        self.assertIn('if page.count == 200', release)
        self.assertNotRegex(release, r'sleep|timeout')


    def test_original_filename_prefetch_covers_scan_and_observer_changes(self):
        source = IOS.read_text()
        options = block(source, 'static func fetchOptions()')
        self.assertIn('options.prefetchAssetExtendedMetadata = true', options)
        self.assertIn('return options', options)
        scan = block(source, 'static func scan(store:')
        self.assertIn('PHAsset.fetchAssets(with: fetchOptions())', scan)
        tracking = block(source, 'func ensureTracking()')
        self.assertIn('PHAsset.fetchAssets(with: LocalCatalog.fetchOptions())', tracking)
        changes = block(source, 'func photoLibraryDidChange(')
        self.assertIn('self.assets = details.fetchResultAfterChanges', changes)
        self.assertIn('details.insertedObjects + details.changedObjects', changes)
        for section in [scan, block(source, 'static func patch(store:')]:
            self.assertIn('let name = asset.extendedMetadata.originalFilename ?? "媒体"', section)
            self.assertNotIn('PHAssetResource.assetResources', section)
            self.assertIn('"id": asset.localIdentifier, "name": name', section)
        scanner = (args.root / 'clients/ios/Xszc/PhotoScanner.swift').read_text()
        self.assertIn('PHAssetResource.assetResources(for: asset)', scanner)
        self.assertIn('filename: resource.originalFilename', scanner)
        selected = (args.root / 'clients/ios/Xszc/SelectedMedia.swift').read_text()
        self.assertIn('descriptor["id"] as? String', selected)
        self.assertIn('PhotoScanner().enqueue(asset: asset', selected)
        self.assertIn('iOS: "27.0"', (args.root / 'clients/ios/project.yml').read_text())


if __name__ == '__main__':
    unittest.main(argv=[__file__, *remaining])
