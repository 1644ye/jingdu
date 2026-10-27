package org.quietreader

import android.app.Activity
import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.graphics.Color
import android.graphics.Bitmap
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Button
import android.widget.EditText
import android.widget.AbsListView
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.quietreader.core.Anchor
import org.quietreader.core.Content
import org.quietreader.core.Protocol
import org.quietreader.core.ReadingSettings
import org.quietreader.reader.ReaderPositions
import org.quietreader.reader.ReaderActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the real Activity controls and result handlers. ActivityMonitor supplies a local
 * document URI in place of the external system picker; this is not a manual picker UI test.
 * Every test restores a complete snapshot, including pre-existing books and reading records.
 */
@Suppress("DEPRECATION")
class ImportFlowTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private val store get() = (instrumentation.targetContext.applicationContext as ReaderApp).store
    private val fixtureId = "import-flow-${UUID.randomUUID()}"
    private val fixtureTitle = "文件流程验证 $fixtureId"
    private val fixtureText = "清晨推窗，山色入帘。这里保留完整正文与稳定定位。"
    private var original: JsonObject? = null
    private var main: MainActivity? = null
    private val testFiles = mutableListOf<File>()
    private var recoveryBefore = emptySet<String>()
    private var accessibilityFlags: Int? = null

    override fun setUp() {
        super.setUp()
        ReaderPositions.awaitPendingWrites()
        original = store.backup()
        recoveryBefore = recoveryFiles().map { it.name }.toSet()
        val automation = instrumentation.uiAutomation
        val service = automation.serviceInfo
        accessibilityFlags = service.flags
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = service
        setActivityIntent(Intent(Intent.ACTION_MAIN))
    }

    fun testImportAndExistingBookDefaultSkipThroughPicker() {
        start()
        val input = file("import.json", Protocol.wrap(listOf(book())).toString())
        selectDocument("导入 JSON", input)
        clickText("确认导入 1 个文件")
        await("有效文件应通过实际确认按钮写入书库") { store.book(fixtureId) != null && hasText("实际导入结果") }
        assertTrue(hasText("新建 1", partial = true))
        assertEquals(fixtureText, Content.texts(Content.sections(store.book(fixtureId)!!).single(), "novel").getValue("line"))
        val revision = store.listBooks().first { it.id == fixtureId }.contentRevision
        clickText("查看书架")

        input.writeText(Protocol.wrap(listOf(book(title = "不应覆盖原书名"))).toString(), Charsets.UTF_8)
        selectDocument("导入 JSON", input)
        await("同 ID 文件应显示默认跳过选项") { hasText("跳过（默认）") }
        clickText("确认导入 1 个文件")
        await("默认跳过应产生实际结果") { hasText("实际导入结果") }
        assertTrue(hasText("跳过 1", partial = true))
        assertEquals(fixtureTitle, store.book(fixtureId)!!.get("title").asString)
        assertEquals(revision, store.listBooks().first { it.id == fixtureId }.contentRevision)
        clickText("查看书架")
    }

    fun testInvalidFileDoesNotPartiallyImportItsValidBook() {
        start()
        val invalid = book(id = "$fixtureId-invalid").apply { remove("title") }
        val input = file("invalid.json", Protocol.wrap(listOf(book(), invalid)).toString())
        selectDocument("导入 JSON", input)
        await("错误文件应停在诊断预览") { hasText("1 个文件有错误", partial = true) }
        assertTrue(hasText("0 本可导入", partial = true))
        assertFalse("错误文件不能出现可提交按钮", hasText("确认导入 1 个文件"))
        assertNull("同文件中的有效书也不得部分入库", store.book(fixtureId))
        assertNull(store.book("$fixtureId-invalid"))
        clickText("返回书架")
    }

    fun testCopyConflictIsSelectedUsingActualSpinner() {
        store.importFile(Protocol.wrap(listOf(book())))
        start()
        val input = file("copy.json", Protocol.wrap(listOf(book())).toString())
        selectDocument("导入 JSON", input)
        clickText("跳过（默认）")
        clickText("作为副本导入")
        clickText("确认导入 1 个文件")
        await("副本导入应完成并报告实际结果") { hasText("实际导入结果") }
        assertTrue(hasText("副本 1", partial = true))
        val copies = store.listBooks().filter { it.title == fixtureTitle }
        assertEquals(2, copies.size)
        val copied = store.book(copies.single { it.id != fixtureId }.id)!!
        val originalBook = store.book(fixtureId)!!
        assertFalse(Content.sections(copied).single().get("id").asString == Content.sections(originalBook).single().get("id").asString)
        assertFalse(Content.texts(Content.sections(copied).single(), "novel").containsKey("line"))
        assertEquals(listOf(fixtureText), Content.texts(Content.sections(copied).single(), "novel").values.toList())
        assertTrue(Protocol.validate(Protocol.wrap(listOf(copied)), "copy.json").valid)
        clickText("查看书架")
    }

    fun testContentExportAndWholeBackupRestoreThroughPickers() {
        store.importFile(Protocol.wrap(listOf(book())))
        val anchor = Anchor(fixtureId, "chapter", "line", 0, 2, fixtureText.substring(0, 2))
        store.savePosition(anchor, .42f)
        store.saveAnnotation(anchor, "note", "恢复后仍应存在的笔记")
        store.toggleFavorite(fixtureId)
        val group = store.createGroup("恢复测试 $fixtureId")
        store.setMembership(listOf(fixtureId), group, true)
        val savedSettings = store.settings().copy(theme = "sepia", fontSize = 25f)
        store.saveSettings(savedSettings)
        setActivityIntent(Intent(Intent.ACTION_MAIN).putExtra("screen", "detail").putExtra("bookId", fixtureId))
        start("导出 JSON")

        val contentFile = file("exported-content.json")
        selectDocument("导出 JSON", contentFile, Intent.ACTION_CREATE_DOCUMENT)
        await("实际内容导出应写完且返回详情") { contentFile.length() > 0 && hasText("导出 JSON") }
        val content = Protocol.parse(contentFile.readText(Charsets.UTF_8), contentFile.name)
        assertTrue(content.errors.toString(), content.valid)
        assertEquals(listOf(fixtureId), content.document!!.getAsJsonArray("books").map { it.asJsonObject.get("id").asString })
        assertFalse("内容文件不应混入阅读记录", content.document!!.has("annotations"))
        clickText("返回书架")
        clickText("更多")
        clickText("备份与恢复")

        val backupFile = file("exported-backup.json")
        selectDocument("导出完整备份", backupFile, Intent.ACTION_CREATE_DOCUMENT)
        await("完整备份导出应写完并回到工具页") { backupFile.length() > 0 && hasText("选择备份并恢复") }
        val backup = JsonParser.parseString(backupFile.readText(Charsets.UTF_8)).asJsonObject
        assertEquals(emptyList<String>(), store.validateBackup(backup))
        assertTrue(backup.getAsJsonArray("annotations").any { it.asJsonObject.get("note").asString == "恢复后仍应存在的笔记" })

        // Deliberately change state, then restore via the same UI the user operates.
        store.deleteBooks(listOf(fixtureId))
        store.saveSettings(savedSettings.copy(theme = "dark", fontSize = 19f))
        val beforeRestore = recoveryFiles().map { it.name }.toSet()
        selectDocument("选择备份并恢复", backupFile)
        await("恢复前必须先显示整体替换摘要") { hasText("整体替换恢复？") }
        assertNull("确认恢复之前不能写入", store.book(fixtureId))
        clickText("确认替换恢复")
        await("恢复完成应有明确结果") { hasText("恢复完成") }
        assertNotNull(store.book(fixtureId))
        assertEquals(anchor.contentId, store.position(fixtureId)!!.contentId)
        assertEquals(anchor.start, store.position(fixtureId)!!.start)
        assertEquals(.42f, store.listBooks().first { it.id == fixtureId }.progress, .0001f)
        assertTrue(store.listBooks().first { it.id == fixtureId }.favorite)
        assertEquals("恢复后仍应存在的笔记", store.annotations(fixtureId).single().note)
        assertTrue(group in store.groupIds(fixtureId))
        assertEquals(savedSettings, store.settings())
        assertTrue("恢复必须生成当前数据的保护备份", recoveryFiles().any { it.name !in beforeRestore })

        // The recovery file is also reachable from the completion dialog, not just on disk.
        val protectionFile = file("before-restore.json")
        selectDocument("导出保护备份", protectionFile, Intent.ACTION_CREATE_DOCUMENT)
        await("应能实际导出恢复前保护备份") { protectionFile.length() > 0 && hasText("更多") }
        val protection = JsonParser.parseString(protectionFile.readText(Charsets.UTF_8)).asJsonObject
        assertEquals(emptyList<String>(), store.validateBackup(protection))
        assertFalse(protection.getAsJsonObject("content").getAsJsonArray("books").any { it.asJsonObject.get("id").asString == fixtureId })
    }

    fun testMoreMenuTemplateDownloadAndShelfManagement() {
        store.importFile(Protocol.wrap(listOf(book())))
        start()
        val homeButtons=onUi{all(main!!.window.decorView).filterIsInstance<Button>().map{it.text.toString()}}
        listOf("导入 JSON","下载模板","全选当前","取消选择","批量操作").forEach{assertFalse("首页不应显示 $it",it in homeButtons)}
        clickText("更多")
        listOf("导入 JSON","下载模板","书架管理","应用配色").forEach{assertTrue("更多应有 $it",hasText(it))}
        clickText("下载模板")
        await("下载模板应打开可操作页面"){hasText("诗集最简模板")}
        val template=file("template.json")
        selectDocument("下载",template,Intent.ACTION_CREATE_DOCUMENT)
        await("移动后仍能实际下载模板"){template.length()>0 && hasText("诗集最简模板")}
        assertTrue(Protocol.parse(template.readText(),"template.json").valid)
        clickText("返回书架");clickText("更多");clickText("书架管理")
        await("管理页面应有批量控制"){hasText("全选当前")&&hasText("取消选择")&&hasText("批量操作")}
        onUi{all(main!!.window.decorView).filterIsInstance<EditText>().first{it.hint.toString()=="搜索书名、作者、标签"}.setText(fixtureTitle)}
        await("应只筛选出测试书"){onUi{all(main!!.window.decorView).filterIsInstance<AbsListView>().single().adapter.count==1}}
        clickText("全选当前");await("全选作用于当前筛选结果"){hasText("已选择 1 本")}
        clickText("取消选择");assertTrue(hasText("共 1 本 · 点击书籍选择"))
        onUi{val list=all(main!!.window.decorView).filterIsInstance<AbsListView>().single();list.performItemClick(list.getChildAt(0),0,list.adapter.getItemId(0))}
        await("取消选择后点击书籍仍应选择而非打开详情"){hasText("已选择 1 本")}
        clickText("批量操作");clickText("删除书籍");await("删除仍须确认"){hasText("删除 1 本书？")};clickText("取消")
        assertNotNull(store.book(fixtureId))
        capture("bookshelf-management")
        clickText("返回书架");assertFalse(hasText("批量操作"))
    }

    fun testCustomPaletteHexRgbSaveAndRecreate() {
        store.saveSettings(ReadingSettings())
        store.importFile(Protocol.wrap(listOf(book())))
        start();clickText("更多");clickText("应用配色")
        await("应显示自由配色编辑器"){hasText("背景颜色  #F8F6F0")}
        clickText("背景颜色  #F8F6F0")
        setColorInput("背景颜色","#NOPE")
        clickText("应用")
        assertTrue("无效色值应保留编辑器",withAccessibilityNode({it.isEditable&&it.contentDescription?.toString()=="背景颜色 色值"}){true}==true)
        setColorInput("背景颜色","#102030");clickText("应用")
        clickText("卡片颜色  #FFFDF7");setColorInput("卡片颜色","#F2E5D5");clickText("应用")
        clickText("按钮颜色  #416752");setColorInput("按钮颜色","#FFFF00")
        val progress=Bundle().apply{putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,64f)}
        await("RGB滑杆应真实修改色值"){withAccessibilityNode({it.contentDescription?.toString()=="蓝 B"}){it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,progress)}==true}
        clickText("应用")
        await("RGB修改应回填颜色"){hasText("按钮颜色  #FFFF40")}
        capture("appearance-editor")
        clickText("保存配色")
        await("配色应持久化并回到书架"){store.settings().theme=="custom"&&hasText("更多")}
        val saved=store.settings()
        assertEquals("#102030",saved.customBackground);assertEquals("#F2E5D5",saved.customSurface);assertEquals("#FFFF40",saved.customAccent)
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        try {
            onUi{main!!.recreate()}
            val recreated=instrumentation.waitForMonitorWithTimeout(monitor,15000) as? MainActivity
            assertNotNull("重新创建应用页面应成功",recreated)
            main=recreated;setActivity(recreated)
            await("重建后应显示书架"){hasText("更多")&&onUi{all(main!!.window.decorView).filterIsInstance<AbsListView>().any{it.adapter!=null}}}
            assertEquals(saved,store.settings())
            val more=onUi{all(main!!.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="更多"}}
            assertEquals("亮色按钮应自动使用深色文字",Color.BLACK,onUi{more.currentTextColor})
            assertEquals(Color.WHITE,Ui(main!!,saved).foreground)
            assertEquals(Color.BLACK,Ui(main!!,saved).surfaceText)
            capture("custom-colors-shelf")
            val reader=instrumentation.startActivitySync(Intent(instrumentation.targetContext,ReaderActivity::class.java).putExtra("bookId",fixtureId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                await("阅读页应加载并继承配色"){onUi{all(reader.window.decorView).filterIsInstance<TextView>().any{it.isTextSelectable&&it.text.contains(fixtureText)}}}
                val button=onUi{all(reader.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="设置"}}
                assertEquals(Color.parseColor("#FFFF40"),onUi{button.backgroundTintList!!.defaultColor})
                assertEquals(Color.BLACK,onUi{button.currentTextColor})
                capture("custom-colors-reader")
            } finally {onUi{reader.finish()};instrumentation.waitForIdleSync();ReaderPositions.awaitPendingWrites()}
        } finally {instrumentation.removeMonitor(monitor)}
    }

    private fun setColorInput(label:String,value:String){
        val args=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}
        await("$label 的弹窗输入框应可编辑"){withAccessibilityNode({it.isEditable&&it.contentDescription?.toString()=="$label 色值"}){it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)}==true}
        instrumentation.waitForIdleSync()
    }
    private fun capture(name:String){
        // Let dialog dismissal and feedback toasts finish before saving reviewable pixels.
        instrumentation.waitForIdleSync();Thread.sleep(4000)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"visual").apply{mkdirs()}
        File(folder,"$name.png").outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
    }

    private fun start(expected: String = "更多") {
        main = activity
        await("主界面应完成初始化并显示 $expected") { hasText(expected) }
        if(expected=="更多")await("书架应加载完成"){onUi{all(main!!.window.decorView).filterIsInstance<AbsListView>().any{it.adapter!=null}}}
    }

    private fun book(id: String = fixtureId, title: String = fixtureTitle): JsonObject = JsonParser.parseString("""
        {"id":"$id","type":"novel","title":"$title","author":"测试作者","chapters":[
          {"id":"chapter","title":"第一章","blocks":[{"id":"line","type":"paragraph","text":"$fixtureText"}]}]}
    """).asJsonObject

    private fun file(suffix: String, text: String = ""): File = File(instrumentation.targetContext.filesDir, "$fixtureId-$suffix").also {
        it.writeText(text, Charsets.UTF_8)
        testFiles.add(it)
    }

    private fun selectDocument(button: String, file: File, action: String = Intent.ACTION_OPEN_DOCUMENT) {
        if(button=="导入 JSON")clickText("更多")
        // Both category and MIME type participate in ActivityMonitor's IntentFilter matching.
        val filter = IntentFilter(action).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
        val monitor = instrumentation.addMonitor(filter, result, true)
        try {
            clickText(button)
            await("$button 必须启动被监测的系统文档选择 Intent") { monitor.hits > 0 }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun all(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(all(view.getChildAt(i)))
    }

    private fun hasText(text: String, partial: Boolean = false): Boolean {
        fun matches(value: CharSequence?) = if (partial) value?.contains(text) == true else value?.toString() == text
        if (onUi { main?.let { all(it.window.decorView).filterIsInstance<TextView>().any { view -> view.isShown && matches(view.text) } } == true }) return true
        return withAccessibilityNode({ matches(it.text) }) { true } ?: false
    }

    private fun clickText(text: String) {
        await("找不到可操作控件：$text") {
            // This also scrolls a real ScrollView to controls below the fold before clicking.
            val clicked = onUi {
                val label = main?.let { all(it.window.decorView).filterIsInstance<TextView>().firstOrNull { v -> v.isShown && v.text.toString() == text } }
                if (label == null) false else {
                    label.requestRectangleOnScreen(Rect(0, 0, label.width, label.height), true)
                    var target: View? = label
                    while (target != null && !target.isClickable) target = target.parent as? View
                    target?.takeIf { it.isEnabled }?.performClick() == true
                }
            }
            if (clicked) true else withAccessibilityNode({ it.isVisibleToUser && it.text?.toString() == text }) { found ->
                if (!found.isEnabled) false
                else if (found.performAction(AccessibilityNodeInfo.ACTION_CLICK)) true
                else {
                    // AlertDialog list rows can expose non-clickable TextViews; clicking their
                    // ListView ancestor does not select the row. Tap the actual visible label.
                    val bounds = Rect()
                    found.getBoundsInScreen(bounds)
                    if (bounds.isEmpty) false else {
                        val time = SystemClock.uptimeMillis()
                        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                        val up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up) }
                        finally { down.recycle(); up.recycle() }
                        true
                    }
                }
            } ?: false
        }
        instrumentation.waitForIdleSync()
    }

    /** Inspect dialog/popup windows as well as the Activity; a toast need not be the active root. */
    private fun <T> withAccessibilityNode(match: (AccessibilityNodeInfo) -> Boolean, use: (AccessibilityNodeInfo) -> T): T? {
        val automation = instrumentation.uiAutomation
        val roots = automation.windows.sortedByDescending { it.layer }.mapNotNull { it.root }.toMutableList()
        if (roots.isEmpty()) automation.rootInActiveWindow?.let { roots.add(it) }
        fun visit(node: AccessibilityNodeInfo): T? {
            if (match(node)) return use(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        try { roots.forEach { root -> visit(root)?.let { return it } }; return null }
        finally { roots.forEach { it.recycle() } }
    }

    private fun <T> onUi(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(100)
        }
        val labels = mutableListOf<String>()
        withAccessibilityNode<Unit>({ node -> if (node.isVisibleToUser) node.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }; false }) { }
        fail("$message\n当前可见文本：${labels.distinct().take(50).joinToString(" | ")}")
    }

    private fun recoveryFiles() = File(instrumentation.targetContext.filesDir, "recovery").listFiles().orEmpty().filter { it.extension == "json" }

    override fun tearDown() {
        // ActivityInstrumentationTestCase2 clears non-final subclass fields in tearDown.
        val targetStore=store
        val snapshot=original
        val files=testFiles.toList()
        val existingRecovery=recoveryBefore
        val flags=accessibilityFlags
        val automation=instrumentation.uiAutomation
        val recoveryDirectory=File(instrumentation.targetContext.filesDir,"recovery")
        try { super.tearDown() } finally {
            try { ReaderPositions.awaitPendingWrites(); snapshot?.let { targetStore.restoreBackup(it) } } finally {
                files.forEach { it.delete() }
                recoveryDirectory.listFiles().orEmpty().filter { it.extension=="json" && it.name !in existingRecovery }.forEach { it.delete() }
                flags?.let { val service=automation.serviceInfo;service.flags=it;automation.serviceInfo=service }
            }
        }
    }
}
