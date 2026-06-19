package ca.sekhrit.timetrackermoblie

import android.os.Bundle
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsActivity : AppCompatActivity() {
    private val dbHelper by lazy { DatabaseHelper(this) }
    private lateinit var listView: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val topPadding = (16 * resources.displayMetrics.density).toInt()
        val sidePadding = (12 * resources.displayMetrics.density).toInt()
        val bottomPadding = (16 * resources.displayMetrics.density).toInt()
        listView = ListView(this).apply {
            setPadding(sidePadding, topPadding, sidePadding, bottomPadding)
            clipToPadding = false
            setBackgroundColor(Color.rgb(18, 18, 18))
            divider = null
        }
        setContentViewWithHeader(
            title = "Logs",
            content = listView,
            actions = listOf(
                HeaderAction("Refresh") { refreshLogs() },
                HeaderAction("Clear") {
                    dbHelper.deleteAllLogs()
                    refreshLogs()
                }
            )
        )

        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        if (::listView.isInitialized) {
            refreshLogs()
        }
    }

    private fun refreshLogs() {
        val logs = dbHelper.getAllLogs()
        val displayList = logs.map { formatLogEntry(it) }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val text = view.findViewById<TextView>(android.R.id.text1)
                text.setTextColor(Color.WHITE)
                text.textSize = 14f
                text.setPadding(20, 16, 20, 16)
                view.setBackgroundColor(Color.rgb(18, 18, 18))
                return view
            }
        }
        listView.adapter = adapter
    }

    private fun formatLogEntry(entry: LogEntry): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val name = entry.appLabel ?: entry.packageName
        val start = sdf.format(Date(entry.startTimestamp))
        val end = entry.endTimestamp?.let { sdf.format(Date(it)) } ?: "running"
        val duration = formatDuration(entry.duration)
        
        return "$name\n${entry.packageName}\n$start - $end   $duration"
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

}
