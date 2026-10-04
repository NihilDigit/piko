package dev.piko.desktop.update

import com.github.luben.zstd.ZstdDecompressCtx
import com.github.luben.zstd.ZstdException
import dev.piko.shared.PikoHome
import dev.piko.shared.log.PikoLog
import dev.piko.shared.update.ChecksumMismatchException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable

/**
 * 一个版本的应用目录清单（Release 附件 piko-windows-<架构>-<版本>-files.json），由
 * desktopApp 的 packageReleaseUpdate 任务生成。[ManifestFile.patch] 标出装进 app.zip 的文件，
 * 即每次构建都会变的那几个：exe、jar、AOT 缓存与启动配置。
 */
@Serializable
data class UpdateManifest(
    val version: String,
    val files: List<ManifestFile>,
)

@Serializable
data class ManifestFile(
    /** 相对应用目录，以 / 分隔。 */
    val path: String,
    val size: Long,
    val sha256: String,
    /** 毫秒。AOT 缓存按 jar 的修改时间校验，替换后要原样还原。 */
    val mtime: Long,
    val patch: Boolean = false,
)

/**
 * 补丁包之外的文件，本机都与新版本逐字节相同时才能增量更新。本机多出的文件不管：
 * 类路径写在 Piko.cfg 里，多余的文件不会被加载。
 *
 * 补丁包里的文件不比较，一律替换：jar 即使内容没变，修改时间也跟新的 AOT 缓存对不上。
 */
internal fun canPatch(installDir: File, manifest: UpdateManifest): Boolean =
    manifest.files.filterNot { it.patch }.all { entry ->
        val file = installDir.resolve(entry.path)
        file.isFile && file.length() == entry.size && file.inputStream().use(::sha256Hex) == entry.sha256
    }

/**
 * 把补丁包解到 [target]，逐个对照清单校验并还原修改时间。只收清单里标了 patch 的路径，
 * 清单里有而包里缺的也算失败：替换一半的应用目录比不更新更糟。
 */
internal fun extractPatch(zip: File, manifest: UpdateManifest, target: File) {
    val expected = manifest.files.filter { it.patch }.associateBy { it.path }
    val root = target.canonicalFile
    val seen = mutableSetOf<String>()
    ZipFile(zip).use { archive ->
        for (entry in archive.entries()) {
            if (entry.isDirectory) continue
            val spec = expected[entry.name] ?: throw ChecksumMismatchException("补丁包里有清单外的文件：${entry.name}")
            val out = root.resolve(spec.path).canonicalFile
            check(out.path.startsWith(root.path + File.separator)) { "路径越界：${spec.path}" }
            out.parentFile.mkdirs()
            val actual = archive.getInputStream(entry).use { input ->
                out.outputStream().use { output -> sha256Hex(input) { buffer, length -> output.write(buffer, 0, length) } }
            }
            if (actual != spec.sha256 || out.length() != spec.size) {
                throw ChecksumMismatchException("${spec.path}: $actual != ${spec.sha256}")
            }
            out.setLastModified(spec.mtime)
            seen += spec.path
        }
    }
    val missing = expected.keys - seen
    if (missing.isNotEmpty()) throw ChecksumMismatchException("补丁包缺少：${missing.joinToString()}")
}

/**
 * 便携版的整包更新：从便携 zip（Release 附件 piko-windows-<架构>-<版本>.zip）里只解出要换的文件到 [target]，
 * 即补丁文件全部，加上与本机内容不同的其余文件（运行时、mpv 这些换了版本时）。与本机相同的不解，
 * 替换时少动一个是一个。补丁文件即使内容没变也照解：替换后按暂存的那组 jar 清理旧 jar，漏掉一个就被当成旧的删了。
 *
 * zip 由 CI 的 Compress-Archive 打出，条目带一层应用目录名（Piko/app/...），分隔符可能是反斜杠，这里都去掉再比对清单。
 * 清单里的文件本机没有、包里也没有时算失败，同 [extractPatch]。
 */
internal fun extractChanged(zip: File, manifest: UpdateManifest, installDir: File, target: File) {
    val expected = manifest.files.associateBy { it.path }
    val root = target.canonicalFile
    val unchanged = manifest.files.filter { entry ->
        !entry.patch && installDir.resolve(entry.path).let { file ->
            file.isFile && file.length() == entry.size && file.inputStream().use(::sha256Hex) == entry.sha256
        }
    }.mapTo(HashSet()) { it.path }
    val seen = mutableSetOf<String>()
    ZipFile(zip).use { archive ->
        for (entry in archive.entries()) {
            if (entry.isDirectory) continue
            val path = entry.name.replace('\\', '/').substringAfter('/')
            // 便携标记只进 zip、不在清单里（清单在放标记之前生成）。也不装进来：经应用内更新升上来的便携版
            // 数据原本在 ~/.piko，凭空多出标记会让它改读 data/，登录与设置像是丢了
            if (path == PikoHome.PORTABLE_MARKER) continue
            val spec = expected[path] ?: throw ChecksumMismatchException("便携包里有清单外的文件：${entry.name}")
            if (path in unchanged) continue
            val out = root.resolve(spec.path).canonicalFile
            check(out.path.startsWith(root.path + File.separator)) { "路径越界：${spec.path}" }
            out.parentFile.mkdirs()
            val actual = archive.getInputStream(entry).use { input ->
                out.outputStream().use { output -> sha256Hex(input) { buffer, length -> output.write(buffer, 0, length) } }
            }
            if (actual != spec.sha256 || out.length() != spec.size) {
                throw ChecksumMismatchException("${spec.path}: $actual != ${spec.sha256}")
            }
            out.setLastModified(spec.mtime)
            seen += spec.path
        }
    }
    val missing = expected.keys - unchanged - seen
    if (missing.isNotEmpty()) throw ChecksumMismatchException("便携包缺少：${missing.joinToString()}")
}

/** [target] 下已解出的文件，路径相对 [target]、以 / 分隔。 */
internal fun stagedFiles(target: File): List<String> {
    val root = target.canonicalFile
    return root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
}

/**
 * 差分包（Release 附件 piko-windows-<架构>-<版本>-from-<旧版本>.zip）里每个补丁文件是一个
 * `<路径>.zst`，由 CI 以旧版的对应文件为前缀字典（zstd --patch-from）压成，见
 * .github/scripts/delta-updates.sh。拿本机文件作字典还原，结果与 [extractPatch] 解出的逐字节相同，
 * 同样逐个对照清单、还原修改时间。
 *
 * 对应文件默认是同一路径。jar 名里带内容哈希，改过的模块 jar 每次构建都换名，另带一个
 * `<路径>.base`，内容是旧版那个文件的路径。两边都没有的文件，CI 按普通 zstd 压缩，不需要字典。
 *
 * 本机文件不是差分所基于的那一版时，还原要么被 zstd 的帧校验拦下，要么摘要对不上，
 * 两者都抛 [ChecksumMismatchException]，由调用方改下完整的补丁包。
 */
internal fun applyDelta(zip: File, manifest: UpdateManifest, installDir: File, target: File) {
    val root = target.canonicalFile
    val installRoot = installDir.canonicalFile
    ZipFile(zip).use { archive ->
        val expected = manifest.files.filter { it.patch }
        val allowed = expected.flatMap { listOf("${it.path}.zst", "${it.path}.base") }.toSet()
        val names = archive.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
        val foreign = names - allowed
        if (foreign.isNotEmpty()) throw ChecksumMismatchException("差分包里有清单外的文件：${foreign.joinToString()}")
        for (spec in expected) {
            val entry = archive.getEntry("${spec.path}.zst") ?: throw ChecksumMismatchException("差分包缺少：${spec.path}")
            val out = root.resolve(spec.path).canonicalFile
            check(out.path.startsWith(root.path + File.separator)) { "路径越界：${spec.path}" }
            val renamedFrom = archive.getEntry("${spec.path}.base")
                ?.let { base -> archive.getInputStream(base).use { it.readBytes().decodeToString().trim() } }
            val basePath = renamedFrom ?: spec.path
            val baseFile = installRoot.resolve(basePath).canonicalFile
            check(baseFile.path.startsWith(installRoot.path + File.separator)) { "路径越界：$basePath" }
            if (renamedFrom != null && !baseFile.isFile) throw ChecksumMismatchException("本机缺少差分的基准：$basePath")
            val base = baseFile.takeIf { it.isFile }?.readBytes()
            val restored = try {
                ZstdDecompressCtx().use { ctx ->
                    // 旧版没有对应文件时，CI 按普通 zstd 压缩，不需要字典
                    if (base != null) ctx.loadDict(base)
                    ctx.decompress(archive.getInputStream(entry).use { it.readBytes() }, Math.toIntExact(spec.size))
                }
            } catch (e: ZstdException) {
                throw ChecksumMismatchException("${spec.path}: ${e.message}")
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(restored).toHex()
            if (actual != spec.sha256 || restored.size.toLong() != spec.size) {
                throw ChecksumMismatchException("${spec.path}: $actual != ${spec.sha256}")
            }
            out.parentFile.mkdirs()
            out.writeBytes(restored)
            out.setLastModified(spec.mtime)
        }
    }
}

/**
 * zstd 的解码能不能用。用不了时差分更新不可用，改下完整补丁包，原因记进日志。
 *
 * 探的是差分实际要用的解码上下文，不只是加载原生库：Windows ARM64 上 zstd-jni 1.5.7-20 的 DLL 能加载，
 * 却缺了 ZstdDecompressCtx.init 这个 JNI 方法（CI 上实测 UnsatisfiedLinkError）。UnsatisfiedLinkError 是 Error
 * 不是 Exception，不先探一下的话，更新会在还原差分时直接崩掉，接不住。
 */
internal val zstdAvailable: Boolean by lazy {
    runCatching { ZstdDecompressCtx().close() }
        .onFailure { PikoLog.w("Update", "zstd 原生库加载失败，差分更新不可用，改下完整补丁包", it) }
        .isSuccess
}

internal fun sha256Hex(input: InputStream, onChunk: (ByteArray, Int) -> Unit = { _, _ -> }): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(256 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        onChunk(buffer, read)
    }
    return digest.digest().toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
