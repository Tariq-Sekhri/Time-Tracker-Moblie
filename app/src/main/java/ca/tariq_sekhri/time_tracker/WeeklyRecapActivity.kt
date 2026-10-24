package ca.tariq_sekhri.time_tracker

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.time.LocalDate
import java.time.ZoneId

/** A lightweight local recap; no network, account or web UI required. */
class WeeklyRecapActivity : Activity() {
    private val ink = Color.rgb(244, 243, 255)
    private val muted = Color.rgb(174, 173, 194)
    private val purple = Color.rgb(164, 147, 255)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(24).toFloat() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val window = RecapWindow(runCatching { LocalDate.parse(intent.getStringExtra("week")) }
            .getOrDefault(RecapWindow.completed(java.time.ZonedDateTime.now()).start), ZoneId.systemDefault())
        val demo = BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)
        Thread {
            val recap = if (demo) WeeklyRecap(window, listOf(10800, 12600, 9000, 14400, 16200, 7200, 5400).map { it.toLong() }, 99000)
                else WeeklyRecap.read(this, window)
            runOnUiThread { render(recap, demo) }
        }.start()
    }
    private fun render(recap: WeeklyRecap, demo: Boolean) {
        window.statusBarColor = Color.rgb(17, 19, 27)
        window.navigationBarColor = Color.rgb(17, 19, 27)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(28))
            setBackgroundColor(Color.rgb(17, 19, 27))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(17, 19, 27)); addView(root) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        root.addView(text("‹  Time Tracker", 16f, muted).apply {
            contentDescription = "Back to Time Tracker"
            setOnClickListener { startActivity(Intent(this@WeeklyRecapActivity, MainActivity::class.java)); finish() }
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(text(if (demo) "WEEKLY RECAP  /  SAMPLE PREVIEW" else "YOUR MONDAY MOMENT", 11f, purple, true).apply { letterSpacing = 0.13f })
        root.addView(text("Your week,\nwrapped ✨", 32f, ink, true))
        root.addView(text(recap.dates, 15f, muted).apply { setPadding(0, dp(4), 0, dp(16)) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18))
            background = shape(Color.rgb(36, 32, 60))
        }
        root.addView(card, LinearLayout.LayoutParams(-1, -2))
        card.addView(text("DEVICE USAGE", 11f, purple, true).apply { letterSpacing = 0.12f })
        card.addView(text(recap.totalText, 40f, ink, true))
        card.addView(text(recap.comparison, 14f, Color.rgb(203, 194, 255)))
        card.addView(text("Previous week: ${WeeklyRecap.duration(recap.previousSeconds)}", 12f, muted))
        val chart = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(0, dp(14), 0, 0) }
        val peak = maxOf(1L, recap.days.maxOrNull() ?: 1L)
        recap.days.forEachIndexed { index, seconds ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            chart.addView(column, LinearLayout.LayoutParams(0, dp(112), 1f))
            val height = maxOf(3, (seconds.toDouble() / peak * 88).toInt())
            column.addView(View(this).apply {
                background = shape(if (seconds == peak) purple else Color.rgb(93, 78, 151))
                contentDescription = "${listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")[index]}: ${WeeklyRecap.duration(seconds)}"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }, LinearLayout.LayoutParams(dp(23), dp(height)))
            column.addView(text(listOf("M", "T", "W", "T", "F", "S", "S")[index], 11f, muted))
        }
        card.addView(chart)
        root.addView(text(recap.message, 17f, ink, true).apply { setPadding(0, dp(20), 0, dp(10)) })
        root.addView(text("A little perspective for a fresh start.\nMake room for what matters to you this week.", 14f, muted))
        root.addView(text("Every Monday morning · On-device & private", 11f, muted).apply { setPadding(0, dp(18), 0, dp(10)) })
        root.addView(Button(this).apply {
            text = "Notification settings"; isAllCaps = false; setTextColor(ink)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(43, 45, 60))
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, WeeklyRecapNotifications.CHANNEL))
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
    }
}
