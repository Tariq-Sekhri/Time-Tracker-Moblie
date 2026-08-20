package ca.tariq_sekhri.time_tracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class NotesActivity : AppCompatActivity() {

    private lateinit var editText: EditText
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.BLACK)
        }

        editText = EditText(this).apply {
            hint = "Write your notes here..."
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = null
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setText(prefs.getString(KEY_NOTES, ""))
            setSelection(text.length)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(KEY_NOTES, s?.toString() ?: "").apply()
                }
            })
        }

        scroll.addView(editText)

        setContentViewWithHeader(
            title = "Notes",
            content = scroll,
            actions = listOf(
                HeaderAction("Copy") { copyNotes() },
                HeaderAction("Clear") { confirmClear() }
            )
        )
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putString(KEY_NOTES, editText.text.toString()).apply()
    }

    private fun copyNotes() {
        val text = editText.text.toString()
        if (text.isNotBlank()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Notes", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Notes copied to clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No notes to copy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        if (editText.text.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("Clear notes?")
            .setMessage("Are you sure you want to clear your notes?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                editText.setText("")
                prefs.edit().remove(KEY_NOTES).apply()
                Toast.makeText(this, "Notes cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    companion object {
        private const val PREFS_NAME = "TimeTrackerNotes"
        private const val KEY_NOTES = "notes_content"
    }
}
