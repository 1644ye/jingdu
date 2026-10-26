package org.quietreader.data

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.test.AndroidTestCase
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.quietreader.core.Anchor
import org.quietreader.core.Protocol
import org.quietreader.core.ReadingSettings
import java.io.File

/** Real Android SQLite measurements, intentionally no machine-dependent duration assertions. */
@Suppress("DEPRECATION")
class PerformanceTest : AndroidTestCase() {
    fun testLargeLibraryImportSearchResumeAndEmptyRestore() {
        val sourceName="performance-source.db"
        val restoredName="performance-restored.db"
        context.deleteDatabase(sourceName); context.deleteDatabase(restoredName)
        var source=LibraryStore(context,sourceName)
        val restored=LibraryStore(context,restoredName)
        val stages=JsonArray()
        val report=JsonObject().apply {
            addProperty("deviceModel",Build.MODEL); addProperty("manufacturer",Build.MANUFACTURER)
            addProperty("api",Build.VERSION.SDK_INT); addProperty("androidRelease",Build.VERSION.RELEASE)
            addProperty("abis",Build.SUPPORTED_ABIS.joinToString(",")); addProperty("package",context.packageName)
            addProperty("buildMode",if(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release")
            addProperty("poemCount",1000); addProperty("novelChapters",1000); addProperty("novelChineseCharacters",3_000_000)
            addProperty("batchBookCount",10); addProperty("memoryNote","PSS 与 Java heap 为各阶段完成后的观测值，不是峰值；没有强制 GC。")
            addProperty("measurementScope","Android SQLite 数据层：导入、目录、正文按需读取、中文搜索、进度、备份恢复；不代表UI首帧或翻页性能。")
            val memory=ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            addProperty("deviceTotalRamBytes",memory.totalMem)
            addProperty("startedAt",System.currentTimeMillis()); add("stages",stages)
        }
        fun memory(): Pair<Int,Long> {
            val m=Debug.MemoryInfo(); Debug.getMemoryInfo(m)
            val runtime=Runtime.getRuntime()
            return m.totalPss to runtime.totalMemory()-runtime.freeMemory()
        }
        fun <T> measure(name:String,action:()->T):T {
            val start=SystemClock.elapsedRealtime()
            val result=action()
            val duration=SystemClock.elapsedRealtime()-start
            val (pss,heap)=memory()
            val entry=JsonObject().apply { addProperty("stage",name); addProperty("elapsedMs",duration); addProperty("observedPssKiB",pss); addProperty("observedJavaHeapBytes",heap) }
            stages.add(entry); Log.i("QuietReaderPerf",entry.toString())
            return result
        }
        var protection:File?=null
        try {
            Log.i("QuietReaderPerf","device=${Build.MODEL} api=${Build.VERSION.SDK_INT} mode=${report.get("buildMode").asString}; 1000 poems; 1000 chapters / 3000000 CJK characters; 10-book batch")
            val poetry=measure("generate-1000-poems") {
                JsonObject().apply {
                    addProperty("id","perf-poetry"); addProperty("type","poetry"); addProperty("title","千首诗压力测试"); addProperty("author","测试生成器")
                    add("tags",JsonArray().apply { add("测试") })
                    add("poems",JsonArray().apply { repeat(1000) { i -> add(JsonObject().apply {
                        addProperty("id","poem-$i"); addProperty("title","测试诗$i")
                        add("stanzas",JsonArray().apply { add(JsonObject().apply {
                            addProperty("id","stanza-$i")
                            add("lines",JsonArray().apply { listOf("床前明月光，","疑是地上霜。","举头望明月，","低头思故乡。").forEachIndexed { j,line -> add(JsonObject().apply {
                                addProperty("id","line-$i-$j"); addProperty("original",line)
                                addProperty("pinyin","测试拼音辅助文本"); addProperty("translation","用于检查辅助内容的逐行译文。"); addProperty("note","测试注释")
                            }) } })
                        }) })
                    }) } })
                }
            }
            measure("import-1000-poems") { assertEquals(1,source.importFile(Protocol.wrap(listOf(poetry)),emptyMap()).imported) }
            val novel=measure("generate-1000-chapters-3000000-characters") { novel() }
            val chineseCount=novel.getAsJsonArray("chapters").sumOf { chapter -> chapter.asJsonObject.getAsJsonArray("blocks").sumOf { it.asJsonObject.get("text").asString.length } }
            assertEquals(3_000_000,chineseCount)
            measure("import-1000-chapters-3000000-characters") { assertEquals(1,source.importFile(Protocol.wrap(listOf(novel)),emptyMap()).imported) }
            measure("import-10-book-batch") {
                val books=(0 until 10).map { i -> JsonObject().apply {
                    addProperty("id","batch-$i"); addProperty("type","novel"); addProperty("title","批量测试$i")
                    add("chapters",JsonArray().apply { add(JsonObject().apply {
                        addProperty("id","batch-chapter-$i"); addProperty("title","起始章")
                        add("blocks",JsonArray().apply { add(JsonObject().apply { addProperty("id","batch-paragraph-$i"); addProperty("type","paragraph"); addProperty("text","离线阅读测试正文。") }) })
                    }) })
                } }
                assertEquals(10,source.importFile(Protocol.wrap(books),emptyMap()).imported)
            }
            measure("list-books-12") { assertEquals(12,source.listBooks().size) }
            measure("poetry-directory-1000") { assertEquals(1000,source.sections("perf-poetry").size) }
            measure("novel-directory-1000") { assertEquals(1000,source.sections("perf-novel").size) }
            measure("read-one-middle-chapter") {
                val section=source.section("perf-novel","n-c-500")!!
                assertEquals(3000,section.getAsJsonArray("blocks").sumOf { it.asJsonObject.get("text").asString.length })
            }
            val hit=measure("chinese-search-unique-hit-in-last-chapter") {
                val results=source.search("perf-novel",MARKER)
                assertEquals(1,results.size)
                results.single().also { assertEquals("n-c-999",it.anchor.sectionId); assertEquals("n-p-999-0",it.anchor.contentId); assertEquals(120,it.anchor.start); assertEquals(MARKER,it.anchor.quote) }
            }
            measure("save-location-note-settings") {
                source.savePosition(hit.anchor,0.999f)
                source.saveAnnotation(hit.anchor,"note","大规模书籍恢复验证")
                source.saveSettings(ReadingSettings(fontSize=30f,theme="dark"),"perf-novel")
                val group=source.createGroup("性能测试收藏")
                source.setMembership(listOf("perf-poetry","perf-novel"),group,true)
                source.togglePoemFavorite("perf-poetry","poem-999")
            }
            measure("close-reopen-and-restore-location") {
                source.close(); source=LibraryStore(context,sourceName)
                val location=source.position("perf-novel")!!
                assertEquals(hit.anchor.contentId,location.contentId); assertEquals(120,location.start); assertFalse(location.orphaned)
            }
            val backup=measure("assemble-full-backup") { source.backup() }
            measure("validate-full-backup") { assertEquals(emptyList<String>(),source.validateBackup(backup)) }
            val bytes=measure("serialize-full-backup-utf8") { backup.toString().toByteArray(Charsets.UTF_8).size }
            report.addProperty("serializedBackupBytes",bytes)
            measure("restore-backup-into-empty-database") {
                assertTrue(restored.listBooks().isEmpty()); protection=restored.restoreBackup(backup)
            }
            measure("verify-restored-relations-and-location") {
                assertEquals(12,restored.listBooks().size); assertEquals(1000,restored.sections("perf-novel").size)
                assertEquals("n-p-999-0",restored.position("perf-novel")!!.contentId)
                assertEquals(120,restored.position("perf-novel")!!.start)
                assertEquals("大规模书籍恢复验证",restored.annotations("perf-novel").single().note)
                assertEquals(30f,restored.settings("perf-novel").fontSize,0.001f)
                assertEquals(1,restored.groupIds("perf-novel").size)
                assertEquals("poem-999",restored.favoritePoems().single().poemId)
                assertEquals(hit.anchor,restored.search("perf-novel",MARKER).single().anchor)
            }
            report.addProperty("result","passed")
        } catch(error:Throwable) {
            report.addProperty("result","failed"); report.addProperty("error",error.toString()); throw error
        } finally {
            report.addProperty("finishedAt",System.currentTimeMillis())
            val file=File(context.filesDir,"performance-result.json")
            file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report),Charsets.UTF_8)
            Log.i("QuietReaderPerf","report=${file.absolutePath}; result=${report.get("result")}")
            source.close(); restored.close(); protection?.delete()
            context.deleteDatabase(sourceName); context.deleteDatabase(restoredName)
        }
    }

    private fun novel():JsonObject {
        val seed="山城雨后书店来信写满远行与归乡"
        val body=seed.repeat(3000/seed.length+1).take(3000)
        return JsonObject().apply {
            addProperty("id","perf-novel"); addProperty("type","novel"); addProperty("title","三百万字压力测试")
            add("volumes",JsonArray().apply { repeat(10) { i -> add(JsonObject().apply { addProperty("id","volume-$i"); addProperty("title","测试卷$i") }) } })
            add("chapters",JsonArray().apply { repeat(1000) { i ->
                val text=if(i==999) body.replaceRange(120,120+MARKER.length,MARKER) else body
                add(JsonObject().apply {
                    addProperty("id","n-c-$i"); addProperty("volumeId","volume-${i/100}"); addProperty("title","测试章$i")
                    add("blocks",JsonArray().apply { repeat(2) { block -> add(JsonObject().apply {
                        addProperty("id","n-p-$i-$block"); addProperty("type","paragraph"); addProperty("text",text.substring(block*1500,(block+1)*1500))
                    }) } })
                })
            } })
        }
    }

    companion object { private const val MARKER="寻山临水知音" }
}
