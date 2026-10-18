import XCTest
@testable import Xszc

@MainActor final class LocalGallerySelectionTests: XCTestCase {
    @MainActor private final class ReadGate {
        let entered: XCTestExpectation
        var continuation: CheckedContinuation<[String], Error>?
        init(_ entered: XCTestExpectation) { self.entered = entered }
        func read() async throws -> [String] {
            try await withCheckedThrowingContinuation {
                continuation = $0
                entered.fulfill()
            }
        }
        func complete(_ rows: [String]) {
            continuation?.resume(returning: rows)
            continuation = nil
        }
    }

    private func selection() -> LocalGallerySelection<String> {
        let state = LocalGallerySelection<String>(id: { $0 })
        state.active = true
        return state
    }

    func testBulkCompletionRetainsExistingChoicesAndAllReadRows() async throws {
        let state = selection()
        state.toggle("existing")
        let gate = ReadGate(expectation(description: "bulk read suspended"))
        let worker = Task { try await state.selectScope(isCurrentQuery: { true }, read: gate.read) }
        await fulfillment(of: [gate.entered], timeout: 2)
        XCTAssertEqual(Set(state.values.keys), ["existing"])
        gate.complete(["a", "b", "a"])
        try await worker.value
        XCTAssertEqual(Set(state.values.keys), ["existing", "a", "b"])
        XCTAssertTrue(state.active)
    }

    func testCancelAndReenterRejectsLateBulkRows() async throws {
        for reenter in [false, true] {
            let state = selection()
            let gate = ReadGate(expectation(description: "old bulk read suspended"))
            let worker = Task { try await state.selectScope(isCurrentQuery: { true }, read: gate.read) }
            await fulfillment(of: [gate.entered], timeout: 2)
            state.reset()
            if reenter { state.active = true; state.toggle("new") }
            gate.complete(["old"])
            try await worker.value
            XCTAssertEqual(Set(state.values.keys), reenter ? ["new"] : [])
            XCTAssertEqual(state.active, reenter)
        }
    }

    func testIndividualChoiceAndPendingIntentSupersedeBulkRead() async throws {
        for pending in [false, true] {
            let state = selection()
            let gate = ReadGate(expectation(description: "bulk read suspended"))
            let worker = Task { try await state.selectScope(isCurrentQuery: { true }, read: gate.read) }
            await fulfillment(of: [gate.entered], timeout: 2)
            if pending { state.invalidate() } else { state.toggle("new") }
            gate.complete(["old"])
            try await worker.value
            XCTAssertEqual(Set(state.values.keys), pending ? [] : ["new"])
        }
    }

    func testQueryChangeRejectsLateCompletion() async throws {
        let state = selection()
        var query = "photos"
        let gate = ReadGate(expectation(description: "bulk read suspended"))
        let worker = Task { try await state.selectScope(isCurrentQuery: { query == "photos" }, read: gate.read) }
        await fulfillment(of: [gate.entered], timeout: 2)
        query = "videos"
        gate.complete(["photo"])
        try await worker.value
        XCTAssertTrue(state.values.isEmpty)
    }

    func testCancelledWorkerCannotCommitEvenIfReadReturnsRows() async throws {
        let state = selection()
        let gate = ReadGate(expectation(description: "bulk read suspended"))
        let worker = Task { try await state.selectScope(isCurrentQuery: { true }, read: gate.read) }
        await fulfillment(of: [gate.entered], timeout: 2)
        worker.cancel()
        gate.complete(["cancelled"])
        do { try await worker.value; XCTFail("expected cancellation") }
        catch is CancellationError { }
        XCTAssertTrue(state.values.isEmpty)
    }

    func testFailedReadPreservesExistingSelection() async throws {
        struct FixtureFailure: Error {}
        let state = selection()
        state.toggle("existing")
        do {
            try await state.selectScope(isCurrentQuery: { true }) { throw FixtureFailure() }
            XCTFail("read failure must reach the screen")
        } catch is FixtureFailure { }
        XCTAssertEqual(Set(state.values.keys), ["existing"])
    }
}
