package dev.piko.shared.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SandboxPathsTest {
    private val root = "/container/Documents/Piko"

    @Test fun emptySelectionResetsToRoot() {
        assertEquals("", SandboxPaths.relativeDirectory(root, ""))
        assertEquals(root, SandboxPaths.resolveDirectory(root, ""))
    }

    @Test fun descendantsRoundTripWithoutPersistingTheContainer() {
        val relative = SandboxPaths.relativeDirectory(root, "$root/影片/Season 1")
        assertEquals("影片/Season 1", relative)
        assertEquals("/new-container/Documents/Piko/影片/Season 1", SandboxPaths.resolveDirectory("/new-container/Documents/Piko", relative))
    }

    @Test fun repeatedSeparatorsAndDotSegmentsAreNormalized() {
        assertEquals("b", SandboxPaths.relativeDirectory("$root/", "$root//a/.././b/"))
        assertEquals(root, SandboxPaths.resolveDirectory("/container//Documents/./Piko/", ""))
    }

    @Test fun pathPrefixesAreNotDirectoryContainment() {
        assertFailsWith<IllegalArgumentException> { SandboxPaths.relativeDirectory(root, "/container/Documents/Piko-other") }
    }

    @Test fun leavingTheManagedDirectoryIsRejected() {
        assertFailsWith<IllegalArgumentException> { SandboxPaths.relativeDirectory(root, "$root/../outside") }
        assertFailsWith<IllegalArgumentException> { SandboxPaths.relativeDirectory(root, "/other/provider/file") }
    }

    @Test fun relativeSelectionsAndNulBytesAreRejected() {
        assertFailsWith<IllegalArgumentException> { SandboxPaths.relativeDirectory(root, "relative") }
        assertFailsWith<IllegalArgumentException> { SandboxPaths.relativeDirectory(root, "$root/\u0000bad") }
        assertFailsWith<IllegalArgumentException> { SandboxPaths.resolveDirectory(root, "\u0000bad") }
    }

    @Test fun persistedPathsMustBeStrictlyRelativeAndNormalized() {
        for (bad in listOf("/absolute", "..", "a/../b", ".", "a//b", "a/", "./a")) {
            assertFailsWith<IllegalArgumentException>(bad) { SandboxPaths.resolveDirectory(root, bad) }
        }
    }

    @Test fun filesystemRootIsNotAnAppManagedDirectory() {
        assertFailsWith<IllegalArgumentException> { SandboxPaths.relativeDirectory("/", "/anything") }
        assertFailsWith<IllegalArgumentException> { SandboxPaths.resolveDirectory("/", "") }
    }

    @Test fun traversalAboveFilesystemRootIsRejected() {
        assertFailsWith<IllegalArgumentException> { SandboxPaths.relativeDirectory(root, "/../../container/Documents/Piko") }
    }
}
