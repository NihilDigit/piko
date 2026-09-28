package dev.piko.shared.naming

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Android 的 java.util.regex 底下是 ICU，不认 Java 专有的 \p{IsXxx}、\p{InXxx}：Android 8 上编译即抛
 * PatternSyntaxException，写在顶层 val 里的正则让整个类初始化失败，1.0.0 在 Android 8 上一解析文件名就崩。
 * 桌面测试跑在 HotSpot 上照常通过，CI 的模拟器是 API 34 且不走命名解析，都抓不到，只能扫源码。
 * 脚本写 \p{script=Han}，区块写 \p{block=CJKUnifiedIdeographs}，两边都认。
 */
class AndroidRegexGuardTest {
    @Test
    fun `android code uses no java-only unicode property prefixes`() {
        val root = File("..").canonicalFile
        val sourceSets = listOf(
            "shared/src/commonMain", "shared/src/jvmSharedMain", "shared/src/androidMain",
            "ui/src/commonMain", "ui/src/androidMain",
            "app/src/main",
        ).map(root::resolve).filter(File::isDirectory)
        assertTrue(sourceSets.isNotEmpty(), "找不到源码目录：${root.absolutePath}")
        val offenders = sourceSets.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                file.readLines().withIndex()
                    // 注释里提到这种写法不算，说明为什么不用它的注释正需要写出它
                    .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && JAVA_ONLY_PROPERTY.containsMatchIn(line) }
                    .map { (index, _) -> "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}" }
            }
        }
        assertTrue(offenders.isEmpty(), "这些正则用了 Android 不认的 \\p{Is…}/\\p{In…}：$offenders")
    }

    private companion object {
        // 源码里的反斜杠在原始字符串中是一个，在普通字符串中是两个，两种都算
        val JAVA_ONLY_PROPERTY = Regex("""\\{1,2}[pP]\{(?:Is|In)[A-Z]""")
    }
}
