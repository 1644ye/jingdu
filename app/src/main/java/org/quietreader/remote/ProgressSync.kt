package org.quietreader.remote

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.quietreader.core.Anchor
import org.quietreader.core.ProgressVersions
import org.quietreader.data.LibraryStore
import java.io.File
import java.util.UUID

class ProgressSync(private val context:Context,private val store:LibraryStore) {
    private val gson=Gson()
    private val prefs=context.getSharedPreferences("progress-device",Context.MODE_PRIVATE)
    private val device=prefs.getString("id",null)?:UUID.randomUUID().toString().also{prefs.edit().putString("id",it).commit()}
    private val connections=RemotePreferences(context)
    private fun journal():AtomicFile {
        val connection=connections.connection(true)
        return AtomicFile(File(context.noBackupFilesDir,"progress-"+UUID.nameUUIDFromBytes((connections.user(true)+"/"+connection.owner+"/"+connection.repo+"/"+connection.branch).toByteArray())+".json"))
    }
    private fun read():JsonObject {val file=journal();return if(file.baseFile.exists())file.openRead().bufferedReader().use{JsonParser.parseReader(it).asJsonObject}else JsonObject()}
    private fun write(value:JsonObject){val file=journal();val output=file.startWrite();try{output.write(value.toString().toByteArray());file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}}
    private fun vector(value:JsonObject):Map<String,Long> = value.getAsJsonObject("version").entrySet().associate{it.key to it.value.asLong}.also{require(it.size<=256&&it.values.all{n->n>=0}){"无效同步版本"}}
    private fun record(id:String,identity:String,version:Map<String,Long>,revision:String):JsonObject? {
        val anchor=store.position(id)?:return null
        val book=store.listBooks().firstOrNull{it.id==id}?:return null
        return JsonObject().apply{addProperty("identity",identity);addProperty("device",device);addProperty("title",book.title);addProperty("updatedAt",System.currentTimeMillis());addProperty("revision",revision);addProperty("progress",book.progress);addProperty("status",book.status);add("anchor",gson.toJsonTree(anchor.copy(bookId=identity)));add("version",gson.toJsonTree(version))}
    }
    @Synchronized fun sync(attempt:Int=0):String {
        require(connections.validated(true)){"请先在进度账号与仓库设置中连接并保存"}
        require(!prefs.getBoolean("paused",false)){"恢复备份后同步已暂停，请确认后重新启用"}
        val api=GitHubApi(connections.token(true).also{require(it.isNotEmpty()){"请先登录进度同步账号"}})
        val connection=connections.connection(true);val state=read();val local=state.getAsJsonObject("books")?:JsonObject().also{state.add("books",it)}
        val checkpoints=state.getAsJsonObject("checkpoints")?:JsonObject().also{state.add("checkpoints",it)}
        val sources=store.remoteSources().toMap()
        // Capture local changes against previously observed versions before fetching remote changes.
        sources.forEach{(id,source)->val identity=source.get("identity").asString;val prior=local.getAsJsonObject(identity);val position=store.position(id)
            if(position!=null){val anchor=gson.toJsonTree(position.copy(bookId=identity));val book=store.listBooks().first{it.id==id};val checkpoint=JsonObject().apply{add("anchor",anchor);addProperty("status",book.status)};if(prior==null||checkpoints.get(identity)!=checkpoint){record(id,identity,ProgressVersions.advance(device,listOfNotNull(prior?.let{vector(it)})),source.get("revision").asString)?.let{local.add(identity,it);checkpoints.add(identity,checkpoint)}}}
        }
        write(state)
        val resolvedRevision=api.revisionOrNull(connection)
        val revision=resolvedRevision?:connection.branch.ifBlank{api.get(connection.prefix()).asJsonObject.get("default_branch").asString}
        val directory=if(resolvedRevision==null)JsonArray()else try{JsonParser.parseString(api.file(connection,"progress",revision)).asJsonArray}catch(e:GitHubApi.Failure){if(e.status==404)JsonArray()else throw e}
        require(directory.size()<=200){"同步设备数量超过限制"}
        val candidates=mutableMapOf<String,MutableList<JsonObject>>()
        local.entrySet().forEach{candidates.getOrPut(it.key){mutableListOf()}.add(it.value.asJsonObject)}
        for(entry in directory){val item=entry.asJsonObject;if(item.get("type").asString!="file"||!item.get("name").asString.endsWith(".json"))continue
            val remote=JsonParser.parseString(api.file(connection,item.get("path").asString,revision,true)).asJsonObject
            require(remote.get("schemaVersion")?.asInt==1){"同步文件版本无效"}
            remote.getAsJsonObject("books").entrySet().forEach{(identity,element)->val value=element.asJsonObject;require(value.get("identity").asString==identity);vector(value);candidates.getOrPut(identity){mutableListOf()}.add(value)}
        }
        val conflicts=JsonObject()
        candidates.forEach{(identity,entries)->
            val maxima=entries.filter{candidate->entries.none{other->ProgressVersions.compare(vector(candidate),vector(other))==ProgressVersions.Relation.BEFORE}}.distinctBy{it.get("version").toString()}
            if(maxima.size>1){conflicts.add(identity,JsonArray().apply{maxima.forEach{add(it)}})}else{
                val winner=maxima.single();val source=sources.entries.firstOrNull{it.value.get("identity").asString==identity}
                if(source!=null&&(local.get(identity)!=winner||store.position(source.key)==null)){
                    if((context.applicationContext as? org.quietreader.ReaderApp)?.readingActive==true){conflicts.add(identity,JsonArray().apply{add(winner)});return@forEach}
                    val original=gson.fromJson(winner.get("anchor"),Anchor::class.java).copy(bookId=source.key)
                    val anchor=if(source.value.get("revision").asString==winner.get("revision").asString)original else store.locateRemote(original)
                    if(anchor==null){conflicts.add(identity,JsonArray().apply{add(winner)});return@forEach}
                    store.applyRemoteProgress(anchor,winner.get("progress").asFloat,winner.get("status").asString)
                    checkpoints.add(identity,JsonObject().apply{add("anchor",gson.toJsonTree(store.position(source.key)!!.copy(bookId=identity)));addProperty("status",winner.get("status").asString)})
                }
                local.add(identity,winner)
            }
        }
        state.add("conflicts",conflicts);write(state)
        val path="progress/$device.json"
        val existing=if(resolvedRevision==null)null else try{JsonParser.parseString(api.file(connection,path,revision)).asJsonObject.get("sha").asString}catch(e:GitHubApi.Failure){if(e.status==404)null else throw e}
        val document=JsonObject().apply{addProperty("schemaVersion",1);add("books",local)}
        try{api.put(connection,path,document.toString(),existing)}catch(e:GitHubApi.Failure){if(e.status in listOf(409,422)&&attempt<1)return sync(attempt+1);throw e}
        prefs.edit().putLong("lastSuccess",System.currentTimeMillis()).commit()
        state.add("uploaded",local.deepCopy());write(state)
        return "同步完成，${conflicts.size()} 项需要选择位置"
    }
    @Synchronized fun conflicts():JsonObject=read().getAsJsonObject("conflicts")?:JsonObject()
    @Synchronized fun pendingCount():Int {
        val state=read();val books=state.getAsJsonObject("books")?:JsonObject();val uploaded=state.getAsJsonObject("uploaded")?:JsonObject();val checkpoints=state.getAsJsonObject("checkpoints")?:JsonObject()
        return store.remoteSources().count{(id,source)->val identity=source.get("identity").asString;val record=books.getAsJsonObject(identity);val position=store.position(id);position!=null&&(record==null||record!=uploaded.get(identity)||checkpoints.getAsJsonObject(identity)?.get("anchor")!=gson.toJsonTree(position.copy(bookId=identity)))}
    }
    @Synchronized fun keepLocal(identity:String){
        val state=read();val choices=state.getAsJsonObject("conflicts").getAsJsonArray(identity)
        val source=store.remoteSources().firstOrNull{it.second.get("identity").asString==identity}?:error("请先下载此书并在本机选择阅读位置")
        val observed=choices.map{vector(it.asJsonObject)}+listOfNotNull(state.getAsJsonObject("books").getAsJsonObject(identity)?.let{vector(it)})
        val chosen=record(source.first,identity,ProgressVersions.advance(device,observed),source.second.get("revision").asString)?:error("请先在本机阅读此书以选择位置")
        state.getAsJsonObject("books").add(identity,chosen);state.getAsJsonObject("conflicts").remove(identity);write(state)
    }
    @Synchronized fun resolve(identity:String,index:Int){
        val state=read();val choices=state.getAsJsonObject("conflicts").getAsJsonArray(identity);val chosen=choices[index].asJsonObject.deepCopy()
        chosen.add("version",gson.toJsonTree(ProgressVersions.advance(device,choices.map{vector(it.asJsonObject)})))
        store.remoteSources().firstOrNull{it.second.get("identity").asString==identity}?.let{(id,source)->
            val original=gson.fromJson(chosen.get("anchor"),Anchor::class.java).copy(bookId=id)
            val anchor=if(source.get("revision").asString==chosen.get("revision").asString)original else store.locateRemote(original)?:error("正文无法可靠定位，请先在本机选择章节，再采用本机位置")
            store.applyRemoteProgress(anchor,chosen.get("progress").asFloat,chosen.get("status").asString)
            chosen.add("anchor",gson.toJsonTree(store.position(id)!!.copy(bookId=identity)));chosen.addProperty("revision",source.get("revision").asString)
            val checkpoints=state.getAsJsonObject("checkpoints")?:JsonObject().also{state.add("checkpoints",it)}
            checkpoints.add(identity,JsonObject().apply{add("anchor",chosen.get("anchor"));add("status",chosen.get("status"))})
        }
        state.getAsJsonObject("books").add(identity,chosen);state.getAsJsonObject("conflicts").remove(identity);write(state)
    }
}
