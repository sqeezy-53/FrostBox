package org.frostbox.app.runtime

import java.io.InputStream

// InputStream.readNBytes is API 33; runtime diagnostics must also work on API 29.
internal fun InputStream.readLogPrefix(limit: Int): String {
    require(limit in 1..1_048_576)
    val bytes = ByteArray(limit)
    var used = 0
    while (used < limit) {
        val count = read(bytes, used, limit - used)
        if (count < 0) break
        if (count == 0) {
            val one = read()
            if (one < 0) break
            bytes[used++] = one.toByte()
        } else used += count
    }
    return String(bytes, 0, used, Charsets.UTF_8)
}
