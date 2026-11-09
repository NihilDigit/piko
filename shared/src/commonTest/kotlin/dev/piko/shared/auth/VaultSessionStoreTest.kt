package dev.piko.shared.auth

import dev.piko.shared.data.SavedAccount
import dev.piko.shared.data.SavedAccounts
import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class VaultSessionStoreTest {
    private val session = Session(accessToken = "access", refreshToken = "refresh", sub = "sub", expiresAt = 1_900_000_000)

    @Test
    fun missingRecordsAreEmptyWithoutWritingAnything() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        assertEquals(SavedAccounts(), store.loadAccounts())
        assertNull(store.load("alice"))
        assertNull(store.loadCredentials("alice"))
        assertEquals("", store.loadArchivePasswords("alice"))
        assertTrue(vault.items.isEmpty())
    }

    @Test
    fun sessionsPasswordsAndArchivePasswordsSurviveReopeningAndRefresh() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        store.save("alice", session)
        store.saveCredentials("alice", "password")
        store.saveArchivePasswords("alice", "[\"archive\"]")
        val refreshed = session.copy(accessToken = "refreshed")
        store.save("alice", refreshed)
        val reopened = VaultSessionStore(vault)
        assertEquals(refreshed, reopened.load("alice"))
        assertEquals("password", reopened.loadCredentials("alice")?.password)
        assertEquals("[\"archive\"]", reopened.loadArchivePasswords("alice"))
        assertEquals(1, vault.items.size)
    }

    @Test
    fun accountsAreIsolatedAndNamesAreNotPresentInVaultKeys() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        store.saveCredentials("alice@example.com", "a")
        store.saveCredentials("bob@example.com", "b")
        assertEquals("a", store.loadCredentials("alice@example.com")?.password)
        assertEquals("b", store.loadCredentials("bob@example.com")?.password)
        assertEquals(2, vault.items.size)
        assertTrue(vault.items.keys.all { it.matches(Regex("piko\\.session\\.v1\\.[0-9a-f]{64}")) })
    }

    @Test
    fun logoutKeepsArchivePasswordsUntilExplicitlyCleared() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        store.save("alice", session)
        store.saveCredentials("alice", "password")
        store.saveArchivePasswords("alice", "[\"archive\"]")
        store.clear("alice")
        store.clearCredentials("alice")
        val reopened = VaultSessionStore(vault)
        assertNull(reopened.load("alice"))
        assertNull(reopened.loadCredentials("alice"))
        assertEquals("[\"archive\"]", reopened.loadArchivePasswords("alice"))
        assertEquals(1, vault.items.size)
        reopened.saveArchivePasswords("alice", "")
        assertTrue(vault.items.isEmpty())
    }

    @Test
    fun metadataIsIndependentFromAccountSecrets() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        val accounts = SavedAccounts(current = "alice", accounts = listOf(SavedAccount("alice", name = "Alice")))
        store.saveAccounts(accounts)
        store.save("alice", session)
        assertEquals(accounts, VaultSessionStore(vault).loadAccounts())
        store.clear("alice")
        assertEquals(accounts, store.loadAccounts())
        assertEquals(1, vault.items.size)
        store.saveAccounts(SavedAccounts())
        assertEquals(SavedAccounts(), store.loadAccounts())
    }

    @Test
    fun lockedVaultPropagatesInsteadOfAppearingLoggedOut() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        store.save("alice", session)
        assertEquals(session, store.load("alice"))
        vault.available = false
        assertFailsWith<VaultUnavailableException> { store.load("alice") }
        assertFailsWith<VaultUnavailableException> { store.loadAccounts() }
        assertFailsWith<VaultUnavailableException> { store.loadArchivePasswords("alice") }
        vault.available = true
        assertEquals(session, store.load("alice"))
    }

    @Test
    fun failedWritesNeverPublishAnOptimisticValue() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        store.save("alice", session)
        vault.failWrites = true
        assertFailsWith<VaultUnavailableException> { store.save("alice", session.copy(accessToken = "new")) }
        assertEquals(session, store.load("alice"))
        assertEquals(session, VaultSessionStore(vault).load("alice"))
    }

    @Test
    fun malformedSecretsArePreservedAndDiagnosticsDoNotLeakInput() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        store.save("alice", session)
        val key = vault.items.keys.single()
        val broken = "{\"private-token\":".encodeToByteArray()
        vault.items[key] = broken
        val error = assertFailsWith<VaultUnavailableException> { store.saveCredentials("alice", "new") }
        // JVM coroutine stack recovery may wrap a sanitized exception in another copy of it.
        // Inspect the whole chain instead of assuming that the outer exception has no cause.
        var cursor: Throwable? = error
        var depth = 0
        while (cursor != null) {
            assertTrue(depth++ < 16, "Unexpected exception cause cycle")
            assertTrue(cursor is VaultUnavailableException)
            assertEquals("Stored credentials are corrupt or use an unsupported format", cursor.message)
            assertFalse(cursor.message.orEmpty().contains("private-token"))
            cursor = cursor.cause
        }
        assertContentEquals(broken, vault.items[key])
    }

    @Test
    fun swappedAccountPayloadIsNeverAcceptedOrOverwritten() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        store.saveCredentials("alice", "a")
        val aliceKey = vault.items.keys.single()
        store.saveCredentials("bob", "b")
        val bobKey = vault.items.keys.single { it != aliceKey }
        vault.items[bobKey] = vault.items.getValue(aliceKey).copyOf()
        assertFailsWith<VaultUnavailableException> { store.loadCredentials("bob") }
        assertFailsWith<VaultUnavailableException> { store.clearCredentials("bob") }
        assertEquals("a", store.loadCredentials("alice")?.password)
    }

    @Test
    fun futureVersionsArePreservedRatherThanReset() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        store.saveAccounts(SavedAccounts())
        val key = vault.items.keys.single()
        vault.items[key] = vault.items.getValue(key).decodeToString().replace("\"version\":1", "\"version\":2").encodeToByteArray()
        val original = vault.items.getValue(key).copyOf()
        assertFailsWith<VaultUnavailableException> { store.loadAccounts() }
        assertContentEquals(original, vault.items[key])
    }

    @Test
    fun concurrentUpdatesToDifferentFieldsDoNotLoseChanges() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        coroutineScope {
            listOf(
                async { store.save("alice", session) },
                async { store.saveCredentials("alice", "password") },
                async { store.saveArchivePasswords("alice", "[\"archive\"]") },
            ).awaitAll()
        }
        assertEquals(session, store.load("alice"))
        assertEquals("password", store.loadCredentials("alice")?.password)
        assertEquals("[\"archive\"]", store.loadArchivePasswords("alice"))
    }

    @Test
    fun unknownFieldsRemainReadable() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        store.save("alice", session)
        val key = vault.items.keys.single()
        vault.items[key] = (vault.items.getValue(key).decodeToString().dropLast(1) + ",\"extra\":42}").encodeToByteArray()
        assertEquals(session, store.load("alice"))
    }

    @Test
    fun invalidUtf8IsNotSilentlyReplaced() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        store.saveAccounts(SavedAccounts())
        val key = vault.items.keys.single()
        vault.items[key] = byteArrayOf(-1, -2)
        assertFailsWith<VaultUnavailableException> { store.loadAccounts() }
        assertContentEquals(byteArrayOf(-1, -2), vault.items[key])
    }

    @Test
    fun failedDeletePreservesStoredCredentials() = runBlocking {
        val vault = FakeVault()
        val store = VaultSessionStore(vault)
        store.saveCredentials("alice", "password")
        vault.failDeletes = true
        assertFailsWith<VaultUnavailableException> { store.clearCredentials("alice") }
        assertEquals("password", store.loadCredentials("alice")?.password)
    }

    private class FakeVault : SecretVault {
        val items = mutableMapOf<String, ByteArray>()
        var available = true
        var failWrites = false
        var failDeletes = false
        override fun read(key: String): ByteArray? {
            if (!available) throw VaultUnavailableException("locked")
            return items[key]?.copyOf()
        }
        override fun write(key: String, data: ByteArray) {
            if (!available || failWrites) throw VaultUnavailableException("write failed")
            items[key] = data.copyOf()
        }
        override fun delete(key: String) {
            if (!available || failDeletes) throw VaultUnavailableException("delete failed")
            items.remove(key)
        }
    }
}
