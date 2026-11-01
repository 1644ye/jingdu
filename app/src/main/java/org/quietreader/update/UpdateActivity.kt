package org.quietreader.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import org.quietreader.ReaderApp
import org.quietreader.Ui
import org.quietreader.core.ReleaseUpdates

class UpdateActivity:Activity() {
    private val updater get()=(application as ReaderApp).updates
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var ui:Ui
    private lateinit var panel:LinearLayout
    private lateinit var status:TextView
    private lateinit var downloadStatus:TextView
    private var release:ReleaseUpdates.Release?=null
    private var busy=false
    private val poll=object:Runnable{override fun run(){refreshDownload();handler.postDelayed(this,1000)}}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);ui=Ui(this,(application as ReaderApp).store.settings())
        panel=ui.panel(14)
        panel.setBackgroundColor(ui.background)
        panel.setOnApplyWindowInsetsListener{view,insets->view.setPadding(ui.dp(14)+insets.systemWindowInsetLeft,ui.dp(14)+insets.systemWindowInsetTop,ui.dp(14)+insets.systemWindowInsetRight,ui.dp(14)+insets.systemWindowInsetBottom);insets}
        panel.addView(ui.actions("返回书架" to {finish()},"<" to {finish()}))
        panel.addView(ui.text("软件更新",26f,true));panel.addView(ui.text("当前版本 ${updater.installedVersion}"))
        panel.addView(ui.text("更新来源：${ReleaseUpdates.REPOSITORY} · GitHub Releases",13f))
        panel.addView(Switch(this).apply{text="自动检查更新（每天一次）";setTextColor(ui.foreground);isChecked=updater.autoCheckEnabled;setOnCheckedChangeListener{_,checked->updater.autoCheckEnabled=checked}})
        panel.addView(Switch(this).apply{text="自动下载新版 APK";setTextColor(ui.foreground);isChecked=updater.autoDownloadEnabled;setOnCheckedChangeListener{_,checked->updater.autoDownloadEnabled=checked}})
        panel.addView(ui.text("自动下载会使用当前网络；安装新版仍需在系统页面确认。",13f))
        status=ui.text(state?.getString("status")?:"点击检查更新获取最新版本。",15f);panel.addView(status)
        panel.addView(ui.button("检查更新"){check()})
        panel.addView(ui.button("下载新版"){release?.let{target->operate({updater.download(target)}){refreshDownload()}}?:message("请先检查更新")})
        downloadStatus=ui.text("");panel.addView(downloadStatus)
        panel.addView(ui.actions("安装已下载更新" to {install()},"取消下载／清理安装包" to {operate({updater.cancelDownload()}){refreshDownload()}}))
        setContentView(ui.scroll(panel));check()
    }
    override fun onResume(){super.onResume();handler.removeCallbacks(poll);handler.post(poll)}
    override fun onPause(){handler.removeCallbacks(poll);super.onPause()}
    override fun onSaveInstanceState(out:Bundle){out.putString("status",status.text.toString());super.onSaveInstanceState(out)}
    private fun message(text:String){ui.dialog().setTitle("软件更新").setMessage(text).setPositiveButton("知道了",null).show()}
    private fun <T> operate(task:()->T,onError:((Throwable)->Unit)?=null,done:(T)->Unit){
        if(busy){message("正在处理，请稍候");return};busy=true
        updater.run(task){result->busy=false;if(!isFinishing&&!isDestroyed)result.fold(done){error->if(onError!=null)onError(error)else message(error.message?:"操作失败，请重试")}}
    }
    private fun check(){
        if(busy)return;status.text="正在检查更新…"
        operate({updater.latest()?.also{if(updater.autoDownloadEnabled&&ReleaseUpdates.newer(it.version,updater.installedVersion))updater.download(it)}},onError={status.text="检查失败：${it.message?:"请检查网络后重试"}"}){latest->release=latest
            status.text=when{latest==null->"尚未发布可下载版本。请在 GitHub Release 发布 APK 后再次检查。";ReleaseUpdates.newer(latest.version,updater.installedVersion)->"发现新版本 ${latest.version} · ${latest.bytes/1024/1024} MiB\n${latest.notes}";else->"当前已是最新版本（${updater.installedVersion}）"}
        }
    }
    private fun refreshDownload(){downloadStatus.text=try{updater.downloadState()?.let{"${it.version} · ${it.description()}"}?:"尚无已下载的更新"}catch(e:Exception){"无法读取下载状态：${e.message}"}}
    private fun install(){
        operate({updater.installerIntent()}){intent->
            if(!packageManager.canRequestPackageInstalls()){
                ui.dialog().setTitle("允许安装更新").setMessage("请在系统设置中允许静读安装应用，返回后再次点击安装。")
                    .setNegativeButton("取消",null).setPositiveButton("打开设置"){_,_->runCatching{startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))}.onFailure{message(it.message?:"无法打开系统设置")}}.show()
            }else runCatching{startActivity(intent)}.onFailure{message(it.message?:"无法打开系统安装器")}
        }
    }
}
