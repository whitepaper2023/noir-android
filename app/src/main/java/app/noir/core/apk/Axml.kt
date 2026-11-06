package app.noir.core.apk

/**
 * Minimal Android binary XML (AXML) reader.
 *
 * Only what a manifest audit needs: element names, attributes and nesting depth.
 * Deliberately dependency-free — the popular apk-parser library fails on some
 * system APKs with `newPosition > limit`.
 */
object Axml {

    data class Node(val name: String, val attrs: Map<String, String?>, val depth: Int)

    fun parse(bytes: ByteArray): List<Node> {
        if (bytes.size < 8) return emptyList()

        var strings: List<String> = emptyList()
        val nodes = ArrayList<Node>()
        var depth = 0
        var offset = 8

        while (offset + 8 <= bytes.size) {
            val type = u16(bytes, offset)
            val headerSize = u16(bytes, offset + 2)
            val size = u32(bytes, offset + 4)
            if (size <= 0 || offset + size > bytes.size) break

            when (type) {
                CHUNK_STRING_POOL -> strings = stringPool(bytes, offset)
                CHUNK_START_ELEMENT -> {
                    startElement(bytes, offset, headerSize, strings, depth)?.let { nodes.add(it) }
                    depth++
                }
                CHUNK_END_ELEMENT -> if (depth > 0) depth--
            }

            // headerSize is not used directly, but a zero size would loop forever
            if (size < 8) break
            offset += size
        }

        return nodes
    }

    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val CHUNK_END_ELEMENT = 0x0103

    private fun startElement(
        bytes: ByteArray,
        offset: Int,
        headerSize: Int,
        strings: List<String>,
        depth: Int
    ): Node? {
        if (offset + 36 > bytes.size) return null
        val nameIndex = u32(bytes, offset + 20)
        val attrStart = u16(bytes, offset + 24)
        val attrSize = u16(bytes, offset + 26)
        val attrCount = u16(bytes, offset + 28)
        if (attrSize < 20) return null

        val attrs = LinkedHashMap<String, String?>()
        // attributeStart is relative to the attrExt struct, which follows the chunk header.
        var attr = offset + headerSize + attrStart
        repeat(attrCount) {
            if (attr + 20 > bytes.size) return@repeat
            val attrName = u32(bytes, attr + 4)
            val rawValue = u32(bytes, attr + 8)
            val dataType = bytes[attr + 15].toInt() and 0xFF
            val data = u32(bytes, attr + 16)

            val key = strings.getOrNull(attrName).orEmpty()
            if (key.isNotEmpty()) {
                attrs[key] = when (dataType) {
                    TYPE_STRING -> strings.getOrNull(data)
                    TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
                    TYPE_INT_DEC, TYPE_INT_HEX -> data.toString()
                    else -> if (rawValue in strings.indices) strings[rawValue] else null
                }
            }
            attr += attrSize
        }

        return Node(strings.getOrNull(nameIndex).orEmpty(), attrs, depth)
    }

    private fun stringPool(bytes: ByteArray, offset: Int): List<String> {
        if (offset + 28 > bytes.size) return emptyList()
        val count = u32(bytes, offset + 8)
        val flags = u32(bytes, offset + 16)
        val stringsStart = offset + u32(bytes, offset + 20)
        val utf8 = (flags and 0x100) != 0
        if (count <= 0 || count > 1_000_000) return emptyList()

        val result = ArrayList<String>(count)
        for (i in 0 until count) {
            val entry = stringsStart + u32(bytes, offset + 28 + i * 4)
            result.add(if (utf8) readUtf8(bytes, entry) else readUtf16(bytes, entry))
        }
        return result
    }

    private fun readUtf8(bytes: ByteArray, start: Int): String {
        var p = start
        if (p + 2 > bytes.size) return ""
        var skip = bytes[p].toInt() and 0xFF
        p++
        if (skip and 0x80 != 0) {
            if (p >= bytes.size) return ""
            skip = ((skip and 0x7F) shl 8) or (bytes[p].toInt() and 0xFF)
            p++
        }
        var length = bytes[p].toInt() and 0xFF
        p++
        if (length and 0x80 != 0) {
            if (p >= bytes.size) return ""
            length = ((length and 0x7F) shl 8) or (bytes[p].toInt() and 0xFF)
            p++
        }
        if (p + length > bytes.size || length < 0) return ""
        return String(bytes, p, length, Charsets.UTF_8)
    }

    private fun readUtf16(bytes: ByteArray, start: Int): String {
        var p = start
        if (p + 2 > bytes.size) return ""
        var length = u16(bytes, p)
        p += 2
        if (length and 0x8000 != 0) {
            if (p + 2 > bytes.size) return ""
            length = ((length and 0x7FFF) shl 16) or u16(bytes, p)
            p += 2
        }
        val byteLength = length * 2
        if (byteLength < 0 || p + byteLength > bytes.size) return ""
        return String(bytes, p, byteLength, Charsets.UTF_16LE)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12
}
