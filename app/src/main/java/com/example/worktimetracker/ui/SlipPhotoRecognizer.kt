package com.example.worktimetracker.ui

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 工资条照片 → 文本行。
 *
 * 用 **bundled** 的 ML Kit 中文识别：模型随 APK 一起打包，**不依赖 Google Play 服务**，
 * 断网也能识别 —— 国行机（本项目主力设备 vivo V2425A）上没有 Play 服务，
 * 换成 `play-services-mlkit-*` 那一套会直接不可用。
 *
 * 只负责「图 → 文本行」，不认识工资条语义；语义解析在
 * [com.example.worktimetracker.domain.payroll.SlipOcrParser]（纯函数，可单测）。
 */
object SlipPhotoRecognizer {

    /**
     * 识别图片并返回**按版面自上而下**的文本行。
     *
     * 必须从 IO 线程调用：`InputImage.fromFilePath` 会读文件。
     *
     * @throws Exception 图片无法解码或识别失败
     */
    suspend fun recognizeLines(context: Context, uri: Uri): List<String> {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val image = InputImage.fromFilePath(context, uri)
            val result = recognizer.process(image).awaitResult()
            // 按 block / line 展开：工资条是一行一个键值对，行粒度刚好对得上解析器
            result.textBlocks.flatMap { block -> block.lines.map { it.text } }
        } finally {
            recognizer.close()
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }
}
