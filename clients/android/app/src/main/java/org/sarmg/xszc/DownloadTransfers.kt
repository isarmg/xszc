package org.sarmg.xszc

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Active-session download observations; interrupted saves clean their pending MediaStore row. */
internal object DownloadTransfers {
    data class Entry(val id: String, val profile: String, val name: String, val bytes: Long, val total: Long, val phase: String, val asset: RemoteAsset? = null) {
        val isCompleted get() = phase == "已保存到手机"
    }
    val rows = MutableStateFlow<List<Entry>>(emptyList())
    @Synchronized fun start(profile: String, name: String, total: Long, phase: String = "正在下载", asset: RemoteAsset? = null): String {
        val id = UUID.randomUUID().toString()
        rows.value = rows.value + Entry(id, profile, name, 0, total, phase, asset)
        return id
    }
    @Synchronized fun update(id: String, bytes: Long? = null, phase: String? = null) {
        val previous = rows.value.firstOrNull { it.id == id } ?: return
        val received = if (bytes == null) previous.bytes else maxOf(previous.bytes, bytes)
        if (bytes != null && previous.phase == "正在下载" && (phase == null || phase == "正在下载")) {
            TransferTelemetry.downloads.received(previous.profile, id, received - previous.bytes,
                received.toDouble() / previous.total.coerceAtLeast(1))
        }
        rows.value = rows.value.map { if (it.id == id) it.copy(bytes = received, phase = phase ?: it.phase) else it }
    }
    @Synchronized fun clearAllCompleted(profile: String) {
        rows.value = rows.value.filterNot { it.profile == profile && it.isCompleted }
    }
}
