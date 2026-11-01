package org.quietreader.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.Editable
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.google.gson.JsonObject
import org.quietreader.ReaderApp
import org.quietreader.Ui
import org.quietreader.core.Anchor
import org.quietreader.core.Content
import org.quietreader.core.ReadingSettings
import org.quietreader.data.AnnotationRecord
import org.quietreader.data.SectionSummary
import java.util.concurrent.Executors
import kotlin.math.abs

/** Android text selection is preserved; pagination uses the same fonts and layout as TextView. */
class ReaderActivity : Activity() {
    private val store get() = (application as ReaderApp).store
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var bookId: String
    private var poetry = false
    private var bookTitle = ""
    private var initialized = false
    private var foregroundActive = false
    private var contentRevision = 0
    private var sections = emptyList<SectionSummary>()
    private var settings = ReadingSettings()
    private var singleBookSettings = true
    private var annotations = emptyList<AnnotationRecord>()
    private val sectionCache = linkedMapOf<String, JsonObject>()
    private val scrollPageCache = linkedMapOf<Int, List<ReaderPage>>()
    private val scrollUnits = mutableListOf<ScrollUnit>()
    private val expandedLines = hashSetOf<String>()
    private var pageList = emptyList<ReaderPage>()
    private var pageIndex = 0
    private var sectionIndex = 0
    private var windowStart = 0
    private var windowEnd = 0
    private var busy = false
    private var generation = 0
    private var restoring = false
    private var selecting = false
    private var pendingAnchor: Anchor? = null
    private var lastAnchor: Anchor? = null
    private var searchHighlight: Anchor? = null
    private var foreground = Color.rgb(49, 44, 37)
    private var secondary = Color.rgb(106, 97, 84)
    private var background = Color.rgb(250, 247, 239)
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var host: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var footer: LinearLayout
    private lateinit var loading: ProgressBar
    private val visibleText = mutableListOf<TextBinding>()
    private val saveRunnable = Runnable { persistPosition() }
    private val resizeRunnable:Runnable=object:Runnable {
        override fun run(){
            if(isDestroyed || isFinishing)return
            if(busy){main.postDelayed(this,180);return}
            if(sections.isNotEmpty())rebuild(captureAnchor()?:lastAnchor)
        }
    }
    private var lastWidth = 0
    private var lastHeight = 0

    private data class TextBinding(val view: TextView, val block: ReaderBlock, val start: Int, val end: Int)
    private data class ScrollUnit(val section:Int,val part:Int,val page:ReaderPage)
    private data class Initial(val title: String, val poetry: Boolean, val sections: List<SectionSummary>, val settings: ReadingSettings, val position: Anchor?, val annotations: List<AnnotationRecord>, val revision: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getStringExtra("bookId").orEmpty()
        if (bookId.isEmpty()) { finish(); return }
        createUi()
        runWork({
            val summary = store.listBooks().firstOrNull { it.id == bookId } ?: error("这本书已被删除，请返回书架。")
            Initial(summary.title, summary.type == "poetry", store.sections(bookId), store.settings(bookId), store.position(bookId), store.annotations(bookId),summary.contentRevision)
        }) { initial ->
            bookTitle = initial.title; poetry = initial.poetry; sections = initial.sections
            settings = normalize(initial.settings); annotations = initial.annotations; contentRevision = initial.revision; initialized = true
            val explicitSection = savedInstanceState?.getString("sectionId") ?: intent.getStringExtra("sectionId")
            val explicitContent = savedInstanceState?.getString("contentId") ?: intent.getStringExtra("contentId")
            val explicitOffset = savedInstanceState?.getInt("offset") ?: intent.getIntExtra("offset", 0)
            val anchor = if (explicitSection != null) Anchor(bookId, explicitSection, explicitContent.orEmpty(), explicitOffset,
                savedInstanceState?.getInt("end") ?: intent.getIntExtra("end", explicitOffset)) else initial.position
            if (intent.hasExtra("end") && explicitContent != null) searchHighlight = anchor
            lastAnchor = anchor?.takeUnless { it.orphaned }
            if (anchor?.orphaned == true) toast("原位置已变化，从对应篇章开始阅读。")
            applyTheme()
            if (sections.isEmpty()) { showError("书籍没有可阅读内容。"); return@runWork }
            sectionIndex = sections.indexOfFirst { it.id == anchor?.sectionId }.coerceAtLeast(0)
            val target = if(anchor?.orphaned==true) Anchor(bookId,anchor.sectionId,"") else anchor
            host.post { rebuild(target) }
        }
    }

    private fun createUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        toolbar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("返回") { finish() })
        titleView = TextView(this).apply { textSize = 16f; maxLines = 1; gravity=Gravity.CENTER_VERTICAL; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6),0,dp(6),0) }
        top.addView(titleView, LinearLayout.LayoutParams(0, dp(48), 1f))
        top.addView(button("设置") { showSettings() })
        top.addView(button("更多") { showMore() })
        toolbar.addView(top)
        val actions = LinearLayout(this)
        actions.addView(button("目录") { showContents() })
        actions.addView(button("进度") { showProgress() })
        actions.addView(button("搜索") { showSearch() })
        actions.addView(button("书签") { toggleBookmark() })
        actions.addView(button("笔记") { openNotes() })
        toolbar.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        root.addView(toolbar)
        host = FrameLayout(this)
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(button("上一页") { previousPage() })
        status = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setPadding(dp(4),0,dp(4),0); contentDescription = "阅读进度；点击跳转"; setOnClickListener { showProgress() } }
        footer.addView(status,LinearLayout.LayoutParams(0,dp(48),1f))
        footer.addView(button("下一页") { nextPage() })
        root.addView(footer)
        setContentView(root)
        loading = ProgressBar(this).apply { contentDescription = "正在载入正文" }
        host.addView(loading,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER))
        host.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right-left; val h = bottom-top
            if (w > 0 && h > 0 && (lastWidth != w || lastHeight != h)) {
                val first = lastWidth == 0
                lastWidth = w; lastHeight = h
                if (!first) { main.removeCallbacks(resizeRunnable); main.postDelayed(resizeRunnable,180) }
            }
        }
    }

    private fun button(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text; textSize = 13f; minHeight = dp(48); minimumHeight = dp(48); minWidth = dp(58); minimumWidth = dp(58)
        setPadding(dp(6),0,dp(6),0); contentDescription = text; setOnClickListener { if(!initialized && text!="返回")toast("正在载入书籍，请稍候。")else click() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun spPixels(value:Float)=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,value,resources.displayMetrics)
    private fun toast(message: String) = Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
    private fun showError(message: String) { loading.visibility = View.GONE; AlertDialog.Builder(this).setTitle("阅读提示").setMessage(message).setPositiveButton("知道了",null).show() }
    private fun <T> runWork(task: () -> T, done: (T) -> Unit) {
        worker.execute { try { ReaderPositions.awaitPendingWrites();val result = task(); main.post { if (!isFinishing && !isDestroyed) done(result) } }
        catch (e: Exception) { main.post { if (!isFinishing && !isDestroyed) { busy = false; showError(e.message ?: "操作失败，请重试。") } } } }
    }
    private fun <T> writeWork(task:()->T,done:(T)->Unit){
        ReaderPositions.write(task,{value->main.post{if(foregroundActive && !isFinishing && !isDestroyed)done(value)}},
            {error->main.post{if(foregroundActive && !isFinishing && !isDestroyed)showError(error.message?:"保存失败，请重试。")}})
    }

    private fun loadSection(index: Int): JsonObject {
        val id = sections[index].id
        synchronized(sectionCache) { sectionCache[id]?.let { return it } }
        val section = store.section(bookId,id) ?: error("篇章不存在，请返回目录。")
        synchronized(sectionCache) {
            sectionCache[id] = section
            while(sectionCache.size > 5) sectionCache.remove(sectionCache.keys.first())
        }
        return section
    }

    private fun rebuild(anchor: Anchor? = captureAnchor()) {
        if (sections.isEmpty() || host.width <= 0 || host.height <= 0) return
        val target = sections.indexOfFirst { it.id == anchor?.sectionId }.takeIf { it >= 0 } ?: sectionIndex
        sectionIndex = target; lastAnchor = anchor ?: lastAnchor
        pendingAnchor = anchor
        busy = true; restoring = true
        val request = ++generation
        synchronized(scrollPageCache){scrollPageCache.clear()}
        scrollUnits.clear()
        val configuration = settings
        val expanded = expandedLines.toSet()
        val width = (host.width - dp(settings.margin)*2).coerceAtLeast(dp(64))
        val height = (host.height-dp(24)).coerceAtLeast(dp(64))
        host.removeAllViews(); host.addView(loading,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER)); loading.visibility=View.VISIBLE
        visibleText.clear()
        runWork({
            val json = loadSection(target)
            val blocks = ReaderDocument.blocks(json,poetry,configuration,expanded)
            val pages = ReaderPaginator.paginate(blocks,configuration,width,if(configuration.mode=="paged")height else maxOf(height*2,dp(1200)),resources.displayMetrics.density,resources.displayMetrics.scaledDensity,::spPixels)
            blocks to pages
        }) { (blocks,pages) ->
            if (generation != request) return@runWork
            host.removeAllViews(); visibleText.clear(); busy = false
            if(configuration.mode == "paged") {
                pageList = pages
                pageIndex = pageFor(anchor).coerceIn(0,(pages.size-1).coerceAtLeast(0))
                showPage()
            } else {
                windowStart=target; windowEnd=target
                synchronized(scrollPageCache){scrollPageCache[target]=pages}
                val part=pageFor(anchor,pages).coerceIn(0,pages.lastIndex.coerceAtLeast(0))
                val unit=ScrollUnit(target,part,pages[part]);scrollUnits.add(unit)
                scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(settings.margin),dp(12),dp(settings.margin),dp(12)) }
                content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; setOnClickListener { toggleToolbar() } }
                scroll.addView(content,FrameLayout.LayoutParams(-1,-2)); host.addView(scroll,FrameLayout.LayoutParams(-1,-1))
                content.addView(unitView(unit))
                scroll.setOnScrollChangeListener { _, _, _, _, _ ->
                    if (!restoring && !busy) {
                        schedulePositionSave(); updateStatus()
                        if (scroll.scrollY + scroll.height >= content.height-dp(300)) extendWindow(true)
                        else if (scroll.scrollY < dp(80)) extendWindow(false)
                    }
                }
                scroll.post {
                    restoreScroll(anchor)
                    restoring = false; updateStatus(); schedulePositionSave()
                    if(scroll.scrollY<dp(80) && (part>0 || target>0))extendWindow(false)
                    else if(scroll.scrollY+scroll.height >= content.height-dp(300)) extendWindow(true)
                }
            }
            titleView.text = bookTitle
            pendingAnchor=null
        }
    }

    private fun pageFor(anchor: Anchor?,pages:List<ReaderPage> = pageList): Int {
        if(anchor==null || anchor.contentId.isEmpty()) return 0
        return pages.indexOfFirst { page -> page.slices.any { it.block.contentId==anchor.contentId && it.block.isOriginal && anchor.start>=it.start && (anchor.start<it.end || (it.end==it.block.text.length && anchor.start==it.end)) } }.takeIf{it>=0}
            ?: pages.indexOfFirst { p -> p.slices.any { it.block.contentId==anchor.contentId } }.coerceAtLeast(0)
    }

    private fun unitView(unit:ScrollUnit):LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;tag=unit
        // A few short poems still provide a scrollable window, without advancing by themselves.
        minimumHeight = host.height / 2
        unit.page.slices.forEach { slice ->
            val tv = textView(slice.block,slice.start,slice.end)
            addView(tv,LinearLayout.LayoutParams(-1,slice.height).apply { topMargin=slice.before; bottomMargin=slice.after })
        }
    }

    private fun extendWindow(forward: Boolean,step:Boolean=false) {
        if(busy || settings.mode!="scroll" || sections.isEmpty() || scrollUnits.isEmpty()) return
        val edge=if(forward)scrollUnits.last()else scrollUnits.first()
        val existing=synchronized(scrollPageCache){scrollPageCache[edge.section]}?:return
        val sameSection=if(forward)edge.part<existing.lastIndex else edge.part>0
        val index=if(sameSection)edge.section else edge.section+(if(forward)1 else -1)
        if(index !in sections.indices){if(step)toast(if(forward)"已到全书末尾"else"已经是开头");return}
        val wanted=if(sameSection)edge.part+(if(forward)1 else -1) else if(forward)0 else -1
        if(content.childCount>=3) {
            // Never discard a fragment which is still visible, including auxiliary-only text.
            if(forward && content.getChildAt(0).bottom>scroll.scrollY)return
            if(!forward && content.getChildAt(content.childCount-1).top<scroll.scrollY+scroll.height)return
        }
        busy=true
        val request=generation
        val configuration=settings
        val expanded=expandedLines.toSet()
        val width=(host.width-dp(configuration.margin)*2).coerceAtLeast(dp(64))
        val height=maxOf((host.height-dp(24))*2,dp(1200))
        runWork({
            val pages=synchronized(scrollPageCache){scrollPageCache[index]}?:ReaderPaginator.paginate(ReaderDocument.blocks(loadSection(index),poetry,configuration,expanded),configuration,width,height,resources.displayMetrics.density,resources.displayMetrics.scaledDensity,::spPixels)
            pages
        }) { pages ->
            if(request!=generation) return@runWork
            if(content.childCount>=3 && ((forward && content.getChildAt(0).bottom>scroll.scrollY) || (!forward && content.getChildAt(content.childCount-1).top<scroll.scrollY+scroll.height))){busy=false;return@runWork}
            val beforeY=scroll.scrollY
            restoring=true
            synchronized(scrollPageCache){scrollPageCache[index]=pages}
            val unit=ScrollUnit(index,if(wanted<0)pages.lastIndex else wanted,pages[if(wanted<0)pages.lastIndex else wanted])
            val view=unitView(unit)
            if(forward) {content.addView(view);scrollUnits.add(unit)} else {content.addView(view,0);scrollUnits.add(0,unit)}
            var removedHeight=0
            if(content.childCount>3) {
                val removeAt=if(forward) 0 else content.childCount-1
                val removedView=content.getChildAt(removeAt) as ViewGroup
                if(removeAt==0)removedHeight=removedView.height
                val removedText=(0 until removedView.childCount).map{removedView.getChildAt(it)}.toSet()
                content.removeViewAt(removeAt)
                scrollUnits.removeAt(removeAt)
                visibleText.removeAll { it.view in removedText }
            }
            windowStart=scrollUnits.first().section;windowEnd=scrollUnits.last().section
            synchronized(scrollPageCache){val retain=scrollUnits.map{it.section}.toSet();scrollPageCache.keys.toList().filter{it !in retain}.forEach{scrollPageCache.remove(it)}}
            content.post {
                // Pixels are only a transient insertion adjustment; persisted positions use IDs/offsets.
                scroll.scrollTo(0,beforeY+(if(!forward)view.height else 0)-removedHeight)
                restoring=false;busy=false;updateStatus();schedulePositionSave()
                if(step)scroll.smoothScrollBy(0,((if(forward)1 else -1)*scroll.height*.85f).toInt())
                if(scroll.scrollY+scroll.height>=content.height-dp(100)) extendWindow(true)
            }
        }
    }

    private fun showPage() {
        if(pageList.isEmpty()) return
        host.removeAllViews(); visibleText.clear()
        val page=pageList[pageIndex]
        val pageHost = PageSurface(this,{ forward -> if(forward) nextPage() else previousPage() },{toggleToolbar()})
        pageHost.canSwipe = { !selecting }
        pageHost.setPadding(dp(settings.margin),dp(12),dp(settings.margin),dp(12))
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; isClickable=true;setOnClickListener { toggleToolbar() } }
        page.slices.forEach { slice ->
            val tv=textView(slice.block,slice.start,slice.end)
            column.addView(tv,LinearLayout.LayoutParams(-1,slice.height).apply { topMargin=slice.before;bottomMargin=slice.after })
        }
        // An extremely large accessibility font can make one shaped line taller than the viewport.
        val overflow=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;addView(column,FrameLayout.LayoutParams(-1,-2))}
        pageHost.addView(overflow,FrameLayout.LayoutParams(-1,-1));host.addView(pageHost,FrameLayout.LayoutParams(-1,-1))
        restoring=false;updateStatus();schedulePositionSave()
    }

    private fun textView(block: ReaderBlock,start: Int,end: Int): TextView {
        val value=SpannableString(ReaderPaginator.styledText(block,start,end,settings,resources.displayMetrics.scaledDensity,spPixels(settings.fontSize)))
        if(block.isOriginal) {
            annotations.filter { !it.anchor.orphaned && it.kind!="bookmark" && it.anchor.sectionId==block.sectionId && it.anchor.contentId==block.contentId }.forEach {
                val from=(it.anchor.start-start).coerceAtLeast(0);val to=(it.anchor.end-start).coerceAtMost(value.length)
                if(from<to) {
                    value.setSpan(UnderlineSpan(),from,to,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    value.setSpan(BackgroundColorSpan(if(settings.theme=="dark") 0x665E81AC else 0x55E2B558),from,to,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            searchHighlight?.takeIf { it.sectionId==block.sectionId && it.contentId==block.contentId }?.let {
                val from=(it.start-start).coerceAtLeast(0);val to=(it.end-start).coerceAtMost(value.length)
                if(from<to) value.setSpan(BackgroundColorSpan(0x99E9B949.toInt()),from,to,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return TextView(this).apply {
            text=value;setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,spPixels(ReaderPaginator.size(settings,block.role)));typeface=ReaderPaginator.face(settings,block.role)
            setTextColor(if(block.role in listOf("aux","meta","separator")) secondary else this@ReaderActivity.foreground)
            includeFontPadding=false;setPadding(0,0,0,0);setLineSpacing(0f,settings.lineSpacing)
            textDirection=View.TEXT_DIRECTION_FIRST_STRONG_LTR
            if(android.os.Build.VERSION.SDK_INT>=28)setFallbackLineSpacing(true)
            breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE;hyphenationFrequency=android.text.Layout.HYPHENATION_FREQUENCY_NONE
            if(block.role=="separator") gravity=Gravity.CENTER
            if(block.role=="empty"){
                isClickable=true;isFocusable=true;contentDescription="当前显示设置下没有正文，恢复纯原文"
                setOnClickListener{expandedLines.clear();changeSettings(settings.copy(showOriginal=true,showPinyin=false,showTranslation=false,showNotes=false,showAnalysis=false))}
            }
            if(block.selectable && block.isOriginal) {
                setTextIsSelectable(true)
                customSelectionActionModeCallback=object: ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode,menu: Menu): Boolean {
                        selecting=true;menu.add(0,201,10,"划线");menu.add(0,202,11,"笔记")
                        if(poetry)menu.add(0,203,12,"复制诗行")
                        return true
                    }
                    override fun onPrepareActionMode(mode: ActionMode,menu: Menu)=false
                    override fun onActionItemClicked(mode: ActionMode,item: MenuItem): Boolean {
                        if(item.itemId !in 201..203)return false
                        val a=(selectionStart.coerceAtLeast(0)+start).coerceAtMost(block.original.length)
                        val b=(selectionEnd.coerceAtLeast(0)+start).coerceAtMost(block.original.length)
                        val anchor=selectionAnchor(block,minOf(a,b),maxOf(a,b))
                        when(item.itemId) {201->saveAnnotation(anchor,"highlight","");202->noteEditor(anchor);203->copy(block.original,"诗行")}
                        mode.finish();return true
                    }
                    override fun onDestroyActionMode(mode: ActionMode) { selecting=false }
                }
            }
            if(block.contentId.isNotEmpty()) visibleText.add(TextBinding(this,block,start,end))
            if(block.role!="empty")setOnClickListener {
                if(selecting)return@setOnClickListener
                if(block.role=="poem"){
                    if(expandedLines.contains(block.contentId))expandedLines.remove(block.contentId)else expandedLines.add(block.contentId)
                    rebuild(captureAnchor())
                }else toggleToolbar()
            }
        }
    }

    private fun selectionAnchor(block: ReaderBlock, start: Int,end: Int): Anchor = Anchor(bookId,block.sectionId,block.contentId,start,end,
        block.original.substring(start,end),block.original.substring((start-24).coerceAtLeast(0),start),block.original.substring(end,(end+24).coerceAtMost(block.original.length)))

    private fun captureAnchor(): Anchor? {
        if(restoring || sections.isEmpty())return pendingAnchor ?: lastAnchor
        if(settings.mode=="paged")return pageList.getOrNull(pageIndex)?.slices?.firstOrNull { it.block.contentId.isNotEmpty() }?.let{it.anchor(bookId,if(it.block.isOriginal)it.start else 0)} ?: lastAnchor
        if(!::scroll.isInitialized)return lastAnchor
        val viewport=IntArray(2);scroll.getLocationOnScreen(viewport)
        val top=viewport[1]+scroll.paddingTop
        val bottom=viewport[1]+scroll.height-scroll.paddingBottom
        val position=IntArray(2)
        val binding=visibleText.sortedBy { it.view.getLocationOnScreen(position);position[1] }.firstOrNull {
            it.view.getLocationOnScreen(position);position[1]+it.view.height>top && position[1]<bottom
        } ?: return lastAnchor
        binding.view.getLocationOnScreen(position)
        val line=binding.view.layout?.getLineForVertical((top-position[1]).coerceAtLeast(0)) ?: 0
        val offset=if(binding.block.isOriginal)binding.start+(binding.view.layout?.getLineStart(line) ?: 0)else 0
        return ReaderSlice(binding.block,binding.start,binding.end,0,0,0).anchor(bookId,offset)
    }

    private fun restoreScroll(anchor: Anchor?) {
        if(anchor==null || !::scroll.isInitialized)return
        val target=visibleText.firstOrNull { it.block.isOriginal && it.block.sectionId==anchor.sectionId && it.block.contentId==anchor.contentId && anchor.start>=it.start && (anchor.start<it.end || (anchor.start==it.end && it.end==it.block.original.length)) }
            ?: visibleText.firstOrNull { !it.block.isOriginal && it.block.sectionId==anchor.sectionId && it.block.contentId==anchor.contentId }
            ?: visibleText.firstOrNull { it.block.sectionId==anchor.sectionId } ?: return
        val line=if(target.block.isOriginal)target.view.layout?.getLineForOffset((anchor.start-target.start).coerceIn(0,target.view.text.length)) ?: 0 else 0
        val location=IntArray(2);val parent=IntArray(2)
        target.view.getLocationOnScreen(location);content.getLocationOnScreen(parent)
        scroll.scrollTo(0,location[1]-parent[1]+(target.view.layout?.getLineTop(line) ?: 0))
    }

    private fun updateStatus() {
        val anchor=captureAnchor() ?: lastAnchor
        if(anchor!=null) {
            lastAnchor=anchor
            sections.indexOfFirst { it.id==anchor.sectionId }.takeIf { it>=0 }?.let { sectionIndex=it }
        }
        val section=sections.getOrNull(sectionIndex) ?: return
        status.text=if(settings.mode=="paged") "${pageIndex+1}/${pageList.size} 页 · ${sectionIndex+1}/${sections.size} ${if(poetry)"首" else "章"}\n本篇 ${(chapterProgress(anchor)*100).toInt()}% · 全书 ${(progress(anchor)*100).toInt()}%"
        else "${section.title}\n本篇 ${(chapterProgress(anchor)*100).toInt()}% · 全书 ${(progress(anchor)*100).toInt()}%"
    }

    private fun progress(anchor: Anchor?): Float {
        if(sections.isEmpty())return 0f
        val index=sections.indexOfFirst { it.id==anchor?.sectionId }.takeIf { it>=0 } ?: sectionIndex
        val part=chapterProgress(anchor)
        return ((index+part)/sections.size).coerceIn(0f,1f)
    }
    private fun chapterProgress(anchor:Anchor?):Float {
        if(settings.mode=="paged" && pageList.isNotEmpty())return (pageIndex.toFloat()+1f)/pageList.size
        val last=scrollUnits.lastOrNull()
        val lastPart=last?.let{synchronized(scrollPageCache){scrollPageCache[it.section]?.lastIndex}}
        if(::scroll.isInitialized && sectionIndex==sections.lastIndex && windowEnd==sections.lastIndex && last?.part==lastPart && scroll.scrollY+scroll.height>=content.height+scroll.paddingTop+scroll.paddingBottom-2)return 1f
        val json=synchronized(sectionCache){sectionCache[anchor?.sectionId]}?:return 0f
        val texts=Content.texts(json,if(poetry)"poetry"else"novel")
        val total=texts.values.sumOf{it.length}.coerceAtLeast(1)
        var before=0
        for((id,text) in texts){if(id==anchor?.contentId)return ((before+(anchor?.start?:0)).toFloat()/total).coerceIn(0f,1f);before+=text.length}
        return 0f
    }
    private fun schedulePositionSave(){main.removeCallbacks(saveRunnable);if(foregroundActive)main.postDelayed(saveRunnable,350)}
    private fun persistPosition(force:Boolean=false){
        if(!foregroundActive && !force)return
        val anchor=captureAnchor() ?: lastAnchor ?: return
        if(anchor.contentId.isEmpty())return
        lastAnchor=anchor;val value=progress(anchor)
        ReaderPositions.save(store,anchor,value){e->main.post{if(!isDestroyed)toast("阅读位置保存失败：${e.message ?: "存储不可用"}")}}
    }
    private fun toggleToolbar(){if(selecting)return;val visibility=if(toolbar.visibility==View.VISIBLE)View.INVISIBLE else View.VISIBLE;toolbar.visibility=visibility;footer.visibility=visibility}

    private fun previousPage(){
        if(busy)return
        if(settings.mode=="scroll") { if(::scroll.isInitialized && scroll.scrollY>0)scroll.smoothScrollBy(0,-(scroll.height*.85f).toInt()) else extendWindow(false,true);return }
        if(pageIndex>0){pageIndex--;showPage()} else navigateSection(sectionIndex-1,true)
    }
    private fun nextPage(){
        if(busy)return
        if(settings.mode=="scroll") {if(::scroll.isInitialized && scroll.scrollY+scroll.height<content.height+scroll.paddingTop+scroll.paddingBottom)scroll.smoothScrollBy(0,(scroll.height*.85f).toInt())else extendWindow(true,true);return}
        if(pageIndex<pageList.lastIndex){pageIndex++;showPage()} else navigateSection(sectionIndex+1)
    }
    private fun navigateSection(index: Int,lastPage: Boolean=false){
        if(index !in sections.indices){toast(if(index<0)"已经是开头" else "已到全书末尾");return}
        persistPosition();sectionIndex=index
        val anchor=Anchor(bookId,sections[index].id,"")
        if(!lastPage){rebuild(anchor);return}
        // Load only the previous chapter and target its final original character.
        runWork({ReaderDocument.blocks(loadSection(index),poetry,settings,expandedLines.toSet()).lastOrNull {it.isOriginal}}){block->
            rebuild(if(block==null)anchor else ReaderSlice(block,0,block.text.length,0,0,0).anchor(bookId,(block.text.length-1).coerceAtLeast(0)))
        }
    }

    override fun onPause(){foregroundActive=false;main.removeCallbacks(saveRunnable);persistPosition(true);(application as ReaderApp).readingActive=false;(application as ReaderApp).scheduleSync();super.onPause()}
    override fun onResume(){
        (application as ReaderApp).readingActive=true
        super.onResume()
        foregroundActive=true
        if(!initialized)return
        runWork({
            val summary=store.listBooks().firstOrNull{it.id==bookId}
            if(summary==null)null else Initial(summary.title,summary.type=="poetry",store.sections(bookId),store.settings(bookId),store.position(bookId),store.annotations(bookId),summary.contentRevision)
        }){current->
            if(current==null){toast("这本书已被删除");finish();return@runWork}
            val newSettings=normalize(current.settings)
            // A full backup can replace content while retaining its revision number and IDs.
            // On return, the database is authoritative after all earlier reader writes have flushed.
            val previous=current.position?:Anchor(bookId,current.sections.firstOrNull()?.id.orEmpty(),"")
            synchronized(sectionCache){sectionCache.clear()}
            sections=current.sections;bookTitle=current.title;poetry=current.poetry;contentRevision=current.revision
            titleView.text=bookTitle
            annotations=current.annotations;settings=newSettings
            applyTheme()
            if(previous.orphaned){toast("原位置已变化，从对应篇章开始阅读。");rebuild(Anchor(bookId,previous.sectionId,""))}else rebuild(previous)
        }
    }
    private fun normalize(value:ReadingSettings)=if(value.mode=="page")value.copy(mode="paged")else value
    override fun onSaveInstanceState(outState: Bundle){
        (captureAnchor() ?: lastAnchor)?.let {outState.putString("sectionId",it.sectionId);outState.putString("contentId",it.contentId);outState.putInt("offset",it.start);outState.putInt("end",it.end)}
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy(){main.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy()}

    private fun applyTheme(){
        val custom=if(settings.theme=="custom")Ui(this,settings)else null
        val dark=custom?.dark ?: (settings.theme=="dark" || (settings.theme=="system" && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES))
        background=when {dark->Color.rgb(28,29,28);settings.theme=="sepia"->Color.rgb(231,224,201);else->Color.rgb(250,247,239)}
        foreground=if(dark)Color.rgb(220,219,211)else Color.rgb(49,44,37)
        secondary=if(dark)Color.rgb(160,163,154)else Color.rgb(106,97,84)
        if(custom!=null){background=custom.background;foreground=custom.foreground;secondary=custom.muted}
        root.setBackgroundColor(background);titleView.setTextColor(foreground);status.setTextColor(secondary)
        fun tint(view:View){
            if(view is Button){view.setTextColor(custom?.accentText?:foreground);view.backgroundTintList=android.content.res.ColorStateList.valueOf(custom?.accent?:background)}
            if(view is ViewGroup)for(index in 0 until view.childCount)tint(view.getChildAt(index))
        }
        tint(toolbar);tint(footer)
        window.statusBarColor=background;window.navigationBarColor=background
        window.decorView.systemUiVisibility=if(dark)0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if(settings.keepAwake)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun copy(text: String,label: String){(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label,text));toast("已复制$label")}
    private fun openNotes(){persistPosition();startActivity(Intent().setClassName(this,"org.quietreader.MainActivity").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("screen","notes").putExtra("bookId",bookId))}

    private fun saveAnnotation(anchor: Anchor,kind: String,note: String){
        if(anchor.quote.isEmpty()){toast("请先选择正文文字");return}
        writeWork({store.saveAnnotation(anchor,kind,note);store.annotations(bookId)}){annotations=it;toast(if(kind=="highlight")"已保存划线" else "已保存笔记");rebuild(captureAnchor())}
    }
    private fun noteEditor(anchor: Anchor){
        val input=EditText(this).apply {hint="写下你的想法";minLines=3;gravity=Gravity.TOP;setPadding(dp(20),dp(12),dp(20),dp(12))}
        AlertDialog.Builder(this).setTitle("添加笔记").setMessage(anchor.quote.take(120)).setView(input).setNegativeButton("取消",null)
            .setPositiveButton("保存"){_,_->saveAnnotation(anchor,"note",input.text.toString())}.show()
    }
    private fun toggleBookmark(){
        val anchor=captureAnchor()?.takeIf{it.contentId.isNotEmpty() && it.quote.isNotEmpty()} ?: run{toast("当前位置没有可标记的原文，请切换纯原文或选择正文。");return}
        val existing=annotations.firstOrNull {it.kind=="bookmark" && !it.anchor.orphaned && it.anchor.sectionId==anchor.sectionId && it.anchor.contentId==anchor.contentId && abs(it.anchor.start-anchor.start)<32}
        writeWork({if(existing!=null)store.deleteAnnotation(existing.id)else store.saveAnnotation(anchor,"bookmark","");store.annotations(bookId)}){
            annotations=it;toast(if(existing==null)"书签已保存"else"已删除当前位置书签")
        }
    }

    private fun showMore(){
        if(sections.isEmpty())return
        if(poetry)runWork({store.isPoemFavorite(bookId,sections[sectionIndex].id)}){showMoreDialog(it)}else showMoreDialog(false)
    }
    private fun showMoreDialog(favorite:Boolean){
        val options=mutableListOf(if(poetry)"上一首"else"上一章",if(poetry)"下一首"else"下一章","查看本书书签和笔记")
        if(poetry)options.addAll(listOf(if(favorite)"取消收藏本首"else"收藏本首","复制整首诗","纯原文模式","选择诗行复制"))
        AlertDialog.Builder(this).setTitle("阅读操作").setItems(options.toTypedArray()){_,which->
            when(which){0->navigateSection(sectionIndex-1);1->navigateSection(sectionIndex+1);2->openNotes();3->{val id=sections[sectionIndex].id;writeWork({store.togglePoemFavorite(bookId,id);store.isPoemFavorite(bookId,id)}){toast(if(it)"已收藏本首诗"else"已取消收藏")}};4->runWork({ReaderDocument.poemText(loadSection(sectionIndex))}){copy(it,"整首诗")};5->{expandedLines.clear();changeSettings(settings.copy(showOriginal=true,showPinyin=false,showTranslation=false,showNotes=false,showAnalysis=false))};6->choosePoemLines()}
        }.setNegativeButton("关闭",null).show()
    }

    private fun choosePoemLines(){
        runWork({
            buildList<Pair<Int,String>> {
                loadSection(sectionIndex).getAsJsonArray("stanzas").forEachIndexed{index,stanza->
                    stanza.asJsonObject.getAsJsonArray("lines").forEach{add(index to it.asJsonObject.get("original").asString)}
                }
            }
        }){lines->
            val chosen=BooleanArray(lines.size)
            AlertDialog.Builder(this).setTitle("选择要复制的诗行")
                .setMultiChoiceItems(lines.map{it.second}.toTypedArray(),chosen){_,which,value->chosen[which]=value}
                .setNegativeButton("取消",null).setPositiveButton("复制"){_,_->
                    val selected=lines.filterIndexed{index,_->chosen[index]}
                    if(selected.isEmpty()){toast("未选择诗行");return@setPositiveButton}
                    var stanza=-1
                    val text=buildString{selected.forEach{(index,line)->if(isNotEmpty())append(if(stanza==index)"\n"else"\n\n");append(line);stanza=index}}
                    copy(text,"所选诗行")
                }.show()
        }
    }

    private fun showContents(){
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
        val input=EditText(this).apply {hint=if(poetry)"搜索诗名"else"搜索章节或卷名";isSingleLine=true}
        val list=ListView(this);panel.addView(input);panel.addView(list,LinearLayout.LayoutParams(-1,dp(380)))
        var rows=emptyList<Pair<String,SectionSummary?>>()
        fun update(){
            val query=input.text.toString()
            val matches=sections.filter{it.title.contains(query,true)||it.volumeTitle.contains(query,true)}
            rows=buildList {
                var volume=""
                matches.forEach { section ->
                    if(section.volumeId.isNotEmpty() && section.volumeId!=volume)add(section.volumeTitle to null)
                    volume=section.volumeId;add(section.title to section)
                }
            }
            list.adapter=object:BaseAdapter(){
                override fun getCount()=rows.size
                override fun getItem(position:Int)=rows[position]
                override fun getItemId(position:Int)=position.toLong()
                override fun isEnabled(position:Int)=rows[position].second!=null
                override fun getView(position:Int,convert:View?,parent:ViewGroup?):View=TextView(this@ReaderActivity).apply {
                    text=rows[position].first;textSize=if(rows[position].second==null)14f else 17f
                    setPadding(dp(12),dp(14),dp(12),dp(14));minHeight=dp(48)
                    if(rows[position].second==null)typeface=android.graphics.Typeface.DEFAULT_BOLD
                }
            }
        }
        update();input.addTextChangedListener(watcher {update()})
        val dialog=AlertDialog.Builder(this).setTitle(if(poetry)"诗集目录"else"小说目录").setView(panel).setNegativeButton("关闭",null).create()
        list.setOnItemClickListener{_,_,position,_->rows[position].second?.let {section->dialog.dismiss();navigateSection(sections.indexOfFirst {it.id==section.id})}};dialog.show()
    }
    private fun watcher(action:()->Unit)=object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){action()};override fun afterTextChanged(s:Editable?){} }

    private fun showProgress(){
        if(sections.isEmpty())return
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(8))}
        val label=TextView(this).apply{text="拖动选择全书位置";textSize=16f}
        val slider=SeekBar(this).apply{max=1000;progress=(progress(captureAnchor())*1000).toInt();contentDescription="全书阅读位置"}
        panel.addView(label);panel.addView(slider)
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(bar:SeekBar?,value:Int,user:Boolean){val index=((value/1000f)*sections.size).toInt().coerceAtMost(sections.lastIndex);label.text="${value/10f}% · ${sections[index].title}"};override fun onStartTrackingTouch(b:SeekBar?){};override fun onStopTrackingTouch(b:SeekBar?){} })
        AlertDialog.Builder(this).setTitle("跳转阅读进度").setView(panel).setNegativeButton("取消",null).setPositiveButton("跳转"){_,_->
            val fraction=slider.progress/1000f;val position=fraction*sections.size;val index=position.toInt().coerceAtMost(sections.lastIndex);val local=if(fraction==1f)1f else position-index
            runWork({
                val blocks=ReaderDocument.blocks(loadSection(index),poetry,settings,expandedLines.toSet()).filter{it.isOriginal}
                val length=blocks.sumOf{it.text.length};var remain=(length*local).toInt().coerceAtMost((length-1).coerceAtLeast(0))
                var target:Anchor?=null
                for(block in blocks){if(remain<block.text.length){target=ReaderSlice(block,0,block.text.length,0,0,0).anchor(bookId,remain);break};remain-=block.text.length}
                target?:Anchor(bookId,sections[index].id,"")
            }){rebuild(it)}
        }.show()
    }

    private fun showSearch(){
        val input=EditText(this).apply{hint="输入本书正文关键词";isSingleLine=true;setPadding(dp(20),dp(8),dp(20),dp(8))}
        val dialog=AlertDialog.Builder(this).setTitle("全文搜索").setView(input).setNegativeButton("取消",null).setPositiveButton("搜索",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val query=input.text.toString();if(query.isBlank()){input.error="请输入关键词";return@setOnClickListener}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            runWork({store.search(bookId,query,200)}){hits->
                dialog.dismiss()
                if(hits.isEmpty()){toast("本书中没有找到“$query”");return@runWork}
                AlertDialog.Builder(this).setTitle("${hits.size} 条命中${if(hits.size==200)"（最多显示 200 条）"else""}")
                    .setItems(hits.map{"${it.sectionTitle}\n${it.snippet}"}.toTypedArray()){_,index->searchHighlight=hits[index].anchor;rebuild(hits[index].anchor)}.setNegativeButton("关闭",null).show()
            }
        }};dialog.show()
    }

    private fun changeSettings(value: ReadingSettings){
        val anchor=captureAnchor()?:lastAnchor
        settings=value
        val destination=if(singleBookSettings)bookId else null
        writeWork({store.saveSettings(value,destination);if(destination==null)store.clearBookSettings(bookId);Unit}){}
        applyTheme();rebuild(anchor)
    }

    private fun showSettings(){
        var settingsDialog:AlertDialog?=null
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(16))}
        fun label(value:String)=TextView(this).apply{text=value;textSize=14f;setPadding(0,dp(12),0,dp(4))}.also{panel.addView(it)}
        val preview=TextView(this).apply{text="山光悦鸟性，潭影空人心。\n阅读设置会立即预览并保存。";setPadding(dp(12),dp(12),dp(12),dp(12));textSize=settings.fontSize;typeface=ReaderPaginator.face(settings,"body");setTextColor(this@ReaderActivity.foreground);setBackgroundColor(this@ReaderActivity.background)}
        panel.addView(preview)
        fun refreshPreview(){preview.textSize=settings.fontSize;preview.typeface=ReaderPaginator.face(settings,"body");preview.setLineSpacing(0f,settings.lineSpacing);preview.setTextColor(foreground);preview.setBackgroundColor(background);preview.setPadding(dp(settings.margin),dp(12),dp(settings.margin),dp(12))}
        refreshPreview()
        val scope=CheckBox(this).apply{text="仅用于本书（关闭则保存为全局默认）";isChecked=singleBookSettings;setOnCheckedChangeListener{_,checked->singleBookSettings=checked}}
        panel.addView(scope)
        fun choose(title:String,labels:Array<String>,values:List<String>,current:String,apply:(String)->ReadingSettings){
            label(title);val spinner=Spinner(this);spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,labels);spinner.setSelection(values.indexOf(current).coerceAtLeast(0));var ready=false
            spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(parent:AdapterView<*>?){};override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){if(ready){changeSettings(apply(values[position]));refreshPreview()};ready=true}}
            panel.addView(spinner)
        }
        choose("阅读方式",arrayOf("纵向连续滚动","横向分页"),listOf("scroll","paged"),settings.mode){settings.copy(mode=it)}
        choose("主题",arrayOf("跟随系统","浅色","护眼","深色","自定义配色"),listOf("system","light","sepia","dark","custom"),settings.theme){settings.copy(theme=it)}
        choose("系统字体",arrayOf("衬线字体","无衬线字体","等宽字体","系统默认"),listOf("serif","sans-serif","monospace","sans"),settings.fontFamily){settings.copy(fontFamily=it)}
        fun slider(title:String,min:Int,max:Int,current:Int,format:(Int)->String,apply:(Int)->ReadingSettings){
            val heading=label("$title · ${format(current)}")
            val bar=SeekBar(this).apply{this.max=max-min;progress=current-min;contentDescription=title}
            bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(b:SeekBar?,value:Int,user:Boolean){heading.text="$title · ${format(value+min)}";if(title=="字号")preview.textSize=(value+min).toFloat()}
                override fun onStartTrackingTouch(b:SeekBar?){}
                override fun onStopTrackingTouch(b:SeekBar?){changeSettings(apply(bar.progress+min));refreshPreview()}
            });panel.addView(bar)
        }
        slider("字号",14,48,settings.fontSize.toInt(),{"$it sp"}){settings.copy(fontSize=it.toFloat())}
        slider("行距",10,24,(settings.lineSpacing*10).toInt(),{"${it/10f} 倍"}){settings.copy(lineSpacing=it/10f)}
        slider("段间距",0,40,settings.paragraphSpacing.toInt(),{"$it dp"}){settings.copy(paragraphSpacing=it.toFloat())}
        slider("左右边距",8,64,settings.margin.toInt(),{"$it dp"}){settings.copy(margin=it.toFloat())}
        fun check(title:String,value:Boolean,apply:(Boolean)->ReadingSettings){panel.addView(CheckBox(this).apply{text=title;isChecked=value;setOnCheckedChangeListener{_,enabled->changeSettings(apply(enabled))}})}
        check("阅读时保持屏幕常亮",settings.keepAwake){settings.copy(keepAwake=it)}
        if(!poetry)check("段落首行缩进",settings.indent){settings.copy(indent=it)}
        else {
            label("诗歌辅助内容（点击诗行也可单独展开）")
            check("显示原文",settings.showOriginal){settings.copy(showOriginal=it)}
            check("显示拼音",settings.showPinyin){settings.copy(showPinyin=it)}
            check("显示逐行译文",settings.showTranslation){settings.copy(showTranslation=it)}
            check("显示逐行注释",settings.showNotes){settings.copy(showNotes=it)}
            check("显示背景、全诗译文和赏析",settings.showAnalysis){settings.copy(showAnalysis=it)}
        }
        panel.addView(button("恢复本书为全局设置"){
            val anchor=captureAnchor();writeWork({store.clearBookSettings(bookId);store.settings()}){settings=normalize(it);applyTheme();rebuild(anchor);settingsDialog?.dismiss();showSettings();toast("已恢复全局阅读设置")}
        })
        val scroller=ScrollView(this).apply{addView(panel)}
        settingsDialog=AlertDialog.Builder(this).setTitle("阅读设置").setView(scroller).setPositiveButton("完成",null).create()
        settingsDialog?.show()
    }
}

/** Swipes begin only in whitespace and avoid both selection and Android's back-gesture edge. */
private class PageSurface(context:Context,private val turn:(Boolean)->Unit,private val tap:()->Unit):FrameLayout(context){
    var canSwipe:()->Boolean={true}
    private var downX=0f;private var downY=0f;private var eligible=false
    private val density=resources.displayMetrics.density
    init{isClickable=true;isFocusable=true;contentDescription="显示或隐藏阅读工具栏"}
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean{
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y;eligible=canSwipe() && downX>24*density && downX<width-24*density && blankAt(downX,downY,this)}
            MotionEvent.ACTION_MOVE->{
                if(!canSwipe())eligible=false
                if(eligible && abs(event.x-downX)>36*density && abs(event.x-downX)>abs(event.y-downY)*1.5f)return true
            }
        }
        return super.onInterceptTouchEvent(event)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean{
        if(event.actionMasked==MotionEvent.ACTION_UP && eligible && abs(event.x-downX)>48*density){turn(event.x<downX);eligible=false;return true}
        if(event.actionMasked==MotionEvent.ACTION_UP && eligible){eligible=false;performClick();return true}
        return eligible || super.onTouchEvent(event)
    }
    override fun performClick():Boolean{super.performClick();tap();return true}
    private fun blankAt(x:Float,y:Float,parent:ViewGroup):Boolean {
        for(index in 0 until parent.childCount){
            val child=parent.getChildAt(index)
            if(x<child.left || x>=child.right || y<child.top || y>=child.bottom)continue
            val localX=x-child.left;val localY=y-child.top
            if(child is TextView){
                val layout=child.layout ?: return true
                if(localY>=layout.height)return true
                val line=layout.getLineForVertical(localY.toInt().coerceAtLeast(0))
                return localX<layout.getLineLeft(line)-6*density || localX>layout.getLineRight(line)+6*density
            }
            if(child is ViewGroup)return blankAt(localX+child.scrollX,localY+child.scrollY,child)
        }
        return true
    }
}
