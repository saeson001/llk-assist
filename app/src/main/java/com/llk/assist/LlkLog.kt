package com.llk.assist

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 轻量文件日志：写入 LLKZS/logs/log-yyyy-MM-dd.log，超过 5MB 轮转。 */
object LlkLog {

    private var dir: File? = null
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)

    @Synchronized
    fun init(context: Context) {
        dir = try { LlkDir.logDir(context) } catch (_: Exception) { null }
    }

    @Synchronized
    fun write(tag: String, msg: String) {
        val d = dir ?: return
        try {
            val name = "log-${dateFmt.format(Date())}.log"
            val out = File(d, name)
            if (out.exists() && out.length() > 5_000_000L) {
                val old = File(d, name.replace(".log", ".old.log"))
                if (old.exists()) old.delete()
                out.renameTo(old)
            }
            out.appendText("${timeFmt.format(Date())} [$tag] $msg\n")
        } catch (_: Exception) {
        }
    }
}
