package dev.piko.desktop.update

import com.github.luben.zstd.util.ZstdVersion
import dev.piko.desktop.LinuxDesktop
import dev.piko.desktop.isLinux
import dev.piko.desktop.isMacOs
import dev.piko.shared.log.PikoLog
import dev.piko.shared.update.ChecksumMismatchException
import dev.piko.shared.update.GithubReleaseClient
import dev.piko.shared.update.LatestRelease
import dev.piko.shared.update.ReleaseAsset
import dev.piko.update.AvailableUpdate
import dev.piko.update.GithubUpdateService
import dev.piko.update.UpdateStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class DesktopUpdate(
    override val version: String,
    override val notes: String,
    override val pageUrl: String,
    val plan: DesktopUpdatePlan,
) : AvailableUpdate {
    override val downloadSize: Long
        get() = when (plan) {
            is DesktopUpdatePlan.Patch -> plan.zip.size
            is DesktopUpdatePlan.Delta -> plan.delta.size
            is DesktopUpdatePlan.Installer -> plan.msi.size
            is DesktopUpdatePlan.Portable -> plan.zip.size
            is DesktopUpdatePlan.MacBundle -> plan.dmg.size
            // 控制文件在检查时已经下过了，不算在内
            is DesktopUpdatePlan.AppImage -> plan.delta?.plan?.downloadBytes ?: plan.appImage.size
            is DesktopUpdatePlan.Manual -> plan.download.size
        }
    override val canInstallInApp: Boolean get() = plan !is DesktopUpdatePlan.Manual
}

sealed interface DesktopUpdatePlan {
    /** 只替换每次构建都会变的那几个文件，其余与本机逐字节相同。 */
    data class Patch(val zip: ReleaseAsset, val manifest: UpdateManifest) : DesktopUpdatePlan

    /**
     * 换的文件与 [Patch] 相同，但只下以本机版本为基准的差分，约是完整补丁包的五分之一。
     * 本机文件对不上时退回 [fallback]。
     */
    data class Delta(val delta: ReleaseAsset, val fallback: Patch) : DesktopUpdatePlan

    /** 整包重装。MSI 的升级是先卸后装，要等 Piko 退出后再跑，否则弹文件占用的对话框。 */
    data class Installer(val msi: ReleaseAsset) : DesktopUpdatePlan

    /**
     * 便携版的整包更新：运行时之类也换了、只换补丁文件不够时，下整个便携 zip，只解出与本机不同的文件，
     * 由同一个脚本换上。不交给 MSI：便携版不在 Windows Installer 的登记里，msiexec 会另装一份到 LocalAppData。
     */
    data class Portable(val zip: ReleaseAsset, val manifest: UpdateManifest) : DesktopUpdatePlan

    /**
     * macOS：整个 .app 换成新 DMG 里的那份。不逐个换文件：包是签了名的（ad-hoc），改动封印里的任何一个文件
     * 签名即失效；整包替换时新包自带完好的签名。[bundle] 是本机正在用的 .app。
     */
    data class MacBundle(val dmg: ReleaseAsset, val bundle: File) : DesktopUpdatePlan

    /**
     * Linux：整个 AppImage 换成新版。[target] 是本机正在用的那个文件（APPIMAGE 给出的路径）。
     * [delta] 不为 null 时按 zsync 只下本机没有的块，拼不出来再整包下载；为 null 时（Release 没有 .zsync、
     * 本机文件读不了）直接整包下载。新文件先写在 [target] 旁边，校验过才改名换上。
     */
    data class AppImage(val appImage: ReleaseAsset, val target: File, val delta: ZsyncDelta?) : DesktopUpdatePlan

    /** zsync 差分：控制文件（Release 附件）与拿本机 AppImage 对照出的下载计划。 */
    class ZsyncDelta internal constructor(val control: ReleaseAsset, internal val plan: ZsyncPlan)

    /**
     * 只给下载页：开发时从 gradle 直接跑；macOS 上 .app 所在的位置换不了（在 DMG 里直接开的、所在目录不可写、
     * 被系统随机挪到只读位置运行的）；Linux 上不是以 AppImage 运行（解开的 app-image），或 AppImage 所在目录不可写。
     * [download] 是该平台的安装包，只用来显示大小。
     */
    data class Manual(val download: ReleaseAsset) : DesktopUpdatePlan
}

/**
 * 桌面端的应用内更新。以下说的是 Windows；macOS 整包换 .app（[DesktopUpdatePlan.MacBundle]），
 * Linux 整个换 AppImage、按 zsync 只下变了的块（[DesktopUpdatePlan.AppImage]）。
 *
 * 每个版本除了 MSI 与便携 zip，还附一份应用目录清单（files.json）与只含易变文件的 app.zip。
 * 检查时把本机应用目录与新版清单逐个比对：不同之处都在 app.zip 里就增量更新，否则 MSI 安装的
 * 走整包重装，便携版只给下载页。增量更新时若有以本机版本为基准的差分包（CI 为最近几个版本各出一份），
 * 改下差分包。两个架构的更新同一条路，附件名按架构区分。
 *
 * 替换文件要先退出自己：下载校验完停在 [UpdateStatus.ReadyToRestart]，用户同意后写一个
 * PowerShell 脚本、脱离本进程启动，由它等本进程退出、替换或跑 msiexec、再启动新版本，见
 * resources/update/apply-update.ps1。本进程经 [exitRequests] 请入口退出。
 */
class DesktopAppUpdater private constructor(
    private val installation: Installation?,
    private val currentVersion: String,
    checksOnStartup: Boolean,
    releases: GithubReleaseClient,
) : GithubUpdateService<DesktopUpdate>(
    releases = releases,
    currentVersion = currentVersion,
    checksOnStartup = checksOnStartup,
    log = { message, cause -> PikoLog.w("Update", message, cause) },
) {
    /** 本机应用目录。[exe] 是启动器，更新后由脚本重新拉起。 */
    private class Installation(val dir: File, val exe: File)

    private val json = Json { ignoreUnknownKeys = true }
    // 规范成长路径：java.io.tmpdir 在 Windows 上常是 8.3 短路径（用户名带空格或汉字时），交给更新脚本后
    // 与它列出的长路径对不上。脚本自己也不再按前缀截路径，这里再防一道
    private val stagingRoot = File(System.getProperty("java.io.tmpdir"), "piko-update").let { runCatching { it.canonicalFile }.getOrDefault(it) }

    private val mutableExitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 更新脚本已启动，入口收到后退出应用。 */
    val exitRequests: SharedFlow<Unit> = mutableExitRequests.asSharedFlow()

    override suspend fun resolve(release: LatestRelease): DesktopUpdate? {
        if (isLinux) return resolveAppImage(release)
        if (isMacOs) {
            val dmg = release.asset("piko-macos-$ARCH-${release.version}.dmg") ?: return null
            val bundle = installation?.let { replaceableBundle(it.exe) }
            val plan = if (bundle != null) DesktopUpdatePlan.MacBundle(dmg, bundle) else DesktopUpdatePlan.Manual(dmg)
            return DesktopUpdate(release.version, release.notes, release.pageUrl, plan)
        }
        val prefix = "piko-windows-$ARCH-${release.version}"
        // 三个附件由另一条流水线陆续挂上，缺一个都按还没有新版本处理
        val manifestAsset = release.asset("$prefix-files.json") ?: return null
        val zip = release.asset("$prefix-app.zip") ?: return null
        val msi = release.asset("$prefix.msi") ?: return null
        fun update(plan: DesktopUpdatePlan) = DesktopUpdate(release.version, release.notes, release.pageUrl, plan)

        val installation = installation ?: return update(DesktopUpdatePlan.Manual(msi))
        val manifest = json.decodeFromString(UpdateManifest.serializer(), fetchVerified(manifestAsset).decodeToString())
        return withContext(Dispatchers.IO) {
            when {
                canPatch(installation.dir, manifest) -> {
                    val patch = DesktopUpdatePlan.Patch(zip, manifest)
                    val delta = release.asset("$prefix-from-$currentVersion.zip")?.takeIf { zstdAvailable }
                    update(delta?.let { DesktopUpdatePlan.Delta(it, patch) } ?: patch)
                }
                isMsiInstall(installation.dir) -> update(DesktopUpdatePlan.Installer(msi))
                else -> release.asset("$prefix.zip")
                    ?.let { update(DesktopUpdatePlan.Portable(it, manifest)) }
                    ?: update(DesktopUpdatePlan.Manual(msi))
            }
        }
    }

    /**
     * Linux 只认 AppImage。查到新版时就拿本机的 AppImage 对照 .zsync 算好要下多少，弹窗里的大小才是实际下载量：
     * 版本之间多半只有 jar 与 AOT 缓存变了，整包一百多 MB，差分通常只有其中一小部分。
     * 对照要把本机文件整个读一遍（几秒），只在确有新版时做。
     */
    private suspend fun resolveAppImage(release: LatestRelease): DesktopUpdate? {
        val name = "piko-linux-$ARCH-${release.version}.AppImage"
        val appImage = release.asset(name) ?: return null
        fun update(plan: DesktopUpdatePlan) = DesktopUpdate(release.version, release.notes, release.pageUrl, plan)
        val target = installation?.exe?.takeIf { replaceableAppImage(it) } ?: return update(DesktopUpdatePlan.Manual(appImage))
        val delta = release.asset("$name.zsync")?.let { control ->
            runCatching {
                val parsed = ZsyncControl.parse(fetchVerified(control))
                val plan = withContext(Dispatchers.IO) { planZsync(parsed, target) }
                PikoLog.i("Update", "zsync：${plan.control.blockCount} 块，本机已有 ${plan.reusedBytes} 字节，要下 ${plan.downloadBytes} 字节，${plan.ranges.size} 段")
                DesktopUpdatePlan.ZsyncDelta(control, plan)
            }.onFailure { if (it is CancellationException) throw it; log("zsync 对照失败，改为整包下载", it) }.getOrNull()
        }
        return update(DesktopUpdatePlan.AppImage(appImage, target, delta))
    }

    /** AppImage 所在目录可写才能在旁边写新文件、改名换上；装在 /opt 一类位置的给下载页。 */
    private fun replaceableAppImage(file: File): Boolean =
        file.isFile && file.parentFile?.let { java.nio.file.Files.isWritable(it.toPath()) } == true

    /**
     * 带 [AUTO_INSTALL_PROPERTY] 启动时，开屏查到新版就直接下载、退出、安装，不等人点弹窗。
     * 只为端到端地实测更新：本机反复装卸测试版本、CI 的 macOS runner 上没有人点。
     */
    override suspend fun checkOnStartup(isIgnored: suspend (version: String) -> Boolean) {
        super.checkOnStartup(isIgnored)
        if (System.getProperty(AUTO_INSTALL_PROPERTY) != "true") return
        val update = (status as? UpdateStatus.Available)?.update ?: return
        // 每个版本只自动试一次：没装上时脚本照旧拉起旧版，旧版又查到同一个新版，不设限就一直循环，
        // 每一轮还把上一轮的暂存连同失败记号一起清掉，看不出为什么失败
        val attempted = stagingRoot.resolve("auto-${update.version}")
        if (attempted.exists()) {
            PikoLog.w("Update", "自动安装 ${update.version} 已试过一次，不再自动重试")
            return
        }
        stagingRoot.mkdirs()
        attempted.createNewFile()
        PikoLog.i("Update", "自动安装 ${update.version}：${update.own().plan::class.simpleName}")
        downloadAndInstall(update)
        (status as? UpdateStatus.ReadyToRestart)?.let { restartToInstall(it.update) }
        PikoLog.i("Update", "自动安装结束于 ${status::class.simpleName}")
    }

    // 桌面端的 Installing 是退出前的最后几秒：这时再查，状态会回到 Available，下载按钮又能点了。
    // Android 不能照此处理：系统安装器被划掉时可能不回调，Installing 会一直停着
    override suspend fun check(silent: Boolean) {
        if (status is UpdateStatus.Installing) return
        super.check(silent)
    }

    override suspend fun downloadAndInstall(update: AvailableUpdate) {
        val own = update.own()
        if (!own.canInstallInApp || isStagingOrLaunched()) return
        status = UpdateStatus.Downloading(update, 0f)
        status = try {
            withContext(Dispatchers.IO) { stage(own) }
            UpdateStatus.ReadyToRestart(update)
        } catch (e: CancellationException) {
            status = UpdateStatus.Available(update)
            throw e
        } catch (e: Exception) {
            downloadFailed(e, update)
        }
    }

    /** 下载到暂存目录并校验。增量更新还要把 app.zip 解开，逐个对照清单。 */
    private suspend fun stage(update: DesktopUpdate) {
        val staging = stagingDir(update)
        keepScriptLogs(staging)
        staging.deleteRecursively()
        staging.mkdirs()
        when (val plan = update.plan) {
            is DesktopUpdatePlan.Patch -> stagePatch(plan, staging, update)
            is DesktopUpdatePlan.Delta -> {
                val zip = download(plan.delta, staging.resolve(plan.delta.name), update)
                val installDir = checkNotNull(installation).dir
                try {
                    applyDelta(zip, plan.fallback.manifest, installDir, staging.resolve(PATCH_DIR))
                } catch (e: Throwable) {
                    // 本机的 jar 或 AOT 缓存被改动过、不是差分所基于的那一版，或者 zstd 的原生库半路出了错
                    // （LinkageError 是 Error，按 Exception 接不住）。都退回完整补丁包；取消照旧往上抛
                    if (e is CancellationException) throw e
                    if (e !is ChecksumMismatchException && e !is LinkageError) throw e
                    log("差分还原失败，改下完整补丁包", e)
                    staging.resolve(PATCH_DIR).deleteRecursively()
                    stagePatch(plan.fallback, staging, update)
                }
                zip.delete()
            }
            is DesktopUpdatePlan.Installer -> download(plan.msi, staging.resolve(plan.msi.name), update)
            is DesktopUpdatePlan.Portable -> {
                val zip = download(plan.zip, staging.resolve(plan.zip.name), update)
                extractChanged(zip, plan.manifest, checkNotNull(installation).dir, staging.resolve(PATCH_DIR))
                zip.delete()
            }
            is DesktopUpdatePlan.MacBundle -> download(plan.dmg, staging.resolve(plan.dmg.name), update)
            is DesktopUpdatePlan.AppImage -> stageAppImage(plan, update)
            is DesktopUpdatePlan.Manual -> error("只给下载页的更新不能在应用内安装")
        }
    }

    /**
     * 新的 AppImage 写在旧的旁边（同一个文件系统，换上时改名即可，不必再拷一遍），先试 zsync 差分，
     * 拼不出来或摘要对不上就删掉重下整包。两条路最后都按 GitHub 公布的 SHA-256 核对。
     */
    private suspend fun stageAppImage(plan: DesktopUpdatePlan.AppImage, update: DesktopUpdate) {
        val staged = stagedAppImage(plan.target)
        staged.delete()
        val delta = plan.delta
        if (delta != null) {
            try {
                val expected = plan.appImage.sha256 ?: throw ChecksumMismatchException("Release 没有公布 ${plan.appImage.name} 的摘要")
                var fetched = 0L
                var reported = 0f
                val progressLock = Any()
                assembleZsync(delta.plan, plan.target, staged) { range, onChunk ->
                    releases.downloadRange(plan.appImage, range) { buffer, length ->
                        onChunk(buffer, length)
                        // 几段同时在下；每涨 1% 才改一次状态，同 GithubReleaseClient.download
                        synchronized(progressLock) {
                            fetched += length
                            val progress = (fetched.toFloat() / delta.plan.downloadBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
                            if (progress - reported >= 0.01f) {
                                reported = progress
                                status = UpdateStatus.Downloading(update, progress)
                            }
                        }
                    }
                }
                val actual = staged.inputStream().use(::sha256Hex)
                if (actual != expected) throw ChecksumMismatchException("${plan.appImage.name}（zsync 拼出）: $actual != $expected")
                PikoLog.i("Update", "zsync 拼出新版 AppImage，下载 $fetched 字节，整包 ${plan.appImage.size} 字节")
                makeExecutable(staged)
                return
            } catch (e: CancellationException) {
                staged.delete()
                throw e
            } catch (e: Exception) {
                // 本机的 AppImage 被改过、镜像不认 Range、半路断网：都退回整包下载，整包也下不来才算失败
                log("zsync 差分未成，改下整包", e)
                staged.delete()
                status = UpdateStatus.Downloading(update, 0f)
            }
        }
        download(plan.appImage, staged, update)
        makeExecutable(staged)
    }

    private fun makeExecutable(file: File) {
        check(file.setExecutable(true, false)) { "无法给 ${file.name} 加上执行权限" }
    }

    private suspend fun stagePatch(plan: DesktopUpdatePlan.Patch, staging: File, update: DesktopUpdate) {
        val zip = download(plan.zip, staging.resolve(plan.zip.name), update)
        extractPatch(zip, plan.manifest, staging.resolve(PATCH_DIR))
        zip.delete()
    }

    private suspend fun download(asset: ReleaseAsset, target: File, update: DesktopUpdate): File {
        val expected = asset.sha256 ?: throw ChecksumMismatchException("Release 没有公布 ${asset.name} 的摘要")
        val digest = MessageDigest.getInstance("SHA-256")
        target.outputStream().use { out ->
            releases.download(
                asset,
                onChunk = { buffer, length ->
                    out.write(buffer, 0, length)
                    digest.update(buffer, 0, length)
                },
                onProgress = { status = UpdateStatus.Downloading(update, it) },
            )
        }
        val actual = digest.digest().toHex()
        if (actual != expected) {
            target.delete()
            throw ChecksumMismatchException("${asset.name}: $actual != $expected")
        }
        return target
    }

    private suspend fun fetchVerified(asset: ReleaseAsset): ByteArray {
        val expected = asset.sha256 ?: throw ChecksumMismatchException("Release 没有公布 ${asset.name} 的摘要")
        val bytes = ByteArrayOutputStream()
        releases.download(asset, onChunk = { buffer, length -> bytes.write(buffer, 0, length) }, onProgress = {})
        val content = bytes.toByteArray()
        val actual = MessageDigest.getInstance("SHA-256").digest(content).toHex()
        if (actual != expected) throw ChecksumMismatchException("${asset.name}: $actual != $expected")
        return content
    }

    override suspend fun restartToInstall(update: AvailableUpdate) {
        val own = update.own()
        val installation = installation ?: return
        if (status !is UpdateStatus.ReadyToRestart) return
        // 挂起之前就改状态：连点两下时，第二下看到的已不是 ReadyToRestart，不会再起一个脚本
        status = UpdateStatus.Installing(update)
        val started = runCatching {
            withContext(Dispatchers.IO) { launchApplyScript(own, installation) }
        }.onFailure { log("启动更新脚本失败", it) }
        if (started.isFailure) {
            status = UpdateStatus.Failed("无法启动更新程序", update)
            return
        }
        mutableExitRequests.tryEmit(Unit)
    }

    /**
     * 暂存目录正被下载写入，或已交给更新脚本。此时再下载会先清空暂存目录，脚本拿到的就是
     * 重下到一半的文件。检查与改状态之间没有挂起点，两次点击在主线程上排队，后一次必然看到
     * 前一次改过的状态。
     */
    private fun isStagingOrLaunched(): Boolean = when (status) {
        is UpdateStatus.Downloading, is UpdateStatus.ReadyToRestart, is UpdateStatus.Installing -> true
        else -> false
    }

    private fun launchApplyScript(update: DesktopUpdate, installation: Installation) {
        val plan = update.plan
        if (plan is DesktopUpdatePlan.MacBundle) return launchMacScript(update, plan, installation)
        if (plan is DesktopUpdatePlan.AppImage) return installAppImage(update, plan)
        val staging = stagingDir(update)
        val script = staging.resolve("apply-update.ps1")
        val resource = checkNotNull(javaClass.getResourceAsStream("/update/apply-update.ps1")) { "缺少更新脚本" }
        resource.use { input -> script.outputStream().use { input.copyTo(it) } }
        val (mode, source) = when (plan) {
            is DesktopUpdatePlan.Patch, is DesktopUpdatePlan.Delta, is DesktopUpdatePlan.Portable -> "patch" to staging.resolve(PATCH_DIR)
            is DesktopUpdatePlan.Installer -> "msi" to staging.resolve(plan.msi.name)
            is DesktopUpdatePlan.MacBundle, is DesktopUpdatePlan.AppImage, is DesktopUpdatePlan.Manual -> error("不是 Windows 的更新方式：$plan")
        }
        val checksums = staging.resolve(CHECKSUMS_FILE)
        checksums.writeText(stagedChecksums(update.plan, staging).joinToString("") { (sha256, path) -> "$sha256  $path\n" })
        // 新版应用目录的全部文件。换完之后 app 与 runtime 下不在其中的即旧版留下的，脚本删掉；MSI 模式用不上
        val keep = staging.resolve(KEEP_FILE)
        manifestOf(update.plan)?.let { manifest -> keep.writeText(manifest.files.joinToString("") { "${it.path}\n" }) }
        // JDK 在 Windows 上以 CREATE_NO_WINDOW 创建子进程，控制台程序不会闪出窗口；
        // 子进程不随父进程退出，脚本在本进程退出后接着跑
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.absolutePath,
            "-ProcessId", appProcessIds(installation).joinToString(","),
            "-InstallDir", installation.dir.absolutePath,
            "-Mode", mode,
            "-Source", source.absolutePath,
            "-Executable", installation.exe.name,
            "-LogFile", staging.resolve("update.log").absolutePath,
            "-Checksums", checksums.absolutePath,
            "-KeepList", if (keep.isFile) keep.absolutePath else "",
        )
            .directory(staging)
            // 脚本自己的日志之外，PowerShell 的解析错误之类只会出现在标准输出里
            .redirectErrorStream(true)
            .redirectOutput(staging.resolve("powershell.log"))
            .start()
    }

    /**
     * macOS 的更新脚本（resources/update/apply-update-mac.sh）：等退出、复核 DMG、整包换掉 .app、重新打开。
     * 用系统自带的 bash 跑，脱离本进程：父进程退出不带走子进程。
     */
    private fun launchMacScript(update: DesktopUpdate, plan: DesktopUpdatePlan.MacBundle, installation: Installation) {
        val staging = stagingDir(update)
        val script = staging.resolve("apply-update-mac.sh")
        val resource = checkNotNull(javaClass.getResourceAsStream("/update/apply-update-mac.sh")) { "缺少更新脚本" }
        resource.use { input -> script.outputStream().use { input.copyTo(it) } }
        val checksums = staging.resolve(CHECKSUMS_FILE)
        checksums.writeText(stagedChecksums(plan, staging).joinToString("") { (sha256, path) -> "$sha256  $path\n" })
        ProcessBuilder(
            "/bin/bash", script.absolutePath,
            appProcessIds(installation).joinToString(","),
            plan.bundle.absolutePath,
            staging.resolve(plan.dmg.name).absolutePath,
            checksums.absolutePath,
            staging.resolve("update.log").absolutePath,
        )
            .directory(staging)
            .redirectErrorStream(true)
            .redirectOutput(staging.resolve("bash.log"))
            .start()
    }

    /**
     * Linux：换上新的 AppImage，再起一个脱离本进程的 sh，等本进程退出后打开新版。
     *
     * 换文件不必等退出：正在运行的 AppImage 由它的运行时经 FUSE 挂载，挂载进程开着旧文件，改名只换掉目录项，
     * 旧的 inode 读到进程结束为止。所以在这里当场换，换不上（目录权限变了之类）时状态直接报给界面，旧文件原样不动。
     * 重新打开要等退出：新进程起来时本进程还握着单实例锁，它会把自己当成后来者，转交完参数就退出。
     * 与 macOS 一样不带本进程的 JAVA_TOOL_OPTIONS 与 AppImage 的环境变量，新版本按自己的启动方式起来。
     */
    private fun installAppImage(update: DesktopUpdate, plan: DesktopUpdatePlan.AppImage) {
        val staged = stagedAppImage(plan.target)
        // 同 macOS 与 Windows 的脚本：换上之前再核一遍，暂存文件在下载完到此刻之间可能被改写
        val expected = checkNotNull(plan.appImage.sha256)
        val actual = staged.inputStream().use(::sha256Hex)
        if (actual != expected) throw ChecksumMismatchException("${staged.name}: $actual != $expected")
        makeExecutable(staged)
        java.nio.file.Files.move(
            staged.toPath(),
            plan.target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        PikoLog.i("Update", "已换上 ${update.version} 的 AppImage")
        val staging = stagingDir(update)
        val relaunch = listOf("/bin/sh", "-c", RELAUNCH_SCRIPT, "piko-relaunch", ProcessHandle.current().pid().toString(), plan.target.absolutePath)
        fun start(command: List<String>) = ProcessBuilder(command)
            .directory(staging)
            .redirectErrorStream(true)
            .redirectOutput(staging.resolve("relaunch.log"))
            // _JPACKAGE_LAUNCHER 是 jpackage 的启动器在本进程里设的，带着它起新的启动器，后者以为 JVM 参数已经备好，
            // 不读 Piko.cfg，只打出 java 的用法就退出（实测）
            .apply { environment().keys.removeAll(listOf("APPIMAGE", "APPDIR", "ARGV0", "OWD", "JAVA_TOOL_OPTIONS", "_JPACKAGE_LAUNCHER")) }
            .start()
        // setsid 让它脱离本进程所在的会话，终端里启动的 Piko 退出时不连带它收到 SIGHUP；没有 setsid 的系统照样起
        runCatching { start(listOf("setsid") + relaunch) }.getOrElse { start(relaunch) }
    }

    /** 新 AppImage 暂存的位置：与旧文件同目录的隐藏文件，换上时改名即可。 */
    private fun stagedAppImage(target: File) = File(target.parentFile, ".${target.name}.piko-update")

    /**
     * 本机的 .app 能不能整包换掉。换不了的给下载页：
     * - 在挂载的 DMG 里直接打开的，所在卷只读；
     * - 从带隔离标记的位置打开、被系统随机挪到只读位置运行的（App Translocation，路径里有 AppTranslocation）；
     * - 所在目录当前用户写不进去的（没有管理员权限时的 /Applications）。
     * [exe] 是 jpackage 写进 jpackage.app-path 的启动器，在 Piko.app/Contents/MacOS 下。
     */
    private fun replaceableBundle(exe: File): File? {
        val bundle = exe.parentFile?.parentFile?.parentFile ?: return null
        val parent = bundle.parentFile ?: return null
        return bundle.takeIf {
            it.name.endsWith(".app") &&
                "/AppTranslocation/" !in it.absolutePath &&
                !it.absolutePath.startsWith("/Volumes/") &&
                java.nio.file.Files.isWritable(parent.toPath()) &&
                java.nio.file.Files.isWritable(it.toPath())
        }
    }

    /**
     * 脚本要等退出的进程：本进程，以及拉起它的启动器。jpackage 的 Windows 启动器会另起一个同名的
     * 子进程跑 JVM，自己留着等它结束（实测进程树里是两个 Piko.exe，父子关系）。只等本进程的话，
     * JVM 退出的那一刻启动器还在，替换 exe 删不掉旧的那份，msiexec 则撞上占用中的文件。
     */
    private fun appProcessIds(installation: Installation): List<Long> {
        val self = ProcessHandle.current()
        val launcher = self.parent().orElse(null)?.takeIf { parent ->
            parent.info().command().map { File(it).canonicalFile == installation.exe.canonicalFile }.orElse(false)
        }
        return listOfNotNull(self.pid(), launcher?.pid())
    }

    /**
     * 脚本安装前逐个复核的摘要，路径相对暂存目录。下载时已校验过一遍，这里再交给脚本，是因为
     * 从校验完到本进程退出之间，暂存目录仍可能被改写：Bilby 0.15.1 就把重下到一半的 MSI 交给了 msiexec。
     */
    private fun stagedChecksums(plan: DesktopUpdatePlan, staging: File): List<Pair<String, String>> = when (plan) {
        is DesktopUpdatePlan.Patch -> plan.manifest.patchChecksums()
        is DesktopUpdatePlan.Delta -> plan.fallback.manifest.patchChecksums()
        is DesktopUpdatePlan.Installer -> listOf(checkNotNull(plan.msi.sha256) to plan.msi.name)
        is DesktopUpdatePlan.MacBundle -> listOf(checkNotNull(plan.dmg.sha256) to plan.dmg.name)
        is DesktopUpdatePlan.AppImage -> error("AppImage 在本进程里换上，不经脚本")
        // 解出了哪些随本机情况而定，按实际解出的列
        is DesktopUpdatePlan.Portable -> {
            val byPath = plan.manifest.files.associateBy { it.path }
            stagedFiles(staging.resolve(PATCH_DIR)).map { path -> checkNotNull(byPath[path]).sha256 to "$PATCH_DIR/$path" }
        }
        is DesktopUpdatePlan.Manual -> error("只给下载页的更新不能在应用内安装")
    }

    private fun manifestOf(plan: DesktopUpdatePlan): UpdateManifest? = when (plan) {
        is DesktopUpdatePlan.Patch -> plan.manifest
        is DesktopUpdatePlan.Delta -> plan.fallback.manifest
        is DesktopUpdatePlan.Portable -> plan.manifest
        is DesktopUpdatePlan.Installer, is DesktopUpdatePlan.MacBundle, is DesktopUpdatePlan.AppImage, is DesktopUpdatePlan.Manual -> null
    }

    private fun UpdateManifest.patchChecksums() = files.filter { it.patch }.map { it.sha256 to "$PATCH_DIR/${it.path}" }

    private fun stagingDir(update: DesktopUpdate) = stagingRoot.resolve(update.version)

    /**
     * 重新暂存之前，把上一次脚本留下的日志追加进暂存根目录的 [HISTORY_LOG]。暂存目录每次下载都整个清掉，
     * 上一次为什么没装上就跟着没了，用户反馈时也无从查起。
     */
    private fun keepScriptLogs(staging: File) {
        runCatching {
            listOf("update.log", "powershell.log", "bash.log", "msiexec.log")
                .map(staging::resolve)
                .filter { it.isFile && it.length() > 0 }
                .forEach { log -> stagingRoot.resolve(HISTORY_LOG).appendText("== ${staging.name}/${log.name}\n${log.readText()}\n") }
        }.onFailure { log("保留更新日志失败", it) }
    }

    // apply-update.ps1 失败时在暂存目录留下这个文件；暂存目录要到下一次下载才清
    override fun takePreviousFailure(version: String): Boolean {
        val marker = stagingRoot.resolve(version).resolve("failed")
        if (!marker.isFile) return false
        log("上次更新到 $version 未完成：${marker.readText().trim()}", null)
        marker.delete()
        return true
    }

    private fun isMsiInstall(dir: File): Boolean {
        val upgradeCode = System.getProperty(WindowsInstaller.UPGRADE_CODE_PROPERTY) ?: return false
        return WindowsInstaller.isInstalledAt(upgradeCode, dir)
    }

    companion object {
        private const val PATCH_DIR = "files"
        private const val CHECKSUMS_FILE = "staged.sha256"
        private const val KEEP_FILE = "keep.txt"
        private const val HISTORY_LOG = "update-history.log"

        /** 换掉 Release 接口地址，用于在本机对着假的 Release 走一遍更新。 */
        private const val API_OVERRIDE_PROPERTY = "piko.update.api"

        /** 开屏查到新版即自动装上，见 [checkOnStartup]。 */
        private const val AUTO_INSTALL_PROPERTY = "piko.update.auto"

        /**
         * 带版本号打的包由 build.gradle.kts 写进这个属性。本地与非 tag 构建用的默认版本号可能与某个
         * 正式版相同，不能拿版本号本身判断是不是开发构建。
         */
        private const val RELEASE_BUILD_PROPERTY = "piko.release-build"

        private val ARCH = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64"

        /**
         * 安装包把 zstd-jni 的 DLL 放在资源目录的 zstd 子目录里。zstd-jni 默认把它从 jar 解压到
         * %TEMP%，进程占着删不掉，每次更新留一份。资源目录里没有时（gradle run、测试）沿用默认行为。
         */
        private fun useBundledZstd() {
            val dir = System.getProperty("compose.application.resources.dir") ?: return
            val dll = File(dir, "zstd/${System.mapLibraryName("libzstd-jni-${ZstdVersion.VERSION}")}")
            if (dll.isFile) System.setProperty("ZstdNativePath", dll.absolutePath)
        }

        /**
         * 清掉上一次增量更新没删成的 .old 与 .new。1.0.0 的更新脚本只等 JVM 退出，不等启动器，
         * 换 exe 时启动器还占着旧的那份，改了名删不掉，一直留在安装目录里。现在的脚本不会再留，
         * 这里收拾的是老版本更新过来时留下的。
         *
         * 只认补丁会换的那几类文件（根目录的 exe，app 下的 jar、cfg、aot 与 .jpackage.xml）加上 .old、.new：
         * 按扩展名一概删的话，用户放在便携版目录里的 notes.old 也会被删，旧版本还把下载放进过安装目录。
         */
        private fun removeUpdateLeftovers(installDir: File) {
            if (isMacOs) return
            fun leftovers(dir: File, patched: Regex) = dir.listFiles { file ->
                file.isFile && (file.name.endsWith(".old") || file.name.endsWith(".new")) &&
                    patched.matches(file.name.dropLast(4))
            }.orEmpty().toList()
            val found = leftovers(installDir, ROOT_PATCHED) + leftovers(installDir.resolve("app"), APP_PATCHED)
            found.forEach { leftover -> if (leftover.delete()) PikoLog.i("Update", "清掉更新残留 ${leftover.name}") }
        }

        // 补丁会换的文件，与 desktopApp/build.gradle.kts 的 UpdateArtifactsTask.isPatch 同一套
        private val ROOT_PATCHED = Regex("""[^\\/]+\.exe""")
        private val APP_PATCHED = Regex("""[^\\/]+\.(jar|cfg|aot)|\.jpackage\.xml""")

        /**
         * 等本进程退出后打开新的 AppImage，参数是本进程的 pid 与 AppImage 的路径。等上两分钟还没退出就放弃，
         * 不在用户手动重开之后再多开一个。
         */
        private val RELAUNCH_SCRIPT = """
            deadline=${'$'}(( ${'$'}(date +%s) + 120 ))
            while kill -0 "${'$'}1" 2>/dev/null; do
                if [ "${'$'}(date +%s)" -gt "${'$'}deadline" ]; then echo 'app did not exit within 120 s'; exit 1; fi
                sleep 0.2
            done
            exec "${'$'}2"
        """

        /** 上次下到一半或没换上的 AppImage 暂存文件，见 [stagedAppImage]。 */
        private fun removeStagedAppImage(appImage: File) {
            val staged = File(appImage.parentFile, ".${appImage.name}.piko-update")
            if (staged.isFile && staged.delete()) PikoLog.i("Update", "清掉未换上的 AppImage 暂存文件")
        }

        /** Flatpak 里返回 null：更新由 flatpak 负责，应用自己也写不进 /app。 */
        fun create(): DesktopAppUpdater? {
            if (isLinux && LinuxDesktop.isFlatpak) return null
            useBundledZstd()
            // jpackage 启动器写进这两个属性；gradle run 时都没有
            val version = System.getProperty("jpackage.app-version")
            // Linux 上要换的是 AppImage 文件本身，不是挂载目录里的启动器；解开的 app-image 没有 APPIMAGE，只给下载页
            val exe = if (isLinux) {
                LinuxDesktop.appImage?.also(::removeStagedAppImage)
            } else {
                System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }?.also { it.parentFile?.let(::removeUpdateLeftovers) }
            }
            val releases = GithubReleaseClient(
                http = HttpClient(OkHttp),
                userAgent = "Piko/${version ?: "dev"}",
                latestReleaseUrls = System.getProperty(API_OVERRIDE_PROPERTY)?.let(::listOf) ?: GithubReleaseClient.LATEST_RELEASE_SOURCES,
            )
            return DesktopAppUpdater(
                installation = exe?.let { Installation(it.parentFile, it) },
                currentVersion = version ?: "0",
                checksOnStartup = version != null && System.getProperty(RELEASE_BUILD_PROPERTY) == "true",
                releases = releases,
            )
        }
    }
}
