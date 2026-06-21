package ca.tariq_sekhri.time_tracker

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import java.io.IOException

class SyncActivity : AppCompatActivity() {

    private lateinit var syncManager: SyncManager
    private val client = OkHttpClient()

    private lateinit var editIp: EditText
    private lateinit var tvLabel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var syncPanel: LinearLayout
    private lateinit var btnCheck: Button
    private lateinit var btnPush: Button
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

        tvLabel = TextView(this).apply {
            text = "Server IP / Address"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
            setPadding(0, 0, 0, dp(4))
        }
        content.addView(tvLabel)

        editIp = EditText(this).apply {
            hint = "e.g. 192.168.1.100"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(syncManager.getServerIp() ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        content.addView(editIp)

        tvStatus = TextView(this).apply {
            text = if (syncManager.isServerLocked()) "Server locked" else "Not connected"
            setTextColor(Color.GRAY)
            textSize = 16f
            setPadding(0, dp(12), 0, dp(16))
        }
        content.addView(tvStatus)

        syncPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111827"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        content.addView(syncPanel, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(16)
        })

        syncPanel.addView(TextView(this).apply {
            text = "Next automatic push"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
        })

        tvCountdown = TextView(this).apply {
            text = "Waiting for tracker..."
            setTextColor(Color.WHITE)
            textSize = 28f
            setPadding(0, dp(4), 0, 0)
        }
        syncPanel.addView(tvCountdown)

        btnCheck = Button(this).apply {
            transformationMethod = null
            setBackgroundColor(Color.parseColor("#374151"))
            setTextColor(Color.WHITE)
            setOnClickListener { 
                if (syncManager.isServerLocked()) {
                    syncManager.unlockServer()
                    updateUiState()
                } else {
                    checkAndLockServer()
                }
            }
        }
        content.addView(btnCheck)

        btnPush = Button(this).apply {
            text = "Push Logs"
            transformationMethod = null
            setBackgroundColor(Color.parseColor("#065F46"))
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(12))
            val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) }
            layoutParams = lp
            setOnClickListener { pushLogs() }
        }
        content.addView(btnPush)

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
        val locked = syncManager.isServerLocked()
        tvLabel.visibility = if (locked) View.GONE else View.VISIBLE
        editIp.visibility = if (locked) View.GONE else View.VISIBLE
        editIp.isEnabled = !locked
        syncPanel.visibility = if (locked) View.VISIBLE else View.GONE
        btnCheck.text = if (locked) "Change Server" else "Check & Lock"
        btnPush.visibility = if (locked) View.VISIBLE else View.GONE
        btnPush.isEnabled = locked
        tvStatus.text = if (locked) "Server locked and ready" else "Enter server IP to begin"
        tvStatus.setTextColor(if (locked) Color.GREEN else Color.GRAY)
        updateCountdown()
    }

    private fun updateCountdown() {
        if (!syncManager.isServerLocked()) return
        val seconds = syncManager.secondsUntilNextAutoPush()
        tvCountdown.text = if (seconds == null) {
            "Waiting for tracker..."
        } else {
            formatCountdown(seconds)
        }
    }

    private fun checkAndLockServer() {
        val ip = editIp.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter IP address", Toast.LENGTH_SHORT).show()
            return
        }

        val url = if (ip.startsWith("http")) "$ip/v1/check" else "http://$ip:3000/v1/check"
        
        tvStatus.text = "Checking..."
        tvStatus.setTextColor(Color.YELLOW)

        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvStatus.text = "Failed: ${e.message}"
                    tvStatus.setTextColor(Color.RED)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()?.replace("\"", "")
                runOnUiThread {
                    if (response.isSuccessful && body == "Time Tracker Backend v1") {
                        syncManager.lockServer(ip)
                        syncManager.resetNextAutoPush()
                        updateUiState()
                        tvStatus.text = "Success! Locked to $body"
                        tvStatus.setTextColor(Color.GREEN)
                    } else {
                        tvStatus.text = "Invalid response from server: $body"
                        tvStatus.setTextColor(Color.RED)
                    }
                }
            }
        })
    }

    private fun pushLogs() {
        tvStatus.text = "Pushing logs..."
        tvStatus.setTextColor(Color.YELLOW)
        syncManager.pushLogs { success, message ->
            runOnUiThread {
                tvStatus.text = message
                tvStatus.setTextColor(if (success) Color.GREEN else Color.RED)
                if (success) {
                    Toast.makeText(this@SyncActivity, "Sync complete", Toast.LENGTH_SHORT).show()
                }
                updateCountdown()
            }
        }
    }

    private fun formatCountdown(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}m ${seconds.toString().padStart(2, '0')}s"
    }
}
