package dev.piko.shared.data

import io.github.nihildigit.pikpak.imageThumbnailUrlOf
import io.github.nihildigit.pikpak.videoThumbnailUrlOf
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultMediaMetadataTest {
    @Test
    fun mediaMetadataSurvivesManifestAndProjection() {
        val entry = VaultEntry.create("movie.mp4", 1024, "ABC", null, 0,
            duration = "120", width = "1920", height = "1080", mimeType = "video/mp4")
        val restored = Json.decodeFromString<VaultEntry>(Json.encodeToString(VaultEntry.serializer(), entry))
        val row = restored.toFileStat("folder")
        assertEquals("120", row.params["duration"])
        assertEquals("1920", row.params["width"])
        assertEquals("1080", row.params["height"])
        assertEquals("video/mp4", row.mimeType)
        assertEquals(videoThumbnailUrlOf("ABC"), row.thumbnailLink)
        val enriched = entry.enrich(row.copy(params = emptyMap(), mimeType = ""))
        assertEquals(row.params, enriched.params)
        assertEquals(row.mimeType, enriched.mimeType)
        assertEquals("240", entry.enrich(row.copy(params = mapOf("duration" to "240"))).params["duration"])
    }

    @Test
    fun legacyManifestAndImageThumbnail() {
        val entry = Json.decodeFromString<VaultEntry>("""{"id":"old","name":"photo.jpg","size":10,"gcid":"ABC","addedAt":0}""")
        assertNull(entry.duration)
        assertNull(entry.mimeType)
        assertEquals(imageThumbnailUrlOf("ABC"), entry.toFileStat("").thumbnailLink)
    }
}
