/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.apk

import java.io.File
import java.util.zip.ZipFile

/** Static string extraction from an APK, without unpacking it to disk. */
object ApkScan {

    private val URL = Regex("https?://[A-Za-z0-9._~:/?#@!&()*+,;=%-]{4,300}")

    fun urls(apk: File, limit: Int = 300): List<String> {
        val out = LinkedHashSet<String>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements() && out.size < limit) {
                val entry = entries.nextElement()
                if (!entry.name.startsWith("classes") || !entry.name.endsWith(".dex")) continue
                if (entry.size <= 0 || entry.size > 64L * 1024 * 1024) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                scan(bytes, out, limit)
            }
        }
        return out.toList()
    }

    private fun scan(bytes: ByteArray, out: MutableSet<String>, limit: Int) {
        var i = 0
        val n = bytes.size
        while (i < n - 8 && out.size < limit) {
            if (bytes[i] == 'h'.code.toByte()) {
                var j = i
                while (j < n && j - i < 320) {
                    val c = bytes[j].toInt() and 0xFF
                    if (c <= 32 || c >= 127) break
                    j++
                }
                if (j - i > 8) {
                    val s = String(bytes, i, j - i, Charsets.US_ASCII)
                    if (s.startsWith("http://") || s.startsWith("https://")) {
                        URL.find(s)?.value?.let { out.add(it) }
                    }
                }
                i = if (j > i) j else i + 1
            } else {
                i++
            }
        }
    }
}
