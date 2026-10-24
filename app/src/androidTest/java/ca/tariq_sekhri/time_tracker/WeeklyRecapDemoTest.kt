package ca.tariq_sekhri.time_tracker

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class WeeklyRecapDemoTest {
    @Test fun previewNotification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recap = WeeklyRecap(RecapWindow.completed(ZonedDateTime.now()),
            listOf(10800L, 12600L, 9000L, 14400L, 16200L, 7200L, 5400L), 99000L)
        assertTrue(WeeklyRecapNotifications.show(context, recap, demo = true))
        val posted = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.single { it.id == WeeklyRecapNotifications.ID }
        assertTrue(posted.notification.extras.getCharSequence("android.bigText").toString().contains("−6h 30m"))
    }
    @Test fun previewRecapScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, WeeklyRecapActivity::class.java)
            .putExtra("demo", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }
}
