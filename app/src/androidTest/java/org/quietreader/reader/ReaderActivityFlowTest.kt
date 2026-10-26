package org.quietreader.reader

import android.content.Intent
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.quietreader.ReaderApp
import org.quietreader.core.ReadingSettings

/** Device integration: imported persistent content -> exact target -> real page turn -> saved anchor. */
@Suppress("DEPRECATION")
class ReaderActivityFlowTest : ActivityInstrumentationTestCase2<ReaderActivity>(ReaderActivity::class.java) {
    private val bookId="reader-instrumentation-novel"
    private val targetOffset=7000
    private val marker="【唯一定位目标】"
    private val base="夜色里的山城依然安静，旧书店的门慢慢打开。雨后的石阶映着窗边一盏灯。".repeat(400)
    private val paragraph=base.take(targetOffset)+marker+base.drop(targetOffset)
    private val store get()=(instrumentation.targetContext.applicationContext as ReaderApp).store

    override fun setUp(){
        super.setUp()
        store.deleteBooks(listOf(bookId))
        val blocks=JsonArray().apply{add(JsonObject().apply{addProperty("id","b");addProperty("type","paragraph");addProperty("text",paragraph)})}
        val chapters=JsonArray().apply{add(JsonObject().apply{addProperty("id","c");addProperty("title","测试章节");add("blocks",blocks)})}
        val books=JsonArray().apply{add(JsonObject().apply{addProperty("id",bookId);addProperty("type","novel");addProperty("title","阅读流程测试");add("chapters",chapters)})}
        store.importFile(JsonObject().apply{addProperty("schemaVersion",1);addProperty("kind","ebook-library");add("books",books)})
        store.saveSettings(ReadingSettings(mode="paged",fontSize=22f),bookId)
        setActivityIntent(Intent(Intent.ACTION_MAIN).putExtra("bookId",bookId).putExtra("sectionId","c").putExtra("contentId","b").putExtra("offset",targetOffset).putExtra("end",targetOffset+marker.length))
    }

    fun testOpenAtTextOffsetTurnPageAndPersist(){
        val screen=activity
        await("正文应真实显示"){descendants(screen.window.decorView).filterIsInstance<TextView>().any{it.isTextSelectable && it.text.isNotEmpty()}}
        await("阅读位置应自动保存"){store.position(bookId)?.contentId=="b"}
        val first=store.position(bookId)!!
        assertTrue("首屏位置必须在目标之前",first.start in 0..targetOffset)
        val shown=descendants(screen.window.decorView).filterIsInstance<TextView>().filter{it.isTextSelectable}.joinToString(""){it.text}
        assertTrue("实际页面必须包含唯一目标，不能只回到章首",shown.contains("【唯一") || shown.contains("定位目标】"))
        assertEquals(first.quote,paragraph.substring(first.start,first.end))
        instrumentation.runOnMainSync {
            val next=descendants(screen.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="下一页"}
            assertTrue(next.performClick())
        }
        await("翻页应保存更靠后的原文位置"){(store.position(bookId)?.start ?: 0)>first.start}
        val later=store.position(bookId)!!
        assertEquals("c",later.sectionId)
        assertEquals("b",later.contentId)
        assertEquals(later.quote,paragraph.substring(later.start,later.end))
        instrumentation.runOnMainSync{screen.finish()}
        instrumentation.waitForIdleSync()
        assertNotNull(store.position(bookId))
    }

    private fun descendants(view:View):List<View> = buildList {
        add(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)addAll(descendants(view.getChildAt(i)))
    }
    private fun await(message:String,condition:()->Boolean){
        val deadline=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<deadline){instrumentation.waitForIdleSync();if(condition())return;Thread.sleep(100)}
        fail(message)
    }
    override fun tearDown(){
        try{super.tearDown()}finally{ReaderPositions.awaitPendingWrites();store.deleteBooks(listOf(bookId))}
    }
}
