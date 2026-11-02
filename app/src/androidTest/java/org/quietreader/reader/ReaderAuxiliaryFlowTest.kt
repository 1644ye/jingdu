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
        val document=JsonParser.parseString("""{"schemaVersion":1,"kind":"ebook-library","books":[{"id":"$id","type":"poetry","title":"辅助内容验收","poems":[{"id":"p1","title":"第一首","background":"思乡背景","stanzas":[{"id":"s1","lines":[{"id":"l1","original":"床前明月光。","pinyin":"chuáng qián míng yuè guāng","note":"床：卧具，另有不同释义。"}]}]},{"id":"p2","title":"跳过的一首","stanzas":[{"id":"s2","lines":[{"id":"l2","original":"低头思故乡。"}]}]},{"id":"p3","title":"阅读中的一首","stanzas":[{"id":"s3","lines":[{"id":"l3","original":"悠悠山水"}]}]}]}]}""").asJsonObject
        val poem=document.getAsJsonArray("books")[0].asJsonObject.getAsJsonArray("poems")[0].asJsonObject
        poem.addProperty("translation","月光引起了乡愁。");poem.addProperty("appreciation","借月色寄托思乡之情。")
        poem.getAsJsonArray("stanzas")[0].asJsonObject.getAsJsonArray("lines")[0].asJsonObject.addProperty("translation","明亮的月光照在床前。")
        store.importFile(document)
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
    fun testAllAuxiliaryKindsRenderInBothReadingModesAndAllDisplayModes(){
        val main=activity;await("阅读器加载"){all(main.window.decorView).any{it.contentDescription=="查看注释"}}
        val cases=listOf(Triple("pinyin","拼音","chuáng qián míng yuè guāng"),Triple("translation","译文","明亮的月光照在床前。"),Triple("note","注释","床：卧具，另有不同释义。"),Triple("background","创作背景","思乡背景"),Triple("poemTranslation","全诗译文","月光引起了乡愁。"),Triple("appreciation","赏析","借月色寄托思乡之情。"))
        for(readingMode in listOf("paged","scroll"))for((kind,label,content) in cases)for(displayMode in listOf("hidden","small","inline","link")){
            val value=ReadingSettings(mode=readingMode).originalOnly().withAuxiliaryMode(kind,displayMode)
            instrumentation.runOnMainSync{main.javaClass.getDeclaredMethod("changeSettings",ReadingSettings::class.java).apply{isAccessible=true}.invoke(main,value)}
            await("$readingMode/$kind/$displayMode 应正确保存并呈现"){
                val views=all(main.window.decorView).filterIsInstance<TextView>()
                store.settings(id)==value&&!busy(main)&&when(displayMode){
                    "hidden"->views.none{it.text.contains(content)||it.contentDescription=="查看$label"}
                    "link"->views.any{it.contentDescription=="查看$label"}&&views.none{it.text.contains(content)}
                    else->views.any{it.text.contains(content)&&kotlin.math.abs(it.textSize-(value.fontSize*(if(displayMode=="small").78f else 1f)*main.resources.displayMetrics.scaledDensity))<1f}
                }
            }
            if(displayMode=="link"){
                instrumentation.runOnMainSync{all(main.window.decorView).first{it.contentDescription=="查看$label"}.performClick()}
                await("$kind 弹窗正文应完整"){hasText(content)};tap("关闭")
            }
            if(readingMode=="paged"&&kind=="pinyin"&&displayMode in listOf("small","inline"))screenshot("pinyin-$displayMode")
        }
    }
    fun testMissingPinyinAndOtherAuxiliaryDataAreExplainedWithoutErasingPreference(){
        val main=activity;await("阅读器加载"){all(main.window.decorView).any{it.contentDescription=="查看注释"}}
        instrumentation.runOnMainSync{main.javaClass.getDeclaredMethod("navigateSection",Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(main,1,false)}
        await("切换到缺少辅助内容的篇章"){!busy(main)&&all(main.window.decorView).filterIsInstance<TextView>().any{it.text.toString()=="跳过的一首"}}
        val settings=ReadingSettings(pinyinDisplay="small",notesDisplay="link",backgroundDisplay="inline")
        instrumentation.runOnMainSync{main.javaClass.getDeclaredMethod("changeSettings",ReadingSettings::class.java).apply{isAccessible=true}.invoke(main,settings)}
        await("缺少字段须有明确反馈"){
            !busy(main)&&all(main.window.decorView).filterIsInstance<TextView>().any{it.text.contains("本篇未提供：拼音、逐行注释、创作背景")}
        }
        await("切换辅助内容不得跳回前一首"){store.position(id)?.sectionId=="p2"&&!busy(main)}
        assertEquals(settings,store.settings(id));screenshot("auxiliary-missing-content")
        instrumentation.runOnMainSync{all(main.window.decorView).filterIsInstance<Button>().first{it.text=="辅助"}.performClick()}
        await("设置页须显示拼音是否存在"){hasText("拼音 · 本篇未提供")}
        screenshot("auxiliary-missing-settings")
        await("设置页须显示注释是否存在"){if(hasText("逐行注释 · 本篇未提供"))true else{scrollDialog();false}}
        tap("完成")
    }
    fun testChangingAuxiliaryModeKeepsSecondAndLastShortPoem(){
        val main=activity;await("阅读器加载"){all(main.window.decorView).any{it.contentDescription=="查看注释"}}
        for(index in listOf(1,2)){
            instrumentation.runOnMainSync{main.javaClass.getDeclaredMethod("navigateSection",Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(main,index,false)}
            await("目标短诗加载"){!busy(main)&&store.position(id)?.sectionId=="p${index+1}"}
            for(mode in listOf("small","inline","link","hidden")){
                val settings=ReadingSettings(mode="scroll").originalOnly().withAuxiliaryMode("pinyin",mode)
                instrumentation.runOnMainSync{main.javaClass.getDeclaredMethod("changeSettings",ReadingSettings::class.java).apply{isAccessible=true}.invoke(main,settings)}
                await("切换 $mode 后应保持第 ${index+1} 首"){!busy(main)&&store.settings(id)==settings&&store.position(id)?.sectionId=="p${index+1}"}
                Thread.sleep(250);assertEquals("p${index+1}",store.position(id)?.sectionId)
            }
        }
    }
    private fun busy(main:ReaderActivity)=main.javaClass.getDeclaredField("busy").apply{isAccessible=true}.getBoolean(main)
    private fun scrollDialog(){
        val root=instrumentation.uiAutomation.rootInActiveWindow?:return
        try{fun scroll(node:AccessibilityNodeInfo):Boolean{if(node.className=="android.widget.ScrollView"&&node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;for(i in 0 until node.childCount){val child=node.getChild(i)?:continue;try{if(scroll(child))return true}finally{child.recycle()}};return false};scroll(root)}finally{root.recycle()}
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
