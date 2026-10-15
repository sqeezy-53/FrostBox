package org.frostbox.app.runtime

import android.content.Context
import android.os.Process
import android.system.Os
import org.frostbox.app.data.ManagedFiles
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Cross-process exclusion for runtime work and storage reconciliation.
 * The OS releases the lock on process death; orphan guest processes still block maintenance. */
internal object RuntimeStorageLease {
    fun acquire(context: Context, maintenance: Boolean = false): Closeable {
        val file = File(context.filesDir, "runtime-storage.lock")
        ManagedFiles.noLink(file)
        val stream = RandomAccessFile(file, "rw")
        try {
            val lock = stream.channel.tryLock() ?: error("Runtime/storage is busy; stop the session first")
            try {
                if (maintenance) requireNoGuests()
                ManagedFiles.write(File(context.filesDir, "runtime-storage-owner.json"), JSONObject()
                    .put("pid", Process.myPid()).put("uid", Process.myUid())
                    .put("startTime", File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19])
                    .put("purpose", if (maintenance) "immutable-file-sharing" else "runtime")
                    .put("active", true))
            } catch (e: Throwable) { lock.close(); throw e }
            return Closeable {
                try {
                    ManagedFiles.write(File(context.filesDir, "runtime-storage-owner.json"), JSONObject()
                        .put("pid", Process.myPid()).put("active", false))
                } finally { lock.close(); stream.close() }
            }
        } catch (e: Throwable) { stream.close(); throw e }
    }

    internal fun requireNoGuests() {
        val entries = File("/proc").listFiles() ?: error("Cannot inspect owned processes; maintenance refused")
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == Process.myPid()) continue
            val uid = runCatching { Os.stat(entry.path).st_uid }.getOrNull() ?: continue
            if (uid != Process.myUid()) continue
            val executable = try { Os.readlink("${entry.path}/exe") }
            catch (e: Exception) {
                // A reaped process is harmless; a live but unreadable owned process is ambiguous.
                check(!entry.exists()) { "Cannot identify owned process $pid; maintenance refused" }
                continue
            }
            check(!executable.contains("libbox64") && !executable.contains("wineserver") &&
                !executable.contains("libfrostbox_probe")) { "Owned guest process $pid is alive; recover/stop runtime first" }
        }
    }
}
