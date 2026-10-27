package org.quietreader

import android.content.Context
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.*
import org.quietreader.core.ReadingSettings
import kotlin.math.pow

/** Small native view vocabulary shared by the library screens. */
class Ui(val context: Context, settings: ReadingSettings = ReadingSettings()) {
    private val theme=settings.theme
    private val custom=theme=="custom"
    private val night=theme=="dark" || (theme=="system" && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)
    val background=if(custom)Color.parseColor(settings.customBackground)else if(night)Color.rgb(27,30,28)else if(theme=="sepia")Color.rgb(233,226,207)else Color.rgb(248,246,240)
    val surface=if(custom)Color.parseColor(settings.customSurface)else if(night)Color.rgb(39,44,40)else Color.rgb(255,253,247)
    val foreground=if(custom)contrastText(background)else if(night)Color.rgb(230,232,220)else Color.rgb(39,47,40)
    val surfaceText=if(custom)contrastText(surface)else foreground
    val dark=if(custom)foreground==Color.WHITE else night
    val muted=if(custom)foreground else if(night)Color.rgb(169,180,165)else Color.rgb(100,112,100)
    val surfaceMuted=if(custom)surfaceText else muted
    val accent=if(custom)Color.parseColor(settings.customAccent)else if(night)Color.rgb(91,126,105)else Color.rgb(65,103,82)
    val accentText=contrastText(accent)
    fun dialog()=AlertDialog.Builder(ContextThemeWrapper(context,if(dark)android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert))
    companion object {
        /** Choose the larger WCAG contrast ratio, including bright custom button colors. */
        fun contrastText(color:Int):Int {
            fun linear(v:Int):Double {val c=v/255.0;return if(c<=.04045)c/12.92 else ((c+.055)/1.055).pow(2.4)}
            val luminance=.2126*linear(Color.red(color))+.7152*linear(Color.green(color))+.0722*linear(Color.blue(color))
            return if((luminance+.05)/.05>=1.05/(luminance+.05))Color.BLACK else Color.WHITE
        }
    }
    fun dp(value:Int) = (value*context.resources.displayMetrics.density+.5f).toInt()
    fun text(value:String,size:Float=16f,bold:Boolean=false) = TextView(context).apply {
        text=value;textSize=size;setTextColor(this@Ui.foreground);setPadding(dp(4),dp(6),dp(4),dp(6))
        if(bold)typeface=Typeface.create("serif",Typeface.BOLD)
    }
    fun panel(padding:Int=12) = LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(padding),dp(padding),dp(padding),dp(padding))}
    fun row() = LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    fun button(label:String,action:()->Unit) = Button(context).apply {
        text=label;textSize=14f;contentDescription=label;isAllCaps=false;minimumHeight=dp(48);minHeight=dp(48)
        setPadding(dp(14),dp(4),dp(14),dp(4));setTextColor(accentText)
        background=GradientDrawable().apply {setColor(accent);cornerRadius=dp(10).toFloat()}
        layoutParams=LinearLayout.LayoutParams(-2,-2).apply {setMargins(dp(3),dp(4),dp(3),dp(4))}
        setOnClickListener{action()}
    }
    fun field(hint:String,value:String="",multiline:Boolean=false)=EditText(context).apply {
        this.hint=hint;setText(value);textSize=16f;setTextColor(surfaceText);setHintTextColor(surfaceMuted)
        setPadding(dp(12),dp(10),dp(12),dp(10));minimumHeight=dp(48)
        isSingleLine=!multiline;if(multiline){minLines=3;gravity=Gravity.TOP}
        background=GradientDrawable().apply {setColor(surface);setStroke(dp(1),surfaceMuted);cornerRadius=dp(8).toFloat()}
    }
    fun card()=panel().apply {background=GradientDrawable().apply {setColor(surface);cornerRadius=dp(12).toFloat()}}
    fun scroll(view:View)=ScrollView(context).apply {isFillViewport=true;addView(view,FrameLayout.LayoutParams(-1,-2))}
    fun actions(vararg items:Pair<String,()->Unit>)=HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled=false
        addView(row().apply{items.forEach{addView(button(it.first,it.second))}})
    }
    fun spinner(labels:List<String>,selected:Int,changed:(Int)->Unit)=Spinner(context).apply {
        minimumHeight=dp(48)
        backgroundTintList=android.content.res.ColorStateList.valueOf(this@Ui.foreground)
        adapter=object:ArrayAdapter<String>(context,android.R.layout.simple_spinner_dropdown_item,labels){
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View=super.getView(position,convertView,parent).apply{(this as TextView).setTextColor(this@Ui.foreground)}
            override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View=super.getDropDownView(position,convertView,parent).apply{(this as TextView).setTextColor(surfaceText);setBackgroundColor(surface);minimumHeight=dp(48)}
        }
        setSelection(selected.coerceIn(0,(labels.size-1).coerceAtLeast(0)))
        onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            var previous=selected
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){if(previous!=position){previous=position;changed(position)}}
        }
    }
    fun watch(input:EditText,changed:(String)->Unit){input.addTextChangedListener(object:TextWatcher{
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){changed(s?.toString().orEmpty())}
        override fun afterTextChanged(s:Editable?){}
    })}
}
