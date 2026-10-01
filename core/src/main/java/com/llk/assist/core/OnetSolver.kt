package com.llk.assist.core

/**
 * 连连看求解：两个同类方块之间找一条最多折两次（0/1/2 折）的连线，
 * 路径只能经过空格或棋盘外一圈。board[r*cols+c] = 类编号，-1 = 空。
 *
 * allowOutside=true（默认）：允许连线绕到棋盘外一圈（该玩法确认支持），
 * 这样远距离/被包围的同类对也能被识别；绕外的路径折数记 2，排序靠后。
 */
class OnetSolver(
    private val rows: Int,
    private val cols: Int,
    private val board: IntArray,
    private val allowOutside: Boolean = true
) {

    private fun passable(r: Int, c: Int): Boolean {
        if (r < 0 || r >= rows || c < 0 || c >= cols) return allowOutside
        return board[r * cols + c] == EMPTY
    }

    /** 共线两点的开区间检查（不含两端点，端点由调用方单独校验）。 */
    private fun clearBetween(r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        if (r1 == r2) {
            val lo = minOf(c1, c2) + 1
            val hi = maxOf(c1, c2) - 1
            for (c in lo..hi) if (!passable(r1, c)) return false
            return true
        }
        if (c1 == c2) {
            val lo = minOf(r1, r2) + 1
            val hi = maxOf(r1, r2) - 1
            for (r in lo..hi) if (!passable(r, c1)) return false
            return true
        }
        return false
    }

    fun connect(ar: Int, ac: Int, br: Int, bc: Int): List<Pt>? {
        // 0 折：直线
        if ((ar == br || ac == bc) && clearBetween(ar, ac, br, bc)) {
            return listOf(Pt(ar, ac), Pt(br, bc))
        }
        // 1 折：一个拐角
        if (passable(ar, bc) && clearBetween(ar, ac, ar, bc) && clearBetween(ar, bc, br, bc)) {
            return listOf(Pt(ar, ac), Pt(ar, bc), Pt(br, bc))
        }
        if (passable(br, ac) && clearBetween(ar, ac, br, ac) && clearBetween(br, ac, br, bc)) {
            return listOf(Pt(ar, ac), Pt(br, ac), Pt(br, bc))
        }
        // 2 折：竖直中线
        for (c in -1..cols) {
            if (c == ac || c == bc) continue
            if (!passable(ar, c) || !passable(br, c)) continue
            if (clearBetween(ar, ac, ar, c) && clearBetween(ar, c, br, c) && clearBetween(br, c, br, bc)) {
                return listOf(Pt(ar, ac), Pt(ar, c), Pt(br, c), Pt(br, bc))
            }
        }
        // 2 折：水平中线
        for (r in -1..rows) {
            if (r == ar || r == br) continue
            if (!passable(r, ac) || !passable(r, bc)) continue
            if (clearBetween(ar, ac, r, ac) && clearBetween(r, ac, r, bc) && clearBetween(r, bc, br, bc)) {
                return listOf(Pt(ar, ac), Pt(r, ac), Pt(r, bc), Pt(br, bc))
            }
        }
        return null
    }

    /** 返回当前所有可消除对，折数少者优先，最多 limit 条。 */
    fun findHints(limit: Int = 12): List<Hint> {
        val byType = HashMap<Int, ArrayList<Pt>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val v = board[r * cols + c]
                if (v >= 0) byType.getOrPut(v) { ArrayList() }.add(Pt(r, c))
            }
        }
        val out = ArrayList<Hint>()
        for ((_, list) in byType) {
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val path = connect(a.r, a.c, b.r, b.c) ?: continue
                    out.add(Hint(a, b, path, path.size - 2))
                }
            }
        }
        out.sortWith(compareBy({ it.turns }, { it.a.r * cols + it.a.c }, { it.b.r * cols + it.b.c }))
        return if (out.size > limit) out.subList(0, limit) else out
    }

    /**
     * 贪心整盘求解：反复取当前折数最少的一对并从棋盘移除，
     * 返回整盘消除序列（自动消按此序列连续执行，无需每对重新识别）。
     */
    fun solveSequence(limit: Int = 48): List<Hint> {
        val work = board.copyOf()
        val out = ArrayList<Hint>()
        var solver = OnetSolver(rows, cols, work, allowOutside)
        var guard = 0
        while (out.size < limit && guard++ < limit * 2) {
            val h = solver.findHints(1).firstOrNull() ?: break
            out.add(h)
            work[h.a.r * cols + h.a.c] = EMPTY
            work[h.b.r * cols + h.b.c] = EMPTY
            solver = OnetSolver(rows, cols, work, allowOutside)
        }
        return out
    }

    companion object {
        const val EMPTY = -1

        /** 障碍物（如木质箱子）：不可消除、不可作为路径。 */
        const val BLOCKED = -2
    }
}
