package ca.tariq_sekhri.time_tracker

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.URI
import java.util.ArrayDeque

class BrowserTrackerService : AccessibilityService() {

    private val dbHelper by lazy { DatabaseHelper(this) }
    private var currentSessionId: Long? = null
    private var currentFormattedLabel: String? = null
    private var currentPackage: String? = null
    private var sessionStartMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "BrowserTrackerService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // If user moved to a non-browser app, finalize any active browser session
        if (!BrowserTitleCache.isBrowserPackage(packageName)) {
            closeActiveSession("APP_SWITCH")
            return
        }

        val rootNode = rootInActiveWindow ?: event.source ?: return
        val rawTitleOrUrl = extractTitleOrUrl(rootNode, packageName)

        if (!rawTitleOrUrl.isNullOrBlank()) {
            val cleanTitle = cleanTitleOrUrl(rawTitleOrUrl)
            handleTitleChange(packageName, cleanTitle)
        }
    }

    private fun extractTitleOrUrl(root: AccessibilityNodeInfo, packageName: String): String? {
        // 1. Direct search using known resource IDs
        val knownIds = listOf(
            "com.android.chrome:id/url_bar",
            "org.chromium.chrome:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.vivaldi.browser:id/url_bar",
            "com.vivaldi.browser:id/location_bar",
            "com.microsoft.emmx:id/url_bar",
            "com.microsoft.emmx:id/search_box",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/url_bar_title",
            "org.mozilla.firefox:id/toolbar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.opera.browser:id/url_field",
            "com.duckduckgo.mobile.android:id/omnibarTextInput"
        )

        for (id in knownIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val text = node.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    if (!text.isNullOrBlank()) return text
                }
            }
        }

        // 2. Breadth-first search for address bar / URL / Webview title nodes
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var inspectedCount = 0

        while (queue.isNotEmpty() && inspectedCount < 120) {
            inspectedCount++
            val node = queue.removeFirst()

            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            // Look for URL / omnibox inputs
            if (resId.contains("url") || resId.contains("address") || resId.contains("location") ||
                resId.contains("omnibox") || resId.contains("toolbar") || resId.contains("search_box")
            ) {
                val candidate = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }
                if (!candidate.isNullOrBlank() && !candidate.equals("Search", ignoreCase = true)) {
                    return candidate
                }
            }

            // Look for web text containing domain markers
            if (!text.isNullOrBlank() && (text.contains("http://") || text.contains("https://") ||
                        text.contains("wikipedia.org") || text.contains(".com") || text.contains(".org") || text.contains(".io"))
            ) {
                return text
            }

            // Also check WebView content description for title
            if (node.className?.toString()?.contains("WebView") == true) {
                if (!desc.isNullOrBlank()) return desc
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return null
    }

    private fun cleanTitleOrUrl(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("http://")) text = text.removePrefix("http://")
        if (text.startsWith("https://")) text = text.removePrefix("https://")
        if (text.startsWith("www.")) text = text.removePrefix("www.")

        // Special handling for Wikipedia
        if (text.contains("wikipedia.org", ignoreCase = true)) {
            val afterWiki = text.substringAfter("/wiki/", "")
            if (afterWiki.isNotBlank()) {
                val articleName = afterWiki.substringBefore("?").substringBefore("#").replace("_", " ")
                return "$articleName - Wikipedia"
            }
            return "Wikipedia"
        }

        // Special handling for major domains
        if (text.startsWith("youtube.com", ignoreCase = true) || text.startsWith("m.youtube.com", ignoreCase = true)) {
            return "YouTube"
        }
        if (text.startsWith("reddit.com", ignoreCase = true) || text.startsWith("old.reddit.com", ignoreCase = true)) {
            return "Reddit"
        }
        if (text.startsWith("github.com", ignoreCase = true)) {
            val path = text.removePrefix("github.com/").trim('/')
            return if (path.isNotBlank()) "$path - GitHub" else "GitHub"
        }

        // If it looks like a URL/domain
        if (text.contains(".")) {
            val slashIdx = text.indexOf('/')
            val host = if (slashIdx != -1) text.substring(0, slashIdx) else text
            val path = if (slashIdx != -1) text.substring(slashIdx + 1).trim('/') else ""

            if (path.isNotBlank() && !path.contains("?") && path.length < 35) {
                return "$path - $host"
            }
            return host
        }

        return text
    }

    private fun handleTitleChange(packageName: String, cleanTitle: String) {
        val appName = getAppName(packageName)
        val formattedLabel = "$cleanTitle - $appName"

        // Cache the title immediately so UsageLogImporter and AppMetadataHelper can use it
        BrowserTitleCache.setLatestTitle(packageName, cleanTitle)

        if (formattedLabel == currentFormattedLabel && packageName == currentPackage) {
            // Keep duration growing
            currentSessionId?.let { id ->
                val now = System.currentTimeMillis()
                val durationSec = (now - sessionStartMs) / 1000L
                if (durationSec > 0) {
                    val cv = android.content.ContentValues().apply {
                        put(DatabaseHelper.COLUMN_END_TIMESTAMP, now)
                        put(DatabaseHelper.COLUMN_DURATION, durationSec)
                    }
                    dbHelper.writableDatabase.update(
                        DatabaseHelper.TABLE_LOGS,
                        cv,
                        "${DatabaseHelper.COLUMN_ID}=?",
                        arrayOf(id.toString())
                    )
                }
            }
            return
        }

        val now = System.currentTimeMillis()

        // Close previous session
        closeActiveSession("URL_CHANGED")

        // Start new session
        val metadata = RichLogMetadata(
            packageName = packageName,
            appLabel = formattedLabel,
            startEventType = "BROWSER_URL_CHANGED"
        )
        currentSessionId = dbHelper.insertLog(metadata, now)
        currentFormattedLabel = formattedLabel
        currentPackage = packageName
        sessionStartMs = now
    }

    private fun closeActiveSession(reason: String) {
        val id = currentSessionId ?: return
        val now = System.currentTimeMillis()
        dbHelper.endLog(id, now, reason)
        currentSessionId = null
        currentFormattedLabel = null
        currentPackage = null
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    override fun onInterrupt() {
        closeActiveSession("INTERRUPTED")
    }

    companion object {
        private const val TAG = "BrowserTrackerService"
    }
}
