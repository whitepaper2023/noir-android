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
                zip.getInputStream(entry).use { input ->
                    // 1.0.4：流式读 dex，不再整文件 readBytes() 进内存。大 dex（微信这类
                    // 几十 MB）整文件读入是闪退的头号嫌疑：读一份几十 MB 的字节数组，
                    // 后面 deepScan 还要再复制一份同样大小的 String，峰值上百 MB，
                    // 堆一紧张就 OutOfMemoryError；Error 不进 catch (Exception)，
                    // 直接崩掉整轮扫描（旧版本也崩过，Lu 2026-11-07 反馈）。
                    // 每块 1MB，块之间留 320 字节重叠（URL 最长 300 字符 + 前缀），
                    // 跨块的 URL 也能扫到，内存峰值 1MB 出头。
                    // 一次多读一块，才能知道当前块是不是最后一块：不是最后一块时，
                    // 顶到块尾还没结束的 URL 可能是被截断的，跳过它，下一块会从重叠区
                    // 里把它完整扫出来；不然 endpoints 里会多一条截断的假 URL。
                    val buf = ByteArray(1024 * 1024)
                    fun readChunk(): ByteArray? {
                        val n = input.read(buf)
                        return if (n <= 0) null else buf.copyOf(n)
                    }
                    var tail = ByteArray(0)
                    var chunk = readChunk()
                    while (out.size < limit) {
                        val current = chunk ?: break
                        val next = readChunk()
                        val window = tail + current
                        scan(window, out, limit, isLast = (next == null))
                        tail = if (window.size > 320) {
                            window.copyOfRange(window.size - 320, window.size)
                        } else {
                            window
                        }
                        chunk = next
                    }
                }
            }
        }
        return out.toList()
    }

    private fun scan(bytes: ByteArray, out: MutableSet<String>, limit: Int, isLast: Boolean = true) {
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
                // 顶到块尾还没结束、又不是最后一块：URL 可能被截断了，跳过，
                // 下一块的重叠区会把它完整扫出来。
                if (j - i > 8 && (isLast || j < n)) {
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
