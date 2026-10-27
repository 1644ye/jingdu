package org.quietreader.core

import com.google.gson.JsonObject

data class Anchor(
    val bookId: String,
    val sectionId: String,
    val contentId: String,
    val start: Int = 0,
    val end: Int = 0,
    val quote: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val orphaned: Boolean = false,
)

/** Offsets are UTF-16 offsets, matching Android's text layout and selection APIs. */
object Anchors {
    fun relocate(anchor: Anchor, oldText: String?, newText: String?): Anchor {
        if (anchor.orphaned || oldText == null || newText == null) return anchor.copy(orphaned = true)
        if (anchor.start < 0 || anchor.end < anchor.start || anchor.end > oldText.length) {
            return anchor.copy(orphaned = true)
        }
        if (oldText == newText) {
            if (anchor.quote.isNotEmpty() && oldText.substring(anchor.start, anchor.end) != anchor.quote) {
                return anchor.copy(orphaned = true)
            }
            return anchor
        }
        val quote = anchor.quote.ifEmpty { oldText.substring(anchor.start, anchor.end) }
        if (quote.isEmpty()) return anchor.copy(orphaned = true)
        // Derive context for older records without it; never look in a different content ID.
        fun boundary(offset: Int): Int = if (offset in 1 until oldText.length && oldText[offset].isLowSurrogate() && oldText[offset - 1].isHighSurrogate()) offset - 1 else offset
        val prefix = anchor.prefix.ifEmpty { oldText.substring(boundary((anchor.start - 24).coerceAtLeast(0)), anchor.start) }
        val suffix = anchor.suffix.ifEmpty { oldText.substring(anchor.end, boundary((anchor.end + 24).coerceAtMost(oldText.length))) }
        if (oldText.substring(anchor.start, anchor.end) != quote) return anchor.copy(orphaned = true)
        var found = -1
        var cursor = 0
        while (cursor <= newText.length - quote.length) {
            val at = newText.indexOf(quote, cursor)
            if (at < 0) break
            val end = at + quote.length
            val left = prefix.isEmpty() || (at >= prefix.length && newText.regionMatches(at - prefix.length, prefix, 0, prefix.length))
            val right = suffix.isEmpty() || (end + suffix.length <= newText.length && newText.regionMatches(end, suffix, 0, suffix.length))
            if (left && right) {
                if (found >= 0) return anchor.copy(orphaned = true)
                found = at
            }
            cursor = at + 1
        }
        return if (found < 0) anchor.copy(orphaned = true)
        else anchor.copy(start = found, end = found + quote.length, quote = quote, prefix = prefix, suffix = suffix, orphaned = false)
    }
}

data class ReadingSettings(
    val mode: String = "scroll",
    val fontSize: Float = 22f,
    val lineSpacing: Float = 1.5f,
    val paragraphSpacing: Float = 16f,
    val margin: Float = 24f,
    val fontFamily: String = "serif",
    val theme: String = "system",
    val customBackground: String = "#F8F6F0",
    val customSurface: String = "#FFFDF7",
    val customAccent: String = "#416752",
    val keepAwake: Boolean = false,
    val indent: Boolean = true,
    val showOriginal: Boolean = true,
    val showPinyin: Boolean = false,
    val showTranslation: Boolean = false,
    val showNotes: Boolean = false,
    val showAnalysis: Boolean = false,
)

object Content {
    fun sections(book: JsonObject): List<JsonObject> =
        book.getAsJsonArray(if (book.get("type")?.asString == "poetry") "poems" else "chapters")
            ?.map { it.asJsonObject } ?: emptyList()

    fun texts(section: JsonObject, type: String): Map<String, String> = buildMap {
        if (type == "poetry") {
            section.getAsJsonArray("stanzas")?.forEach { stanza ->
                stanza.asJsonObject.getAsJsonArray("lines")?.forEach { line ->
                    val obj = line.asJsonObject
                    put(obj.get("id").asString, obj.get("original").asString)
                }
            }
        } else {
            section.getAsJsonArray("blocks")?.forEach { block ->
                val obj = block.asJsonObject
                if (obj.get("type")?.asString != "separator") put(obj.get("id").asString, obj.get("text").asString)
            }
        }
    }
}
