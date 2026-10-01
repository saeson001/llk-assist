package com.llk.assist.core

import com.llk.assist.core.BoardDetector.FEAT

/**
 * 方块分类：同一图案的贴图像素近乎一致，用“灰度相关 + 颜色直方图交”做两两相似度，
 * Otsu 自适应取阈值，贪心并查集聚类（类大小上限约束），再用宽松阈值回收孤立类。
 * 输出 ids[r*cols+c] = 类编号（-1 表示该格无效）。
 */
object TileClassifier {

    /** 单类方块数量上限（连连看每类通常 2 或 4 个，上限只用于防止病态合并）。 */
    const val MAX_CLASS = 8

    fun classify(det: BoardDetector.Detection): IntArray {
        val n = det.rows * det.cols
        val ids = IntArray(n) { -1 }
        // 障碍物（木板等）标记为 BLOCKED：不可消除、不可作为路径
        val idx = (0 until n).filter {
            val c = det.cells[it]
            c != null && !c.blocked
        }
        for (bi in (0 until n).filter { det.cells[it] != null && det.cells[it]!!.blocked }) {
            ids[bi] = OnetSolver.BLOCKED
        }
        val m = idx.size
        if (m == 0) return ids

        // 两两相似度
        val sims = FloatArray(m * m)
        val flat = FloatArray(m * (m - 1) / 2)
        var fi = 0
        for (i in 0 until m) {
            for (j in i + 1 until m) {
                val s = similarity(det.cells[idx[i]]!!, det.cells[idx[j]]!!)
                sims[i * m + j] = s
                sims[j * m + i] = s
                flat[fi++] = s
            }
        }
        val t = otsu(flat).coerceIn(0.66f, 0.90f)

        val parent = IntArray(m) { it }
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) x = parent[x]
            var y = a
            while (parent[y] != y) {
                val nx = parent[y]
                parent[y] = x
                y = nx
            }
            return x
        }

        fun merge(ra: Int, rb: Int): Boolean {
            val i = find(ra)
            val j = find(rb)
            if (i == j) return false
            parent[j] = i
            return true
        }

        fun groupSize(root: Int): Int {
            var c = 0
            for (i in 0 until m) if (find(i) == root) c++
            return c
        }

        // 主聚类：相似度降序贪心合并
        val pairs = ArrayList<Triple<Float, Int, Int>>(flat.size)
        for (i in 0 until m) {
            for (j in i + 1 until m) {
                if (sims[i * m + j] >= t) pairs.add(Triple(sims[i * m + j], i, j))
            }
        }
        pairs.sortByDescending { it.first }
        for ((_, i, j) in pairs) {
            val ri = find(i)
            val rj = find(j)
            if (ri == rj) continue
            if (groupSize(ri) + groupSize(rj) > MAX_CLASS) continue
            parent[rj] = ri
        }

        // 宽松回收：奇数大小的类按阈值递减策略并入最相似邻类，
        // 直到全部为偶数（游戏规则：每种方块必为偶数个）。
        var decay = 0.97f
        var guard = 0
        while (guard++ < 8) {
            val groups = HashMap<Int, MutableList<Int>>()
            for (i in 0 until m) groups.getOrPut(find(i)) { ArrayList() }.add(i)
            val oddRoots = groups.filter { it.value.size % 2 == 1 }.keys.toHashSet()
            if (oddRoots.isEmpty()) break
            var bestPair: Triple<Float, Int, Int>? = null
            for (i in 0 until m) {
                for (j in i + 1 until m) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri == rj) continue
                    if (ri !in oddRoots && rj !in oddRoots) continue
                    val si = groupSize(ri)
                    val sj = groupSize(rj)
                    if (si % 2 == 0 || sj % 2 == 0) continue // 奇+奇才能合并成偶
                    if (si + sj > MAX_CLASS) continue
                    val s = sims[i * m + j]
                    if (s < t * decay) continue
                    val cur = bestPair
                    if (cur == null || s > cur.first) bestPair = Triple(s, i, j)
                }
            }
            val pick = bestPair
            if (pick == null) {
                decay -= 0.05f
                if (decay < 0.60f) break
                continue
            }
            parent[find(pick.second)] = find(pick.third)
        }

        // 写回：按类大小降序编号
        val roots = HashMap<Int, Int>()
        for (i in 0 until m) roots[find(i)] = 0
        val counts = HashMap<Int, Int>()
        for (i in 0 until m) {
            val r = find(i)
            counts[r] = (counts[r] ?: 0) + 1
        }
        roots.keys.sortedByDescending { counts[it] ?: 0 }.forEachIndexed { k, root -> roots[root] = k }
        for (i in 0 until m) ids[idx[i]] = roots[find(i)] ?: -1
        return ids
    }

    /** 灰度 NCC（gray 已归一化，点积即相关系数）与 HSV 直方图交集的加权组合。 */
    /**
     * 相似度 = 0.55 x 内容掩码 IoU + 0.25 x 内容区 RGB 相关系数 + 0.20 x 内容区直方图交集。
     * 全部只看图标内容，排除所有方块共有的白色底与边框。
     */
    private fun similarity(a: BoardDetector.Cell, b: BoardDetector.Cell): Float {
        val n = FEAT * FEAT
        var inter = 0
        var union = 0
        for (i in 0 until n) {
            val x = a.mask[i]
            val y = b.mask[i]
            if (x && y) inter++
            if (x || y) union++
        }
        val iou = if (union == 0) 0f else inter.toFloat() / union

        var ncc = 0f
        if (union >= 30) {
            var cnt = 0
            var srA = 0f
            var srB = 0f
            var srrA = 0f
            var srrB = 0f
            var srrAB = 0f
            var sgA = 0f
            var sgB = 0f
            var sggA = 0f
            var sggB = 0f
            var sggAB = 0f
            var sbA = 0f
            var sbB = 0f
            var sbbA = 0f
            var sbbB = 0f
            var sbbAB = 0f
            for (i in 0 until n) {
                if (!a.mask[i] && !b.mask[i]) continue
                val ar = a.rgb[i]
                val ag = a.rgb[n + i]
                val ab = a.rgb[2 * n + i]
                val br = b.rgb[i]
                val bg = b.rgb[n + i]
                val bb = b.rgb[2 * n + i]
                cnt++
                srA += ar; srB += br; srrA += ar * ar; srrB += br * br; srrAB += ar * br
                sgA += ag; sgB += bg; sggA += ag * ag; sggB += bg * bg; sggAB += ag * bg
                sbA += ab; sbB += bb; sbbA += ab * ab; sbbB += bb * bb; sbbAB += ab * bb
            }
            ncc = corr(cnt, srA, srB, srrA, srrB, srrAB) +
                    corr(cnt, sgA, sgB, sggA, sggB, sggAB) +
                    corr(cnt, sbA, sbB, sbbA, sbbB, sbbAB)
            ncc /= 3f
        }

        var hs = 0f
        for (k in 0 until 36) hs += minOf(a.hist[k], b.hist[k])

        return 0.55f * iou + 0.25f * ncc + 0.20f * hs
    }

    /** 单通道皮尔逊相关（用原始矩计算）。 */
    private fun corr(cnt: Int, sa: Float, sb: Float, saa: Float, sbb: Float, sab: Float): Float {
        if (cnt == 0) return 0f
        val fa = cnt.toFloat()
        val num = fa * sab - sa * sb
        val denA = fa * saa - sa * sa
        val denB = fa * sbb - sb * sb
        val den = kotlin.math.sqrt(denA * denB)
        if (den < 1e-6f) return 0f
        return (num / den).coerceIn(0f, 1f)
    }

    /** 对 [0,1] 相似度做 Otsu 二分阈值。 */
    private fun otsu(vals: FloatArray): Float {
        if (vals.isEmpty()) return 0.8f
        val bins = 128
        val hist = IntArray(bins)
        for (v in vals) hist[(v * bins).toInt().coerceIn(0, bins - 1)]++
        val total = vals.size.toLong()
        var sumAll = 0L
        for (b in 0 until bins) sumAll += hist[b].toLong() * b
        var wB = 0L
        var sumB = 0L
        var best = -1.0
        var bestB = bins / 2
        for (b in 0 until bins) {
            wB += hist[b]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += hist[b].toLong() * b
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                bestB = b
            }
        }
        return (bestB + 1f) / bins
    }
}
