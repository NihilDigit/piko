package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileStat
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class ContentKey(val gcid: String, val sizeBytes: Long) {
    companion object {
        fun of(gcid: String, sizeBytes: Long): ContentKey? =
            gcid.takeIf { it.isNotBlank() && sizeBytes >= 0 }?.let { ContentKey(it.uppercase(), sizeBytes) }
        fun of(file: FileStat): ContentKey? = of(file.hash, file.sizeBytes)
    }
}

@Serializable
data class TranscodeVariantMetadata(
    val name: String,
    val mediaId: String,
    val width: Int = 0,
    val height: Int = 0,
    val bitRate: Long = 0L,
    val durationSeconds: Long = 0L,
    val videoType: String = "",
    val streamSizeBytes: Long? = null,
    val unreadable: Boolean = false,
) {
    val estimatedSizeBytes: Long?
        get() = streamSizeBytes ?: if (bitRate > 0L && durationSeconds > 0L) (bitRate * durationSeconds) / 8L else null
}

/** Rank 0 is listing/legacy data; higher ranks must identify original media, not a transcode. */
@Serializable
data class ContentMetadata(
    val durationMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mimeType: String? = null,
    val cid: String? = null,
    val qualityRank: Int = 0,
    val transcodes: List<TranscodeVariantMetadata>? = null,
) {
    val isEmpty: Boolean
        get() = durationMillis == null &&
            width == null &&
            height == null &&
            mimeType.isNullOrBlank() &&
            cid.isNullOrBlank() &&
            transcodes == null

    fun merge(incoming: ContentMetadata): ContentMetadata {
        val preferred = if (incoming.qualityRank > qualityRank) incoming else this
        val fallback = if (preferred === this) incoming else this
        return ContentMetadata(
            durationMillis = preferred.durationMillis?.takeIf { it >= 0 } ?: fallback.durationMillis?.takeIf { it >= 0 },
            width = preferred.width?.takeIf { it > 0 } ?: fallback.width?.takeIf { it > 0 },
            height = preferred.height?.takeIf { it > 0 } ?: fallback.height?.takeIf { it > 0 },
            mimeType = preferred.mimeType?.takeIf { it.isNotBlank() } ?: fallback.mimeType?.takeIf { it.isNotBlank() },
            cid = preferred.cid?.takeIf { it.isNotBlank() } ?: fallback.cid?.takeIf { it.isNotBlank() },
            qualityRank = maxOf(qualityRank, incoming.qualityRank),
            transcodes = mergeTranscodes(incoming),
        )
    }

    private fun mergeTranscodes(incoming: ContentMetadata): List<TranscodeVariantMetadata>? {
        val currentList = this.transcodes
        val incomingList = incoming.transcodes
        if (currentList == null && incomingList == null) return null
        if (currentList == null) return incomingList
        if (incomingList == null) return currentList
        val useIncomingAsBase = if (incoming.qualityRank != qualityRank) {
            incoming.qualityRank > qualityRank
        } else {
            incomingList.isNotEmpty() || currentList.isEmpty()
        }
        val base = if (useIncomingAsBase) incomingList else currentList
        val other = if (useIncomingAsBase) currentList else incomingList
        if (base.isEmpty()) return if (other.isNotEmpty() && !useIncomingAsBase) other else emptyList()
        val existingByMediaId = other.associateBy { it.mediaId }
        val existingByName = other.associateBy { it.name }
        return base.map { item ->
            val existingItem = existingByMediaId[item.mediaId] ?: existingByName[item.name]
            if (existingItem == null) {
                item
            } else {
                val mergedSize = item.streamSizeBytes?.takeIf { it > 0L }
                    ?: existingItem.streamSizeBytes?.takeIf { it > 0L }
                val mergedUnreadable = item.unreadable || (existingItem.unreadable && item.streamSizeBytes == null)
                item.copy(
                    width = item.width.takeIf { it > 0 } ?: existingItem.width,
                    height = item.height.takeIf { it > 0 } ?: existingItem.height,
                    bitRate = item.bitRate.takeIf { it > 0L } ?: existingItem.bitRate,
                    durationSeconds = item.durationSeconds.takeIf { it > 0L } ?: existingItem.durationSeconds,
                    videoType = item.videoType.ifBlank { existingItem.videoType },
                    streamSizeBytes = if (mergedUnreadable) null else mergedSize,
                    unreadable = mergedUnreadable,
                )
            }
        }
    }

    fun withTranscodeProbe(
        mediaId: String,
        streamSizeBytes: Long?,
        unreadable: Boolean = false,
    ): ContentMetadata {
        val current = transcodes ?: return this
        var changed = false
        val updated = current.map { item ->
            if (item.mediaId != mediaId) {
                item
            } else {
                val nextSize = if (unreadable) null else (streamSizeBytes?.takeIf { it > 0L } ?: item.streamSizeBytes)
                val nextItem = item.copy(streamSizeBytes = nextSize, unreadable = unreadable)
                if (nextItem != item) changed = true
                nextItem
            }
        }
        return if (changed) copy(transcodes = updated) else this
    }

    companion object {
        fun of(file: FileStat) = of(file.params, file.mimeType)

        fun of(params: Map<String, String>, mimeType: String) = ContentMetadata(
            durationMillis = params["duration"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                ?.let { (it * 1000).toLong() },
            width = params["width"]?.toIntOrNull()?.takeIf { it > 0 },
            height = params["height"]?.toIntOrNull()?.takeIf { it > 0 },
            mimeType = mimeType.takeIf { it.isNotBlank() },
            cid = params["piko_vault_cid"],
        )
    }
}

internal interface BatchDriveDetailObserver : DriveDetailObserver {
    fun observeBatch(details: Collection<FileDetail>)
}

internal fun FileDetail.toObservedContentMetadata(): Pair<ContentKey, ContentMetadata>? {
    val key = ContentKey.of(hash, sizeBytes) ?: return null
    val original = medias.firstOrNull { it.isOrigin }?.video
    val listed = ContentMetadata.of(params, mimeType)
    return key to ContentMetadata(
        durationMillis = original?.duration?.takeIf { it > 0 }?.let { it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 } ?: listed.durationMillis,
        width = original?.width?.takeIf { it > 0 } ?: listed.width,
        height = original?.height?.takeIf { it > 0 } ?: listed.height,
        mimeType = listed.mimeType,
        cid = listed.cid ?: params["cid"],
        qualityRank = if (original != null) 2 else 1,
        transcodes = extractTranscodeVariants(),
    )
}

internal fun FileDetail.extractTranscodeVariants(): List<TranscodeVariantMetadata>? {
    val playable = medias.mapNotNull { media ->
        val video = media.video ?: return@mapNotNull null
        val qualityName = media.mediaName.ifBlank { media.resolutionName }.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        if (media.isOrigin || media.link.url.isBlank() || media.mediaId.isBlank()) return@mapNotNull null
        TranscodeVariantMetadata(
            name = qualityName,
            mediaId = media.mediaId,
            width = video.width.coerceAtLeast(0),
            height = video.height.coerceAtLeast(0),
            bitRate = video.bitRate.coerceAtLeast(0L),
            durationSeconds = video.duration.coerceAtLeast(0L),
            videoType = video.videoType,
        )
    }
    return when {
        playable.isNotEmpty() -> playable
        medias.isNotEmpty() -> emptyList()
        else -> null
    }
}

internal fun FileStat.enrichWithContentMetadata(
    metadata: ContentMetadata?,
    fallbackDurationMillis: Long? = null,
): FileStat {
    if (isFolder) return this
    val durationMs = (metadata?.durationMillis?.takeIf { it > 0L } ?: fallbackDurationMillis?.takeIf { it > 0L })
    val width = metadata?.width?.takeIf { it > 0 }
    val height = metadata?.height?.takeIf { it > 0 }
    val needDuration = durationMs != null && (params["duration"]?.toDoubleOrNull()?.takeIf { it > 0.0 } == null)
    val needWidth = width != null && (params["width"]?.toIntOrNull()?.takeIf { it > 0 } == null)
    val needHeight = height != null && (params["height"]?.toIntOrNull()?.takeIf { it > 0 } == null)
    if (!needDuration && !needWidth && !needHeight) return this
    val updatedParams = LinkedHashMap<String, String>(params.size + 3)
    updatedParams.putAll(params)
    if (needDuration && durationMs != null) {
        updatedParams["duration"] = if (durationMs % 1000L == 0L) {
            (durationMs / 1000L).toString()
        } else {
            (durationMs / 1000.0).toString()
        }
    }
    if (needWidth && width != null) updatedParams["width"] = width.toString()
    if (needHeight && height != null) updatedParams["height"] = height.toString()
    return copy(params = CompactStringMap.of(updatedParams))
}

internal fun mergeTreeContentMetadata(
    first: Map<ContentKey, ContentMetadata>, second: Map<ContentKey, ContentMetadata>,
): Map<ContentKey, ContentMetadata> {
    val result = first.toMutableMap()
    for ((key, value) in second) result[key] = result[key]?.merge(value) ?: value
    return result
}

@Serializable
internal data class EntryMetadata(val originalCreatedAtMillis: Long? = null)

internal fun treeEpochMillis(value: String): Long? {
    if (value.length < 10) return null
    val first = value[0]
    if (first !in '0'..'9' && first != '+' && first != '-') return null
    return try { Instant.parse(value).toEpochMilliseconds() } catch (_: IllegalArgumentException) { null }
}

/** Owns its array and follows AbstractMap's structural equality/hashCode contract. */
internal class CompactStringMap private constructor(private val cells: Array<String>) : AbstractMap<String, String>() {
    override val size: Int get() = cells.size / 2
    override fun get(key: String): String? {
        for (i in cells.indices step 2) if (cells[i] == key) return cells[i + 1]
        return null
    }
    override val entries: Set<Map.Entry<String, String>> get() = object : AbstractSet<Map.Entry<String, String>>() {
        override val size: Int get() = cells.size / 2
        override fun iterator(): Iterator<Map.Entry<String, String>> = object : Iterator<Map.Entry<String, String>> {
            private var index = 0
            override fun hasNext() = index < cells.size
            override fun next(): Map.Entry<String, String> {
                if (!hasNext()) throw NoSuchElementException()
                val entry = object : Map.Entry<String, String> {
                    override val key = cells[index]
                    override val value = cells[index + 1]
                    override fun equals(other: Any?) = other is Map.Entry<*, *> && key == other.key && value == other.value
                    override fun hashCode() = key.hashCode() xor value.hashCode()
                }
                index += 2
                return entry
            }
        }
    }
    companion object {
        fun of(values: Map<String, String>, canonical: (String) -> String = { it }): Map<String, String> {
            if (values.isEmpty()) return emptyMap()
            if (values is CompactStringMap) return values
            val cells = Array(values.size * 2) { "" }
            var index = 0
            for ((key, value) in values) { cells[index++] = canonical(key); cells[index++] = canonical(value) }
            return CompactStringMap(cells)
        }
    }
}

/** Per-account, bounded by both count and retained characters; never interns arbitrary strings. */
internal class TreeCanonicalizer(private val maxEntries: Int = 4096, private val maxCharacters: Int = 262144) {
    private val values = HashMap<String, String>()
    private var characters = 0
    fun canonical(value: String): String {
        values[value]?.let { return it }
        if (value.length <= 2048 && values.size < maxEntries && characters + value.length <= maxCharacters) {
            values[value] = value
            characters += value.length
        }
        return value
    }
    fun clear() { values.clear(); characters = 0 }
    fun compact(file: FileStat): FileStat {
        val params = CompactStringMap.of(file.params, ::canonical)
        val mime = canonical(file.mimeType)
        val extension = canonical(file.fileExtension)
        val phase = canonical(file.phase)
        val modified = if (file.modifiedTime == file.createdTime) file.createdTime else file.modifiedTime
        if (params === file.params && mime === file.mimeType && extension === file.fileExtension &&
            phase === file.phase && modified === file.modifiedTime) return file
        return file.copy(params = params, mimeType = mime, fileExtension = extension, phase = phase, modifiedTime = modified)
    }
}
