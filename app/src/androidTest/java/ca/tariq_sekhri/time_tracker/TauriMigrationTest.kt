package ca.tariq_sekhri.time_tracker

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual bundled React -> Rust -> Kotlin path, only in isolated debug. */
@RunWith(AndroidJUnit4::class)
class TauriMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) webView(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun js(script: String): String {
        val latch = CountDownLatch(1); var result = "null"
        scenario.onActivity { activity ->
            val web = webView(activity.window.decorView)
            if (web == null) latch.countDown()
            else web.evaluateJavascript(script) { value -> result = value; latch.countDown() }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "WebView did not respond" }
        return result
    }
    private fun eventually(message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail(message)
    }
    private fun bridge(operation: String, payload: JSONObject = JSONObject()): Any? {
        js("window.__qaResult = null; window.__TAURI_INTERNALS__.invoke('mobile_request', {operation:${JSONObject.quote(operation)},payload:$payload}).then(value => window.__qaResult = {ok:true,value}).catch(error => window.__qaResult = {ok:false,error:String(error)}); true")
        var result: JSONObject? = null
        eventually("Bridge timed out: $operation") {
            val raw = js("JSON.stringify(window.__qaResult)")
            if (raw == "null" || raw == "\"null\"") false else {
                val decoded = JSONArray("[$raw]").getString(0)
                result = JSONObject(decoded); true
            }
        }
        check(result!!.getBoolean("ok")) { "Bridge $operation: ${result!!.optString("error")}" }
        return result!!.opt("value")
    }
    private fun navigate(page: String) {
        js("Array.from(document.querySelectorAll('.bottom-nav button')).find(button => button.textContent === ${JSONObject.quote(page)})?.click(); true")
        eventually("Did not navigate to $page") { js("document.querySelector('.app-header h1')?.textContent === ${JSONObject.quote(if (page == "Stats") "Statistics" else page)}") == "true" }
    }

    @Test
    fun bundledUiPreservesNativeDataAndSyncContracts() {
        check(context.packageName == "ca.tariq_sekhri.time_tracker.dev") { "QA must never run against the production app" }
        val prefs = context.getSharedPreferences("TimeTrackerPrefs", 0)
        prefs.edit().clear().putInt("day_start_hour", 6).commit()
        context.getSharedPreferences(NotesStore.PREFS, 0).edit().clear().commit()
        val helper = DatabaseHelper(context)
        val now = System.currentTimeMillis()
        helper.writableDatabase.execSQL("INSERT INTO logs(package_name,app_label,start_timestamp,end_timestamp,duration) VALUES(?,?,?,?,?)",
            arrayOf("qa.migration.sentinel", "Migration sentinel", now - 120000, now - 60000, 60))
        val id = helper.readableDatabase.rawQuery("SELECT MAX(id) FROM logs WHERE package_name='qa.migration.sentinel'", null).use { it.moveToFirst(); it.getLong(0) }
        val server = MockWebServer()
        var socket: WebSocket? = null
        var timer: JSONObject? = null
        var notes = ""
        val uploads = mutableListOf<JSONObject>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                return when (request.requestUrl?.encodedPath) {
                    "/v1/check" -> json(JSONObject.quote(SyncManager.EXPECTED_CHECK_RESPONSE))
                    "/v1/register" -> json("""{"uuid":"qa-device","token":"qa-only-token","is_active":true}""")
                    "/v1/status" -> json("""{"uuid":"qa-device","name":"QA phone","is_active":true}""")
                    "/v1/devices" -> json("""[{"uuid":"qa-device","name":"QA phone"},{"uuid":"qa-desktop","name":"QA desktop"}]""")
                    "/v1/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket; webSocket.send("""{"type":"timer","timer":null}""") }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    })
                    "/v1/manual/projects" -> json("""[{"name":"Migration","color":"#b18bff"}]""")
                    "/v1/manual/timer" -> {
                        timer = JSONObject(request.body.readUtf8()); socket?.send(JSONObject().put("type", "timer").put("timer", timer).toString())
                        json(timer.toString())
                    }
                    "/v1/manual/entries" -> { timer = null; socket?.send("""{"type":"timer","timer":null}"""); json("{}") }
                    "/v1/notes" -> { if (request.method == "POST") notes = JSONObject(request.body.readUtf8()).getString("text"); json(JSONObject().put("revision", 1).put("text", notes).toString()) }
                    "/v1/sync", "/v1/upload_all_logs" -> { synchronized(uploads) { uploads.add(JSONObject(request.body.readUtf8())) }; json("{}") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(8765) // Loopback inside the emulator. No production backend is contacted.
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            eventually("Bundled React UI did not start") { js("Boolean(document.querySelector('.bottom-nav'))") == "true" }
            assertTrue(js("location.origin").contains("tauri.localhost"))
            val state = bridge("state") as JSONObject
            assertTrue(state.has("permissions")); assertFalse(state.getJSONObject("sync").has("token"))
            val range = JSONObject().put("startMs", now - 180000).put("endMs", now)
            val rows = bridge("logs", range) as JSONArray
            assertTrue("Expected seeded log $id; rows=$rows; state=$state; range=$range", (0 until rows.length()).any { rows.getJSONObject(it).getLong("id") == id })
            val usage = bridge("usage", range) as JSONObject
            assertTrue(usage.getLong("total") >= 60)
            bridge("settings.save", JSONObject().put("day_start_hour", 7))
            assertEquals(7, prefs.getInt("day_start_hour", 0))

            navigate("Logs"); navigate("Calendar"); navigate("Stats"); navigate("Sync"); navigate("Settings"); navigate("Notes")
            eventually("Shared Notes editor not ready") { js("Boolean(document.querySelector('[contenteditable]'))") == "true" }
            js("document.querySelector('[contenteditable]').innerHTML='<p>Migration QA note</p>'; document.querySelector('[contenteditable]').dispatchEvent(new InputEvent('input',{bubbles:true})); true")
            eventually("React note did not persist in native storage") { context.getSharedPreferences(NotesStore.PREFS, 0).getString(NotesStore.CONTENT, "")!!.contains("Migration QA note") }
            navigate("Usage"); navigate("Notes")
            eventually("Saved note did not reload") { js("document.querySelector('[contenteditable]')?.textContent.includes('Migration QA note')") == "true" }

            bridge("sync.check", JSONObject().put("server", "127.0.0.1"))
            bridge("sync.register", JSONObject().put("name", "QA phone"))
            eventually("Shared timer socket did not connect") { js("true"); ManualTimerSync.status == ManualTimerSync.Status.CONNECTED }
            bridge("timer.save", JSONObject().put("title", "Migration QA").put("project", "Migration"))
            eventually("Timer did not start") { ManualTimerSync.timer?.title == "Migration QA" }
            bridge("timer.stop", JSONObject().put("title", "Migration QA").put("project", "Migration"))
            eventually("Timer did not stop") { ManualTimerSync.timer == null }
            bridge("sync.now")
            assertTrue(synchronized(uploads) { uploads.isNotEmpty() })
            bridge("log.delete", JSONObject().put("id", id))
            assertTrue(helper.getDeletedLogIds().contains(id))
            bridge("sync.now")
            assertFalse(helper.getDeletedLogIds().contains(id))
            assertTrue(synchronized(uploads) { uploads.any { body -> val deleted = body.optJSONArray("deleted_log_ids") ?: JSONArray(); (0 until deleted.length()).any { deleted.getLong(it) == id } } })
            assertEquals(2, (bridge("sync.devices") as JSONArray).length())
            bridge("sync.change")
            assertTrue(context.getSharedPreferences(NotesStore.PREFS, 0).getString(NotesStore.CONTENT, "")!!.contains("Migration QA note"))
            js("location.reload(); true")
            eventually("React did not reopen after recreation") { js("Boolean(document.querySelector('.bottom-nav'))") == "true" }
            assertEquals(7, prefs.getInt("day_start_hour", 0))
        } finally {
            // Keep Tauri's event loop alive until instrumentation ends the process.
            instrumentation.runOnMainSync { ManualTimerSync.disconnect() }
            server.shutdown(); helper.close()
        }
    }
}
