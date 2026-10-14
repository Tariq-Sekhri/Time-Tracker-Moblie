package ca.tariq_sekhri.time_tracker

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/** Start, rename, and stop the manual timer shared with the desktop app. */
class ManualTimerCard(private val activity: AppCompatActivity) {
    private val muted = Color.rgb(157, 164, 178)
    private val purple = Color.rgb(177, 139, 255)
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { showElapsed(); ticker.postDelayed(this, 1_000L) }
    }
    private var binding = false
    private var shownProjects: List<String> = emptyList()

    private val status = TextView(activity).apply { textSize = 12f; setTextColor(muted) }
    private val message = TextView(activity).apply { textSize = 13f; setTextColor(muted); setPadding(0, activity.dp(6), 0, 0) }
    private val titleInput = EditText(activity).apply {
        hint = "What are you working on?"; setHintTextColor(Color.rgb(107, 114, 128)); setTextColor(Color.WHITE); textSize = 15f
        setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; imeOptions = EditorInfo.IME_ACTION_DONE
        filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        backgroundTintList = android.content.res.ColorStateList.valueOf(purple)
        setText(draftTitle)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!binding) draftTitle = s?.toString() ?: "" }
        })
        setOnFocusChangeListener { _, focused -> if (!focused && !binding) saveRunningDetails() }
        setOnEditorActionListener { view, _, _ -> view.clearFocus(); true }
    }
    private val projectPicker = Spinner(activity).apply {
        backgroundTintList = android.content.res.ColorStateList.valueOf(purple)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (binding) return
                draftProject = shownProjects.getOrNull(position - 1)
                saveRunningDetails()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    private val elapsed = TextView(activity).apply { textSize = 26f; typeface = Typeface.MONOSPACE; setTextColor(Color.WHITE) }
    private val action = Button(activity).apply {
        textSize = 15f; transformationMethod = null; setTextColor(Color.WHITE); minHeight = 0; minimumHeight = 0
        setOnClickListener { onAction() }
    }

    val view: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(28, 32, 42))
        setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply { text = "Manual timer"; setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(status)
        })
        addView(titleInput, LinearLayout.LayoutParams(-1, activity.dp(52)).apply { topMargin = activity.dp(6) })
        addView(projectPicker, LinearLayout.LayoutParams(-1, activity.dp(48)))
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, activity.dp(8), 0, 0)
            addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
            addView(action, LinearLayout.LayoutParams(activity.dp(110), activity.dp(48)))
        })
        addView(message)
    }

    init { bind() }

    /** Refreshes from [ManualTimerSync]; called whenever the shared timer changes. */
    fun bind() {
        binding = true
        val timer = ManualTimerSync.timer
        val ready = ManualTimerSync.status == ManualTimerSync.Status.CONNECTED
        status.text = when (ManualTimerSync.status) {
            ManualTimerSync.Status.CONNECTED -> "● Live"
            ManualTimerSync.Status.CONNECTING -> "Connecting…"
            else -> "Offline"
        }
        status.setTextColor(if (ready) Color.rgb(134, 239, 172) else muted)
        message.text = when (ManualTimerSync.status) {
            ManualTimerSync.Status.NOT_READY -> "Register this device on the Sync screen to share a timer with your desktop."
            ManualTimerSync.Status.OLD_SERVER -> "Update the sync server to share a timer with your desktop."
            ManualTimerSync.Status.OFFLINE -> "Can't reach the sync server. Retrying…"
            else -> ""
        }
        message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
        // Don't overwrite what the user is typing; their edit is saved on Done.
        if (timer != null && !titleInput.hasFocus()) { titleInput.setText(timer.title); draftTitle = timer.title }
        if (timer != null) draftProject = timer.project_name
        bindProjects(draftProject)
        action.text = if (timer == null) "Start" else "Stop"
        action.setBackgroundColor(if (timer == null) Color.rgb(101, 70, 164) else Color.rgb(153, 27, 27))
        action.isEnabled = ready
        action.alpha = if (ready) 1f else .5f
        ticker.removeCallbacks(tick)
        if (timer != null) ticker.post(tick) else showElapsed()
        binding = false
    }

    fun release() = ticker.removeCallbacks(tick)

    private fun bindProjects(selected: String?) {
        val names = ManualTimerSync.projects.map { it.name }.let { names ->
            if (selected != null && names.none { it.equals(selected, ignoreCase = true) }) names + selected else names
        }
        if (names != shownProjects || projectPicker.adapter == null) {
            shownProjects = names
            projectPicker.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("No project") + names).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        val index = shownProjects.indexOfFirst { it.equals(selected, ignoreCase = true) }
        projectPicker.setSelection(index + 1, false)
        // Spinner draws its selected text with the theme colour; keep it readable on the dark card.
        projectPicker.post { (projectPicker.selectedView as? TextView)?.setTextColor(Color.WHITE) }
    }

    private fun showElapsed() {
        val timer = ManualTimerSync.timer
        val seconds = if (timer == null) 0L else (System.currentTimeMillis() / 1000 - timer.start_time).coerceAtLeast(0)
        elapsed.text = "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
        elapsed.setTextColor(if (timer == null) muted else Color.WHITE)
    }

    private fun saveRunningDetails() {
        val timer = ManualTimerSync.timer ?: return
        val title = titleInput.text.toString().trim()
        if (title == timer.title && draftProject == timer.project_name) return
        ManualTimerSync.save(title, draftProject, ::showError)
    }

    private fun onAction() {
        val title = titleInput.text.toString().trim()
        // Stop records the typed name itself, so losing focus must not save it separately.
        binding = true; titleInput.clearFocus(); binding = false
        if (ManualTimerSync.timer == null) ManualTimerSync.save(title, draftProject, ::showError)
        else ManualTimerSync.stop(title, draftProject, ::showError) {
            draftTitle = ""; draftProject = null
            binding = true; titleInput.setText(""); binding = false
            bind()
            Toast.makeText(activity, "Time recorded", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    companion object {
        // Survives page re-renders so a half-typed task name isn't lost.
        private var draftTitle = ""
        private var draftProject: String? = null
    }
}
