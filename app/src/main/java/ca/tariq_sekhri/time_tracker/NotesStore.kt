package ca.tariq_sekhri.time_tracker

import android.content.Context
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/** One lock for editor saves and sync acknowledgements across all activities/workers. */
object NotesStore {
    const val PREFS = "TimeTrackerNotes"
    const val CONTENT = "notes_content"
    private val lock = Any()
    private val syncLock = Any()
    private val gson = Gson()
    private data class State(val server: String = "", val revision: Long = 0, val text: String = "")
    private data class Snapshot(val revision: Long, val text: String)
    private data class Update(val operation_id: String, val base_revision: Long, val text: String)
    fun save(context: Context, incoming: String, expected: String? = null) = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(CONTENT, "") ?: ""
        val text = if(expected != null && expected != current && current != incoming) preserve(current, incoming) else incoming
        if (prefs.getString(CONTENT, "") != text) {
            check(prefs.edit().putString(CONTENT, text).putString("edit_id", UUID.randomUUID().toString()).commit()) { "Could not save notes" }
        }
        text
    }
    private fun preserve(current: String, incoming: String): String {
        val prefix = "<!--time-tracker-rich-notes-->"
        fun html(text: String) = if(text.startsWith(prefix)) text.removePrefix(prefix) else "<p>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</p>"
        if(current.isEmpty()) return incoming
        if(incoming.isEmpty()) return current
        return prefix + html(current) + "<h3>Preserved conflicting edit</h3>" + html(incoming)
    }
    /** Blocking: called only from sync's background thread/callback. */
    fun sync(context: Context, client: OkHttpClient, server: String, token: String) = synchronized(syncLock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (state, text, edit) = synchronized(lock) {
            var state = prefs.getString("sync_state", null)?.let { gson.fromJson(it, State::class.java) } ?: State()
            if (state.server != server) state = State(server = server)
            val text = prefs.getString(CONTENT, "") ?: ""
            val edit = prefs.getString("edit_id", null) ?: UUID.randomUUID().toString().also {
                check(prefs.edit().putString("edit_id", it).commit())
            }
            Triple(state, text, edit)
        }
        val builder = Request.Builder().url("$server/v1/notes").header("Authorization", "Bearer $token")
        if (text != state.text) builder.post(gson.toJson(Update(edit, state.revision, text)).toRequestBody("application/json".toMediaType()))
        val snapshot = client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException(if(response.code == 404) "Notes sync requires an updated Time Tracker server" else "Notes sync failed (${response.code})")
            gson.fromJson(response.body?.string() ?: throw IOException("Empty notes response"), Snapshot::class.java)
        }
        synchronized(lock) {
            // Leave a newer local edit pending with its original base: next sync preserves both.
            if (prefs.getString(CONTENT, "") == text && prefs.getString("edit_id", null) == edit) {
                check(prefs.edit().putString(CONTENT, snapshot.text).putString("sync_state", gson.toJson(State(server, snapshot.revision, snapshot.text))).commit())
            }
        }
    }
}
