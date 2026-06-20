package ca.sekhrit.timetrackermoblie

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*

class SyncActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var dbHelper: DatabaseHelper
    private val client = OkHttpClient()
    private val gson = Gson()

    private lateinit var editIp: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnCheck: Button
    private lateinit var btnPush: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("TimeTrackerPrefs", Context.MODE_PRIVATE)
        dbHelper = DatabaseHelper(this)

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
            setText(prefs.getString("server_ip", ""))
            inputType = InputType.TYPE_CLASS_TEXT
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        content.addView(editIp)

        tvStatus = TextView(this).apply {
            text = if (prefs.getString("server_ip", null) != null) "Server saved" else "Not connected"
            setTextColor(Color.GRAY)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        }
        content.addView(tvStatus)

        btnCheck = Button(this).apply {
            text = "Check & Save"
            transformationMethod = null
            setBackgroundColor(Color.parseColor("#374151"))
            setTextColor(Color.WHITE)
            setOnClickListener { checkServer() }
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
            isEnabled = prefs.getString("server_ip", null) != null
            setOnClickListener { pushLogs() }
        }
        content.addView(btnPush)

        setContentViewWithHeader("Cloud Sync", rootScroll)
    }

    private fun checkServer() {
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
                        prefs.edit().putString("server_ip", ip).apply()
                        tvStatus.text = "Success! Connected to $body"
                        tvStatus.setTextColor(Color.GREEN)
                        btnPush.isEnabled = true
                    } else {
                        tvStatus.text = "Invalid response from server: $body"
                        tvStatus.setTextColor(Color.RED)
                    }
                }
            }
        })
    }

    private fun pushLogs() {
        val ip = prefs.getString("server_ip", null) ?: return
        val url = if (ip.startsWith("http")) "$ip/v1/upload_logs" else "http://$ip:3000/v1/upload_logs"

        val device = getDeviceMetadata()
        val logs = dbHelper.getAllLogs().map { entry ->
            BackendLog(
                id = entry.id,
                deviceId = 0, // Server might re-assign or we can use 0
                app = entry.appLabel ?: entry.packageName,
                timestamp = entry.startTimestamp / 1000,
                duration = entry.duration
            )
        }

        if (logs.isEmpty()) {
            Toast.makeText(this, "No logs to push", Toast.LENGTH_SHORT).show()
            return
        }

        val payload = LogPayload(device, logs)
        val json = gson.toJson(payload)
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        tvStatus.text = "Pushing ${logs.size} logs..."
        tvStatus.setTextColor(Color.YELLOW)

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvStatus.text = "Push failed: ${e.message}"
                    tvStatus.setTextColor(Color.RED)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        tvStatus.text = "Successfully pushed ${logs.size} logs"
                        tvStatus.setTextColor(Color.GREEN)
                        Toast.makeText(this@SyncActivity, "Sync complete", Toast.LENGTH_SHORT).show()
                    } else {
                        tvStatus.text = "Server error: ${response.code}"
                        tvStatus.setTextColor(Color.RED)
                    }
                }
            }
        })
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceMetadata(): BackendDevice {
        val uuid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: UUID.randomUUID().toString()
        val name = "${Build.MANUFACTURER} ${Build.MODEL}"
        return BackendDevice(id = 0, name = name, uuid = uuid)
    }

    // Backend Models
    data class BackendDevice(val id: Long, val name: String, val uuid: String)
    data class BackendLog(
        val id: Long,
        @SerializedName("device_id") val deviceId: Long,
        val app: String,
        val timestamp: Long,
        val duration: Long
    )
    data class LogPayload(val device: BackendDevice, val logs: List<BackendLog>)
}
