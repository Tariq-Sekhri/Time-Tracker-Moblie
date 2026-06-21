package ca.tariq_sekhri.time_tracker

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import java.io.IOException

class SyncActivity : AppCompatActivity() {

    private lateinit var syncManager: SyncManager
    private val client = OkHttpClient()

    private lateinit var editIp: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnCheck: Button
    private lateinit var btnPush: Button

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

        val tvLabel = TextView(this).apply {
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
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        }
        content.addView(tvStatus)

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

    private fun updateUiState() {
        val locked = syncManager.isServerLocked()
        editIp.isEnabled = !locked
        btnCheck.text = if (locked) "Change Server" else "Check & Lock"
        btnPush.isEnabled = locked
        tvStatus.text = if (locked) "Server locked and ready" else "Enter server IP to begin"
        tvStatus.setTextColor(if (locked) Color.GREEN else Color.GRAY)
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
            }
        }
    }
}
