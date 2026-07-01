package ca.tariq_sekhri.time_tracker

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SyncActivity : AppCompatActivity() {

    private lateinit var syncManager: SyncManager

    private lateinit var statusCardsRow: LinearLayout
    private lateinit var tvServerIpValue: TextView
    private lateinit var tvCountdownValue: TextView
    private lateinit var setupSection: LinearLayout
    private lateinit var editIp: EditText
    private lateinit var btnCheck: Button
    private lateinit var tvServerError: TextView
    private lateinit var deviceSection: LinearLayout
    private lateinit var registerRow: LinearLayout
    private lateinit var btnRegister: Button
    private lateinit var tvRegisteredBadge: TextView
    private lateinit var tvDeviceUuid: TextView
    private lateinit var btnPush: Button
    private lateinit var tvOperationStatus: TextView
    private lateinit var btnChangeServer: Button

    private var isSyncing = false
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownTicker = object : Runnable {
        override fun run() {
            updateCountdown()
            countdownHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncManager = SyncManager(this)

        val rootScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.BLACK)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        rootScroll.addView(content)

        statusCardsRow = SyncUi.statusCardsRow(this)
        content.addView(statusCardsRow)

        val serverIpCard = SyncUi.cardInRow(this) {
            addView(SyncUi.cardLabel(this@SyncActivity, "Server IP"))
            tvServerIpValue = SyncUi.cardValue(this@SyncActivity, "—", mono = true)
            addView(tvServerIpValue)
        }
        statusCardsRow.addView(serverIpCard)

        val countdownCard = SyncUi.cardPanel(this) {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(SyncUi.cardLabel(this@SyncActivity, "Next automatic push"))
            tvCountdownValue = SyncUi.cardValue(this@SyncActivity, "—", mono = true).apply {
                textSize = 18f
            }
            addView(tvCountdownValue)
        }
        statusCardsRow.addView(countdownCard)

        setupSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        content.addView(setupSection)

        setupSection.addView(TextView(this).apply {
            text = "No server configured. Enter a server IP."
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
        })

        val ipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        editIp = EditText(this).apply {
            hint = "e.g. 192.168.1.100"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(8)
            }
        }
        ipRow.addView(editIp)

        btnCheck = Button(this).apply {
            text = "Check"
            transformationMethod = null
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener { checkServer() }
        }
        ipRow.addView(btnCheck)
        setupSection.addView(ipRow)

        tvServerError = TextView(this).apply {
            setTextColor(Color.parseColor("#F87171"))
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        setupSection.addView(tvServerError)

        deviceSection = SyncUi.cardPanel(this) {
        }.apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = dp(16)
            layoutParams = lp
        }
        content.addView(deviceSection)

        deviceSection.addView(SyncUi.sectionTitle(this, "This device"))
        deviceSection.addView(SyncUi.sectionSubtitle(this, "Register to upload logs to the server"))

        registerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        deviceSection.addView(registerRow)

        btnRegister = Button(this).apply {
            text = "Register"
            transformationMethod = null
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener { showRegisterConfirm() }
        }
        registerRow.addView(btnRegister)

        tvRegisteredBadge = TextView(this).apply {
            text = "Registered"
            setTextColor(Color.parseColor("#86EFAC"))
            setBackgroundColor(Color.parseColor("#14532D"))
            textSize = 13f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        registerRow.addView(tvRegisteredBadge)

        tvDeviceUuid = TextView(this).apply {
            setTextColor(Color.parseColor("#D1D5DB"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        deviceSection.addView(tvDeviceUuid)

        btnPush = Button(this).apply {
            text = "Push Now"
            transformationMethod = null
            setBackgroundColor(Color.parseColor("#065F46"))
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(16)
            }
            setOnClickListener { pushNow() }
        }
        deviceSection.addView(btnPush)

        tvOperationStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        deviceSection.addView(tvOperationStatus)

        btnChangeServer = Button(this).apply {
            text = "Change server"
            transformationMethod = null
            setBackgroundColor(Color.parseColor("#374151"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(24)
            }
            setOnClickListener { changeServer() }
        }
        content.addView(btnChangeServer)

        updateUiState()
        setContentViewWithHeader("Cloud Sync", rootScroll)
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
        countdownHandler.removeCallbacks(countdownTicker)
        countdownHandler.post(countdownTicker)
    }

    override fun onPause() {
        countdownHandler.removeCallbacks(countdownTicker)
        super.onPause()
    }

    private fun updateUiState() {
        val hasServer = syncManager.hasServerIp()
        val registered = syncManager.isRegistered()

        statusCardsRow.visibility = if (hasServer) View.VISIBLE else View.GONE
        setupSection.visibility = if (hasServer) View.GONE else View.VISIBLE
        btnChangeServer.visibility = if (hasServer) View.VISIBLE else View.GONE

        if (hasServer) {
            tvServerIpValue.text = syncManager.getServerIp()
        }

        btnRegister.visibility = if (registered) View.GONE else View.VISIBLE
        tvRegisteredBadge.visibility = if (registered) View.VISIBLE else View.GONE
        btnPush.isEnabled = registered && !isSyncing

        val uuid = syncManager.getDeviceUuid()
        if (registered && uuid != null) {
            tvDeviceUuid.text = "UUID: $uuid"
            tvDeviceUuid.visibility = View.VISIBLE
        } else {
            tvDeviceUuid.visibility = View.GONE
        }

        deviceSection.visibility = if (hasServer) View.VISIBLE else View.GONE
        updateCountdown()
    }

    private fun updateCountdown() {
        if (!syncManager.hasServerIp()) {
            tvCountdownValue.text = "—"
            return
        }
        if (isSyncing) {
            tvCountdownValue.text = "Syncing…"
            return
        }
        if (!syncManager.isRegistered()) {
            tvCountdownValue.text = "—"
            return
        }
        val seconds = syncManager.secondsUntilNextAutoPush()
        tvCountdownValue.text = if (seconds == null) {
            "—"
        } else {
            formatCountdown(seconds)
        }
    }

    private fun checkServer() {
        val ip = editIp.text.toString().trim()
        if (ip.isEmpty()) {
            showServerError("Enter a server IP")
            return
        }

        showServerError(null)
        btnCheck.isEnabled = false
        btnCheck.text = "Checking..."

        syncManager.checkServer(ip) { result ->
            runOnUiThread {
                btnCheck.isEnabled = true
                btnCheck.text = "Check"
                result.fold(
                    onSuccess = {
                        showServerError(null)
                        editIp.setText("")
                        updateUiState()
                        tvOperationStatus.text = "Server connected"
                        tvOperationStatus.setTextColor(Color.parseColor("#86EFAC"))
                    },
                    onFailure = { e ->
                        showServerError(e.message ?: "Check failed")
                        tvOperationStatus.text = ""
                    }
                )
            }
        }
    }

    private fun showRegisterConfirm() {
        if (!syncManager.hasServerIp()) {
            Toast.makeText(this, "Configure server first", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Register this device?")
            .setMessage("Registering means uploading your logs to the server.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Register") { _, _ -> registerDevice() }
            .show()
    }

    private fun registerDevice() {
        btnRegister.isEnabled = false
        tvOperationStatus.text = "Registering..."
        tvOperationStatus.setTextColor(Color.parseColor("#FBBF24"))

        syncManager.register { success, message ->
            if (!success) {
                runOnUiThread {
                    btnRegister.isEnabled = true
                    tvOperationStatus.text = message
                    tvOperationStatus.setTextColor(Color.parseColor("#F87171"))
                }
                return@register
            }
            syncManager.uploadAllLogs { uploadSuccess, uploadMessage ->
                runOnUiThread {
                    btnRegister.isEnabled = true
                    updateUiState()
                    if (uploadSuccess) {
                        tvOperationStatus.text = uploadMessage
                        tvOperationStatus.setTextColor(Color.parseColor("#86EFAC"))
                        Toast.makeText(this@SyncActivity, uploadMessage, Toast.LENGTH_SHORT).show()
                    } else {
                        tvOperationStatus.text = "Registered, but $uploadMessage"
                        tvOperationStatus.setTextColor(Color.parseColor("#FBBF24"))
                    }
                }
            }
        }
    }

    private fun pushNow() {
        isSyncing = true
        btnPush.isEnabled = false
        tvOperationStatus.text = "Syncing..."
        tvOperationStatus.setTextColor(Color.parseColor("#FBBF24"))
        updateCountdown()

        syncManager.sync { success, message ->
            runOnUiThread {
                isSyncing = false
                btnPush.isEnabled = syncManager.isRegistered()
                tvOperationStatus.text = message
                tvOperationStatus.setTextColor(
                    if (success) Color.parseColor("#86EFAC") else Color.parseColor("#F87171")
                )
                if (success) {
                    Toast.makeText(this@SyncActivity, message, Toast.LENGTH_SHORT).show()
                }
                updateCountdown()
            }
        }
    }

    private fun changeServer() {
        AlertDialog.Builder(this)
            .setTitle("Change server?")
            .setMessage("This clears your server connection and device registration.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Change") { _, _ ->
                syncManager.unlockServer()
                isSyncing = false
                tvOperationStatus.text = ""
                updateUiState()
            }
            .show()
    }

    private fun showServerError(message: String?) {
        if (message.isNullOrBlank()) {
            tvServerError.visibility = View.GONE
            tvServerError.text = ""
        } else {
            tvServerError.visibility = View.VISIBLE
            tvServerError.text = message
        }
    }

    private fun formatCountdown(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}
