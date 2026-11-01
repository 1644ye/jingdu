package org.quietreader

import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.quietreader.remote.RemoteActivity
import org.quietreader.remote.RemotePreferences

@Suppress("DEPRECATION")
class RemoteConnectionTest:ActivityInstrumentationTestCase2<RemoteActivity>(RemoteActivity::class.java){
    fun testSettingsPageRestoresAfterRecreate(){
        val original=activity
        instrumentation.runOnMainSync{all(original.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="书源账号与仓库设置"}.performClick()}
        val monitor=instrumentation.addMonitor(RemoteActivity::class.java.name,null,false)
        var restored:RemoteActivity?=null
        try{
            instrumentation.runOnMainSync{original.recreate()}
            restored=instrumentation.waitForMonitorWithTimeout(monitor,10000) as? RemoteActivity
            assertNotNull(restored);instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync{
                assertTrue(all(restored!!.window.decorView).filterIsInstance<EditText>().any{it.hint.toString()=="仓库所有者"})
                all(restored!!.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="<"}.performClick()
                assertTrue(all(restored!!.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="浏览书源目录"})
            }
        }finally{restored?.let{screen->instrumentation.runOnMainSync{screen.finish()}};instrumentation.removeMonitor(monitor)}
    }
    fun testUpButtonReturnsFromSettingsAndAscendsDirectory(){
        val screen=activity
        instrumentation.runOnMainSync{
            all(screen.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="书源账号与仓库设置"}.performClick()
            all(screen.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="<"}.performClick()
            assertTrue(all(screen.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="浏览书源目录"})
            screen.javaClass.getDeclaredField("catalog").apply{isAccessible=true}.set(screen,org.quietreader.core.RemoteLibrary.Catalog("test",emptyList()))
            val folder=screen.javaClass.getDeclaredField("folder").apply{isAccessible=true};folder.set(screen,"books/诗集/")
            screen.javaClass.getDeclaredMethod("browse").apply{isAccessible=true}.invoke(screen)
            all(screen.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="<"}.performClick()
            assertEquals("books/",folder.get(screen))
            all(screen.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="<"}.performClick()
            assertEquals("",folder.get(screen))
            all(screen.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="<"}.performClick()
            assertTrue(all(screen.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="浏览书源目录"})
        }
    }
    private fun all(view:View):List<View> = buildList{add(view);if(view is ViewGroup)for(index in 0 until view.childCount)addAll(all(view.getChildAt(index)))}
    fun testPendingRequestShowsFeedbackAndDoesNotSilentlyAcceptAnotherLogin(){
        val screen=activity
        val release=java.util.concurrent.CountDownLatch(1)
        val started=java.util.concurrent.CountDownLatch(1)
        val finished=java.util.concurrent.CountDownLatch(1)
        val method=screen.javaClass.declaredMethods.single{it.name=="run"&&it.parameterCount==3}.apply{isAccessible=true}
        val task:()->String={started.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS));"done"}
        val done:(String)->Unit={finished.countDown()}
        try{
            instrumentation.runOnMainSync{
                all(screen.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="书源账号与仓库设置"}.performClick()
                method.invoke(screen,task,"正在连接 GitHub，获取登录设备码…",done)
                val status=all(screen.window.decorView).filterIsInstance<TextView>().first{it.contentDescription=="连接任务状态"}
                assertEquals(View.VISIBLE,status.visibility)
                assertTrue(status.text.contains("正在连接 GitHub"))
                all(screen.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="登录 / 更换账号"}.performClick()
                assertTrue(status.text.contains("已有请求进行中"))
            }
            assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS))
        }finally{release.countDown()}
        assertTrue(finished.await(5,java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.runOnMainSync{assertEquals(View.GONE,all(screen.window.decorView).first{it.contentDescription=="连接任务状态"}.visibility)}
    }
    fun testTimeoutShowsReasonAndAllowsRetry(){
        val screen=activity
        val method=screen.javaClass.declaredMethods.single{it.name=="run"&&it.parameterCount==3}.apply{isAccessible=true}
        val fail:()->String={throw java.net.SocketTimeoutException()};val ignored:(String)->Unit={}
        instrumentation.runOnMainSync{method.invoke(screen,fail,"正在连接 GitHub…",ignored)}
        val end=System.currentTimeMillis()+5000
        var explained=false
        while(!explained&&System.currentTimeMillis()<end){
            instrumentation.runOnMainSync{explained=all(screen.window.decorView).filterIsInstance<TextView>().any{it.contentDescription=="连接任务状态"&&it.text.contains("超时")}}
            if(!explained)Thread.sleep(50)
        }
        assertTrue("超时必须显示可重试的原因",explained)
        val retried=java.util.concurrent.CountDownLatch(1);val retry:()->String={"ok"};val success:(String)->Unit={retried.countDown()}
        instrumentation.runOnMainSync{method.invoke(screen,retry,"重新连接 GitHub…",success)}
        assertTrue("失败后不能一直处于忙碌状态",retried.await(5,java.util.concurrent.TimeUnit.SECONDS))
    }
    fun testManualSourceFieldsAndCancelDoNotChangeConnection(){
        val before=RemotePreferences(instrumentation.targetContext).connection(false)
        val screen=activity
        instrumentation.runOnMainSync{all(screen.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="书源账号与仓库设置"}.performClick()}
        instrumentation.waitForIdleSync()
        val picture=instrumentation.uiAutomation.takeScreenshot()
        val directory=instrumentation.targetContext.getExternalFilesDir("visual")!!
        directory.mkdirs()
        java.io.File(directory,"github-source-settings.png").outputStream().use{picture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        picture.recycle()
        instrumentation.runOnMainSync{
            val fields=all(screen.window.decorView).filterIsInstance<EditText>()
            assertTrue(fields.any{it.hint.toString()=="仓库所有者"})
            assertTrue(fields.any{it.hint.toString()=="仓库名称"})
            fields.first{it.hint.toString()=="仓库所有者"}.setText("another-owner")
            fields.first{it.hint.toString()=="仓库名称"}.setText("another-library")
            all(screen.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="取消"}.performClick()
        }
        assertEquals(before,RemotePreferences(instrumentation.targetContext).connection(false))
    }
}
