package org.sarmg.ana

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.time.LocalDate
import java.time.ZoneOffset

internal enum class PhotoDiscovery(val title: String) {
    TIMELINE("时间线"), MEMORIES("回忆录"), PLACES("地点");

    fun filters(base: CloudFilters, today: LocalDate): CloudFilters = when (this) {
        TIMELINE -> base
        MEMORIES -> base.copy(captureMonth = today.monthValue,
            captureBefore = today.withDayOfYear(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        PLACES -> base.copy(hasLocation = true)
    }
}

internal fun openCaptureLocation(context: Context, location: CaptureLocation) {
    val coordinates = "${location.latitude},${location.longitude}"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=$coordinates"))
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "${location.label(4)}，没有可用的地图应用", Toast.LENGTH_LONG).show()
    }
}
