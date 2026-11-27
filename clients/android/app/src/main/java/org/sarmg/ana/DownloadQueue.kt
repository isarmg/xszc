package org.sarmg.ana

import android.content.Context
import kotlinx.coroutines.*

/** Session-owned queue: leaving the cloud screen does not interrupt an original download. */
internal class SessionDownloadQueue(
    private val scope: CoroutineScope,
    private val restore: suspend (RemoteAsset, String, () -> Unit) -> Unit,
) {
    private data class Request(val asset: RemoteAsset, val profile: String, val transfer: String, val current: () -> Boolean,
        val save: suspend (RemoteAsset, String, () -> Unit) -> Unit)
    private val pending = ArrayDeque<Request>()
    private val active = mutableMapOf<String, Request>()
    private var worker: Job? = null
    private var generation = 0
    private var telemetry: Pair<String, String>? = null

    @Synchronized fun enqueue(profile: String, assets: List<RemoteAsset>, current: () -> Boolean,
        save: suspend (RemoteAsset, String, () -> Unit) -> Unit = restore): Int {
        if (!current()) return 0
        var added = 0
        for (asset in assets.distinctBy { it.id }) {
            val key = "$profile/${asset.id}"
            if (key in active) continue
            val resource = asset.resources.firstOrNull { it.role == "primary" }
                ?: asset.resources.firstOrNull { it.role != "thumbnail" }
            val request = Request(asset, profile, DownloadTransfers.start(profile,
                resource?.filename ?: "媒体", resource?.contentSize ?: 0, "等待下载", asset), current, save)
            active[key] = request
            pending.addLast(request); added++
        }
        if (worker == null && pending.isNotEmpty()) {
            val epoch = generation
            worker = scope.launch(start = CoroutineStart.LAZY) { drain(epoch) }.also { it.start() }
        }
        return added
    }

    @Synchronized fun cancelPending() {
        generation++
        worker?.cancel(); worker = null
        active.values.forEach { DownloadTransfers.update(it.transfer, phase = "已取消") }
        active.clear(); pending.clear()
        telemetry?.let { TransferTelemetry.downloads.end(it.first, it.second) }; telemetry = null
    }

    private suspend fun drain(epoch: Int) {
        val job = currentCoroutineContext().job
        var currentTelemetry: Pair<String, String>? = null
        try { while (true) {
            val request = synchronized(this) {
                if (epoch != generation) return
                if (pending.isEmpty()) { worker = null; return }
                pending.removeFirst()
            }
            fun checkCurrent() {
                job.ensureActive()
                if (synchronized(this) { epoch != generation } || !request.current())
                    throw CancellationException("账户已退出或切换")
            }
            try {
                checkCurrent()
                synchronized(this) {
                    if (epoch != generation) throw CancellationException("下载队列已停止")
                    if (currentTelemetry?.first != request.profile) {
                        currentTelemetry?.let { TransferTelemetry.downloads.end(it.first, it.second) }
                        currentTelemetry = request.profile to TransferTelemetry.downloads.begin(request.profile)
                        telemetry = currentTelemetry
                    }
                    TransferTelemetry.downloads.resource(request.profile, currentTelemetry!!.second, request.transfer)
                }
                DownloadTransfers.update(request.transfer, phase = "正在下载")
                request.save(request.asset, request.transfer, ::checkCurrent)
            } catch (error: CancellationException) {
                DownloadTransfers.update(request.transfer, phase = "已取消")
                if (!job.isActive) throw error
            } catch (error: Exception) {
                DownloadTransfers.update(request.transfer, phase = "下载失败：${error.message ?: "未知错误"}")
            } finally {
                synchronized(this) {
                    val key = "${request.profile}/${request.asset.id}"
                    if (active[key] === request) active.remove(key)
                }
            }
        } } finally {
            synchronized(this) {
                currentTelemetry?.let { TransferTelemetry.downloads.end(it.first, it.second) }
                if (telemetry == currentTelemetry) telemetry = null
            }
        }
    }
}

internal object DownloadQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queue: SessionDownloadQueue? = null

    @Synchronized fun enqueue(context: Context, config: SecureConfig, assets: List<RemoteAsset>): Int {
        val credentials = config.connection()
        if (credentials.token.isBlank()) return 0
        val app = context.applicationContext
        // Each request captures its own connection; an account change invalidates pending writes.
        val current = { config.connection() == credentials }
        val target = queue ?: SessionDownloadQueue(scope) { _, _, _ -> error("缺少下载连接") }.also { queue = it }
        return target.enqueue(credentials.profile, assets, current) { asset, transfer, checkCurrent ->
            RemoteLibrary.restore(app, BackupApi(credentials.server, credentials.token), asset,
                credentials.profile, transfer, checkCurrent)
        }
    }
    @Synchronized fun cancelPending() { queue?.cancelPending() }
}
