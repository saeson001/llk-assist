package com.llk.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** 全屏单击捕获层：显示提示文字，捕获用户的一次点击坐标。 */
class TapCaptureView(
    context: Context,
    private val prompt: String,
    private val onTap: (Float, Float) -> Unit
) : View(context) {

    private val dimPaint = Paint().apply { color = 0x73000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 44f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
            onTap(event.x, event.y)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(dimPaint.color)
        textPaint.color = Color.WHITE
        canvas.drawText(prompt, width / 2f, height / 3f, textPaint)
        textPaint.textSize = 32f
        textPaint.color = 0xFFDDDDDD.toInt()
        canvas.drawText("（点击后自动继续）", width / 2f, height / 3f + 60f, textPaint)
    }
}
