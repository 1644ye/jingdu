package org.quietreader.remote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.quietreader.ReaderApp
import org.quietreader.Ui
import org.quietreader.core.Protocol
import org.quietreader.core.RemoteLibrary
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RemoteActivity:Activity() {
    private val store get()=(application as ReaderApp).store
    private val preferences by lazy{RemotePreferences(this)}
    private val executor=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var ui:Ui
    private lateinit var panel:LinearLayout
    private val cancelled=AtomicBoolean(false)
    private var busy=false
    private var operationMessage=""
    private lateinit var operationStatus:TextView
    private var upAction:(()->Unit)?=null
    private var pageGeneration=0
    private var connection=GitHubConnection()
    private var revision=""
    private var repositoryId=""
    private var offline=false
    private val fileVersions=mutableMapOf<String,String>()
    private var catalog:RemoteLibrary.Catalog?=null
    private var folder=""
    private var search=""
    private val selected=linkedSetOf<String>()
    private var screenKind="home"
    private var directoryPath=""
    private fun goUp(){cancelled.set(true);upAction?.invoke()?:finish()}
    private fun page(title:String,up:()->Unit={home()}){pageGeneration++;upAction=up;ui=Ui(this,store.settings());panel=ui.panel();panel.addView(ui.row().apply{addView(ui.button("返回书架"){finish()});addView(ui.button("<"){goUp()}.apply{contentDescription="返回上一级"})});panel.addView(ui.text(title,24f,true));operationStatus=ui.text(if(busy)operationMessage else "",16f).apply{contentDescription="连接任务状态";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;visibility=if(busy)View.VISIBLE else View.GONE};panel.addView(operationStatus);setContentView(ui.scroll(panel).apply{setBackgroundColor(ui.background);setOnApplyWindowInsetsListener{view,insets->view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom);insets}});window.statusBarColor=ui.background;window.navigationBarColor=ui.background;window.decorView.systemUiVisibility=if(ui.dark)0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR}
    override fun onCreate(state:Bundle?){super.onCreate(state)
        folder=state?.getString("folder").orEmpty();search=state?.getString("search").orEmpty();selected.addAll(state?.getStringArrayList("selected").orEmpty())
        val cached=runCatching{if(cacheFile().exists())JsonParser.parseString(cacheFile().readText()).asJsonObject else null}.getOrNull()
        if(cached!=null){connection=preferences.connection(false);repositoryId=cached.get("repository").asString;revision=cached.get("revision").asString;catalog=runCatching{RemoteLibrary.catalog(JsonParser.parseString(cached.get("catalog").asString).asJsonObject)}.getOrNull();offline=true}
        when(state?.getString("screenKind")){"source-settings","source-auth"->settings(false);"state-settings","state-auth"->settings(true);"browse"->if(catalog!=null)browse()else home();"directory"->if(cached!=null){home();directory(state.getString("directoryPath").orEmpty())}else home();"tasks"->tasksPage();"sync"->syncPage();else->home()}
    }
    override fun onSaveInstanceState(out:Bundle){out.putString("screenKind",screenKind);out.putString("folder",folder);out.putString("search",search);out.putString("directoryPath",directoryPath);out.putStringArrayList("selected",ArrayList(selected));super.onSaveInstanceState(out)}
    override fun onDestroy(){cancelled.set(true);executor.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy()}
    private fun <T> run(task:()->T,message:String="正在处理，请稍候…",done:(T)->Unit){
        if(busy){operationStatus.text=operationMessage+"\n已有请求进行中，请等待完成后重试。";operationStatus.visibility=View.VISIBLE;Toast.makeText(this,"已有请求进行中，请稍候",Toast.LENGTH_SHORT).show();return}
        busy=true;operationMessage=message;operationStatus.text=message;operationStatus.visibility=View.VISIBLE;cancelled.set(false)
        val origin=pageGeneration
        executor.execute{try{val value=task();handler.post{busy=false;if(!isDestroyed&&!isFinishing){operationStatus.visibility=View.GONE;if(origin==pageGeneration)done(value)}}}catch(e:Exception){
            val explanation=when(e){
                is java.net.SocketTimeoutException->"连接 GitHub 超时，请检查网络后重新点击登录。"
                is java.net.UnknownHostException->"无法解析 GitHub 地址，请检查网络或 DNS 后重试。"
                is javax.net.ssl.SSLException->"无法建立 GitHub 安全连接，请检查网络和系统时间后重试。"
                is java.io.IOException->"无法连接 GitHub，请检查当前网络是否能访问 github.com 后重试。"
                else->e.message?:"操作失败，请重试。"
            }
            handler.post{busy=false;if(!isDestroyed&&!isFinishing){if(origin==pageGeneration){operationStatus.text=explanation;operationStatus.visibility=View.VISIBLE;ui.dialog().setTitle("操作未完成").setMessage(explanation).setPositiveButton("知道了",null).show()}else operationStatus.visibility=View.GONE}}
        }}
    }
    private fun home(){screenKind="home";page("GitHub 书库与同步",up={finish()});panel.addView(ui.button("浏览书源目录"){loadCatalog()});panel.addView(ui.button("直接浏览真实目录（无需索引）"){if(ensureSource())run({connection=preferences.connection(false);revision=GitHubApi(preferences.requireLogin(false)).revision(connection)}){directory("")}});if(cacheFile().exists())panel.addView(ui.button("离线浏览上次索引"){run({val cached=JsonParser.parseString(cacheFile().readText()).asJsonObject;connection=preferences.connection(false);repositoryId=cached.get("repository").asString;revision=cached.get("revision").asString;RemoteLibrary.catalog(JsonParser.parseString(cached.get("catalog").asString).asJsonObject)}){catalog=it;offline=true;folder="";browse()}});panel.addView(ui.button("书源账号与仓库设置"){settings(false)});panel.addView(ui.button("进度账号与仓库设置"){settings(true)});panel.addView(ui.button("阅读进度同步"){syncPage()});panel.addView(ui.button("下载任务"){tasksPage()});panel.addView(ui.text("首次使用请登录自己的 GitHub 账号并选择仓库。书源仅供读取，App 不会修改 GitHub 书源。"))}
    private fun ensureSource():Boolean {
        val reason=if(!preferences.loggedIn(false))"请先登录自己的 GitHub 账号"else if(!preferences.validated(false))"请选择当前账号可访问的书源仓库，连接并保存"else return true
        settings(false);operationStatus.text=reason;operationStatus.visibility=View.VISIBLE;return false
    }
    private fun clearSourceSession(){catalog=null;selected.clear();folder="";search="";directoryPath="";fileVersions.clear();repositoryId="";revision="";connection=preferences.connection(false);offline=false}
    private fun settings(sync:Boolean){screenKind=if(sync)"state-settings"else"source-settings";page(if(sync)"进度同步连接"else"只读书源连接");val current=preferences.connection(sync)
        panel.addView(ui.text("当前账号：${preferences.user(sync)}"));panel.addView(ui.text("请使用自己的 GitHub 账号授权；更换账号后需重新选择仓库。",14f));panel.addView(ui.actions("登录 / 更换账号" to {login(sync)},"退出账号" to {preferences.logout(sync);if(!sync)clearSourceSession();settings(sync)}))
        val url=ui.field("粘贴 GitHub 仓库 URL（可选）");panel.addView(url)
        val owner=ui.field("仓库所有者",current.owner);val repo=ui.field("仓库名称",current.repo);val branch=ui.field("分支（空白使用默认分支）",current.branch);val root=ui.field("书源根目录（可留空）",current.root);val index=ui.field("索引路径",current.catalog)
        listOf(owner,repo,branch).forEach{panel.addView(it)};if(!sync)listOf(root,index).forEach{panel.addView(it)}
        panel.addView(ui.button("从已授权仓库选择"){run({GitHubApi(preferences.token(sync).also{require(it.isNotEmpty()){"请先登录"}}).repositories(sync)}){repos->if(repos.isEmpty())ui.dialog().setMessage("当前应用尚未安装到可用仓库，请在 GitHub 的 Install App 中选择仓库。").setPositiveButton("确定",null).show()else ui.dialog().setItems(repos.map{it.get("full_name").asString}.toTypedArray()){_,position->val selected= repos[position];owner.setText(selected.getAsJsonObject("owner").get("login").asString);repo.setText(selected.get("name").asString);url.setText("")}.show()}})
        panel.addView(ui.button("连接并保存"){
            run({var value=GitHubConnection(owner.text.toString().trim(),repo.text.toString().trim(),branch.text.toString().trim(),if(sync)""else root.text.toString().trim(),if(sync)"catalog.json"else index.text.toString().trim())
                if(url.text.isNotBlank()){val parsed=Uri.parse(url.text.toString().trim());require(parsed.scheme=="https"&&parsed.host=="github.com"&&parsed.pathSegments.size==2){"请填写 https://github.com/所有者/仓库"};value=value.copy(owner=parsed.pathSegments[0],repo=parsed.pathSegments[1].removeSuffix(".git"))}
                val token=preferences.requireLogin(sync);value.validate();val api=GitHubApi(token)
                val repository=api.get(value.prefix()).asJsonObject
                val empty=api.revisionOrNull(value)==null
                if(token.isNotEmpty()){val accessible=api.repositories(sync);if(sync||repository.get("private").asBoolean)require(accessible.any{it.get("id")==repository.get("id")}){"请将对应 GitHub App 安装到此仓库，并检查权限"}}
                if(sync)require(repository.get("private").asBoolean){"请使用私有仓库存放阅读进度"}
                preferences.save(sync,value);cacheFile().delete();empty
            }){empty->home();if(empty)ui.dialog().setTitle("连接已保存，仓库暂无提交").setMessage(if(sync)"首次同步时会创建进度文件。"else"请在 GitHub 上传书籍 JSON 和 catalog.json 后刷新。App 不会修改远端书源。").setPositiveButton("知道了",null).show()}
        });panel.addView(ui.button("取消"){home()})
    }
    private fun login(sync:Boolean){
        val client=if(sync)GitHubApi.STATE_CLIENT else GitHubApi.LIBRARY_CLIENT
        run({GitHubApi.oauth("device/code",mapOf("client_id" to client)).also{require(it.has("device_code")){GitHubApi.authorizationError(it.get("error")?.asString)}}},message="正在连接 GitHub，获取登录设备码…\n网络较慢时需要等待，失败后会显示原因。"){device->
            screenKind=if(sync)"state-auth"else"source-auth";page("GitHub 设备授权",up={settings(sync)});val code=device.get("user_code").asString
            panel.addView(ui.text("请在浏览器中登录目标账号并输入：$code",22f,true).apply{setTextIsSelectable(true)})
            panel.addView(ui.button("打开 GitHub 授权页面"){startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/login/device")))})
            panel.addView(ui.text("完成授权后，点击下方按钮等待确认。"))
            panel.addView(ui.button("我已授权，等待确认"){
                run({var interval=device.get("interval").asLong.coerceAtLeast(5);val deadline=android.os.SystemClock.elapsedRealtime()+device.get("expires_in").asLong*1000
                    var token:String?=null
                    while(token==null&&android.os.SystemClock.elapsedRealtime()<deadline&&!cancelled.get()){
                        Thread.sleep(interval*1000)
                        val response=GitHubApi.oauth("oauth/access_token",mapOf("client_id" to client,"device_code" to device.get("device_code").asString,"grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
                        if(response.has("access_token"))token=response.get("access_token").asString else when(response.get("error")?.asString){"authorization_pending"->Unit;"slow_down"->interval+=5;else->error(GitHubApi.authorizationError(response.get("error")?.asString))}
                    }
                    require(token!=null){"授权过期或已取消"};val user=GitHubApi(token).get("/user").asJsonObject.get("login").asString
                    require(!cancelled.get()){"已取消授权等待"};preferences.login(sync,token,user);cacheFile().delete();user
                },message="正在等待 GitHub 授权确认…\n请在浏览器完成设备码授权，再返回此处等待。"){if(!sync)clearSourceSession();settings(sync)}
            });panel.addView(ui.button("取消授权等待"){cancelled.set(true);if(!busy)settings(sync)})
        }
    }
    private fun cacheFile()=java.io.File(noBackupFilesDir,"github-catalog.json")
    private fun loadCatalog(){if(!ensureSource())return;run({connection=preferences.connection(false);offline=false;fileVersions.clear();val api=GitHubApi(preferences.requireLogin(false));val repo=api.get(connection.prefix()).asJsonObject;repositoryId=repo.get("id").asString;revision=api.revision(connection)
            val text=api.file(connection,connection.path(connection.catalog),revision,true);val parsed=RemoteLibrary.catalog(JsonParser.parseString(text).asJsonObject)
            val entries=parsed.books.associateBy{"$repositoryId/${parsed.libraryId}/${it.id}"};val present=entries.keys.toMutableSet()
            store.remoteSources().forEach{(_,source)->val identity=source.get("identity").asString;entries[identity]?.let{entry->try{api.fileVersion(connection,connection.path(entry.path),revision)}catch(e:IllegalStateException){if(e.message?.startsWith("文件已不存在：")==true)present.remove(identity)else throw e}}}
            store.reconcileSources(repositoryId,parsed.libraryId,present,revision)
            cacheFile().writeText(JsonObject().apply{addProperty("repository",repositoryId);addProperty("revision",revision);addProperty("catalog",text)}.toString());parsed
        }){catalog=it;folder="";selected.clear();browse()}}
    private fun browse(){screenKind="browse";page("GitHub 书库 / $folder",up={if(folder.isEmpty())home()else{folder=folder.trimEnd('/').substringBeforeLast('/',"").let{if(it.isEmpty())""else"$it/"};browse()}});val data=catalog?:return
        if(offline)panel.addView(ui.text("正在浏览上次缓存；联网刷新后可下载与检查更新。"))
        panel.addView(ui.actions("刷新" to {loadCatalog()},"真实目录" to {directory(folder)},"上一级" to {folder=folder.trimEnd('/').substringBeforeLast('/',"").let{if(it.isEmpty())""else"$it/"};browse()}))
        val field=ui.field("书名、作者、标签",search);panel.addView(field);panel.addView(ui.button("搜索"){search=field.text.toString();browse()})
        val entries=data.books.filter{it.path.startsWith(folder)&&(search.isBlank()||listOf(it.title,it.author,it.tags.joinToString(" ")).any{s->s.contains(search,true)})}
        if(!offline)panel.addView(ui.button("检查当前目录正文更新"){run({val api=GitHubApi(preferences.token(false));entries.associate{it.id to api.fileVersion(connection,connection.path(it.path),revision)}}){fileVersions.putAll(it);browse()}})
        panel.addView(ui.actions("全选当前目录" to {selected.addAll(entries.map{it.id});browse()},"取消选择" to {selected.clear();browse()},"下载 / 更新所选" to {download(entries.filter{it.id in selected})}))
        panel.addView(ui.text("${entries.size} 本 · 已选 ${selected.size} 本"))
        entries.mapNotNull{it.path.removePrefix(folder).substringBefore('/',"").takeIf{s->s.isNotEmpty()}}.distinct().sorted().forEach{dir->panel.addView(ui.button("目录 · $dir"){folder+="$dir/";browse()})}
        val sources=store.remoteSources().map{it.second}
        entries.filter{!it.path.removePrefix(folder).contains('/')||search.isNotBlank()}.forEach{entry->
            val prior=sources.firstOrNull{it.get("identity").asString=="$repositoryId/${data.libraryId}/${entry.id}"}
            val status=if(prior==null)"未下载"else if(fileVersions[entry.id]!=null)if(prior.get("revision").asString==fileVersions[entry.id])"已下载 · 最新"else"有更新"else if(prior.get("commit")?.asString==revision)"已下载"else"已下载 · 可检查更新"
            panel.addView(CheckBox(this).apply{text="${entry.title} · ${entry.author} · $status";setTextColor(ui.foreground);isChecked=entry.id in selected;setOnCheckedChangeListener{_,checked->if(checked)selected.add(entry.id)else selected.remove(entry.id)}})
        }
    }
    private fun directory(path:String){if(!ensureSource())return;run({val api=GitHubApi(preferences.token(false));JsonParser.parseString(api.file(connection,connection.path(path.trimEnd('/')),revision)).asJsonArray}){items->screenKind="directory";directoryPath=path;page("真实目录 / $path",up={if(path.isEmpty())home()else directory(path.trimEnd('/').substringBeforeLast('/',"").let{if(it.isEmpty())""else"$it/"})});panel.addView(ui.button("返回索引"){browse()});if(items.size()>=1000)panel.addView(ui.text("此目录可能未完整加载，请在 GitHub 查看完整目录。"));items.forEach{element->val item=element.asJsonObject;val name=item.get("name").asString;val type=item.get("type").asString;if(type=="dir")panel.addView(ui.button("目录 · $name"){directory(path+name+"/")})else panel.addView(ui.text(name))}}}
    private fun download(entries:List<RemoteLibrary.Entry>){
        if(entries.isEmpty())return
        if(!ensureSource())return
        if(offline){ui.dialog().setMessage("请联网刷新后再下载").setPositiveButton("确定",null).show();return}
        val data=catalog?:return
        (application as ReaderApp).downloads.enqueue(connection,repositoryId,data.libraryId,revision,entries)
        tasksPage()
    }
    private fun tasksPage(){
        screenKind="tasks";page("下载任务",up={if(catalog!=null)browse()else home()})
        val queue=(application as ReaderApp).downloads
        panel.addView(ui.text("任务已保存，离开页面后仍会下载。断网后联网重试；断点仅在服务器支持时续传。"))
        val rows=ui.panel(0);panel.addView(rows);var previous=""
        val generation=pageGeneration
        fun refresh(){
            if(isDestroyed||pageGeneration!=generation)return
            val tasks=queue.tasks();val signature=tasks.toString()
            if(signature!=previous){previous=signature;rows.removeAllViews()
                if(tasks.isEmpty())rows.addView(ui.text("没有下载任务"))
                tasks.reversed().forEach{task->
                    val id=task.get("id").asString;val state=task.get("state").asString
                    val label=when(state){"queued"->"等待联网下载";"downloading"->"正在下载";"paused"->"已暂停";"failed"->"失败";"cancelled"->"已取消";else->"已完成"}
                    rows.addView(ui.text(task.getAsJsonObject("entry").get("title").asString+" · $label · "+(task.get("bytes")?.asLong?:0)+" 字节"))
                    task.get("error")?.asString?.let{rows.addView(ui.text(it,13f))}
                    fun action(value:String){runCatching{queue.action(id,value)}.onFailure{Toast.makeText(this,it.message,Toast.LENGTH_LONG).show()};previous="";refresh()}
                    val actions=mutableListOf<Pair<String,()->Unit>>()
                    if(state in listOf("queued","downloading"))actions.add("暂停" to {action("paused")})
                    if(state in listOf("paused","failed","cancelled"))actions.add("继续 / 重试" to {action("queued")})
                    if(state !in listOf("completed","cancelled"))actions.add("取消" to {action("cancelled")})
                    if(actions.isNotEmpty())rows.addView(ui.actions(*actions.toTypedArray()))
                }
            }
        }
        val tick=object:Runnable{override fun run(){if(pageGeneration==generation&&!isDestroyed){refresh();handler.postDelayed(this,1000)}}}
        handler.post(tick)
    }
    private fun syncPage(){screenKind="sync";page("阅读进度同步");val sync=(application as ReaderApp).progressSync
        val prefs=getSharedPreferences("progress-device",MODE_PRIVATE)
        panel.addView(ui.text("上次成功："+prefs.getLong("lastSuccess",0).let{if(it==0L)"尚未同步"else java.util.Date(it).toString()}))
        panel.addView(ui.text("待同步：${sync.pendingCount()} 本"))
        prefs.getString("error",null)?.let{panel.addView(ui.text("后台同步未完成：$it"))}
        if(prefs.getBoolean("paused",false))panel.addView(ui.text("同步已暂停。确认仓库与本机进度后，重新开启同步开关。"))
        panel.addView(Switch(this).apply{text="启用进度同步";setTextColor(ui.foreground);isChecked=prefs.getBoolean("enabled",false);setOnCheckedChangeListener{_,checked->prefs.edit().putBoolean("enabled",checked).putBoolean("paused",false).apply()}})
        panel.addView(ui.button("立即同步"){run({org.quietreader.reader.ReaderPositions.awaitPendingWrites();sync.sync()}){result->ui.dialog().setMessage(result).setPositiveButton("确定"){_,_->syncPage()}.show()}})
        sync.conflicts().entrySet().forEach{(identity,choices)->panel.addView(ui.button("选择阅读位置 · "+choices.asJsonArray[0].asJsonObject.get("title").asString){val labels=choices.asJsonArray.map{it.asJsonObject.let{o->"${o.get("device").asString.take(8)} · ${o.getAsJsonObject("anchor").get("sectionId").asString} · ${(o.get("progress").asFloat*100).toInt()}%"}}+"采用本机当前阅读位置（正文不同请先阅读定位）";ui.dialog().setItems(labels.toTypedArray()){_,index->run({if(index==choices.asJsonArray.size())sync.keepLocal(identity)else sync.resolve(identity,index)}){syncPage()}}.show()})}
    }
}
