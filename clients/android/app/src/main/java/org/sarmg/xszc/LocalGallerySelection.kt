package org.sarmg.xszc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Main-thread selection state, shared by the screen and its execution tests. */
internal class LocalGallerySelection<T>(private val id: (T) -> String) {
    var values by mutableStateOf<Map<String, T>>(emptyMap())
    var active by mutableStateOf(false)
    var generation by mutableIntStateOf(0)
        private set

    fun invalidate() { generation++ }

    fun reset() {
        invalidate()
        values = emptyMap()
        active = false
    }

    fun toggle(value: T) {
        invalidate()
        val key = id(value)
        values = if (key in values) values - key else values + (key to value)
    }

    suspend fun selectScope(isCurrentQuery: () -> Boolean, read: suspend () -> List<T>) {
        if (!active) return
        val request = generation
        val chosen = read()
        currentCoroutineContext().ensureActive()
        if (active && request == generation && isCurrentQuery()) {
            values = values + chosen.associateBy(id)
        }
    }
}
