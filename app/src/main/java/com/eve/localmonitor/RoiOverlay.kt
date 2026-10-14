package com.eve.localmonitor
import android.content.Context
import android.graphics.*
import android.view.*

class RoiOverlay(c: Context, a: android.util.AttributeSet?=null): View(c,a) {
    val roi = RectF(0.15f,0.15f,0.85f,0.85f); private var mode=0; private var sx=0f; private var sy=0f
    
    // 锁定开关：为 true 时禁止任何拖动和调整
    var isLocked = false

    override fun onDraw(c: Canvas){ 
        super.onDraw(c); 
        val l=roi.left*width; 
        val t=roi.top*height; 
        val r=roi.right*width; 
        val b=roi.bottom*height
        val shade=Paint().apply{color=0x55000000}; 
        c.drawRect(0f,0f,width.toFloat(),height.toFloat(),shade); 
        shade.color=Color.TRANSPARENT; 
        c.drawRect(l,t,r,b,shade)
        
        // 锁定后红框颜色变为黄色且隐藏右下角拖动圆点
        val p=Paint().apply{style=Paint.Style.STROKE;strokeWidth=5f;color=if(isLocked) Color.YELLOW else Color.RED}; 
        c.drawRect(l,t,r,b,p)
        
        if(!isLocked) {
            p.style=Paint.Style.FILL; 
            c.drawCircle(r,b,18f,p)
        }
    }
    
    override fun onTouchEvent(e: MotionEvent): Boolean { 
        // 如果已锁定，直接拦截触摸事件，红框无法移动
        if (isLocked) return false 

        val x=e.x/width; 
        val y=e.y/height; 
        when(e.action){
            MotionEvent.ACTION_DOWN->{
                sx=x;sy=y; 
                mode=if(Math.hypot((x-roi.right).toDouble(),(y-roi.bottom).toDouble())<.08)2 else if(roi.contains(x,y))1 else 0; 
                return true
            }
            MotionEvent.ACTION_MOVE->{
                if(mode==1){
                    val dx=x-sx;val dy=y-sy
                    roi.left=(roi.left+dx).coerceIn(0f,roi.right-.1f)
                    roi.right=(roi.right+dx).coerceIn(roi.left+.1f,1f)
                    roi.top=(roi.top+dy).coerceIn(0f,roi.bottom-.1f)
                    roi.bottom=(roi.bottom+dy).coerceIn(roi.top+.1f,1f)
                    sx=x;sy=y
                }else if(mode==2){
                    roi.right=x.coerceIn(roi.left+.1f,1f)
                    roi.bottom=y.coerceIn(roi.top+.1f,1f)
                }
                invalidate();
                return true
            }
            MotionEvent.ACTION_UP->{
                mode=0;
                return true
            }
        }
        return true
    }
}
