package org.quietreader.core

import com.google.gson.JsonObject
import java.net.URI

/** Public application releases are independent of the user's private book and progress repositories. */
object ReleaseUpdates {
    const val REPOSITORY = "1644ye/jingdu"
    data class Release(val version:String,val notes:String,val url:String,val bytes:Long)
    fun version(value:String):List<Int> {
        require(value.matches(Regex("v?[0-9]+\\.[0-9]+\\.[0-9]+"))){"版本号必须是 v主版本.次版本.修订号"}
        return value.removePrefix("v").split('.').map{it.toInt()}
    }
    fun newer(candidate:String,installed:String):Boolean {
        val a=version(candidate);val b=version(installed)
        return a.indices.firstOrNull{a[it]!=b[it]}?.let{a[it]>b[it]}?:false
    }
    fun parse(document:JsonObject):Release {
        require(document.get("draft")?.asBoolean!=true&&document.get("prerelease")?.asBoolean!=true){"仅安装正式发布版本"}
        val number=document.get("tag_name")?.asString.orEmpty().removePrefix("v");version(number)
        val assets=document.getAsJsonArray("assets")?.map{it.asJsonObject}.orEmpty()
        val apks=assets.filter{it.get("name")?.asString?.endsWith(".apk",true)==true}
        val preferred=apks.filter{it.get("name").asString=="QuietReader-$number-debug.apk"}
        val asset=(if(preferred.isNotEmpty())preferred else apks).singleOrNull()?:error("发布版本必须包含一个可识别的 APK 安装包")
        val url=asset.get("browser_download_url")?.asString.orEmpty();val uri=URI(url)
        require(uri.scheme=="https"&&uri.host=="github.com"&&uri.userInfo==null&&uri.port==-1&&uri.path.startsWith("/$REPOSITORY/releases/download/")){"安装包必须来自静读的 GitHub Release"}
        val bytes=asset.get("size")?.asLong?:0L;require(bytes>0){"安装包大小无效"}
        return Release(number,document.get("body")?.takeUnless{it.isJsonNull}?.asString.orEmpty(),url,bytes)
    }
}
