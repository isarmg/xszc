package org.sarmg.ana

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.IOException
import java.io.InputStream

/** BitmapFactory decodes stored pixels; apply the camera's display orientation. */
internal object MediaBitmapDecoder {
    fun decode(maxSize: Int, open: () -> InputStream?): Bitmap? {
        require(maxSize > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = open() ?: return null
        boundsInput.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        // PNGs and other formats without readable EXIF still decode normally.
        val orientation = try {
            open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
        val bitmap = open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(90f); matrix.postScale(1f, -1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }
}
