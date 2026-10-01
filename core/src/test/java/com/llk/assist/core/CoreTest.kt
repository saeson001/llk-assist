package com.llk.assist.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 用真实游戏截图在 PC 上验证整条流水线：网格检测 -> 方块分类 -> 可消对求解。
 * 纯 JVM 运行，不需要安卓设备。
 */
class CoreTest {

    @Test
    fun pipelineOnSampleScreenshot() {
        val stream = javaClass.classLoader.getResourceAsStream("sample.png")
            ?: throw AssertionError("找不到样例图 sample.png")
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
        println("棋盘: ${det.rows}行 x ${det.cols}列 bbox=(${det.boardLeft},${det.boardTop})-(${det.boardRight},${det.boardBottom})")
        val missing = (0 until det.rows * det.cols).filter { det.cells[it] == null }
            .map { "${it / det.cols},${it % det.cols}" }
        println("缺块: ${missing.joinToString(" ")}")

        val ids = TileClassifier.classify(det)
        val counts = HashMap<Int, Int>()
        for (id in ids) if (id >= 0) counts[id] = (counts[id] ?: 0) + 1
        println(
            "类别数=${counts.size} 分布=" +
                    counts.entries.sortedBy { it.key }.joinToString { "类${it.key}:${it.value}个" }
        )
        val byClass = HashMap<Int, MutableList<String>>()
        ids.forEachIndexed { i, id ->
            if (id >= 0) {
                val cell = det.cells[i]
                val tag = if (cell?.recovered == true) "*" else ""
                byClass.getOrPut(id) { mutableListOf() }.add("(${i / det.cols},${i % det.cols})$tag")
            }
        }
        byClass.toSortedMap().forEach { (k, v) -> println("类$k: ${v.joinToString(" ")}") }

        val solver = OnetSolver(det.rows, det.cols, ids)
        val hints = solver.findHints(60)
        println("可消对=${hints.size}")
        hints.take(12).forEach {
            println("  (${it.a.r},${it.a.c})-(${it.b.r},${it.b.c}) 折=${it.turns} 类型=${ids[it.a.r * det.cols + it.a.c]}")
        }

        assertEquals("样例应为 8x6=48 格", 48, det.rows * det.cols)
        assertEquals("满盘棋盘每格都应有方块", 48, det.cells.count { it != null })
        assertTrue("应存在可消对", hints.isNotEmpty())
        for ((k, v) in counts) {
            assertTrue("类$k 有 $v 个，非偶数", v % 2 == 0)
        }
    }

    @Test
    fun pipelineOnIrregularBoard() {
        // 真机截图：有空槽的不规则棋盘（v1.1.0 沟槽投影法在此翻车成 6x5）。
        // 图上带上一轮绘制的提示线，属于“脏输入”，只做弱断言。
        val stream = javaClass.classLoader.getResourceAsStream("sample_irregular.jpg")
            ?: throw AssertionError("找不到样例图 sample_irregular.jpg")
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
            ?: throw AssertionError("未检测到棋盘")
        println("不规则棋盘: ${det.rows}行 x ${det.cols}列 实心=${det.tileCount()} bbox=(${det.boardLeft},${det.boardTop})-(${det.boardRight},${det.boardBottom})")
        println("  列中心=${det.colCenter.joinToString { it.toInt().toString() }}")
        println("  行中心=${det.rowCenter.joinToString { it.toInt().toString() }}")

        val ids = TileClassifier.classify(det)
        val counts = HashMap<Int, Int>()
        for (id in ids) if (id >= 0) counts[id] = (counts[id] ?: 0) + 1
        println("类别数=${counts.size} 分布=${counts.entries.sortedBy { it.key }.joinToString { "类${it.key}:${it.value}" }}")

        val hints = OnetSolver(det.rows, det.cols, ids).findHints(60)
        println("可消对=${hints.size}")
        hints.take(8).forEach {
            println("  (${it.a.r},${it.a.c})-(${it.b.r},${it.b.c}) 折=${it.turns}")
        }

        assertTrue("应识别为 6 列", det.cols == 6)
        assertTrue("行数应大于 5（旧算法误判 5）", det.rows in 6..9)
        assertTrue("实心方块数应大于 24", det.tileCount() > 24)
        assertTrue("应存在可消对", hints.isNotEmpty())
    }

    @Test
    fun syntheticIrregularBoard() {
        // 合成棋盘：6 列 x 8 行，抽掉整列 2（含 8 块）与另外 2 块，剩 38 块。
        // 整列缺失会触发格点重建（v1.5.1 之前 Detection 列数与中心表不一致导致越界崩溃）。
        val w = 480
        val h = 640
        val px = IntArray(w * h)
        java.util.Arrays.fill(px, 0xFFEDE9C8.toInt()) // 页面浅黄底
        fillRect(px, w, 12, 12, 450, 558, 0xFF7CB342.toInt()) // 棋盘绿板

        val removed = (0 until 8).map { it to 3 }.toSet() + setOf(1 to 0, 5 to 0)
        val palette = intArrayOf(
            0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt(),
            0xFF2E7D32.toInt(), 0xFFD81B60.toInt(), 0xFF00897B.toInt(), 0xFF6D4C41.toInt(),
            0xFF5C6BC0.toInt(), 0xFFFDD835.toInt()
        )
        var k = 0
        var expectTiles = 0
        for (r in 0 until 8) {
            for (c in 0 until 6) {
                if ((r to c) in removed) continue
                val x0 = 40 + 60 * c - 24
                val y0 = 40 + 60 * r - 24
                fillRect(px, w, x0, y0, x0 + 47, y0 + 47, 0xFFFBF6E3.toInt()) // 白底方块
                val color = palette[k / 2 % palette.size] // 每种颜色恰好 2 或 4 块
                k++
                fillRect(px, w, x0 + 14, y0 + 14, x0 + 33, y0 + 33, color) // 图标
                expectTiles++
            }
        }
        assertEquals(38, expectTiles)
        // 把 (5,4) 覆盖成棕色木箱障碍物：无浅色底，应判为不可消除
        val cx0 = 40 + 60 * 4 - 24
        val cy0 = 40 + 60 * 5 - 24
        fillRect(px, w, cx0, cy0, cx0 + 47, cy0 + 47, 0xFFB08850.toInt())
        fillRect(px, w, cx0 + 6, cy0 + 8, cx0 + 41, cy0 + 20, 0xFF8A6238.toInt())
        fillRect(px, w, cx0 + 6, cy0 + 28, cx0 + 41, cy0 + 40, 0xFF8A6238.toInt())
        // 保证一组 0 折相邻对：把 (2,0) 与 (2,1) 的图标重涂成同色
        val ax = 40 + 60 * 0 - 24
        val ay = 40 + 60 * 2 - 24
        fillRect(px, w, ax + 14, ay + 14, ax + 33, ay + 33, 0xFFE53935.toInt())
        val bx2 = 40 + 60 * 1 - 24
        val by2 = 40 + 60 * 2 - 24
        fillRect(px, w, bx2 + 14, by2 + 14, bx2 + 33, by2 + 33, 0xFFE53935.toInt())

        val det = BoardDetector.detect(px, w, h)
            ?: throw AssertionError("合成棋盘未检测到: ${BoardDetector.lastFailReason}")
        println("合成棋盘: ${det.rows}行 x ${det.cols}列 实心=${det.tileCount()}")
        // 行列数必须与中心表一致（v1.5.1 崩溃正是两者不一致）
        assertEquals(det.cols, det.colCenter.size)
        assertEquals(det.rows, det.rowCenter.size)
        // 整列缺失的列 3 应被格点重建，与两侧列保持一个格距（60px）
        assertEquals(60f, det.colCenter[3] - det.colCenter[2], 2f)
        assertEquals(60f, det.colCenter[4] - det.colCenter[3], 2f)
        val ids = TileClassifier.classify(det)
        val hints = OnetSolver(det.rows, det.cols, ids).findHints(20)
        println("合成棋盘可消对=${hints.size}")
        assertEquals(8, det.rows)
        assertEquals(6, det.cols)
        assertEquals(38, det.tileCount())
        // 障碍物：木箱应标记为 BLOCKED，且不参与任何提示
        assertEquals(OnetSolver.BLOCKED, ids[5 * det.cols + 4])
        val touchesCrate = hints.any { h ->
            (h.a.r == 5 && h.a.c == 4) || (h.b.r == 5 && h.b.c == 4)
        }
        assertTrue("障碍物不应出现在提示中", !touchesCrate)
        assertTrue("应存在可消对", hints.isNotEmpty())
    }

    private fun fillRect(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in y0..y1) {
            for (x in x0..x1) {
                px[y * w + x] = color
            }
        }
    }

    @Test
    fun solverRules() {
        // 3x3 小棋盘手工用例：验证 0/1/2 折与“绕棋盘外”
        // . A .
        // B . .
        // . . .
        val board = intArrayOf(
            -1, 0, -1,
            1, -1, -1,
            -1, -1, -1
        )
        val s = OnetSolver(3, 3, board)
        // (0,1) 与 (1,0)：connect 只管几何，L 形可达；类型配对由 findHints 负责
        val p0 = s.connect(0, 1, 1, 0)
        assertTrue("L 形几何可达", p0 != null && p0.size == 3)
        assertTrue("只有不同类型时不应提示", s.findHints().isEmpty())
        // 同类对角且拐角为空：L 形 1 折
        val board2 = intArrayOf(
            0, -1, -1,
            -1, -1, -1,
            -1, -1, 0
        )
        val s2 = OnetSolver(3, 3, board2)
        val p = s2.connect(0, 0, 2, 2)
        assertTrue("对角 L 形应 1 折可达", p != null && p.size == 3)
        // 两个拐角都被占死：1 折不可用，只能走中间通道 2 折
        val board2b = intArrayOf(
            0, -1, 1,
            -1, -1, -1,
            1, -1, 0
        )
        val s2b = OnetSolver(3, 3, board2b)
        val pb = s2b.connect(0, 0, 2, 2)
        assertTrue("拐角被挡应 2 折绕行", pb != null && pb.size == 4)
        // (0,0) 与棋盘外(0,-1)？外部不是方块，不测。测同行直线：
        val board3 = intArrayOf(
            0, -1, 0,
            -1, -1, -1,
            -1, -1, -1
        )
        val s3 = OnetSolver(3, 3, board3)
        val p3 = s3.connect(0, 0, 0, 2)
        assertTrue("同行 0 折", p3 != null && p3.size == 2)
        // 中间被挡（含棋盘外默认不可走）：走棋盘内部的 2 折通路
        val board4 = intArrayOf(
            0, 1, 0,
            -1, 1, -1,
            -1, -1, -1
        )
        val s4 = OnetSolver(3, 3, board4)
        val p4 = s4.connect(0, 0, 0, 2)
        assertTrue("中间被挡应绕行", p4 != null && p4.size >= 3)
    }
}
