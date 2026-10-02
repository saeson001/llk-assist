package com.llk.assist.core

import kotlin.math.max
import kotlin.math.min

/**
 * 棋盘检测 v2：绿色棋盘定位 -> 棋盘内“非绿色”连通域 = 方块贴图 -> 按方块中心聚类得到行列号。
 * 不再依赖整行/整列沟槽投影，因此支持有空槽的不规则棋盘；每个方块的坐标取自其真实像素 bbox。
 */
object BoardDetector {

    const val FEAT = 32

    class Cell(
        val row: Int,
        val col: Int,
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val cx: Float,
        val cy: Float,
        val mask: BooleanArray,
        val rgb: FloatArray,
        val hist: FloatArray,
        val contentCount: Int,
        val recovered: Boolean,
        val blocked: Boolean
    )

    class Detection(
        val rows: Int,
        val cols: Int,
        val boardLeft: Int,
        val boardTop: Int,
        val boardRight: Int,
        val boardBottom: Int,
        val cells: Array<Cell?>,
        val colCenter: FloatArray,
        val rowCenter: FloatArray,
        val pitchX: Float,
        val pitchY: Float
    ) {
        /** 列 c 的屏幕中心 x；c 可为 -1 或 cols，表示棋盘外一圈（按格距外推）。 */
        fun centerX(c: Int): Float = when {
            c < 0 -> colCenter[0] - pitchX * (-c)
            c >= cols -> colCenter[cols - 1] + pitchX * (c - cols + 1)
            else -> colCenter[c]
        }

        fun centerY(r: Int): Float = when {
            r < 0 -> rowCenter[0] - pitchY * (-r)
            r >= rows -> rowCenter[rows - 1] + pitchY * (r - rows + 1)
            else -> rowCenter[r]
        }

        fun tileCount(): Int = cells.count { it != null }
    }

    private class Comp(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val area: Int) {
        val cx: Float get() = (x0 + x1) / 2f
        val cy: Float get() = (y0 + y1) / 2f
        val wd: Int get() = x1 - x0 + 1
        val ht: Int get() = y1 - y0 + 1
    }

    /** 最近一次 detect 失败的原因（单线程调试用）。 */
    var lastFailReason: String = ""
        private set

    // 上一次成功识别的格点（残局复用：块少时行列信息不足，网格一经确定不会变化）
    private var lastColLat: Lattice? = null
    private var lastRowLat: Lattice? = null

    private fun fail(reason: String): Detection? {
        lastFailReason = reason
        return null
    }

    /**
     * 检测棋盘。
     * region = [x, y, w, h]（可选）：只在该区域内识别。
     * downscale（可选，>=1）：先按该因子盒式降采样再检测，速度更快，
     * 输出坐标一律为全图坐标（内部裁剪/缩放检测后平移缩放回来）。
     */
    fun detect(px: IntArray, w: Int, h: Int, region: IntArray? = null, downscale: Int = 1): Detection? {
        lastFailReason = ""
        // 1. 裁剪
        var src = px
        var rw = w
        var rh = h
        var rx = 0
        var ry = 0
        if (region != null && region.size >= 4) {
            rx = region[0].coerceIn(0, w - 2)
            ry = region[1].coerceIn(0, h - 2)
            rw = region[2].coerceIn(2, w - rx)
            rh = region[3].coerceIn(2, h - ry)
            src = IntArray(rw * rh)
            for (y in 0 until rh) {
                System.arraycopy(px, (y + ry) * w + rx, src, y * rw, rw)
            }
        }
        // 2. 降采样（盒式平均）
        val ds = downscale.coerceAtLeast(1)
        if (ds > 1) {
            val dw = rw / ds
            val dh = rh / ds
            if (dw >= 60 && dh >= 60) {
                val small = IntArray(dw * dh)
                val n = ds * ds
                for (y in 0 until dh) {
                    for (x in 0 until dw) {
                        var rs = 0
                        var gs = 0
                        var bs = 0
                        for (yy in 0 until ds) {
                            for (xx in 0 until ds) {
                                val p = src[(y * ds + yy) * rw + (x * ds + xx)]
                                rs += (p shr 16) and 0xFF
                                gs += (p shr 8) and 0xFF
                                bs += p and 0xFF
                            }
                        }
                        small[y * dw + x] = 0xFF000000.toInt() or
                                ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                    }
                }
                src = small
                rw = dw
                rh = dh
            }
        }
        val d = detectInternal(src, rw, rh) ?: return null
        return shift(d, rx, ry, ds)
    }

    /** 把（裁剪+缩放）检测结果的所有坐标还原为全图坐标：全图 = 偏移 + 检测坐标 × ds。 */
    private fun shift(d: Detection, ox: Int, oy: Int, ds: Int): Detection {
        val cells = arrayOfNulls<Cell>(d.cells.size)
        for (i in d.cells.indices) {
            val c = d.cells[i] ?: continue
            cells[i] = Cell(
                c.row, c.col,
                ox + c.x0 * ds, oy + c.y0 * ds, ox + (c.x1 + 1) * ds - 1, oy + (c.y1 + 1) * ds - 1,
                ox + c.cx * ds, oy + c.cy * ds, c.mask, c.rgb, c.hist, c.contentCount, c.recovered, c.blocked
            )
        }
        val colC = FloatArray(d.cols) { ox + d.colCenter[it] * ds }
        val rowC = FloatArray(d.rows) { oy + d.rowCenter[it] * ds }
        return Detection(
            d.rows, d.cols, ox + d.boardLeft * ds, oy + d.boardTop * ds,
            ox + (d.boardRight + 1) * ds - 1, oy + (d.boardBottom + 1) * ds - 1,
            cells, colC, rowC, d.pitchX * ds, d.pitchY * ds
        )
    }

    private fun detectInternal(px: IntArray, w: Int, h: Int): Detection? {
        // 检测区域过小直接失败
        if (w < 60 || h < 60) return fail("区域过小 ${w}x${h}")
        // 1. 绿色掩码
        val mask = BooleanArray(w * h)
        for (i in mask.indices) {
            val p = px[i]
            if (isGreen((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)) mask[i] = true
        }

        // 2. 最大绿色连通域 = 棋盘（边框 + 内部衬底连成一片），拿到搜索范围
        var bestSize = 0
        var bx0 = 0
        var by0 = 0
        var bx1 = 0
        var by1 = 0
        var bestLabel = 0
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        var cur = 0
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            cur++
            var sp = 0
            stack[sp++] = start
            label[start] = cur
            var size = 0
            var x0 = Int.MAX_VALUE
            var y0 = Int.MAX_VALUE
            var x1 = 0
            var y1 = 0
            while (sp > 0) {
                val p = stack[--sp]
                size++
                val x = p % w
                val y = p / w
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                if (x > 0 && mask[p - 1] && label[p - 1] == 0) {
                    label[p - 1] = cur; stack[sp++] = p - 1
                }
                if (x < w - 1 && mask[p + 1] && label[p + 1] == 0) {
                    label[p + 1] = cur; stack[sp++] = p + 1
                }
                if (y > 0 && mask[p - w] && label[p - w] == 0) {
                    label[p - w] = cur; stack[sp++] = p - w
                }
                if (y < h - 1 && mask[p + w] && label[p + w] == 0) {
                    label[p + w] = cur; stack[sp++] = p + w
                }
            }
            if (size > bestSize) {
                bestSize = size
                bx0 = x0; by0 = y0; bx1 = x1; by1 = y1
                bestLabel = cur
            }
        }
        var bw = bx1 - bx0 + 1
        var bh = by1 - by0 + 1
        if (bestSize == 0 || bw < w * 0.30f || bh < h * 0.20f) {
            return fail("棋盘连通域过小 bestSize=$bestSize bw=$bw bh=$bh")
        }

        // 2.5 按行/列密度收拢 bbox：棋盘的每行/列都有大量绿像素，
        // 而残留的旧提示线、角标等细长绿色杂物没有；取最大连续密集段。
        val rc = IntArray(h)
        val cc = IntArray(w)
        for (i in label.indices) {
            if (label[i] == bestLabel) {
                rc[i / w]++
                cc[i % w]++
            }
        }
        val runY = largestRun(rc, (bw * 0.05f).toInt(), (bw * 0.5f).toInt())
            ?: return fail("行密度收拢失败")
        val runX = largestRun(cc, (bh * 0.05f).toInt(), (bh * 0.5f).toInt())
            ?: return fail("列密度收拢失败")
        bx0 = runX[0]; bx1 = runX[1]
        by0 = runY[0]; by1 = runY[1]
        bw = bx1 - bx0 + 1
        bh = by1 - by0 + 1
        if (bw < w * 0.30f || bh < h * 0.20f) {
            return fail("收拢后棋盘过小 bw=$bw bh=$bh")
        }

        // 3. 棋盘内非绿色连通域 = 方块
        val comps = tileComponents(px, w, bx0, by0, bx1, by1)
        if (comps.isEmpty()) return fail("棋盘内无方块连通域")
        val maxArea = comps.maxOf { it.area }
        val cand = comps.filter { it.area > maxArea * 0.12f }
        // 残局支持：块少是合法状态（最后几块时 cand/tiles 天然 <8、覆盖率天然 <20%），
        // 有历史格点缓存时不再因"过少/覆盖率"拒绝，格点拟合失败则复用缓存网格——
        // 网格一经确定不会变化，1.9.20 日志实锤残局 8 块被"覆盖率过低"拒收，
        // 最后两块只能手点
        val endgame = cand.size < 8 && lastColLat != null && lastRowLat != null
        if (cand.size < 8 && !endgame) return fail("候选方块过少 ${cand.size}")
        val medArea = median(cand.map { it.area.toFloat() })
        // 注意：不再要求方块数为偶数——个别组件异常变奇数时直接否决会整体失败，
        // 奇偶性由 TileClassifier 的回收步骤利用游戏规则修复
        val tiles = cand.filter { it.area >= medArea * 0.40f && it.area <= medArea * 1.9f }
        if (tiles.size < 8 && !endgame) {
            return fail("面积过滤后方块过少 tiles=${tiles.size} cand=${cand.size} medArea=$medArea")
        }
        val tileW = median(tiles.map { it.wd.toFloat() })
        val tileH = median(tiles.map { it.ht.toFloat() })
        if (tileW < 8f || tileH < 8f) return fail("方块尺寸异常 ${tileW}x${tileH}")

        // 4. 行列等差格点拟合：聚类中心受贴图阴影影响会偏移，且整列全绿贴图
        // 被棋盘衬底吸收后会导致列缺失、后续列错位。按首尾距离定格距生成
        // 完整格点阵，可补出中间缺失的空行/空列，并把噪声平均掉。
        val colClusters = clusterCenters(tiles.map { it.cx }.sorted(), tileW * 0.5f)
        val rowClusters = clusterCenters(tiles.map { it.cy }.sorted(), tileH * 0.5f)
        var colLat = if (colClusters.size >= 2) fitLattice(colClusters) else null
        var rowLat = if (rowClusters.size >= 2) fitLattice(rowClusters) else null
        if (endgame && (colLat == null || rowLat == null)) {
            // 残局块太少拉不出行列等差：直接复用上次成功的格点
            colLat = lastColLat
            rowLat = lastRowLat
        }
        if (colLat == null) return fail("列格点拟合失败")
        if (rowLat == null) return fail("行格点拟合失败")
        val cols = colLat.centers.size
        val rows = rowLat.centers.size
        if (cols !in 3..10 || rows !in 3..14) {
            return fail("行列数异常 rows=$rows cols=$cols")
        }
        // 覆盖率下限只在无历史格点时启用（首次学习防幻影帧拟合假网格）；
        // 有缓存后残局低覆盖是合法状态
        if (tiles.size < rows * cols * 0.2f && lastColLat == null) {
            return fail("覆盖率过低 tiles=${tiles.size} grid=${rows}x$cols")
        }
        val pitchX = colLat.pitch
        val pitchY = rowLat.pitch
        if (pitchX <= 0f || pitchY <= 0f) return fail("格距异常")

        // 5. 每格至多一块（同格冲突保留面积大的），逐格提取特征
        val slot = HashMap<Int, Comp>()
        for (t in tiles) {
            val c = Math.round((t.cx - colLat.centers[0]) / pitchX)
            val r = Math.round((t.cy - rowLat.centers[0]) / pitchY)
            if (c < 0 || c >= cols || r < 0 || r >= rows) continue
            if (kotlin.math.abs(t.cx - colLat.centers[c]) > pitchX * 0.35f) continue
            if (kotlin.math.abs(t.cy - rowLat.centers[r]) > pitchY * 0.35f) continue
            val key = r * cols + c
            val old = slot[key]
            if (old == null || t.area > old.area) slot[key] = t
        }
        val cells = arrayOfNulls<Cell>(rows * cols)
        for ((key, t) in slot) {
            cells[key] = extractCell(px, w, key / cols, key % cols, t.x0, t.y0, t.x1, t.y1)
        }

        // 5.5 空槽回捞：偏绿贴图（生菜、钱袋等）的连通域可能经抗锯齿并入棋盘衬底而缺失。
        // 网格几何此时已定，若该格内非绿像素占比可观，则按格矩形提取方块。
        val hw = (pitchX * 0.42f).toInt()
        val hh = (pitchY * 0.42f).toInt()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val key = r * cols + c
                if (slot.containsKey(key) || cells[key] != null) continue
                // 注意用格点中心（可能与原始聚类中心不一致：整列/整行缺失时格点已重建）
                val x0 = (colLat.centers[c].toInt() - hw).coerceIn(bx0, bx1)
                val x1 = (colLat.centers[c].toInt() + hw).coerceIn(bx0, bx1)
                val y0 = (rowLat.centers[r].toInt() - hh).coerceIn(by0, by1)
                val y1 = (rowLat.centers[r].toInt() + hh).coerceIn(by0, by1)
                var nn = 0
                var tot = 0
                for (y in y0..y1) {
                    for (x in x0..x1) {
                        val p = px[y * w + x]
                        if (!isGreen((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)) nn++
                        tot++
                    }
                }
                if (tot > 0 && nn >= tot * 0.20f) {
                    // 收紧到非绿像素 bbox，与连通域裁剪口径一致，避免带入绿色衬底
                    var tx0 = Int.MAX_VALUE
                    var ty0 = Int.MAX_VALUE
                    var tx1 = -1
                    var ty1 = -1
                    for (y in y0..y1) {
                        for (x in x0..x1) {
                            val p = px[y * w + x]
                            if (!isGreen((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)) {
                                if (x < tx0) tx0 = x
                                if (x > tx1) tx1 = x
                                if (y < ty0) ty0 = y
                                if (y > ty1) ty1 = y
                            }
                        }
                    }
                    if (tx1 > tx0 && ty1 > ty0) {
                        cells[key] = extractCell(px, w, r, c, tx0, ty0, tx1, ty1, recovered = true)
                    }
                }
            }
        }

        val bl = slot.values.minOf { it.x0 }
        val bt = slot.values.minOf { it.y0 }
        val br = slot.values.maxOf { it.x1 }
        val bb = slot.values.maxOf { it.y1 }
        // 必须传格点中心（尺寸与 rows/cols 一致）；传原始聚类数组会造成
        // “列数与中心表不一致”，后续平移/绘制/点击都会越界
        lastColLat = colLat
        lastRowLat = rowLat
        return Detection(rows, cols, bl, bt, br, bb, cells, colLat.centers, rowLat.centers, pitchX, pitchY)
    }

    /** 棋盘 bbox 内的“非绿色”连通域。先做一次腐蚀，断开方块间 1-2px 的抗锯齿粘连。 */
    private fun tileComponents(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): ArrayList<Comp> {
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val ng = BooleanArray(bw * bh)
        for (gy in 0 until bh) {
            for (gx in 0 until bw) {
                val p = px[(y0 + gy) * w + (x0 + gx)]
                ng[gy * bw + gx] = !isGreen((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            }
        }
        val er = BooleanArray(bw * bh)
        for (gy in 1 until bh - 1) {
            for (gx in 1 until bw - 1) {
                val i = gy * bw + gx
                er[i] = ng[i] && ng[i - 1] && ng[i + 1] && ng[i - bw] && ng[i + bw]
            }
        }
        val taken = er
        val stack = IntArray(bw * bh)
        val out = ArrayList<Comp>()
        for (base in taken.indices) {
            if (!taken[base]) continue
            var sp = 0
            stack[sp++] = base
            taken[base] = false
            var cx0 = Int.MAX_VALUE
            var cy0 = Int.MAX_VALUE
            var cx1 = 0
            var cy1 = 0
            var area = 0
            while (sp > 0) {
                val q = stack[--sp]
                val qx = q % bw
                val qy = q / bw
                area++
                if (qx < cx0) cx0 = qx
                if (qx > cx1) cx1 = qx
                if (qy < cy0) cy0 = qy
                if (qy > cy1) cy1 = qy
                if (qx > 0 && taken[q - 1]) {
                    taken[q - 1] = false; stack[sp++] = q - 1
                }
                if (qx < bw - 1 && taken[q + 1]) {
                    taken[q + 1] = false; stack[sp++] = q + 1
                }
                if (qy > 0 && taken[q - bw]) {
                    taken[q - bw] = false; stack[sp++] = q - bw
                }
                if (qy < bh - 1 && taken[q + bw]) {
                    taken[q + bw] = false; stack[sp++] = q + bw
                }
            }
            // 面积按腐蚀前 bbox 记（外扩 1px 抵消腐蚀）
            out.add(
                Comp(
                    x0 + cx0 - 1, y0 + cy0 - 1, x0 + cx1 + 1, y0 + cy1 + 1, area
                )
            )
        }
        return out
    }

    /** 值不低于 th 的最长连续段，长度不足 minLen 视为无。 */
    private fun largestRun(cnt: IntArray, th: Int, minLen: Int): IntArray? {
        var bs = -1
        var blen = 0
        var s = -1
        for (i in 0..cnt.size) {
            val ok = i < cnt.size && cnt[i] >= th
            if (ok && s < 0) s = i
            if (!ok && s >= 0) {
                if (i - s > blen) {
                    blen = i - s
                    bs = s
                }
                s = -1
            }
        }
        return if (blen >= minLen) intArrayOf(bs, bs + blen - 1) else null
    }

    /** 一维聚类：相邻中心间距超过 gap 视为新列/新行，返回各类中心。 */
    private fun clusterCenters(sorted: List<Float>, gap: Float): FloatArray {
        val out = ArrayList<Float>()
        var start = 0
        for (i in 1..sorted.size) {
            if (i == sorted.size || sorted[i] - sorted[i - 1] > gap) {
                var s = 0f
                for (j in start until i) s += sorted[j]
                out.add(s / (i - start))
                start = i
            }
        }
        return out.toFloatArray()
    }

    private class Lattice(val centers: FloatArray, val pitch: Float)

    /**
     * 把一维聚类中心拟合为等差格点，带离群剔除：
     * 单个聚类中心被贴图阴影拉偏时，先按当前格点剔除残差过大的离群点再重拟合，
     * 而不是整体失败。格点数由首尾距离决定，中间缺整列/整行时自动补出空格点。
     */
    private fun fitLattice(clustersIn: FloatArray): Lattice? {
        var clusters = clustersIn
        var guard = 0
        while (guard++ < 3) {
            if (clusters.size < 2) return null
            val lat = fitOnce(clusters) ?: return null
            val keep = clusters.filter { c ->
                val k = Math.round((c - lat.centers[0]) / lat.pitch)
                k in lat.centers.indices && kotlin.math.abs(c - lat.centers[k]) <= lat.pitch * 0.33f
            }
            if (keep.size == clusters.size) return lat
            clusters = keep.toFloatArray()
        }
        return if (clusters.size >= 2) fitOnce(clusters) else null
    }

    private fun fitOnce(clusters: FloatArray): Lattice? {
        if (clusters.size < 2) return null
        val diffs = ArrayList<Float>()
        for (i in 0 until clusters.size - 1) diffs.add(clusters[i + 1] - clusters[i])
        var pitch = median(diffs)
        val minD = diffs.min()
        if (minD < pitch * 0.7f) pitch = minD
        if (pitch <= 0f) return null
        var n = Math.round((clusters[clusters.size - 1] - clusters[0]) / pitch).toInt() + 1
        if (n < clusters.size) n = clusters.size
        if (n <= 0) return null
        val fitted = (clusters[clusters.size - 1] - clusters[0]) / (n - 1)
        if (fitted <= 0f) return null
        val centers = FloatArray(n)
        for (k in 0 until n) centers[k] = clusters[0] + k * fitted
        return Lattice(centers, fitted)
    }

    private fun median(vals: List<Float>): Float {
        if (vals.isEmpty()) return 0f
        val s = vals.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    private fun extractCell(
        px: IntArray,
        w: Int,
        r: Int,
        c: Int,
        tx0: Int,
        ty0: Int,
        tx1: Int,
        ty1: Int,
        recovered: Boolean = false
    ): Cell? {
        if (tx1 <= tx0 || ty1 <= ty0) return null
        val tw = tx1 - tx0 + 1
        val th = ty1 - ty0 + 1

        // 双线性重采样到 FEAT x FEAT 的 RGB
        val n = FEAT * FEAT
        val rgb = FloatArray(n * 3)
        for (fy in 0 until FEAT) {
            val sy = ty0 + (fy + 0.5f) * th / FEAT - 0.5f
            val y0i = sy.toInt().coerceIn(ty0, ty1)
            val y1i = (y0i + 1).coerceAtMost(ty1)
            val wy = (sy - y0i).coerceIn(0f, 1f)
            for (fx in 0 until FEAT) {
                val sx = tx0 + (fx + 0.5f) * tw / FEAT - 0.5f
                val x0i = sx.toInt().coerceIn(tx0, tx1)
                val x1i = (x0i + 1).coerceAtMost(tx1)
                val wx = (sx - x0i).coerceIn(0f, 1f)
                val p00 = px[y0i * w + x0i]
                val p10 = px[y0i * w + x1i]
                val p01 = px[y1i * w + x0i]
                val p11 = px[y1i * w + x1i]
                val rr = lerp(lerp((p00 shr 16) and 0xFF, (p10 shr 16) and 0xFF, wx), lerp((p01 shr 16) and 0xFF, (p11 shr 16) and 0xFF, wx), wy)
                val gg = lerp(lerp((p00 shr 8) and 0xFF, (p10 shr 8) and 0xFF, wx), lerp((p01 shr 8) and 0xFF, (p11 shr 8) and 0xFF, wx), wy)
                val bb = lerp(lerp(p00 and 0xFF, p10 and 0xFF, wx), lerp(p01 and 0xFF, p11 and 0xFF, wx), wy)
                val o = fy * FEAT + fx
                rgb[o] = rr
                rgb[n + o] = gg
                rgb[2 * n + o] = bb
            }
        }

        // 背景色 = 32x32 边缘两圈均值；与之差异大的像素视为“图标内容”
        var bgr = 0f
        var bgg = 0f
        var bbb = 0f
        var bn = 0
        for (fy in 0 until FEAT) {
            for (fx in 0 until FEAT) {
                if (fx > 1 && fx < FEAT - 3 && fy > 1 && fy < FEAT - 3) continue
                val o = fy * FEAT + fx
                bgr += rgb[o]
                bgg += rgb[n + o]
                bbb += rgb[2 * n + o]
                bn++
            }
        }
        bgr /= bn
        bgg /= bn
        bbb /= bn

        // 障碍物判定：普通方块的底圈是浅米色（高亮度、低饱和）；
        // 木质箱子/木板没有浅色底，底圈偏暗偏棕，视为不可消除的障碍物。
        val ringMax = maxOf(bgr, maxOf(bgg, bbb))
        val ringMin = minOf(bgr, minOf(bgg, bbb))
        val ringV = ringMax / 255f
        val ringS = if (ringMax <= 0f) 1f else (ringMax - ringMin) / ringMax
        val isBlocked = ringV < 0.82f || ringS > 0.45f

        val cmask = BooleanArray(n)
        var content = 0
        for (i in 0 until n) {
            val dr = rgb[i] - bgr
            val dg = rgb[n + i] - bgg
            val db = rgb[2 * n + i] - bbb
            if (dr * dr + dg * dg + db * db > 3600f) { // 颜色距离阈值 60
                cmask[i] = true
                content++
            }
        }
        // 障碍物是低对比度目标（条纹与底色接近），无内容也接受——它只会被标记为 BLOCKED
        if (content < n * 0.05f && !isBlocked) return null

        // 视觉中心 = bbox 几何中心（最简单可靠：始终在检测到的方块范围内居中）
        val fcx = (tx0 + tx1) / 2f
        val fcy = (ty0 + ty1) / 2f

        // 直方图只统计内容像素并归一化，去掉共有背景的干扰
        val hist = FloatArray(36)
        for (i in 0 until n) {
            if (cmask[i]) addHist(hist, rgb[i], rgb[n + i], rgb[2 * n + i])
        }
        if (content <= 0 && !isBlocked) return null
        if (content > 0) {
            val cntF = content.toFloat()
            for (i in 0 until 36) hist[i] = hist[i] / cntF
        }
        // 圆牌与点击落点 = 浅色面行带的垂直中心（视觉正中）
        return Cell(r, c, tx0, ty0, tx1, ty1, fcx, fcy, cmask, rgb, hist, content, recovered, isBlocked)
    }

    private fun lerp(a: Int, b: Int, t: Float): Float = a + (b - a) * t

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun addHist(hist: FloatArray, r: Float, g: Float, b: Float) {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        if (mx <= 0f) return
        val v = mx / 255f
        val s = (mx - mn) / mx
        if (v < 0.15f) return
        val d = mx - mn
        var hh: Float = when {
            mx == r -> 60f * ((g - b) / d)
            mx == g -> 60f * (2f + (b - r) / d)
            else -> 60f * (4f + (r - g) / d)
        }
        if (hh < 0) hh += 360f
        val hb = (hh / 30f).toInt().coerceIn(0, 11)
        val sb = when {
            s < 0.33f -> 0
            s < 0.66f -> 1
            else -> 2
        }
        hist[hb * 3 + sb] += 1f
    }

    fun isGreen(r: Int, g: Int, b: Int): Boolean {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        if (mx == 0) return false
        val v = mx / 255f
        val s = (mx - mn) / mx.toFloat()
        if (v < 0.25f || s < 0.15f) return false
        val d = mx - mn
        var hh: Float = when {
            mx == r -> 60f * ((g - b).toFloat() / d)
            mx == g -> 60f * (2f + (b - r).toFloat() / d)
            else -> 60f * (4f + (r - g).toFloat() / d)
        }
        if (hh < 0) hh += 360f
        return hh in 60f..180f
    }
}
