package org.quietreader.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GitHubConnection(val owner:String="",val repo:String="reading-library",val branch:String="",val root:String="",val catalog:String="catalog.json") {
    fun validate(){require(owner.matches(Regex("[A-Za-z0-9_-]+"))&&repo.matches(Regex("[A-Za-z0-9_.-]+"))){"仓库所有者或名称无效"};if(root.isNotBlank())org.quietreader.core.RemoteLibrary.path(root);org.quietreader.core.RemoteLibrary.path(catalog)}
    fun prefix()="/repos/${GitHubApi.segment(owner)}/${GitHubApi.segment(repo)}"
    fun path(value:String)=listOf(root.trim('/'),value).filter{it.isNotEmpty()}.joinToString("/")
}

/** Tokens are encrypted with an Android Keystore key and excluded from library backups. */
class RemotePreferences(private val context:Context) {
    private val preferences=context.getSharedPreferences("github-connections",Context.MODE_PRIVATE)
    private val gson=Gson()
    fun connection(sync:Boolean):GitHubConnection=gson.fromJson(preferences.getString(if(sync)"state"else"library",null),GitHubConnection::class.java)?:GitHubConnection(repo=if(sync)"reading-state"else"reading-library")
    fun save(sync:Boolean,value:GitHubConnection){value.validate();require(!value.owner.equals(connection(!sync).owner,true)||!value.repo.equals(connection(!sync).repo,true)){"书源和同步必须使用不同仓库"};if(!sync&&connection(false)!=value)(context.applicationContext as? org.quietreader.ReaderApp)?.downloads?.cancelAll();preferences.edit().putString(if(sync)"state"else"library",gson.toJson(value)).putBoolean(if(sync)"state-valid"else"library-valid",true).commit();if(sync)pauseSync()}
    fun validated(sync:Boolean)=preferences.getBoolean(if(sync)"state-valid"else"library-valid",false)
    private fun pauseSync(){context.getSharedPreferences("progress-device",Context.MODE_PRIVATE).edit().putBoolean("enabled",false).putBoolean("paused",true).commit()}
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        return (store.getKey("jingdu-github",null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("jingdu-github",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
    }
    fun token(sync:Boolean):String {
        val stored=preferences.getString(if(sync)"state-token"else"library-token",null)?:return ""
        val parts=stored.split(':');val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
    }
    fun login(sync:Boolean,token:String,login:String){if(!sync&&user(false)!=login)(context.applicationContext as? org.quietreader.ReaderApp)?.downloads?.cancelAll();val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());val value=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(token.toByteArray()),Base64.NO_WRAP);preferences.edit().putString(if(sync)"state-token"else"library-token",value).putString(if(sync)"state-user"else"library-user",login).commit();if(sync)pauseSync()}
    fun user(sync:Boolean)=preferences.getString(if(sync)"state-user"else"library-user","未登录").orEmpty()
    fun logout(sync:Boolean){if(!sync)(context.applicationContext as? org.quietreader.ReaderApp)?.downloads?.cancelAll();preferences.edit().remove(if(sync)"state-token"else"library-token").remove(if(sync)"state-user"else"library-user").commit();if(sync)pauseSync()}
}

class GitHubApi(private val token:String="",private val readJson:((String)->com.google.gson.JsonElement)?=null,private val openDownload:((URL)->HttpURLConnection)?=null) {
    class EmptyRepository:IllegalStateException("仓库尚无提交。请先在 GitHub 上传书籍 JSON 和 catalog.json，再刷新书库；App 不会向书源写入文件。")
    class Failure(val status:Int,val apiMessage:String?=null,method:String="GET"):Exception(when(status){401->"GitHub 授权已失效，请重新登录";403,429->"GitHub 权限不足或请求受限，请检查授权并稍后重试";404->"仓库或文件不存在，或当前账号没有访问权限";409->if(method=="PUT")"远端版本发生变化，请重新同步"else"GitHub 无法读取当前仓库状态（409）${apiMessage?.let{": $it"}.orEmpty()}";422->"GitHub 请求参数未通过校验或请求受限（422），请检查分支、路径及仓库配置";else->"GitHub 请求失败（$status）"})
    companion object {
        const val LIBRARY_CLIENT="Iv23liwlcvuZlOilMie3"
        const val STATE_CLIENT="Iv23liNWpFfrzUs2jRqb"
        fun authorizationError(code:String?):String=when(code){
            "device_flow_disabled"->"此 GitHub App 未启用 Device Flow，请在 GitHub App 设置中启用后重试。"
            "incorrect_client_credentials","invalid_client"->"GitHub App 的 Client ID 无效，请检查应用注册配置。"
            "access_denied"->"你已取消 GitHub 授权，可以重新点击登录。"
            "expired_token","bad_verification_code"->"设备码已过期或无效，请重新点击登录获取新码。"
            else->"无法开始 GitHub 授权，请确认 App 已启用 Device Flow 后重试。"
        }
        fun segment(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
        fun location(value:String)=value.split('/').joinToString("/"){segment(it)}
        fun oauth(path:String,values:Map<String,String>):JsonObject {
            val text=values.entries.joinToString("&"){segment(it.key)+"="+segment(it.value)}
            return JsonParser.parseString(GitHubApi().request("https://github.com/login/$path","POST",text,"application/x-www-form-urlencoded")).asJsonObject
        }
    }
    fun request(url:String,method:String="GET",body:String?=null,contentType:String="application/json",limit:Int=40*1024*1024,raw:Boolean=false):String {
        require(url.startsWith("https://api.github.com/")||url.startsWith("https://github.com/login/"))
        val connection=URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects=false;connection.connectTimeout=20000;connection.readTimeout=30000;connection.requestMethod=method
            connection.setRequestProperty("Accept",if(raw)"application/vnd.github.raw+json"else"application/json");connection.setRequestProperty("User-Agent","Jingdu-Android")
            if(token.isNotEmpty()&&url.startsWith("https://api.github.com/"))connection.setRequestProperty("Authorization","Bearer $token")
            if(body!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type",contentType);connection.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}}
            if(connection.responseCode !in 200..299){
                val apiMessage=runCatching{connection.errorStream?.use{input->val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024);while(output.size()<8192){val n=input.read(buffer,0,minOf(buffer.size,8192-output.size()));if(n<0)break;output.write(buffer,0,n)};JsonParser.parseString(output.toString("UTF-8")).asJsonObject.get("message")?.asString?.take(300)}}.getOrNull()
                throw Failure(connection.responseCode,apiMessage,method)
            }
            connection.inputStream.use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);var total=0;while(true){if(Thread.currentThread().isInterrupted)throw InterruptedException();val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit){"远端文件超过大小限制"};out.write(buffer,0,n)};return out.toString("UTF-8")}
        } finally {connection.disconnect()}
    }
    fun get(path:String)=readJson?.invoke(path)?:JsonParser.parseString(request("https://api.github.com$path"))
    fun repositories(sync:Boolean):List<JsonObject> {
        val result=mutableListOf<JsonObject>()
        val installations=get("/user/installations?per_page=100").asJsonObject
        require(installations.get("total_count").asInt<=100){"安装数量较多，请缩小当前应用授权范围"}
        for(item in installations.getAsJsonArray("installations")){
            val installation=item.asJsonObject;val permission=installation.getAsJsonObject("permissions").get("contents")?.asString
            if(sync){if(permission!="write")continue}else require(permission!="write"){"书源应用具有内容写权限，请先在 GitHub 将书源 App 改为只读"}
            var page=1
            while(true){val response=get("/user/installations/${installation.get("id").asLong}/repositories?per_page=100&page=$page").asJsonObject;val entries=response.getAsJsonArray("repositories");entries.forEach{result.add(it.asJsonObject)};if(entries.size()<100)break;page++}
        }
        return result
    }
    fun revisionOrNull(connection:GitHubConnection):String? {
        val branch=connection.branch.ifBlank{get(connection.prefix()).asJsonObject.get("default_branch").asString}
        return try{get(connection.prefix()+"/commits/"+segment(branch)).asJsonObject.get("sha").asString}catch(e:Failure){
            if(e.status==409&&e.apiMessage?.contains("empty",true)==true)null
            else if(e.status in listOf(404,422))throw IllegalStateException("无法读取分支“$branch”（${e.status}）。请确认该分支存在、已完成首次提交且当前账号有读取权限。",e)
            else throw e
        }
    }
    fun revision(connection:GitHubConnection):String=revisionOrNull(connection)?:throw EmptyRepository()
    fun file(connection:GitHubConnection,path:String,ref:String,raw:Boolean=false)=request("https://api.github.com"+connection.prefix()+"/contents/"+location(path)+"?ref="+segment(ref),raw=raw)
    fun downloadFile(connection:GitHubConnection,path:String,ref:String,target:java.io.File,stopped:()->Boolean,progress:(Long)->Unit){
        val offset=target.takeIf{it.exists()}?.length()?:0L
        val url=URL("https://api.github.com"+connection.prefix()+"/contents/"+location(path)+"?ref="+segment(ref))
        val http=openDownload?.invoke(url)?:url.openConnection() as HttpURLConnection
        try{
            http.instanceFollowRedirects=false;http.connectTimeout=20000;http.readTimeout=30000
            http.setRequestProperty("Accept","application/vnd.github.raw+json");http.setRequestProperty("User-Agent","Jingdu-Android")
            if(token.isNotEmpty())http.setRequestProperty("Authorization","Bearer $token")
            if(offset>0)http.setRequestProperty("Range","bytes=$offset-")
            val status=http.responseCode
            if(status==416){target.outputStream().close();throw java.io.IOException("远端不接受当前断点，已准备重新下载")}
            if(status !in listOf(200,206))throw Failure(status)
            val append=status==206&&offset>0
            if(status==206)require(http.getHeaderField("Content-Range")?.startsWith("bytes ${if(append)offset else 0}-")==true){"续传位置与远端响应不一致"}
            var count=if(append)offset else 0L
            java.io.FileOutputStream(target,append).use{output->http.inputStream.use{input->val buffer=ByteArray(32768);while(true){if(stopped()||Thread.currentThread().isInterrupted)throw DownloadStopped();val n=input.read(buffer);if(n<0)break;count+=n;require(count<=org.quietreader.core.Protocol.MAX_FILE_BYTES){"书籍超过32 MiB上限"};output.write(buffer,0,n);progress(count)}};output.fd.sync()}
        }finally{http.disconnect()}
    }
    private val trees=mutableMapOf<String,JsonObject>()
    fun fileVersion(connection:GitHubConnection,path:String,ref:String):String {
        var sha=ref
        for(part in path.split('/')){
            val tree=trees.getOrPut(sha){get(connection.prefix()+"/git/trees/"+segment(sha)).asJsonObject}
            require(tree.get("truncated")?.asBoolean!=true){"远端目录过大，未完整加载"}
            val entry=tree.getAsJsonArray("tree").firstOrNull{it.asJsonObject.get("path").asString==part}?.asJsonObject?:error("文件已不存在：$path")
            sha=entry.get("sha").asString
        }
        return sha
    }
    fun put(connection:GitHubConnection,path:String,content:String,sha:String?){
        val body=JsonObject().apply{addProperty("message","Update reading progress");addProperty("content",Base64.encodeToString(content.toByteArray(Charsets.UTF_8),Base64.NO_WRAP));if(sha!=null)addProperty("sha",sha);if(connection.branch.isNotBlank())addProperty("branch",connection.branch)}
        request("https://api.github.com"+connection.prefix()+"/contents/"+location(path),"PUT",body.toString())
    }
}
