package org.quietreader.reader

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import com.google.gson.JsonObject
import org.quietreader.core.Anchor
import org.quietreader.core.Content
import org.quietreader.core.ReadingSettings
import kotlin.math.max

/** Render-only objects. Original strings and IDs remain untouched in storage. */
data class ReaderBlock(
    val sectionId: String,
    val contentId: String,
    val text: String,
    val role: String = "body",
    val spaceBefore: Float = 0f,
    val selectable: Boolean = true,
    val original: String = text,
    val popup: String = "",
    val popupTitle: String = "",
) {
    val isOriginal get() = role == "body" || role == "poem" || role == "heading"
}

data class ReaderSlice(val block: ReaderBlock, val start: Int, val end: Int, val height: Int, val before: Int, val after: Int) {
    fun anchor(bookId: String, at: Int = start): Anchor {
        fun boundary(value:Int):Int {
            val position=value.coerceIn(0,block.original.length)
            return if(position>0 && position<block.original.length && Character.isLowSurrogate(block.original[position]) && Character.isHighSurrogate(block.original[position-1]))position-1 else position
        }
        val offset = boundary(at)
        val end = boundary((offset + 32).coerceAtMost(block.original.length))
        return Anchor(bookId, block.sectionId, block.contentId, offset, end,
            block.original.substring(offset, end),
            block.original.substring(boundary((offset - 24).coerceAtLeast(0)), offset),
            block.original.substring(end, boundary((end + 24).coerceAtMost(block.original.length))))
    }
}

data class ReaderPage(val slices: List<ReaderSlice>)

object ReaderDocument {
    private fun JsonObject.str(key: String) = get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    fun blocks(section: JsonObject, poetry: Boolean, settings: ReadingSettings, expanded: Set<String>): List<ReaderBlock> {
        val sid = section.str("id")
        val originals=Content.texts(section,if(poetry)"poetry"else"novel")
        val first=originals.entries.firstOrNull()
        val last=originals.entries.lastOrNull()
        fun auxiliary(text:String,role:String,before:Float=0f,atEnd:Boolean=false):ReaderBlock {
            val target=if(atEnd)last else first
            return ReaderBlock(sid,target?.key.orEmpty(),text,role,before,false,target?.value.orEmpty())
        }
        return buildList {
            fun addAuxiliary(value:String,kind:String,label:String,id:String,original:String,expanded:Boolean=false,atEnd:Boolean=false){
                if(value.isBlank())return
                var mode=settings.auxiliaryMode(kind)
                val legacy=when(kind){"pinyin"->settings.pinyinDisplay;"translation"->settings.translationDisplay;"note"->settings.notesDisplay;else->"explicit"}
                if(expanded&&legacy=="legacy")mode="small"
                if(mode=="hidden")return
                if(mode=="link")add(ReaderBlock(sid,id,"〔$label〕","link",if(atEnd)12f else 0f,false,original,value,label))
                else add(ReaderBlock(sid,id,"$label　$value",if(mode=="inline")"aux-inline"else"aux",if(atEnd)12f else 0f,false,original))
            }
            add(auxiliary(section.str("title"),"title"))
            if (poetry) {
                val tags=section.getAsJsonArray("tags")?.joinToString(" · "){it.asString}.orEmpty()
                val meta = listOf(section.str("dynasty"), section.str("author"), section.str("genre"),tags).filter { it.isNotEmpty() }.joinToString(" · ")
                if (meta.isNotEmpty()) add(auxiliary(meta,"meta"))
                section.getAsJsonArray("stanzas")?.forEachIndexed { stanzaIndex, stanza ->
                    stanza.asJsonObject.getAsJsonArray("lines")?.forEachIndexed { lineIndex, element ->
                        val line = element.asJsonObject
                        val id = line.str("id")
                        val original = line.str("original")
                        if (settings.showOriginal) add(ReaderBlock(sid, id, original, "poem", if (lineIndex == 0 && stanzaIndex > 0) 20f else 0f))
                        val open = id in expanded
                        listOf("pinyin" to "拼音","translation" to "译文","note" to "注释").forEach{(key,label)->addAuxiliary(line.str(key),key,label,id,original,open)}
                    }
                }
                listOf(Triple("background","background","创作背景"),Triple("translation","poemTranslation","全诗译文"),Triple("appreciation","appreciation","赏析")).forEach{(key,kind,label)->addAuxiliary(section.str(key),kind,label,last?.key.orEmpty(),last?.value.orEmpty(),atEnd=true)}
                if(none{it.isOriginal || it.role in listOf("aux","analysis","aux-inline","link")}) {
                    add(auxiliary("当前显示设置下没有正文，请在更多中切换纯原文。\n点击这里恢复原文。","empty",18f))
                }
            } else {
                var precedingId=first?.key.orEmpty()
                var precedingText=first?.value.orEmpty()
                section.getAsJsonArray("blocks")?.forEach { element ->
                    val b = element.asJsonObject
                    val type = b.str("type")
                    add(if (type == "separator") ReaderBlock(sid, precedingId, "────────", "separator", selectable = false,original=precedingText)
                    else ReaderBlock(sid, b.str("id"), b.str("text"), if (type == "heading") "heading" else "body"))
                    if(type!="separator"){precedingId=b.str("id");precedingText=b.str("text")}
                }
            }
        }
    }

    fun poemText(section: JsonObject): String = buildString {
        append(section.str("title"))
        val author = section.str("author")
        if (author.isNotEmpty()) append("\n").append(author)
        section.getAsJsonArray("stanzas")?.forEach { stanza ->
            append("\n\n")
            append(stanza.asJsonObject.getAsJsonArray("lines").joinToString("\n") { it.asJsonObject.str("original") })
        }
    }
}

/** Measures actual shaped text with Android's layout engine, never a character-count page. */
object ReaderPaginator {
    fun size(settings: ReadingSettings, role: String): Float = settings.fontSize * when(role) {
        "title" -> 1.25f
        "heading" -> 1.1f
        "meta", "aux", "link" -> .78f
        "analysis" -> .9f
        else -> 1f
    }

    fun face(settings: ReadingSettings, role: String): Typeface = Typeface.create(settings.fontFamily,
        if (role == "title" || role == "heading") Typeface.BOLD else Typeface.NORMAL)

    fun styledText(block: ReaderBlock, start: Int, end: Int, settings: ReadingSettings, scaledDensity: Float,bodyTextSizePx:Float=settings.fontSize*scaledDensity): CharSequence {
        val text = SpannableString(block.text.substring(start, end))
        if (block.role == "body" && settings.indent && start == 0 && text.isNotEmpty()) {
            text.setSpan(LeadingMarginSpan.Standard((bodyTextSizePx * 2).toInt(), 0), 0, text.length, 0)
        }
        return text
    }

    fun layout(text: CharSequence, paint: TextPaint, width: Int, settings: ReadingSettings): StaticLayout {
        val builder=StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setLineSpacing(0f, settings.lineSpacing).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setTextDirection(android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if(android.os.Build.VERSION.SDK_INT>=28)builder.setUseLineSpacingFromFallbacks(true)
        return builder.build()
    }

    fun paginate(blocks: List<ReaderBlock>, settings: ReadingSettings, width: Int, height: Int, density: Float, scaledDensity: Float,fontPixels:(Float)->Float={it*scaledDensity}): List<ReaderPage> {
        val pages = mutableListOf<ReaderPage>()
        var slices = mutableListOf<ReaderSlice>()
        var used = 0
        val usableHeight = height.coerceAtLeast(1)
        fun finish() { if (slices.isNotEmpty()) { pages.add(ReaderPage(slices)); slices = mutableListOf(); used = 0 } }
        blocks.forEach { block ->
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = fontPixels(size(settings, block.role)); typeface = face(settings, block.role) }
            var offset = 0
            val after = (settings.paragraphSpacing * density * if (block.role == "aux") .35f else 1f).toInt()
            while (offset < block.text.length) {
                // Bounded layout chunks avoid allocating a layout for a huge single paragraph.
                var chunkEnd = (offset + 16384).coerceAtMost(block.text.length)
                if (chunkEnd < block.text.length && Character.isHighSurrogate(block.text[chunkEnd - 1])) chunkEnd--
                val text = styledText(block, offset, chunkEnd, settings, scaledDensity,fontPixels(settings.fontSize))
                val measured = layout(text, paint, width, settings)
                val before = if (offset == 0) (block.spaceBefore * density).toInt() else 0
                if (slices.isNotEmpty() && usableHeight - used - before < measured.getLineBottom(0)) finish()
                val available = max(measured.getLineBottom(0), usableHeight - used - before)
                var lastLine = 0
                while (lastLine + 1 < measured.lineCount && measured.getLineBottom(lastLine + 1) <= available) lastLine++
                // If this is a bounded chunk, leave its incomplete final line for the next layout.
                if (chunkEnd < block.text.length && lastLine == measured.lineCount - 1 && lastLine > 0) lastLine--
                val consumed = measured.getLineEnd(lastLine).coerceAtLeast(1)
                val end = (offset + consumed).coerceAtMost(block.text.length)
                val sliceHeight = measured.getLineBottom(lastLine)
                val spacing = if (end == block.text.length) after.coerceAtMost((usableHeight-used-before-sliceHeight).coerceAtLeast(0)) else 0
                slices.add(ReaderSlice(block, offset, end, sliceHeight, before, spacing))
                used += before + sliceHeight + spacing
                offset = end
                if (offset < block.text.length) finish()
            }
        }
        finish()
        return pages.ifEmpty { listOf(ReaderPage(emptyList())) }
    }
}
