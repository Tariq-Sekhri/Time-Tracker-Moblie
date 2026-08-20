package ca.tariq_sekhri.time_tracker

import java.util.concurrent.ConcurrentHashMap

object BrowserTitleCache {
    private val latestTitles = ConcurrentHashMap<String, String>()

    fun setLatestTitle(packageName: String, title: String) {
        if (title.isNotBlank()) {
            latestTitles[packageName] = title
        }
    }

    fun getLatestTitle(packageName: String): String? {
        return latestTitles[packageName]
    }

    fun isBrowserPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower.contains("chrome") ||
                lower.contains("browser") ||
                lower.contains("firefox") ||
                lower.contains("opera") ||
                lower.contains("brave") ||
                lower.contains("edge") ||
                lower.contains("sbrowser") ||
                lower.contains("duckduckgo") ||
                lower.contains("vivaldi")
    }
}
