package org.sarmg.xszc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalGallerySelectionTest {
    private fun selection() = LocalGallerySelection<String> { it }.apply { active = true }

    @Test fun bulkCompletionRetainsExistingChoicesAndAllReadRows() = runBlocking {
        val state = selection()
        state.toggle("existing")
        val read = CompletableDeferred<List<String>>()
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            state.selectScope({ true }) { read.await() }
        }
        assertEquals(setOf("existing"), state.values.keys)
        read.complete(listOf("a", "b", "a"))
        worker.join()
        assertEquals(setOf("existing", "a", "b"), state.values.keys)
        assertTrue(state.active)
    }

    @Test fun cancelAndReenterRejectsLateBulkRows() = runBlocking {
        for (reenter in listOf(false, true)) {
            val state = selection()
            val read = CompletableDeferred<List<String>>()
            val worker = launch(start = CoroutineStart.UNDISPATCHED) {
                state.selectScope({ true }) { read.await() }
            }
            state.reset()
            if (reenter) { state.active = true; state.toggle("new") }
            read.complete(listOf("old"))
            worker.join()
            assertEquals(if (reenter) setOf("new") else emptySet<String>(), state.values.keys)
            assertEquals(reenter, state.active)
        }
    }

    @Test fun individualChoiceAndPendingIntentSupersedeBulkRead() = runBlocking {
        for (pending in listOf(false, true)) {
            val state = selection()
            val read = CompletableDeferred<List<String>>()
            val worker = launch(start = CoroutineStart.UNDISPATCHED) {
                state.selectScope({ true }) { read.await() }
            }
            // The screen invalidates when a single-item read starts, before it resolves.
            if (pending) state.invalidate() else state.toggle("new")
            read.complete(listOf("old"))
            worker.join()
            assertEquals(if (pending) emptySet<String>() else setOf("new"), state.values.keys)
        }
    }

    @Test fun queryChangeRejectsLateCompletion() = runBlocking {
        val state = selection()
        var query = "photos"
        val read = CompletableDeferred<List<String>>()
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            state.selectScope({ query == "photos" }) { read.await() }
        }
        query = "videos"
        read.complete(listOf("photo"))
        worker.join()
        assertTrue(state.values.isEmpty())
    }

    @Test fun cancelledWorkerCannotCommitEvenIfReadReturnsRows() = runBlocking {
        val state = selection()
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            state.selectScope({ true }) {
                coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                listOf("cancelled")
            }
        }
        worker.join()
        assertTrue(worker.isCancelled)
        assertTrue(state.values.isEmpty())
    }

    @Test fun failedReadPreservesExistingSelection() = runBlocking {
        val state = selection()
        state.toggle("existing")
        try {
            state.selectScope({ true }) { throw IllegalStateException("fixture") }
            fail("read failure must reach the screen")
        } catch (expected: IllegalStateException) {
            assertEquals("fixture", expected.message)
        }
        assertEquals(setOf("existing"), state.values.keys)
    }
}
