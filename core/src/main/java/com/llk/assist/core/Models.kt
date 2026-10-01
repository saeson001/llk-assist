package com.llk.assist.core

/** 棋盘坐标点，r/c 可为 -1 或 rows/cols，表示棋盘外一圈（连线允许绕到棋盘外）。 */
data class Pt(val r: Int, val c: Int)

/** 一组可消除的方块对：a、b 为方块位置，path 为连线的途径点（含首尾），turns 为折角数。 */
data class Hint(val a: Pt, val b: Pt, val path: List<Pt>, val turns: Int)
