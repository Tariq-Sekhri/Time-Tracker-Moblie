package ca.tariq_sekhri.time_tracker

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log

class MediaTrackerService : NotificationListenerService() {

    private val dbHelper by lazy { DatabaseHelper(this) }
    private lateinit var mediaSessionManager: MediaSessionManager
    
    private val activeLogs = mutableMapOf<String, Long>()
    private val currentMetadata = mutableMapOf<String, String>()
    private val activeCallbacks = mutableMapOf<String, MediaController.Callback>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("MediaTracker", "MediaTrackerService connected")
        
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(this, MediaTrackerService::class.java)
        
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener({ controllers ->
                updateSessions(controllers)
            }, componentName)
            updateSessions(mediaSessionManager.getActiveSessions(componentName))
        } catch (e: SecurityException) {
            Log.e("MediaTracker", "Missing notification access permission", e)
        }
    }

    private fun updateSessions(controllers: List<MediaController>?) {
        if (controllers == null) return
        
        val currentPackages = controllers.map { it.packageName }.toSet()

        for (controller in controllers) {
            val pkg = controller.packageName
            if (!activeCallbacks.containsKey(pkg)) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        handleMediaState(controller)
                    }
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        handleMediaState(controller)
                    }
                }
                controller.registerCallback(callback)
                activeCallbacks[pkg] = callback
            }
            handleMediaState(controller)
        }
        
        val missingPackages = activeCallbacks.keys - currentPackages
        for (pkg in missingPackages) {
            activeLogs[pkg]?.let { id ->
                dbHelper.endLog(id, System.currentTimeMillis(), "MEDIA_DISCONNECTED")
                activeLogs.remove(pkg)
                currentMetadata.remove(pkg)
            }
            activeCallbacks.remove(pkg)
        }
    }

    private fun handleMediaState(controller: MediaController) {
        val packageName = controller.packageName
        val playbackState = controller.playbackState
        val metadata = controller.metadata
        val now = System.currentTimeMillis()

        if (playbackState != null && playbackState.state == PlaybackState.STATE_PLAYING) {
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Title"
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val appName = getAppName(packageName)
            val formattedName = "$title - $artist - $appName"
            
            val previousFormat = currentMetadata[packageName]
            
            if (previousFormat != formattedName) {
                // End previous log if track changed
                activeLogs[packageName]?.let { id ->
                    dbHelper.endLog(id, now, "TRACK_CHANGED")
                }
                
                // Start new log
                val richMeta = RichLogMetadata(
                    packageName = packageName,
                    appLabel = formattedName,
                    startEventType = "MEDIA_PLAYING"
                )
                val id = dbHelper.insertLog(richMeta, now)
                activeLogs[packageName] = id
                currentMetadata[packageName] = formattedName
            }
        } else {
            // Paused or stopped
            activeLogs[packageName]?.let { id ->
                dbHelper.endLog(id, now, "MEDIA_STOPPED")
                activeLogs.remove(packageName)
                currentMetadata.remove(packageName)
            }
        }
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
}
