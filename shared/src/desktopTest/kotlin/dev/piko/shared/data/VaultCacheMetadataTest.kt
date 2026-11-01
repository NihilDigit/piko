package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultCacheMetadataTest {
    @Test
    fun originalTimeOnlyEnrichesTheRestoredInstance() {
        val entry = VaultEntry("entry", "video.mkv", 10, "HASH", addedAt = 200, createdAt = 100, restoredFileId = "restored")
        val file = FileStat(kind = FileKind.FILE, id = "restored", name = "renamed.mkv", hash = "HASH", size = "10",
            createdTime = "2026-01-01T00:00:00Z", modifiedTime = "2026-02-01T00:00:00Z")
        val enriched = entry.enrich(file)
        assertEquals("100", enriched.params["piko_original_created_at"])
        assertEquals(file.createdTime, enriched.createdTime)
        assertEquals(file.modifiedTime, enriched.modifiedTime)
        assertNull(entry.enrich(file.copy(id = "copy", name = entry.name)).params["piko_original_created_at"])
    }

    @Test
    fun ingestPreservesInstanceTimeAndFallsBackToAddedTime() {
        val file = FileStat(kind = FileKind.FILE, id = "original", name = "v", hash = "HASH", size = "10",
            createdTime = "1970-01-01T00:00:01Z")
        fun draft(origin: FileStat?) = VaultEntryDraft("v", 10, "HASH", originFile = origin)
        assertEquals(1_000L, VaultIngest.buildEntry(draft(file), 2_000).createdAt)
        assertEquals(500L, VaultIngest.buildEntry(draft(file.copy(params = mapOf("piko_original_created_at" to "500"))), 2_000).createdAt)
        assertEquals(2_000L, VaultIngest.buildEntry(draft(null), 2_000).createdAt)
        assertEquals("1970-01-01T00:00:02Z", VaultEntry("old", "v", 10, "HASH", addedAt = 2_000).toFileStat("").createdTime)
    }

    @Test
    fun v2DeduplicatesSourcesAndReadsV1() {
        val source = "magnet:?xt=urn:btih:example&tr=shared"
        val entries = (0..20).map { VaultEntry("$it", "$it.mkv", 10, "HASH", source = source, addedAt = 100, createdAt = 50) }
        val snapshot = mapOf("manifest" to entries)
        val encoded = VaultStore.encodeParsed(snapshot)
        assertEquals(1, Regex(Regex.escape(source)).findAll(encoded).count())
        assertEquals(snapshot, VaultStore.decodeParsed(encoded))
        val legacy = Json.encodeToString(MapSerializer(String.serializer(), ListSerializer(VaultEntry.serializer())), snapshot)
        assertEquals(snapshot, VaultStore.decodeParsed(legacy))
        assertNull(VaultStore.decodeParsed(encoded.replace("\"version\":2", "\"version\":99")))
        assertNull(VaultStore.decodeParsed(encoded.replace("\"source\":0", "\"source\":999")))
    }
}
