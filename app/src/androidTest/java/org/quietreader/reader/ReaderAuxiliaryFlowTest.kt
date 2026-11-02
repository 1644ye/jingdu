package org.quietreader.reader

import android.content.Intent
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import com.google.gson.JsonParser
import org.quietreader.ReaderApp
import org.quietreader.core.Anchor
import org.quietreader.core.ReadingSettings
import java.io.File

@Suppress("DEPRECATION")
class ReaderAuxiliaryFlowTest:ActivityInstrumentationTestCase2<ReaderActivity>(ReaderActivity::class.java){
    private val id="reader-auxiliary-fixture"
    private val store get()=(instrumentation.targetContext.applicationContext as ReaderApp).store
    override fun setUp(){
        super.setUp();ReaderPositions.awaitPendingWrites();store.deleteBooks(listOf(id))
        store.importFile(JsonParser.parseString("""{"schemaVersion":1,"kind":"ebook-library","books":[{"id":"$id","type":"poetry","title":"辅助内容验收","poems":[{"id":"p1","title":"第一首","background":"思乡背景","stanzas":[{"id":"s1","lines":[{"id":"l1","original":"床前明月光。","pinyin":"chuáng qián míng yuè guāng","note":"床：卧具，另有不同释义。"}]}]},{"id":"p2","title":"跳过的一首","stanzas":[{"id":"s2","lines":[{"id":"l2","original":"低头思故乡。"}]}]},{"id":"p3","title":"阅读中的一首","stanzas":[{"id":"s3","lines":[{"id":"l3","original":"悠悠山水"}]}]}]}]}""").asJsonObject)
        store.saveSettings(ReadingSettings(mode="paged",notesDisplay="link",backgroundDisplay="hidden"),id)
        store.savePosition(Anchor(id,"p3","l3"),.7f)
        setActivityIntent(Intent(Intent.ACTION_MAIN).putExtra("bookId",id).putExtra("sectionId","p1"))
    }
    fun testPopupAndImmediateDisplaySettingsPreserveOriginalPosition(){
        val main=activity;await("注释链接出现"){all(main.window.decorView).any{it.contentDescription=="查看注释"}}
        instrumentation.runOnMainSync{all(main.window.decorView).first{it.contentDescription=="查看注释"}.performClick()}
        await("弹窗显示真实注释"){hasText("床：卧具，另有不同释义。")};screenshot("auxiliary-popup");tap("关闭")
        await("阅读位置保存"){store.position(id)?.sectionId=="p1"}
        val before=store.position(id)
        instrumentation.runOnMainSync{all(main.window.decorView).filterIsInstance<Button>().first{it.text=="辅助"}.performClick()}
        tapDescription("拼音 显示形式");tap("正文大小");screenshot("auxiliary-settings");tap("完成")
        await("显示方式立即保存并显示拼音"){store.settings(id).pinyinDisplay=="inline"&&all(main.window.decorView).filterIsInstance<TextView>().any{it.text.contains("chuáng qián")}}
        assertEquals(before?.sectionId,store.position(id)?.sectionId);assertEquals("l1",store.position(id)?.contentId)
    }
    fun testContentsDistinguishCompletedCurrentAndSkippedPoems(){
        val main=activity;await("短诗完整显示后记为已读"){store.sectionReadStates(id)["p1"]=="read"}
        instrumentation.runOnMainSync{all(main.window.decorView).filterIsInstance<Button>().first{it.text=="目录"}.performClick()}
        await("目录显示已读与在读"){hasText("已读 · 第一首")&&hasText("阅读中 · 阅读中的一首")&&hasText("跳过的一首")}
        screenshot("contents-read-state")
        assertFalse(store.sectionReadStates(id).containsKey("p2"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    }
    private fun hasText(value:String):Boolean{val root=instrumentation.uiAutomation.rootInActiveWindow?:return false;try{return root.findAccessibilityNodeInfosByText(value).any{node->try{node.text?.toString()==value}finally{node.recycle()}}}finally{root.recycle()}}
    private fun tap(value:String)=tapNode{it.text?.toString()==value}
    private fun tapDescription(value:String)=tapNode{it.contentDescription?.toString()==value}
    private fun tapNode(matches:(AccessibilityNodeInfo)->Boolean){await("可点击目标出现"){
        val root=instrumentation.uiAutomation.rootInActiveWindow?:return@await false
        try{fun visit(node:AccessibilityNodeInfo):Boolean{if(matches(node)){var target=node;while(!target.isClickable&&target.parent!=null)target=target.parent;return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)};for(i in 0 until node.childCount){val child=node.getChild(i)?:continue;try{if(visit(child))return true}finally{child.recycle()}};return false};visit(root)}finally{root.recycle()}
    }}
    private fun all(view:View):List<View> = buildList{add(view);if(view is ViewGroup)for(i in 0 until view.childCount)addAll(all(view.getChildAt(i)))}
    private fun screenshot(name:String){instrumentation.waitForIdleSync();Thread.sleep(300);val file=File(instrumentation.targetContext.getExternalFilesDir(null),"visual/$name.png");file.parentFile?.mkdirs();instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
    private fun await(message:String,condition:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){instrumentation.waitForIdleSync();if(condition())return;Thread.sleep(100)};fail(message)}
    override fun tearDown(){try{super.tearDown()}finally{ReaderPositions.awaitPendingWrites();store.deleteBooks(listOf(id))}}
}
