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
        installShortSections()
        val screen=activity
        val busy=ReaderActivity::class.java.getDeclaredField("busy").apply{isAccessible=true}
        val edge=ReaderActivity::class.java.getDeclaredField("windowEnd").apply{isAccessible=true}
        val extend=ReaderActivity::class.java.getDeclaredMethod("extendWindow",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}
        var scroll:ScrollView?=null
        await{var ready=false;instrumentation.runOnMainSync{
            scroll=all(screen.window.decorView).filterIsInstance<ScrollView>().firstOrNull()
            if(scroll!=null&&!busy.getBoolean(screen)){
                ready=(scroll!!.getChildAt(0) as ViewGroup).childCount>=3
                if(!ready)extend.invoke(screen,true,false)
            }
        };ready}
        var before=0;var y=0;var first:View?=null
        instrumentation.runOnMainSync{before=edge.getInt(screen);first=(scroll!!.getChildAt(0) as ViewGroup).getChildAt(0);scroll!!.scrollTo(0,scroll!!.getChildAt(0).height);y=scroll!!.scrollY}
        Thread.sleep(750);instrumentation.waitForIdleSync()
        var after=0;var same=false;var finalY=0
        instrumentation.runOnMainSync{after=edge.getInt(screen);same=first===(scroll!!.getChildAt(0) as ViewGroup).getChildAt(0);finalY=scroll!!.scrollY}
        assertTrue("只允许补足有限预读窗口：$before → $after",after<=before+3)
        assertTrue("预读不能接力回收并推进当前正文",same)
        assertEquals("追加预读内容不能自行滚动",y,finalY)
    }
    private fun installShortSections(start:Int=0){
        val chapters=JsonArray().apply{repeat(60){i->add(JsonObject().apply{
            addProperty("id","c$i");addProperty("title","短章 $i")
            add("blocks",JsonArray().apply{add(JsonObject().apply{addProperty("id","b$i");addProperty("type","paragraph");addProperty("text","月光落在书页上。")})})
        })}}
        store.deleteBooks(listOf(bookId))
        store.importFile(JsonObject().apply{addProperty("schemaVersion",1);addProperty("kind","ebook-library");add("books",JsonArray().apply{add(JsonObject().apply{addProperty("id",bookId);addProperty("type","novel");addProperty("title","短章");add("chapters",chapters)})})})
        setActivityIntent(Intent(Intent.ACTION_MAIN).putExtra("bookId",bookId).putExtra("sectionId","c$start"))
    }
    fun testUpwardScrollNearBothEdgesLoadsPreviousInsteadOfNext(){
        installShortSections(30)
        val screen=activity
        val busy=ReaderActivity::class.java.getDeclaredField("busy").apply{isAccessible=true}
        val start=ReaderActivity::class.java.getDeclaredField("windowStart").apply{isAccessible=true}
        val end=ReaderActivity::class.java.getDeclaredField("windowEnd").apply{isAccessible=true}
        var scroll:ScrollView?=null
        await{var ready=false;instrumentation.runOnMainSync{
            scroll=all(screen.window.decorView).filterIsInstance<ScrollView>().firstOrNull()
            ready=scroll!=null&&!busy.getBoolean(screen)&&scroll!!.scrollY>80
        };ready}
        var before=0;var oldEnd=0
        instrumentation.runOnMainSync{before=start.getInt(screen);oldEnd=end.getInt(screen);scroll!!.scrollTo(0,0)}
        await{var ready=false;instrumentation.runOnMainSync{ready=!busy.getBoolean(screen)&&start.getInt(screen)<before};ready}
        var newEnd=0;instrumentation.runOnMainSync{newEnd=end.getInt(screen)}
        assertTrue("向上回看不能自动加载后续短章：$oldEnd → $newEnd",newEnd<=oldEnd)
    }
    fun testLoadingDuringDraggingKeepsVisibleTextAndGesturePosition(){loadingWhileHeld(true)}
    fun testPreviousContentArrivesWhileFingerHeldWithoutMovingVisibleText(){loadingWhileHeld(false)}
    private fun loadingWhileHeld(forward:Boolean){
        val screen=activity
        await{store.position(bookId)?.start?.let{it>30000}==true}
        val busy=ReaderActivity::class.java.getDeclaredField("busy").apply{isAccessible=true}
        val extend=ReaderActivity::class.java.getDeclaredMethod("extendWindow",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}
        var scroll:ScrollView?=null;var reference:View?=null;var beforeTop=0
        await{var ready=false;instrumentation.runOnMainSync{
            scroll=all(screen.window.decorView).filterIsInstance<ScrollView>().first()
            val window=scroll!!.getChildAt(0) as ViewGroup
            ready=window.childCount==3&&!busy.getBoolean(screen)
            if(!ready&&!busy.getBoolean(screen))extend.invoke(screen,true,false)
        };ready}
        val down=android.os.SystemClock.uptimeMillis()
        fun touch(action:Int,y:Float){val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,scroll!!.width*.5f,y,0);scroll!!.dispatchTouchEvent(event);event.recycle()}
        instrumentation.runOnMainSync{
            val window=scroll!!.getChildAt(0) as ViewGroup
            reference=window.getChildAt(if(forward)1 else 0)
            busy.setBoolean(screen,true)
            scroll!!.scrollTo(0,if(forward)window.getChildAt(0).bottom+20 else 0)
            busy.setBoolean(screen,false)
            extend.invoke(screen,forward,false)
            touch(android.view.MotionEvent.ACTION_DOWN,scroll!!.height*.5f)
            touch(android.view.MotionEvent.ACTION_MOVE,scroll!!.height*.6f)
            val location=IntArray(2);reference!!.getLocationOnScreen(location);beforeTop=location[1]
        }
        Thread.sleep(300)
        repeat(12){i->
            instrumentation.runOnMainSync{touch(android.view.MotionEvent.ACTION_MOVE,scroll!!.height*(if(i%2==0).5f else .6f))}
            Thread.sleep(20)
        }
        var ready=false;var afterTop=0;var previousInserted=false
        try{
            instrumentation.runOnMainSync{ready=!busy.getBoolean(screen);previousInserted=(scroll!!.getChildAt(0) as ViewGroup).getChildAt(0)!==reference;val location=IntArray(2);reference!!.getLocationOnScreen(location);afterTop=location[1]}
            assertTrue("按住拖动时也必须及时显示已加载的正文",ready)
            if(!forward)assertTrue("前文必须已实际插入，不能只取消加载任务",previousInserted)
            assertTrue("加载不能移动原可见正文：$beforeTop → $afterTop",kotlin.math.abs(afterTop-beforeTop)<=2)
        }
        finally{instrumentation.runOnMainSync{touch(android.view.MotionEvent.ACTION_CANCEL,scroll!!.height*.6f)}}
        await{var idle=false;instrumentation.runOnMainSync{idle=!busy.getBoolean(screen)};idle}
    }
    private fun all(view:View):List<View> = buildList{add(view);if(view is ViewGroup)for(i in 0 until view.childCount)addAll(all(view.getChildAt(i)))}
    private fun await(condition:()->Boolean){val limit=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<limit){instrumentation.waitForIdleSync();if(condition())return;Thread.sleep(100)};fail("正文载入或位置保存未在20秒内完成")}
    override fun tearDown(){try{super.tearDown()}finally{ReaderPositions.awaitPendingWrites();store.deleteBooks(listOf(bookId))}}
}
