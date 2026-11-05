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
    fun testRecyclingWindowDuringFlingDoesNotRunAway(){
        val screen=activity
        await{store.position(bookId)?.start?.let{it>30000}==true}
        val busy=ReaderActivity::class.java.getDeclaredField("busy").apply{isAccessible=true}
        var scroll:ScrollView?=null
        var first:View?=null
        val extend=ReaderActivity::class.java.getDeclaredMethod("extendWindow",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}
        await{
            var ready=false
            instrumentation.runOnMainSync{
                scroll=all(screen.window.decorView).filterIsInstance<ScrollView>().first()
                val window=scroll!!.getChildAt(0) as ViewGroup
                ready=window.childCount==3&&!busy.getBoolean(screen)
                if(ready)first=window.getChildAt(0)
                else if(!busy.getBoolean(screen))extend.invoke(screen,true,false)
            };ready
        }
        val capture=ReaderActivity::class.java.getDeclaredMethod("captureAnchor").apply{isAccessible=true}
        val finished=java.util.concurrent.CountDownLatch(1)
        var rebased:Int?=null;var end=0;var samples=0;var frames=0
        instrumentation.runOnMainSync{
            scroll!!.postOnAnimation(object:Runnable{
                override fun run(){
                    frames++
                    if(!busy.getBoolean(screen)&&(scroll!!.getChildAt(0) as ViewGroup).getChildAt(0)!==first){
                        end=(capture.invoke(screen) as org.quietreader.core.Anchor).start
                        if(rebased==null)rebased=end
                        samples++
                    }
                    if(samples>=25||frames>=240)finished.countDown() else scroll!!.postOnAnimation(this)
                }
            })
            scroll!!.fling(14000)
        }
        assertTrue("等待逐帧采样完成",finished.await(15,java.util.concurrent.TimeUnit.SECONDS))
        assertNotNull("应实际回收正文窗口",rebased)
        assertTrue("回收窗口后不得继续使用旧惯性坐标：$rebased → $end",kotlin.math.abs(end-rebased!!)<100)
    }
    fun testShortSectionsDoNotChainAdvanceWithoutAnotherGesture(){
        val chapters=JsonArray().apply{repeat(60){i->add(JsonObject().apply{
            addProperty("id","c$i");addProperty("title","短章 $i")
            add("blocks",JsonArray().apply{add(JsonObject().apply{addProperty("id","b$i");addProperty("type","paragraph");addProperty("text","月光落在书页上。")})})
        })}}
        store.deleteBooks(listOf(bookId))
        store.importFile(JsonObject().apply{addProperty("schemaVersion",1);addProperty("kind","ebook-library");add("books",JsonArray().apply{add(JsonObject().apply{addProperty("id",bookId);addProperty("type","novel");addProperty("title","短章");add("chapters",chapters)})})})
        setActivityIntent(Intent(Intent.ACTION_MAIN).putExtra("bookId",bookId))
        val screen=activity
        val busy=ReaderActivity::class.java.getDeclaredField("busy").apply{isAccessible=true}
        val edge=ReaderActivity::class.java.getDeclaredField("windowEnd").apply{isAccessible=true}
        val extend=ReaderActivity::class.java.getDeclaredMethod("extendWindow",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}
        var scroll:ScrollView?=null
        await{var ready=false;instrumentation.runOnMainSync{
            scroll=all(screen.window.decorView).filterIsInstance<ScrollView>().firstOrNull()
            if(scroll!=null&&!busy.getBoolean(screen)){
                ready=(scroll!!.getChildAt(0) as ViewGroup).childCount==3
                if(!ready)extend.invoke(screen,true,false)
            }
        };ready}
        var before=0
        instrumentation.runOnMainSync{before=edge.getInt(screen);scroll!!.scrollTo(0,scroll!!.getChildAt(0).height)}
        Thread.sleep(750);instrumentation.waitForIdleSync()
        var after=0;instrumentation.runOnMainSync{after=edge.getInt(screen)}
        assertTrue("一次滑动不应让加载回调接力翻过多个短章：$before → $after",after<=before+1)
    }
    private fun all(view:View):List<View> = buildList{add(view);if(view is ViewGroup)for(i in 0 until view.childCount)addAll(all(view.getChildAt(i)))}
    private fun await(condition:()->Boolean){val limit=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<limit){instrumentation.waitForIdleSync();if(condition())return;Thread.sleep(100)};fail("正文载入或位置保存未在20秒内完成")}
    override fun tearDown(){try{super.tearDown()}finally{ReaderPositions.awaitPendingWrites();store.deleteBooks(listOf(bookId))}}
}
