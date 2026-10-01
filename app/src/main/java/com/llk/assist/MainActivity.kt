package com.llk.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private lateinit var btnOverlay: Button
    private lateinit var btnA11y: Button
    private lateinit var btnStorage: Button
    private lateinit var btnStart: Button
    private lateinit var aiBase: EditText
    private lateinit var aiKey: EditText
    private lateinit var aiModel: EditText
    private lateinit var aiEnabled: CheckBox
    private lateinit var aiStatus: TextView

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) askProjection()
            else Toast.makeText(this, "未授予通知权限（不影响使用）", Toast.LENGTH_SHORT).show()
        }

    private val projectionPerm =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, res.resultCode)
                    .putExtra(CaptureService.EXTRA_RESULT, data)
                ContextCompat.startForegroundService(this, i)
                Toast.makeText(this, "悬浮窗已创建，切换到游戏后点识别或开自动", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "未授予录屏权限", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnA11y = findViewById(R.id.btn_a11y)
        btnStorage = findViewById(R.id.btn_storage)
        btnStart = findViewById(R.id.btn_start)
        aiBase = findViewById(R.id.ai_base)
        aiKey = findViewById(R.id.ai_key)
        aiModel = findViewById(R.id.ai_model)
        aiEnabled = findViewById(R.id.ai_enabled)
        aiStatus = findViewById(R.id.ai_status)

        btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "在列表中找到“连连看助手”并开启", Toast.LENGTH_LONG).show()
        }
        btnStorage.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                Toast.makeText(this, "日志目录：${LlkDir.describe(this)}", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.ai_save).setOnClickListener { saveAiConfig() }
        findViewById<Button>(R.id.ai_test).setOnClickListener { testAiConfig() }
        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                btnOverlay.performClick()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@setOnClickListener
            }
            askProjection()
        }
    }

    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionPerm.launch(mpm.createScreenCaptureIntent())
    }

    override fun onResume() {
        super.onResume()
        btnOverlay.text = if (Settings.canDrawOverlays(this)) "悬浮窗权限：已开启" else "悬浮窗权限：去开启"
        btnA11y.text = if (isA11yOn()) "无障碍服务：已开启" else "无障碍服务：去开启"
        btnStorage.text = if (LlkDir.isRootStorageAvailable()) "文件权限：已开启（存储根目录/LLKZS）" else "文件权限：去开启（改用应用目录/LLKZS）"
        loadAiConfigToUi()
    }

    // ---------- AI 配置 ----------

    private fun loadAiConfigToUi() {
        try {
            val f = LlkDir.configFile(this)
            if (!f.exists()) return
            val o = org.json.JSONObject(f.readText())
            aiBase.setText(o.optString("aiBaseUrl"))
            aiKey.setText(o.optString("aiApiKey"))
            aiModel.setText(o.optString("aiModel"))
            aiEnabled.isChecked = o.optBoolean("aiEnabled")
        } catch (_: Exception) {
        }
    }

    private fun saveAiConfig() {
        try {
            LlkDir.base(this)
            // 合并写入：保留文件里已有的其他字段（如暂停/继续按钮校准坐标）
            val f = LlkDir.configFile(this)
            val o = if (f.exists()) {
                try { org.json.JSONObject(f.readText()) } catch (_: Exception) { org.json.JSONObject() }
            } else org.json.JSONObject()
            o.put("aiEnabled", aiEnabled.isChecked)
                .put("aiBaseUrl", aiBase.text.toString().trim())
                .put("aiApiKey", aiKey.text.toString().trim())
                .put("aiModel", aiModel.text.toString().trim())
            f.writeText(o.toString(2))
            aiStatus.text = "配置已保存到 ${f.absolutePath}"
        } catch (e: Exception) {
            aiStatus.text = "保存失败：${e.message}"
        }
    }

    private fun testAiConfig() {
        val cfg = AiClient.Config(
            enabled = true,
            baseUrl = aiBase.text.toString().trim(),
            key = aiKey.text.toString().trim(),
            model = aiModel.text.toString().trim()
        )
        if (cfg.baseUrl.isEmpty() || cfg.key.isEmpty() || cfg.model.isEmpty()) {
            aiStatus.text = "请先填写接口地址、Key 和模型名"
            return
        }
        aiStatus.text = "测试中（最长约 90 秒）..."
        Thread {
            val testImg = try {
                val f = java.io.File(LlkDir.cacheDir(this), "last_frame.jpg")
                if (f.exists()) f.readBytes() else null
            } catch (_: Exception) {
                null
            }
            val result = AiClient.testConnection(cfg, testImg)
            runOnUiThread {
                aiStatus.text = result
                LlkLog.write("ai", "测试连接：$result")
            }
        }.start()
    }

    private fun isA11yOn(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains("$packageName/.GameWatchService") ||
                s.contains("$packageName/${GameWatchService::class.java.name}")
    }
}
