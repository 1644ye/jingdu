package org.quietreader

import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AbsListView
import android.widget.Button
import android.widget.GridView
import android.widget.ListView
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.quietreader.data.BookSummary
import org.quietreader.reader.ReaderPositions
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ShelfLayoutTest:ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java){
    private val store get()=(instrumentation.targetContext.applicationContext as ReaderApp).store
    private val id="shelf-layout-${UUID.randomUUID()}"
    private lateinit var snapshot:JsonObject
    private lateinit var preferences:SharedPreferences
    private lateinit var updaterPreferences:SharedPreferences
    private var savedPreferences=emptyMap<String,Any?>()
    private var savedUpdaterPreferences=emptyMap<String,Any?>()
    private var firstShelf=0L
    private var secondShelf=0L
    override fun setUp(){
        super.setUp();ReaderPositions.awaitPendingWrites();snapshot=store.backup()
        val context=instrumentation.targetContext
        preferences=context.getSharedPreferences("MainActivity",0);savedPreferences=preferences.all.toMap();preferences.edit().putLong("currentShelf",-1).putBoolean("grid",true).commit()
        updaterPreferences=context.getSharedPreferences("app-updates",0);savedUpdaterPreferences=updaterPreferences.all.toMap();updaterPreferences.edit().putBoolean("auto-check",false).commit()
        val book=JsonParser.parseString("""{"schemaVersion":1,"kind":"ebook-library","books":[{"id":"$id","type":"novel","title":"自定义书架测试书","chapters":[{"id":"chapter","title":"章节","blocks":[{"id":"block","type":"paragraph","text":"书架切换验证正文"}]}]}]}""").asJsonObject
        store.importFile(book);store.renameHomeShelf("我的首页")
        firstShelf=store.createGroup("测试诗集");secondShelf=store.createGroup("测试小说")
        store.setMembership(listOf(id),firstShelf,true);store.setHomeVisible(listOf(id),false)
        setActivityIntent(Intent(Intent.ACTION_MAIN))
    }
    fun testBottomShelfSelectionHomeVisibilityAndManagement(){
        val main=activity;ready(main)
        assertFalse(onUi{books(main).contains(id)})
        click(main,"测试诗集");await("自定义书架应展示从首页隐藏的书籍"){onUi{books(main).contains(id)&&selected(main,"测试诗集")}}
        click(main,"测试小说");await("空书架应能正常选择"){onUi{books(main).isEmpty()&&selected(main,"测试小说")}}
        click(main,"我的首页");await("返回首页"){onUi{selected(main,"我的首页")}}
        navigate(main,"manage");await("管理页能找到从首页隐藏的书籍"){onUi{books(main).contains(id)}}
        navigate(main,"shelf");ready(main)
        store.setHomeVisible(listOf(id),true);navigate(main,"shelf")
        await("恢复首页显示后应重新出现"){onUi{books(main).contains(id)}}
        screenshot("shelves-home")
    }
    fun testRapidShelfSwitchKeepsPageListAndAdapterInstances(){
        val main=activity;ready(main)
        val list=onUi{all(main.window.decorView).filterIsInstance<AbsListView>().first()}
        val adapter=onUi{list.adapter}
        val area=onUi{all(main.window.decorView).first{it.contentDescription=="书架切换区域"}}
        val tabs=onUi{all(main.window.decorView).first{it.contentDescription=="底部书架选择"}}
        onUi{repeat(12){index->all(main.window.decorView).filterIsInstance<Button>().first{it.text==if(index%2==0)"测试诗集" else "测试小说"}.performClick()}}
        await("连续切换后动画应稳定结束"){onUi{area.alpha==1f&&area.translationX==0f&&selected(main,"测试小说")}}
        onUi{assertSame(list,all(main.window.decorView).filterIsInstance<AbsListView>().first());assertSame(adapter,list.adapter);assertSame(tabs,all(main.window.decorView).first{it.contentDescription=="底部书架选择"})}
        click(main,"测试诗集");await("复用列表仍应展示对应书籍"){onUi{books(main).contains(id)}}
    }
    fun testJoinShelfOffersNewShelfAfterExistingShelves(){
        val main=activity;ready(main)
        onUi{main.javaClass.getDeclaredMethod("chooseGroup",List::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(main,listOf(id),true)}
        tapDialog("＋ 新建书架并加入")
        await("新建书架名称可编辑"){
            val root=instrumentation.uiAutomation.rootInActiveWindow?:return@await false
            try{fun fill(node:AccessibilityNodeInfo):Boolean{if(node.className=="android.widget.EditText")return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"新增书架验证")});for(i in 0 until node.childCount){val child=node.getChild(i)?:continue;try{if(fill(child))return true}finally{child.recycle()}};return false};fill(root)}finally{root.recycle()}
        }
        tapDialog("保存")
        await("已有书架时仍能新增并加入书籍"){store.groups().firstOrNull{it.name=="新增书架验证"}?.let{it.id in store.groupIds(id)}?:false}
        ready(main);assertTrue(onUi{all(main.window.decorView).any{it.contentDescription=="新建书架"}})
    }
    fun testHorizontalGesturesSwitchShelvesAndVerticalDragKeepsShelf(){
        val main=activity;ready(main)
        // Put fixture shelves immediately after Home, regardless of any existing user shelves.
        repeat(store.groups().size){store.moveShelf(firstShelf,-1)}
        repeat(store.groups().size){store.moveShelf(secondShelf,-1)};store.moveShelf(firstShelf,-1)
        navigate(main,"shelf");ready(main)
        drag(main,horizontal=false,forward=true);assertTrue(onUi{selected(main,"我的首页")})
        drag(main,horizontal=true,forward=true);await("左滑必须切换下一书架"){onUi{selected(main,"测试诗集")&&books(main).contains(id)}}
        screenshot("shelves-custom")
        drag(main,horizontal=true,forward=true);await("再次左滑切换第二个书架"){onUi{selected(main,"测试小说")}}
        drag(main,horizontal=true,forward=false);await("右滑应返回上一个书架"){onUi{selected(main,"测试诗集")}}
    }
    fun testDisplayModeLivesInMoreAndSyncHasVisibleFailureFeedback(){
        val main=activity;ready(main)
        assertFalse(onUi{all(main.window.decorView).filterIsInstance<Button>().any{it.text=="列表显示"||it.text=="网格显示"}})
        click(main,"更多");tapDialog("列表显示")
        await("更多里的列表显示切换实际列表"){onUi{all(main.window.decorView).any{it is ListView}&&all(main.window.decorView).none{it is GridView}}}
        val connections=instrumentation.targetContext.getSharedPreferences("github-connections",0)
        val had=connections.contains("state-valid");val previous=connections.getBoolean("state-valid",false)
        try{
            connections.edit().putBoolean("state-valid",false).commit();click(main,"立即同步")
            await("未配置同步必须明确提示设置位置"){instrumentation.uiAutomation.rootInActiveWindow?.let{root->try{root.findAccessibilityNodeInfosByText("请先在进度账号与仓库设置中连接并保存").isNotEmpty()}finally{root.recycle()}}?:false}
            tapDialog("知道了");ready(main);assertTrue(onUi{all(main.window.decorView).filterIsInstance<Button>().first{it.text=="立即同步"}.isEnabled})
        }finally{connections.edit().apply{if(had)putBoolean("state-valid",previous)else remove("state-valid")}.commit()}
        click(main,"更多");tapDialog("软件更新")
        await("软件更新页面可从更多打开"){instrumentation.uiAutomation.rootInActiveWindow?.let{root->try{root.findAccessibilityNodeInfosByText("自动检查更新").isNotEmpty()}finally{root.recycle()}}?:false}
        screenshot("software-update")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    }
    private fun ready(main:MainActivity)=await("等待书架加载"){onUi{all(main.window.decorView).any{it.contentDescription=="底部书架选择"}&&all(main.window.decorView).any{it is AbsListView&&it.adapter!=null}}}
    private fun selected(main:MainActivity,name:String)=all(main.window.decorView).any{it.contentDescription=="书架：$name"&&it.isSelected}
    private fun books(main:MainActivity):List<String> = all(main.window.decorView).filterIsInstance<AbsListView>().flatMap{list->(0 until (list.adapter?.count?:0)).mapNotNull{(list.adapter?.getItem(it) as? BookSummary)?.id}}
    private fun click(main:MainActivity,label:String){onUi{all(main.window.decorView).filterIsInstance<Button>().first{it.text.toString()==label}.performClick()}}
    private fun navigate(main:MainActivity,screen:String){onUi{main.javaClass.getDeclaredMethod("navigate",String::class.java,String::class.java).apply{isAccessible=true}.invoke(main,screen,null)}}
    private fun drag(main:MainActivity,horizontal:Boolean,forward:Boolean){
        val coordinates=onUi{val area=all(main.window.decorView).first{it is ShelfSwipeLayout};val location=IntArray(2);area.getLocationOnScreen(location);floatArrayOf(location[0]+area.width*.8f,location[1]+area.height*.5f,area.width*.6f,area.height*.25f)}
        val x=if(horizontal&&!forward)coordinates[0]-coordinates[2]else coordinates[0];val y=coordinates[1];val dx=if(horizontal)coordinates[2]*(if(forward)-1 else 1)else 0f;val dy=if(horizontal)0f else coordinates[3]
        val start=SystemClock.uptimeMillis()
        for(step in 0..10){val action=when(step){0->MotionEvent.ACTION_DOWN;10->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE};val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x+dx*step/10,y+dy*step/10,0);try{instrumentation.sendPointerSync(event)}finally{event.recycle()};Thread.sleep(16)}
        instrumentation.waitForIdleSync()
    }
    private fun tapDialog(label:String){await("弹窗按钮 $label 应可操作"){
        val root=instrumentation.uiAutomation.rootInActiveWindow?:return@await false
        try{root.findAccessibilityNodeInfosByText(label).any{node->try{if(node.text?.toString()!=label)false else {var target=node;while(!target.isClickable&&target.parent!=null)target=target.parent;target.performAction(AccessibilityNodeInfo.ACTION_CLICK)}}finally{node.recycle()}}}finally{root.recycle()}
    }}
    private fun screenshot(name:String){Thread.sleep(300);val file=File(instrumentation.targetContext.getExternalFilesDir(null),"visual/$name.png");file.parentFile?.mkdirs();instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
    private fun all(view:View):List<View> = buildList{add(view);if(view is ViewGroup)for(i in 0 until view.childCount)addAll(all(view.getChildAt(i)))}
    private fun <T> onUi(block:()->T):T{val result=AtomicReference<T>();instrumentation.runOnMainSync{result.set(block())};return result.get()}
    private fun await(message:String,condition:()->Boolean){val end=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<end){instrumentation.waitForIdleSync();if(condition())return;Thread.sleep(100)};fail(message)}
    private fun restore(preferences:SharedPreferences,values:Map<String,Any?>){preferences.edit().clear().apply{values.forEach{(key,value)->when(value){is String->putString(key,value);is Boolean->putBoolean(key,value);is Long->putLong(key,value);is Int->putInt(key,value);is Float->putFloat(key,value)}}}.commit()}
    override fun tearDown(){val targetStore=store;val saved=snapshot;val prefs=preferences;val savedPrefs=savedPreferences;val updatePrefs=updaterPreferences;val savedUpdates=savedUpdaterPreferences
        try{super.tearDown()}finally{ReaderPositions.awaitPendingWrites();targetStore.restoreBackup(saved);restore(prefs,savedPrefs);restore(updatePrefs,savedUpdates)}
    }
}
