package org.quietreader.reader

import android.test.AndroidTestCase
import com.google.gson.JsonParser
import org.quietreader.core.ReadingSettings

/** Run on Android: these exercise the platform's real font shaping and StaticLayout. */
@Suppress("DEPRECATION")
class ReaderPaginationTest : AndroidTestCase() {
    fun testIndependentAuxiliaryModesKeepOriginalAnchorsAndPopupBody(){
        val poem=JsonParser.parseString("""{"id":"p","title":"题","background":"夜晚思乡的背景","translation":"全诗译文","appreciation":"借景抒情","stanzas":[{"id":"s","lines":[{"id":"l","original":"明月。","pinyin":"míng yuè","translation":"明亮的月光","note":"月：月亮"}]}]}""").asJsonObject
        val settings=ReadingSettings(pinyinDisplay="small",translationDisplay="hidden",notesDisplay="link",backgroundDisplay="inline",poemTranslationDisplay="link",appreciationDisplay="hidden")
        val blocks=ReaderDocument.blocks(poem,true,settings,setOf("l"))
        assertEquals(listOf("明月。"),blocks.filter{it.isOriginal}.map{it.text})
        assertEquals(1,blocks.count{it.role=="aux"});assertEquals(1,blocks.count{it.role=="aux-inline"})
        assertFalse(blocks.any{it.text.contains("明亮的月光")||it.text.contains("借景抒情")})
        val links=blocks.filter{it.role=="link"};assertEquals(2,links.size)
        assertEquals("月：月亮",links.first().popup)
        links.forEach{assertEquals("l",it.contentId);assertEquals("明月。",it.original);assertFalse(it.selectable)}
        assertTrue(ReaderDocument.blocks(poem,true,settings.originalOnly(),setOf("l")).none{it.role in listOf("aux","aux-inline","link")})
        assertEquals("small",ReadingSettings(showNotes=true).auxiliaryMode("note"))
    }
    private fun pages(blocks: List<ReaderBlock>, settings: ReadingSettings = ReadingSettings(mode="paged"), width: Int=320, height: Int=420) =
        ReaderPaginator.paginate(blocks,settings,width,height,1f,1f)

    fun testPaginationHasNoOmissionOrDuplicationAndPreservesWhitespace() {
        val original = ("  山城的雨停了，书店灯下，有人写了一封信。\n第二行保留空格  与标点！\n".repeat(160))
        val block=ReaderBlock("chapter", "paragraph",original)
        val result=pages(listOf(block))
        assertTrue(result.size>1)
        assertEquals(original,result.flatMap{it.slices}.joinToString(""){it.block.text.substring(it.start,it.end)})
        var offset=0
        result.flatMap{it.slices}.forEach{assertEquals(offset,it.start);assertTrue(it.end>it.start);offset=it.end}
        assertEquals(original.length,offset)
    }

    fun testActualLayoutChangesWithFontAndViewport() {
        val blocks=listOf(ReaderBlock("c","b","山光悦鸟性，潭影空人心。".repeat(400)))
        val normal=pages(blocks)
        val large=pages(blocks,ReadingSettings(mode="paged",fontSize=36f))
        val narrow=pages(blocks,width=200)
        assertTrue(large.size>normal.size)
        assertTrue(narrow.size>normal.size)
        val target=2500
        listOf(normal,large,narrow).forEach{p->assertEquals(1,p.flatMap{it.slices}.count{target>=it.start&&target<it.end})}
    }

    fun testLargeSingleParagraphAndSurrogatePairs() {
        val original="月🌙明，山川悠悠。".repeat(6000)
        val result=pages(listOf(ReaderBlock("c","b",original)))
        val slices=result.flatMap{it.slices}
        assertEquals(original,slices.joinToString(""){original.substring(it.start,it.end)})
        slices.forEach{
            if(it.start>0)assertFalse(Character.isLowSurrogate(original[it.start]))
            if(it.end<original.length)assertFalse(Character.isLowSurrogate(original[it.end]))
        }
    }

    fun testPoemLineAndStanzaIdentitySurvivesAuxiliaryReflow() {
        val json=JsonParser.parseString("""{"id":"p","title":"静夜思","stanzas":[{"id":"s1","lines":[{"id":"l1","original":"床前明月光，","pinyin":"chuáng qián míng yuè guāng","translation":"明亮的月光洒在床前。"}]},{"id":"s2","lines":[{"id":"l2","original":"低头思故乡。","note":"思念故乡。"}]}]}""").asJsonObject
        val plain=ReaderDocument.blocks(json,true,ReadingSettings(),emptySet())
        val auxiliary=ReaderDocument.blocks(json,true,ReadingSettings(showPinyin=true,showTranslation=true,showNotes=true),emptySet())
        assertEquals(listOf("l1","l2"),plain.filter{it.isOriginal}.map{it.contentId})
        assertEquals(plain.filter{it.isOriginal}.map{it.text},auxiliary.filter{it.isOriginal}.map{it.text})
        assertTrue(plain.first{it.contentId=="l2"}.spaceBefore>0f)
        assertEquals(3,auxiliary.count{it.role=="aux"})
        assertTrue(ReaderDocument.poemText(json).contains("床前明月光，\n\n低头思故乡。"))
        val anchor=pages(auxiliary).flatMap{it.slices}.first{it.block.contentId=="l2"&&it.block.isOriginal}.anchor("book")
        assertEquals("l2",anchor.contentId)
        assertEquals("低头思故乡。",anchor.quote)
        assertEquals(anchor.quote,anchor.blockText(json).substring(anchor.start,anchor.end))
    }

    private fun org.quietreader.core.Anchor.blockText(section:com.google.gson.JsonObject):String =
        org.quietreader.core.Content.texts(section,"poetry").getValue(contentId)

    fun testOriginalHiddenDoesNotLoseOriginalAnchor() {
        val json=JsonParser.parseString("""{"id":"p","title":"题","stanzas":[{"id":"s","lines":[{"id":"l","original":"明月。","translation":"这里是一段比原文长很多的说明。"}]}]}""").asJsonObject
        val blocks=ReaderDocument.blocks(json,true,ReadingSettings(showOriginal=false,showTranslation=true),emptySet())
        val aux=blocks.first{it.role=="aux"}
        val anchor=ReaderSlice(aux,0,aux.text.length,0,0,0).anchor("book",0)
        assertEquals("明月。",anchor.quote)
        assertEquals(3,anchor.end)
    }

    fun testSavedAnchorDoesNotSplitSurrogatePair() {
        val original="a".repeat(31)+"🌙"+"b".repeat(80)
        val slice=ReaderSlice(ReaderBlock("c","b",original),0,original.length,0,0,0)
        val start=slice.anchor("book",0)
        assertEquals(31,start.end)
        val middle=slice.anchor("book",32)
        assertEquals(31,middle.start)
        assertTrue(middle.quote.startsWith("🌙"))
        assertEquals(middle.quote,original.substring(middle.start,middle.end))
    }

    fun testMeasuredSliceFitsTheActualTextViewIncludingFallbackFonts() {
        val settings=ReadingSettings(mode="paged",fontSize=30f,lineSpacing=1.5f,fontFamily="serif")
        val block=ReaderBlock("c","b","山光悦鸟性，潭影空人心。月🌙与远山༄༅།བོད།".repeat(80))
        val slices=pages(listOf(block),settings,width=320,height=420).flatMap{it.slices}
        for(slice in slices){
            val view=android.widget.TextView(context).apply {
                text=ReaderPaginator.styledText(block,slice.start,slice.end,settings,1f)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,settings.fontSize)
                typeface=ReaderPaginator.face(settings,"body")
                includeFontPadding=false;setPadding(0,0,0,0);setLineSpacing(0f,settings.lineSpacing)
                breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency=android.text.Layout.HYPHENATION_FREQUENCY_NONE
                textDirection=android.view.View.TEXT_DIRECTION_FIRST_STRONG_LTR
                if(android.os.Build.VERSION.SDK_INT>=28)setFallbackLineSpacing(true)
            }
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(320,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED))
            assertTrue("TextView 实际高度 ${view.measuredHeight} 不应超过分配高度 ${slice.height}",view.measuredHeight<=slice.height+1)
        }
    }
}
