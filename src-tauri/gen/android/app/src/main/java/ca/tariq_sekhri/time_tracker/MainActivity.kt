package ca.tariq_sekhri.time_tracker

import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.decorView.setBackgroundColor(Color.rgb(17, 19, 24))
    ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
      view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
      insets
    }
    UsageSyncScheduler.schedule(applicationContext)
  }
  override fun onResume() {
    super.onResume()
    if (UsageLogImporter.hasUsageAccess(this)) startForegroundService(Intent(this, UsageTrackerService::class.java))
    ManualTimerSync.connect(applicationContext)
  }
  override fun onPause() {
    ManualTimerSync.disconnect()
    super.onPause()
  }
  override fun onWebViewCreate(webView: WebView) {
    super.onWebViewCreate(webView)
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        webView.evaluateJavascript("window.__TIME_TRACKER_BACK__?.() ?? false") { handled ->
          if (handled != "true") moveTaskToBack(true)
        }
      }
    })
  }
  companion object { const val EXTRA_START_PAGE = "time_tracker_start_page" }
}
