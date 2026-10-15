package org.sarmg.xszc

import android.database.MatrixCursor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectedMediaVersionInstrumentedTest {
    private fun version(seconds: Long, generation: Long): SelectedMedia.MediaVersion =
        MatrixCursor(arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.DATE_MODIFIED)).use {
            // Deliberately reorder the columns: version reads use their names.
            it.addRow(arrayOf(generation, seconds))
            SelectedMedia.readVersion(it)
        }

    @Test fun currentMetadataProvidesTheTimestampInsteadOfAQueuedSnapshot() {
        val queuedVersion = version(10, 20)
        val currentVersion = version(11, 21)
        assertEquals(11_000L, currentVersion.modifiedMs)
        assertNotEquals(queuedVersion, currentVersion)
        assertEquals(currentVersion, version(11, 21))
    }

    @Test fun generationDetectsEditsDuringCopyEvenWithinTheSameSecond() {
        val before = version(10, 20)
        val after = version(10, 21)
        assertEquals(before.modifiedMs, after.modifiedMs)
        assertNotEquals(before, after)
    }

    @Test fun zeroIsAValidVersion() {
        assertEquals(SelectedMedia.MediaVersion(0L, 0L), version(0L, 0L))
    }

    @Test fun unknownVersionFieldsCannotSilentlyBecomeZero() {
        for (values in listOf(arrayOf<Any?>(null, 20L), arrayOf<Any?>(10L, null))) {
            MatrixCursor(arrayOf(MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.GENERATION_MODIFIED)).use {
                it.addRow(values)
                assertThrows(IllegalStateException::class.java) { SelectedMedia.readVersion(it) }
            }
        }
    }

    @Test fun missingMediaCannotSupplyAVersion() {
        MatrixCursor(arrayOf(MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.GENERATION_MODIFIED)).use {
            assertThrows(IllegalStateException::class.java) { SelectedMedia.readVersion(it) }
        }
    }
}
