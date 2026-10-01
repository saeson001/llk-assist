package com.llk.assist.core

import org.junit.Test
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** 测量用例：打印方块逐行“左右边距亮度”剖面，定位面/唇边分界，用于校准数字居中。 */
class MeasureTest {

    @Test
    fun measureTileProfile() {
        val stream = javaClass.classLoader.getResourceAsStream("sample_large.jpg")
            ?: throw AssertionError("找不到 sample_large.jpg")
        val src = ImageIO.read(stream)
        val argb = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = argb.createGraphics()
        g.drawImage(src, 0, 0, null)
        g.dispose()
        val w = argb.width
        val h = argb.height
        val px = IntArray(w * h)
        argb.getRGB(0, 0, w, h, px, 0, w)

        val det = BoardDetector.detect(px, w, h)
            ?: throw AssertionError("未检测到棋盘: ${BoardDetector.lastFailReason}")
        println("棋盘 ${det.rows}x${det.cols} 块=${det.tileCount()}")

        // 断言：数字锚点（面中心 cy）应比 bbox 中心偏上 2-15px（实测约 6px，
        // 因为方块底部有唇边+投影）。这是"数字居中"的回归保护。
        var checked = 0
        for (cell in det.cells) {
            if (cell == null || cell.blocked) continue
            val bboxCy = (cell.y0 + cell.y1) / 2f
            val offset = bboxCy - cell.cy
            println("  方块(${cell.row},${cell.col}) bbox中心y=$bboxCy 面中心y=${cell.cy} 偏上=${"%.1f".format(offset)}px")
            // 现在 cx/cy = bbox 几何中心，offset 应为 0
            assertTrue("面中心应等于 bbox 中心（实测偏移 $offset）", offset == 0f)
            checked++
            if (checked >= 6) break
        }
        assertTrue("应有足够的方块参与断言", checked >= 3)
    }

    private fun assertTrue(msg: String, cond: Boolean) {
        if (!cond) throw AssertionError(msg)
    }
}
