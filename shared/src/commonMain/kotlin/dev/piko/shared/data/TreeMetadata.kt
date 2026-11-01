package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
internal data class ContentKey(val gcid: String, val sizeBytes: Long) {
    companion object {
        fun of(gcid: String, sizeBytes: Long): ContentKey? =
            gcid.takeIf { it.isNotBlank() && sizeBytes >= 0 }?.let { ContentKey(it.uppercase(), sizeBytes) }
        fun of(file: FileStat): ContentKey? = of(file.hash, file.sizeBytes)
    }
}

/** Rank 0 is listing/legacy data; higher ranks must identify original media, not a transcode. */
@Serializable
internal data class ContentMetadata(
    val durationMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mimeType: String? = null,
    val cid: String? = null,
    val qualityRank: Int = 0,
) {
    fun merge(incoming: ContentMetadata): ContentMetadata {
        val preferred = if (incoming.qualityRank > qualityRank) incoming else this
        val fallback = if (preferred === this) incoming else this
        return ContentMetadata(
            preferred.durationMillis?.takeIf { it >= 0 } ?: fallback.durationMillis?.takeIf { it >= 0 },
            preferred.width?.takeIf { it > 0 } ?: fallback.width?.takeIf { it > 0 },
            preferred.height?.takeIf { it > 0 } ?: fallback.height?.takeIf { it > 0 },
            preferred.mimeType?.takeIf { it.isNotBlank() } ?: fallback.mimeType?.takeIf { it.isNotBlank() },
            preferred.cid?.takeIf { it.isNotBlank() } ?: fallback.cid?.takeIf { it.isNotBlank() },
            maxOf(qualityRank, incoming.qualityRank),
        )
    }

    companion object {
        fun of(file: FileStat) = ContentMetadata(
            durationMillis = file.params["duration"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                ?.let { (it * 1000).toLong() },
            width = file.params["width"]?.toIntOrNull()?.takeIf { it > 0 },
            height = file.params["height"]?.toIntOrNull()?.takeIf { it > 0 },
            mimeType = file.mimeType.takeIf { it.isNotBlank() },
            cid = file.params["piko_vault_cid"],
        )
    }
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

internal fun treeEpochMillis(value: String): Long? =
    try { Instant.parse(value).toEpochMilliseconds() } catch (_: IllegalArgumentException) { null }

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
