package dev.piko.ui.components

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.piko.shared.data.ThumbnailClarity
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.platform.resolveCoilModel

private val PIKPAK_THUMBNAIL_REGEX = Regex(
    """^https?://[^/]+/v0/(screenshot-thumbnails|thumbnails)/([A-Za-z0-9]+)/([0-9]+)/([0-9]+)(?:[?#].*)?$""",
    RegexOption.IGNORE_CASE,
)

private val VOLATILE_QUERY_KEYS = setOf(
    "e",
    "exp",
    "expire",
    "expires",
    "sig",
    "sign",
    "signature",
    "token",
    "auth_key",
    "ossaccesskeyid",
    "security-token",
    "x-oss-expires",
    "x-oss-signature",
    "x-oss-access-key-id",
    "x-oss-security-token",
    "x-amz-expires",
    "x-amz-signature",
    "x-amz-date",
    "x-amz-credential",
    "x-amz-security-token",
    "st",
    "se",
    "sp",
    "sv",
    "sr",
    "t",
    "ts",
)

/**
 * 按 [clarity] 将 PikPak 无签名缩略图地址解析至对应清晰度规格：
 * - `HIGH` 且 [allowUpgrade] 为 true 时将 `/240/720` 等标准规格提升至 `/720/2048`；
 * - 其余情况保留原始 [model] 不变。
 */
fun resolveThumbnailModelForClarity(
    model: Any,
    clarity: ThumbnailClarity,
    allowUpgrade: Boolean = true,
): Any {
    if (!allowUpgrade || !clarity.requestLarge || model !is String) return model
    val match = PIKPAK_THUMBNAIL_REGEX.matchEntire(model) ?: return model
    val kind = match.groupValues[1]
    val gcid = match.groupValues[2].uppercase()
    val schemeAndHost = model.substringBefore("/v0/")
    return "$schemeAndHost/v0/$kind/$gcid/720/2048"
}

/**
 * 判断 [model] 是否会在 [clarity] 下被提升至高清 URL，用于在远端缺少 720/2048 缩略图时执行一次性回退。
 */
fun isUpgradedThumbnailClarityUrl(model: Any, clarity: ThumbnailClarity): Boolean {
    if (!clarity.requestLarge || model !is String) return false
    val resolved = resolveThumbnailModelForClarity(model, clarity, allowUpgrade = true)
    return resolved != model
}

/**
 * 为缩略图生成跨重启稳定的内容身份键：
 * 1. PikPak `/v0/(screenshot-)?thumbnails/<GCID>/<w>/<h>` 统一归并为 `pikpak:<kind>:<GCID>`，不受区域 CDN 子域或签名参数变化影响；
 * 2. 其他带易变签名参数（`Expires`/`Signature`/`token`/`e`/`sig` 等）的远程 URL 自动剥离易变查询参数，避免每次重启 100% 穿透磁盘缓存；
 * 3. 本机路径与非 HTTP URI 保持原样。
 */
fun stableThumbnailIdentity(model: Any, stableId: String? = null): String {
    if (model !is String) {
        return stableId?.takeIf { it.isNotBlank() }?.let { "id:$it" } ?: model.toString()
    }
    PIKPAK_THUMBNAIL_REGEX.matchEntire(model)?.let { match ->
        val kind = if (match.groupValues[1].equals("screenshot-thumbnails", ignoreCase = true)) "vid" else "img"
        val gcid = match.groupValues[2].uppercase()
        return "pikpak:$kind:$gcid"
    }
    if (model.startsWith("http://", ignoreCase = true) || model.startsWith("https://", ignoreCase = true)) {
        val withoutFragment = model.substringBefore('#')
        val qIndex = withoutFragment.indexOf('?')
        if (qIndex < 0) return withoutFragment
        val base = withoutFragment.substring(0, qIndex)
        val query = withoutFragment.substring(qIndex + 1)
        val params = query.split('&').filter { it.isNotEmpty() }
        val hasVolatile = params.any { part ->
            val key = part.substringBefore('=').lowercase()
            key in VOLATILE_QUERY_KEYS || key.startsWith("x-oss-") || key.startsWith("x-amz-")
        }
        if (!hasVolatile) return withoutFragment
        if (!stableId.isNullOrBlank()) return "stable:$stableId"
        val kept = params.filterNot { part ->
            val key = part.substringBefore('=').lowercase()
            key in VOLATILE_QUERY_KEYS || key.startsWith("x-oss-") || key.startsWith("x-amz-")
        }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }
    return model
}

private fun isRemoteHttpUrl(model: Any): Boolean =
    model is String && (model.startsWith("http://", ignoreCase = true) || model.startsWith("https://", ignoreCase = true))

/**
 * 确定性 Coil 磁盘缓存键（仅用于远程 HTTP(S) 缩略图）：
 * - `LOW` 与 `STANDARD` 共用 `sd` 档磁盘缓存，切换时无需重复下载；
 * - `HIGH` 使用 `hd` 档（若回退到原图则复用 `sd` 档）。
 */
fun thumbnailDiskCacheKey(
    model: Any,
    clarity: ThumbnailClarity,
    allowUpgrade: Boolean = true,
    stableId: String? = null,
): String? {
    if (!isRemoteHttpUrl(model)) return null
    val tier = if (clarity.requestLarge && allowUpgrade && isUpgradedThumbnailClarityUrl(model, clarity)) {
        clarity.diskTierKey
    } else {
        "sd"
    }
    return "piko-thumb:v1:${stableThumbnailIdentity(model, stableId)}:$tier"
}

/**
 * 确定性 Coil 内存缓存键，按清晰度或防窥模糊规格隔离解码位图，同时共享底层磁盘缓存。
 */
fun thumbnailMemoryCacheKey(
    model: Any,
    clarity: ThumbnailClarity,
    blurredDecodePx: Int? = null,
    stableId: String? = null,
): String {
    val identity = stableThumbnailIdentity(model, stableId)
    return if (blurredDecodePx != null) {
        "piko-spoiler:$blurredDecodePx:$identity"
    } else {
        "piko-thumb:v1:$identity:${clarity.name}"
    }
}

/**
 * 构建带稳定缓存键与清晰度解码上限的缩略图 [ImageRequest]，供列表、海报墙、瀑布流与预取共用。
 */
fun buildThumbnailImageRequest(
    context: PlatformContext,
    platform: PikoPlatform,
    rawModel: Any,
    clarity: ThumbnailClarity,
    allowHighResUpgrade: Boolean = true,
    stableId: String? = null,
): ImageRequest {
    val effectiveModel = resolveThumbnailModelForClarity(rawModel, clarity, allowUpgrade = allowHighResUpgrade)
    val resolvedData = platform.resolveCoilModel(effectiveModel)
    val diskKey = thumbnailDiskCacheKey(rawModel, clarity, allowUpgrade = allowHighResUpgrade, stableId = stableId)
    val memoryKey = thumbnailMemoryCacheKey(rawModel, clarity, stableId = stableId)
    return ImageRequest.Builder(context)
        .data(resolvedData)
        .size(clarity.decodeMaxPx, clarity.decodeMaxPx)
        .precision(Precision.INEXACT)
        .memoryCacheKey(memoryKey)
        .apply {
            if (diskKey != null) diskCacheKey(diskKey)
        }
        .build()
}
