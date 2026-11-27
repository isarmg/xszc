package org.sarmg.ana

import org.junit.Assert.*
import org.junit.Test

class TransferPresentationTest {
    @Test fun activeResourceFractionIsIncludedWhileHistoryStaysComplete() {
        val pending = UploadPhoto("pending", "batch", "photo", "content://photo", 3, 1, 2, "queued", null)
        assertEquals(0.5f, pending.progress(0.5), 0.0001f)
        val done = pending.copy(id = "done", complete = 3)
        assertEquals(0.75f, uploadProgress(listOf(done, pending), mapOf("content://photo" to 0.5)), 0.0001f)
        assertEquals(1f / 3f, pending.progress(Double.NaN), 0.0001f)
    }
    @Test fun uploadingPreparationAndErrorsNeverReportCompleteProgress() {
        val blocked = UploadPhoto("blocked", "batch", "photo", null, 2, 2, 1, "blocked", "export failed")
        val done = blocked.copy(id = "done", state = "queued", error = null)
        val preparing = blocked.copy(id = "pending", resources = 1, complete = 1, originalsExpected = 3, state = "pending", error = null)
        assertFalse(blocked.isCompleted); assertEquals(0.99f, blocked.progress, 0.0001f)
        assertEquals(0.25f, preparing.progress, 0.0001f)
        assertTrue(done.isCompleted); assertEquals(1f, done.progress, 0f)
        assertEquals(0.625f, uploadProgress(listOf(done, preparing)), 0.0001f)
    }
    @Test fun downloadsWeightBytesAndRetainUnknownSizeJobsUntilSaved() {
        val done = DownloadTransfers.Entry("done", "profile", "large", 0, 900, "已保存到手机")
        val pending = DownloadTransfers.Entry("pending", "profile", "small", 50, 100, "正在下载")
        assertEquals(0.95f, downloadProgress(listOf(done, pending)), 0.0001f)
        val unknown = pending.copy(total = 0, bytes = 0)
        assertEquals(0f, downloadProgress(listOf(unknown)), 0f)
        assertEquals(1f, downloadProgress(listOf(unknown.copy(phase = "已保存到手机"))), 0f)
        assertEquals(0f, downloadProgress(emptyList()), 0f)
    }
}
