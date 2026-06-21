package ca.tariq_sekhri.time_tracker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*

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
        prefs.edit().putBoolean("server_locked", false).apply()
    }

    fun pushLogs(onComplete: (Boolean, String) -> Unit) {
        val ip = getServerIp() ?: return onComplete(false, "No server IP")

        val lastId = prefs.getLong("last_pushed_log_id", 0L)
        val logs = dbHelper.getLogsAfter(lastId)

        if (logs.isEmpty()) {
            finishWithDeletions(ip, onComplete)
            return
        }

        resolveServerDeviceId(ip) { deviceId ->
            val url = baseUrl(ip) + "/v1/upload_logs"
            val payload = LogPayload(getDeviceMetadata(deviceId), logs.map { entry ->
                BackendLog(
                    id = entry.id,
                    deviceId = deviceId,
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
    }

    private fun finishWithDeletions(ip: String, onComplete: (Boolean, String) -> Unit) {
        resolveServerDeviceId(ip) { deviceId ->
            syncDeletions(ip, deviceId, onComplete)
        }
    }

    private fun syncDeletions(ip: String, deviceId: Long, onFinished: (Boolean, String) -> Unit) {
        val deletedIds = dbHelper.getDeletedLogIds()
        if (deletedIds.isEmpty()) {
            return onFinished(true, "Sync complete")
        }

        val url = "${baseUrl(ip)}/v1/devices/$deviceId/logs"
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

    private fun resolveServerDeviceId(ip: String, onResolved: (Long) -> Unit) {
        val cached = prefs.getLong("server_device_id", 0L)
        if (cached > 0L) {
            return onResolved(cached)
        }

        val url = "${baseUrl(ip)}/v1/devices"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResolved(0L)
            }

            override fun onResponse(call: Call, response: Response) {
                val deviceId = if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val type = object : TypeToken<List<BackendDevice>>() {}.type
                    val devices: List<BackendDevice> = runCatching { gson.fromJson<List<BackendDevice>>(body, type) }
                        .getOrDefault(emptyList())
                    val uuid = getDeviceUuid()
                    devices.firstOrNull { it.uuid == uuid }?.id ?: 0L
                } else {
                    0L
                }
                if (deviceId > 0L) {
                    prefs.edit().putLong("server_device_id", deviceId).apply()
                }
                response.close()
                onResolved(deviceId)
            }
        })
    }

    private fun baseUrl(ip: String): String {
        return if (ip.startsWith("http")) ip else "http://$ip:3000"
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceUuid(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceMetadata(deviceId: Long): BackendDevice {
        val uuid = getDeviceUuid()
        val name = "${Build.MANUFACTURER} ${Build.MODEL}"
        return BackendDevice(id = deviceId, name = name, uuid = uuid)
    }

    data class BackendDevice(val id: Long, val name: String, val uuid: String)
    data class BackendLog(
        val id: Long,
        @SerializedName("device_id") val deviceId: Long,
        val app: String,
        val timestamp: Long,
        val duration: Long
    )
    data class LogPayload(val device: BackendDevice, val logs: List<BackendLog>)
}
