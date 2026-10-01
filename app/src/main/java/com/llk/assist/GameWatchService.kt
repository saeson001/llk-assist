package com.llk.assist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务：监听窗口切换，记录前台包名；自动模式下切入其他应用立即触发一次识别；
 * 同时提供手势点击能力，供“自动消”功能点击配对方块。
 */
class GameWatchService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GameWatchService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        CaptureService.lastForegroundPkg = pkg
        val svc = CaptureService.instance ?: return
        if (pkg != packageName) {
            svc.analyzeNow()
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val canGesture = (serviceInfo.capabilities and
                android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        LlkLog.write("a11y", "无障碍已连接，手势能力=$canGesture")
        if (!canGesture) {
            // 更新 APK 后系统可能沿用旧服务能力缓存：必须关闭再重新开启无障碍
            Toast.makeText(
                this,
                "无障碍缺少手势能力：请关闭后重新开启“连连看助手”的无障碍服务",
                Toast.LENGTH_LONG
            ).show()
        }
        Toast.makeText(this, "连连看助手：无障碍服务已启动", Toast.LENGTH_SHORT).show()
        // 若自动消已开启但此前因无障碍未连接而挂起，现在自动续上
        CaptureService.instance?.onA11yConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * 在屏幕坐标 (x, y) 处执行一次轻点。
     * dispatchGesture 在部分设备上仅主线程可靠：统一切主线程派发，并同步等待结果。
     */
    fun tap(x: Float, y: Float): Boolean {
        val latch = CountDownLatch(1)
        var dispatched = false
        mainHandler.post {
            try {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                    .build()
                dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        LlkLog.write("click", "手势完成 @(${x.toInt()},${y.toInt()})")
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        LlkLog.write("click", "手势被系统取消 @(${x.toInt()},${y.toInt()})")
                        latch.countDown()
                    }
                }, null)
                if (!dispatched) {
                    LlkLog.write("click", "手势未派发（无障碍不支持手势）@(${x.toInt()},${y.toInt()})")
                    latch.countDown()
                }
            } catch (e: Exception) {
                LlkLog.write("click", "手势异常：${e.message}")
                latch.countDown()
            }
        }
        latch.await(400, TimeUnit.MILLISECONDS)
        return dispatched
    }
}
