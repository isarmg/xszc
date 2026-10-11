import XCTest

final class NavigationTests: XCTestCase {
    @MainActor func testVideoOpensFullScreenPausedAndSupportsSeekingSpeedAndBackupSelection() {
        continueAfterFailure = false
        let monitor = monitorFullPhotoAccess()
        defer { removeUIInterruptionMonitor(monitor) }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-gallery_grid_columns", "3"]
        app.launch()
        defer { app.terminate() }
        requestPhotoAccess(app)
        let tile = app.descendants(matching: .any).matching(identifier: "media.tile.layout-video.mp4").firstMatch
        for _ in 0..<30 {
            if tile.isHittable { break }
            app.scrollViews["gallery.scroll"].swipeUp(velocity: .fast)
        }
        XCTAssertTrue(tile.isHittable, "Seed the video fixture with scripts/seed-ios-ui.sh")
        tile.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let preview = app.descendants(matching: .any).matching(identifier: "video.preview").firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        XCTAssertEqual(preview.frame.width, app.frame.width, accuracy: 1)
        XCTAssertEqual(preview.frame.height, app.frame.height, accuracy: 1)
        let play = app.buttons["video.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 15))
        XCTAssertEqual(play.label, "播放")
        XCTAssertFalse(app.staticTexts["layout-video.mp4"].exists)
        XCTAssertFalse(app.staticTexts["状态待确认"].exists)
        XCTAssertFalse(app.buttons["不再自动备份此项目"].exists)
        app.buttons["video.speed"].tap()
        app.buttons["1.5×"].tap()
        XCTAssertEqual(app.buttons["video.speed"].value as? String, "1.5×")
        XCTAssertEqual(play.label, "播放", "Changing speed must keep the video paused")
        let progress = app.descendants(matching: .any).matching(identifier: "video.progress").firstMatch
        XCTAssertTrue(progress.exists)
        progress.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let seeked = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value BEGINSWITH %@", "00:06"), object: progress)
        let seekResult = XCTWaiter.wait(for: [seeked], timeout: 5)
        if seekResult != .completed {
            // Capture the failed observation before continueAfterFailure stops
            // the test. The displayed target alone does not prove AVPlayer sought.
            screenshot("video-seek-timeout")
            let progressExists = progress.exists
            let details = [
                "Expected progress value beginning 00:06 after the midpoint tap",
                "Progress exists: \(progressExists)",
                "Progress value: \(progressExists ? String(describing: progress.value) : "missing")",
                "Progress frame: \(progressExists ? String(describing: progress.frame) : "missing")",
                "Visible time labels: \(preview.staticTexts.allElementsBoundByIndex.map(\.label).filter { $0.contains(":") })",
                "Preview: \(preview.debugDescription)",
            ].joined(separator: "\n")
            print("XSZC_UI_VIDEO_SEEK_TIMEOUT\n\(details)")
            let diagnostic = XCTAttachment(string: details)
            diagnostic.name = "video-seek-timeout-details"
            diagnostic.lifetime = .keepAlways
            add(diagnostic)
        }
        XCTAssertEqual(seekResult, .completed)
        screenshot("video-fullscreen-paused")
        // Keep the 1.5x selection/paused checks above separate from the
        // auto-hide interaction. The 12s fixture ends after only 8s at 1.5x;
        // XCTest can need longer to observe hiding, reveal, and tap pause.
        // At 0.5x the same real clip gives this bounded interaction 24s.
        app.buttons["video.speed"].tap()
        app.buttons["0.5×"].tap()
        XCTAssertEqual(app.buttons["video.speed"].value as? String, "0.5×")
        XCTAssertEqual(play.label, "播放", "Changing speed must keep the video paused")
        progress.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5)).tap()
        let rewound = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value BEGINSWITH %@", "00:00"), object: progress)
        XCTAssertEqual(XCTWaiter.wait(for: [rewound], timeout: 5), .completed)
        play.tap()
        // Slow accessibility queries can outlast the 3.5s auto-hide window.
        // Verify playback after explicitly revealing the controls below.
        let hidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: play)
        XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 8), .completed)
        preview.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).tap()
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(play.label, "暂停", "Revealing controls must not change playback")
        play.tap()
        let paused = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "播放"), object: play)
        XCTAssertEqual(XCTWaiter.wait(for: [paused], timeout: 5), .completed)
        // Read advancement while paused, after the controls have been revealed.
        // The progress control intentionally disappears during automatic hiding.
        // XCTest's polling can skip a particular second. Require
        // actual advancement instead of catching one transient timestamp.
        let advanced = XCTNSPredicateExpectation(predicate: NSPredicate(
            format: "exists == true AND NOT (value BEGINSWITH %@)", "00:00"), object: progress)
        XCTAssertEqual(XCTWaiter.wait(for: [advanced], timeout: 5), .completed)
        app.buttons["video.backup"].tap()
        XCTAssertEqual(app.buttons["video.backup"].label, "取消选择备份")
        play.tap()
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(play.label, "播放")
        app.buttons["video.close"].tap()
        XCTAssertTrue(app.buttons["gallery.cancel-selection"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["gallery.selected-count"].label, "已选 1 项")
    }

    @MainActor func testCompleteDirectoryScrollsPastPageBoundariesAndKeepsPositionAcrossRefresh() throws {
        continueAfterFailure = false
        let monitor = monitorFullPhotoAccess()
        defer { removeUIInterruptionMonitor(monitor) }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-gallery_grid_columns", "8"]
        app.launch()
        defer { app.terminate() }
        requestPhotoAccess(app)
        let initialIdle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: app.buttons["gallery.filter"])
        XCTAssertEqual(XCTWaiter.wait(for: [initialIdle], timeout: 30), .completed)
        let loaded = app.staticTexts["gallery.loaded-count"]
        let count = Int(loaded.label.split(separator: " ").dropFirst().first ?? "0") ?? 0
        guard count > 150 else { throw XCTSkip("Seed the complete gallery with scripts/seed-ios-ui.sh") }
        XCTAssertFalse(app.buttons["gallery.load-more"].exists)
        let older = app.descendants(matching: .any).matching(identifier: "media.tile.gallery-page-001.png").firstMatch
        let scroll = app.scrollViews["gallery.scroll"]
        for _ in 0..<30 {
            if older.isHittable { break }
            scroll.swipeUp(velocity: .fast)
        }
        XCTAssertTrue(older.isHittable, app.debugDescription)
        let position = older.frame.minY
        let total = loaded.label
        XCUIDevice.shared.press(.home)
        app.activate()
        let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: app.buttons["gallery.filter"])
        XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 20), .completed)
        XCTAssertTrue(older.isHittable)
        XCTAssertEqual(older.frame.minY, position, accuracy: 2)
        XCTAssertEqual(loaded.label, total)
        XCTAssertFalse(app.buttons["gallery.load-more"].exists)
    }

    @MainActor func testTransfersStartWithTwoCollapsedCapsules() {
        continueAfterFailure = false
        let monitor = monitorFullPhotoAccess()
        defer { removeUIInterruptionMonitor(monitor) }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        defer { app.terminate() }
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.alerts.firstMatch.waitForExistence(timeout: 2) {
            _ = allowFullPhotoAccess(springboard.alerts.firstMatch)
        }
        app.tabBars.buttons["传输"].tap()
        let upload = app.buttons["transfers.upload"]
        let download = app.buttons["transfers.download"]
        XCTAssertTrue(upload.waitForExistence(timeout: 5))
        XCTAssertTrue(download.exists)
        XCTAssertTrue((upload.value as? String)?.contains("已收起") == true)
        XCTAssertTrue((download.value as? String)?.contains("已收起") == true)
        XCTAssertEqual(upload.frame.width, download.frame.width, accuracy: 1)
        for id in ["transfers.upload", "transfers.download"] {
            let speed = app.staticTexts["\(id).speed"]
            let progress = app.progressIndicators["\(id).progress"]
            XCTAssertTrue(speed.exists)
            XCTAssertEqual(speed.label, "0 KB/s")
            XCTAssertLessThan(progress.frame.maxX, speed.frame.minX)
            XCTAssertLessThan(speed.frame.maxX, app.buttons[id].frame.maxX)
        }
        XCTAssertFalse(app.buttons["继续待处理任务"].exists)
        XCTAssertFalse(app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "transfers.photo.")).firstMatch.exists)
        screenshot("transfers-collapsed-capsules")
    }
    @MainActor func testLoginRemainsReachableFromGalleryAndSettings() {
        continueAfterFailure = false
        let monitor = monitorFullPhotoAccess()
        defer { removeUIInterruptionMonitor(monitor) }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-gallery_grid_columns", "3"]
        app.launch()
        defer { app.terminate() }
        let pairing = app.buttons["gallery.backup"]
        XCTAssertTrue(pairing.waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["account.open"].exists)
        requestPhotoAccess(app)
        screenshot("local-gallery-loading")
        // The complete directory contains hundreds of photos. Exercise the
        // first visible photo instead of assuming a named fixture is on screen.
        let photo = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND identifier ENDSWITH %@", "media.tile.", ".png")).firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 15))
        XCTAssertTrue(photo.isHittable)
        let photoIdentifier = photo.identifier
        let selectionIdentifier = photoIdentifier.replacingOccurrences(of: "media.tile.", with: "media.select.")
        photo.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let preview = app.descendants(matching: .any).matching(identifier: "gallery.preview").firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        XCTAssertEqual(preview.frame.width, app.frame.width, accuracy: 1)
        screenshot("photo-fullscreen")
        preview.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let selectMode = app.buttons["gallery.select"]
        XCTAssertTrue(selectMode.waitForExistence(timeout: 30))
        XCTAssertFalse(app.buttons[selectionIdentifier].exists)
        let filterHeight = app.buttons["gallery.filter"].frame.height
        selectMode.tap()
        XCTAssertEqual(app.buttons["gallery.filter"].frame.height, filterHeight, accuracy: 1)
        XCTAssertGreaterThan(app.buttons["gallery.select-all"].frame.midX, app.frame.midX)
        XCTAssertGreaterThan(app.buttons["gallery.cancel-selection"].frame.midX, app.frame.midX)
        let selection = app.buttons[selectionIdentifier]
        XCTAssertTrue(selection.waitForExistence(timeout: 30), app.debugDescription)
        let tile = app.descendants(matching: .any).matching(identifier: photoIdentifier).firstMatch
        XCTAssertTrue(tile.exists)
        XCTAssertGreaterThan(selection.frame.midX, tile.frame.midX)
        XCTAssertLessThan(selection.frame.midY, tile.frame.midY)
        selection.tap()
        XCTAssertTrue(app.staticTexts["已选 1 项"].waitForExistence(timeout: 5))
        XCTAssertTrue(pairing.isHittable)
        screenshot("local-gallery-selected")
        selection.tap()
        app.buttons["gallery.cancel-selection"].tap()
        screenshot("local-gallery")
        pairing.tap()
        assertGlassLogin(app)
        XCTAssertFalse(app.buttons["account.submit"].isEnabled)
        app.secureTextFields["pairing.authorization-code"].tap()
        app.secureTextFields["pairing.authorization-code"].typeText("test-instance-code")
        XCTAssertFalse(app.buttons["account.submit"].isEnabled)
        screenshot("account-login-keyboard")
        app.buttons["account.cancel"].tap()
        app.tabBars.buttons["设置"].tap()
        XCTAssertTrue(app.buttons["settings.login"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["登录 / 切换账户"].exists)
        XCTAssertFalse(app.buttons["保存备份偏好"].exists)
        XCTAssertFalse(app.buttons["settings.logout"].exists)
        screenshot("settings")
        app.buttons["settings.login"].tap()
        assertGlassLogin(app)
        screenshot("account-login")
        app.textFields["account.server"].tap()
        app.textFields["account.server"].typeText("http://invalid.example")
        app.secureTextFields["pairing.authorization-code"].tap()
        app.secureTextFields["pairing.authorization-code"].typeText("test-instance-code")
        XCTAssertTrue(app.buttons["account.submit"].isEnabled)
        app.buttons["account.submit"].tap()
        let error = app.staticTexts["account.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertTrue(error.label.contains("请输入 HTTPS 服务器根地址"))
        app.buttons["account.cancel"].tap()
        app.tabBars.buttons["云端"].tap()
        XCTAssertFalse(app.navigationBars.firstMatch.exists)
        XCTAssertFalse(app.buttons["account.open"].exists)
        screenshot("cloud")
        app.tabBars.buttons["传输"].tap()
        XCTAssertFalse(app.navigationBars.firstMatch.exists)
        XCTAssertFalse(app.buttons["account.open"].exists)
        screenshot("transfers")
    }
    @MainActor func testPhotoPermissionsRemainReachableOnlyInSettings() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        defer { app.terminate() }
        XCTAssertFalse(app.buttons["gallery.add"].exists)
        XCTAssertFalse(app.buttons["gallery.photo-access"].exists)
        app.tabBars.buttons["设置"].tap()
        let photoSettings = app.buttons["settings.photo-access"]
        XCTAssertTrue(photoSettings.waitForExistence(timeout: 5))
        photoSettings.tap()
        XCTAssertFalse(app.alerts["照片权限设置"].exists)
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 10))
        app.activate()
    }

    @MainActor func testGalleryKeepsControlsFixedWhileDatesScroll() {
        continueAfterFailure = false
        let monitor = monitorFullPhotoAccess()
        defer { removeUIInterruptionMonitor(monitor) }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-gallery_grid_columns", "2"]
        app.launch()
        defer { app.terminate() }
        requestPhotoAccess(app)
        let filter = app.buttons["gallery.filter"]
        let select = app.buttons["gallery.select"]
        let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: filter)
        XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 60), .completed)
        let filterFrame = filter.frame
        let selectFrame = select.frame
        let firstDate = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "gallery.date.")).firstMatch
        // A firstMatch query can resolve to a different header after scrolling.
        let date = app.staticTexts[firstDate.identifier]
        XCTAssertTrue(date.isHittable)
        screenshot("gallery-before-scroll")
        let scroll = app.scrollViews["gallery.scroll"]
        XCTAssertTrue(scroll.exists)
        let start = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.65))
        let end = start.withOffset(CGVector(dx: 0, dy: -220))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.5)
        XCTAssertTrue(filter.isHittable)
        XCTAssertTrue(select.isHittable)
        XCTAssertEqual(filter.frame.minY, filterFrame.minY, accuracy: 1)
        XCTAssertEqual(select.frame.minY, selectFrame.minY, accuracy: 1)
        XCTAssertFalse(date.isHittable, "日期应随照片滚出屏幕，而不是固定在顶部")
        screenshot("gallery-after-scroll")
    }

    @MainActor private func assertGlassLogin(_ app: XCUIApplication) {
        let dialog = app.descendants(matching: .any).matching(identifier: "account.dialog").firstMatch
        XCTAssertTrue(dialog.waitForExistence(timeout: 5))
        XCTAssertEqual(dialog.frame.midY, app.frame.midY, accuracy: 55)
        XCTAssertTrue(app.textFields["account.server"].exists)
        XCTAssertTrue(app.secureTextFields["pairing.authorization-code"].exists)
        XCTAssertTrue(app.buttons["account.submit"].exists)
        XCTAssertTrue(app.buttons["account.cancel"].exists)
        XCTAssertFalse(app.sheets.firstMatch.exists)
    }

    @MainActor func testGalleryAutoRefreshesAfterSystemPhotoLibraryChanges() {
        continueAfterFailure = false
        let monitor = monitorFullPhotoAccess()
        defer { removeUIInterruptionMonitor(monitor) }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        defer { app.terminate() }
        requestPhotoAccess(app)
        let count = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "共 ")).firstMatch
        XCTAssertTrue(count.waitForExistence(timeout: 10))
        let initialCount = Int(count.label.split(separator: " ")[1])!
        screenshot("gallery-waiting-for-new-photo")
        // The host harness adds a real synthetic photo only after the baseline is observed.
        print("XSZC_UI_READY_FOR_PHOTO_CHANGE")
        // Hosted PhotoKit import/catalog work can outlive addmedia returning.
        // Keep a bounded wait and require the actual new count and named tile.
        XCTAssertTrue(app.staticTexts["共 \(initialCount + 1) 项"].waitForExistence(timeout: 240), app.debugDescription)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "media.tile.layout-auto-refresh.png").firstMatch.exists)
        screenshot("gallery-auto-refreshed")
    }

    @MainActor func testSettingsSwitchesPersistImmediately() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        defer { app.terminate() }
        app.tabBars.buttons["设置"].tap()
        let charging = app.switches["后台仅充电时运行"]
        XCTAssertTrue(charging.waitForExistence(timeout: 5))
        let initialValue = charging.value as? String
        charging.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        let updatedValue = charging.value as? String
        XCTAssertNotEqual(updatedValue, initialValue)
        XCTAssertFalse(app.buttons["保存备份偏好"].exists)
        app.terminate()
        app.launch()
        app.tabBars.buttons["设置"].tap()
        XCTAssertTrue(charging.waitForExistence(timeout: 5))
        XCTAssertEqual(charging.value as? String, updatedValue)
        charging.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
    }

    @MainActor func testGalleryPinchChangesPhotoSizeAndRespectsLimits() {
        continueAfterFailure = false
        let monitor = monitorFullPhotoAccess()
        defer { removeUIInterruptionMonitor(monitor) }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-gallery_grid_columns", "3"]
        app.launch()
        defer { app.terminate() }
        requestPhotoAccess(app)
        XCTAssertFalse(app.buttons["调整缩略图大小"].exists)
        let tile = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "media.tile.")).firstMatch
        let initialWidth = tile.frame.width
        pinchGallery(app, scale: 2, velocity: 1)
        XCTAssertGreaterThan(tile.frame.width, initialWidth * 1.3)
        screenshot("gallery-pinch-enlarged")
        for _ in 0..<4 {
            if abs(tile.frame.width - (app.frame.width - 32)) < 3 { break }
            pinchGallery(app, scale: 2, velocity: 1)
        }
        let fullWidth = tile.frame.width
        XCTAssertEqual(fullWidth, app.frame.width - 32, accuracy: 3)
        pinchGallery(app, scale: 1.5, velocity: 1)
        XCTAssertEqual(tile.frame.width, fullWidth, accuracy: 1)
        let expectedMinimum = (app.frame.width - 32 - 21) / 8
        for _ in 0..<6 {
            if abs(tile.frame.width - expectedMinimum) < 3 { break }
            pinchGallery(app, scale: 0.5, velocity: -2)
        }
        let minimumWidth = tile.frame.width
        XCTAssertEqual(minimumWidth, expectedMinimum, accuracy: 3)
        screenshot("gallery-pinch-reduced")
        XCTAssertTrue(app.buttons["gallery.select"].isHittable)
    }

    @MainActor private func pinchGallery(_ app: XCUIApplication, scale: CGFloat, velocity: CGFloat) {
        // The ScrollView extends behind the floating glass tab bar. Pinching
        // its entire AX frame can touch a tab. Small thumbnails also leave
        // too little room for XCTest's two fingers; use a wide date header
        // inside the unobstructed viewport once photos become small.
        let top = app.buttons["gallery.filter"].frame.maxY + 8
        let bottom = app.tabBars.firstMatch.frame.minY - 8
        let photos = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "media.tile.")).allElementsBoundByIndex
        if let photo = photos.first(where: {
            $0.frame.width >= 100 && $0.frame.minY >= top && $0.frame.maxY <= bottom
        }) {
            photo.pinch(withScale: scale, velocity: velocity)
        } else {
            let headers = app.descendants(matching: .any)
                .matching(NSPredicate(format: "identifier BEGINSWITH %@", "gallery.section.")).allElementsBoundByIndex
            guard let header = headers.first(where: {
                $0.frame.width >= 200 && $0.frame.minY >= top && $0.frame.maxY <= bottom
            }) else {
                XCTFail("No unobstructed gallery pinch target: \(app.debugDescription)")
                return
            }
            header.pinch(withScale: scale, velocity: velocity)
        }
    }

    @MainActor private func requestPhotoAccess(_ app: XCUIApplication) {
        let tile = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "media.tile.")).firstMatch
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        // Fresh hosted simulators may still be indexing the large imported
        // library after addmedia returns and permission is first granted.
        let deadline = ProcessInfo.processInfo.systemUptime + 240
        while ProcessInfo.processInfo.systemUptime < deadline {
            _ = allowFullPhotoAccess(springboard.alerts.firstMatch)
            if tile.exists { return }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        XCTFail("Authorized photos did not load: \(app.debugDescription)")
    }

    @MainActor private func monitorFullPhotoAccess() -> NSObjectProtocol {
        addUIInterruptionMonitor(withDescription: "xszc Full Photo Library access") { alert in
            self.allowFullPhotoAccess(alert)
        }
    }

    @MainActor private func allowFullPhotoAccess(_ alert: XCUIElement) -> Bool {
        guard alert.exists else { return false }
        let title = alert.label
        let englishTitle = title.lowercased()
        let english = englishTitle.contains("full access") && englishTitle.contains("photo library")
        let chinese = title.contains("完全访问") && (title.contains("照片") || title.contains("图库"))
        guard title.contains("媒体备份"), english || chinese else { return false }
        let allow = alert.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "允许完全访问", "Allow Full Access")).firstMatch
        guard allow.exists else { return false }
        allow.tap()
        return true
    }

    @MainActor private func screenshot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? screenshot.pngRepresentation.write(to: documents.appendingPathComponent(name + ".png"))
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
