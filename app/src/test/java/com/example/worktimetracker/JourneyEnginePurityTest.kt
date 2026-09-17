package com.example.worktimetracker

import com.example.worktimetracker.domain.journey.JourneyEngine
import com.example.worktimetracker.domain.journey.JourneyEvent
import com.example.worktimetracker.domain.journey.JourneyPhase
import com.example.worktimetracker.domain.journey.JourneyReason
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `JourneyEngine` 的**依赖边界**测试（阶段 3 §5.4 第 2 / 11 条）。
 *
 * 为什么单独一个文件、而且直接扫源码文本：
 * 「不引用采样策略 / Room / Android / 旧状态机 / 班次画像」这几条
 * **在行为测试里根本看不出来** —— 引擎只要在某一拍偷偷读一次 `ShiftProfile`，
 * 绝大多数用例照样绿。能把这条钉住的只有源码扫描 + 签名反射。
 *
 * ⚠️ 扫描前**必须先剥注释**：KDoc 里写着"不读 Room""不调用采样策略"这类句子，
 * 直接全文搜关键字会命中自己写的说明，测试就变成了永远为真的空转。
 */
class JourneyEnginePurityTest {

    @Test
    fun engineSourceHasNoForbiddenImports() {
        val imports = source()
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .toList()

        assertTrue("引擎必须有 import（否则说明源码没读到）", imports.isNotEmpty())
        imports.forEach { line ->
            FORBIDDEN_IMPORT_PREFIXES.forEach { prefix ->
                assertFalse("出现了不该有的导入：$line", line.removePrefix("import ").startsWith(prefix))
            }
        }
    }

    @Test
    fun engineCodeDoesNotReferenceSamplingRoomAndroidLegacyOrShiftProfile() {
        val code = stripComments(source())

        // 采样：档位由 AdaptiveSamplingPolicy 决定，引擎不许沾
        assertNotMentioned(code, "SamplingTier")
        assertNotMentioned(code, "SamplingDecision")
        assertNotMentioned(code, "AdaptiveSamplingPolicy")
        assertNotMentioned(code, "SamplingTuning")

        // 平台与持久化
        assertNotMentioned(code, "android.")
        assertNotMentioned(code, "androidx.")
        assertNotMentioned(code, "RoomDatabase")
        assertNotMentioned(code, "Context")

        // 旧状态机 / 班次画像（状态机误差必须与班次先验误差可分开归因）
        assertNotMentioned(code, "TrajectoryAnchorEngine")
        assertNotMentioned(code, "ShiftProfile")

        // 时间源：时刻只能从 observation.now 来，不许自己取当前时间
        assertNotMentioned(code, "System.currentTimeMillis")
        assertNotMentioned(code, "SystemClock")
        assertNotMentioned(code, "LocalDate.now")
        assertNotMentioned(code, "Random")
    }

    @Test
    fun engineExposesExactlyOneEntryPointAndNoPlatformTypes() {
        val java = JourneyEngine::class.java

        val publicMethods = java.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && !it.isSynthetic
        }
        assertEquals(
            "引擎只该暴露 reduce（多了就是往引擎里塞别的职责）",
            listOf("reduce"),
            publicMethods.map { it.name }.distinct()
        )

        val signatureNames = mutableListOf<String>()
        publicMethods.forEach { method ->
            signatureNames += method.returnType.name
            method.parameterTypes.forEach { signatureNames += it.name }
        }
        java.declaredFields.forEach { signatureNames += it.type.name }
        signatureNames.forEach { name ->
            assertFalse(
                "引擎签名里出现了平台类型：$name",
                name.startsWith("android.") || name.startsWith("androidx.")
            )
        }
    }

    @Test
    fun enginePackageStaysInsideDomainJourney() {
        assertTrue(
            JourneyEngine::class.java.name.startsWith("com.example.worktimetracker.domain.journey.")
        )
        // 依赖方向：domain 不许反向依赖 data / location（编排层）
        sequenceOf(JourneyPhase::class, JourneyEvent::class, JourneyReason::class).forEach {
            assertTrue(it.java.name.startsWith("com.example.worktimetracker.domain."))
        }
    }

    // ---------------------------------------------------------------- 辅助

    private fun assertNotMentioned(code: String, token: String) {
        assertFalse("引擎源码里出现了「$token」：它属于别的单元或平台层", code.contains(token))
    }

    /** 剥掉 `//` 行注释与 `/* … */` 块注释 —— 说明性文字不算引用。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> {
                    val end = src.indexOf('\n', i)
                    i = if (end < 0) src.length else end
                }
                src.startsWith("/*", i) -> {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end < 0) src.length else end + 2
                }
                else -> {
                    out.append(src[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * 从测试工作目录向上找源码文件。
     *
     * 找不到就**直接失败**（而不是跳过）—— 静默跳过的守卫等于没有守卫。
     */
    private fun source(): String {
        val relative = "app/src/main/java/com/example/worktimetracker/domain/journey/JourneyEngine.kt"
        var current: File? = File(cwd)
        repeat(6) {
            val dir = current ?: return@repeat
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            current = dir.parentFile
        }
        throw AssertionError(
            "找不到 $relative（user.dir=$cwd）；本测试必须在仓库工作区内运行"
        )
    }

    /** 测试进程的工作目录（`user.dir` 在 JVM 上是可空类型，给个兜底避免可空污染）。 */
    private val cwd: String get() = System.getProperty("user.dir") ?: "."

    private companion object {
        val FORBIDDEN_IMPORT_PREFIXES = listOf(
            "android.",
            "androidx.",
            "kotlinx.android",
            "com.example.worktimetracker.data",
            "com.example.worktimetracker.location",
            "com.example.worktimetracker.domain.shift"
        )
    }
}
