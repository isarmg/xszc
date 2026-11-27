package org.sarmg.ana

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.UUID

/** Monotonic, two-second byte window. Confirmed server parts never count as network traffic. */
internal data class TransferRate private constructor(private val samples: List<Sample>) {
    private data class Sample(val time: Double, val bytes: Double)
    constructor() : this(emptyList())

    fun start(at: Double = now()): TransferRate = if (at.isFinite()) TransferRate(listOf(Sample(at, 0.0))) else TransferRate()
    fun record(bytes: Long, at: Double = now()): TransferRate {
        val last = samples.lastOrNull() ?: return this
        if (bytes <= 0 || !at.isFinite()) return this
        val time = maxOf(at, last.time)
        val next = samples.toMutableList()
        val sample = Sample(time, last.bytes + bytes)
        if (time == last.time && next.size > 1) next[next.lastIndex] = sample else next.add(sample)
        while (next.size > 2 && next[1].time <= time - 2) next.removeAt(0)
        return TransferRate(next)
    }
    fun bytesPerSecond(at: Double = now()): Double {
        val first = samples.firstOrNull() ?: return 0.0
        val last = samples.last()
        if (!at.isFinite() || at < last.time || at - last.time >= 2) return 0.0
        val start = maxOf(first.time, at - 2)
        val duration = at - start
        if (duration < 0.1) return 0.0
        var baseline = first.bytes
        if (start > first.time) for ((before, after) in samples.zipWithNext()) {
            if (after.time <= start) { baseline = after.bytes; continue }
            if (before.time < start) baseline = before.bytes + (after.bytes - before.bytes) * (start - before.time) / (after.time - before.time)
            break
        }
        return maxOf(0.0, (last.bytes - baseline) / duration)
    }
    companion object {
        fun now(): Double = System.nanoTime() / 1_000_000_000.0
        fun formatted(bytesPerSecond: Double): String {
            if (!bytesPerSecond.isFinite() || bytesPerSecond <= 0) return "0 KB/s"
            val (divisor, unit) = when {
                bytesPerSecond >= 1_000_000_000 -> 1_000_000_000.0 to "GB/s"
                bytesPerSecond >= 1_000_000 -> 1_000_000.0 to "MB/s"
                else -> 1_000.0 to "KB/s"
            }
            return String.format(Locale.getDefault(), "%.1f %s", bytesPerSecond / divisor, unit)
        }
    }
}

internal class TransferMonitor {
    data class State(val session: String, val asset: String? = null, val progress: Double = 0.0, val rate: TransferRate = TransferRate())
    val states = MutableStateFlow<Map<String, State>>(emptyMap())
    @Synchronized fun begin(profile: String, at: Double = TransferRate.now()): String = UUID.randomUUID().toString().also {
        states.value = states.value + (profile to State(it, rate = TransferRate().start(at)))
    }
    @Synchronized fun resource(profile: String, session: String, asset: String?) {
        val current = states.value[profile]?.takeIf { it.session == session } ?: return
        states.value = states.value + (profile to current.copy(asset = asset, progress = 0.0))
    }
    @Synchronized fun report(profile: String, session: String, asset: String, bytes: Long, progress: Double, at: Double = TransferRate.now()) {
        val current = states.value[profile]?.takeIf { it.session == session && it.asset == asset } ?: return
        val fraction = if (progress.isFinite()) progress.coerceIn(0.0, 0.99) else 0.0
        states.value = states.value + (profile to current.copy(progress = fraction, rate = current.rate.record(bytes, at)))
    }
    @Synchronized fun received(profile: String, asset: String, bytes: Long, progress: Double) {
        val session = states.value[profile]?.session ?: return
        report(profile, session, asset, bytes, progress)
    }
    @Synchronized fun end(profile: String, session: String) {
        if (states.value[profile]?.session == session) states.value = states.value - profile
    }
    @Synchronized fun reset() { states.value = emptyMap() }
}

internal object TransferTelemetry {
    val uploads = TransferMonitor()
    val downloads = TransferMonitor()
    fun reset() { uploads.reset(); downloads.reset() }
}
