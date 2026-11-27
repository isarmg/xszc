package org.sarmg.ana

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal class CaptureLocation private constructor(val latitude: Double, val longitude: Double) {
    fun label(precision: Int = 2): String = String.format(Locale.ROOT, "%1$.${precision}f, %2$.${precision}f", latitude, longitude)

    fun putIn(metadata: JSONObject) {
        metadata.put("capture_latitude", latitude).put("capture_longitude", longitude)
    }

    companion object {
        private val videoCoordinates = Regex("^([+-]\\d{1,2}(?:\\.\\d+)?)([+-]\\d{1,3}(?:\\.\\d+)?)(?:[+-]\\d+(?:\\.\\d+)?)?/?$")

        fun of(latitude: Double?, longitude: Double?): CaptureLocation? =
            if (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                CaptureLocation(latitude, longitude)
            } else null

        fun fromVideoMetadata(text: String?): CaptureLocation? {
            val match = text?.let(videoCoordinates::matchEntire) ?: return null
            return of(match.groupValues[1].toDoubleOrNull(), match.groupValues[2].toDoubleOrNull())
        }

        fun fromMetadata(metadata: JSONObject?): CaptureLocation? {
            val latitude = (metadata?.opt("capture_latitude") as? Number)?.toDouble()
            val longitude = (metadata?.opt("capture_longitude") as? Number)?.toDouble()
            return of(latitude, longitude)
        }
    }
}

internal val RemoteAsset.captureLocation: CaptureLocation?
    get() = resources.asSequence().filter { it.role != "thumbnail" }
        .mapNotNull { CaptureLocation.fromMetadata(it.metadata) }.firstOrNull()

internal fun hasMediaLocationAccess(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun readCaptureLocation(context: Context, uri: Uri, staged: File, mediaKind: String): CaptureLocation? {
    if (!hasMediaLocationAccess(context)) return null
    return try {
        if (mediaKind == "video") {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(staged.absolutePath)
                CaptureLocation.fromVideoMetadata(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
            }
        } else {
            context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))?.use { input ->
                val coordinates = FloatArray(2)
                if (ExifInterface(input).getLatLong(coordinates)) {
                    CaptureLocation.of(coordinates[0].toDouble(), coordinates[1].toDouble())
                } else null
            }
        }
    } catch (_: Exception) {
        // Absent, redacted or unreadable GPS metadata does not block the media backup.
        null
    }
}
