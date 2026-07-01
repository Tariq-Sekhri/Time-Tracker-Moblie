package ca.tariq_sekhri.time_tracker

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.math.max

class SyncManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("TimeTrackerPrefs", Context.MODE_PRIVATE)
    private val dbHelper = DatabaseHelper(context)
    private val client = OkHttpClient()
    private val gson = Gson()

    fun hasServerIp(): Boolean = !getServerIp().isNullOrBlank()

    fun getServerIp(): String? = prefs.getString(PREF_SERVER_IP, null)

    fun getDeviceUuid(): String? = prefs.getString(PREF_DEVICE_UUID, null)

    fun isRegistered(): Boolean =
        !getDeviceToken().isNullOrBlank() && !getDeviceUuid().isNullOrBlank()

    fun isConfigured(): Boolean = hasServerIp() && isRegistered()

    fun unlockServer() {
        prefs.edit()
            .remove(PREF_SERVER_IP)
            .remove(PREF_DEVICE_UUID)
            .remove(PREF_DEVICE_TOKEN)
            .remove(PREF_LAST_PUSHED_LOG_ID)
            .remove(PREF_NEXT_AUTO_PUSH_AT_MS)
            .apply()
    }

    fun saveServerIp(ip: String) {
        prefs.edit().putString(PREF_SERVER_IP, normalizeServerIp(ip)).apply()
    }

    fun resetNextAutoPush() {
        if (!isConfigured()) return
        prefs.edit()
            .putLong(PREF_NEXT_AUTO_PUSH_AT_MS, System.currentTimeMillis() + AUTO_PUSH_INTERVAL_SECONDS * 1000L)
            .apply()
    }

    fun secondsUntilNextAutoPush(): Long? {
        if (!isConfigured()) return null
        val nextPushAt = prefs.getLong(PREF_NEXT_AUTO_PUSH_AT_MS, 0L)
        if (nextPushAt <= 0L) return null
        return max(0L, (nextPushAt - System.currentTimeMillis() + 999L) / 1000L)
    }

    fun checkServer(ip: String, onComplete: (Result<String>) -> Unit) {
        val normalized = normalizeServerIp(ip)
        if (normalized.isBlank()) {
            onComplete(Result.failure(IOException("Enter a server IP")))
            return
        }
        val url = baseUrl(normalized) + "/v1/check"
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()?.trim()?.replace("\"", "")
                response.close()
                if (response.isSuccessful && body == EXPECTED_CHECK_RESPONSE) {
                    saveServerIp(normalized)
                    onComplete(Result.success(normalized))
                } else {
                    onComplete(Result.failure(IOException("Invalid response from server: $body")))
                }
            }
        })
    }

    fun register(onComplete: (Boolean, String) -> Unit) {
        val ip = getServerIp() ?: return onComplete(false, "No server configured")
        val url = baseUrl(ip) + "/v1/register"
        val name = "${Build.MANUFACTURER} ${Build.MODEL}"
        val body = gson.toJson(RegisterPayload(name)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(false, "Register failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    response.close()
                    onComplete(false, "Register error: ${response.code}")
                    return
                }
                val responseBody = response.body?.string() ?: ""
                response.close()
                val result = gson.fromJson(responseBody, RegisterResponse::class.java)
                prefs.edit()
                    .putString(PREF_DEVICE_UUID, result.uuid)
                    .putString(PREF_DEVICE_TOKEN, result.token)
                    .putLong(PREF_LAST_PUSHED_LOG_ID, 0L)
                    .apply()
                onComplete(true, "Registered")
            }
        })
    }

    fun uploadAllLogs(onComplete: (Boolean, String) -> Unit) {
        postAllLogs(reupload = false, onComplete)
    }

    fun reuploadAllLogs(onComplete: (Boolean, String) -> Unit) {
        if (!isRegistered()) {
            onComplete(false, "Not registered")
            return
        }
        prefs.edit().putLong(PREF_LAST_PUSHED_LOG_ID, 0L).apply()
        postAllLogs(reupload = true, onComplete)
    }

    private fun postAllLogs(reupload: Boolean, onComplete: (Boolean, String) -> Unit) {
        val ip = getServerIp() ?: return onComplete(false, "No server configured")
        val token = getDeviceToken() ?: return onComplete(false, "Not registered")
        val deviceUuid = getDeviceUuid() ?: return onComplete(false, "Not registered")

        val allLogs = dbHelper.getAllLogs()
        if (allLogs.isEmpty()) {
            return onComplete(true, "No logs to upload")
        }

        val backendLogs = allLogs.map { mapLog(it, deviceUuid) }
        val payload = UploadPayload(token, backendLogs)
        val url = baseUrl(ip) + "/v1/upload_all_logs"
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(false, "Upload failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                if (response.isSuccessful) {
                    val maxId = allLogs.maxOf { it.id }
                    prefs.edit().putLong(PREF_LAST_PUSHED_LOG_ID, maxId).apply()
                    resetNextAutoPush()
                    val verb = if (reupload) "Re-uploaded" else "Uploaded"
                    onComplete(true, "$verb ${allLogs.size} logs")
                } else {
                    onComplete(false, "Upload error: ${response.code}")
                }
            }
        })
    }

    fun sync(onComplete: (Boolean, String) -> Unit) {
        if (!isConfigured()) return onComplete(false, "Not configured")
        val ip = getServerIp()!!
        val token = getDeviceToken()!!
        val deviceUuid = getDeviceUuid()!!

        val lastId = prefs.getLong(PREF_LAST_PUSHED_LOG_ID, 0L)
        val logs = dbHelper.getLogsAfter(lastId)
        val deletedIds = dbHelper.getDeletedLogIds()

        if (logs.isEmpty() && deletedIds.isEmpty()) {
            return onComplete(true, "Nothing to sync")
        }

        val backendLogs = logs.map { mapLog(it, deviceUuid) }
        val payload = SyncPayload(token, backendLogs, deletedIds)
        val url = baseUrl(ip) + "/v1/sync"
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(false, "Sync failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                if (response.isSuccessful) {
                    if (logs.isNotEmpty()) {
                        prefs.edit().putLong(PREF_LAST_PUSHED_LOG_ID, logs.maxOf { it.id }).apply()
                    }
                    if (deletedIds.isNotEmpty()) {
                        dbHelper.clearDeletedLogs(deletedIds)
                    }
                    resetNextAutoPush()
                    val parts = mutableListOf<String>()
                    if (logs.isNotEmpty()) parts.add("pushed ${logs.size} logs")
                    if (deletedIds.isNotEmpty()) parts.add("deleted ${deletedIds.size} on server")
                    onComplete(true, parts.joinToString(", ").replaceFirstChar { it.uppercase() })
                } else {
                    onComplete(false, "Sync error: ${response.code}")
                }
            }
        })
    }

    fun pushLogs(onComplete: (Boolean, String) -> Unit) = sync(onComplete)

    private fun getDeviceToken(): String? = prefs.getString(PREF_DEVICE_TOKEN, null)

    private fun mapLog(entry: LogEntry, deviceUuid: String) = BackendLog(
        id = entry.id,
        device_uuid = deviceUuid,
        app = entry.appLabel ?: entry.packageName,
        timestamp = entry.startTimestamp / 1000,
        duration = entry.duration
    )

    private fun normalizeServerIp(serverIp: String): String {
        var ip = serverIp.trim()
        if (ip.startsWith("http://")) ip = ip.removePrefix("http://")
        else if (ip.startsWith("https://")) ip = ip.removePrefix("https://")
        ip.split("/").firstOrNull()?.let { if (it.isNotBlank()) ip = it }
        ip.split(":").firstOrNull()?.let { if (it.isNotBlank()) ip = it }
        return ip
    }

    private fun baseUrl(ip: String): String = "http://$ip:3000"

    data class RegisterPayload(val name: String)

    data class RegisterResponse(val uuid: String, val token: String)

    data class BackendLog(
        val id: Long,
        @SerializedName("device_uuid") val device_uuid: String,
        val app: String,
        val timestamp: Long,
        val duration: Long
    )

    data class UploadPayload(val token: String, val logs: List<BackendLog>)

    data class SyncPayload(
        val token: String,
        val logs: List<BackendLog>,
        @SerializedName("deleted_log_ids") val deleted_log_ids: List<Long>
    )

    companion object {
        const val AUTO_PUSH_INTERVAL_SECONDS = 300
        const val EXPECTED_CHECK_RESPONSE = "Time Tracker Backend v1"
        private const val PREF_SERVER_IP = "server_ip"
        private const val PREF_DEVICE_UUID = "device_uuid"
        private const val PREF_DEVICE_TOKEN = "device_token"
        private const val PREF_LAST_PUSHED_LOG_ID = "last_pushed_log_id"
        private const val PREF_NEXT_AUTO_PUSH_AT_MS = "next_auto_push_at_ms"
    }
}
