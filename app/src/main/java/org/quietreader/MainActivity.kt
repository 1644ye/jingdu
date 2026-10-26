package org.quietreader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonNull
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.quietreader.core.*
import org.quietreader.data.*
import org.quietreader.reader.ReaderActivity
import org.quietreader.reader.ReaderPositions
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader
import java.math.BigDecimal
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val store get()=(application as ReaderApp).store
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val gson=GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val compactGson=GsonBuilder().disableHtmlEscaping().create()
    private lateinit var ui:Ui
    private lateinit var root:LinearLayout
    private lateinit var body:FrameLayout
    private var screen="shelf"
    private var screenBook:String?=null
    private var generation=0
    private var taskRunning=false
    private var resumed=false
    private var unregisterBack:(()->Unit)?=null
    private var memoryRecoveryReserve:ByteArray?=ByteArray(256*1024)
    private var globalSettings=ReadingSettings()
    private var allBooks=emptyList<BookSummary>()
    private var groups=emptyList<ShelfGroup>()
    private var memberships=emptyMap<String,Set<Long>>()
    private var grid=true
    private var query=""
    private var filter=0
    private var sort=0
    private var selectedGroup:Long?=null
    private val selected=linkedSetOf<String>()
    private var shelfList:AbsListView?=null
    private var selectLabel:TextView?=null
    private var filtered=emptyList<BookSummary>()
    private var pendingWrite:((Uri)->Unit)?=null
    private var recoveryFile:File?=null
    private val cancel=AtomicBoolean(false)
    private var importFiles=emptyList<PreviewFile>()
    private val conflictChoices=mutableMapOf<String,String>()
    private var progressText:TextView?=null
    private val filters=listOf("全部","诗集","小说","收藏","未读","在读","已读")
    private data class PreviewBook(val id:String,val title:String,val type:String,val sections:Int)
    private data class PreviewFile(val name:String,val staged:File?,val books:List<PreviewBook>,val errors:MutableList<String>,val warnings:List<String>)
    private data class ShelfData(val books:List<BookSummary>,val groups:List<ShelfGroup>,val memberships:Map<String,Set<Long>>,val settings:ReadingSettings)
    private data class Detail(val book:BookSummary,val sections:List<SectionSummary>,val groups:List<ShelfGroup>,val member:Set<Long>)

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(Build.VERSION.SDK_INT>=33){
            val dispatcher=onBackInvokedDispatcher
            val callback=OnBackInvokedCallback{handleBack()}
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback)
            unregisterBack={dispatcher.unregisterOnBackInvokedCallback(callback)}
        }
        screen=state?.getString("screen")?:intent.getStringExtra("screen")?:"shelf"
        screenBook=state?.getString("bookId")?:intent.getStringExtra("bookId")
        grid=getPreferences(0).getBoolean("grid",true)
        ui=Ui(this);shell("静读");loading("正在打开书库…")
        work({
            cacheDir.listFiles()?.filter{it.isFile&&it.name.startsWith("import-")&&it.extension=="json"}?.forEach{it.delete()}
            val examples=listOf("poetry-example","novel-example").flatMap {name->
                Protocol.parse(resource("$name.json"),"内置示例").document!!.getAsJsonArray("books").map{it.asJsonObject}
            }
            store.initializeExamples(Protocol.wrap(examples))
            store.settings()
        }){globalSettings=it;render()}
    }
    override fun onResume(){super.onResume();if(resumed && !taskRunning && pendingWrite==null)work({store.settings()}){globalSettings=it;render()};resumed=true}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(!taskRunning){screen=intent.getStringExtra("screen")?:"shelf";screenBook=intent.getStringExtra("bookId");render()}}
    override fun onConfigurationChanged(config:Configuration){super.onConfigurationChanged(config);if(!taskRunning && screen!="import")render()}
    override fun onSaveInstanceState(out:Bundle){out.putString("screen",if(screen=="import")"shelf"else screen);out.putString("bookId",screenBook);super.onSaveInstanceState(out)}
    override fun onDestroy(){unregisterBack?.invoke();cancel.set(true);main.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy()}
    // API 33+ is handled by the registered OnBackInvokedCallback above; this is the API 26-32 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Android") override fun onBackPressed(){handleBack()}
    private fun handleBack(){
        if(taskRunning){toast("任务正在进行；可使用页面上的取消按钮。");return}
        if(screen!="shelf"){screen="shelf";screenBook=null;render()}else finish()
    }

    private fun <T> work(task:()->T,onError:((Exception)->Unit)?=null,done:(T)->Unit){
        // Clear the queued closure before posting errors, so a failed parse/restore does not
        // keep its large source tree alive while the error screen is being constructed.
        var queuedTask:(()->T)?=task
        fun failed(error:Exception){main.post{if(!isFinishing&&!isDestroyed){if(onError!=null)onError(error)else showError(error.message?:"操作失败，请重试。")}}}
        worker.execute {
            try{ReaderPositions.awaitPendingWrites();val result=queuedTask!!.invoke();queuedTask=null;main.post{if(!isFinishing&&!isDestroyed)done(result)}}
            catch(error:OutOfMemoryError){queuedTask=null;memoryRecoveryReserve=null;System.gc();failed(IllegalStateException("设备可用内存不足，操作未能完整结束。请减小文件或分批处理，关闭其他任务后重试；请查看当前页面的结果说明。"))}
            catch(error:Exception){queuedTask=null;failed(error)}
        }
    }
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    private fun showError(message:String){AlertDialog.Builder(this).setTitle("操作未完成").setMessage(message).setPositiveButton("知道了",null).show()}
    private fun navigate(name:String,id:String?=null){if(taskRunning)return;screen=name;screenBook=id;render()}
    private fun render(){generation++;ui=Ui(this,globalSettings.theme);when(screen){"detail"->detail(screenBook?:return navigate("shelf"));"notes"->notes(screenBook);"groups"->groupsScreen();"poems"->poems();"settings"->settingsScreen();"tools"->toolsScreen();"import"->importPreview();else->shelf()}}
    private fun shell(title:String){
        root=ui.panel(0);root.setBackgroundColor(ui.background)
        root.setOnApplyWindowInsetsListener{v,insets->v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom);insets}
        val top=ui.row().apply{setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(6))}
        if(screen!="shelf")top.addView(ui.button("返回书架"){navigate("shelf")}.apply{isEnabled=!taskRunning;alpha=if(taskRunning).45f else 1f})
        top.addView(ui.text(title,24f,true),LinearLayout.LayoutParams(0,-2,1f))
        if(screen=="shelf")top.addView(ui.button("更多"){menu()})
        root.addView(top)
        body=FrameLayout(this);root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        val outer=FrameLayout(this).apply{setBackgroundColor(ui.background);addView(root,FrameLayout.LayoutParams(if(resources.configuration.screenWidthDp>960)ui.dp(960)else -1,-1,Gravity.CENTER_HORIZONTAL))}
        setContentView(outer)
        window.statusBarColor=ui.background;window.navigationBarColor=ui.background
        window.decorView.systemUiVisibility=if(ui.dark)0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    private fun loading(message:String){body.removeAllViews();body.addView(ui.panel(24).apply{addView(ProgressBar(this@MainActivity));addView(ui.text(message))})}
    private fun menu(){val labels=arrayOf("导入 JSON","模板与备份","书架分组","全部书签与笔记","收藏的诗作","全局阅读设置");AlertDialog.Builder(this).setTitle("静读").setItems(labels){_,i->when(i){0->pickImport();1->navigate("tools");2->navigate("groups");3->navigate("notes");4->navigate("poems");5->navigate("settings")}}.show()}
    private fun resource(name:String)=checkNotNull(javaClass.classLoader?.getResourceAsStream("protocol/$name")){"模板资源缺失：$name"}.bufferedReader(Charsets.UTF_8).use{it.readText()}
    private fun date(value:Long)=if(value<=0)"尚未阅读"else SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(value))
    private fun type(value:String)=if(value=="poetry")"诗集"else"小说"
    private fun status(value:String)=when(value){"read"->"已读";"reading"->"在读";else->"未读"}
    private fun openReader(id:String,section:String?=null,anchor:Anchor?=null){
        val intent=Intent(this,ReaderActivity::class.java).putExtra("bookId",id)
        (section?:anchor?.sectionId)?.let{intent.putExtra("sectionId",it)}
        anchor?.let{intent.putExtra("contentId",it.contentId).putExtra("offset",it.start).putExtra("end",it.end)}
        startActivity(intent)
    }

    private fun shelf(){
        shell("静读");loading("正在整理书架…");val token=generation
        work({val books=store.listBooks();ShelfData(books,store.groups(),books.associate{it.id to store.groupIds(it.id)},store.settings())}){data->
            if(token!=generation)return@work
            allBooks=data.books;groups=data.groups;memberships=data.memberships;globalSettings=data.settings
            selected.retainAll(allBooks.map{it.id}.toSet());if(groups.none{it.id==selectedGroup})selectedGroup=null
            body.removeAllViews()
            val panel=ui.panel(10)
            panel.addView(ui.actions("导入 JSON" to {pickImport()},"下载模板" to {navigate("tools")},(if(grid)"列表显示"else"网格显示") to {grid=!grid;getPreferences(0).edit().putBoolean("grid",grid).apply();shelf()}))
            allBooks.filter{it.lastRead>0}.maxByOrNull{it.lastRead}?.let{b->panel.addView(ui.button("继续阅读 · ${b.title}"){openReader(b.id)})}
            val search=ui.field("搜索书名、作者、标签",query);panel.addView(search)
            val options=ui.row()
            options.addView(ui.spinner(filters,filter){filter=it;filterShelf()},LinearLayout.LayoutParams(0,-2,1f))
            options.addView(ui.spinner(listOf("最近阅读","导入时间","书名"),sort){sort=it;filterShelf()},LinearLayout.LayoutParams(0,-2,1f))
            options.addView(ui.spinner(listOf("全部分组")+groups.map{it.name},groups.indexOfFirst{it.id==selectedGroup}+1){selectedGroup=groups.getOrNull(it-1)?.id;filterShelf()},LinearLayout.LayoutParams(0,-2,1f));panel.addView(options)
            selectLabel=ui.text("长按书籍进入批量选择",13f);panel.addView(selectLabel)
            panel.addView(ui.actions("全选当前" to {selected.addAll(filtered.map{it.id});refreshSelection()},"取消选择" to {selected.clear();refreshSelection()},"批量操作" to {batchActions()}))
            shelfList=if(grid)GridView(this).apply{numColumns=if(resources.configuration.screenWidthDp>=600)3 else 2;horizontalSpacing=ui.dp(8);verticalSpacing=ui.dp(8);stretchMode=GridView.STRETCH_COLUMN_WIDTH}else ListView(this).apply{dividerHeight=ui.dp(8)}
            panel.addView(shelfList,LinearLayout.LayoutParams(-1,0,1f))
            val empty=ui.text(if(allBooks.isEmpty())"书架还是空的。导入 JSON，或下载模板开始填写。"else"没有符合条件的书籍。",16f).apply{gravity=Gravity.CENTER}
            panel.addView(empty);shelfList!!.emptyView=empty
            shelfList!!.setOnItemClickListener{_,_,position,_->val b=filtered[position];if(selected.isEmpty())navigate("detail",b.id)else{if(!selected.add(b.id))selected.remove(b.id);refreshSelection()}}
            shelfList!!.setOnItemLongClickListener{_,_,position,_->val id=filtered[position].id;if(!selected.add(id))selected.remove(id);refreshSelection();true}
            ui.watch(search){query=it;filterShelf()};body.addView(panel);filterShelf()
        }
    }
    private fun filterShelf(){
        filtered=allBooks.filter{b->(query.isBlank()||listOf(b.title,b.author,b.tags.joinToString(" ")).any{it.contains(query,true)})&&
            (selectedGroup==null||memberships[b.id]?.contains(selectedGroup)==true)&&when(filter){1->b.type=="poetry";2->b.type=="novel";3->b.favorite;4->b.status=="unread";5->b.status=="reading";6->b.status=="read";else->true}}
        filtered=when(sort){1->filtered.sortedByDescending{it.importedAt};2->filtered.sortedBy{it.title};else->filtered.sortedByDescending{it.lastRead}}
        shelfList?.adapter=object:BaseAdapter(){
            override fun getCount()=filtered.size
            override fun getItem(position:Int)=filtered[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,convert:View?,parent:ViewGroup?):View{
                val b=filtered[position];val card=ui.card()
                if(b.id in selected)card.background=GradientDrawable().apply{setColor(ui.surface);setStroke(ui.dp(3),ui.accent);cornerRadius=ui.dp(12).toFloat()}
                val title=ui.text((if(b.id in selected)"✓ "else"")+b.title,if(grid)20f else 18f,true).apply{minHeight=ui.dp(if(grid)80 else 48);maxLines=3;gravity=if(grid)Gravity.CENTER else Gravity.CENTER_VERTICAL;setTextColor(ui.foreground)}
                card.addView(title);card.addView(ui.text(b.author.ifEmpty{"佚名"},14f));card.addView(ui.text("${type(b.type)} · ${status(b.status)} · ${(b.progress*100).toInt()}%${if(b.favorite)" · ★"else""}${if("示例" in b.tags)" · 示例"else""}",12f));card.addView(ui.text(date(b.lastRead),12f).apply{setTextColor(ui.muted)})
                card.contentDescription="${b.title}，${b.author}，${type(b.type)}，${status(b.status)}，阅读${(b.progress*100).toInt()}%"
                return card
            }
        };refreshSelection()
    }
    private fun refreshSelection(){selectLabel?.text=if(selected.isEmpty())"共 ${filtered.size} 本 · 长按可批量选择"else"已选择 ${selected.size} 本";(shelfList?.adapter as? BaseAdapter)?.notifyDataSetChanged()}
    private fun batchActions(){if(selected.isEmpty()){toast("请长按书籍或全选当前列表。");return};val ids=selected.toList();AlertDialog.Builder(this).setTitle("操作 ${ids.size} 本书").setItems(arrayOf("加入分组","移出分组","导出内容 JSON","删除书籍")){_,i->when(i){0->chooseGroup(ids,true);1->chooseGroup(ids,false);2->exportBooks(ids);3->deleteBooks(ids)}}.show()}
    private fun deleteBooks(ids:List<String>){AlertDialog.Builder(this).setTitle("删除 ${ids.size} 本书？").setMessage("将同时删除正文、阅读进度、分组归属、收藏、书签、划线、笔记及单书阅读设置。此操作不能撤销；可先导出完整备份。").setNegativeButton("取消",null).setPositiveButton("删除"){_,_->work({store.deleteBooks(ids)}){selected.removeAll(ids.toSet());navigate("shelf")}}.show()}
    private fun chooseGroup(ids:List<String>,add:Boolean){work({store.groups()}){items->if(items.isEmpty()){toast("请先创建一个分组。");navigate("groups");return@work};AlertDialog.Builder(this).setTitle(if(add)"加入分组"else"移出分组").setItems(items.map{it.name}.toTypedArray()){_,index->work({store.setMembership(ids,items[index].id,add)}){toast("分组已更新");render()}}.setNegativeButton("取消",null).show()}}

    private fun detail(id:String){shell("书籍详情");loading("正在读取目录…");val token=generation
        work({Detail(store.listBooks().firstOrNull{it.id==id}?:error("书籍已删除"),store.sections(id),store.groups(),store.groupIds(id))}){data->
            if(token!=generation)return@work
            val b=data.book;val panel=ui.panel(14)
            panel.addView(ui.text(b.title,28f,true));panel.addView(ui.text("${b.author.ifEmpty{"佚名"}} · ${type(b.type)} · ${status(b.status)}"));panel.addView(ui.text(b.description.ifEmpty{"暂无简介"},14f))
            if(b.tags.isNotEmpty())panel.addView(ui.text(b.tags.joinToString(" · "),13f))
            panel.addView(ui.text("阅读 ${(b.progress*100).toInt()}% · ${date(b.lastRead)}",13f))
            panel.addView(ui.actions((if(b.lastRead>0)"继续阅读"else"开始阅读") to {openReader(id)},(if(b.favorite)"取消收藏"else"收藏本书") to {work({store.toggleFavorite(id)}){if(screen=="detail"&&screenBook==id)render()}},"编辑信息" to {editBook(b)}))
            panel.addView(ui.actions("书签与笔记" to {navigate("notes",id)},"导出 JSON" to {exportBooks(listOf(id))},"删除书籍" to {deleteBooks(listOf(id))}))
            panel.addView(ui.actions("加入分组" to {chooseGroup(listOf(id),true)},"移出分组" to {chooseGroup(listOf(id),false)},"阅读状态" to {AlertDialog.Builder(this).setTitle("阅读状态").setItems(arrayOf("未读","在读","已读")){_,i->work({store.setStatus(id,listOf("unread","reading","read")[i])}){if(screen=="detail"&&screenBook==id)render()}}.show()}))
            panel.addView(ui.text("分组："+data.groups.filter{it.id in data.member}.joinToString("、"){it.name}.ifEmpty{"尚未分组"},13f))
            val search=ui.field("搜索目录或卷名");panel.addView(search)
            val list=ListView(this);list.addHeaderView(panel,null,false);var entries=emptyList<Pair<String,SectionSummary?>>()
            fun update(q:String){entries=buildList{var volume="";data.sections.filter{it.title.contains(q,true)||it.volumeTitle.contains(q,true)}.forEach{s->if(s.volumeId.isNotEmpty()&&s.volumeId!=volume)add("卷 · ${s.volumeTitle}" to null);volume=s.volumeId;add(s.title to s)}};list.adapter=textAdapter(entries.map{it.first},entries.map{it.second!=null})}
            list.setOnItemClickListener{_,_,i,_->entries.getOrNull(i-1)?.second?.let{openReader(id,it.id)}};ui.watch(search){update(it)};update("")
            body.removeAllViews();body.addView(list)
        }
    }
    private fun editBook(book:BookSummary){val form=ui.panel();val title=ui.field("书名",book.title);val author=ui.field("作者",book.author);val desc=ui.field("简介",book.description,true);val tags=ui.field("标签，以逗号分隔",book.tags.joinToString(","));listOf(title,author,desc,tags).forEach{form.addView(it)}
        val dialog=AlertDialog.Builder(this).setTitle("编辑书籍信息").setView(ui.scroll(form)).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.setOnShowListener{dialog.getButton(-1).setOnClickListener{if(title.text.isBlank()){title.error="书名不能为空";return@setOnClickListener};work({store.editBook(book.id,title.text.toString(),author.text.toString(),desc.text.toString(),tags.text.toString().split(',', '，').map{it.trim()}.filter{it.isNotEmpty()})}){dialog.dismiss();if(screen=="detail"&&screenBook==book.id)render()}}};dialog.show()}
    private fun textAdapter(labels:List<String>,enabled:List<Boolean>?=null)=object:BaseAdapter(){override fun getCount()=labels.size;override fun getItem(position:Int)=labels[position];override fun getItemId(position:Int)=position.toLong();override fun isEnabled(position:Int)=enabled?.get(position)?:true;override fun getView(position:Int,convert:View?,parent:ViewGroup?):View=ui.text(labels[position],16f,enabled?.get(position)==false).apply{setPadding(ui.dp(16),ui.dp(14),ui.dp(16),ui.dp(14));minHeight=ui.dp(52)}}

    private fun groupsScreen(){shell("书架分组");loading("读取分组…");val token=generation
        work({store.groups()}){items->if(token!=generation)return@work;val panel=ui.panel();panel.addView(ui.button("创建分组"){nameDialog("创建分组",""){name->work({store.createGroup(name)}){if(screen=="groups")render()}}});panel.addView(ui.text("一本书可以加入多个分组。删除分组不会删除书籍。",13f));val list=ListView(this);list.adapter=textAdapter(items.map{it.name});list.setOnItemClickListener{_,_,index,_->val group=items[index];AlertDialog.Builder(this).setTitle(group.name).setItems(arrayOf("查看分组书籍","重命名","删除分组")){_,action->when(action){0->{selectedGroup=group.id;navigate("shelf")};1->nameDialog("重命名分组",group.name){name->work({store.renameGroup(group.id,name)}){if(screen=="groups")render()}};2->AlertDialog.Builder(this).setTitle("删除分组？").setMessage("仅删除分组与归属关系，书籍和笔记会保留。").setNegativeButton("取消",null).setPositiveButton("删除"){_,_->work({store.deleteGroup(group.id)}){if(screen=="groups")render()}}.show()}}.show()};panel.addView(list,LinearLayout.LayoutParams(-1,0,1f));body.removeAllViews();body.addView(panel)}
    }
    private fun nameDialog(title:String,value:String,save:(String)->Unit){val input=ui.field("分组名称",value);val dialog=AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.setOnShowListener{dialog.getButton(-1).setOnClickListener{if(input.text.isBlank())input.error="名称不能为空"else{save(input.text.toString());dialog.dismiss()}}};dialog.show()}
    private fun notes(bookId:String?){shell(if(bookId==null)"书签与笔记"else"本书书签与笔记");val token=generation;val panel=ui.panel();val search=ui.field("搜索摘录或笔记");panel.addView(search);val list=ListView(this);panel.addView(list,LinearLayout.LayoutParams(-1,0,1f));val empty=ui.text("还没有记录。阅读时选择文字，可划线或添加笔记。");panel.addView(empty);list.emptyView=empty;body.addView(panel);var rows=emptyList<AnnotationRecord>();var request=0
        fun refresh(q:String){val current=++request;work({store.annotations(bookId,q) to store.listBooks().associateBy{it.id}}){(items,books)->if(token!=generation||current!=request)return@work;rows=items;list.adapter=textAdapter(items.map{"${when(it.kind){"bookmark"->"书签";"highlight"->"划线";else->"笔记"}} · ${books[it.anchor.bookId]?.title.orEmpty()}${if(it.anchor.orphaned)" · 原位置已变化"else""}\n${it.anchor.quote.take(180)}${if(it.note.isEmpty())""else"\n${it.note}"}\n${date(it.createdAt)}"})}}
        list.setOnItemClickListener{_,_,index,_->val record=rows[index];val options=if(record.anchor.orphaned)arrayOf("编辑笔记","删除记录")else arrayOf("跳回原文","编辑笔记","删除记录");AlertDialog.Builder(this).setTitle(if(record.anchor.orphaned)"原位置已变化，笔记已保留"else"阅读记录").setItems(options){_,action->when(options[action]){"跳回原文"->openReader(record.anchor.bookId,anchor=record.anchor);"编辑笔记"->{val input=ui.field("笔记正文",record.note,true);AlertDialog.Builder(this).setTitle("编辑笔记").setView(input).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->work({store.editAnnotation(record.id,input.text.toString())}){refresh(search.text.toString())}}.show()};else->AlertDialog.Builder(this).setTitle("删除这条记录？").setNegativeButton("取消",null).setPositiveButton("删除"){_,_->work({store.deleteAnnotation(record.id)}){refresh(search.text.toString())}}.show()}}.show()};ui.watch(search){refresh(it)};refresh("")
    }
    private fun poems(){shell("收藏的诗作");loading("读取收藏…");val token=generation;work({store.favoritePoems() to store.listBooks().associateBy{it.id}}){(items,books)->if(token!=generation)return@work;body.removeAllViews();if(items.isEmpty()){body.addView(ui.text("还没有收藏诗作。在诗集阅读页面的“更多”中收藏。"));return@work};val list=ListView(this);list.adapter=textAdapter(items.map{"${it.title}\n${books[it.bookId]?.title.orEmpty()}"});list.setOnItemClickListener{_,_,i,_->openReader(items[i].bookId,items[i].poemId)};list.setOnItemLongClickListener{_,_,i,_->AlertDialog.Builder(this).setTitle("取消收藏 ${items[i].title}？").setNegativeButton("取消",null).setPositiveButton("取消收藏"){_,_->work({store.togglePoemFavorite(items[i].bookId,items[i].poemId)}){poems()}}.show();true};body.addView(list)}}

    private fun pickImport(){
        if(taskRunning)return
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/json","text/plain","application/octet-stream")).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
        try{startActivityForResult(intent,101)}catch(e:Exception){showError("无法打开文件选择器：${e.message}")}
    }
    private fun pickRestore(){
        try{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),102)}catch(e:Exception){showError("无法打开文件选择器：${e.message}")}
    }
    @Deprecated("Android activity result API") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK){if(requestCode==103){pendingWrite=null;toast("已取消导出，书库未改变。")};return}
        if(requestCode==103){val write=pendingWrite;pendingWrite=null;data?.data?.let{write?.invoke(it)};return}
        val uris=buildList{data?.clipData?.let{clip->for(i in 0 until clip.itemCount)add(clip.getItemAt(i).uri)};data?.data?.let{add(it)}}.distinct()
        if(uris.isEmpty()){showError("没有选中文件。");return}
        if(requestCode==101)prepareImport(uris)else if(requestCode==102)prepareRestore(uris.first())
    }
    private fun fileName(uri:Uri):String=runCatching{contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}.getOrNull()?:uri.lastPathSegment?:"未命名.json"
    private fun readUtf8(uri:Uri,maxBytes:Int,cancelled:()->Boolean={false}):Pair<String,Int>{
        val runtime=Runtime.getRuntime()
        val heap=runtime.maxMemory()
        val headroom=heap-(runtime.totalMemory()-runtime.freeMemory())
        val reserve=maxOf(16L*1024*1024,heap/8)
        val allocationBudget=minOf(heap*65/100,(headroom-reserve).coerceAtLeast(0))
        val stream=contentResolver.openInputStream(uri)?:error("无法读取该文件，请重新选择。")
        var bytes=0
        val counted=object:java.io.FilterInputStream(stream){
            private fun count(n:Int){if(cancelled())throw ImportCancelledException();if(n>0){bytes+=n;require(bytes<=maxBytes){"文件超过 ${maxBytes/1024/1024} MiB 限制，请拆分后重试。"}}}
            override fun read():Int{val value=`in`.read();count(if(value<0)0 else 1);return value}
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int{val n=`in`.read(buffer,offset,length);count(n);return n}
        }
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text=InputStreamReader(counted,decoder).use{reader->buildString{val buffer=CharArray(8192);while(true){
            if(cancelled())throw ImportCancelledException();val n=reader.read(buffer);if(n<0)break
            // UTF-16 decoding, StringBuilder growth/copy and the parsed tree coexist. File byte
            // limits alone cannot protect a 192 MiB heap from a mostly-ASCII 128 MiB backup.
            require((length.toLong()+n)*8+128*1024<=allocationBudget){"设备当前可用内存不足以安全读取此文件，尚未写入书库。${maxBytes/1024/1024} MiB 是格式上限，实际容量还受设备内存限制。请拆分内容、使用较小备份，或关闭其他任务后重试。"}
            append(buffer,0,n)
        }}}
        return text to bytes
    }
    private fun progressScreen(title:String,message:String,canCancel:Boolean=true){
        shell(title);body.removeAllViews();val panel=ui.panel(20);panel.addView(ProgressBar(this));progressText=ui.text(message);panel.addView(progressText)
        if(canCancel)panel.addView(ui.button("取消当前任务"){cancel.set(true);progressText?.text="正在取消；已完成文件会保留，当前文件将回滚…"})
        body.addView(panel)
    }
    private fun progressMessage(message:String){main.post{if(!isDestroyed)progressText?.text=message}}
    private fun prepareImport(uris:List<Uri>){
        if(uris.size>16){showError("每批最多选择 16 个文件，请分批导入。");return}
        taskRunning=true;cancel.set(false);screen="import";conflictChoices.clear();progressScreen("校验导入文件","正在读取文件…")
        val old=importFiles;importFiles=emptyList()
        work({
            old.forEach{it.staged?.delete()}
            var total=0L;val results=mutableListOf<PreviewFile>()
            for((index,uri)in uris.withIndex()){
                if(cancel.get())break
                val name=fileName(uri);progressMessage("正在校验 ${index+1}/${uris.size}：$name")
                try{
                    val(text,size)=readUtf8(uri,Protocol.MAX_FILE_BYTES){cancel.get()};total+=size
                    require(total<=64L*1024*1024){"本批文件累计超过 64 MiB，请分批导入。"}
                    if(cancel.get())break
                    val validation=Protocol.parse(text,name)
                    val problems=validation.errors.map{"${it.fileName}\n${it.path}：${it.message}\n修复：${it.suggestion}"}.toMutableList()
                    validation.document?.getAsJsonArray("books")?.forEachIndexed{bookIndex,item->
                        val book=item.asJsonObject
                        val metadata=book.deepCopy().apply{remove("poems");remove("chapters")}
                        if(metadata.toString().toByteArray(Charsets.UTF_8).size>LibraryStore.MAX_METADATA_BYTES)problems.add("$name\nbooks[$bookIndex]：元数据超过 256 KiB。\n修复：缩短简介、标签或卷目录。")
                        Content.sections(book).forEachIndexed{sectionIndex,section->
                            if(section.toString().toByteArray(Charsets.UTF_8).size>LibraryStore.MAX_SECTION_BYTES)problems.add("$name\nbooks[$bookIndex].${if(book.get("type").asString=="poetry")"poems"else"chapters"}[$sectionIndex]：篇章超过 1 MiB。\n修复：拆分章节或诗作后重新导入。")
                        }
                    }
                    val warnings=validation.warnings.map{"${it.path}：${it.message}"}
                    val books=validation.document?.getAsJsonArray("books")?.map{val b=it.asJsonObject;PreviewBook(b.get("id").asString,b.get("title").asString,b.get("type").asString,Content.sections(b).size)}?:emptyList()
                    val staged=if(validation.valid){File(cacheDir,"import-${UUID.randomUUID()}.json").also{file->file.writer(Charsets.UTF_8).use{compactGson.toJson(validation.document,it)}}}else null
                    results.add(PreviewFile(name,staged,books,problems,warnings))
                }catch(e:ImportCancelledException){break}catch(e:Exception){results.add(PreviewFile(name,null,emptyList(),mutableListOf("$name：${e.message?:"文件读取失败"}\n请检查文件格式、编码与访问权限。"),emptyList()))}
            }
            val duplicates=results.flatMap{f->f.books.map{it.id to f}}.groupBy{it.first}.filter{it.value.size>1}
            duplicates.forEach{(id,occurrences)->occurrences.map{it.second}.distinct().forEach{it.errors.add("同批文件出现重复书籍 ID：$id。请只保留一个版本或更换 ID；受影响文件整份不导入。")}}
            results to store.listBooks()
        },onError={e->taskRunning=false;showError("校验未完成：${e.message}");navigate("shelf")}){(files,books)->taskRunning=false;importFiles=files;allBooks=books;if(cancel.get()){toast("已取消校验，尚未写入任何书籍。");navigate("shelf")}else importPreview()}
    }
    private fun importPreview(){
        screen="import";shell("导入预览");val panel=ui.panel(16)
        panel.addView(ui.text("确认前不会写入书库。已有书籍默认跳过。",15f,true))
        val valid=importFiles.filter{it.staged!=null&&it.errors.isEmpty()}
        val invalid=importFiles.size-valid.size
        panel.addView(ui.text("${importFiles.size} 个文件 · ${valid.sumOf{it.books.size}} 本可导入 · $invalid 个文件有错误",14f))
        val existing=allBooks.map{it.id}.toSet()
        if(valid.isNotEmpty())panel.addView(ui.button(if(invalid>0)"仅导入校验通过的 ${valid.size} 个文件"else"确认导入 ${valid.size} 个文件"){commitImport(valid)})
        panel.addView(ui.button("重新选择文件"){pickImport()})
        val rows=mutableListOf<()->View>()
        importFiles.forEach{file->
            rows.add{ui.text("${file.name} · ${if(file.errors.isEmpty())"校验通过"else"有错误，不会导入"}",19f,true)}
            if(file.errors.isNotEmpty())rows.add{ui.text(file.errors.joinToString("\n\n"),13f).apply{setTextColor(if(ui.dark)Color.rgb(255,180,170)else Color.rgb(150,50,38))}}
            file.books.forEach{b->rows.add{
                ui.card().apply{
                    addView(ui.text("${b.title} · ${type(b.type)} · ${b.sections} ${if(b.type=="poetry")"首"else"章"}",15f))
                    if(b.id in existing&&file.errors.isEmpty()){
                        addView(ui.text("已有相同 ID；更新保留可靠定位的记录，失效笔记仍会保留。",12f))
                        val modes=listOf("skip","update","copy");addView(ui.spinner(listOf("跳过（默认）","更新内容","作为副本导入"),modes.indexOf(conflictChoices[b.id]?:"skip")){conflictChoices[b.id]=modes[it]})
                    }
                }
            }}
            if(file.warnings.isNotEmpty())rows.add{ui.text("以下字段不会保存：\n"+file.warnings.joinToString("\n"),13f)}
        }
        val list=ListView(this);list.addHeaderView(panel,null,false)
        list.adapter=object:BaseAdapter(){override fun getCount()=rows.size;override fun getItem(position:Int)=position;override fun getItemId(position:Int)=position.toLong();override fun getView(position:Int,convert:View?,parent:ViewGroup?)=rows[position]()}
        body.addView(list)
    }
    private fun commitImport(files:List<PreviewFile>){
        val choices=conflictChoices.toMap();val stagedFiles=importFiles.mapNotNull{it.staged};taskRunning=true;cancel.set(false);progressScreen("正在导入","每个文件独立提交；取消会回滚当前文件。")
        work({val reports=mutableListOf<String>();for((index,file)in files.withIndex()){
            if(cancel.get()){reports.add("未处理的 ${files.size-index} 个文件已取消。");break}
            try{
                val staged=checkNotNull(file.staged);require(staged.length()<=40L*1024*1024){"预览缓存异常，请重新选择原文件。"}
                // The source file already passed its byte limit. Canonical default fields may add bytes.
                val validation=Protocol.validate(parseBackup(staged.readText(Charsets.UTF_8)),file.name);check(validation.valid){validation.errors.joinToString("\n"){it.message}}
                val result=store.importFile(validation.document!!,choices,{cancel.get()}){done,total->progressMessage("${index+1}/${files.size} ${file.name}\n书籍 $done/$total，提交前可取消")}
                reports.add("${file.name}：新建 ${result.imported}，更新 ${result.updated}，副本 ${result.copied}，跳过 ${result.skipped}。")
            }catch(e:ImportCancelledException){reports.add("${file.name}：取消，当前文件已回滚。其后 ${files.size-index-1} 个文件未处理。");break}
            catch(e:Exception){reports.add("${file.name}：失败，未保存该文件。${e.message}")}
        };stagedFiles.forEach{it.delete()};reports.joinToString("\n\n")},onError={e->taskRunning=false;showError("导入任务中断：${e.message}。请查看书架，已完成文件仍保留。");navigate("shelf")}){report->taskRunning=false;importFiles=emptyList();screen="shelf";render();AlertDialog.Builder(this).setTitle("实际导入结果").setMessage(report).setPositiveButton("查看书架",null).show()}
    }

    private fun createDocument(name:String,maxBytes:Int=Protocol.MAX_FILE_BYTES,write:(java.io.OutputStream)->Unit){
        pendingWrite={uri->taskRunning=true;progressScreen("正在导出","正在写入所选文件…",false)
            work({val stream=contentResolver.openOutputStream(uri,"wt")?:error("无法写入目标文件。");stream.use{target->
                val bounded=object:java.io.FilterOutputStream(target){var count=0L;override fun write(b:Int){require(++count<=maxBytes){"导出超过 ${maxBytes/1024/1024} MiB，请减少所选书籍并分批导出。"};out.write(b)};override fun write(b:ByteArray,offset:Int,length:Int){count+=length;require(count<=maxBytes){"导出超过 ${maxBytes/1024/1024} MiB，请减少所选书籍并分批导出。"};out.write(b,offset,length)}}
                write(bounded)
            }},onError={e->taskRunning=false;showError("导出失败：${e.message}。目标文件可能不完整，请重新导出。");render()}){taskRunning=false;toast("导出完成。");render()}}
        try{startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,name),103)}catch(e:Exception){pendingWrite=null;showError("无法创建文件：${e.message}")}
    }
    private fun exportBooks(ids:List<String>){
        if(ids.isEmpty()){toast("没有可导出的书籍。");return}
        if(ids.size>Protocol.MAX_BOOKS){showError("一个内容文件最多导出 ${Protocol.MAX_BOOKS} 本书，请在书架批量选择后分批导出。");return}
        createDocument("静读-内容.json"){output->
            val document=store.exportContent(ids);val checked=Protocol.validate(document,"导出内容")
            require(checked.valid){"导出内容超出协议限制，请减少所选书籍后重试。\n"+checked.errors.take(3).joinToString("\n"){it.message}}
            output.writer(Charsets.UTF_8).use{gson.toJson(checked.document,it)}
        }
    }
    private fun copyText(value:String,label:String){(getSystemService(Context.CLIPBOARD_SERVICE)as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label,value));toast("已复制$label")}
    private fun toolsScreen(){shell("模板与备份");val panel=ui.panel(16)
        panel.addView(ui.text("填写自己的书",23f,true));panel.addView(ui.text("下载或复制模板，使用 UTF-8 保存。示例明确标记为示例，只初始化一次，删除后不会自动回来。",14f))
        listOf("诗集最简模板" to "poetry-template.json","小说最简模板" to "novel-template.json","完整诗集示例" to "poetry-example.json","完整小说示例" to "novel-example.json","JSON Schema" to "ebook-library-v1.schema.json").forEach{(title,file)->panel.addView(ui.text(title,17f,true));panel.addView(ui.actions("下载" to {createDocument(file){out->out.write(resource(file).toByteArray(Charsets.UTF_8))}},"复制" to {work({resource(file)}){copyText(it,title)}}))}
        panel.addView(ui.text("导入规则",21f,true));panel.addView(ui.text("单文件 ≤32 MiB；一批 ≤16 个文件、累计 ≤64 MiB。JSON 嵌套 ≤32 层。书籍 ID 唯一，内部 ID 在各书内跨层级唯一。未知字段会警告并忽略。单篇章正文序列化 ≤1 MiB，元数据 ≤256 KiB。内容导出只包含正文；阅读记录请使用完整备份。",14f))
        panel.addView(ui.button("导出全库内容 JSON"){work({store.listBooks().map{it.id}}){exportBooks(it)}})
        panel.addView(ui.text("备份你的阅读生活",23f,true));panel.addView(ui.text("完整备份包含正文、进度、分组、收藏、书签、划线、笔记及全局/单书设置。恢复采用整体替换，开始前会保存当前数据的保护备份。完整备份格式上限 128 MiB；实际可处理容量还受设备可用内存限制，内存不足会提示缩小文件。超限导出会明确失败。",14f))
        panel.addView(ui.button("导出完整备份"){createDocument("静读-完整备份.json",128*1024*1024){out->out.writer(Charsets.UTF_8).use{gson.toJson(store.backup(),it)}}})
        panel.addView(ui.button("选择备份并恢复"){pickRestore()})
        panel.addView(ui.button("导出恢复前保护备份"){chooseRecovery()})
        body.addView(ui.scroll(panel))
    }
    private fun chooseRecovery(){work({File(filesDir,"recovery").listFiles().orEmpty().filter{it.extension=="json"}.sortedByDescending{it.lastModified()}}){files->if(files.isEmpty()){toast("还没有恢复前保护备份。");return@work};AlertDialog.Builder(this).setTitle("选择保护备份").setItems(files.map{it.name}.toTypedArray()){_,index->val file=files[index];createDocument(file.name,128*1024*1024){out->file.inputStream().use{it.copyTo(out)}}}.setNegativeButton("取消",null).show()}}
    private fun prepareRestore(uri:Uri){taskRunning=true;cancel.set(false);progressScreen("校验完整备份","正在读取并核对关联关系…")
        work({val(text,size)=readUtf8(uri,128*1024*1024){cancel.get()};if(cancel.get())throw ImportCancelledException();val backup=parseBackup(text);val errors=store.validateBackup(backup);require(errors.isEmpty()){errors.joinToString("\n")};backup},onError={e->taskRunning=false;showError("恢复尚未开始，原数据未改变。\n${e.message}");navigate("tools")}){backup->
            taskRunning=false;navigate("tools");if(cancel.get()){toast("已取消，原数据未改变。");return@work}
            val books=backup.getAsJsonObject("content").getAsJsonArray("books").size();val notes=backup.getAsJsonArray("annotations").size();val groups=backup.getAsJsonArray("groups").size()
            AlertDialog.Builder(this).setTitle("整体替换恢复？").setMessage("备份包含 $books 本书、$notes 条阅读记录、$groups 个分组。\n\n恢复会整体替换当前书库及设置。开始前自动生成当前数据的可恢复保护备份；失败会回滚。恢复写入阶段不能取消。").setNegativeButton("取消",null).setPositiveButton("确认替换恢复"){_,_->
                taskRunning=true;progressScreen("恢复备份","正在保护现有数据并恢复…",false)
                var restoredFile:File?=null
                work({restoredFile=store.restoreBackup(backup);store.settings()},onError={e->
                    taskRunning=false
                    val committed=restoredFile
                    if(committed!=null){recoveryFile=committed;selected.clear();navigate("shelf");showError("数据已恢复，但界面刷新未完成：${e.message}\n恢复前保护备份仍保存在应用中，可在模板与备份页面导出。")}
                    else{navigate("tools");showError("恢复未完成，原书库未被替换：${e.message}")}
                }){settings->val file=checkNotNull(restoredFile);recoveryFile=file;globalSettings=settings;taskRunning=false;selected.clear();navigate("shelf");AlertDialog.Builder(this).setTitle("恢复完成").setMessage("已恢复 $books 本书及关联记录。恢复前的保护备份已保存在应用中，可立即导出到你选择的位置。").setNegativeButton("稍后",null).setPositiveButton("导出保护备份"){_,_->createDocument(file.name,128*1024*1024){out->file.inputStream().use{it.copyTo(out)}}}.show()}
            }.show()
        }
    }
    /** Strict bounded JSON for the separate backup kind, including duplicate-key rejection. */
    private fun parseBackup(text:String):JsonObject{
        val reader=JsonReader(StringReader(text.removePrefix("\uFEFF"))).apply{strictness=Strictness.STRICT};var nodes=0
        fun read(depth:Int):JsonElement{if(cancel.get())throw ImportCancelledException();require(depth<=48&&++nodes<=3_000_000){"备份超过 48 层或 300 万节点限制。"};return when(reader.peek()){
            JsonToken.BEGIN_OBJECT->{reader.beginObject();JsonObject().also{o->while(reader.hasNext()){val key=reader.nextName();require(!o.has(key)){"重复字段：${reader.path}"};o.add(key,read(depth+1))};reader.endObject()}}
            JsonToken.BEGIN_ARRAY->{reader.beginArray();JsonArray().also{a->while(reader.hasNext())a.add(read(depth+1));reader.endArray()}}
            JsonToken.STRING->JsonPrimitive(reader.nextString());JsonToken.NUMBER->JsonPrimitive(BigDecimal(reader.nextString()));JsonToken.BOOLEAN->JsonPrimitive(reader.nextBoolean());JsonToken.NULL->{reader.nextNull();JsonNull.INSTANCE};else->error("JSON 格式错误：${reader.path}")}}
        reader.use{val value=read(0);require(value.isJsonObject&&reader.peek()==JsonToken.END_DOCUMENT){"备份必须是一个完整 JSON 对象。"};return value.asJsonObject}
    }

    private fun settingsScreen(){shell("全局阅读设置");loading("读取设置…");val token=generation
        work({store.settings()}){initial->if(token!=generation)return@work;var value=initial;val panel=ui.panel(16);val preview=ui.text("山光悦鸟性，潭影空人心。\n设置会立即预览并保存。",value.fontSize).apply{typeface=Typeface.create(value.fontFamily,Typeface.NORMAL);setLineSpacing(0f,value.lineSpacing)};panel.addView(preview);panel.addView(ui.text("适用于未单独设置的书籍。单书覆盖可在阅读设置中恢复为全局。",13f))
            fun save(next:ReadingSettings){value=next;globalSettings=next;preview.textSize=value.fontSize;preview.typeface=Typeface.create(value.fontFamily,Typeface.NORMAL);preview.setLineSpacing(0f,value.lineSpacing);val colors=Ui(this,next.theme);preview.setBackgroundColor(colors.background);preview.setTextColor(colors.foreground);work({store.saveSettings(next)}){}}
            fun choice(title:String,labels:List<String>,values:List<String>,current:String,apply:(String)->ReadingSettings){panel.addView(ui.text(title,16f,true));panel.addView(ui.spinner(labels,values.indexOf(current).coerceAtLeast(0)){save(apply(values[it]))})}
            choice("阅读方式",listOf("纵向连续滚动","横向分页"),listOf("scroll","paged"),value.mode){value.copy(mode=it)}
            choice("主题",listOf("跟随系统","浅色","护眼","深色"),listOf("system","light","sepia","dark"),value.theme){value.copy(theme=it)}
            choice("系统字体",listOf("衬线字体","无衬线字体","等宽字体"),listOf("serif","sans-serif","monospace"),value.fontFamily){value.copy(fontFamily=it)}
            fun slider(title:String,min:Int,max:Int,current:Int,label:(Int)->String,apply:(Int)->ReadingSettings){val text=ui.text("$title · ${label(current)}",16f,true);panel.addView(text);val bar=SeekBar(this).apply{this.max=max-min;progress=(current-min).coerceIn(0,max-min);contentDescription=title};bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(b:SeekBar?,p:Int,user:Boolean){text.text="$title · ${label(p+min)}";if(user){val next=apply(p+min);preview.textSize=next.fontSize;preview.setLineSpacing(0f,next.lineSpacing)}};override fun onStartTrackingTouch(b:SeekBar?){};override fun onStopTrackingTouch(b:SeekBar?){save(apply(bar.progress+min))}});panel.addView(bar)}
            slider("字号",14,48,value.fontSize.toInt(),{"$it sp"}){value.copy(fontSize=it.toFloat())};slider("行距",10,24,(value.lineSpacing*10).toInt(),{"${it/10f} 倍"}){value.copy(lineSpacing=it/10f)};slider("段间距",0,40,value.paragraphSpacing.toInt(),{"$it dp"}){value.copy(paragraphSpacing=it.toFloat())};slider("左右边距",8,64,value.margin.toInt(),{"$it dp"}){value.copy(margin=it.toFloat())}
            fun check(title:String,current:Boolean,apply:(Boolean)->ReadingSettings){panel.addView(CheckBox(this).apply{text=title;setTextColor(ui.foreground);minHeight=ui.dp(48);isChecked=current;setOnCheckedChangeListener{_,checked->save(apply(checked))}})}
            check("阅读时保持屏幕常亮",value.keepAwake){value.copy(keepAwake=it)};check("小说首行缩进",value.indent){value.copy(indent=it)};check("诗歌显示原文",value.showOriginal){value.copy(showOriginal=it)};check("显示拼音",value.showPinyin){value.copy(showPinyin=it)};check("显示逐行译文",value.showTranslation){value.copy(showTranslation=it)};check("显示逐行注释",value.showNotes){value.copy(showNotes=it)};check("显示背景、全诗译文和赏析",value.showAnalysis){value.copy(showAnalysis=it)}
            panel.addView(ui.button("保存并返回书架"){work({store.saveSettings(value)}){globalSettings=value;navigate("shelf")}});body.removeAllViews();body.addView(ui.scroll(panel))
        }
    }
}
