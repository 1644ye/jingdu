package org.quietreader

import android.app.Activity
import android.content.Intent
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Button
import android.widget.TextView
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.quietreader.core.ReadingSettings
import org.quietreader.data.BookSummary
import org.quietreader.reader.ReaderActivity
import java.util.concurrent.atomic.AtomicReference

/** UI integration: actual bookshelf item -> book detail button -> real reader activity. */
@Suppress("DEPRECATION")
class MainActivityFlowTest:ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java){
    private val fixtureId="main-activity-instrumentation-book"
    private val fixtureTitle="书架流程测试书"
    private var oldSettings:ReadingSettings?=null
    private val store get()=(instrumentation.targetContext.applicationContext as ReaderApp).store

    override fun setUp(){
        super.setUp();oldSettings=store.settings();store.deleteBooks(listOf(fixtureId))
        val block=JsonObject().apply{addProperty("id","main-test-block");addProperty("type","paragraph");addProperty("text","这段正文用于验证从书架、详情到阅读器的真实操作。")}
        val chapter=JsonObject().apply{addProperty("id","main-test-chapter");addProperty("title","第一章");add("blocks",JsonArray().apply{add(block)})}
        val book=JsonObject().apply{addProperty("id",fixtureId);addProperty("type","novel");addProperty("title",fixtureTitle);add("chapters",JsonArray().apply{add(chapter)})}
        store.importFile(JsonObject().apply{addProperty("schemaVersion",1);addProperty("kind","ebook-library");add("books",JsonArray().apply{add(book)})})
        setActivityIntent(Intent(Intent.ACTION_MAIN))
    }

    fun testShelfToDetailsToReader(){
        val main=activity
        await("书架必须展示实际入库书籍"){
            onUi { all(main.window.decorView).filterIsInstance<AbsListView>().any{list->(0 until (list.adapter?.count?:0)).any{(list.adapter.getItem(it) as? BookSummary)?.id==fixtureId}} }
        }
        var list:AbsListView?=null;var position=0
        instrumentation.runOnMainSync {
            list=all(main.window.decorView).filterIsInstance<AbsListView>().first{it.adapter!=null}
            position=(0 until list!!.adapter.count).first{(list!!.adapter.getItem(it) as? BookSummary)?.id==fixtureId}
            list!!.setSelection(position)
        }
        instrumentation.waitForIdleSync()
        await("目标书籍行应可见"){onUi{list!!.getChildAt(position-list!!.firstVisiblePosition)!=null}}
        instrumentation.runOnMainSync {
            val row=list!!.getChildAt(position-list!!.firstVisiblePosition)
            assertTrue(list!!.performItemClick(row,position,list!!.adapter.getItemId(position)))
        }
        await("详情应有真实开始阅读按钮"){onUi{all(main.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="开始阅读"}}}
        assertTrue(onUi{all(main.window.decorView).filterIsInstance<TextView>().any{it.text.toString()==fixtureTitle}})
        val monitor=instrumentation.addMonitor(ReaderActivity::class.java.name,null,false)
        var reader:Activity?=null
        try {
            instrumentation.runOnMainSync {all(main.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="开始阅读"}.performClick()}
            reader=instrumentation.waitForMonitorWithTimeout(monitor,15000)
            assertNotNull("必须启动真实 ReaderActivity",reader)
            assertEquals(fixtureId,reader!!.intent.getStringExtra("bookId"))
            await("阅读正文应真实显示"){onUi{all(reader!!.window.decorView).filterIsInstance<TextView>().any{it.isTextSelectable&&it.text.contains("书架、详情到阅读器")}}}
            await("自动保存阅读记录"){store.position(fixtureId)?.contentId=="main-test-block"}
        }finally {
            reader?.let{screen->instrumentation.runOnMainSync{screen.finish()}}
            instrumentation.removeMonitor(monitor)
        }
    }

    fun testGlobalSettingsDoesNotRequireBookIdentifier(){
        setActivityIntent(Intent(Intent.ACTION_MAIN).putExtra("screen","settings"))
        val main=activity
        await("不传bookId也应能打开全局设置"){onUi{all(main.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="保存并返回书架"}}}
        assertNull(main.intent.getStringExtra("bookId"))
        assertTrue(onUi{all(main.window.decorView).filterIsInstance<TextView>().any{it.text.contains("适用于未单独设置的书籍")}})
        instrumentation.runOnMainSync{all(main.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="保存并返回书架"}.performClick()}
        await("保存后返回实际书架"){onUi{all(main.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="更多"}}}
    }

    fun testNotesParentRestoresAfterRecreate(){
        val original=activity
        await("书架初始化"){onUi{all(original.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="更多"}}}
        instrumentation.runOnMainSync{original.javaClass.getDeclaredMethod("navigate",String::class.java,String::class.java).apply{isAccessible=true}.invoke(original,"detail",fixtureId)}
        await("详情载入"){onUi{all(original.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="书签与笔记"}}}
        instrumentation.runOnMainSync{all(original.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="书签与笔记"}.performClick()}
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false);var restored:MainActivity?=null
        try{
            instrumentation.runOnMainSync{original.recreate()};restored=instrumentation.waitForMonitorWithTimeout(monitor,10000) as? MainActivity;assertNotNull(restored)
            await("笔记页面恢复"){onUi{all(restored!!.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="<"}}}
            instrumentation.runOnMainSync{all(restored!!.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="<"}.performClick()}
            await("返回本书详情而非直接退回书架"){onUi{all(restored!!.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="编辑信息"}}}
        }finally{restored?.let{screen->instrumentation.runOnMainSync{screen.finish()}};instrumentation.removeMonitor(monitor)}
    }
    fun testGlobalAuxiliaryControlsReflectExplicitModesInsteadOfLegacyFlags(){
        val configured=ReadingSettings(showPinyin=false,pinyinDisplay="link",showNotes=false,notesDisplay="inline")
        store.saveSettings(configured);val main=activity
        await("书架初始化"){onUi{all(main.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="更多"}}}
        onUi{main.javaClass.getDeclaredMethod("navigate",String::class.java,String::class.java).apply{isAccessible=true}.invoke(main,"settings",null)}
        await("六个全局显示控件应反映实际模式"){
            onUi{val spinners=all(main.window.decorView).filterIsInstance<android.widget.Spinner>()
                spinners.firstOrNull{it.contentDescription=="全局拼音 显示形式"}?.selectedItem=="点击链接浮现"&&spinners.firstOrNull{it.contentDescription=="全局逐行注释 显示形式"}?.selectedItem=="正文大小"&&spinners.count{it.contentDescription?.startsWith("全局")==true}==6}
        }
        onUi{all(main.window.decorView).filterIsInstance<android.widget.Spinner>().first{it.contentDescription=="全局拼音 显示形式"}.setSelection(1)}
        await("全局拼音新模式应保存"){store.settings().pinyinDisplay=="small"}
    }
    private fun all(view:View):List<View> = buildList{add(view);if(view is ViewGroup)for(i in 0 until view.childCount)addAll(all(view.getChildAt(i)))}
    private fun <T> onUi(block:()->T):T {val result=AtomicReference<T>();instrumentation.runOnMainSync{result.set(block())};return result.get()}
    private fun await(message:String,condition:()->Boolean){val end=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<end){instrumentation.waitForIdleSync();if(condition())return;Thread.sleep(100)};fail(message)}
    override fun tearDown(){val saved=oldSettings;val targetStore=store;val id=fixtureId;try{super.tearDown()}finally{org.quietreader.reader.ReaderPositions.awaitPendingWrites();targetStore.deleteBooks(listOf(id));saved?.let{targetStore.saveSettings(it)}}}
}
