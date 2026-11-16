package dev.piko.update

import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdaterVerificationTest {

    private val certA = "faec4b73479583c878030b5bbe86f2ff995089a4a421579d0e71ee7ac3c8618f"
    private val certB = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val certC = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

    @Test
    fun `accepts matching package, non-downgrading version, and matching signer`() {
        verifyArchiveIdentity(
            expectedPackageName = "dev.piko",
            installedVersionCode = 1005000L,
            installedSigners = PackageSigners(setOf(certA), listOf(certA)),
            archivePackageName = "dev.piko",
            archiveVersionCode = 1005001L,
            archiveSigners = PackageSigners(setOf(certA), listOf(certA)),
        )
        // Same versionCode is allowed for reinstall/patch verification
        verifyArchiveIdentity(
            expectedPackageName = "dev.piko",
            installedVersionCode = 1005000L,
            installedSigners = PackageSigners(setOf(certA)),
            archivePackageName = "dev.piko",
            archiveVersionCode = 1005000L,
            archiveSigners = PackageSigners(setOf(certA)),
        )
    }

    @Test
    fun `accepts forward v3 key rotation lineage`() {
        verifyArchiveIdentity(
            expectedPackageName = "dev.piko",
            installedVersionCode = 1005000L,
            installedSigners = PackageSigners(setOf(certA), listOf(certA)),
            archivePackageName = "dev.piko",
            archiveVersionCode = 1006000L,
            archiveSigners = PackageSigners(setOf(certB), listOf(certA, certB)),
        )
    }

    @Test
    fun `rejects key rotation rollback to older historical key`() {
        assertThrows(SecurityException::class.java) {
            verifyArchiveIdentity(
                expectedPackageName = "dev.piko",
                installedVersionCode = 1005000L,
                installedSigners = PackageSigners(setOf(certB), listOf(certA, certB)),
                archivePackageName = "dev.piko",
                archiveVersionCode = 1006000L,
                archiveSigners = PackageSigners(setOf(certA), listOf(certA)),
            )
        }
    }

    @Test
    fun `rejects forked key rotation lineage`() {
        assertThrows(SecurityException::class.java) {
            verifyArchiveIdentity(
                expectedPackageName = "dev.piko",
                installedVersionCode = 1005000L,
                installedSigners = PackageSigners(setOf(certB), listOf(certA, certB)),
                archivePackageName = "dev.piko",
                archiveVersionCode = 1006000L,
                archiveSigners = PackageSigners(setOf(certC), listOf(certA, certC)),
            )
        }
    }

    @Test
    fun `rejects package name mismatch or missing package name`() {
        assertThrows(SecurityException::class.java) {
            verifyArchiveIdentity(
                expectedPackageName = "dev.piko",
                installedVersionCode = 1005000L,
                installedSigners = PackageSigners(setOf(certA)),
                archivePackageName = "com.example.other",
                archiveVersionCode = 1006000L,
                archiveSigners = PackageSigners(setOf(certA)),
            )
        }
        assertThrows(SecurityException::class.java) {
            verifyArchiveIdentity(
                expectedPackageName = "dev.piko",
                installedVersionCode = 1005000L,
                installedSigners = PackageSigners(setOf(certA)),
                archivePackageName = null,
                archiveVersionCode = 1006000L,
                archiveSigners = PackageSigners(setOf(certA)),
            )
        }
    }

    @Test
    fun `rejects version downgrade`() {
        assertThrows(SecurityException::class.java) {
            verifyArchiveIdentity(
                expectedPackageName = "dev.piko",
                installedVersionCode = 1005000L,
                installedSigners = PackageSigners(setOf(certA)),
                archivePackageName = "dev.piko",
                archiveVersionCode = 1004999L,
                archiveSigners = PackageSigners(setOf(certA)),
            )
        }
    }

    @Test
    fun `rejects missing signing certificates`() {
        assertThrows(SecurityException::class.java) {
            verifyArchiveIdentity(
                expectedPackageName = "dev.piko",
                installedVersionCode = 1005000L,
                installedSigners = PackageSigners(emptySet()),
                archivePackageName = "dev.piko",
                archiveVersionCode = 1006000L,
                archiveSigners = PackageSigners(setOf(certA)),
            )
        }
        assertThrows(SecurityException::class.java) {
            verifyArchiveIdentity(
                expectedPackageName = "dev.piko",
                installedVersionCode = 1005000L,
                installedSigners = PackageSigners(setOf(certA)),
                archivePackageName = "dev.piko",
                archiveVersionCode = 1006000L,
                archiveSigners = PackageSigners(emptySet()),
            )
        }
    }

    @Test
    fun `rejects mismatched single signer`() {
        assertThrows(SecurityException::class.java) {
            verifyArchiveIdentity(
                expectedPackageName = "dev.piko",
                installedVersionCode = 1005000L,
                installedSigners = PackageSigners(setOf(certA)),
                archivePackageName = "dev.piko",
                archiveVersionCode = 1006000L,
                archiveSigners = PackageSigners(setOf(certB)),
            )
        }
    }

    @Test
    fun `requires exact signer set when multiple signers are present`() {
        verifyArchiveIdentity(
            expectedPackageName = "dev.piko",
            installedVersionCode = 1005000L,
            installedSigners = PackageSigners(setOf(certA, certB)),
            archivePackageName = "dev.piko",
            archiveVersionCode = 1006000L,
            archiveSigners = PackageSigners(setOf(certA, certB)),
        )
        assertThrows(SecurityException::class.java) {
            verifyArchiveIdentity(
                expectedPackageName = "dev.piko",
                installedVersionCode = 1005000L,
                installedSigners = PackageSigners(setOf(certA, certB)),
                archivePackageName = "dev.piko",
                archiveVersionCode = 1006000L,
                archiveSigners = PackageSigners(setOf(certA)),
            )
        }
    }
}
