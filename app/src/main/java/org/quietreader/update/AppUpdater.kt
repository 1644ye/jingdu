package org.quietreader.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.quietreader.core.ReleaseUpdates
import org.quietreader.remote.GitHubApi
import java.io.File
import java.util.concurrent.Executors

class AppUpdater(private val context:Context) {
    private val preferences=context.getSharedPreferences("app-updates",Context.MODE_PRIVATE)
    private val manager=context.getSystemService(DownloadManager::class.java)
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    val installedVersion:String get()=context.packageManager.getPackageInfo(context.packageName,0).versionName?:"0.0.0"
    var autoCheckEnabled:Boolean
        get()=preferences.getBoolean("auto-check",true)
        set(value){preferences.edit().putBoolean("auto-check",value).apply()}
    var autoDownloadEnabled:Boolean
        get()=preferences.getBoolean("auto-download",false)
        set(value){preferences.edit().putBoolean("auto-download",value).apply()}

    fun <T> run(task:()->T,done:(Result<T>)->Unit){executor.execute{val result=runCatching(task);main.post{done(result)}}}
    fun latest():ReleaseUpdates.Release?=try {
        ReleaseUpdates.parse(GitHubApi().get("/repos/${ReleaseUpdates.REPOSITORY}/releases/latest").asJsonObject)
    }catch(e:GitHubApi.Failure){if(e.status==404)null else throw e}

    @Synchronized fun autoCheck(found:(ReleaseUpdates.Release)->Unit){
        if(!autoCheckEnabled||System.currentTimeMillis()-preferences.getLong("last-check",0)<24*60*60*1000L)return
        preferences.edit().putLong("last-check",System.currentTimeMillis()).apply()
        run({latest()?.takeIf{ReleaseUpdates.newer(it.version,installedVersion)}?.also{if(autoDownloadEnabled)download(it)}}){result->result.getOrNull()?.let(found)}
    }
    data class Download(val id:Long,val version:String,val status:Int,val received:Long,val total:Long,val reason:Int) {
        val active get()=status in listOf(DownloadManager.STATUS_PENDING,DownloadManager.STATUS_RUNNING,DownloadManager.STATUS_PAUSED)
        val complete get()=status==DownloadManager.STATUS_SUCCESSFUL
        fun description():String=when(status){
            DownloadManager.STATUS_PENDING->"等待下载"
            DownloadManager.STATUS_RUNNING->"正在下载 ${if(total>0)"${received*100/total}%"else"${received/1024} KiB"}"
            DownloadManager.STATUS_PAUSED->"下载暂停，等待网络或系统调度（$reason）"
            DownloadManager.STATUS_SUCCESSFUL->"已下载，可以安装"
            else->"下载失败（$reason），请重新下载"
        }
    }
    fun downloadState():Download? {
        val id=preferences.getLong("download-id",-1);if(id<0)return null
        return manager.query(DownloadManager.Query().setFilterById(id))?.use{cursor->
            if(!cursor.moveToFirst())return@use null
            fun int(key:String)=cursor.getInt(cursor.getColumnIndexOrThrow(key))
            fun long(key:String)=cursor.getLong(cursor.getColumnIndexOrThrow(key))
            Download(id,preferences.getString("download-version","").orEmpty(),int(DownloadManager.COLUMN_STATUS),long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),int(DownloadManager.COLUMN_REASON))
        }
    }
    @Synchronized fun download(release:ReleaseUpdates.Release):Long {
        require(ReleaseUpdates.newer(release.version,installedVersion)){"当前版本无需更新"}
        val old=downloadState()
        if(old!=null&&old.version==release.version&&(old.active||(old.complete&&apkFile(old.version).isFile)))return old.id
        if(old?.active==true)error("已有更新正在下载，请先取消")
        if(old!=null)manager.remove(old.id)
        val file=apkFile(release.version);if(file.exists())check(file.delete()){"无法清理上次下载，请重试"}
        val request=DownloadManager.Request(Uri.parse(release.url)).setTitle("静读 ${release.version}").setDescription("软件更新，下载完成后请确认安装")
            .setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,file.name)
        val id=manager.enqueue(request)
        preferences.edit().putLong("download-id",id).putString("download-version",release.version).commit()
        return id
    }
    @Synchronized fun cancelDownload(){downloadState()?.let{manager.remove(it.id)};preferences.edit().remove("download-id").remove("download-version").commit()}
    private fun apkFile(version:String):File {ReleaseUpdates.version(version);return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"jingdu-update-$version.apk")}

    @Suppress("DEPRECATION")
    fun validateApk(file:File) {
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed=context.packageManager.getPackageInfo(context.packageName,flags)
        val archive=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:error("无法读取安装包，请重新下载")
        require(archive.packageName==context.packageName){"安装包不是静读"}
        val currentCode=if(Build.VERSION.SDK_INT>=28)installed.longVersionCode else installed.versionCode.toLong()
        val nextCode=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else archive.versionCode.toLong()
        require(nextCode>currentCode){"安装包版本不高于当前版本"}
        val current=if(Build.VERSION.SDK_INT>=28)installed.signingInfo?.apkContentsSigners else installed.signatures
        val next=if(Build.VERSION.SDK_INT>=28)archive.signingInfo?.apkContentsSigners else archive.signatures
        require(!current.isNullOrEmpty()&&!next.isNullOrEmpty()&&current.toSet()==next.toSet()){"安装包签名与当前应用不一致，无法覆盖升级"}
    }
    fun installerIntent():Intent {
        val state=downloadState()?:error("请先下载新版 APK");require(state.complete){"下载尚未完成"}
        validateApk(apkFile(state.version))
        val uri=manager.getUriForDownloadedFile(state.id)?:error("安装包已被移除，请重新下载")
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
