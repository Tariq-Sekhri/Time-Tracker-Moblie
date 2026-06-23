package ca.tariq_sekhri.time_tracker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*
import kotlin.math.max

class SyncManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("TimeTrackerPrefs", Context.MODE_PRIVATE)
    private val dbHelper = DatabaseHelper(context)
    private val client = OkHttpClient()
    private val gson = Gson()

    fun isServerLocked(): Boolean = prefs.getBoolean("server_locked", false)
    fun getServerIp(): String? = prefs.getString("server_ip", null)

    fun lockServer(ip: String) {
        prefs.edit()
            .putString("server_ip", ip)
            .putBoolean("server_locked", true)
            .apply()
    }

    fun unlockServer() {
        prefs.edit()
            .putBoolean("server_locked", false)
            .remove(PREF_NEXT_AUTO_PUSH_AT_MS)
            .apply()
    }

    fun resetNextAutoPush() {
        if (!isServerLocked()) return
        prefs.edit()
            .putLong(PREF_NEXT_AUTO_PUSH_AT_MS, System.currentTimeMillis() + AUTO_PUSH_INTERVAL_SECONDS * 1000L)
            .apply()
    }

    fun secondsUntilNextAutoPush(): Long? {
        if (!isServerLocked()) return null
        val nextPushAt = prefs.getLong(PREF_NEXT_AUTO_PUSH_AT_MS, 0L)
        if (nextPushAt <= 0L) return null
        return max(0L, (nextPushAt - System.currentTimeMillis() + 999L) / 1000L)
    }

    fun pushLogs(onComplete: (Boolean, String) -> Unit) {
        val ip = getServerIp() ?: return onComplete(false, "No server IP")
        resetNextAutoPush()

        val lastId = prefs.getLong("last_pushed_log_id", 0L)
        val logs = dbHelper.getLogsAfter(lastId)

        if (logs.isEmpty()) {
            finishWithDeletions(ip, onComplete)
            return
        }

        val url = baseUrl(ip) + "/v1/upload_logs"
        val device = getDeviceMetadata()
        val payload = LogPayload(device, logs.map { entry ->
            BackendLog(
                id = entry.id,
                device_uuid = device.uuid,
                app = entry.appLabel ?: entry.packageName,
                timestamp = entry.startTimestamp / 1000,
                duration = entry.duration
            )
        })

        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(false, "Push failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val maxId = logs.maxOf { it.id }
                    prefs.edit().putLong("last_pushed_log_id", maxId).apply()
                    finishWithDeletions(ip) { deleteSuccess, deleteMsg ->
                        onComplete(
                            deleteSuccess,
                            if (deleteSuccess) "Successfully pushed ${logs.size} logs" else "Pushed ${logs.size} logs, but $deleteMsg"
                        )
                    }
                } else {
                    onComplete(false, "Push server error: ${response.code}")
                }
                response.close()
            }
        })
    }

    private fun finishWithDeletions(ip: String, onComplete: (Boolean, String) -> Unit) {
        syncDeletions(ip, onComplete)
    }

    private fun syncDeletions(ip: String, onFinished: (Boolean, String) -> Unit) {
        val deletedIds = dbHelper.getDeletedLogIds()
        if (deletedIds.isEmpty()) {
            return onFinished(true, "Sync complete")
        }

        val url = "${baseUrl(ip)}/v1/devices/${getDeviceUuid()}/logs"
        val body = gson.toJson(deletedIds).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).delete(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFinished(false, "Delete sync failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    dbHelper.clearDeletedLogs(deletedIds)
                    onFinished(true, "Deleted ${deletedIds.size} logs on server")
                } else {
                    onFinished(false, "Delete server error: ${response.code}")
                }
                response.close()
            }
        })
    }

    private fun baseUrl(ip: String): String {
        return if (ip.startsWith("http")) ip else "http://$ip:3000"
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceUuid(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: prefs.getString(PREF_FALLBACK_DEVICE_UUID, null)
            ?: UUID.randomUUID().toString().also { generated ->
                prefs.edit().putString(PREF_FALLBACK_DEVICE_UUID, generated).apply()
            }
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceMetadata(): BackendDevice {
        val uuid = getDeviceUuid()
        val name = "${Build.MANUFACTURER} ${Build.MODEL}"
        return BackendDevice(name = name, uuid = uuid)
    }

    data class BackendDevice(val name: String, val uuid: String)
    data class BackendLog(
        val id: Long,
        val device_uuid: String,
        val app: String,
        val timestamp: Long,
        val duration: Long
    )
    data class LogPayload(val device: BackendDevice, val logs: List<BackendLog>)

    companion object {
        const val AUTO_PUSH_INTERVAL_SECONDS = 300
        private const val PREF_NEXT_AUTO_PUSH_AT_MS = "next_auto_push_at_ms"
        private const val PREF_FALLBACK_DEVICE_UUID = "fallback_device_uuid"
    }
}
