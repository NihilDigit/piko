package dev.piko.desktop.update

import dev.piko.shared.log.PikoLog
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * 还原差分包里的 zstd 帧：以本机旧文件为前缀（zstd --patch-from 的对端），经 FFM 直调 libzstd 的 C API。
 *
 * 不用 zstd-jni 的 Java 类：它的 win_aarch64 原生库只导出 ZSTD_* C 函数，一个 JNI 方法都没有，
 * ZstdDecompressCtx 在 Windows ARM64 上抛 UnsatisfiedLinkError（CI 实测）。它在 JDK 22 以上改走的 FFM 绑定
 * 也只覆盖了一部分类，ZstdDecompressCtx 不在其中。x64 的库两样都导出，两个架构因此走同一条路，
 * zstd-jni 只在构建时提供原生库文件，运行时不再依赖它。
 *
 * 一次性解码（ZSTD_decompressDCtx）不受流式解码的 windowLogMax 限制，CLI 为大文件自动加大窗口压出的帧
 * 也能还原。
 */
class ZstdPatch private constructor(lookup: SymbolLookup) {
    private val linker = Linker.nativeLinker()

    private fun function(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(lookup.find(name).orElseThrow { NoSuchElementException(name) }, descriptor)

    private val createDCtx = function(lookup, "ZSTD_createDCtx", FunctionDescriptor.of(ADDRESS))
    private val freeDCtx = function(lookup, "ZSTD_freeDCtx", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
    private val refPrefix = function(lookup, "ZSTD_DCtx_refPrefix", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG))
    private val decompressDCtx = function(
        lookup,
        "ZSTD_decompressDCtx",
        FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG),
    )
    private val isError = function(lookup, "ZSTD_isError", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
    private val getErrorName = function(lookup, "ZSTD_getErrorName", FunctionDescriptor.of(ADDRESS, JAVA_LONG))

    /** [base] 为 null 时 [frame] 是普通的 zstd 帧。还原结果不是 [size] 字节，或 zstd 报错时抛 [ZstdPatchException]。 */
    internal fun decode(base: ByteArray?, frame: ByteArray, size: Int): ByteArray {
        Arena.ofConfined().use { arena ->
            val ctx = createDCtx.invokeWithArguments() as MemorySegment
            if (ctx == MemorySegment.NULL) throw OutOfMemoryError("ZSTD_createDCtx")
            try {
                if (base != null) {
                    // 前缀只被引用不被复制，解码结束前这段内存要一直有效，所以随 arena 一起释放
                    val prefix = arena.copyOf(base)
                    requireOk(refPrefix.invokeWithArguments(ctx, prefix, base.size.toLong()) as Long)
                }
                val source = arena.copyOf(frame)
                val target = arena.allocate(size.toLong().coerceAtLeast(1))
                val written = requireOk(
                    decompressDCtx.invokeWithArguments(ctx, target, size.toLong(), source, frame.size.toLong()) as Long,
                )
                if (written != size.toLong()) throw ZstdPatchException("还原出 $written 字节，应为 $size")
                return target.asSlice(0, written).toArray(JAVA_BYTE)
            } finally {
                freeDCtx.invokeWithArguments(ctx)
            }
        }
    }

    private fun Arena.copyOf(bytes: ByteArray): MemorySegment =
        allocate(bytes.size.toLong().coerceAtLeast(1)).also { MemorySegment.copy(bytes, 0, it, JAVA_BYTE, 0, bytes.size) }

    private fun requireOk(code: Long): Long {
        if (isError.invokeWithArguments(code) as Int == 0) return code
        val name = (getErrorName.invokeWithArguments(code) as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)
        throw ZstdPatchException(name)
    }

    companion object {
        /**
         * 载入 [directory] 里唯一的 libzstd（构建时从 zstd-jni 的按平台 jar 里取出，见 build.gradle.kts 的
         * bundledAppResources）。库一直载着，不随哪次解码卸掉。载不了时抛异常。
         */
        fun open(directory: File): ZstdPatch {
            val library = directory.listFiles { file -> file.isFile && file.name.endsWith(".dll") }
                ?.singleOrNull() ?: throw NoSuchElementException("$directory 里没有唯一的 zstd 库")
            return ZstdPatch(SymbolLookup.libraryLookup(library.toPath(), Arena.global()))
        }

        /**
         * 安装包资源目录 zstd 子目录里的那一份。没有资源目录（gradle run）、不是 Windows 包、或载入失败时为 null，
         * 差分更新不可用，改下完整补丁包，原因记进日志。选更新方式之前先问这里。
         */
        val bundled: ZstdPatch? by lazy {
            val resources = System.getProperty("compose.application.resources.dir") ?: return@lazy null
            val directory = File(resources, "zstd").takeIf { it.isDirectory } ?: return@lazy null
            runCatching { open(directory) }
                .onFailure { PikoLog.w("Update", "libzstd 加载失败，差分更新不可用，改下完整补丁包", it) }
                .getOrNull()
        }
    }
}

internal class ZstdPatchException(message: String) : Exception(message)
