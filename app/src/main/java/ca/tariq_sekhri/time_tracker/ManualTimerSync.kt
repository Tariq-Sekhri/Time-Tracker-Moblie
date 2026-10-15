package ca.tariq_sekhri.time_tracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Shared timers stay live through the server; offline timers are persisted locally.
 * Finished sessions are always saved locally before upload, and can be retried safely.
 */
object ManualTimerSync {
    enum class Status { NOT_READY, CONNECTING, CONNECTED, OFFLINE, OLD_SERVER }

    data class Timer(
        val session_id: String,
        val title: String,
        val project_name: String?,
        val start_time: Long,
        val version: Long = 0,
        val device_uuid: String = ""
    )

    data class Project(val name: String, val color: String)

    private data class Event(val type: String?, val timer: Timer?)

    private val gson = Gson()
    private val json = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    private var remoteTimer: Timer? = null
    val timer: Timer? get() = appContext?.let { ManualTimerStore.running(it) } ?: remoteTimer
    val projects: List<Project> get() = appContext?.let { ManualTimerStore.categories(it).map { category -> Project(category.name, category.color) } } ?: emptyList()
    var status = Status.NOT_READY; private set

    private var socket: WebSocket? = null
    private var wanted = false
    private var appContext: Context? = null
    private val reconnect = Runnable { if (wanted) openSocket() }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    /** State changes happen on the main thread, where the UI reads them. */
    private fun update(change: () -> Unit) { main.post { change(); listeners.toList().forEach { it() } } }

    fun connect(context: Context) {
        appContext = context.applicationContext
        wanted = true
        main.removeCallbacks(reconnect)
        if (socket == null) openSocket()
    }

    fun disconnect() {
        wanted = false
        main.removeCallbacks(reconnect)
        socket?.close(1000, null)
        socket = null
    }

    private fun connection(): Pair<String, String>? {
        val sync = SyncManager(appContext ?: return null)
        if (!sync.isActive()) return null
        val base = sync.serverBaseUrl() ?: return null
        val token = sync.deviceToken() ?: return null
        return base to token
    }

    private fun setStatus(next: Status) = update { status = next }

    private fun openSocket() {
        val (base, token) = connection() ?: return setStatus(Status.NOT_READY)
        setStatus(Status.CONNECTING)
        val request = Request.Builder().url("$base/v1/ws").header("Authorization", "Bearer $token").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                setStatus(Status.CONNECTED)
                refreshProjects()
                pushPending()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { gson.fromJson(text, Event::class.java) }.getOrNull() ?: return
                when (event.type) {
                    "timer" -> update {
                        val recorded = appContext?.let { ManualTimerStore.entries(it).any { entry -> entry.session_id == event.timer?.session_id } } == true
                        remoteTimer = if (recorded) null else event.timer
                    }
                    "projects" -> refreshProjects()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket, Status.OFFLINE)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Servers from before the shared timer have no /v1/ws endpoint.
                closed(webSocket, if (response?.code == 404) Status.OLD_SERVER else Status.OFFLINE)
            }
        })
    }

    private fun closed(webSocket: WebSocket, next: Status) {
        main.post {
            if (socket !== webSocket) return@post
            socket = null
            setStatus(next)
            if (wanted) main.postDelayed(reconnect, if (next == Status.OLD_SERVER) 600_000L else 5_000L)
        }
    }

    private fun refreshProjects() {
        val (base, token) = connection() ?: return
        val request = Request.Builder().url("$base/v1/manual/projects").header("Authorization", "Bearer $token").get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) return
                    val body = response.body?.string() ?: return
                    val list = runCatching { gson.fromJson(body, Array<Project>::class.java).toList() }.getOrNull() ?: return
                    appContext?.let { context -> ManualTimerStore.mergeCategories(context, list) }
                    update {}
                }
            }
        })
    }

    /** Starts a timer, or renames the running one. Callbacks run on the main thread. */
    fun save(title: String, projectName: String?, onError: (String) -> Unit, onSaved: () -> Unit = {}) {
        if (!validDetails(title, projectName)) return onError("Use a title up to 200 characters and a project up to 203 characters")
        val current = timer
        val body = Timer(
            session_id = current?.session_id ?: UUID.randomUUID().toString(),
            title = title.trim(),
            project_name = projectName,
            start_time = current?.start_time ?: (System.currentTimeMillis() / 1000)
        )
        val context = appContext ?: return onError("Timer storage is not ready")
        val localSave = {
            runCatching { ManualTimerStore.save(context, body) }
                .fold({ update { onSaved() } }, { main.post { onError(it.message ?: "Could not save timer") } })
        }
        val connection = connection()
        if (ManualTimerStore.running(context) != null || status != Status.CONNECTED || connection == null) {
            localSave(); return
        }
        val (base, token) = connection
        val request = Request.Builder().url("$base/v1/manual/timer").header("Authorization", "Bearer $token")
            .put(gson.toJson(body).toRequestBody(json)).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { localSave() }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = response.body?.string()
                    when {
                        // 409: another device already started a timer; show that one.
                        response.isSuccessful || response.code == 409 ->
                            runCatching { gson.fromJson(text, Timer::class.java) }.getOrNull()?.let { saved -> update { remoteTimer = saved; onSaved() } }
                                ?: main.post { onError("Invalid timer response") }
                        response.code == 410 -> update { if (remoteTimer?.session_id == body.session_id) remoteTimer = null; onError("Timer has already stopped") }
                        else -> { if (response.code == 404 || response.code == 405) setStatus(Status.OLD_SERVER); localSave() }
                    }
                }
            }
        })
    }

    /** Stops the running timer wherever it was started and records it with the given details. */
    fun stop(title: String, projectName: String?, onError: (String) -> Unit, onRecorded: () -> Unit) {
        if (!validDetails(title, projectName)) return onError("Use a title up to 200 characters and a project up to 203 characters")
        val current = timer ?: return
        val context = appContext ?: return onError("Timer storage is not ready")
        val recordLocal = {
            runCatching { ManualTimerStore.finish(context, current, title, projectName) }
                .fold({ update { if (remoteTimer?.session_id == current.session_id) remoteTimer = null; onRecorded(); pushPending() } },
                    { main.post { onError(it.message ?: "Could not record timer") } })
        }
        recordLocal()
    }

    private fun validDetails(title: String, project: String?) = title.trim().codePointCount(0, title.trim().length) <= 200 &&
        title.none { it.isISOControl() } && (project == null || (project.isNotBlank() && project.codePointCount(0, project.length) <= 203))

    fun pushPending() {
        val context = appContext ?: return
        val (base, token) = connection() ?: return
        Thread { runCatching { ManualTimerStore.sync(context, client, base, token) }; update {} }.start()
    }
}
