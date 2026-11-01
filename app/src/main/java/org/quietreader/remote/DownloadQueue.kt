package org.quietreader.remote

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.quietreader.core.Protocol
import org.quietreader.core.RemoteLibrary
import org.quietreader.data.LibraryStore
import java.io.File
import java.util.UUID

class DownloadStopped:java.io.IOException("下载已暂停或取消")

/** Disk-backed tasks contain source locations, never credentials; immutable commits bind partial files. */
class DownloadQueue(private val context:Context,private val store:LibraryStore,private val apiFactory:(String)->GitHubApi={GitHubApi(it)}){
    private val gson=Gson()
    private val directory=File(context.noBackupFilesDir,"downloads").apply{mkdirs()}
    private val journal=AtomicFile(File(directory,"tasks.json"))
    private var running=false
    @Synchronized fun tasks():List<JsonObject> = if(journal.baseFile.exists())journal.openRead().bufferedReader().use{JsonParser.parseReader(it).asJsonArray.map{value->value.asJsonObject}}else emptyList()
    private fun save(tasks:List<JsonObject>){val output=journal.startWrite();try{output.write(gson.toJson(tasks).toByteArray(Charsets.UTF_8));journal.finishWrite(output)}catch(e:Exception){journal.failWrite(output);throw e}}
    @Synchronized private fun change(id:String,action:(JsonObject)->Unit){val rows=tasks();rows.firstOrNull{it.get("id").asString==id}?.let(action);save(rows)}
    @Synchronized fun enqueue(connection:GitHubConnection,repository:String,library:String,commit:String,entries:List<RemoteLibrary.Entry>){
        val rows=tasks().toMutableList();val account=RemotePreferences(context).user(false)
        entries.forEach{entry->val identity="$repository/$library/${entry.id}";if(rows.none{it.get("identity").asString==identity&&it.get("commit").asString==commit&&it.get("state").asString in listOf("queued","downloading","paused")})rows.add(JsonObject().apply{addProperty("id",UUID.randomUUID().toString());addProperty("identity",identity);addProperty("repositoryId",repository);addProperty("account",account);add("connection",gson.toJsonTree(connection));add("entry",gson.toJsonTree(entry));addProperty("commit",commit);addProperty("state","queued");addProperty("bytes",0)})}
        save(rows);schedule()
    }
    @Synchronized fun action(id:String,action:String){require(action in listOf("paused","cancelled","queued"));change(id){row->require(row.get("state").asString!="completed"){"已完成任务无需重试"};row.addProperty("state",action);row.remove("error")};if(action=="queued")schedule()}
    @Synchronized fun cancelAll(){val rows=tasks();rows.filter{it.get("state").asString in listOf("queued","downloading","paused","failed")}.forEach{it.addProperty("state","cancelled")};save(rows)}
    @Synchronized fun recover(){val rows=tasks();rows.filter{it.get("state").asString=="downloading"}.forEach{it.addProperty("state","queued")};save(rows);if(rows.any{it.get("state").asString=="queued"})schedule()}
    fun schedule(){context.getSystemService(android.app.job.JobScheduler::class.java).schedule(android.app.job.JobInfo.Builder(7144,android.content.ComponentName(context,DownloadJob::class.java)).setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(500).build())}
    @Synchronized fun hasQueued()=tasks().any{it.get("state").asString=="queued"}
    fun process(stop:()->Boolean){
        synchronized(this){if(running)return;running=true}
        try{while(!stop()){
            val task=synchronized(this){tasks().firstOrNull{it.get("state").asString=="queued"}?.also{candidate->change(candidate.get("id").asString){it.addProperty("state","downloading")}}}?:break
            val id=task.get("id").asString;val partial=File(directory,"$id.part")
            fun interrupted()=stop()||synchronized(this){tasks().firstOrNull{it.get("id").asString==id}?.get("state")?.asString!="downloading"}
            try{
                val preferences=RemotePreferences(context);val connection=gson.fromJson(task.get("connection"),GitHubConnection::class.java)
                require(preferences.connection(false)==connection&&preferences.user(false)==task.get("account").asString){"书源账号或仓库已更换，请重新选择下载"}
                val api=apiFactory(preferences.requireLogin(false));val entry=gson.fromJson(task.get("entry"),RemoteLibrary.Entry::class.java);val commit=task.get("commit").asString
                val blob=api.fileVersion(connection,connection.path(entry.path),commit)
                val existing=store.remoteSources().firstOrNull{it.second.get("identity").asString==task.get("identity").asString}
                if(existing?.second?.get("revision")?.asString!=blob){
                    var checkpoint=0L
                    api.downloadFile(connection,connection.path(entry.path),commit,partial,::interrupted){bytes->val now=android.os.SystemClock.elapsedRealtime();if(now-checkpoint>1000){checkpoint=now;change(id){it.addProperty("bytes",bytes)}}}
                    if(interrupted())throw DownloadStopped()
                    val text=java.io.InputStreamReader(partial.inputStream(),Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)).use{it.readText()}
                    val parsed=Protocol.parse(text,entry.path);require(parsed.valid){parsed.errors.joinToString("\n"){it.message}}
                    val books=parsed.document!!.getAsJsonArray("books");require(books.size()==1&&books[0].asJsonObject.get("id").asString==entry.id){"正文ID与索引不符"}
                    val book=books[0].asJsonObject
                    synchronized(this){if(interrupted())throw DownloadStopped();store.importRemote(book,JsonObject().apply{addProperty("identity",task.get("identity").asString);addProperty("repositoryId",task.get("repositoryId").asString);addProperty("path",entry.path);addProperty("revision",blob);addProperty("commit",commit);add("tags",book.getAsJsonArray("tags")?:JsonArray())});change(id){it.addProperty("state","completed");it.addProperty("bytes",partial.length());it.remove("error")}}
                }else change(id){it.addProperty("state","completed");it.addProperty("note","正文未变化")}
                partial.delete()
            }catch(e:DownloadStopped){change(id){if(it.get("state").asString=="downloading")it.addProperty("state","queued")}}
            catch(e:Exception){change(id){if(it.get("state").asString=="downloading"){it.addProperty("state",if(e is java.io.IOException)"queued"else"failed");it.addProperty("error",e.message?:"下载失败");it.addProperty("bytes",partial.length())}};if(e is java.io.IOException)break}
        }}finally{synchronized(this){running=false}}
    }
}
