package org.sarmg.ana

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
        } catch (failure: ConfigStorageException) {
            // Configuration corruption or an unknown commit is not a transient network retry.
            Result.failure(workDataOf("failure_kind" to "local_config",
                "message" to (failure.message ?: "本地配置不可用，原任务已保留").take(512)))
        } finally {
            backupMutex.unlock()
        }
    }

    private suspend fun runBackup(): Result {
        val config = SecureConfig(applicationContext)
        val profile = inputData.getString("profile") ?: return Result.failure()
        val settings = config.settingsSnapshot()
        val credentials = settings.connection
        val preferences = settings.preferences
        if (credentials.token.isBlank() || profile != credentials.profile) return Result.failure()
        fun isCurrent() = !isStopped && config.connection() == credentials
        val automatic = inputData.getString(BackupScheduler.SOURCE_KEY) == BackupScheduler.SOURCE_AUTOMATIC
        if (automatic && !preferences.auto) return Result.success()
        val session = TransferStore.open(applicationContext, profile)
        val handle = session.handle
        val staging = session.paths.staging
        val telemetrySession = TransferTelemetry.uploads.begin(profile)
        var snapshot = BackupSnapshot(state = "running", message = "正在准备备份", lastRunAt = System.currentTimeMillis())
        try {
            val api = BackupApi(credentials.server, credentials.token)
            check(credentials.token.isNotBlank() && credentials.accountId.isNotBlank() && credentials.deviceId.isNotBlank()) {
                "请重新输入用户名和密码登录"
            }
            val accountId = credentials.accountId
            val deviceId = credentials.deviceId
            TransferStore.command(handle, "bind", JSONObject().put("server", credentials.server)
                .put("account_id", accountId).put("device_id", deviceId))
            var resourceCount = 0
            suspend fun drain() {
                while (isCurrent() && resourceCount < MAX_UPLOAD_ITEMS) {
                    val envelope = MobileContractV1.requireEnvelope(NativeBridgeV1.next(handle, staging.path))
                    if (envelope.isNull("value")) break
                    val job = envelope.getJSONObject("value")
                    MobileContractV1.requireIdentity(job)
                    try {
                        snapshot = snapshot.copy(message = "正在上传：${job.getJSONObject("request").getString("filename")}")
                        publish(config, credentials, snapshot, "上传资源", 0, 0)
                        snapshot = snapshot.copy(uploadedBytes = snapshot.uploadedBytes + uploadJob(api, handle, job, config, credentials, telemetrySession))
                        resourceCount++
                    } catch (error: ConfigStorageException) {
                        // The job can already be active before the first progress snapshot is written.
                        try {
                            TransferStore.command(handle, "fail_upload", JSONObject().put("job_id", job.getString("job_id"))
                                .put("error", "本地配置不可用，原任务可在配置恢复后继续").put("retryable", true)
                                .put("retry_after_ms", JSONObject.NULL))
                        } catch (queueFailure: Exception) { error.addSuppressed(queueFailure) }
                        throw error
                    }
                }
            }
            // Always consume durable work first. Manual entry never invokes the automatic scanner.
            drain()
            // Re-read after each photo: new selections join the active queue in FIFO order.
            while (true) {
                if (!isCurrent()) return Result.failure()
                val item = TransferStore.command(handle, "next_pending_item") as? JSONObject ?: break
                if (resourceCount >= MAX_UPLOAD_ITEMS) return Result.retry()
                val batchId = item.getString("batch_id")
                if (TransferStore.items(handle, batchId).length() == 0) continue
                try {
                    snapshot = snapshot.copy(message = "正在准备所选原始文件")
                    publish(config, credentials, snapshot, "准备", 0, 0)
                    SelectedMedia.prepare(applicationContext, handle, staging, batchId, item)
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: ConfigStorageException) { throw error }
                catch (error: Exception) {
                    TransferStore.setItem(handle, batchId, item.getString("id"), "blocked",
                        error.message ?: "需要重新授权访问")
                }
                drain()
            }
            var automaticMore = false
            if (automatic) {
                if (!preferences.camera && preferences.albums.isEmpty()) {
                    config.saveSnapshot(snapshot.copy(state = "waiting", message = "请在设置中选择自动备份相册"), credentials)
                    return Result.success()
                }
                if (!hasMediaAccess(preferences)) {
                    config.saveSnapshot(snapshot.copy(state = "waiting", message = "自动扫描需要重新授权访问"), credentials)
                    return Result.success()
                }
                if (resourceCount >= MAX_UPLOAD_ITEMS) return Result.retry()
                // Walk each collection once, draining each staged asset before reading another.
                fun checkCurrent() {
                    if (!isCurrent()) throw kotlinx.coroutines.CancellationException("账户已退出或切换")
                }
                val scan = MediaScanner(applicationContext).scan(handle, staging, ScanOptions(
                    preferences.photos, preferences.videos, preferences.camera, preferences.albums, MAX_SCAN_ITEMS),
                    checkCurrent = ::checkCurrent, drain = {
                        drain()
                        if (resourceCount >= MAX_UPLOAD_ITEMS) throw RunWindowComplete()
                    })
                if (scan.error != null) {
                    config.saveSnapshot(snapshot.copy(state = "waiting", message = scan.error), credentials)
                    return Result.success()
                }
                val replaceMembers = !scan.limitReached && scan.libraryStable &&
                    preferences.photos && preferences.videos && hasFullMediaAccess(preferences)
                for ((id, album) in scan.albums) {
                    checkCurrent()
                    api.syncAlbum(id, album.name, album.sourceAssetIds, replaceMembers)
                }
                automaticMore = scan.limitReached
            }
            if (!isCurrent()) return Result.failure()
            val stats = MobileContractV1.requireEnvelope(NativeBridgeV1.stats(handle)).getJSONObject("value")
            val waiting = automaticMore || stats.getLong("retry_wait") > 0 || stats.getLong("ready") > 0 || stats.getLong("discovered") > 0
            config.saveSnapshot(snapshot.copy(state = if (waiting) "waiting" else "success",
                message = if (waiting) "等待网络或系统重试；逐项结果见传输列表" else "本轮处理结束；逐项结果见传输列表",
                lastSuccessAt = if (waiting) 0 else System.currentTimeMillis()), credentials)
            return if (waiting) Result.retry() else Result.success()
        } catch (_: RunWindowComplete) {
            return Result.retry()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: ConfigStorageException) {
            throw error
        } catch (error: Exception) {
            if (isCurrent()) config.saveSnapshot(snapshot.copy(state = "waiting",
                message = error.message ?: "等待网络或系统调度"), credentials)
            return if (uploadFailurePolicy(error).retryable) Result.retry() else Result.failure()
        } finally { TransferTelemetry.uploads.end(profile, telemetrySession) }
    }

    private class RunWindowComplete : Exception()

    private fun uploadJob(api: BackupApi, handle: Long, job: JSONObject, config: SecureConfig,
        credentials: SecureConfig.Connection, telemetrySession: String): Long {
        val profile = credentials.profile
        val jobId = job.getString("job_id")
        val request = job.getJSONObject("request")
        val asset = request.getString("source_asset_id")
        val total = request.getLong("content_size").coerceAtLeast(1)
        TransferTelemetry.uploads.resource(profile, telemetrySession, asset)
        fun ensureActive() {
            if (isStopped || config.connection() != credentials ||
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
                ensureActive()
                confirmedBytes += file.length()
                uploadedBytes += file.length()
                MobileContractV1.requireEnvelope(NativeBridgeV1.markPart(handle, jobId, index))
            }
            ensureActive()
            val completed = api.complete(uploadId)
            ensureActive()
            val manifest = api.manifest(completed.getString("resource_id"))
            ensureActive()
            TransferStore.receipt(handle, job, manifest, completed)
            return uploadedBytes
        } catch (error: UploadSupersededException) {
            TransferStore.command(handle, "supersede", JSONObject().put("job_id", jobId))
            return 0L
        } catch (error: ConfigStorageException) {
            // drain preserves the active job and doWork stops with an explicit configuration failure.
            throw error
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
        credentials: SecureConfig.Connection,
        snapshot: BackupSnapshot,
        stage: String,
        completed: Int,
        total: Int,
    ) {
        config.saveSnapshot(snapshot, credentials)
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

    private fun hasMediaAccess(preferences: SecureConfig.BackupPreferences): Boolean {
        val partial = ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED
        val photosAllowed = !preferences.photos || partial || ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.READ_MEDIA_IMAGES,
        ) == PackageManager.PERMISSION_GRANTED
        val videosAllowed = !preferences.videos || partial || ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        ) == PackageManager.PERMISSION_GRANTED
        return photosAllowed && videosAllowed
    }

    private fun hasFullMediaAccess(preferences: SecureConfig.BackupPreferences): Boolean {
        return (!preferences.photos || ContextCompat.checkSelfPermission(applicationContext,
            android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) &&
            (!preferences.videos || ContextCompat.checkSelfPermission(applicationContext,
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
