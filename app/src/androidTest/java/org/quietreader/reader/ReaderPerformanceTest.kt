package org.quietreader.reader

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.quietreader.ReaderApp
import org.quietreader.core.ReadingSettings
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Device measurements. Logcat tag QuietReaderPerf; one observed run, not a performance guarantee. */
@Suppress("DEPRECATION")
class ReaderPerformanceTest:ActivityInstrumentationTestCase2<ReaderActivity>(ReaderActivity::class.java){
    private val bookId="instrumentation-performance-${UUID.randomUUID()}"
    private val marker="终卷唯一检索标记"
    private val store get()=(instrumentation.targetContext.applicationContext as ReaderApp).store
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun elapsed(start:Long)=(now()-start)/1_000_000.0
    private fun metric(name:String,value:Double)=Log.i("QuietReaderPerf","$name=${"%.3f".format(java.util.Locale.US,value)} ms")

    override fun setUp(){
        super.setUp()
        val context=instrumentation.targetContext
        val metrics=context.resources.displayMetrics
        val mode=if(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0)"debug"else"release"
        Log.i("QuietReaderPerf","device=${Build.MANUFACTURER}/${Build.MODEL} api=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString()} mode=$mode viewport=${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.density} fontScale=${context.resources.configuration.fontScale} heapLimitBytes=${Runtime.getRuntime().maxMemory()} chapters=1000 originalCharacters=3000000 samples=1")
        val paragraph="山水明月清风书声旧城远路归人".repeat(100).take(1000)
        val volumes=JsonArray().apply{repeat(10){index->add(JsonObject().apply{addProperty("id","perf-volume-$index");addProperty("title","第${index+1}卷")})}}
        val chapters=JsonArray().apply{
            repeat(1000){chapter->add(JsonObject().apply{
                addProperty("id","perf-chapter-$chapter");addProperty("volumeId","perf-volume-${chapter/100}");addProperty("title","第${chapter+1}章")
                add("blocks",JsonArray().apply{repeat(3){block->add(JsonObject().apply{
                    addProperty("id","perf-block-$chapter-$block");addProperty("type","paragraph")
                    addProperty("text",if(chapter==999 && block==2)paragraph.dropLast(marker.length)+marker else paragraph)
                })}})
            })}
        }
        val book=JsonObject().apply{addProperty("id",bookId);addProperty("type","novel");addProperty("title","设备性能测试小说");add("tags",JsonArray().apply{add("性能测试")});add("volumes",volumes);add("chapters",chapters)}
        val document=JsonObject().apply{addProperty("schemaVersion",1);addProperty("kind","ebook-library");add("books",JsonArray().apply{add(book)})}
        val importedAt=now();val result=store.importFile(document);metric("transactional_import",elapsed(importedAt));assertEquals(1,result.imported)
        store.saveSettings(ReadingSettings(mode="paged",fontSize=22f),bookId)
        setActivityIntent(Intent(Intent.ACTION_MAIN).putExtra("bookId",bookId).putExtra("sectionId","perf-chapter-500").putExtra("contentId","perf-block-500-0").putExtra("offset",160))
    }

    fun testThousandChapterDirectoryOpenTurnSearchAndRestoreTimings(){
        val directoryAt=now();val directory=store.sections(bookId);metric("directory_1000_rows",elapsed(directoryAt));assertEquals(1000,directory.size)
        val sectionAt=now();val section=store.section(bookId,"perf-chapter-500");metric("load_one_chapter",elapsed(sectionAt));assertNotNull(section)
        val openAt=now();val screen=activity
        await("正文打开"){hasBody(screen)};metric("reader_open_to_laid_out_text",elapsed(openAt))
        await("初始进度保存"){store.position(bookId)?.sectionId=="perf-chapter-500"}
        val before=store.position(bookId)!!
        val beforeStatus=onUi{all(screen.window.decorView).filterIsInstance<TextView>().first{it.contentDescription?.toString()=="阅读进度；点击跳转"}.text.toString()}
        val turnAt=now()
        instrumentation.runOnMainSync{all(screen.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="下一页"}.performClick()}
        await("真实下一页排版"){hasBody(screen) && onUi{all(screen.window.decorView).filterIsInstance<TextView>().first{it.contentDescription?.toString()=="阅读进度；点击跳转"}.text.toString()!=beforeStatus}}
        metric("page_turn_to_laid_out_text",elapsed(turnAt))
        await("翻页进度保存"){store.position(bookId)?.let{it.contentId!=before.contentId || it.start>before.start}==true}
        val checkpoint=store.position(bookId)!!
        val searchAt=now();val hits=store.search(bookId,marker);metric("full_text_search_marker_in_last_chapter",elapsed(searchAt));assertEquals(1,hits.size);assertEquals("perf-chapter-999",hits.single().anchor.sectionId)
        instrumentation.runOnMainSync{screen.finish()};instrumentation.waitForIdleSync()
        val restoreAt=now()
        val reopened=instrumentation.startActivitySync(Intent(instrumentation.targetContext,ReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("bookId",bookId)) as ReaderActivity
        try {
            await("恢复正文"){hasBody(reopened)};metric("reopen_to_laid_out_text",elapsed(restoreAt))
            val restored=android.os.Bundle()
            instrumentation.runOnMainSync{instrumentation.callActivityOnSaveInstanceState(reopened,restored)}
            assertEquals(checkpoint.sectionId,restored.getString("sectionId"));assertEquals(checkpoint.contentId,restored.getString("contentId"));assertEquals(checkpoint.start,restored.getInt("offset"))
            val runtime=Runtime.getRuntime()
            Log.i("QuietReaderPerf","heapUsedBytesObserved=${runtime.totalMemory()-runtime.freeMemory()} heapSampleIsNotPeak=true result=PASS")
        }finally {instrumentation.runOnMainSync{reopened.finish()};instrumentation.waitForIdleSync();ReaderPositions.awaitPendingWrites()}
    }

    private fun hasBody(screen:ReaderActivity)=onUi{all(screen.window.decorView).filterIsInstance<TextView>().any{it.isTextSelectable && it.height>0 && it.layout!=null && it.text.isNotEmpty()}}
    private fun all(view:View):List<View> = buildList{add(view);if(view is ViewGroup)for(i in 0 until view.childCount)addAll(all(view.getChildAt(i)))}
    private fun <T> onUi(block:()->T):T{val output=AtomicReference<T>();instrumentation.runOnMainSync{output.set(block())};return output.get()}
    private fun await(label:String,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<end){instrumentation.waitForIdleSync();if(condition())return;Thread.sleep(20)};fail("$label 未在60秒内完成")}
    override fun tearDown(){try{super.tearDown()}finally{ReaderPositions.awaitPendingWrites();store.deleteBooks(listOf(bookId))}}
}
