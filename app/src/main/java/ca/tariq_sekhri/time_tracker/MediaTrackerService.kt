package ca.tariq_sekhri.time_tracker

import android.app.Notification
import android.content.ComponentName
import android.content.ContentValues
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MediaTrackerService : NotificationListenerService() {

    private val dbHelper by lazy { DatabaseHelper(this) }
    private val categoryManager by lazy { CategoryManager(this) }
    private var mediaSessionManager: MediaSessionManager? = null
    
    private val activeControllers = mutableMapOf<String, MediaController>()
    private val activeCallbacks = mutableMapOf<String, MediaController.Callback>()
    private val activeLogs = mutableMapOf<String, Long>()
    private val activeStartTimes = mutableMapOf<String, Long>()
    private val currentMetadata = mutableMapOf<String, String>()
    private val notificationMetadata = mutableMapOf<String, Pair<String?, String?>>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isHeartbeatRunning = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val toRemove = mutableListOf<String>()

            for ((pkg, id) in activeLogs) {
                val controller = activeControllers[pkg]
                val state = controller?.playbackState?.state
                val isPlaying = state == PlaybackState.STATE_PLAYING ||
                                state == PlaybackState.STATE_BUFFERING ||
                                state == PlaybackState.STATE_FAST_FORWARDING ||
                                state == PlaybackState.STATE_REWINDING

                if (isPlaying) {
                    val start = activeStartTimes[pkg] ?: now
                    val durationSec = (now - start) / 1000L
                    if (durationSec > 0) {
                        val cv = ContentValues().apply {
                            put(DatabaseHelper.COLUMN_END_TIMESTAMP, now)
                            put(DatabaseHelper.COLUMN_DURATION, durationSec)
                        }
                        dbHelper.writableDatabase.update(
                            DatabaseHelper.TABLE_LOGS,
                            cv,
                            "${DatabaseHelper.COLUMN_ID}=?",
                            arrayOf(id.toString())
                        )
                    }
                } else {
                    toRemove.add(pkg)
                }
            }

            for (pkg in toRemove) {
                closeSession(pkg, "MEDIA_STOPPED")
            }

            if (activeLogs.isNotEmpty()) {
                mainHandler.postDelayed(this, 1000L)
            } else {
                isHeartbeatRunning = false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "MediaTrackerService connected")
        
        try {
            val mgr = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
            mediaSessionManager = mgr
            val componentName = ComponentName(this, MediaTrackerService::class.java)
            
            mgr?.addOnActiveSessionsChangedListener({ controllers ->
                updateSessions(controllers)
            }, componentName, mainHandler)

            updateSessions(mgr?.getActiveSessions(componentName))
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing notification access permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaTrackerService", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // 1. Try to extract MediaSession token from notification extras
        val extras = sbn.notification.extras ?: return
        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        }

        // Cache title/artist from notification extras as fallback
        val notifTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val notifText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        if (notifTitle != null || notifText != null) {
            notificationMetadata[pkg] = Pair(notifTitle, notifText)
        }

        if (token != null) {
            val controller = MediaController(this, token)
            registerAndTrackController(controller)
        } else {
            // Check if we have an active controller for this package and re-evaluate
            activeControllers[pkg]?.let { handleMediaState(it) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        notificationMetadata.remove(pkg)
        
        val controller = activeControllers[pkg]
        if (controller == null || controller.playbackState?.state != PlaybackState.STATE_PLAYING) {
            closeSession(pkg, "NOTIFICATION_REMOVED")
        }
    }

    private fun updateSessions(controllers: List<MediaController>?) {
        if (controllers == null) return
        
        val currentPackages = controllers.map { it.packageName }.toSet()

        for (controller in controllers) {
            registerAndTrackController(controller)
        }
        
        val missingPackages = activeControllers.keys - currentPackages
        for (pkg in missingPackages) {
            closeSession(pkg, "MEDIA_DISCONNECTED")
            activeCallbacks[pkg]?.let { runCatching { activeControllers[pkg]?.unregisterCallback(it) } }
            activeControllers.remove(pkg)
            activeCallbacks.remove(pkg)
        }
    }

    private fun registerAndTrackController(controller: MediaController) {
        val pkg = controller.packageName
        val existingController = activeControllers[pkg]
        
        if (existingController == null || existingController.sessionToken != controller.sessionToken) {
            activeCallbacks[pkg]?.let { oldCb ->
                runCatching { existingController?.unregisterCallback(oldCb) }
            }
            
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    handleMediaState(controller)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    handleMediaState(controller)
                }

                override fun onSessionDestroyed() {
                    closeSession(pkg, "SESSION_DESTROYED")
                    activeControllers.remove(pkg)
                    activeCallbacks.remove(pkg)
                }
            }
            
            controller.registerCallback(callback, mainHandler)
            activeControllers[pkg] = controller
            activeCallbacks[pkg] = callback
        }
        
        handleMediaState(controller)
    }

    private fun extractMetadata(controller: MediaController): Pair<String, String> {
        val pkg = controller.packageName
        val metadata = controller.metadata
        val notif = notificationMetadata[pkg]

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata?.description?.title?.toString()
            ?: notif?.first
            ?: ""

        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: metadata?.description?.subtitle?.toString()
            ?: notif?.second
            ?: ""

        return Pair(title.trim(), artist.trim())
    }

    private fun formatMediaLabel(packageName: String, title: String, artist: String): String {
        val appName = getAppName(packageName)
        return when {
            title.isNotBlank() && artist.isNotBlank() -> "$title - $artist - $appName"
            title.isNotBlank() -> "$title - $appName"
            else -> "$appName - Playing Media"
        }
    }

    private fun isSkipped(packageName: String, formattedLabel: String): Boolean {
        val target = "$packageName $formattedLabel"
        return dbHelper.getSkippedApps().any { row ->
            val pattern = row.pattern.trim()
            pattern.isNotEmpty() && (
                target.contains(pattern, ignoreCase = true) ||
                runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(target) }.getOrDefault(false)
            )
        }
    }

    private fun handleMediaState(controller: MediaController) {
        val packageName = controller.packageName
        val playbackState = controller.playbackState
        val now = System.currentTimeMillis()

        val isPlaying = playbackState != null && (
            playbackState.state == PlaybackState.STATE_PLAYING ||
            playbackState.state == PlaybackState.STATE_BUFFERING ||
            playbackState.state == PlaybackState.STATE_FAST_FORWARDING ||
            playbackState.state == PlaybackState.STATE_REWINDING
        )

        if (isPlaying) {
            val (title, artist) = extractMetadata(controller)
            val formattedName = formatMediaLabel(packageName, title, artist)

            if (isSkipped(packageName, formattedName)) {
                closeSession(packageName, "SKIPPED")
                return
            }

            val previousFormat = currentMetadata[packageName]

            if (previousFormat != formattedName) {
                // End previous log if track changed
                closeSession(packageName, "TRACK_CHANGED")

                // Start new log
                val richMeta = RichLogMetadata(
                    packageName = packageName,
                    appLabel = formattedName,
                    startEventType = "MEDIA_PLAYING"
                )
                val category = categoryManager.resolveCategory(packageName, formattedName)
                val id = dbHelper.insertLog(richMeta, now, category)
                
                activeLogs[packageName] = id
                activeStartTimes[packageName] = now
                currentMetadata[packageName] = formattedName
            } else {
                // Update duration for existing track
                activeLogs[packageName]?.let { id ->
                    val start = activeStartTimes[packageName] ?: now
                    val durationSec = (now - start) / 1000L
                    if (durationSec > 0) {
                        val cv = ContentValues().apply {
                            put(DatabaseHelper.COLUMN_END_TIMESTAMP, now)
                            put(DatabaseHelper.COLUMN_DURATION, durationSec)
                        }
                        dbHelper.writableDatabase.update(
                            DatabaseHelper.TABLE_LOGS,
                            cv,
                            "${DatabaseHelper.COLUMN_ID}=?",
                            arrayOf(id.toString())
                        )
                    }
                }
            }

            startHeartbeat()
        } else {
            // Paused or stopped
            closeSession(packageName, "MEDIA_STOPPED")
        }
    }

    private fun closeSession(packageName: String, reason: String) {
        val id = activeLogs.remove(packageName) ?: return
        val start = activeStartTimes.remove(packageName) ?: System.currentTimeMillis()
        val now = System.currentTimeMillis()
        currentMetadata.remove(packageName)

        val durationSec = (now - start) / 1000L
        if (durationSec < 1L) {
            // Delete flash 0-second log to keep log database clean
            dbHelper.writableDatabase.delete(
                DatabaseHelper.TABLE_LOGS,
                "${DatabaseHelper.COLUMN_ID}=?",
                arrayOf(id.toString())
            )
        } else {
            dbHelper.endLog(id, now, reason)
        }

        if (activeLogs.isEmpty()) {
            stopHeartbeat()
        }
    }

    private fun startHeartbeat() {
        if (!isHeartbeatRunning && activeLogs.isNotEmpty()) {
            isHeartbeatRunning = true
            mainHandler.postDelayed(heartbeatRunnable, 1000L)
        }
    }

    private fun stopHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        isHeartbeatRunning = false
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        stopHeartbeat()
        for (pkg in activeLogs.keys.toList()) {
            closeSession(pkg, "LISTENER_DISCONNECTED")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeat()
        for (pkg in activeLogs.keys.toList()) {
            closeSession(pkg, "SERVICE_DESTROYED")
        }
        for ((pkg, controller) in activeControllers) {
            activeCallbacks[pkg]?.let { runCatching { controller.unregisterCallback(it) } }
        }
        activeControllers.clear()
        activeCallbacks.clear()
    }

    companion object {
        private const val TAG = "MediaTracker"
    }
}
