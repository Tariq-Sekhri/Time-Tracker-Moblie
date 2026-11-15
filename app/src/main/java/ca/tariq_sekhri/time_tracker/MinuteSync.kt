package ca.tariq_sekhri.time_tracker

import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Syncs with the server once a minute for as long as the tracker service runs.
 * [UsageSyncScheduler] stays scheduled as the fallback for when the service is stopped.
 */
class MinuteSync(context: Context) {
    private val context = context.applicationContext
    private val sync = SyncManager(this.context)
    private val power = this.context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val executor = Executors.newSingleThreadScheduledExecutor()

    // Both are only touched on the executor thread.
    private var failures = 0
    private var minutesToSkip = 0

    fun start() {
        val interval = SyncManager.AUTO_PUSH_INTERVAL_SECONDS.toLong()
        executor.scheduleWithFixedDelay(::runOnce, interval, interval, TimeUnit.SECONDS)
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun runOnce() {
        try {
            if (!sync.isConfigured()) return
            if (minutesToSkip > 0) {
                minutesToSkip--
                return
            }
            // With the screen off nobody is reading notes or timers, so only wake the
            // network when there are log changes to send.
            if (!power.isInteractive && !sync.hasChangesToUpload()) return
            UsageLogImporter(context).importNow()
            val result = sync.syncBlocking()
            failures = if (result.shouldRetry) failures + 1 else 0
            minutesToSkip = minutesToSkipAfter(failures)
            sync.resetNextAutoPush((minutesToSkip + 1) * SyncManager.AUTO_PUSH_INTERVAL_SECONDS)
        } catch (e: Exception) {
            Log.w("MinuteSync", "Sync attempt failed", e)
        }
    }

    companion object {
        /**
         * Minutes to stay quiet after consecutive failed attempts, so an unreachable
         * server is retried after 2, 4, 8 and then every 15 minutes.
         */
        fun minutesToSkipAfter(failures: Int): Int = when {
            failures <= 0 -> 0
            failures >= 4 -> 14
            else -> (1 shl failures) - 1
        }
    }
}
