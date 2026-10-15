package org.frostbox.app.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant

class DiagnosticLog(context: Context) {
    private val file = File(context.filesDir, "diagnostics.log")
    private val mutable = MutableStateFlow<List<String>>(emptyList())
    val lines = mutable.asStateFlow()
    @Synchronized fun record(message: String) {
        val entry = "${Instant.now()} ${message.replace('\n', ' ').take(1200)}"
        Log.i("FrostBox", entry)
        mutable.value = (mutable.value + entry).takeLast(100)
        // Called on IO by the model; disk failure must not hide the original diagnostic.
        runCatching {
            if (file.length() > 256 * 1024) file.writeText(mutable.value.joinToString("\n") + "\n")
            else file.appendText(entry + "\n")
        }.onFailure { Log.e("FrostBox", "Cannot persist diagnostic", it) }
    }
}
