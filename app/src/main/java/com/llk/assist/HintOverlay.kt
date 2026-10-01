package com.llk.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.llk.assist.core.BoardDetector
import com.llk.assist.core.Hint

/**
 * 全屏悬浮提示层：每对可消方块的两个方块中心各画一个相同编号的圆牌，
 * 不画连线。圆牌黑边描底，任何背景下都清晰。该层不可触摸、不抢焦点。
 */
class HintOverlay(
    context: Context,
    private val captureW: Int,
    private val captureH: Int
) : View(context) {

    private var det: BoardDetector.Detection? = null
    private var hints: List<Hint> = emptyList()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // 点击落点闪烁标记：[x, y, 时间戳]（抓帧像素坐标）
    private val tapFlashes = ArrayList<FloatArray>()

    companion object {
        private val COLORS = intArrayOf(
            0xFFFF2D2D.toInt(), 0xFF1E88FF.toInt(), 0xFFFFD600.toInt(), 0xFF00C853.toInt(),
            0xFFFF6D00.toInt(), 0xFFAA00FF.toInt(), 0xFF00B8D4.toInt(), 0xFFFF4081.toInt()
        )
        private const val MAX_DRAW = 8
    }

    fun setResult(d: BoardDetector.Detection, list: List<Hint>) {
        det = d
        hints = list
        invalidate()
    }

    fun clearResult() {
        if (det != null || hints.isNotEmpty()) {
            det = null
            hints = emptyList()
            invalidate()
        }
    }

    /** 自动消点击时在落点画一个 0.6 秒的白色扩散圈，便于核对点击位置。 */
    fun flashTap(x: Float, y: Float) {
        synchronized(tapFlashes) {
            tapFlashes.add(floatArrayOf(x, y, android.os.SystemClock.elapsedRealtime().toFloat()))
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = det

        // 点击落点闪烁（与提示无关，独立绘制；0.6 秒后消失）
        if (tapFlashes.isNotEmpty()) {
            val now = android.os.SystemClock.elapsedRealtime()
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
            }
            synchronized(tapFlashes) {
                tapFlashes.removeAll { now - it[2] > 600 }
                for (f in tapFlashes) {
                    val age = (now - f[2]) / 600f
                    val fx = f[0] * (width / captureW.toFloat())
                    val fy = f[1] * (height / captureH.toFloat())
                    ring.strokeWidth = 6f
                    ring.alpha = ((1f - age) * 255).toInt()
                    canvas.drawCircle(fx, fy, 30f + 40f * age, ring)
                }
            }
            if (tapFlashes.isNotEmpty()) postInvalidateDelayed(80)
        }

        if (d == null) return
        val sx = width / captureW.toFloat()
        val sy = height / captureH.toFloat()

        hints.take(MAX_DRAW).forEachIndexed { idx, hint ->
            val color = COLORS[idx % COLORS.size]
            drawBadge(canvas, d, hint.a, idx + 1, color, sx, sy)
            drawBadge(canvas, d, hint.b, idx + 1, color, sx, sy)
        }

        if (hints.size > MAX_DRAW) {
            textPaint.textSize = d.pitchX * sx * 0.34f
            textPaint.color = 0xFF222222.toInt()
            canvas.drawText(
                "还有 ${hints.size - MAX_DRAW} 组未标",
                d.centerX((d.cols - 1) / 2) * sx,
                d.boardTop * sy - d.pitchY * sy * 0.45f,
                textPaint
            )
        }
    }

    /** 在方块格点中心画编号圆牌：黑底圈 + 彩色圆 + 白描边 + 数字。 */
    private fun drawBadge(
        canvas: Canvas,
        d: BoardDetector.Detection,
        pt: com.llk.assist.core.Pt,
        num: Int,
        color: Int,
        sx: Float,
        sy: Float
    ) {
        // 用方块自身检测出的“浅色面”中心（fcx/fcy，排除底部唇边）；
        // 格点中心仅在该格检测缺失时兜底
        val cell = if (pt.r in 0 until d.rows && pt.c in 0 until d.cols) {
            d.cells[pt.r * d.cols + pt.c]
        } else null
        val cx = (cell?.cx ?: d.centerX(pt.c)) * sx
        val cy = (cell?.cy ?: d.centerY(pt.r)) * sy
        val r = (minOf(d.pitchX, d.pitchY) * 0.30f).coerceIn(30f, 70f)

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = 0xB3000000.toInt()
        canvas.drawCircle(cx, cy, r + 5f, fillPaint)

        fillPaint.color = color
        canvas.drawCircle(cx, cy, r, fillPaint)

        fillPaint.style = Paint.Style.STROKE
        fillPaint.color = Color.WHITE
        fillPaint.strokeWidth = r * 0.16f
        canvas.drawCircle(cx, cy, r - fillPaint.strokeWidth / 2f, fillPaint)

        textPaint.textSize = r * 1.15f
        textPaint.color = Color.WHITE
        val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(num.toString(), cx, ty, textPaint)
    }
}
