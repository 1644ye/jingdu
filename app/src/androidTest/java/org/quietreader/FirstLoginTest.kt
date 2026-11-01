package org.quietreader

import android.content.SharedPreferences
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.quietreader.remote.RemoteActivity
import java.io.File

@Suppress("DEPRECATION")
class FirstLoginTest:ActivityInstrumentationTestCase2<RemoteActivity>(RemoteActivity::class.java){
    private lateinit var preferences:SharedPreferences
    private var saved=emptyMap<String,Any?>()
    private var cached:ByteArray?=null
    private lateinit var cache:File
    override fun setUp(){super.setUp();val context=instrumentation.targetContext;preferences=context.getSharedPreferences("github-connections",0);saved=preferences.all.toMap();preferences.edit().clear().commit();cache=File(context.noBackupFilesDir,"github-catalog.json");cached=cache.takeIf{it.exists()}?.readBytes();cache.delete()}
    fun testFreshBrowseGoesToOwnAccountLoginWithoutPersonalDefaults(){
        val screen=activity
        instrumentation.runOnMainSync{
            all(screen.window.decorView).filterIsInstance<Button>().first{it.text=="浏览书源目录"}.performClick()
            val fields=all(screen.window.decorView).filterIsInstance<EditText>()
            assertEquals("",fields.first{it.hint=="仓库所有者"}.text.toString());assertEquals("",fields.first{it.hint=="仓库名称"}.text.toString())
            assertTrue(all(screen.window.decorView).filterIsInstance<TextView>().any{it.text.contains("请先登录自己的 GitHub")})
            val busy=screen.javaClass.getDeclaredField("busy").apply{isAccessible=true}.getBoolean(screen);assertFalse("Must not start a remote request",busy)
        }
    }
    fun testSavingRepositoryRequiresOwnLoginEvenForPublicSources(){
        val screen=activity
        instrumentation.runOnMainSync{
            all(screen.window.decorView).filterIsInstance<Button>().first{it.text=="书源账号与仓库设置"}.performClick()
            val fields=all(screen.window.decorView).filterIsInstance<EditText>()
            fields.first{it.hint=="仓库所有者"}.setText("example-reader");fields.first{it.hint=="仓库名称"}.setText("books")
            all(screen.window.decorView).filterIsInstance<Button>().first{it.text=="连接并保存"}.performClick()
        }
        val end=System.currentTimeMillis()+5000;var explained=false
        while(!explained&&System.currentTimeMillis()<end){instrumentation.runOnMainSync{explained=all(screen.window.decorView).filterIsInstance<TextView>().any{it.contentDescription=="连接任务状态"&&it.text.contains("请先登录自己的 GitHub")}};if(!explained)Thread.sleep(50)}
        assertTrue("No anonymous fallback",explained);assertFalse(preferences.getBoolean("library-valid",false))
    }
    private fun all(view:View):List<View> = buildList{add(view);if(view is ViewGroup)for(i in 0 until view.childCount)addAll(all(view.getChildAt(i)))}
    override fun tearDown(){val prefs=preferences;val values=saved;val file=cache;val bytes=cached
        try{super.tearDown()}finally{prefs.edit().clear().apply{values.forEach{(key,value)->when(value){is String->putString(key,value);is Boolean->putBoolean(key,value);is Int->putInt(key,value);is Long->putLong(key,value)}}}.commit();if(bytes!=null)file.writeBytes(bytes)else file.delete()}
    }
}
