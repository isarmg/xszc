package org.sarmg.xszc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.File

class BackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        backupMutex.lock()
        return try {
            withContext(Dispatchers.IO) { runBackup() }
        } finally {
            backupMutex.unlock()
        }
    }

    private suspend fun runBackup(): Result {
        val config = SecureConfig(applicationContext)
        val profile = inputData.getString("profile") ?: return Result.failure()
        val credentials = config.connection()
        if (!config.isLoggedIn || profile != credentials.profile) return Result.failure()
        val automatic = inputData.getString(BackupScheduler.SOURCE_KEY) == BackupScheduler.SOURCE_AUTOMATIC
        if (automatic && !config.autoBackup) return Result.success()
        val session = TransferStore.open(applicationContext, profile)
        val handle = session.handle
        val staging = session.paths.staging
        val telemetrySession = TransferTelemetry.uploads.begin(profile)
        var snapshot = BackupSnapshot(state = "running", message = "正在准备备份", lastRunAt = System.currentTimeMillis())
        try {
            val api = BackupApi(credentials.server, credentials.token)
            check(credentials.token.isNotBlank() && credentials.accountId.isNotBlank() && credentials.deviceId.isNotBlank()) {
                "客户端需要使用当前实例授权码重新配对"
            }
            val accountId = credentials.accountId
            val deviceId = credentials.deviceId
            TransferStore.command(handle, "bind", JSONObject().put("server", credentials.server)
                .put("account_id", accountId).put("device_id", deviceId))
            var resourceCount = 0
            suspend fun drain() {
                while (!isStopped && config.isLoggedIn && config.profile == profile && resourceCount < MAX_UPLOAD_ITEMS) {
                    val envelope = MobileContractV1.requireEnvelope(NativeBridgeV1.next(handle, staging.path))
                    if (envelope.isNull("value")) break
                    val job = envelope.getJSONObject("value")
                    MobileContractV1.requireIdentity(job)
                    snapshot = snapshot.copy(message = "正在上传：${job.getJSONObject("request").getString("filename")}")
                    publish(config, snapshot, "上传资源", 0, 0)
                    snapshot = snapshot.copy(uploadedBytes = snapshot.uploadedBytes + uploadJob(api, handle, job, profile, telemetrySession))
                    resourceCount++
                }
            }
            // Always consume durable work first. Manual entry never invokes the automatic scanner.
            drain()
            // Re-read after each photo: new selections join the active queue in FIFO order.
            while (true) {
                if (isStopped || !config.isLoggedIn || config.profile != profile) return Result.failure()
                val item = TransferStore.command(handle, "next_pending_item") as? JSONObject ?: break
                if (resourceCount >= MAX_UPLOAD_ITEMS) return Result.retry()
                val batchId = item.getString("batch_id")
                if (TransferStore.items(handle, batchId).length() == 0) continue
                try {
                    snapshot = snapshot.copy(message = "正在准备所选原始文件")
                    publish(config, snapshot, "准备", 0, 0)
                    SelectedMedia.prepare(applicationContext, handle, staging, batchId, item)
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) {
                    TransferStore.setItem(handle, batchId, item.getString("id"), "blocked",
                        error.message ?: "需要重新授权访问")
                }
                drain()
            }
            var automaticMore = false
            if (automatic) {
                if (!config.cameraOnly && config.selectedAlbumIds.isEmpty()) {
                    config.saveSnapshot(snapshot.copy(state = "waiting", message = "请在设置中选择自动备份相册"))
                    return Result.success()
                }
                if (!hasMediaAccess(config)) {
                    config.saveSnapshot(snapshot.copy(state = "waiting", message = "自动扫描需要重新授权访问"))
                    return Result.success()
                }
                // Bounded scan/export windows: at most one asset is staged before draining.
                for (attempt in 0 until MAX_SCAN_ITEMS) {
                    if (isStopped || !config.isLoggedIn || config.profile != profile) return Result.failure()
                    if (resourceCount >= MAX_UPLOAD_ITEMS) return Result.retry()
                    val scan = MediaScanner(applicationContext).scan(handle, staging, ScanOptions(
                        config.backupPhotos, config.backupVideos, config.cameraOnly, config.selectedAlbumIds, 1))
                    drain()
                    if (scan.error != null) {
                        config.saveSnapshot(snapshot.copy(state = "waiting", message = scan.error))
                        return Result.success()
                    }
                    val replaceMembers = !scan.limitReached && !config.cameraOnly &&
                        config.backupPhotos && config.backupVideos && hasFullMediaAccess(config)
                    for ((id, album) in scan.albums) {
                        api.syncAlbum(id, album.name, album.sourceAssetIds, replaceMembers)
                    }
                    automaticMore = scan.limitReached
                    if (!scan.limitReached) break
                }
            }
            if (config.profile != profile) return Result.failure()
            val stats = MobileContractV1.requireEnvelope(NativeBridgeV1.stats(handle)).getJSONObject("value")
            val waiting = automaticMore || stats.getLong("retry_wait") > 0 || stats.getLong("ready") > 0 || stats.getLong("discovered") > 0
            config.saveSnapshot(snapshot.copy(state = if (waiting) "waiting" else "success",
                message = if (waiting) "等待网络或系统重试；逐项结果见传输列表" else "本轮处理结束；逐项结果见传输列表",
                lastSuccessAt = if (waiting) 0 else System.currentTimeMillis()))
            return if (waiting) Result.retry() else Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (config.profile == profile) config.saveSnapshot(snapshot.copy(state = "waiting",
                message = error.message ?: "等待网络或系统调度"))
            return if (uploadFailurePolicy(error).retryable) Result.retry() else Result.failure()
        } finally { TransferTelemetry.uploads.end(profile, telemetrySession) }
    }

    private fun uploadJob(api: BackupApi, handle: Long, job: JSONObject, profile: String, telemetrySession: String): Long {
        val jobId = job.getString("job_id")
        val request = job.getJSONObject("request")
        val asset = request.getString("source_asset_id")
        val total = request.getLong("content_size").coerceAtLeast(1)
        TransferTelemetry.uploads.resource(profile, telemetrySession, asset)
        fun ensureActive() {
            val currentConfig = SecureConfig(applicationContext)
            if (isStopped || !currentConfig.isLoggedIn || currentConfig.profile != inputData.getString("profile") ||
                TransferStore.command(handle, "active", JSONObject().put("job_id", jobId)) != true) {
                throw kotlinx.coroutines.CancellationException("本次上传已取消或账户已切换")
            }
        }
        try {
            ensureActive()
            val create = api.createUpload(job.getJSONObject("request").toString(), job.getString("generation_id"), job.getString("upload_operation_header"))
            ensureActive()
            val disposition = create.getString("disposition")
            if (disposition == "complete") {
                val manifest = api.manifest(create.getString("resource_id"))
                ensureActive()
                TransferStore.receipt(handle, job, manifest)
                return 0L
            }
            check(disposition == "upload") { "服务器返回无效的上传状态" }
            val uploadId = create.getString("upload_id")
            MobileContractV1.requireEnvelope(NativeBridgeV1.markUpload(handle, jobId, uploadId))
            val files = mutableMapOf<Int, File>()
            val localParts = job.getJSONArray("local_parts")
            for (position in 0 until localParts.length()) {
                val part = localParts.getJSONObject(position)
                files[part.getInt("index")] = File(part.getString("path"))
            }
            val missing = create.getJSONArray("missing_parts")
            val missingIndices = (0 until missing.length()).map { missing.getInt(it) }.toSet()
            val parts = request.getJSONArray("parts")
            var confirmedBytes = (0 until parts.length()).map(parts::getJSONObject)
                .filter { it.getInt("index") !in missingIndices }.sumOf { it.getLong("size") }
            TransferTelemetry.uploads.report(profile, telemetrySession, asset, 0, confirmedBytes.toDouble() / total)
            var uploadedBytes = 0L
            for (position in 0 until missing.length()) {
                val index = missing.getInt(position)
                val file = files[index] ?: error("缺少本地分块 $index")
                ensureActive()
                val baseline = confirmedBytes
                var lastSent = 0L
                api.uploadPart(uploadId, index, file) { sent ->
                    ensureActive()
                    val current = sent.coerceAtLeast(0)
                    val delta = if (current >= lastSent) current - lastSent else current
                    lastSent = current
                    TransferTelemetry.uploads.report(profile, telemetrySession, asset, delta, (baseline + current).toDouble() / total)
                }
                confirmedBytes += file.length()
                uploadedBytes += file.length()
                MobileContractV1.requireEnvelope(NativeBridgeV1.markPart(handle, jobId, index))
            }
            ensureActive()
            val completed = api.complete(uploadId)
            val manifest = api.manifest(completed.getString("resource_id"))
            ensureActive()
            TransferStore.receipt(handle, job, manifest, completed)
            return uploadedBytes
        } catch (error: UploadSupersededException) {
            TransferStore.command(handle, "supersede", JSONObject().put("job_id", jobId))
            return 0L
        } catch (error: Exception) {
            val policy = uploadFailurePolicy(error)
            TransferStore.command(handle, "fail_upload", JSONObject().put("job_id", jobId)
                .put("error", error.message ?: "上传失败").put("retryable", policy.retryable)
                .put("retry_after_ms", policy.retryAfterMs ?: JSONObject.NULL))
            if (policy.retryable) throw error
            // Permanent refusal is visible per item; it must not block later work.
            return 0L
        } finally { TransferTelemetry.uploads.resource(profile, telemetrySession, null) }
    }

    private suspend fun publish(
        config: SecureConfig,
        snapshot: BackupSnapshot,
        stage: String,
        completed: Int,
        total: Int,
    ) {
        config.saveSnapshot(snapshot)
        setProgress(
            workDataOf(
                PROGRESS_STAGE to stage,
                PROGRESS_MESSAGE to snapshot.message,
                PROGRESS_COMPLETED to completed,
                PROGRESS_TOTAL to total,
                PROGRESS_ITEM to snapshot.currentItem,
            ),
        )
        setForeground(createForegroundInfo(snapshot.message, completed, total))
    }

    private fun hasMediaAccess(config: SecureConfig): Boolean {
        val partial = ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED
        val photosAllowed = !config.backupPhotos || partial || ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.READ_MEDIA_IMAGES,
        ) == PackageManager.PERMISSION_GRANTED
        val videosAllowed = !config.backupVideos || partial || ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        ) == PackageManager.PERMISSION_GRANTED
        return photosAllowed && videosAllowed
    }

    private fun hasFullMediaAccess(config: SecureConfig): Boolean {
        return (!config.backupPhotos || ContextCompat.checkSelfPermission(applicationContext,
            android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) &&
            (!config.backupVideos || ContextCompat.checkSelfPermission(applicationContext,
                android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)
    }

    private fun createForegroundInfo(message: String, completed: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "媒体备份", NotificationManager.IMPORTANCE_LOW))
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("媒体备份")
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(maxOf(total, 1), completed.coerceAtMost(maxOf(total, 1)), total <= 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val PROGRESS_STAGE = "stage"
        const val PROGRESS_MESSAGE = "message"
        const val PROGRESS_COMPLETED = "completed"
        const val PROGRESS_TOTAL = "total"
        const val PROGRESS_ITEM = "item"
        const val OUTPUT_MESSAGE = "result_message"

        private const val CHANNEL_ID = MobileContractV1.NOTIFICATION_CHANNEL
        private const val NOTIFICATION_ID = 4102
        private const val MAX_SCAN_ITEMS = 40
        private const val MAX_UPLOAD_ITEMS = 60
        private val backupMutex = Mutex()
    }
}
