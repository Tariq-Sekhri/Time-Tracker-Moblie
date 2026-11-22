package ca.sekhrit.timetracker

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.util.zip.ZipInputStream

/** One-time import, launched through ADB before the first normal app launch. */
class PackageMigrationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getExternalFilesDir(null)
        AlertDialog.Builder(this)
            .setTitle("Import Time Tracker data")
            .setMessage("Restore your previous logs, sync identity, notes, timers, and settings.")
            .setPositiveButton("Import") { _, _ -> importInBackground() }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun importInBackground() {
        Thread {
            val result = runCatching { restoreArchive() }
            runOnUiThread {
                val message = result.fold({ "Import complete." }, { "Import failed: ${it.message}" })
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private fun restoreArchive() {
        requireTrackingStopped()
        val marker = File(noBackupFilesDir, "package-migration-complete")
        check(!marker.exists() && !getDatabasePath("time_tracker.db").exists()) {
            "The destination already contains tracking data."
        }
        val folder = checkNotNull(getExternalFilesDir(null)) { "Local storage is unavailable." }
        val archive = File(folder, "time-tracker-migration.zip")
        check(archive.isFile) { "The migration archive is missing." }
        val staging = File.createTempFile("migration-", ".staging", cacheDir)
        check(staging.delete() && staging.mkdir()) { "Could not prepare a staging directory." }
        try {
            val contents = File(staging, "contents")
            unpackArchive(archive, contents)
            check(File(contents, "databases/time_tracker.db").isFile &&
                File(contents, "shared_prefs/TimeTrackerPrefs.xml").isFile) {
                "The archive is missing the tracking database or settings."
            }
            requireTrackingStopped()
            installFolders(contents, staging, marker)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun unpackArchive(archive: File, contents: File) {
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val parts = entry.name.split('/')
                check(!entry.isDirectory && parts.size > 1 && parts.first() in persistentFolders &&
                    parts.none { it.isEmpty() || it == "." || it == ".." }) {
                    "The archive contains an unexpected path."
                }
                val target = File(contents, entry.name)
                check(target.createParent() && target.createNewFile()) {
                    "The archive contains a repeated or unwritable path."
                }
                target.outputStream().buffered().use { zip.copyTo(it) }
                zip.closeEntry()
            }
        }
    }

    private fun installFolders(contents: File, staging: File, marker: File) {
        val previous = File(staging, "previous")
        check(previous.mkdir())
        val moved = mutableListOf<String>()
        try {
            for (name in persistentFolders) {
                val source = File(contents, name)
                if (!source.isDirectory) continue
                val target = File(dataDir, name)
                if (target.exists()) {
                    check(target.renameTo(File(previous, name))) { "Could not preserve destination settings." }
                }
                if (!source.renameTo(target)) {
                    val original = File(previous, name)
                    if (original.exists()) check(original.renameTo(target))
                    error("Could not restore ${name}.")
                }
                moved += name
            }
            check(marker.createNewFile()) { "Could not record the completed import." }
        } catch (error: Exception) {
            for (name in moved.asReversed()) {
                val target = File(dataDir, name)
                check(target.renameTo(File(contents, name))) { "Could not roll back the import." }
                val original = File(previous, name)
                if (original.exists()) check(original.renameTo(target))
            }
            throw error
        }
    }

    private fun File.createParent(): Boolean {
        val parent = checkNotNull(parentFile)
        return parent.isDirectory || parent.mkdirs()
    }

    private fun requireTrackingStopped() {
        val manager = getSystemService(ActivityManager::class.java)
        check(manager.runningAppProcesses.orEmpty().none { it.processName == packageName }) {
            "Stop Time Tracker before importing."
        }
    }

    private companion object {
        val persistentFolders = listOf("databases", "shared_prefs", "files", "no_backup", "app_webview")
    }
}
