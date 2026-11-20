package dev.piko.ui.components

import dev.piko.shared.data.ThumbnailClarity
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThumbnailCacheKeyTest {
    @Test
    fun pikpakThumbnailUrlsNormalizeAcrossHostsAndVolatileQueryParams() {
        val urlA = "https://sg-thumbnail-drive.mypikpak.com/v0/screenshot-thumbnails/abc123def456/240/720?e=1700000000&sig=aaa"
        val urlB = "https://us-thumbnail-drive.mypikpak.net/v0/screenshot-thumbnails/ABC123DEF456/240/720?e=1799999999&sig=bbb"

        assertEquals(
            thumbnailDiskCacheKey(urlA, ThumbnailClarity.STANDARD),
            thumbnailDiskCacheKey(urlB, ThumbnailClarity.STANDARD),
        )
        assertEquals(
            thumbnailMemoryCacheKey(urlA, ThumbnailClarity.STANDARD),
            thumbnailMemoryCacheKey(urlB, ThumbnailClarity.STANDARD),
        )
    }

    @Test
    fun signedCdnUrlsStripExpiringSignatureParamsForPersistentDiskCache() {
        val signedA = "https://cdn.example.com/thumbs/file42.jpg?Expires=1700000100&OSSAccessKeyId=AK1&Signature=sigA&variant=v1"
        val signedB = "https://cdn.example.com/thumbs/file42.jpg?Expires=1799999999&OSSAccessKeyId=AK2&Signature=sigB&variant=v1"

        assertEquals(
            thumbnailDiskCacheKey(signedA, ThumbnailClarity.STANDARD),
            thumbnailDiskCacheKey(signedB, ThumbnailClarity.STANDARD),
        )
        assertEquals(
            thumbnailDiskCacheKey(signedA, ThumbnailClarity.STANDARD, stableId = "gcid-42"),
            thumbnailDiskCacheKey(signedB, ThumbnailClarity.STANDARD, stableId = "gcid-42"),
        )
    }

    @Test
    fun clarityTiersShareSdDiskCacheAndUpgradeHighResCleanly() {
        val url = "https://sg-thumbnail-drive.mypikpak.com/v0/screenshot-thumbnails/FEEDBEEF1234/240/720"

        assertEquals(
            thumbnailDiskCacheKey(url, ThumbnailClarity.LOW),
            thumbnailDiskCacheKey(url, ThumbnailClarity.STANDARD),
        )
        assertNotEquals(
            thumbnailMemoryCacheKey(url, ThumbnailClarity.LOW),
            thumbnailMemoryCacheKey(url, ThumbnailClarity.STANDARD),
        )

        assertTrue(isUpgradedThumbnailClarityUrl(url, ThumbnailClarity.HIGH))
        assertFalse(isUpgradedThumbnailClarityUrl(url, ThumbnailClarity.STANDARD))
        assertEquals(
            "https://sg-thumbnail-drive.mypikpak.com/v0/screenshot-thumbnails/FEEDBEEF1234/720/2048",
            resolveThumbnailModelForClarity(url, ThumbnailClarity.HIGH),
        )
        assertNotEquals(
            thumbnailDiskCacheKey(url, ThumbnailClarity.STANDARD),
            thumbnailDiskCacheKey(url, ThumbnailClarity.HIGH, allowUpgrade = true),
        )
        assertEquals(
            thumbnailDiskCacheKey(url, ThumbnailClarity.STANDARD),
            thumbnailDiskCacheKey(url, ThumbnailClarity.HIGH, allowUpgrade = false),
        )
    }

    @Test
    fun folderCoversNeverPinContainerIdAndInvalidateWhenCoverPathChanges() {
        val folderA = FileStat(
            id = "folder-container-42",
            name = "Albums",
            kind = FileKind.FOLDER,
            thumbnailLink = "https://cdn.example.com/covers/coverA.jpg?Expires=1700000100&Signature=sig1",
        )
        val folderASignedAgain = folderA.copy(
            thumbnailLink = "https://cdn.example.com/covers/coverA.jpg?Expires=1799999999&Signature=sig2",
        )
        val folderB = folderA.copy(
            thumbnailLink = "https://cdn.example.com/covers/coverB.jpg?Expires=1799999999&Signature=sig3",
        )

        val visualA = folderA.fileVisual()
        val visualASignedAgain = folderASignedAgain.fileVisual()
        val visualB = folderB.fileVisual()

        // Folders must never use container ID as stableId; files still use hash or file ID.
        assertNull(visualA.stableId)
        assertNull(visualB.stableId)
        assertEquals(
            "gcid-99",
            FileStat(id = "file-1", name = "clip.mp4", kind = FileKind.FILE, hash = "gcid-99").fileVisual().stableId,
        )
        assertEquals(
            "file-2",
            FileStat(id = "file-2", name = "clip.mp4", kind = FileKind.FILE).fileVisual().stableId,
        )

        // Same cover with refreshed signature hits the same persistent disk & memory cache key.
        assertEquals(
            thumbnailDiskCacheKey(folderA.thumbnailLink, ThumbnailClarity.STANDARD, stableId = visualA.stableId),
            thumbnailDiskCacheKey(folderASignedAgain.thumbnailLink, ThumbnailClarity.STANDARD, stableId = visualASignedAgain.stableId),
        )
        assertEquals(
            thumbnailMemoryCacheKey(folderA.thumbnailLink, ThumbnailClarity.STANDARD, stableId = visualA.stableId),
            thumbnailMemoryCacheKey(folderASignedAgain.thumbnailLink, ThumbnailClarity.STANDARD, stableId = visualASignedAgain.stableId),
        )

        // Different cover image for the same folder ID must produce distinct disk & memory keys.
        assertNotEquals(
            thumbnailDiskCacheKey(folderA.thumbnailLink, ThumbnailClarity.STANDARD, stableId = visualA.stableId),
            thumbnailDiskCacheKey(folderB.thumbnailLink, ThumbnailClarity.STANDARD, stableId = visualB.stableId),
        )
        assertNotEquals(
            thumbnailMemoryCacheKey(folderA.thumbnailLink, ThumbnailClarity.STANDARD, stableId = visualA.stableId),
            thumbnailMemoryCacheKey(folderB.thumbnailLink, ThumbnailClarity.STANDARD, stableId = visualB.stableId),
        )
    }

    @Test
    fun localPathsSkipRemoteDiskCacheKey() {
        assertNull(thumbnailDiskCacheKey("C:\\Users\\demo\\video.jpg", ThumbnailClarity.STANDARD))
        assertNull(thumbnailDiskCacheKey("content://media/external/images/1", ThumbnailClarity.HIGH))
    }
}
