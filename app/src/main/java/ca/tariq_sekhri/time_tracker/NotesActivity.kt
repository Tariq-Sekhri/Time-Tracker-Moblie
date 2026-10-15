package ca.tariq_sekhri.time_tracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class NotesActivity : AppCompatActivity() {

    private lateinit var editText: NotesEditor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        editText = NotesEditor(this)

        setContentViewWithHeader(
            title = "Notes",
            content = editText,
            actions = listOf(
                HeaderAction("Copy") { copyNotes() },
                HeaderAction("Clear") { confirmClear() }
            ),
            showBack = false,
            bottomNavigation = notesBottomNavigation()
        )
    }

    private fun notesBottomNavigation() = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(14, 16, 21))
        setPadding(dp(8), dp(6), dp(8), dp(12))
        listOf("◷" to "dashboard", "☷" to "timeline", "▥" to "browser", "✎" to "notes", "⚙" to "settings").forEach { (icon, destination) ->
            addView(Button(this@NotesActivity).apply {
                text = icon; textSize = 28f; transformationMethod = null; setTextColor(Color.WHITE)
                setBackgroundColor(if (destination == "notes") Color.rgb(101, 70, 164) else Color.rgb(42, 47, 60))
                minHeight = 0; minimumHeight = 0
                if (destination != "notes") setOnClickListener { openMain(destination) }
            }, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
    }
    private fun openMain(destination: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_PAGE, destination).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun copyNotes() = editText.copyNotes()

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear notes?")
            .setMessage("Are you sure you want to clear your notes?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> editText.clearNotes() }
            .show()
    }

}
