package ca.tariq_sekhri.time_tracker

import android.content.Context
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Durable local sessions; acknowledgements never remove the local history. */
object ManualTimerStore {
    data class Category(val name: String, val color: String, val pending: Boolean = false)
    private const val SEPARATOR = " \u203a "
    data class Entry(val session_id: String, val title: String, val project_name: String?,
        val start_time: Long, val end_time: Long, val synced: Boolean = false)
    private val lock = Any()
    private val syncLock = Any()
    private val gson = Gson()
    private fun prefs(context: Context) = context.getSharedPreferences("TimeTrackerManualTimers", Context.MODE_PRIVATE)
    fun categories(context: Context): List<Category> = synchronized(lock) {
        prefs(context).getString("categories", null)?.let { gson.fromJson(it, Array<Category>::class.java).toList() } ?: emptyList()
    }
    fun createCategory(context: Context, incoming: String, parent: String?): String = synchronized(lock) {
        val name = incoming.trim()
        require(name.isNotEmpty() && name.codePointCount(0, name.length) <= 100 && !name.contains('\u203a') && name.none { it.isISOControl() }) { "Use a name from 1 to 100 characters without \u203a" }
        val all = categories(context)
        val parentCategory = parent?.let { selected -> all.find { it.name == selected && !it.name.contains(SEPARATOR) }
            ?: error("Choose an existing top-level category") }
        val path = if (parentCategory == null) name else parentCategory.name + SEPARATOR + name
        require(all.none { it.name.equals(path, ignoreCase = true) }) { "A category with this name already exists here" }
        val category = Category(path, parentCategory?.color ?: "#a78bfa", true)
        check(prefs(context).edit().putString("categories", gson.toJson(all + category)).commit()) { "Could not save category" }
        path
    }
    fun mergeCategories(context: Context, remote: List<ManualTimerSync.Project>) = synchronized(lock) {
        val local = categories(context)
        val merged = remote.map { item -> local.find { it.pending && it.name.equals(item.name, true) } ?: Category(item.name, item.color) }.toMutableList()
        merged += local.filter { item -> merged.none { it.name.equals(item.name, true) } }
        // Older clients may send a child path without its parent row.
        merged.toList().filter { it.name.contains(SEPARATOR) }.forEach { child ->
            val parent = child.name.substringBefore(SEPARATOR)
            if (merged.none { it.name.equals(parent, true) }) merged += Category(parent, child.color)
        }
        check(prefs(context).edit().putString("categories", gson.toJson(merged)).commit()) { "Could not save categories" }
    }
    fun running(context: Context): ManualTimerSync.Timer? = synchronized(lock) {
        prefs(context).getString("running", null)?.let { gson.fromJson(it, ManualTimerSync.Timer::class.java) }
    }
    fun entries(context: Context): List<Entry> = synchronized(lock) {
        prefs(context).getString("entries", null)?.let { gson.fromJson(it, Array<Entry>::class.java).toList() } ?: emptyList()
    }
    fun save(context: Context, timer: ManualTimerSync.Timer) = synchronized(lock) {
        check(prefs(context).edit().putString("running", gson.toJson(timer)).commit()) { "Could not save timer" }
    }
    fun finish(context: Context, timer: ManualTimerSync.Timer, title: String, project: String?) = synchronized(lock) {
        val entry = Entry(timer.session_id, title.trim(), project, timer.start_time,
            maxOf(System.currentTimeMillis() / 1000, timer.start_time + 1))
        val all = entries(context)
        val updated = if (all.any { it.session_id == entry.session_id }) all else all + entry
        check(prefs(context).edit().remove("running").putString("entries", gson.toJson(updated)).commit()) { "Could not record timer" }
    }
    /** Blocking, serialized across foreground and WorkManager sync; retries reuse session IDs. */
    fun sync(context: Context, client: OkHttpClient, server: String, token: String) = synchronized(syncLock) {
        val pending = categories(context).filter { it.pending }
        if (pending.isNotEmpty()) {
            val payload = pending.map { ManualTimerSync.Project(it.name, it.color) }
            val request = Request.Builder().url("$server/v1/manual/projects").header("Authorization", "Bearer $token")
                .put(gson.toJson(payload).toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Manual category sync failed (${response.code})")
            }
            synchronized(lock) {
                val updated = categories(context).map { if (pending.any { sent -> sent.name == it.name && sent.color == it.color }) it.copy(pending = false) else it }
                check(prefs(context).edit().putString("categories", gson.toJson(updated)).commit()) { "Could not save category acknowledgement" }
            }
        }
        for (entry in entries(context).filter { !it.synced }) {
            val request = Request.Builder().url("$server/v1/manual/entries").header("Authorization", "Bearer $token")
                .post(gson.toJson(entry).toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Manual timer sync failed (${response.code})")
                // Validate the acknowledgement before marking this session delivered.
                val acknowledged = gson.fromJson(response.body?.string(), Entry::class.java)
                if (acknowledged?.session_id != entry.session_id) throw IOException("Invalid manual timer acknowledgement")
            }
            synchronized(lock) {
                val updated = entries(context).map { if (it.session_id == entry.session_id) it.copy(synced = true) else it }
                check(prefs(context).edit().putString("entries", gson.toJson(updated)).commit()) { "Could not save timer acknowledgement" }
            }
        }
    }
}
