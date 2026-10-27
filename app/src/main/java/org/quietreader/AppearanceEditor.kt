package org.quietreader

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.widget.*
import org.quietreader.core.ReadingSettings
import java.util.Locale

/** A draft palette with live preview; only the explicit save action persists it. */
class AppearanceEditor(
    context:Context,
    initial:ReadingSettings,
    private val onChange:(ReadingSettings)->Unit,
    private val onSave:(ReadingSettings)->Unit,
):LinearLayout(context) {
    private val ui=Ui(context,initial)
    private var value=initial
    private val themes=listOf("system","light","sepia","dark","custom")
    private val preview=FrameLayout(context)
    private val fields=mutableListOf<Pair<Button,()->String>>()
    private val themePicker:Spinner

    init {
        orientation=VERTICAL;setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(24))
        addView(ui.text("选择你喜欢的颜色",23f,true))
        addView(ui.text("先预览，再保存。配色用于书架及使用全局设置的阅读页面，文字会自动适配明暗。",14f))
        addView(ui.text("主题",16f,true))
        themePicker=ui.spinner(listOf("跟随系统","浅色","护眼","深色","自定义"),themes.indexOf(value.theme).coerceAtLeast(0)){index->value=value.copy(theme=themes[index]);changed()}
        themePicker.contentDescription="应用主题";addView(themePicker)
        addView(ui.text("推荐配色",16f,true))
        val presets=listOf(
            Triple("青绿", "#F3F6F1", "#416752"),
            Triple("海蓝", "#EEF4FA", "#265D91"),
            Triple("紫藤", "#F6F1FA", "#765091"),
            Triple("暖橙", "#FFF3E8", "#A65722"),
            Triple("夜蓝", "#151E2D", "#8EBAF4"),
        )
        addView(ui.actions(*presets.map{(name,bg,accent)->name to {
            value=value.copy(theme="custom",customBackground=bg,customSurface=if(name=="夜蓝")"#233047"else"#FFFFFF",customAccent=accent);changed()
        }}.toTypedArray()))
        addView(ui.text("自由调整",16f,true))
        addColor("背景颜色",{value.customBackground}){value=value.copy(theme="custom",customBackground=it)}
        addColor("卡片颜色",{value.customSurface}){value=value.copy(theme="custom",customSurface=it)}
        addColor("按钮颜色",{value.customAccent}){value=value.copy(theme="custom",customAccent=it)}
        addView(ui.text("预览",16f,true));addView(preview,LayoutParams(-1,-2))
        addView(ui.button("保存配色"){onSave(value)},LayoutParams(-1,-2))
        addView(ui.button("恢复默认"){val defaults=ReadingSettings();value=value.copy(theme=defaults.theme,customBackground=defaults.customBackground,customSurface=defaults.customSurface,customAccent=defaults.customAccent);changed()})
        refresh()
    }

    private fun addColor(label:String,read:()->String,write:(String)->Unit){
        val button=ui.button(label){pickColor(label,read()){write(it);changed()}}
        fields.add(button to read);addView(button,LayoutParams(-1,-2).apply{setMargins(0,ui.dp(4),0,ui.dp(4))})
    }
    private fun changed(){onChange(value);refresh()}
    private fun refresh(){
        val selected=themes.indexOf(value.theme).coerceAtLeast(0)
        if(themePicker.selectedItemPosition!=selected)themePicker.setSelection(selected)
        fields.forEach{(button,read)->val color=Color.parseColor(read());button.text="${button.contentDescription}  ${read().uppercase(Locale.ROOT)}";button.setTextColor(Ui.contrastText(color));button.background=GradientDrawable().apply{setColor(color);cornerRadius=ui.dp(10).toFloat();setStroke(ui.dp(1),ui.muted)}}
        val colors=Ui(context,value)
        preview.removeAllViews()
        val sample=colors.panel(16).apply{setBackgroundColor(colors.background);contentDescription="配色预览"}
        sample.addView(colors.text("静读 · 让阅读安静下来",18f,true))
        val card=colors.card()
        card.addView(colors.text("山光悦鸟性，潭影空人心。",19f).apply{setTextColor(colors.surfaceText)})
        card.addView(colors.text("诗集与小说",13f).apply{setTextColor(colors.surfaceMuted)})
        sample.addView(card)
        sample.addView(colors.text("按钮预览",14f,true).apply{setBackgroundColor(colors.accent);setTextColor(colors.accentText);gravity=Gravity.CENTER;minHeight=colors.dp(48)})
        preview.addView(sample)
    }

    private fun pickColor(label:String,initial:String,apply:(String)->Unit){
        var color=Color.parseColor(initial)
        var updating=false
        val panel=ui.panel(16)
        val input=ui.field("#RRGGBB",initial).apply{contentDescription="$label 色值";filters=arrayOf(InputFilter.LengthFilter(7))}
        val swatch=ui.text(initial,18f,true).apply{gravity=Gravity.CENTER;minHeight=ui.dp(64);contentDescription="颜色预览"}
        panel.addView(ui.text("输入六位十六进制色值，或拖动 RGB 滑杆。",14f));panel.addView(input);panel.addView(swatch)
        val sliders=mutableListOf<SeekBar>()
        val labels=mutableListOf<TextView>()
        fun components()=listOf(Color.red(color),Color.green(color),Color.blue(color))
        fun updatePreview(){
            swatch.text=String.format(Locale.ROOT,"#%06X",color and 0xFFFFFF);swatch.setBackgroundColor(color);swatch.setTextColor(Ui.contrastText(color))
            listOf("红 R","绿 G","蓝 B").forEachIndexed{i,name->labels.getOrNull(i)?.text="$name · ${components()[i]}"}
        }
        listOf("红 R","绿 G","蓝 B").forEachIndexed{index,name->
            val heading=ui.text(name,14f);labels.add(heading);panel.addView(heading)
            val slider=SeekBar(context).apply{max=255;progress=components()[index];contentDescription=name}
            sliders.add(slider);panel.addView(slider)
            slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean){
                    if(!fromUser||updating)return
                    color=Color.rgb(sliders[0].progress,sliders[1].progress,sliders[2].progress)
                    updating=true;input.setText(String.format(Locale.ROOT,"#%06X",color and 0xFFFFFF));input.error=null;updating=false;updatePreview()
                }
                override fun onStartTrackingTouch(bar:SeekBar?){}
                override fun onStopTrackingTouch(bar:SeekBar?){}
            })
        }
        fun parsed():String?=input.text.toString().trim().removePrefix("#").takeIf{it.matches(Regex("[0-9a-fA-F]{6}"))}?.let{"#${it.uppercase(Locale.ROOT)}"}
        ui.watch(input){if(!updating)parsed()?.let{color=Color.parseColor(it);updating=true;components().forEachIndexed{i,n->sliders[i].progress=n};updating=false;input.error=null;updatePreview()}}
        updatePreview()
        val dialog=ui.dialog().setTitle(label).setView(ui.scroll(panel)).setNegativeButton("取消",null).setPositiveButton("应用",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val chosen=parsed();if(chosen==null)input.error="请输入 #RRGGBB 格式，例如 #416752。"else{apply(chosen);dialog.dismiss()}
        }}
        dialog.show()
    }
}
