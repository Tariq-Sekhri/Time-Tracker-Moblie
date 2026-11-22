package ca.tariq_sekhri.time_tracker

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One-time export, launched through ADB after stopping the tracking process. */
class PackageMigrationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle("Export Time Tracker data")
            .setMessage("Copy logs, sync identity, notes, timers, and settings to a local migration archive.")
            .setPositiveButton("Export") { _, _ -> exportInBackground() }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun exportInBackground() {
        Thread {
            val result = runCatching {
                requireTrackingStopped()
                check(getDatabasePath("time_tracker.db").isFile) { "The tracking database is missing." }
                val folder = checkNotNull(getExternalFilesDir(null)) { "Local storage is unavailable." }
                val archive = File(folder, "time-tracker-migration.zip")
                val partial = File(folder, "${archive.name}.partial")
                try {
                    ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                        for (name in persistentFolders) {
                            val root = File(dataDir, name)
                            root.walkTopDown().filter { it.isFile }.forEach { file ->
                                val relative = file.relativeTo(dataDir).invariantSeparatorsPath
                                if (!relative.substringAfterLast('/').startsWith("androidx.work.")) {
                                    zip.putNextEntry(ZipEntry(relative))
                                    file.inputStream().buffered().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        }
                    }
                    requireTrackingStopped()
                    check(partial.renameTo(archive)) { "Could not save the archive." }
                } finally {
                    partial.delete()
                }
            }
            runOnUiThread {
                val message = result.fold({ "Export complete." }, { "Export failed: ${it.message}" })
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private fun requireTrackingStopped() {
        val manager = getSystemService(ActivityManager::class.java)
        check(manager.runningAppProcesses.orEmpty().none { it.processName == packageName }) {
            "Stop Time Tracker before exporting."
        }
    }

    private companion object {
        val persistentFolders = listOf("databases", "shared_prefs", "files", "no_backup", "app_webview")
    }
}
