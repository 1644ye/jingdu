package org.quietreader

import android.content.Context
import android.content.ContextWrapper
import android.test.AndroidTestCase
import com.google.gson.JsonParser
import org.quietreader.remote.*
import org.quietreader.core.RemoteLibrary
import org.quietreader.data.LibraryStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Suppress("DEPRECATION")
class DownloadQueueTest:AndroidTestCase(){
    private lateinit var folder:File
    private lateinit var isolated:Context
    private lateinit var store:LibraryStore
    override fun setUp(){super.setUp();folder=File(context.cacheDir,"queue-test-"+java.util.UUID.randomUUID()).apply{mkdirs()};isolated=object:ContextWrapper(context){override fun getNoBackupFilesDir()=folder};store=LibraryStore(context,"download-queue-test.db")}
    override fun tearDown(){store.close();context.deleteDatabase("download-queue-test.db");folder.walkBottomUp().forEach{it.delete()};super.tearDown()}
    private class Response(url:URL,private val status:Int,private val body:ByteArray,private val range:String?=null,private val failAfter:Int?=null):HttpURLConnection(url){
        override fun connect(){}
        override fun disconnect(){}
        override fun usingProxy()=false
        override fun getResponseCode()=status
        override fun getInputStream():java.io.InputStream=if(failAfter==null)body.inputStream()else object:java.io.InputStream(){var position=0;override fun read():Int{if(position>=failAfter)throw java.io.IOException("fixture interrupted");return body[position++].toInt() and 255}}
        override fun getHeaderField(name:String?)=if(name=="Content-Range")range else null
    }
    fun testRangeResumeAndServerIgnoringRange(){
        val target=File(folder,"partial").apply{writeText("abc")};var response:Response?=null
        GitHubApi(openDownload={url->Response(url,206,"def".toByteArray(),"bytes 3-5/6").also{response=it}}).downloadFile(GitHubConnection(),"book.json","fixed",target,{false},{})
        assertEquals("bytes=3-",response!!.getRequestProperty("Range"))
        assertEquals("abcdef",target.readText())
        GitHubApi(openDownload={Response(it,200,"fresh".toByteArray())}).downloadFile(GitHubConnection(),"book.json","fixed",target,{false},{})
        assertEquals("fresh",target.readText())
    }
    fun testPausedTaskPersistsAndCompletedBookIsImportedOnce(){
        val connection=RemotePreferences(context).connection(false)
        val queue=DownloadQueue(isolated,store)
        val entry=RemoteLibrary.Entry("book","book.json","任务书","作者","novel",emptyList(),500)
        queue.enqueue(connection,"repo","library","commit",listOf(entry));val id=queue.tasks().single().get("id").asString
        queue.action(id,"paused")
        val restored=DownloadQueue(isolated,store)
        assertEquals("paused",restored.tasks().single().get("state").asString)
        restored.action(id,"queued")
        val payload="""{"schemaVersion":1,"kind":"ebook-library","books":[{"id":"book","type":"novel","title":"任务书","chapters":[{"id":"chapter","title":"第一章","blocks":[{"id":"block","type":"paragraph","text":"持久下载正文"}]}]}]}""".toByteArray()
        val interrupted=DownloadQueue(isolated,store){GitHubApi(readJson={JsonParser.parseString("""{"tree":[{"path":"book.json","sha":"blob-version"}]}""")},openDownload={Response(it,200,payload,failAfter=payload.size/2)})}
        interrupted.process{false}
        assertEquals("queued",interrupted.tasks().single().get("state").asString)
        val partial=File(folder,"downloads/$id.part");val offset=partial.length().toInt()
        assertTrue("断网必须保留已写入的正文片段",offset in 1 until payload.size)
        val processor=DownloadQueue(isolated,store){GitHubApi(readJson={JsonParser.parseString("""{"tree":[{"path":"book.json","sha":"blob-version"}]}""")},openDownload={Response(it,206,payload.copyOfRange(offset,payload.size),"bytes $offset-${payload.lastIndex}/${payload.size}")})}
        processor.process{false}
        assertEquals("completed",processor.tasks().single().get("state").asString)
        assertEquals(1,store.listBooks().size)
        processor.process{false};assertEquals(1,store.listBooks().size)
    }
    fun testCancelSurvivesReopen(){
        val queue=DownloadQueue(isolated,store);queue.enqueue(RemotePreferences(context).connection(false),"repo","library","commit",listOf(RemoteLibrary.Entry("a","a.json","书","","novel",emptyList(),5)))
        queue.cancelAll()
        assertEquals("cancelled",DownloadQueue(isolated,store).tasks().single().get("state").asString)
    }
}
