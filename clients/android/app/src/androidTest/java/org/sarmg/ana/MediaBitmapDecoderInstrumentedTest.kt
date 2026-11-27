package org.sarmg.ana

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class MediaBitmapDecoderInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)

    private fun fixture(orientation: Int, width: Int = 96, height: Int = 64, body: (File) -> Unit) {
        val file = File.createTempFile("orientation-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, colors[(if (y >= height / 2) 2 else 0) + (if (x >= width / 2) 1 else 0)])
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            body(file)
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test fun allExifOrientationsPreserveExpectedDimensionsAndCornerOrder() {
        val expected = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2),
        )
        for (orientation in 1..8) fixture(orientation) { file ->
            val decoded = requireNotNull(MediaBitmapDecoder.decode(128) { file.inputStream() })
            try {
                assertEquals(if (orientation >= 5) 64 else 96, decoded.width)
                assertEquals(if (orientation >= 5) 96 else 64, decoded.height)
                val actual = listOf(decoded.getPixel(8, 8), decoded.getPixel(decoded.width - 9, 8),
                    decoded.getPixel(8, decoded.height - 9), decoded.getPixel(decoded.width - 9, decoded.height - 9))
                actual.zip(expected[orientation - 1]).forEach { (pixel, index) ->
                    val target = colors[index]
                    assertTrue("orientation=$orientation red", kotlin.math.abs(Color.red(pixel) - Color.red(target)) < 40)
                    assertTrue("orientation=$orientation green", kotlin.math.abs(Color.green(pixel) - Color.green(target)) < 40)
                    assertTrue("orientation=$orientation blue", kotlin.math.abs(Color.blue(pixel) - Color.blue(target)) < 40)
                }
            } finally { decoded.recycle() }
        }
    }

    @Test fun localFullscreenPreviewUsesExifOrientation() {
        fixture(ExifInterface.ORIENTATION_ROTATE_90) { file ->
            val decoded = runBlocking {
                LocalThumbnailCache.image(context, Uri.fromFile(file).toString(), "photo", 0, 2048, preview = true)
            }
            assertNotNull(decoded)
            // preview=true bypasses cache lookup/insertion; the test owns this bitmap.
            decoded!!.let { bitmap ->
                try { assertEquals(64, bitmap.width); assertEquals(96, bitmap.height) }
                finally { bitmap.recycle() }
            }
        }
    }

    @Test fun generatedThumbnailsBakeOrientationWithoutModifyingOriginals() {
        val expected = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2),
        )
        for (orientation in 1..8) fixture(orientation) { file ->
            val originalBytes = file.readBytes()
            val thumbnail = requireNotNull(MediaScanner(context).createThumbnail(file, "photo", context.cacheDir))
            try {
                // Raw BitmapFactory must now be sufficient: orientation is baked into the pixels.
                val bitmap = requireNotNull(BitmapFactory.decodeFile(thumbnail.path))
                try {
                    assertEquals(if (orientation >= 5) 64 else 96, bitmap.width)
                    assertEquals(if (orientation >= 5) 96 else 64, bitmap.height)
                    val actual = listOf(bitmap.getPixel(8, 8), bitmap.getPixel(bitmap.width - 9, 8),
                        bitmap.getPixel(8, bitmap.height - 9), bitmap.getPixel(bitmap.width - 9, bitmap.height - 9))
                    actual.zip(expected[orientation - 1]).forEach { (pixel, index) ->
                        val target = colors[index]
                        assertTrue("thumbnail orientation=$orientation red", kotlin.math.abs(Color.red(pixel) - Color.red(target)) < 40)
                        assertTrue("thumbnail orientation=$orientation green", kotlin.math.abs(Color.green(pixel) - Color.green(target)) < 40)
                        assertTrue("thumbnail orientation=$orientation blue", kotlin.math.abs(Color.blue(pixel) - Color.blue(target)) < 40)
                    }
                    // ExifInterface supplies UNDEFINED for a JPEG without an orientation tag.
                    // Baked pixels must require no further transform; explicit NORMAL is also valid.
                    val outputOrientation = ExifInterface(thumbnail.path)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                    assertTrue("thumbnail must not request another orientation transform: $outputOrientation",
                        outputOrientation == ExifInterface.ORIENTATION_UNDEFINED || outputOrientation == ExifInterface.ORIENTATION_NORMAL)
                    assertArrayEquals(originalBytes, file.readBytes())
                } finally { bitmap.recycle() }
            } finally { thumbnail.delete() }
        }
    }

    @Test fun generatedPortraitThumbnailKeepsThe512PixelLimit() {
        fixture(ExifInterface.ORIENTATION_ROTATE_90, width = 1400, height = 800) { file ->
            val thumbnail = requireNotNull(MediaScanner(context).createThumbnail(file, "photo", context.cacheDir))
            try {
                val bitmap = requireNotNull(BitmapFactory.decodeFile(thumbnail.path))
                try {
                    assertEquals(512, bitmap.height)
                    assertTrue(bitmap.width < bitmap.height)
                    assertTrue(bitmap.width <= 512)
                } finally { bitmap.recycle() }
            } finally { thumbnail.delete() }
        }
    }

    @Test fun metadataIoFailureFallsBackAndClosesBothDecodeStreams() {
        fixture(ExifInterface.ORIENTATION_NORMAL) { file ->
            var opens = 0
            var closes = 0
            val bitmap = requireNotNull(MediaBitmapDecoder.decode(128) {
                opens++
                if (opens == 2) throw IOException("fixture metadata unavailable")
                object : FilterInputStream(file.inputStream()) {
                    override fun close() { closes++; super.close() }
                }
            })
            try {
                assertEquals(96, bitmap.width); assertEquals(64, bitmap.height)
                assertEquals(3, opens); assertEquals(2, closes)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun metadataCancellationIsNotSwallowed() {
        fixture(ExifInterface.ORIENTATION_NORMAL) { file ->
            var opens = 0
            var closes = 0
            try {
                MediaBitmapDecoder.decode(128) {
                    opens++
                    if (opens == 2) throw CancellationException("fixture cancelled")
                    object : FilterInputStream(file.inputStream()) {
                        override fun close() { closes++; super.close() }
                    }
                }
                fail("Cancellation must stop decoding before a third stream is opened")
            } catch (_: CancellationException) {
                assertEquals(2, opens); assertEquals(1, closes)
            }
        }
    }

    @Test fun pngWithoutExifStillDecodesAndRespectsSampleLimit() {
        val file = File.createTempFile("no-exif-", ".png", context.cacheDir)
        val original = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = requireNotNull(MediaBitmapDecoder.decode(128) { file.inputStream() })
            try { assertEquals(128, decoded.width); assertEquals(64, decoded.height) }
            finally { decoded.recycle() }
        } finally { original.recycle(); file.delete() }
    }
}
