package ca.tariq_sekhri.time_tracker

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SkippedAppsActivity : AppCompatActivity() {
    private val dbHelper by lazy { DatabaseHelper(this) }
    private lateinit var listView: ListView
    private var rows: List<SkippedAppRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        listView = ListView(this).apply {
            clipToPadding = false
            setBackgroundColor(Color.BLACK)
            divider = null
        }

        setContentViewWithHeader(
            title = "Skipped Apps",
            content = listView,
            actions = listOf(
                HeaderAction("Clear All") { showClearAllConfirm() },
                HeaderAction("Add") { showAddDialog() }
            )
        )

        listView.setOnItemClickListener { _, _, position, _ ->
            rows.getOrNull(position)?.let { confirmDelete(it) }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::listView.isInitialized) {
            refresh()
        }
    }

    private fun refresh() {
        rows = dbHelper.getSkippedApps()
        val displayRows = if (rows.isEmpty()) {
            listOf("No skipped apps yet.\nAdd package names, app labels, or regex patterns.")
        } else {
            rows.map { it.pattern }
        }
        listView.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayRows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val text = view.findViewById<TextView>(android.R.id.text1)
                text.setTextColor(Color.parseColor("#D1D5DB"))
                text.textSize = 14f
                text.setPadding(dp(16), dp(16), dp(16), dp(16))
                view.setBackgroundColor(if (position % 2 == 0) Color.BLACK else Color.parseColor("#111827"))
                return view
            }
        }
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = "com.example.app or .*youtube.*"
            setSingleLine(true)
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
                            refresh()
                        }
                        .show()
                } else {
                    dbHelper.addSkippedApp(pattern)
                    refresh()
                }
            }
            .show()
    }

    private fun confirmDelete(row: SkippedAppRow) {
        AlertDialog.Builder(this)
            .setTitle("Remove skipped app?")
            .setMessage(row.pattern)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                dbHelper.deleteSkippedApp(row.id)
                refresh()
            }
            .show()
    }

    private fun showClearAllConfirm() {
        AlertDialog.Builder(this)
            .setTitle("Clear all skipped apps?")
            .setMessage("This will remove all your custom skip patterns.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear All") { _, _ ->
                dbHelper.deleteAllSkippedApps()
                refresh()
                Toast.makeText(this, "Skipped apps cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
