package org.quietreader

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Intercept horizontal drags only; the book list keeps its normal vertical scrolling and clicks. */
class ShelfSwipeLayout(context:Context,private val switch:(Int)->Unit):FrameLayout(context){
    private var startX=0f
    private var startY=0f
    private var horizontal=false
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){startX=event.x;startY=event.y;horizontal=false}
        if(event.actionMasked==MotionEvent.ACTION_MOVE&&abs(event.x-startX)>slop&&abs(event.x-startX)>abs(event.y-startY)*1.5f)horizontal=true
        return horizontal
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_UP){if(abs(event.x-startX)>64*resources.displayMetrics.density&&abs(event.x-startX)>abs(event.y-startY)*1.5f)switch(if(event.x<startX)1 else -1);performClick()}
        return true
    }
    override fun performClick():Boolean=super.performClick()
}
