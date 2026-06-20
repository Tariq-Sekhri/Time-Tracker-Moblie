package ca.sekhrit.timetrackermoblie

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var btnStart: Button
    private lateinit var listView: ListView
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var prefs: SharedPreferences
    private lateinit var editMinDuration: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = DatabaseHelper(this)
        prefs = getSharedPreferences("TimeTrackerPrefs", Context.MODE_PRIVATE)
        
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }
        
        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1F2937"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        btnStart = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1.2f).apply {
                setMargins(0, 0, dp(8), 0)
            }
            transformationMethod = null
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        updateTrackingButtonState()
        
        btnStart.setOnClickListener {
            if (hasUsageStatsPermission()) {
                if (isServiceRunning()) {
                    stopService()
                } else if (hasNotificationPermission()) {
                    startService()
                } else {
                    requestNotificationPermission()
                }
                updateTrackingButtonState()
            } else {
                requestUsageStatsPermission()
            }
        }

        val btnRefresh = Button(this).apply {
            text = "Refresh"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(0, 0, dp(8), 0)
            }
            transformationMethod = null
            setBackgroundColor(Color.parseColor("#374151"))
            setTextColor(Color.WHITE)
            setOnClickListener { refreshLogs() }
        }

        val btnSkippedApps = Button(this).apply {
            text = "Skipped"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(0, 0, dp(8), 0)
            }
            transformationMethod = null
            setBackgroundColor(Color.parseColor("#374151"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SkippedAppsActivity::class.java))
            }
        }

        val btnSync = Button(this).apply {
            text = "Sync"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            transformationMethod = null
            setBackgroundColor(Color.parseColor("#374151"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SyncActivity::class.java))
            }
        }

        header.addView(btnStart)
        header.addView(btnRefresh)
        header.addView(btnSkippedApps)
        header.addView(btnSync)
        root.addView(header)

        // Filter Bar
        val filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        val tvLabel = TextView(this).apply {
            text = "Min Duration (sec): "
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
        }

        editMinDuration = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("min_duration", 0).toString())
            setTextColor(Color.WHITE)
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.white)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val value = s.toString().toIntOrNull() ?: 0
                    prefs.edit().putInt("min_duration", value).apply()
                    refreshLogs()
                }
            })
        }

        filterBar.addView(tvLabel)
        filterBar.addView(editMinDuration)

        val btnWipe = Button(this).apply {
            text = "Clear Logs"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginStart = dp(16)
            }
            textSize = 12f
            transformationMethod = null
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#991B1B")) // red-800
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear all logs?")
                    .setMessage("This will delete all usage logs. This cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        dbHelper.deleteAllLogs()
                        refreshLogs()
                        Toast.makeText(this@MainActivity, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
        filterBar.addView(btnWipe)

        root.addView(filterBar)

        // Logs List
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            divider = null
            dividerHeight = 0
            setBackgroundColor(Color.BLACK)
        }
        root.addView(listView)

        setContentView(root)

        if (hasUsageStatsPermission() && hasNotificationPermission() && !isServiceRunning()) {
            startService()
            updateTrackingButtonState()
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, "Clear All Logs")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return if (item.itemId == 1) {
            dbHelper.deleteAllLogs()
            refreshLogs()
            Toast.makeText(this, "All logs cleared", Toast.LENGTH_SHORT).show()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        updateTrackingButtonState()
        refreshLogs()
    }

    private fun refreshLogs() {
        val minDuration = prefs.getInt("min_duration", 0)
        val logs = dbHelper.getAllLogs().filter { it.duration >= minDuration }
        
        listView.adapter = object : ArrayAdapter<LogEntry>(this, 0, logs) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val entry = getItem(position)!!
                val rowRoot = RelativeLayout(context).apply {
                    layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
                    setBackgroundColor(if (position % 2 == 0) Color.BLACK else Color.parseColor("#111827"))
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }

                val btnDelete = ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    setColorFilter(Color.parseColor("#991B1B")) // red-800
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    id = View.generateViewId()
                    val lp = RelativeLayout.LayoutParams(dp(40), dp(40)).apply {
                        addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                        addRule(RelativeLayout.CENTER_VERTICAL)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        dbHelper.deleteLog(entry.id)
                        refreshLogs()
                        Toast.makeText(context, "Log deleted", Toast.LENGTH_SHORT).show()
                    }
                }

                val btnCopy = ImageView(context).apply {
                    setImageResource(R.drawable.ic_copy) // Custom copy icon
                    setColorFilter(Color.parseColor("#9CA3AF"))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    id = View.generateViewId()
                    val lp = RelativeLayout.LayoutParams(dp(40), dp(40)).apply {
                        addRule(RelativeLayout.LEFT_OF, btnDelete.id)
                        addRule(RelativeLayout.CENTER_VERTICAL)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        copyToClipboard(entry.packageName)
                        Toast.makeText(context, "Copied package: ${entry.packageName}", Toast.LENGTH_SHORT).show()
                    }
                }

                val textLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                        addRule(RelativeLayout.LEFT_OF, btnCopy.id)
                    }
                    layoutParams = lp
                }
                
                val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val appNameText = if (entry.appLabel != null && entry.appLabel != entry.packageName) {
                    "${entry.appLabel} - ${entry.packageName}"
                } else {
                    entry.packageName
                }
                val startTimeText = sdf.format(Date(entry.startTimestamp))
                val durationText = formatDuration(entry.duration)

                val tvAppName = TextView(context).apply {
                    text = appNameText
                    setTextColor(Color.parseColor("#D1D5DB"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                }
                val tvTime = TextView(context).apply {
                    text = startTimeText
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 12f
                }
                val tvDuration = TextView(context).apply {
                    text = durationText
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 12f
                }

                textLayout.addView(tvAppName)
                textLayout.addView(tvTime)
                textLayout.addView(tvDuration)

                rowRoot.addView(textLayout)
                rowRoot.addView(btnCopy)
                rowRoot.addView(btnDelete)

                rowRoot.setOnClickListener {
                    val fullLog = "$appNameText\n$startTimeText\n$durationText"
                    copyToClipboard(fullLog)
                    Toast.makeText(context, "Copied full log", Toast.LENGTH_SHORT).show()
                }

                return rowRoot
            }
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${secs}s"
            minutes > 0 -> "${minutes}m ${secs}s"
            else -> "${secs}s"
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Log Entry", text)
        clipboard.setPrimaryClip(clip)
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
            updateTrackingButtonState()
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

    private fun updateTrackingButtonState() {
        if (isServiceRunning()) {
            btnStart.text = "Stop Tracking"
            btnStart.setBackgroundColor(Color.parseColor("#991B1B"))
        } else {
            btnStart.text = "Start Tracking"
            btnStart.setBackgroundColor(Color.parseColor("#065F46"))
        }
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 100
    }
}
