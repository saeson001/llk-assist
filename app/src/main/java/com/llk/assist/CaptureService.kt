package com.llk.assist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.ToneGenerator
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.llk.assist.core.BoardDetector
import com.llk.assist.core.Hint
import com.llk.assist.core.OnetSolver
import com.llk.assist.core.TileClassifier
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台服务：持有 MediaProjection 录屏，抓帧后调用纯 JVM 核心算法，
 * 结果画到悬浮窗路径层，并在控制面板文字栏给出提示。
 */
class CaptureService : Service() {

    companion object {
        const val CH_ID = "llk_assist"
        const val EXTRA_CODE = "projection_code"
        const val EXTRA_RESULT = "projection_result"

        @Volatile
        var instance: CaptureService? = null
            private set

        @Volatile
        var lastForegroundPkg: String? = null
    }

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureW = 0
    private var captureH = 0
    private var pixels: IntArray? = null

    private lateinit var wm: WindowManager
    private lateinit var mainHandler: Handler
    private lateinit var workThread: HandlerThread
    private lateinit var workHandler: Handler

    private var overlay: HintOverlay? = null
    private var panel: LinearLayout? = null
    private var statusText: TextView? = null
    private var btnPath: Button? = null

    private var autoPlay = false
    private var showPaths = false
    private var lastAnalyzedSum = -1L
    private var lastPeekChecksum = -1L
    private var lastAnalyzeAt = 0L
    private var lastHintCount = -1
    private var detectFails = 0
    // 本局网格签名计数（用于过滤动画中的幻影误检帧）
    private val gridCounts = HashMap<String, Int>()
    private var gridWaits = 0
    // 稳定门：规划帧的棋盘区签名 + 第二帧缓冲 + 稳定重试计数
    private var lastBoardSig: FloatArray? = null
    private var pixels2: IntArray? = null
    private var planStableRetries = 0
    // 规划期连续无可用帧计数（画面静止/投影无输出时避免无声卡死）
    private var nullFrames = 0
    private var gestureFails = 0
    private var aiConfigLogged = false
    private var prevObservedSum = -1L
    private var lastIds: IntArray? = null

    // ---- 自动消状态机：IDLE→识别规划(PLANNING)→暂停(PAUSED)→继续→消除(EXECUTING)→循环 ----
    private enum class AutoPhase { IDLE, PLANNING, PAUSED, EXECUTING }
    @Volatile
    private var phase = AutoPhase.IDLE
    private var gamePaused = false
    private var seqQueue: List<Hint> = emptyList()
    private var seqIdx = 0
    private var seqDet: BoardDetector.Detection? = null
    private var emptyRounds = 0
    // 上轮实际规划的块数 / 规划签名 / 连续无效果轮数（块数突变检查、卡死检测）
    private var lastPlanTileCount = 0
    private var lastPlanSig: String? = null
    private var stallRounds = 0
    // 自动消代际令牌：开关切换/进入新一轮时 +1。所有异步回调持有发起时的 epoch，
    // 执行时若 ≠ 当前值说明此链已被新流取代，直接放弃——
    // 根治"两条规划/执行流并发"（日志实锤：200ms 内两次规划、同一坐标 70ms 内被点两次，
    // 游戏端选中又取消，表现为除第一轮外棋盘停滞不动）
    @Volatile
    private var apEpoch = 0

    // 暂停/继续按钮坐标（屏幕像素）；-1 = 未校准
    private var pauseX = -1
    private var pauseY = -1
    private var resumeX = -1
    private var resumeY = -1
    private var tapCaptureView: View? = null
    private var btnPlay: Button? = null

    // 判定区域（抓帧像素坐标）；null = 全盘
    private var region: android.graphics.Rect? = null
    private var regionView: View? = null

    // 工作目录 / 缓存 / 日志 / AI
    private var lastCacheAt = 0L
    private var lastDet: BoardDetector.Detection? = null
    private var lastLocal: List<Hint> = emptyList()
    private var lastAi: List<Hint> = emptyList()
    private val aiExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val autoRunnable = object : Runnable {
        override fun run() {
            workHandler.post { autoTick() }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        mainHandler = Handler(Looper.getMainLooper())
        workThread = HandlerThread("llk-work").also { it.start() }
        workHandler = Handler(workThread.looper)
        LlkLog.init(this)
        LlkDir.ensureConfigTemplate(this)
        loadButtonCalibration()
        // 日志带版本号：不同 build 的日志可区分（此前排障时无法确认用户装的是哪版）
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        LlkLog.write("lifecycle", "服务启动 v$ver，工作目录 ${LlkDir.describe(this)}")
        installCrashLogger()
        startForegroundWithType()
    }

    /** 任何未捕获异常都先写入 LLKZS/logs/crash.log 再交给系统，便于定位闪退。 */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                LlkLog.write("crash", "线程 ${t.name} 崩溃：${android.util.Log.getStackTraceString(e)}")
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection == null && intent != null) {
            // EXTRA_CODE 是 Activity 的 resultCode，授权成功时为 -1（RESULT_OK），需原样传给
            // getMediaProjection；只能用“缺 extra”判断非法，不能用 code < 0
            val code = intent.getIntExtra(EXTRA_CODE, Int.MIN_VALUE)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT)
            }
            if (code == Int.MIN_VALUE || data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            setupProjection(code, data)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithType() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在录屏并监测棋盘")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    private fun setupProjection(code: Int, data: Intent) {
        val proj = try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            mainHandler.post {
                Toast.makeText(this, "录屏初始化失败：${e.message}", Toast.LENGTH_LONG).show()
            }
            stopSelf()
            return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopSelf() }
            }
        }, mainHandler)

        val size = screenSize()
        captureW = size.x
        captureH = size.y
        reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 4)
        vdisplay = proj.createVirtualDisplay(
            "llk-capture", captureW, captureH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, workHandler
        )
        mainHandler.post {
            try {
                addOverlayViews()
                status("就绪：进入游戏后点“识别”或“自动消”")
                // 识别循环常驻：每秒检测画面变化并刷新数字
                mainHandler.postDelayed(autoRunnable, 600)
            } catch (e: Exception) {
                Toast.makeText(this, "悬浮窗创建失败：${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }
    }

    private fun screenSize(): android.graphics.Point {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            android.graphics.Point(b.width(), b.height())
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    // ---------- 悬浮窗 ----------

    private fun addOverlayViews() {
        overlay = HintOverlay(this, captureW, captureH)
        val op = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        op.gravity = Gravity.TOP or Gravity.START
        wm.addView(overlay, op)
        // 数字显示默认关：初始隐藏，点面板【数字】按钮开启
        overlay?.visibility = if (showPaths) View.VISIBLE else View.GONE

        panel = buildPanel()
        val pp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        pp.gravity = Gravity.TOP or Gravity.START
        pp.x = 40
        pp.y = 120
        wm.addView(panel, pp)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Long, radiusDp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color.toInt())
        d.cornerRadius = radiusDp * resources.displayMetrics.density
        return d
    }

    private fun buildPanel(): LinearLayout {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundBg(0xD9222222L, 10f)
        }
        statusText = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            text = "初始化..."
            // 面板尺寸恒定（用户要求）：状态文字固定单行、超宽省略，
            // 宽度略小于按钮行 → 面板大小完全由按钮行决定，不再随提示文字变大变小
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(dp(185), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun mkBtn(label: String, onClick: () -> Unit): Button =
            Button(ctx).apply {
                text = label
                textSize = 12f
                setPadding(dp(8), 0, dp(8), 0)
                minimumWidth = 0
                minWidth = 0
                setOnClickListener { onClick() }
            }
        row.addView(mkBtn("识别") {
            analyzeNow()
        })
        row.addView(mkBtn("选区") { startRegionSelect() })
        row.addView(mkBtn("校准") { startPauseCalibration() })
        row2.addView(mkBtn("自动消") { toggleAutoPlay() })
        btnPath = mkBtn("数字:关") {
            showPaths = !showPaths
            btnPath?.text = if (showPaths) "数字:开" else "数字:关"
            overlay?.visibility = if (showPaths) View.VISIBLE else View.GONE
        }
        row2.addView(btnPath)
        row2.addView(mkBtn("退出") { stopSelf() })
        root.addView(statusText)
        root.addView(row)
        root.addView(row2)

        // 面板可拖动（按住文字区或空白处拖动，按钮不受影响）
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = (v.layoutParams as WindowManager.LayoutParams).x
                    startY = (v.layoutParams as WindowManager.LayoutParams).y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (dragging || dx * dx + dy * dy > 100) {
                        dragging = true
                        val lp = v.layoutParams as WindowManager.LayoutParams
                        lp.x = startX + dx
                        lp.y = startY + dy
                        wm.updateViewLayout(v, lp)
                        true
                    } else false
                }
                else -> dragging
            }
        }
        return root
    }

    private fun status(s: String) {
        // 任意线程都可调用：统一切主线程更新面板文字
        mainHandler.post { statusText?.text = s }
    }

    // ---------- 抓帧与分析 ----------

    /** 手动/无障碍触发：强制完整识别。 */
    fun analyzeNow() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzeAt < 350) return
        lastAnalyzeAt = now
        workHandler.post { runFullCycle(force = true) }
    }

    private fun ensureBuffer(w: Int, h: Int): IntArray {
        var px = pixels
        if (px == null || px.size != w * h) {
            px = IntArray(w * h)
            pixels = px
        }
        return px
    }

    private fun ensureBuffer2(w: Int, h: Int): IntArray {
        var px = pixels2
        if (px == null || px.size != w * h) {
            px = IntArray(w * h)
            pixels2 = px
        }
        return px
    }

    /** 棋盘区分块均值签名（用于帧间稳定性比较，抗图标小变化、对掉落/填充敏感）。 */
    private fun frameSig(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
        val cx = 24
        val cy = 16
        val out = FloatArray(cx * cy * 3)
        val bw = ((x1 - x0 + 1) / cx).coerceAtLeast(2)
        val bh = ((y1 - y0 + 1) / cy).coerceAtLeast(2)
        for (by in 0 until cy) {
            for (bx in 0 until cx) {
                val bx0 = x0 + bx * bw
                val by0 = y0 + by * bh
                val bx1 = minOf(bx0 + bw - 1, x1)
                val by1 = minOf(by0 + bh - 1, y1)
                var rs = 0f
                var gs = 0f
                var bs = 0f
                var n = 0
                var y = by0
                while (y <= by1) {
                    var x = bx0
                    while (x <= bx1) {
                        val p = px[y * w + x]
                        rs += (p shr 16) and 0xFF
                        gs += (p shr 8) and 0xFF
                        bs += p and 0xFF
                        n++
                        x += 3
                    }
                    y += 3
                }
                val o = (by * cx + bx) * 3
                if (n > 0) {
                    out[o] = rs / n
                    out[o + 1] = gs / n
                    out[o + 2] = bs / n
                }
            }
        }
        return out
    }

    /** 两签名的差异块占比（0=完全一致；掉落/填充动画会显著高于闲时微动）。 */
    private fun sigDiffRatio(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 1f
        var diff = 0
        var i = 0
        while (i < a.size) {
            val d = kotlin.math.abs(a[i] - b[i]) +
                    kotlin.math.abs(a[i + 1] - b[i + 1]) +
                    kotlin.math.abs(a[i + 2] - b[i + 2])
            if (d > 90f) diff++
            i += 3
        }
        return diff / (a.size / 3f)
    }

    /** 不隐藏提示层抓一帧算校验和，只用于快速判断画面是否变化。 */
    private fun peekChecksum(): Long? {
        val img = try {
            reader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null
        try {
            val w = img.width
            val h = img.height
            val px = ensureBuffer(w, h)
            return try {
                fillPixels(img, px)
                fastChecksum(px, w, h)
            } catch (e: Exception) {
                // 投影切换/缓冲失效等导致的坏帧：直接丢弃
                LlkLog.write("capture", "帧读取失败：${e.message}")
                null
            }
        } finally {
            img.close()
        }
    }

    /**
     * 完整识别周期。
     * force=false（自动模式）时先做快速比对：画面没变化就不隐藏提示层，
     * 避免每秒闪烁；只有棋盘真的变了才走“隐藏 -> 抓干净帧 -> 分析 -> 恢复”。
     */
    private fun runFullCycle(force: Boolean, ep: Int = apEpoch) {
        val peek = peekChecksum()
        if (peek != null) {
            if (!force && peek == lastPeekChecksum) return
            lastPeekChecksum = peek
        }
        mainHandler.post { overlay?.visibility = View.INVISIBLE }
        workHandler.postDelayed({
            try {
                val img: Image? = try {
                    reader?.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                if (img != null) {
                    nullFrames = 0
                    try {
                        val w = img.width
                        val h = img.height
                        val px = ensureBuffer(w, h)
                        try {
                            fillPixels(img, px)
                            val sum = fastChecksum(px, w, h)
                            val changed = sum != lastAnalyzedSum
                            prevObservedSum = sum
                            lastAnalyzedSum = sum
                            if (force || changed) {
                                val ok = analyzeBoard(px, w, h, ep)
                                // 规划阶段续链——必须确认本次识别成功、且本链未被新流取代
                                // 才规划，否则会用旧棋盘模型重复规划同一批坐标
                                if (ok && phase == AutoPhase.PLANNING && ep == apEpoch) onBoardAnalyzed(ep)
                            }
                        } catch (e: Exception) {
                            // 坏帧（投影切换/缓冲失效）：跳过本帧即可，不让线程崩溃
                            LlkLog.write("capture", "干净帧读取失败：${e.message}")
                        }
                    } finally {
                        img.close()
                    }
                } else if (phase == AutoPhase.PLANNING && ep == apEpoch) {
                    // 画面完全静止时虚拟屏可能不推新帧（acquireLatestImage 为 null）。
                    // 若就此沉默，状态机会卡死在 PLANNING 且日志无任何输出（1.9.14 实测）：
                    // 记日志并短暂重试，连续多次仍无帧才停下提示
                    nullFrames++
                    if (nullFrames >= 20) {
                        nullFrames = 0
                        phase = AutoPhase.IDLE
                        autoPlay = false
                        status("自动消｜录屏无画面输出，已停止（请重新点\"启动\"授权录屏）")
                        LlkLog.write("play", "连续 20 次无可用帧，已停止")
                    } else {
                        if (nullFrames == 1) LlkLog.write("play", "规划期无可用帧（画面静止或投影未输出），等待中…")
                        workHandler.postDelayed({
                            if (ep == apEpoch && phase == AutoPhase.PLANNING) runFullCycle(force = true, ep)
                        }, 300)
                    }
                }
            } finally {
                mainHandler.post {
                    overlay?.visibility = if (showPaths) View.VISIBLE else View.GONE
                    // 刷新“带提示层画面”的基线，供下一轮快速比对
                    workHandler.post { peekChecksum()?.let { lastPeekChecksum = it } }
                }
            }
        }, 70)
    }

    private fun fillPixels(img: Image, out: IntArray) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = img.width
        val h = img.height
        if (pixelStride == 4 && rowStride == w * 4) {
            for (i in 0 until w * h) {
                val o = i * 4
                out[i] = 0xFF000000.toInt() or
                        ((buf.get(o).toInt() and 0xFF) shl 16) or
                        ((buf.get(o + 1).toInt() and 0xFF) shl 8) or
                        (buf.get(o + 2).toInt() and 0xFF)
            }
        } else {
            for (y in 0 until h) {
                val row = y * rowStride
                for (x in 0 until w) {
                    val o = row + x * pixelStride
                    out[y * w + x] = 0xFF000000.toInt() or
                            ((buf.get(o).toInt() and 0xFF) shl 16) or
                            ((buf.get(o + 1).toInt() and 0xFF) shl 8) or
                            (buf.get(o + 2).toInt() and 0xFF)
                }
            }
        }
    }

    private fun fastChecksum(px: IntArray, w: Int, h: Int): Long {
        var s = 0L
        val stepX = (w / 32).coerceAtLeast(1)
        val stepY = (h / 18).coerceAtLeast(1)
        var y = stepY / 2
        while (y < h) {
            var x = stepX / 2
            while (x < w) {
                val p = px[y * w + x]
                s = s * 31 + ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF))
                x += stepX
            }
            y += stepY
        }
        return s
    }

    private fun toggleAutoPlay() {
        autoPlay = !autoPlay
        LlkLog.write("toggle", "自动消 -> ${if (autoPlay) "开" else "关"}")
        if (autoPlay) {
            emptyRounds = 0
            // 纯开关：按下就保持“开”，条件不满足时在状态行显示原因并等待，不回弹
            if (GameWatchService.instance == null) {
                // 1.9.14 教训：这条等待路径此前不写日志——更新 APK 后无障碍被系统重置，
                // 用户按开关后日志 24 秒空白，看起来像"完全不工作"却无从排查
                LlkLog.write("toggle", "无障碍未连接：开关保持开启并等待（此期间不会执行任何点击）")
                status("自动消｜已开启，等待无障碍服务连接…")
                return
            }
            // 关键：无论是否校准都启动状态机——
            // 已校准=暂停流程，未校准=直接模式（此前直接模式被校准拦截，成为死代码）
            startAutoPlayLoop()
        } else {
            // 关闭：先作废所有在途回调（旧流的定时器可能还挂在队列里，若不作废
            // 会在下次开开关后苏醒并与新流并发），再退出状态机；暂停态则恢复游戏
            apEpoch++
            phase = AutoPhase.IDLE
            seqQueue = emptyList()
            if (gamePaused) {
                gamePaused = false
                GameWatchService.instance?.tap(resumeX.toFloat(), resumeY.toFloat())
            }
            status("自动消｜已关闭")
        }
    }

    /** 自动消主循环入口：棋盘可见时识别 → 规划 → 点暂停 → 点继续 → 连续消除 → 循环。 */
    private fun startAutoPlayLoop() {
        // 新一轮 = 新代际：作废所有遗留回调（关→开快速切换时旧流的定时器还在队列里）
        val ep = ++apEpoch
        phase = AutoPhase.PLANNING
        status("自动消｜识别棋盘…")
        workHandler.post { runFullCycle(force = true, ep) }
    }

    /**
     * 棋盘可见状态下的识别完成后续链：规划整盘序列 → 点暂停 → 点继续 → 连续执行。
     * 注意：识别必须发生在点暂停之前（暂停对话框会盖住棋盘，暂停后无法识别）。
     */
    private fun onBoardAnalyzed(ep: Int) {
        // 代际过期：这条链已被新流取代，不得改动任何状态机状态
        if (ep != apEpoch) return
        // 开关可能已在执行/等待期间被关闭：不允许再进入规划（否则关了开关仍空转规划）
        if (!autoPlay) {
            phase = AutoPhase.IDLE
            return
        }
        val det = lastDet
        val ids = lastIds
        if (det == null || ids == null) {
            status("自动消｜识别失败，重试")
            workHandler.postDelayed({
                if (ep == apEpoch && phase == AutoPhase.PLANNING) runFullCycle(force = true, ep)
            }, 1500)
            return
        }
        // 幻影帧过滤 v2：消除/填充/加载动画中的错帧会识别出错误网格。
        // a) 首见网格（仅 1 票）不允许规划——开局加载/掉落动画的错帧（如 5x4）常常是
        //    第一次成功识别就出现，旧逻辑要主流网格 >=3 票才生效，首帧直接放行
        //    （1.9.16 日志 00:03:16 实锤：在 5x4 上规划 10 对，20 击全废）
        // b) 已有主流网格（>=3 票）时，异网格或块数不足其一半 → 等待（原逻辑）
        // c) 与上一轮规划块数差 >30%：正在掉落/重新填充（如 48→28），等一拍再识别
        val cnt = det.tileCount()
        val votes = gridCounts["${det.rows}x${det.cols}"] ?: 0
        val mode = gridCounts.maxByOrNull { it.value }
        val mp = mode?.key?.split("x")?.takeIf { mode.value >= 3 && it.size == 2 }
        val mainGridDiff = mp != null && (det.rows != mp[0].toInt() || det.cols != mp[1].toInt())
        val tooFewTiles = mp != null && cnt < mp[0].toInt() * mp[1].toInt() * 0.5f
        val tileJump = lastPlanTileCount > 0 && kotlin.math.abs(cnt - lastPlanTileCount) > lastPlanTileCount * 0.3f
        if (votes < 2 || mainGridDiff || tooFewTiles || tileJump) {
            gridWaits++
            if (gridWaits >= 10) {
                // 等待上限不再停止（1.9.17 缺陷：棋盘形状随消除/重填变化，如 8x6 消成
                // 7x6 再重填回 8x6，票仓最大者锁死新形状 → 无限等待 → 静默停止，
                // 用户只能反复开关，00:28 会话实锤）。改为清空票仓重新学习，
                // 以当前连续识别到的网格为主，继续走稳定门规划
                gridWaits = 0
                gridCounts.clear()
                gridCounts["${det.rows}x${det.cols}"] = 3
                LlkLog.write("play", "网格长期不一致，重置网格学习：以当前 ${det.rows}x${det.cols} $cnt 块为主")
            } else {
                val why = when {
                    votes < 2 -> "网格未确认（${det.rows}x${det.cols} 首见）"
                    mainGridDiff || tooFewTiles -> "当前误识别 ${det.rows}x${det.cols} $cnt 块"
                    else -> "块数突变 $lastPlanTileCount→$cnt（重填中）"
                }
                status("自动消｜等待棋盘稳定（$why）")
                workHandler.postDelayed({
                    if (ep == apEpoch && phase == AutoPhase.PLANNING) runFullCycle(force = true, ep)
                }, 700)
                return
            }
        }
        gridWaits = 0
        // 稳定门：方块掉落/重新填充动画期间建模必然错位（用户反馈"填充后继续旧路径、
        // 点什么都不对"）。棋盘区两次采样（本帧 + 400ms 后）差异极小才允许规划。
        val detRef = det
        mainHandler.post { overlay?.visibility = View.INVISIBLE }
        workHandler.postDelayed({
            var stable = false
            try {
                val img = reader?.acquireLatestImage()
                if (img != null) {
                    try {
                        val w2 = img.width
                        val h2 = img.height
                        val px2 = ensureBuffer2(w2, h2)
                        fillPixels(img, px2)
                        val lbs = lastBoardSig
                        if (lbs != null) {
                            val bsig = frameSig(
                                px2, w2, h2,
                                detRef.boardLeft, detRef.boardTop, detRef.boardRight, detRef.boardBottom
                            )
                            stable = sigDiffRatio(lbs, bsig) < 0.08f
                        }
                    } finally {
                        img.close()
                    }
                }
            } catch (e: Exception) {
                LlkLog.write("capture", "稳定门取帧失败：${e.message}")
            }
            mainHandler.post { overlay?.visibility = if (showPaths) View.VISIBLE else View.GONE }
            workHandler.post {
                if (ep != apEpoch) return@post
                if (!autoPlay) {
                    phase = AutoPhase.IDLE
                    return@post
                }
                if (!stable) {
                    planStableRetries++
                    if (planStableRetries >= 8) {
                        phase = AutoPhase.IDLE
                        autoPlay = false
                        LlkLog.write("play", "连续 8 次稳定门未通过，已停止")
                        status("自动消｜棋盘持续变化，已停止")
                        return@post
                    }
                    status("自动消｜等待棋盘稳定（掉落/填充动画中）…")
                    workHandler.postDelayed({
                        if (ep == apEpoch && phase == AutoPhase.PLANNING) runFullCycle(force = true, ep)
                    }, 500)
                } else {
                    planStableRetries = 0
                    planAndExecute(det, ids, ep)
                }
            }
        }, 400)
    }

    /** 稳定门通过后：规划序列（每轮最多 10 对，限制重填后的过期深度）→ 暂停/直接 → 执行。 */
    private fun planAndExecute(det: BoardDetector.Detection, ids: IntArray, ep: Int) {
        if (ep != apEpoch) return
        // 同代单飞：同一时刻只允许一条链从 PLANNING 进入执行——"开开关"链与手动"识别"
        // 按钮链可持有同一 epoch，代际令牌拦不住它们并发，必须靠 phase 互斥
        // （1.9.16 日志 00:04:25.905/.973 两次规划、同坐标 68ms 双击实锤）
        if (phase != AutoPhase.PLANNING) return
        val hints = lastLocal
        if (hints.isEmpty()) {
            emptyRounds++
            if (emptyRounds >= 6) {
                phase = AutoPhase.IDLE
                autoPlay = false
                LlkLog.write("play", "连续 6 轮无可消对，已停止")
                status("自动消｜连续 6 轮无可消对，已停止")
                return
            }
            status("自动消｜无可消对（第 $emptyRounds 轮），重新识别…")
            workHandler.postDelayed({
                if (ep == apEpoch && phase == AutoPhase.PLANNING) runFullCycle(force = true, ep)
            }, 1200)
            return
        }
        emptyRounds = 0
        // 每轮最多 10 对：游戏在消除约 1/3 时会重新填充，长序列在重填后会全部过期
        seqQueue = OnetSolver(det.rows, det.cols, ids, true).solveSequence(48).take(10)
        seqIdx = 0
        seqDet = det
        lastPlanTileCount = det.tileCount()
        // 卡死检测：连续多轮"同一模型、同一序列"且点击无效——多为游戏端存在残留
        // 选中的方块（半选中状态下再点=取消选中，怎么点都不消，1.9.16 日志
        // 00:04:11 起 34 块反复规划点击不变实锤），停止并给用户可操作提示
        val first = seqQueue.firstOrNull()
        val planSig = "${det.tileCount()}:${seqQueue.size}:${first?.a?.r},${first?.a?.c}"
        if (planSig == lastPlanSig) stallRounds++ else stallRounds = 0
        lastPlanSig = planSig
        if (stallRounds >= 3) {
            stallRounds = 0
            lastPlanSig = null
            phase = AutoPhase.IDLE
            autoPlay = false
            LlkLog.write("play", "连续 3 轮规划无效果，已停止（疑似游戏存在残留选中方块）")
            status("自动消｜连续点击无效果已停止：请手动点掉一两组方块复位，再开自动消")
            return
        }
        LlkLog.write("play", "规划序列 ${seqQueue.size} 对")

        if (pauseCalibrated) {
            // 暂停模式（用户设计）：识别完成后点暂停（冻结）→ 点继续 → 连续消除
            status("自动消｜规划 ${seqQueue.size} 步，暂停后开始")
            val a11y = GameWatchService.instance ?: run {
                phase = AutoPhase.IDLE
                return
            }
            phase = AutoPhase.PAUSED
            gamePaused = true
            LlkLog.write("loop", "点暂停按钮")
            a11y.tap(pauseX.toFloat(), pauseY.toFloat())
            workHandler.postDelayed({
                if (ep != apEpoch) return@postDelayed
                LlkLog.write("loop", "点继续按钮")
                a11y.tap(resumeX.toFloat(), resumeY.toFloat())
                gamePaused = false
                workHandler.postDelayed({
                    if (ep != apEpoch) return@postDelayed
                    phase = AutoPhase.EXECUTING
                    execStep(ep)
                }, 300)
            }, 450)
        } else {
            // 直接模式（未校准）：跳过暂停/继续，立即连续消除
            status("自动消｜直接模式，消除 ${seqQueue.size} 对（点【校准】可启用暂停流程）")
            phase = AutoPhase.EXECUTING
            execStep(ep)
        }
    }

    /** 单步异常的安全兜底：写主日志（含堆栈）并停下状态机，不让异常带崩整个进程。 */
    private fun execCrashStop(t: Throwable) {
        LlkLog.write("crash", "自动消执行异常（已安全停止，进程未退出）：${android.util.Log.getStackTraceString(t)}")
        apEpoch++
        phase = AutoPhase.IDLE
        autoPlay = false
        status("自动消｜内部异常已停止（详情见日志）")
    }

    private fun execStep(ep: Int) {
        if (ep != apEpoch) return
        try {
            if (phase != AutoPhase.EXECUTING || !autoPlay) {
                finishExecution(ep)
                return
            }
            if (seqIdx >= seqQueue.size) {
                finishExecution(ep)
                return
            }
            val det = seqDet
            val a11y = GameWatchService.instance
            if (det == null || a11y == null) {
                finishExecution(ep)
                return
            }
            val h = seqQueue[seqIdx]
            // 点击坐标 = bbox 几何中心（与圆牌位置一致，稳定居中）
            val ca = det.cells[h.a.r * det.cols + h.a.c]
            val cb = det.cells[h.b.r * det.cols + h.b.c]
            if (ca == null || cb == null) {
                seqIdx++
                execStep(ep)
                return
            }
            val ax = (ca.x0 + ca.x1) / 2f
            val ay = (ca.y0 + ca.y1) / 2f
            val bx = (cb.x0 + cb.x1) / 2f
            val by = (cb.y0 + cb.y1) / 2f
            mainHandler.post { overlay?.flashTap(ax, ay) }
            a11y.tap(ax, ay)
            workHandler.postDelayed({
                try {
                    if (ep != apEpoch || phase != AutoPhase.EXECUTING) return@postDelayed
                    mainHandler.post { overlay?.flashTap(bx, by) }
                    a11y.tap(bx, by)
                    seqIdx++
                    mainHandler.post { status("自动消｜消除 第${seqIdx}/${seqQueue.size}对") }
                    // 双击间隔 250ms + 对间隔 450ms：兼顾可靠性与速度
                    workHandler.postDelayed({ execStep(ep) }, 450)
                } catch (t: Throwable) {
                    execCrashStop(t)
                }
            }, 250)
        } catch (t: Throwable) {
            execCrashStop(t)
        }
    }

    private fun finishExecution(ep: Int) {
        // 代际过期：新流已接管状态机，本回调不得再改动任何状态
        if (ep != apEpoch) return
        // 开关已关闭：彻底停下，不再进入识别→规划循环
        if (!autoPlay) {
            phase = AutoPhase.IDLE
            return
        }
        // 序列执行完 → 新一轮（新代际，作废本轮遗留定时器）
        // → 重新识别（棋盘已可见，等消除动画播完）→ 规划 → 循环
        val next = ++apEpoch
        phase = AutoPhase.PLANNING
        status("自动消｜一轮完成，重新识别…")
        workHandler.postDelayed({
            if (next == apEpoch && phase == AutoPhase.PLANNING) runFullCycle(force = true, next)
        }, 1100)
    }

    /** 数字刷新：仅 IDLE 阶段运行；其余阶段由自动消状态机接管。 */
    private fun autoTick() {
        if (phase != AutoPhase.IDLE) return
        val p = peekChecksum() ?: return
        if (p == lastPeekChecksum) return
        lastPeekChecksum = p
        runFullCycle(force = false)
    }

    /** 无障碍服务连接后回调：若自动消已开启但此前因无障碍未连接而挂起，现在自动续上。 */
    fun onA11yConnected() {
        mainHandler.post {
            status("自动消｜无障碍已连接")
            val willResume = autoPlay && phase == AutoPhase.IDLE
            LlkLog.write("toggle", "无障碍已连接${if (willResume) "，续启自动消" else ""}")
            if (willResume) {
                workHandler.post { startAutoPlayLoop() }
            }
        }
    }

    /** 识别 + 规划数据准备。返回是否成功（失败时已记日志/重试，调用方不得用旧模型续链）。 */
    private fun analyzeBoard(px: IntArray, w: Int, h: Int, ep: Int = apEpoch): Boolean {
        val reg = region?.let { intArrayOf(it.left, it.top, it.width(), it.height()) }
        // 全分辨率识别（v1.6.0 引入的降采样在真机上破坏方块特征，已移除）
        var det = BoardDetector.detect(px, w, h, reg)
        if (det == null && reg != null) {
            // 选区内检测失败（选区含非棋盘内容或画面过渡），回退全盘
            LlkLog.write("detect", "选区内失败（${BoardDetector.lastFailReason}），回退全盘")
            det = BoardDetector.detect(px, w, h, null)
        }
        if (det == null) {
            val reason = BoardDetector.lastFailReason
            LlkLog.write("detect", "失败：$reason")
            detectFails++
            if (phase == AutoPhase.PLANNING) {
                // 规划阶段的识别失败（消除/填格动画过渡）：直接重试
                if (detectFails >= 5) {
                    phase = AutoPhase.IDLE
                    autoPlay = false
                    LlkLog.write("play", "连续 5 次识别失败，已停止")
                    status("自动消｜连续识别失败，已停止")
                } else {
                    status("自动消｜识别失败，重试第 $detectFails 次")
                    workHandler.postDelayed({
                        if (ep == apEpoch && phase == AutoPhase.PLANNING) runFullCycle(force = true, ep)
                    }, 600)
                }
                return false
            }
            mainHandler.post {
                // 消除/重新填格动画期间的瞬时失败很常见：保留上一次提示不清屏，
                // 连续多次失败（大概率已离开棋盘）才清空
                if (detectFails >= 3) {
                    overlay?.clearResult()
                    status(if (reason.isEmpty()) "未检测到棋盘" else "未检测到棋盘（$reason）")
                }
            }
            return false
        }
        detectFails = 0
        // 记录本局网格签名（真实棋盘格会出现次数最多，用于过滤幻影帧）
        val gridSig = "${det.rows}x${det.cols}"
        gridCounts[gridSig] = (gridCounts[gridSig] ?: 0) + 1
        // 记录本帧棋盘区签名（稳定门用：下一帧与本帧差异极小才允许规划）
        lastBoardSig = frameSig(px, w, h, det.boardLeft, det.boardTop, det.boardRight, det.boardBottom)
        val ids = TileClassifier.classify(det)
        val solver = OnetSolver(det.rows, det.cols, ids)
        val hints = solver.findHints(20)
        LlkLog.write(
            "analyze",
            "棋盘 ${det.rows}x${det.cols} 块=${det.tileCount()} 可消=${hints.size} " +
                    "选区=${region?.let { "${it.width()}x${it.height()}@(${it.left},${it.top})" } ?: "全盘"}"
        )
        saveCache(px, w, h, det, ids, hints)

        lastDet = det
        lastLocal = hints
        lastIds = ids
        lastAi = emptyList()
        val a11yMissing = autoPlay && GameWatchService.instance == null
        val msg = if (a11yMissing) {
            "可消 ${hints.size} 组（自动消需开启无障碍服务）"
        } else if (hints.isEmpty()) {
            "棋盘 ${det.rows}x${det.cols}（${det.tileCount()}块），暂无可消对"
        } else {
            "棋盘 ${det.rows}x${det.cols}（${det.tileCount()}块），可消 ${hints.size} 组"
        }
        mainHandler.post {
            overlay?.setResult(det, hints)
            status(msg)
            if (hints.size != lastHintCount) {
                lastHintCount = hints.size
                beep()
            }
        }

        // AI 校验（LLKZS/config.json 配置后生效），异步执行避免阻塞识别循环
        val cfg = try { AiClient.loadConfig(LlkDir.base(this)) } catch (_: Exception) { null }
        if (cfg == null) {
            if (!aiConfigLogged) {
                aiConfigLogged = true
                LlkLog.write("ai", "AI 未配置：编辑 LLKZS/config.json，设 aiEnabled=true 并填 aiBaseUrl/aiApiKey/aiModel")
            }
        } else {
            if (!aiConfigLogged) {
                aiConfigLogged = true
                LlkLog.write("ai", "AI 已启用：model=${cfg.model} baseUrl=${cfg.baseUrl}")
            }
            try {
                aiExecutor.execute { runAi(cfg, px, w, h, det, ids, hints) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
            }
        }
        return true
    }

    private val pauseCalibrated: Boolean
        get() = pauseX >= 0 && pauseY >= 0 && resumeX >= 0 && resumeY >= 0

    private fun loadButtonCalibration() {
        try {
            val f = LlkDir.configFile(this)
            if (!f.exists()) return
            val o = org.json.JSONObject(f.readText())
            pauseX = o.optInt("pauseX", -1)
            pauseY = o.optInt("pauseY", -1)
            resumeX = o.optInt("resumeX", -1)
            resumeY = o.optInt("resumeY", -1)
        } catch (_: Exception) {
        }
    }

    private fun saveButtonCalibration() {
        try {
            val f = LlkDir.configFile(this)
            val o = if (f.exists()) {
                try { org.json.JSONObject(f.readText()) } catch (_: Exception) { org.json.JSONObject() }
            } else org.json.JSONObject()
            o.put("pauseX", pauseX)
                .put("pauseY", pauseY)
                .put("resumeX", resumeX)
                .put("resumeY", resumeY)
            f.writeText(o.toString(2))
        } catch (_: Exception) {
        }
    }

    /** 校准流程：第 1 步点暂停按钮位置（随后程序替用户暂停，让“继续”出现）；第 2 步点继续按钮位置。 */
    private fun startPauseCalibration() {
        if (tapCaptureView != null) return
        mainHandler.post {
            val view = TapCaptureView(this, "第 1/2 步：点击游戏右下角【暂停】按钮的位置") { x, y ->
                val v1 = tapCaptureView ?: return@TapCaptureView
                try { wm.removeView(v1) } catch (_: Exception) {}
                tapCaptureView = null
                pauseX = x.toInt()
                pauseY = y.toInt()
                LlkLog.write("calibration", "第1步完成：暂停=($pauseX,$pauseY)，等待第2步")
                // 程序替用户按下暂停，让“继续”按钮出现（无障碍在线时）
                GameWatchService.instance?.tap(x, y)
                workHandler.postDelayed({
                    showTapCapture("第 2/2 步：点击【继续】按钮出现的位置") { x2, y2 ->
                        val v2 = tapCaptureView
                        if (v2 != null) {
                            try { wm.removeView(v2) } catch (_: Exception) {}
                            tapCaptureView = null
                            resumeX = x2.toInt()
                            resumeY = y2.toInt()
                            saveButtonCalibration()
                            status("自动消｜校准完成：暂停($pauseX,$pauseY) 继续($resumeX,$resumeY)")
                            LlkLog.write("calibration", "暂停=($pauseX,$pauseY) 继续=($resumeX,$resumeY)")
                            if (autoPlay) {
                                // 校准第 1 步已替用户按下暂停（对话框正盖着棋盘，无法识别）：
                                // 先点继续恢复游戏，再走标准循环（识别→规划→暂停→继续→消除）
                                GameWatchService.instance?.tap(resumeX.toFloat(), resumeY.toFloat())
                                gamePaused = false
                                workHandler.postDelayed({ startAutoPlayLoop() }, 500)
                            }
                        }
                    }
                }, 400)
            }
            tapCaptureView = view
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            wm.addView(view, lp)
        }
    }

    private fun showTapCapture(prompt: String, onTap: (Float, Float) -> Unit) {
        if (tapCaptureView != null) return
        mainHandler.post {
            val view = TapCaptureView(this, prompt) { x, y ->
                val v = tapCaptureView ?: return@TapCaptureView
                try { wm.removeView(v) } catch (_: Exception) {}
                tapCaptureView = null
                onTap(x, y)
            }
            tapCaptureView = view
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            wm.addView(view, lp)
        }
    }

    private fun pairKey(a: com.llk.assist.core.Pt, b: com.llk.assist.core.Pt, cols: Int): Long {
        val x = (a.r * cols + a.c).toLong()
        val y = (b.r * cols + b.c).toLong()
        val lo = minOf(x, y)
        val hi = maxOf(x, y)
        return lo * 1000L + hi
    }

    /** 发布提示到悬浮层；AI 结果返回后合并重发。 */
    private fun publish(det: BoardDetector.Detection?, local: List<Hint>, ai: List<Hint>) {
        mainHandler.post {
            if (det == null) return@post
            overlay?.setResult(det, local + ai)
        }
    }

    /** 运行 AI 校验：可连配对与不可连原因，全部写日志，与本地识别合并。 */
    private fun runAi(
        cfg: AiClient.Config,
        px: IntArray,
        w: Int,
        h: Int,
        det: BoardDetector.Detection,
        ids: IntArray,
        localHints: List<Hint>
    ) {
        try {
            val bx0 = (det.boardLeft - det.pitchX * 0.15f).toInt().coerceIn(0, w - 1)
            val by0 = (det.boardTop - det.pitchY * 0.15f).toInt().coerceIn(0, h - 1)
            val bx1 = (det.boardRight + det.pitchX * 0.15f).toInt().coerceIn(0, w - 1)
            val by1 = (det.boardBottom + det.pitchY * 0.15f).toInt().coerceIn(0, h - 1)
            LlkLog.write("ai", "请求 ${cfg.model} 区域=($bx0,$by0)-($bx1,$by1)")
            val res = AiClient.askBoard(cfg, px, w, h, bx0, by0, bx1, by1, det.rows, det.cols)
            if (!res.ok) {
                LlkLog.write("ai", "失败：${res.error}")
                if (res.raw.isNotEmpty()) {
                    LlkLog.write("ai", "原始回复（截断500）：${res.raw.take(500)}")
                }
                mainHandler.post { status("AI失败：${res.error.take(60)}") }
                return
            }
            LlkLog.write("ai", "网格=${res.rows}x${res.cols}（本地 ${det.rows}x${det.cols}） 配对=${res.pairs.size} 不可连=${res.blocked.size}")
            if (res.rows > 0 && res.cols > 0 && (res.rows != det.rows || res.cols != det.cols)) {
                LlkLog.write("ai", "警告：AI 判定网格与本地不一致（AI ${res.rows}x${res.cols} vs 本地 ${det.rows}x${det.cols}）")
            }
            for (b in res.blocked) {
                LlkLog.write(
                    "ai", "不可连 (${"${b.ar + 1},${b.ac + 1}"})-(${"${b.br + 1},${b.bc + 1}"}) 原因：${b.reason}"
                )
            }

            // 合并：本地配对 + AI 配对（AI 独有的很可能是本地分类错误，标记并保留）
            val aiHints = ArrayList<Hint>()
            val localKeys = localHints.map { pairKey(it.a, it.b, det.cols) }.toHashSet()
            for (p in res.pairs) {
                if (p.ar !in 0 until det.rows || p.ac !in 0 until det.cols) continue
                if (p.br !in 0 until det.rows || p.bc !in 0 until det.cols) continue
                val ca = det.cells[p.ar * det.cols + p.ac]
                val cb = det.cells[p.br * det.cols + p.bc]
                if (ca == null || cb == null) continue
                if (ca.blocked || cb.blocked) {
                    LlkLog.write("ai", "AI配对含障碍物，忽略 (${"${p.ar + 1},${p.ac + 1}"})-(${"${p.br + 1},${p.bc + 1}"})")
                    continue
                }
                val a = com.llk.assist.core.Pt(p.ar, p.ac)
                val b = com.llk.assist.core.Pt(p.br, p.bc)
                val key = pairKey(a, b, det.cols)
                if (key in localKeys) {
                    LlkLog.write("ai", "配对 (${"${p.ar + 1},${p.ac + 1}"})-(${"${p.br + 1},${p.bc + 1}"}) 与本地一致")
                    continue
                }
                val sameType = ids[p.ar * det.cols + p.ac] == ids[p.br * det.cols + p.bc]
                LlkLog.write(
                    "ai",
                    "AI新增配对 (${"${p.ar + 1},${p.ac + 1}"})-(${"${p.br + 1},${p.bc + 1}"}) " +
                            if (sameType) "（本地判同类但不可连）" else "（本地判不同类：可能是分类错误）"
                )
                aiHints.add(Hint(a, b, listOf(a, b), 9))
            }
            lastAi = aiHints
            LlkLog.write("ai", "本地 ${localHints.size} 组，AI 新增 ${aiHints.size} 组")
            mainHandler.post {
                status("本地 ${localHints.size} 组 + AI 新增 ${aiHints.size} 组（详见日志）")
                publish(lastDet, lastLocal, lastAi)
            }
        } catch (e: Exception) {
            LlkLog.write("ai", "异常：${e.message}")
            mainHandler.post { status("AI异常：${e.message?.take(60)}") }
        }
    }

    /** 缓存最近一帧缩略图与识别结果到 LLKZS/cache，便于排查。 */
    private fun saveCache(
        px: IntArray,
        w: Int,
        h: Int,
        det: BoardDetector.Detection,
        ids: IntArray,
        hints: List<Hint>
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCacheAt < 4000) return
        lastCacheAt = now
        try {
            val cache = LlkDir.cacheDir(this)
            val full = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val bw = 720
            val bh = (h.toFloat() / w * bw).toInt()
            val small = android.graphics.Bitmap.createScaledBitmap(full, bw, bh, true)
            File(cache, "last_frame.jpg").outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            small.recycle()
            full.recycle()

            val sb = StringBuilder()
            sb.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
            sb.appendLine("棋盘: ${det.rows} x ${det.cols}，实心 ${det.tileCount()} 块")
            sb.appendLine("选区: ${region?.toShortString() ?: "全盘"}")
            sb.appendLine("可消对:")
            hints.forEach {
                sb.appendLine("  (${it.a.r + 1},${it.a.c + 1})-(${it.b.r + 1},${it.b.c + 1}) 折=${it.turns} 类型=${ids[it.a.r * det.cols + it.a.c]}")
            }
            File(cache, "last_detection.txt").writeText(sb.toString())
        } catch (e: Exception) {
            LlkLog.write("cache", "写入失败：${e.message}")
        }
    }

    /** 进入选区模式：全屏拖动框选棋盘；轻点表示恢复全盘。 */
    private fun startRegionSelect() {
        if (regionView != null) return
        mainHandler.post {
            val view = RegionSelectView(this) { rectView ->
                val v = regionView ?: return@RegionSelectView
                try { wm.removeView(v) } catch (_: Exception) {}
                regionView = null
                region = if (rectView == null) {
                    status("选区：已恢复全盘")
                    lastAnalyzedSum = -1
                    lastPeekChecksum = -1
                    analyzeNow()
                    null
                } else {
                    val sx = captureW.toFloat() / v.width
                    val sy = captureH.toFloat() / v.height
                    val r = android.graphics.Rect(
                        (rectView.left * sx).toInt(),
                        (rectView.top * sy).toInt(),
                        (rectView.right * sx).toInt(),
                        (rectView.bottom * sy).toInt()
                    )
                    region = r
                    status("选区 ${r.width()}x${r.height()} @(${r.left},${r.top})")
                    LlkLog.write("region", "选区 ${r.width()}x${r.height()} @(${r.left},${r.top})")
                    lastAnalyzedSum = -1
                    lastPeekChecksum = -1
                    analyzeNow()
                    r
                }
            }
            regionView = view
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            wm.addView(view, lp)
        }
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            mainHandler.postDelayed({ tg.release() }, 300)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        aiExecutor.shutdownNow()
        try {
            regionView?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        regionView = null
        try {
            overlay?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            panel?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        overlay = null
        panel = null
        try {
            vdisplay?.release()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        vdisplay = null
        reader = null
        projection = null
        workThread.quitSafely()
        instance = null
        super.onDestroy()
    }
}
