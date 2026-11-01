package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
internal data class StoredContentMetadata(val key: ContentKey, val metadata: ContentMetadata)

/** JSON framing is streamed; only a single bounded node/string is passed to kotlinx.serialization. */
internal object TreeSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private const val MAX_NODES = 1_000_000
    private const val MAX_SOURCES = 200_000
    private const val MAX_SOURCE_CHARS = 64_000_000

    fun write(sink: Sink, state: DriveTreeIndex.State, savedAt: Long, syncedAt: Long) {
        val sources = HashMap<String, Int>()
        for (file in state.entries.values) {
            if (file.trashed || VaultEntry.isVaulted(file.id)) continue
            file.params["url"]?.let { if (it !in sources) sources[it] = sources.size }
        }
        sink.writeString("{\"schemaVersion\":2,\"savedAtMillis\":$savedAt,\"lastFullSyncAtMillis\":$syncedAt,\"complete\":${state.complete}")
        state.lastUsageBytes?.let { sink.writeString(",\"lastUsageBytes\":$it") }
        sink.writeString(",\"dirtyFolders\":[")
        var comma = false
        for (id in state.dirtyFolders) {
            if (comma) sink.writeString(",")
            sink.writeString(json.encodeToString(String.serializer(), id)); comma = true
        }
        sink.writeString("],\"sources\":[")
        // HashMap iteration is not index order. A reference-sized array does not copy source strings.
        val ordered = arrayOfNulls<String>(sources.size)
        for ((source, index) in sources) ordered[index] = source
        comma = false
        for (source in ordered) {
            if (comma) sink.writeString(",")
            sink.writeString(json.encodeToString(String.serializer(), source!!)); comma = true
        }
        sink.writeString("],\"contentMetadata\":[")
        comma = false
        for ((key, metadata) in state.contentMetadata) {
            if (comma) sink.writeString(",")
            sink.writeString(json.encodeToString(StoredContentMetadata.serializer(), StoredContentMetadata(key, metadata)))
            comma = true
        }
        sink.writeString("],\"entries\":[")
        comma = false
        for (file in state.entries.values) {
            if (file.trashed || VaultEntry.isVaulted(file.id)) continue
            if (comma) sink.writeString(",")
            val sourceRef = file.params["url"]?.let(sources::get)
            val stored = DriveTreeIndex.StoredTreeEntry.of(file).copy(
                params = if (sourceRef == null) file.params else file.params - "url",
                sourceRef = sourceRef,
                originalCreatedAtMillis = state.entryMetadata[file.id]?.originalCreatedAtMillis,
                thumbnailLink = file.thumbnailLink.takeUnless { '?' in it }.orEmpty(),
            )
            sink.writeString(json.encodeToString(DriveTreeIndex.StoredTreeEntry.serializer(), stored))
            comma = true
        }
        sink.writeString("]}")
    }

    fun read(source: Source): DriveTreeIndex.PersistedTreeSnapshot? = try {
        decode(source)
    } catch (_: IllegalArgumentException) { null } catch (_: kotlinx.io.IOException) { null }

    private fun decode(source: Source): DriveTreeIndex.PersistedTreeSnapshot {
        val reader = JsonFrames(source)
        val entries = HashMap<String, FileStat>()
        val references = HashMap<String, Int>()
        val instances = HashMap<String, EntryMetadata>()
        val metadata = ArrayList<StoredContentMetadata>()
        val sources = ArrayList<String>()
        val dirty = HashSet<String>()
        val canonical = TreeCanonicalizer()
        var version = 1
        var savedAt = 0L
        var syncedAt = 0L
        var usage: Long? = null
        var complete = true
        var sourceCharacters = 0
        val seen = HashSet<String>()
        reader.objectFields { name ->
            require(seen.add(name)) { "Duplicate snapshot field" }
            when (name) {
                "schemaVersion" -> version = reader.value().toInt()
                "savedAtMillis" -> savedAt = reader.value().toLong()
                "lastFullSyncAtMillis" -> syncedAt = reader.value().toLong()
                "lastUsageBytes" -> usage = reader.value().let { if (it == "null") null else it.toLong() }
                "complete" -> complete = reader.value().toBooleanStrict()
                "dirtyFolders" -> reader.arrayItems {
                    require(dirty.size < MAX_NODES)
                    dirty.add(json.decodeFromString(String.serializer(), reader.value()))
                }
                "sources" -> reader.arrayItems {
                    require(sources.size < MAX_SOURCES)
                    val value = json.decodeFromString(String.serializer(), reader.value())
                    sourceCharacters += value.length
                    require(sourceCharacters <= MAX_SOURCE_CHARS)
                    sources.add(value)
                }
                "contentMetadata" -> reader.arrayItems {
                    require(metadata.size < MAX_NODES)
                    val item = json.decodeFromString(StoredContentMetadata.serializer(), reader.value())
                    val key = ContentKey.of(item.key.gcid, item.key.sizeBytes)
                    if (key != null) metadata.add(item.copy(key = key, metadata = ContentMetadata().merge(item.metadata)))
                }
                "entries" -> reader.arrayItems {
                    require(entries.size < MAX_NODES)
                    val stored = json.decodeFromString(DriveTreeIndex.StoredTreeEntry.serializer(), reader.value())
                    require(stored.id !in entries) { "Duplicate node identity" }
                    val file = canonical.compact(stored.toFileStat().let {
                        if ('?' in it.thumbnailLink) it.copy(thumbnailLink = "") else it
                    })
                    entries[file.id] = file
                    stored.sourceRef?.let { references[file.id] = it }
                    (stored.originalCreatedAtMillis ?: file.params["piko_original_created_at"]?.toLongOrNull())?.let {
                        instances[file.id] = EntryMetadata(it)
                    }
                }
                else -> reader.skipValue()
            }
        }
        require(version == 1 || version == 2) { "Unsupported snapshot schema" }
        require(reader.finished()) { "Trailing snapshot data" }
        // Field order is irrelevant. Invalid references drop only the untrusted source association.
        for ((id, reference) in references) {
            val url = sources.getOrNull(reference) ?: continue
            val file = entries.getValue(id)
            entries[id] = file.copy(params = CompactStringMap.of(file.params + ("url" to url)))
        }
        return DriveTreeIndex.PersistedTreeSnapshot(
            schemaVersion = version, savedAtMillis = savedAt, lastFullSyncAtMillis = syncedAt,
            lastUsageBytes = usage, complete = complete, dirtyFolders = dirty,
            contentMetadata = metadata, entryMetadata = instances, physicalEntries = entries,
        )
    }

    /** Streaming migration callbacks must write to an unpublished transaction/generation. */
    fun readStreaming(
        source: Source,
        entry: (DriveTreeIndex.StoredTreeEntry) -> Unit,
        sourceValue: (Int, String) -> Unit,
        content: (StoredContentMetadata) -> Unit,
        instance: (String, EntryMetadata) -> Unit,
    ): DriveTreeIndex.PersistedTreeSnapshot {
        val reader = JsonFrames(source)
        var version = 1; var savedAt = 0L; var syncedAt = 0L
        var usage: Long? = null; var complete = true
        val dirty = HashSet<String>(); val seen = HashSet<String>()
        var nodes = 0; var sources = 0; var sourceCharacters = 0
        reader.objectFields { name ->
            require(seen.add(name)) { "Duplicate snapshot field" }
            when (name) {
                "schemaVersion" -> version = reader.value().toInt()
                "savedAtMillis" -> savedAt = reader.value().toLong()
                "lastFullSyncAtMillis" -> syncedAt = reader.value().toLong()
                "lastUsageBytes" -> usage = reader.value().let { if (it == "null") null else it.toLong() }
                "complete" -> complete = reader.value().toBooleanStrict()
                "dirtyFolders" -> reader.arrayItems { require(dirty.size < MAX_NODES); dirty += json.decodeFromString(String.serializer(), reader.value()) }
                "sources" -> reader.arrayItems {
                    require(sources < MAX_SOURCES)
                    val value = json.decodeFromString(String.serializer(), reader.value())
                    sourceCharacters += value.length; require(sourceCharacters <= MAX_SOURCE_CHARS)
                    sourceValue(sources++, value)
                }
                "contentMetadata" -> reader.arrayItems { content(json.decodeFromString(StoredContentMetadata.serializer(), reader.value())) }
                "entryMetadata" -> reader.objectFields { id -> instance(id, json.decodeFromString(EntryMetadata.serializer(), reader.value())) }
                "entries" -> reader.arrayItems {
                    require(nodes++ < MAX_NODES)
                    entry(json.decodeFromString(DriveTreeIndex.StoredTreeEntry.serializer(), reader.value()))
                }
                else -> reader.skipValue()
            }
        }
        require(version == 1 || version == 2) { "Unsupported snapshot schema" }
        require(reader.finished()) { "Trailing snapshot data" }
        return DriveTreeIndex.PersistedTreeSnapshot(schemaVersion = version, savedAtMillis = savedAt, lastFullSyncAtMillis = syncedAt,
            lastUsageBytes = usage, complete = complete, dirtyFolders = dirty)
    }

    /** Balanced UTF-8 byte framing; strings are decoded only after a complete node is bounded. */
    private class JsonFrames(private val source: Source) {
        private var lookahead: Int = -2
        private fun peek(): Int {
            if (lookahead == -2) lookahead = if (source.exhausted()) -1 else source.readByte().toInt() and 255
            return lookahead
        }
        private fun take(): Int = peek().also { lookahead = -2 }
        private fun whitespace() { while (peek() == 32 || peek() == 10 || peek() == 13 || peek() == 9) take() }
        private fun expect(char: Char) { whitespace(); require(take() == char.code) { "Invalid snapshot JSON" } }
        fun finished(): Boolean { whitespace(); return peek() == -1 }
        fun objectFields(consume: (String) -> Unit) {
            expect('{'); whitespace()
            if (peek() == '}'.code) { take(); return }
            while (true) {
                require(peek() == '"'.code)
                val name = json.decodeFromString(String.serializer(), value())
                expect(':'); consume(name); whitespace()
                if (peek() == '}'.code) { take(); return }
                expect(','); whitespace()
            }
        }
        fun arrayItems(consume: () -> Unit) {
            expect('['); whitespace()
            if (peek() == ']'.code) { take(); return }
            while (true) {
                consume(); whitespace()
                if (peek() == ']'.code) { take(); return }
                expect(',')
            }
        }
        fun value(): String = frame(retain = true)!!
        fun skipValue() { frame(retain = false) }
        private fun frame(retain: Boolean): String? {
            whitespace()
            val buffer = if (retain) Buffer() else null
            var depth = 0
            var string = false
            var escape = false
            var length = 0
            do {
                val next = peek()
                require(next != -1) { "Truncated snapshot" }
                if (!string && depth == 0 && (next == ','.code || next == '}'.code || next == ']'.code || next == 32 || next == 10 || next == 13 || next == 9)) break
                val byte = take()
                length++
                require(!retain || length <= 1_048_576) { "Snapshot field exceeds bound" }
                buffer?.writeByte(byte.toByte())
                if (string) {
                    if (escape) escape = false
                    else if (byte == '\\'.code) escape = true
                    else if (byte == '"'.code) string = false
                } else when (byte) {
                    '"'.code -> string = true
                    '{'.code, '['.code -> { depth++; require(depth <= 64) }
                    '}'.code, ']'.code -> { depth--; require(depth >= 0) }
                }
                if (!string && depth == 0 && (byte == '"'.code || byte == '}'.code || byte == ']'.code)) break
            } while (true)
            require(length > 0 && !string && depth == 0)
            return buffer?.readString()
        }
    }
}
