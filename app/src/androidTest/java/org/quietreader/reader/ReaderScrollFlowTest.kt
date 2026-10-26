package org.quietreader.reader

import android.content.Intent
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.quietreader.ReaderApp
import org.quietreader.core.ReadingSettings

/** Verifies that a huge single chapter is virtualized as well as a multi-chapter book. */
@Suppress("DEPRECATION")
class ReaderScrollFlowTest:ActivityInstrumentationTestCase2<ReaderActivity>(ReaderActivity::class.java){
    private val bookId="reader-instrumentation-scroll"
    private val text="雨后的山城很安静，月光落在书页上。".repeat(10000)
    private val store get()=(instrumentation.targetContext.applicationContext as ReaderApp).store
    override fun setUp(){
        super.setUp();store.deleteBooks(listOf(bookId))
        val block=JsonObject().apply{addProperty("id","b");addProperty("type","paragraph");addProperty("text",text)}
        val section=JsonObject().apply{addProperty("id","c");addProperty("title","超长章节");add("blocks",JsonArray().apply{add(block)})}
        val book=JsonObject().apply{addProperty("id",bookId);addProperty("type","novel");addProperty("title","滚动测试");add("chapters",JsonArray().apply{add(section)})}
        store.importFile(JsonObject().apply{addProperty("schemaVersion",1);addProperty("kind","ebook-library");add("books",JsonArray().apply{add(book)})})
        store.saveSettings(ReadingSettings(mode="scroll"),bookId)
        setActivityIntent(Intent(Intent.ACTION_MAIN).putExtra("bookId",bookId).putExtra("sectionId","c").putExtra("contentId","b").putExtra("offset",40000))
    }
    fun testLongChapterCreatesBoundedViewsAndAdvancesWithinSameChapter(){
        val screen=activity
        await{store.position(bookId)?.start?.let{it>30000}==true}
        val first=store.position(bookId)!!.start
        var scroll:ScrollView?=null
        instrumentation.runOnMainSync{scroll=all(screen.window.decorView).filterIsInstance<ScrollView>().first()}
        repeat(4){
            instrumentation.runOnMainSync{scroll!!.scrollTo(0,scroll!!.getChildAt(0).height)}
            Thread.sleep(500);instrumentation.waitForIdleSync()
        }
        await{(store.position(bookId)?.start?:0)>first}
        val anchor=store.position(bookId)!!
        assertEquals("c",anchor.sectionId);assertEquals("b",anchor.contentId)
        assertEquals(anchor.quote,text.substring(anchor.start,anchor.end))
        instrumentation.runOnMainSync{
            val window=scroll!!.getChildAt(0) as ViewGroup
            assertTrue("滚动节点窗口最多三个片段",window.childCount<=3)
            val rendered=all(window).filterIsInstance<TextView>().sumOf{it.text.length}
            assertTrue("不能将超长章整段正文交给TextView",rendered<text.length/2)
        }
    }
    private fun all(view:View):List<View> = buildList{add(view);if(view is ViewGroup)for(i in 0 until view.childCount)addAll(all(view.getChildAt(i)))}
    private fun await(condition:()->Boolean){val limit=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<limit){instrumentation.waitForIdleSync();if(condition())return;Thread.sleep(100)};fail("正文载入或位置保存未在20秒内完成")}
    override fun tearDown(){try{super.tearDown()}finally{ReaderPositions.awaitPendingWrites();store.deleteBooks(listOf(bookId))}}
}
