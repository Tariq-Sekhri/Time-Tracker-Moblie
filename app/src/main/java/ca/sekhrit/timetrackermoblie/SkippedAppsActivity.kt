package ca.sekhrit.timetrackermoblie

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

        val pad = (12 * resources.displayMetrics.density).toInt()
        listView = ListView(this).apply {
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            setBackgroundColor(Color.rgb(18, 18, 18))
            divider = null
        }

        setContentViewWithHeader(
            title = "Skipped Apps",
            content = listView,
            actions = listOf(
                HeaderAction("Refresh") { refresh() },
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
                text.setTextColor(Color.WHITE)
                text.textSize = 15f
                text.setPadding(20, 18, 20, 18)
                view.setBackgroundColor(Color.rgb(18, 18, 18))
                return view
            }
        }
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = "com.example.app or .*youtube.*"
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Add skipped app")
            .setMessage("Matches package name or app label. Plain text and regex both work.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val pattern = input.text.toString().trim()
                if (pattern.isBlank()) {
                    Toast.makeText(this, "Pattern is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                dbHelper.addSkippedApp(pattern)
                refresh()
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
}
