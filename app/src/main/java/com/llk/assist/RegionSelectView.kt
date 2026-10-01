package com.llk.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * 区域选择层：拖动手指框出棋盘判定区域；轻点（几乎未拖动）表示恢复全盘。
 * 回调返回屏幕像素坐标的 Rect（null 表示清除选区）。
 */
class RegionSelectView(
    context: Context,
    private val onDone: (Rect?) -> Unit
) : View(context) {

    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false

    private val dimPaint = Paint().apply { color = 0x59000000.toInt() }
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF00E676.toInt()
        strokeWidth = 5f
    }
    private val fillPaint = Paint().apply { color = 0x3300E676 }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        isFakeBoldText = true
    }
    private val rect = RectF()

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                curX = downX
                curY = downY
                dragging = true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                curX = event.x
                curY = event.y
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                curX = event.x
                curY = event.y
                dragging = false
                val w = kotlin.math.abs(curX - downX)
                val h = kotlin.math.abs(curY - downY)
                val result = if (w < 40f || h < 40f) {
                    null // 轻点 = 清除选区
                } else {
                    Rect(
                        minOf(downX, curX).toInt(),
                        minOf(downY, curY).toInt(),
                        maxOf(downX, curX).toInt(),
                        maxOf(downY, curY).toInt()
                    )
                }
                onDone(result)
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(dimPaint.color)
        if (dragging) {
            rect.set(
                minOf(downX, curX), minOf(downY, curY),
                maxOf(downX, curX), maxOf(downY, curY)
            )
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, rectPaint)
        }
        textPaint.color = Color.WHITE
        canvas.drawText("拖动框选棋盘区域；轻点恢复全盘", 30f, 70f, textPaint)
    }
}
