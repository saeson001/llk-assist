package com.llk.assist

import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * 工作目录管理：优先存储根目录下的 LLKZS（需“所有文件访问”权限），
 * 未授权时退回应用专属目录（/sdcard/Android/data/com.llk.assist/files/LLKZS）。
 * 目录结构：config.json（AI 配置）、logs/（日志）、cache/（最近帧与识别结果）。
 */
object LlkDir {

    fun isRootStorageAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else Environment.getExternalStorageDirectory().canWrite()
    }

    fun base(context: Context): File {
        if (isRootStorageAvailable()) {
            val d = File(Environment.getExternalStorageDirectory(), "LLKZS")
            if (d.isDirectory || d.mkdirs()) return d
        }
        return File(context.getExternalFilesDir(null), "LLKZS").apply { mkdirs() }
    }

    fun logDir(context: Context): File = File(base(context), "logs").apply { mkdirs() }

    fun cacheDir(context: Context): File = File(base(context), "cache").apply { mkdirs() }

    fun configFile(context: Context): File = File(base(context), "config.json")

    /** 配置文件不存在时写入模板，方便用户填 Key。 */
    fun ensureConfigTemplate(context: Context) {
        val f = configFile(context)
        if (f.exists()) return
        try {
            val tpl = JSONObject()
                .put("aiEnabled", false)
                .put("aiBaseUrl", "https://api.openai.com/v1")
                .put("aiApiKey", "")
                .put("aiModel", "gpt-4o-mini")
            f.writeText(tpl.toString(2))
        } catch (_: Exception) {
        }
    }

    fun describe(context: Context): String {
        val root = if (isRootStorageAvailable()) "存储根目录" else "应用目录"
        return "$root/LLKZS（${base(context).absolutePath}）"
    }
}
