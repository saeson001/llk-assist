package com.llk.assist

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 视觉接口（OpenAI 兼容 /chat/completions）：
 * 把棋盘截图发给视觉模型，让 AI 判断哪些方块对可以连、哪些不能、为什么，
 * 返回严格 JSON。用于与本地识别结果互相校验，提升提示准确率。
 */
object AiClient {

    data class Config(
        val enabled: Boolean,
        val baseUrl: String,
        val key: String,
        val model: String
    )

    data class PairRef(val ar: Int, val ac: Int, val br: Int, val bc: Int)
    data class BlockedRef(val ar: Int, val ac: Int, val br: Int, val bc: Int, val reason: String)
    data class AiResult(
        val ok: Boolean,
        val error: String = "",
        val rows: Int = 0,
        val cols: Int = 0,
        val pairs: List<PairRef> = emptyList(),
        val blocked: List<BlockedRef> = emptyList(),
        val raw: String = ""
    )

    /** 读取 LLKZS/config.json，缺失或字段不全返回 null（AI 关闭）。 */
    fun loadConfig(base: File): Config? {
        return try {
            val f = File(base, "config.json")
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            val key = o.optString("aiApiKey").trim()
            val url = o.optString("aiBaseUrl").trim()
            val model = o.optString("aiModel").trim()
            if (!o.optBoolean("aiEnabled") || key.isEmpty() || url.isEmpty() || model.isEmpty()) null
            else Config(true, url, key, model)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 发起 AI 请求。frame 为整帧 ARGB 像素，board 区域用于裁剪；
     * 返回结构化结果（网络/解析失败时 ok=false）。
     */
    fun askBoard(
        cfg: Config,
        frame: IntArray,
        frameW: Int,
        frameH: Int,
        bx0: Int,
        by0: Int,
        bx1: Int,
        by1: Int,
        rows: Int,
        cols: Int
    ): AiResult {
        return try {
            val bw = bx1 - bx0 + 1
            val bh = by1 - by0 + 1
            val sub = IntArray(bw * bh)
            for (y in 0 until bh) {
                System.arraycopy(frame, (y + by0) * frameW + bx0, sub, y * bw, bw)
            }
            var bmp = Bitmap.createBitmap(sub, bw, bh, Bitmap.Config.ARGB_8888)
            val scale = minOf(1f, 640f / maxOf(bw, bh))
            if (scale < 1f) {
                val small = Bitmap.createScaledBitmap(bmp, (bw * scale).toInt(), (bh * scale).toInt(), true)
                bmp.recycle()
                bmp = small
            }
            val jpeg = ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            }
            bmp.recycle()
            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

            val prompt = "这是连连看游戏棋盘，共 $rows 行 × $cols 列。" +
                    "行号从上到下 1..$rows，列号从左到右 1..$cols，空位也算一格。" +
                    "棕色木质箱子/木板是障碍物：不可消除，也不能作为连线经过的路径。" +
                    "请判断哪些相同图案的方块对可以连线消除（连线最多折2次，只能在棋盘内部空位走），" +
                    "以及哪些明显同图案的方块对当前不可连、原因是什么。" +
                    "严格只返回 JSON，格式：" +
                    "{\"rows\":$rows,\"cols\":$cols," +
                    "\"pairs\":[{\"a\":\"r行c列\",\"b\":\"r行c列\"}]," +
                    "\"cannot\":[{\"a\":\"r行c列\",\"b\":\"r行c列\",\"reason\":\"简短中文原因\"}]}"

            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
                )
            val content0 = chat(cfg, content)
            parse(content0)
        } catch (e: Exception) {
            AiResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 连通性测试：有测试图则连图一起发（验证视觉能力），返回可读结果。 */
    fun testConnection(cfg: Config, testJpeg: ByteArray?): String {
        return try {
            val text = if (testJpeg != null) {
                "这是接口连通性与视觉能力测试图。请只回复四个字：连接成功"
            } else {
                "连通性测试，请只回复四个字：连接成功"
            }
            val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
            if (testJpeg != null) {
                content.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(testJpeg, Base64.NO_WRAP))
                        )
                )
            }
            val reply = chat(cfg, content)
            val prefix = if (testJpeg != null) "连接成功（视觉）" else "连接成功"
            "$prefix，模型回复：${reply.take(50)}"
        } catch (e: Exception) {
            "失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 发送一次 chat/completions 请求，返回首条回复文本；失败抛异常。 */
    private fun chat(cfg: Config, content: JSONArray): String {
        val body = JSONObject()
            .put("model", cfg.model)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content)
                )
            )
            .put("max_tokens", 4096)
            .put("temperature", 0)

        val conn = URL(cfg.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 150_000
        conn.setRequestProperty("Authorization", "Bearer ${cfg.key}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: ${text.take(200)}")
        }
        val msg = JSONObject(text)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message")
        // 推理模型可能把输出放进 reasoning_content，正文留空；做兜底
        var content = msg.optString("content")
        if (content.isBlank()) content = msg.optString("reasoning_content")
        return content
    }

    /** 从模型回复中提取第一段 JSON 并解析（宽容处理 ```json 包裹等情况）。 */
    private fun parse(content: String): AiResult {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return AiResult(false, "回复中无 JSON", raw = content)
        return try {
            val o = JSONObject(content.substring(start, end + 1))
            val pairs = ArrayList<PairRef>()
            val blocked = ArrayList<BlockedRef>()
            val pArr = o.optJSONArray("pairs") ?: JSONArray()
            for (i in 0 until pArr.length()) {
                val p = pArr.getJSONObject(i)
                val a = parseCell(p.optString("a"))
                val b = parseCell(p.optString("b"))
                if (a != null && b != null) pairs.add(PairRef(a[0], a[1], b[0], b[1]))
            }
            val cArr = o.optJSONArray("cannot") ?: JSONArray()
            for (i in 0 until cArr.length()) {
                val p = cArr.getJSONObject(i)
                val a = parseCell(p.optString("a"))
                val b = parseCell(p.optString("b"))
                if (a != null && b != null) {
                    blocked.add(BlockedRef(a[0], a[1], b[0], b[1], p.optString("reason")))
                }
            }
            AiResult(true, rows = o.optInt("rows"), cols = o.optInt("cols"), pairs = pairs, blocked = blocked, raw = content)
        } catch (e: Exception) {
            AiResult(false, "JSON解析失败: ${e.message}", raw = content)
        }
    }

    private val cellRegex = Regex("r(\\d+)c(\\d+)", RegexOption.IGNORE_CASE)

    /** "r3c2" -> [2, 1]（转 0 基）。 */
    private fun parseCell(s: String): IntArray? {
        val m = cellRegex.find(s) ?: return null
        return intArrayOf(m.groupValues[1].toInt() - 1, m.groupValues[2].toInt() - 1)
    }
}
