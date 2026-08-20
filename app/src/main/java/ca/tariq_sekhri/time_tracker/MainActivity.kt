package ca.tariq_sekhri.time_tracker

import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var btnStart: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnNotification: Button
    private lateinit var listView: ListView
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var prefs: SharedPreferences
    private lateinit var editMinDuration: EditText
    private var hasPromptedPermissions = false

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
        
        btnStart.setOnClickListener {
            if (hasUsageStatsPermission()) {
                importUsage(showResult = true)
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
            setOnClickListener { importUsage(showResult = false) }
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

        // Services Action Bar
        val servicesBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(0))
        }

        btnAccessibility = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                setMargins(0, 0, dp(8), 0)
            }
            textSize = 12f
            transformationMethod = null
            setOnClickListener {
                requestAccessibilityPermission()
            }
        }

        btnNotification = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            textSize = 12f
            transformationMethod = null
            setOnClickListener {
                requestNotificationPermission()
            }
        }

        servicesBar.addView(btnAccessibility)
        servicesBar.addView(btnNotification)
        root.addView(servicesBar)
        updateTrackingButtonState()

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
        UsageSyncScheduler.schedule(this)
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
        checkAndPromptMissingPermissions()
        if (hasUsageStatsPermission()) {
            importUsage(showResult = false)
        } else {
            refreshLogs()
        }
    }

    private fun checkAndPromptMissingPermissions() {
        if (hasPromptedPermissions) return

        if (!hasUsageStatsPermission()) {
            hasPromptedPermissions = true
            AlertDialog.Builder(this)
                .setTitle("Usage Access Required")
                .setMessage("Time Tracker needs Usage Access to track foreground app usage. Would you like to enable it now?")
                .setPositiveButton("Enable") { _, _ -> requestUsageStatsPermission() }
                .setNegativeButton("Later", null)
                .show()
        } else if (!isAccessibilityServiceEnabled()) {
            hasPromptedPermissions = true
            AlertDialog.Builder(this)
                .setTitle("Browser Tracking")
                .setMessage("Enable Time Tracker in Accessibility Settings to record browser page titles (e.g. Wikipedia - Vivaldi).")
                .setPositiveButton("Enable") { _, _ -> requestAccessibilityPermission() }
                .setNegativeButton("Later", null)
                .show()
        } else if (!isNotificationListenerEnabled()) {
            hasPromptedPermissions = true
            AlertDialog.Builder(this)
                .setTitle("Media Tracking")
                .setMessage("Enable Notification Access for Time Tracker to record active song/media playback (e.g. Spotify, YouTube Music).")
                .setPositiveButton("Enable") { _, _ -> requestNotificationPermission() }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun requestAccessibilityPermission() {
        Toast.makeText(this, "Enable Time Tracker in Accessibility Settings", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestNotificationPermission() {
        Toast.makeText(this, "Enable Time Tracker in Notification Access Settings", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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

                val btnSkip = ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setColorFilter(Color.parseColor("#F59E0B"))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    contentDescription = "Add to skipped apps"
                    id = View.generateViewId()
                    val lp = RelativeLayout.LayoutParams(dp(40), dp(40)).apply {
                        addRule(RelativeLayout.LEFT_OF, btnCopy.id)
                        addRule(RelativeLayout.CENTER_VERTICAL)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        showAddSkippedAppDialog(entry.packageName)
                    }
                }

                val textLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                        addRule(RelativeLayout.LEFT_OF, btnSkip.id)
                    }
                    layoutParams = lp
                }
                
                val appNameText = entry.appLabel ?: entry.packageName
                val startTimeText = formatDisplayTimestamp(entry.startTimestamp)
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
                rowRoot.addView(btnSkip)
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

    private fun showAddSkippedAppDialog(defaultPattern: String = "") {
        val input = EditText(this).apply {
            hint = "com.example.app or .*youtube.*"
            setSingleLine(true)
            setText(defaultPattern)
            setSelection(text.length)
            setTextColor(Color.BLACK)
        }

        AlertDialog.Builder(this)
            .setTitle("Add skipped app")
            .setMessage("Matches package name or app label.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val pattern = input.text.toString().trim()
                if (pattern.isBlank()) {
                    Toast.makeText(this, "Pattern is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val matchCount = dbHelper.countMatchingLogs(pattern)
                if (matchCount > 0) {
                    AlertDialog.Builder(this)
                        .setTitle("Delete matching logs?")
                        .setMessage("This pattern matches $matchCount existing log entries. Delete them?")
                        .setNegativeButton("No", null)
                        .setPositiveButton("Yes") { _, _ ->
                            dbHelper.addSkippedApp(pattern)
                            refreshLogs()
                        }
                        .show()
                } else {
                    dbHelper.addSkippedApp(pattern)
                    refreshLogs()
                }
            }
            .show()
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
        return UsageLogImporter.hasUsageAccess(this)
    }

    private fun requestUsageStatsPermission() {
        Toast.makeText(this, "Please enable Usage Access for Time Tracker", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun importUsage(showResult: Boolean) {
        btnStart.isEnabled = false
        btnStart.text = "Importing..."
        Thread {
            val result = UsageLogImporter(applicationContext).importNow()
            UsageSyncScheduler.runNow(applicationContext)
            runOnUiThread {
                updateTrackingButtonState()
                refreshLogs()
                if (showResult) {
                    val message = if (result.available) {
                        "Imported ${result.importedCount} Android usage log(s)"
                    } else {
                        "Usage Access is required"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun updateTrackingButtonState() {
        btnStart.isEnabled = true
        if (hasUsageStatsPermission()) {
            btnStart.text = "Import Now"
            btnStart.setBackgroundColor(Color.parseColor("#065F46"))
        } else {
            btnStart.text = "Grant Usage Access"
            btnStart.setBackgroundColor(Color.parseColor("#92400E"))
        }

        if (::btnAccessibility.isInitialized) {
            if (isAccessibilityServiceEnabled()) {
                btnAccessibility.text = "Browser: ON"
                btnAccessibility.setBackgroundColor(Color.parseColor("#065F46"))
                btnAccessibility.setTextColor(Color.WHITE)
            } else {
                btnAccessibility.text = "Enable Browser"
                btnAccessibility.setBackgroundColor(Color.parseColor("#92400E"))
                btnAccessibility.setTextColor(Color.WHITE)
            }
        }

        if (::btnNotification.isInitialized) {
            if (isNotificationListenerEnabled()) {
                btnNotification.text = "Media: ON"
                btnNotification.setBackgroundColor(Color.parseColor("#065F46"))
                btnNotification.setTextColor(Color.WHITE)
            } else {
                btnNotification.text = "Enable Media"
                btnNotification.setBackgroundColor(Color.parseColor("#92400E"))
                btnNotification.setTextColor(Color.WHITE)
            }
        }
    }
}
