package org.sarmg.ana

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

object BackupScheduler {
    const val TAG = MobileContractV1.WORK_TAG
    const val SOURCE_KEY = "backup_source"
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_AUTOMATIC = "automatic"

    private const val NOW_WORK = MobileContractV1.NOW_WORK
    private const val PERIODIC_WORK = MobileContractV1.PERIODIC_WORK
    private const val PERIOD_HOURS = 6L

    fun syncAutomatic(context: Context, config: SecureConfig) {
        val settings = config.settingsSnapshot()
        val manager = WorkManager.getInstance(context)
        if (!allowsAutomatic(settings)) {
            manager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest(settings, 0L),
        )
    }

    fun enqueueNow(context: Context, config: SecureConfig, automatic: Boolean = false): UUID {
        val settings = config.settingsSnapshot()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints(settings.preferences))
            .setInputData(workDataOf(SOURCE_KEY to (if (automatic) SOURCE_AUTOMATIC else SOURCE_MANUAL), "profile" to settings.connection.profile))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("$NOW_WORK-${settings.connection.profile}", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        return request.id
    }

    fun stopCurrent(context: Context, config: SecureConfig) {
        val settings = config.settingsSnapshot()
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(TAG)
        if (allowsAutomatic(settings)) {
            manager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                periodicRequest(settings, PERIOD_HOURS),
            )
        }
    }

    private fun allowsAutomatic(settings: SecureConfig.SettingsSnapshot): Boolean = with(settings.preferences) {
        auto && settings.connection.token.isNotBlank() && (photos || videos) && (camera || albums.isNotEmpty())
    }

    private fun periodicRequest(settings: SecureConfig.SettingsSnapshot, initialDelayHours: Long) =
        PeriodicWorkRequestBuilder<BackupWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints(settings.preferences))
            .setInputData(workDataOf(SOURCE_KEY to SOURCE_AUTOMATIC, "profile" to settings.connection.profile))
            .setInitialDelay(initialDelayHours, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

    private fun constraints(preferences: SecureConfig.BackupPreferences): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (preferences.wifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(preferences.charging)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()
}
