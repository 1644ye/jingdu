package org.quietreader

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.TextView
import org.quietreader.core.Protocol
import org.quietreader.core.ReadingSettings
import org.quietreader.reader.ReaderActivity
import org.quietreader.reader.ReaderPositions
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Captures actual rendered pixels for human visual review, not a screenshot mock. */
@Suppress("DEPRECATION")
class VisualSmokeTest : InstrumentationTestCase() {
    private val store get()=(instrumentation.targetContext.applicationContext as ReaderApp).store
    private fun <T> ui(block:()->T):T {val value=AtomicReference<T>();instrumentation.runOnMainSync{value.set(block())};return value.get()}
    private fun views(view:View):List<View> = buildList {add(view);if(view is ViewGroup)for(i in 0 until view.childCount)addAll(views(view.getChildAt(i)))}
    private fun await(block:()->Boolean){val end=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<end){instrumentation.waitForIdleSync();if(block())return;Thread.sleep(100)};fail("界面未在20秒内完成显示")}
    private fun capture(name:String){
        instrumentation.waitForIdleSync();Thread.sleep(300)
        val image=instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("设备必须返回实际截图",image)
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"visual").apply{mkdirs()}
        File(folder,"$name.png").outputStream().use{assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}
        image.recycle()
    }
    fun testRenderShelfPoetryDarkAndLandscape(){
        val previous=store.settings()
        val poetry=Protocol.copyBook(Protocol.parse(javaClass.classLoader!!.getResourceAsStream("protocol/poetry-example.json")!!.bufferedReader().use{it.readText()},"示例").document!!.getAsJsonArray("books")[0].asJsonObject)
        val novel=Protocol.copyBook(Protocol.parse(javaClass.classLoader!!.getResourceAsStream("protocol/novel-example.json")!!.bufferedReader().use{it.readText()},"示例").document!!.getAsJsonArray("books")[0].asJsonObject)
        val ids=listOf(poetry.get("id").asString,novel.get("id").asString)
        var current:Activity?=null
        try {
            store.importFile(Protocol.wrap(listOf(poetry,novel)))
            store.saveSettings(ReadingSettings())
            current=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await{ui{views(current!!.window.decorView).filterIsInstance<AbsListView>().any{(it.adapter?.count?:0)>=2}}}
            capture("shelf")
            ui{current!!.finish()};instrumentation.waitForIdleSync()
            store.saveSettings(ReadingSettings(showPinyin=true,showTranslation=true,showNotes=true),ids[0])
            current=instrumentation.startActivitySync(Intent(instrumentation.targetContext,ReaderActivity::class.java).putExtra("bookId",ids[0]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await{ui{views(current!!.window.decorView).filterIsInstance<TextView>().any{it.text.contains("床前明月光")}}}
            capture("poetry")
            ui{current!!.finish()};instrumentation.waitForIdleSync();ReaderPositions.awaitPendingWrites()
            store.saveSettings(ReadingSettings(mode="paged",theme="dark",fontSize=40f),ids[1])
            current=instrumentation.startActivitySync(Intent(instrumentation.targetContext,ReaderActivity::class.java).putExtra("bookId",ids[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await{ui{views(current!!.window.decorView).filterIsInstance<TextView>().any{it.isTextSelectable&&it.text.isNotEmpty()}}}
            capture("novel-dark-large")
            val monitor=instrumentation.addMonitor(ReaderActivity::class.java.name,null,false)
            ui{current!!.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            val recreated=instrumentation.waitForMonitorWithTimeout(monitor,8000)
            if(recreated!=null)current=recreated
            instrumentation.removeMonitor(monitor)
            await{instrumentation.targetContext.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            await{ui{views(current!!.window.decorView).filterIsInstance<TextView>().any{it.isTextSelectable&&it.text.isNotEmpty()}}}
            capture("novel-landscape-large")
        } finally {
            current?.let{screen->ui{screen.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;screen.finish()}}
            instrumentation.waitForIdleSync();ReaderPositions.awaitPendingWrites()
            store.deleteBooks(ids);store.saveSettings(previous)
        }
    }
}
