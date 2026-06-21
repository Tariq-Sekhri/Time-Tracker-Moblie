package ca.tariq_sekhri.time_tracker

import android.content.Context

class CategoryManager(context: Context) {
    private val dbHelper = DatabaseHelper(context)

    data class CategoryRule(val name: String, val pattern: String, val priority: Int)

    fun resolveCategory(packageName: String, appLabel: String?): String {
        val rules = getRules()
        val appName = appLabel ?: packageName
        
        val matches = rules.filter { rule ->
            runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(appName) }.getOrDefault(false)
        }

        if (matches.isEmpty()) return "Miscellaneous"

        return matches.sortedWith(compareByDescending<CategoryRule> { 
            calculateSpecificity(it.pattern) 
        }.thenByDescending { it.priority })
        .first().name
    }

    private fun getRules(): List<CategoryRule> {
        val rules = mutableListOf<CategoryRule>()
        val db = dbHelper.readableDatabase
        val query = """
            SELECT c.${DatabaseHelper.COLUMN_CAT_NAME}, r.${DatabaseHelper.COLUMN_REG_PATTERN}, c.${DatabaseHelper.COLUMN_CAT_PRIORITY}
            FROM ${DatabaseHelper.TABLE_CATEGORIES} c
            JOIN ${DatabaseHelper.TABLE_REGEX} r ON c.${DatabaseHelper.COLUMN_ID} = r.${DatabaseHelper.COLUMN_REG_CAT_ID}
        """.trimIndent()
        
        val cursor = db.rawQuery(query, null)
        if (cursor.moveToFirst()) {
            do {
                rules.add(CategoryRule(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getInt(2)
                ))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return rules
    }

    private fun calculateSpecificity(pattern: String): Int {
        var score = pattern.length
        if (pattern.startsWith("^") && pattern.endsWith("$")) score += 10000
        else if (pattern.startsWith("^") || pattern.endsWith("$")) score += 1000
        return score
    }
}
