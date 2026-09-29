package dev.piko.shared.naming

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Android 的 java.util.regex 底下是 ICU，不认 Java 专有的 \p{IsXxx}、\p{InXxx}：Android 8 上编译即抛
 * PatternSyntaxException，写在顶层 val 里的正则让整个类初始化失败，1.0.0 在 Android 8 上一解析文件名就崩。
 * 桌面测试跑在 HotSpot 上照常通过，CI 的模拟器是 API 34（NamingUnicodeDigitsSmokeTest 在那里跑解析，但系统太新），都抓不到，只能扫源码。
 * 脚本写 \p{script=Han}，区块写 \p{block=CJKUnifiedIdeographs}，两边都认。
 *
 * \d 也不能用：ICU 的 \d 是全部 Unicode 数字（\p{Nd}），HotSpot 的只是 0-9。文件名里的 𝟐（数学粗体）在 Android 上
 * 被当成数字抓出来，toInt() 解不了，1.1.0 在信息流里一遍历到这样的文件就崩。一律写 [0-9]。
 */
class AndroidRegexGuardTest {
    @Test
    fun `android code uses no java-only unicode property prefixes`() {
        val offenders = androidSources().flatMap { file ->
            file.readLines().withIndex()
                // 注释里提到这种写法不算，说明为什么不用它的注释正需要写出它
                .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && JAVA_ONLY_PROPERTY.containsMatchIn(line) }
                .map { (index, _) -> "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}" }
        }
        assertTrue(offenders.isEmpty(), "这些正则用了 Android 不认的 \\p{Is…}/\\p{In…}：$offenders")
    }

    @Test
    fun `android code uses no unicode-wide digit class`() {
        val offenders = androidSources().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && DIGIT_CLASS.containsMatchIn(line) }
                .map { (index, _) -> "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}" }
        }
        assertTrue(offenders.isEmpty(), "这些正则用了 \\d/\\D，Android 上连全角、数学字体的数字也算，改写 [0-9]：$offenders")
    }

    private val root = File("..").canonicalFile

    private fun androidSources(): List<File> {
        val sourceSets = listOf(
            "shared/src/commonMain", "shared/src/jvmSharedMain", "shared/src/androidMain",
            "ui/src/commonMain", "ui/src/androidMain",
            "app/src/main",
        ).map(root::resolve).filter(File::isDirectory)
        assertTrue(sourceSets.isNotEmpty(), "找不到源码目录：${root.absolutePath}")
        return sourceSets.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    private companion object {
        // 源码里的反斜杠在原始字符串中是一个，在普通字符串中是两个，两种都算
        val JAVA_ONLY_PROPERTY = Regex("""\\{1,2}[pP]\{(?:Is|In)[A-Z]""")
        val DIGIT_CLASS = Regex("""\\{1,2}[dD]""")
    }
}
