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

    fun isActive(): Boolean = isRegistered() && prefs.getBoolean(PREF_DEVICE_ACTIVE, false)

    fun serverBaseUrl(): String? = getServerIp()?.takeIf { it.isNotBlank() }?.let(::baseUrl)

    fun deviceToken(): String? = getDeviceToken()

    fun unlockServer() {
        prefs.edit()
            .remove(PREF_SERVER_IP)
            .remove(PREF_DEVICE_UUID)
            .remove(PREF_DEVICE_TOKEN)
            .remove(PREF_DEVICE_ACTIVE)
            .remove(PREF_LAST_PUSHED_LOG_ID)
            .remove(PREF_NEXT_AUTO_PUSH_AT_MS)
            .apply()
    }

    fun saveServerIp(ip: String) {
        prefs.edit().putString(PREF_SERVER_IP, normalizeServerIp(ip)).apply()
    }

    fun resetNextAutoPush(inSeconds: Int = AUTO_PUSH_INTERVAL_SECONDS) {
        if (!isConfigured()) return
        prefs.edit()
            .putLong(PREF_NEXT_AUTO_PUSH_AT_MS, System.currentTimeMillis() + inSeconds * 1000L)
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

    fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun register(deviceName: String = defaultDeviceName(), onComplete: (Boolean, String) -> Unit) {
        val ip = getServerIp() ?: return onComplete(false, "No server configured")
        val url = baseUrl(ip) + "/v1/register"
        val name = deviceName.trim().ifBlank { defaultDeviceName() }
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
                    .putBoolean(PREF_DEVICE_ACTIVE, result.is_active)
                    .putLong(PREF_LAST_PUSHED_LOG_ID, 0L)
                    .apply()
                onComplete(
                    true,
                    if (result.is_active) "Registered and active" else "Registered; waiting for admin approval"
                )
            }
        })
    }

    fun checkActivation(onComplete: (Boolean, String) -> Unit) {
        val ip = getServerIp() ?: return onComplete(false, "No server configured")
        val token = getDeviceToken() ?: return onComplete(false, "Not registered")
        val request = statusRequest(ip, token)
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(false, "Status check failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    when {
                        response.isSuccessful -> {
                            val status = parseAndSaveStatus(response)
                            if (status == null) {
                                onComplete(false, "Invalid status response")
                            } else {
                                onComplete(
                                    status.is_active,
                                    if (status.is_active) "Device is active" else "Waiting for admin approval"
                                )
                            }
                        }
                        response.code == 401 -> {
                            clearRegistration()
                            onComplete(false, "Device registration was removed. Register again.")
                        }
                        else -> onComplete(false, "Status check error: ${response.code}")
                    }
                }
            }
        })
    }

    fun fetchActiveDevices(onComplete: (Result<List<ServerDevice>>) -> Unit) {
        val ip=getServerIp() ?: return onComplete(Result.failure(IOException("No server configured")))
        val token=getDeviceToken() ?: return onComplete(Result.failure(IOException("Not registered")))
        val request=Request.Builder().url(baseUrl(ip)+"/v1/devices").header("Authorization","Bearer $token").get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call,e: IOException) = onComplete(Result.failure(e))
            override fun onResponse(call: Call,response: Response) {
                val body=response.body?.string() ?: ""; val code=response.code; response.close()
                if(!response.isSuccessful) onComplete(Result.failure(IOException("Device list error: $code")))
                else onComplete(runCatching { gson.fromJson(body,Array<ServerDevice>::class.java).toList() })
            }
        })
    }

    fun uploadAllLogs(onComplete: (Boolean, String) -> Unit) {
        Thread {
            UsageLogImporter(context).importNow()
            postAllLogs(reupload = false, onComplete)
        }.start()
    }

    fun reuploadAllLogs(onComplete: (Boolean, String) -> Unit) {
        if (!isRegistered()) {
            onComplete(false, "Not registered")
            return
        }
        prefs.edit().putLong(PREF_LAST_PUSHED_LOG_ID, 0L).apply()
        Thread {
            UsageLogImporter(context).importNow()
            postAllLogs(reupload = true, onComplete)
        }.start()
    }

    private fun postAllLogs(reupload: Boolean, onComplete: (Boolean, String) -> Unit) {
        val ip = getServerIp() ?: return onComplete(false, "No server configured")
        val token = getDeviceToken() ?: return onComplete(false, "Not registered")
        val deviceUuid = getDeviceUuid() ?: return onComplete(false, "Not registered")

        var uploaded = 0
        val failure: String? = synchronized(uploadLock) {
            val logs = RunningLogs.finished { dbHelper.getAllLogs() }
            if (logs.isEmpty()) return@synchronized null
            val request = jsonPost(ip, "/v1/upload_all_logs", UploadPayload(token, logs.map { mapLog(it, deviceUuid) }))
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@synchronized syncErrorMessage(response.code, "Upload")
                }
            } catch (e: IOException) {
                return@synchronized "Upload failed: ${e.message}"
            }
            setActive(true)
            advanceLastPushedLogId(logs.maxOf { it.id })
            resetNextAutoPush()
            uploaded = logs.size
            null
        }
        if (failure != null) return onComplete(false, failure)
        if (uploaded == 0) return finishNotesSync(ip, token, "Notes synced", onComplete)
        finishNotesSync(ip, token, "${if (reupload) "Re-uploaded" else "Uploaded"} $uploaded logs", onComplete)
    }

    fun sync(onComplete: (Boolean, String) -> Unit) {
        Thread {
            UsageLogImporter(context).importNow()
            syncImported(onComplete)
        }.start()
    }

    private fun syncImported(onComplete: (Boolean, String) -> Unit) {
        if (!isConfigured()) return onComplete(false, "Not configured")
        val ip = getServerIp()!!
        val token = getDeviceToken()!!
        val deviceUuid = getDeviceUuid()!!

        when (val result = uploadChanges(ip, token, deviceUuid)) {
            is UploadResult.Failed -> onComplete(false, result.message)
            UploadResult.NothingToSend -> finishNotesSync(ip, token, "Notes synced", onComplete)
            is UploadResult.Sent -> {
                val parts = mutableListOf<String>()
                if (result.logs > 0) parts.add("pushed ${result.logs} logs")
                if (result.deleted > 0) parts.add("deleted ${result.deleted} on server")
                finishNotesSync(ip, token, (parts + "notes synced").joinToString(", ").replaceFirstChar { it.uppercase() }, onComplete)
            }
        }
    }

    private sealed class UploadResult {
        object NothingToSend : UploadResult()
        data class Sent(val logs: Int, val deleted: Int) : UploadResult()
        data class Failed(val message: String, val retry: Boolean) : UploadResult()
    }

    /** True when a sync would send finished logs or deletions to the server. Reads the database only. */
    fun hasChangesToUpload(): Boolean =
        isConfigured() && (finishedLogsToUpload().isNotEmpty() || dbHelper.getDeletedLogIds().isNotEmpty())

    private fun finishedLogsToUpload(): List<LogEntry> =
        RunningLogs.finished { dbHelper.getLogsAfter(prefs.getLong(PREF_LAST_PUSHED_LOG_ID, 0L)) }

    /** Uploads finished logs and deletions. Blocks on the network. */
    private fun uploadChanges(ip: String, token: String, deviceUuid: String): UploadResult = synchronized(uploadLock) {
        val logs = finishedLogsToUpload()
        val deletedIds = dbHelper.getDeletedLogIds()
        if (logs.isEmpty() && deletedIds.isEmpty()) return@synchronized UploadResult.NothingToSend

        val request = jsonPost(ip, "/v1/sync", SyncPayload(token, logs.map { mapLog(it, deviceUuid) }, deletedIds))
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val retry = response.code >= 500 || response.code == 408 || response.code == 429
                    return@synchronized UploadResult.Failed(syncErrorMessage(response.code, "Sync"), retry)
                }
            }
        } catch (e: IOException) {
            return@synchronized UploadResult.Failed("Sync failed: ${e.message}", true)
        }
        setActive(true)
        if (logs.isNotEmpty()) advanceLastPushedLogId(logs.maxOf { it.id })
        if (deletedIds.isNotEmpty()) dbHelper.clearDeletedLogs(deletedIds)
        resetNextAutoPush()
        UploadResult.Sent(logs.size, deletedIds.size)
    }

    private fun jsonPost(ip: String, path: String, payload: Any): Request = Request.Builder()
        .url(baseUrl(ip) + path)
        .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
        .build()

    private fun finishNotesSync(ip: String, token: String, message: String, onComplete: (Boolean, String) -> Unit) {
        try { ManualTimerStore.sync(context, client, baseUrl(ip), token) }
        catch (e: Exception) { return onComplete(false, "Manual timer sync failed: ${e.message}") }
        try {
            NotesStore.sync(context, client, baseUrl(ip), token)
            resetNextAutoPush()
            onComplete(true, message)
        } catch (e: Exception) {
            onComplete(false, "Notes sync failed: ${e.message}")
        }
    }

    fun pushLogs(onComplete: (Boolean, String) -> Unit) = sync(onComplete)

    data class BlockingSyncResult(
        val successful: Boolean,
        val message: String,
        val shouldRetry: Boolean = false
    )

    fun syncBlocking(): BlockingSyncResult {
        if (!isConfigured()) return BlockingSyncResult(false, "Not configured")
        val ip = getServerIp()!!
        val token = getDeviceToken()!!
        val statusResult = checkActivationBlocking(ip, token)
        if (!statusResult.successful || !isActive()) return statusResult
        try { ManualTimerStore.sync(context, client, baseUrl(ip), token) }
        catch (e: Exception) { return BlockingSyncResult(false, "Manual timer sync failed: ${e.message}", shouldRetry = true) }
        try { NotesStore.sync(context, client, baseUrl(ip), token) }
        catch (e: Exception) { return BlockingSyncResult(false, "Notes sync failed: ${e.message}", shouldRetry = true) }
        val deviceUuid = getDeviceUuid()
            ?: return BlockingSyncResult(false, "Registration is incomplete")
        return when (val result = uploadChanges(ip, token, deviceUuid)) {
            is UploadResult.Failed -> BlockingSyncResult(false, result.message, result.retry)
            UploadResult.NothingToSend -> BlockingSyncResult(true, "Nothing to sync")
            is UploadResult.Sent -> BlockingSyncResult(true, "Synced")
        }
    }

    private fun checkActivationBlocking(ip: String, token: String): BlockingSyncResult {
        return try {
            client.newCall(statusRequest(ip, token)).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val status = parseAndSaveStatus(response)
                            ?: return BlockingSyncResult(false, "Invalid status response", true)
                        if (status.is_active) {
                            BlockingSyncResult(true, "Device is active")
                        } else {
                            BlockingSyncResult(false, "Waiting for admin approval")
                        }
                    }
                    response.code == 401 -> {
                        clearRegistration()
                        BlockingSyncResult(false, "Device registration was removed. Register again.")
                    }
                    else -> BlockingSyncResult(
                        false,
                        "Status check error: ${response.code}",
                        response.code >= 500 || response.code == 408 || response.code == 429
                    )
                }
            }
        } catch (e: IOException) {
            BlockingSyncResult(false, "Status check failed: ${e.message}", true)
        }
    }

    private fun statusRequest(ip: String, token: String): Request {
        val body = gson.toJson(StatusPayload(token))
            .toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(baseUrl(ip) + "/v1/status")
            .post(body)
            .build()
    }

    private fun parseAndSaveStatus(response: Response): StatusResponse? {
        val responseBody = response.body?.string() ?: return null
        val status = runCatching {
            gson.fromJson(responseBody, StatusResponse::class.java)
        }.getOrNull() ?: return null
        if (status.uuid.isBlank()) return null
        prefs.edit()
            .putString(PREF_DEVICE_UUID, status.uuid)
            .putBoolean(PREF_DEVICE_ACTIVE, status.is_active)
            .apply()
        return status
    }

    private fun syncErrorMessage(code: Int, operation: String): String {
        return when (code) {
            401 -> {
                clearRegistration()
                "Device registration was removed. Register again."
            }
            403 -> {
                setActive(false)
                "Waiting for admin approval"
            }
            else -> "$operation error: $code"
        }
    }

    private fun clearRegistration() {
        prefs.edit()
            .remove(PREF_DEVICE_UUID)
            .remove(PREF_DEVICE_TOKEN)
            .remove(PREF_DEVICE_ACTIVE)
            .remove(PREF_LAST_PUSHED_LOG_ID)
            .remove(PREF_NEXT_AUTO_PUSH_AT_MS)
            .apply()
    }

    private fun setActive(active: Boolean) {
        prefs.edit().putBoolean(PREF_DEVICE_ACTIVE, active).apply()
    }

    private fun advanceLastPushedLogId(uploadedId: Long) {
        val current = prefs.getLong(PREF_LAST_PUSHED_LOG_ID, 0L)
        if (uploadedId > current) {
            prefs.edit().putLong(PREF_LAST_PUSHED_LOG_ID, uploadedId).apply()
        }
    }

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

    private fun baseUrl(ip: String): String = "http://$ip:8765"

    data class RegisterPayload(val name: String)
    data class ServerDevice(val uuid:String, val name:String)

    data class RegisterResponse(
        val uuid: String,
        val token: String,
        val is_active: Boolean
    )

    data class StatusPayload(val token: String)

    data class StatusResponse(
        val uuid: String,
        val name: String,
        val is_active: Boolean
    )

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
        const val DEFAULT_SERVER_IP = "100.75.95.90"
        const val AUTO_PUSH_INTERVAL_SECONDS = 60
        const val EXPECTED_CHECK_RESPONSE = "Time Tracker Backend v1"
        private const val PREF_SERVER_IP = "server_ip"
        private const val PREF_DEVICE_UUID = "device_uuid"
        private const val PREF_DEVICE_TOKEN = "device_token"
        private const val PREF_DEVICE_ACTIVE = "device_active"
        private const val PREF_LAST_PUSHED_LOG_ID = "last_pushed_log_id"
        private const val PREF_NEXT_AUTO_PUSH_AT_MS = "next_auto_push_at_ms"
        /** One upload at a time, so two syncs never send the same logs or race on the cursor. */
        private val uploadLock = Any()
    }
}
