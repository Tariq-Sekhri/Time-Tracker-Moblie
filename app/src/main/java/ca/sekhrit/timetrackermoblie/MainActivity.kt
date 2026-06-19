package ca.sekhrit.timetrackermoblie

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        
        btnStart = Button(this)
        updateTrackingButtonState(btnStart)
        btnStart.setOnClickListener {
            if (hasUsageStatsPermission()) {
                if (isServiceRunning()) {
                    stopService()
                    updateTrackingButtonState(btnStart)
                } else if (hasNotificationPermission()) {
                    startService()
                    updateTrackingButtonState(btnStart)
                } else {
                    requestNotificationPermission()
                }
            } else {
                requestUsageStatsPermission()
            }
        }
        
        val btnViewLogs = Button(this).apply {
            text = "View Logs"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LogsActivity::class.java))
            }
        }

        val btnSkippedApps = Button(this).apply {
            text = "Skipped Apps"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SkippedAppsActivity::class.java))
            }
        }

        layout.addView(btnStart)
        layout.addView(btnViewLogs)
        layout.addView(btnSkippedApps)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        if (::btnStart.isInitialized) {
            updateTrackingButtonState(btnStart)
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageStatsPermission() {
        Toast.makeText(this, "Please enable Usage Access for Time Tracker", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startService()
            updateTrackingButtonState(btnStart)
        }
    }

    private fun startService() {
        val intent = Intent(this, UsageTrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopService() {
        val intent = Intent(this, UsageTrackerService::class.java)
        stopService(intent)
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val className = UsageTrackerService::class.java.name
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Integer.MAX_VALUE).any { it.service.className == className }
    }

    private fun updateTrackingButtonState(button: Button) {
        if (isServiceRunning()) {
            button.text = "Stop Tracking"
            button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else {
            button.text = "Start Tracking"
            button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        }
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 100
    }
}
