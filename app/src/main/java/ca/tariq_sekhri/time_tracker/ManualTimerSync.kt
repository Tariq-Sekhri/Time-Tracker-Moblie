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
 * The manual timer shared with every device on the sync server. The server owns
 * the timer, so a timer started on the desktop can be stopped here and the
 * other way round. A WebSocket keeps [timer] current while the app is open.
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

    private data class Entry(
        val session_id: String,
        val title: String,
        val project_name: String?,
        val start_time: Long,
        val end_time: Long
    )

    private data class Event(val type: String?, val timer: Timer?)

    private val gson = Gson()
    private val json = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    var timer: Timer? = null; private set
    var projects: List<Project> = emptyList(); private set
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
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { gson.fromJson(text, Event::class.java) }.getOrNull() ?: return
                when (event.type) {
                    "timer" -> update { timer = event.timer }
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
                    update { projects = list }
                }
            }
        })
    }

    /** Starts a timer, or renames the running one. Callbacks run on the main thread. */
    fun save(title: String, projectName: String?, onError: (String) -> Unit) {
        val (base, token) = connection() ?: return onError("Register this device to use the shared timer")
        val current = timer
        val body = Timer(
            session_id = current?.session_id ?: UUID.randomUUID().toString(),
            title = title.trim(),
            project_name = projectName,
            start_time = current?.start_time ?: (System.currentTimeMillis() / 1000)
        )
        val request = Request.Builder().url("$base/v1/manual/timer").header("Authorization", "Bearer $token")
            .put(gson.toJson(body).toRequestBody(json)).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { onError("Could not reach the sync server") } }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = response.body?.string()
                    when {
                        // 409: another device already started a timer; show that one.
                        response.isSuccessful || response.code == 409 ->
                            runCatching { gson.fromJson(text, Timer::class.java) }.getOrNull()?.let { saved -> update { timer = saved } }
                        response.code == 410 -> update { if (timer?.session_id == body.session_id) timer = null }
                        response.code == 404 || response.code == 405 -> { setStatus(Status.OLD_SERVER); main.post { onError("Update the sync server to use the shared timer") } }
                        else -> main.post { onError("Timer error: ${response.code}") }
                    }
                }
            }
        })
    }

    /** Stops the running timer wherever it was started and records it with the given details. */
    fun stop(title: String, projectName: String?, onError: (String) -> Unit, onRecorded: () -> Unit) {
        val (base, token) = connection() ?: return onError("Register this device to use the shared timer")
        val current = timer ?: return
        val end = maxOf(System.currentTimeMillis() / 1000, current.start_time + 1)
        val entry = Entry(current.session_id, title.trim(), projectName, current.start_time, end)
        val request = Request.Builder().url("$base/v1/manual/entries").header("Authorization", "Bearer $token")
            .post(gson.toJson(entry).toRequestBody(json)).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { onError("Could not reach the sync server") } }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) update { if (timer?.session_id == current.session_id) timer = null; onRecorded() }
                    else main.post { onError("Timer error: ${response.code}") }
                }
            }
        })
    }
}
