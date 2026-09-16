package com.example.worktimetracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.worktimetracker.domain.payroll.PositionedLine
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 工资条照片 → 带位置信息的文本行。
 *
 * 用 **bundled** 的 ML Kit 中文识别：模型随 APK 一起打包，**不依赖 Google Play 服务**，
 * 断网也能识别 —— 国行机（本项目主力设备 vivo V2425A）上没有 Play 服务，
 * 换成 `play-services-mlkit-*` 那一套会直接不可用。
 *
 * ### 真机实测踩到的两件事（都必须在这里解决，别挪到解析器）
 * 1. **满屏平铺水印**：钉钉工资条截图带"姓名+工号"浅灰水印，重复 30 多次。
 *    ML Kit 会把水印也当文字读，碎片挤进表格行里，标签与金额被冲散。
 *    → [prepare] 先做灰度二值化，**把浅色水印抹掉只留深色正文**再识别。
 * 2. **左右两列被拆成两个 block**：标签列与金额列各自成块，`flatMap { it.lines }`
 *    之后的顺序完全不可用。
 *    → 所以这里**必须带上 boundingBox**，版面还原交给纯函数
 *    [com.example.worktimetracker.domain.payroll.OcrLayout]（可单测）。
 */
object SlipPhotoRecognizer {

    /**
     * logcat 标签：`adb logcat -s SlipOcr` 可看原始识别文本与坐标。
     * ⚠️ 用 **W 级**不是 D 级 —— 国行 vivo 会把 `Log.d` 整个过滤掉，日志根本进不了 logcat。
     */
    const val TAG = "SlipOcr"

    /**
     * 判定"深色正文"的灰度阈值（0–255）。
     * 正文是深色（灰度低于它）→ 留黑；水印/底色是浅色 → 全部变白。
     * 160 是实测定下来的：能抹掉浅灰水印，又不会吃掉表格里中灰色的标签文字。
     */
    private const val INK_CUTOFF = 160

    /**
     * 识别图片并返回**按版面自上而下**的文本行（含坐标）。
     *
     * 必须从 IO 线程调用：读图、逐像素二值化都是重活。
     *
     * @throws Exception 图片无法解码或识别失败
     */
    suspend fun recognize(context: Context, uri: Uri): List<PositionedLine> {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val image = prepare(context, uri)
            val result = recognizer.process(image).awaitResult()
            val lines = result.textBlocks.flatMap { block ->
                block.lines.map { line ->
                    val box = line.boundingBox
                    PositionedLine(
                        text = line.text,
                        top = box?.top ?: 0,
                        bottom = box?.bottom ?: 0,
                        left = box?.left ?: 0,
                    )
                }
            }
            // 诊断日志：识别不准时用它定位"是没读出来，还是读出来了没解析对"
            Log.w(TAG, "recognize $uri -> ${lines.size} lines")
            lines.forEach { Log.w(TAG, "  [y=${it.top}-${it.bottom} x=${it.left}] ${it.text}") }
            lines
        } finally {
            recognizer.close()
        }
    }

    /**
     * 读图 → 灰度 → 二值化 → [InputImage]。
     *
     * 这一步只为一件事：**抹掉浅色水印**。没有它，水印碎片会把表格行冲散，
     * 再好的解析器也救不回来（实测 103 行里大半是水印）。
     */
    private fun prepare(context: Context, uri: Uri): InputImage {
        val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("无法读取这张图")
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            pixels[i] = if (lum < INK_CUTOFF) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val ink = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        ink.setPixels(pixels, 0, w, 0, 0, w, h)
        src.recycle()
        return InputImage.fromBitmap(ink, 0)
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }
}
