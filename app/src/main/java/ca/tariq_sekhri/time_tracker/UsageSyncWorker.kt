package ca.tariq_sekhri.time_tracker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.Calendar

class UsageSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        UsageLogImporter(applicationContext).importNow()
        val sync = SyncManager(applicationContext)
        if (!sync.isConfigured()) return Result.success()
        val result = sync.syncBlocking()
        return if (result.shouldRetry) Result.retry() else Result.success()
    }
}

object UsageSyncScheduler {
    private const val PERIODIC_WORK_NAME = "android-usage-import-and-sync"
    private const val IMMEDIATE_WORK_NAME = "android-usage-import-and-sync-now"

    fun schedule(context: Context) {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.WEEK_OF_YEAR, 1)
        }
        val recap = PeriodicWorkRequestBuilder<WeeklyRecapWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("weekly-recap", ExistingPeriodicWorkPolicy.KEEP, recap)
        val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UsageSyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
