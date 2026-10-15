package ca.tariq_sekhri.time_tracker

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ManualTimerOfflineTest {
    @Test fun categoriesPersistValidateHierarchyAndUploadBeforeEntries() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "category-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(namespace + name, mode)
        }
        val server = MockWebServer()
        server.start()
        try {
            assertEquals("School", ManualTimerStore.createCategory(context, " School ", null))
            val child = ManualTimerStore.createCategory(context, "Homework", "School")
            assertEquals("School \u203a Homework", child)
            ManualTimerStore.createCategory(context, "Personal", null)
            ManualTimerStore.createCategory(context, "Homework", "Personal")
            for ((name, parent) in listOf("school" to null, "Homework" to "School", "Nested" to child, "Bad \u203a name" to null)) {
                try { ManualTimerStore.createCategory(context, name, parent); fail("Invalid category accepted") }
                catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
            }
            ManualTimerStore.mergeCategories(context, listOf(ManualTimerSync.Project("Remote", "#123456")))
            assertEquals(5, ManualTimerStore.categories(context).size)
            val timer = ManualTimerSync.Timer(UUID.randomUUID().toString(), "Homework", child, System.currentTimeMillis() / 1000 - 10)
            ManualTimerStore.save(context, timer)
            ManualTimerStore.finish(context, timer, timer.title, child)
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setBody(com.google.gson.Gson().toJson(ManualTimerStore.entries(context).single())))
            ManualTimerStore.sync(context, OkHttpClient(), server.url("/").toString().trimEnd('/'), "test-token")
            val categories = server.takeRequest()
            assertEquals("/v1/manual/projects", categories.path)
            assertEquals("PUT", categories.method)
            assertTrue(categories.body.readUtf8().contains("School \u203a Homework"))
            assertEquals("/v1/manual/entries", server.takeRequest().path)
            assertTrue(ManualTimerStore.categories(context).none { it.pending })
            assertTrue(ManualTimerStore.entries(context).single().synced)
        } finally {
            server.shutdown()
            target.deleteSharedPreferences(namespace + "TimeTrackerManualTimers")
        }
    }
    @Test fun localSessionPersistsAndFailedUploadRetriesWithoutDuplicating() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "manual-test-${UUID.randomUUID()}-"
        fun isolated() = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(namespace + name, mode)
        }
        val context = isolated()
        val timer = ManualTimerSync.Timer(UUID.randomUUID().toString(), "Offline work", "Personal", System.currentTimeMillis() / 1000 - 20)
        val server = MockWebServer()
        server.start()
        try {
            ManualTimerStore.save(context, timer)
            assertEquals(timer, ManualTimerStore.running(isolated()))
            ManualTimerStore.finish(context, timer, "Finished offline", "Personal")
            assertNull(ManualTimerStore.running(isolated()))
            assertEquals(1, ManualTimerStore.entries(isolated()).size)
            assertFalse(ManualTimerStore.entries(context).single().synced)
            val client = OkHttpClient()
            server.enqueue(MockResponse().setResponseCode(503))
            try { ManualTimerStore.sync(context, client, server.url("/").toString().trimEnd('/'), "test-token"); fail("Upload should fail") }
            catch (_: java.io.IOException) {}
            assertFalse(ManualTimerStore.entries(context).single().synced)
            val first = server.takeRequest()
            server.enqueue(MockResponse().setBody(first.body.clone().readUtf8()))
            ManualTimerStore.sync(context, client, server.url("/").toString().trimEnd('/'), "test-token")
            val retry = server.takeRequest()
            assertEquals(first.body.readUtf8(), retry.body.readUtf8())
            assertEquals(1, ManualTimerStore.entries(context).size)
            assertTrue(ManualTimerStore.entries(isolated()).single().synced)
            ManualTimerStore.sync(context, client, server.url("/").toString().trimEnd('/'), "test-token")
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
            target.deleteSharedPreferences(namespace + "TimeTrackerManualTimers")
        }
    }
}
