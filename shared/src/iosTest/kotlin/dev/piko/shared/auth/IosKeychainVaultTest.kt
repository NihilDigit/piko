@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.auth

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Security.*
import kotlin.test.*

/** Tests use real CF dictionaries but a fake SecItem boundary; no hosted-app entitlement needed. */
class IosKeychainVaultTest {
    @Test
    fun invalidIdentitiesFailBeforeCallingTheSystem() {
        assertFailsWith<IllegalArgumentException> { IosKeychainVault(" ") }
        assertFailsWith<IllegalArgumentException> { IosKeychainVault("piko" + 0.toChar()) }
        val vault = IosKeychainVault("piko.test", FakeApi())
        val invalid = "account" + 0.toChar() + "suffix"
        assertFailsWith<IllegalArgumentException> { vault.read(invalid) }
        assertFailsWith<IllegalArgumentException> { vault.write(invalid, byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { vault.delete(invalid) }
    }

    @Test
    fun missingItemIsDifferentFromLockedKeychain() {
        val api = FakeApi()
        val vault = IosKeychainVault("piko.test", api)
        assertNull(vault.read("secret-key"))
        api.onRead = { _, _ -> errSecInteractionNotAllowed }
        val failure = assertFailsWith<VaultUnavailableException> { vault.read("secret-key") }
        assertEquals("Keychain read failed: OSStatus=$errSecInteractionNotAllowed", failure.message)
        assertFalse(failure.message.orEmpty().contains("secret-key"))
    }

    @Test
    fun readsBinaryAndEmptyValuesWithoutTextConversion() {
        val api = FakeApi()
        val vault = IosKeychainVault("piko.test", api)
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0, -1, 42, 0))) {
            api.onRead = { query, result ->
                assertEquals(kCFBooleanTrue, CFDictionaryGetValue(query, kSecReturnData))
                val storage = if (bytes.isEmpty()) byteArrayOf(0) else bytes
                storage.usePinned {
                    result.pointed.value = CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong())
                }
                errSecSuccess
            }
            assertContentEquals(bytes, vault.read("account"))
        }
    }

    @Test
    fun successWithoutAValueIsReportedAsFailureNotLogout() {
        val api = FakeApi()
        api.onRead = { _, _ -> errSecSuccess }
        assertFailsWith<VaultUnavailableException> { IosKeychainVault("piko.test", api).read("account") }
    }

    @Test
    fun updatingExistingSecretNeverDeletesItFirst() {
        val api = FakeApi()
        api.onUpdate = { query, attributes ->
            assertQuery(query)
            assertNull(CFDictionaryGetValue(query, kSecValueData))
            assertNotNull(CFDictionaryGetValue(attributes, kSecValueData))
            assertEquals(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, CFDictionaryGetValue(attributes, kSecAttrAccessible))
            errSecSuccess
        }
        IosKeychainVault("piko.test", api).write("account", byteArrayOf(5))
        assertEquals(listOf("update"), api.calls)
    }

    @Test
    fun missingItemAddsAndConcurrentInsertRetriesUpdate() {
        val api = FakeApi()
        var updates = 0
        api.onUpdate = { query, _ ->
            assertNull(CFDictionaryGetValue(query, kSecValueData))
            if (++updates == 1) errSecItemNotFound else errSecSuccess
        }
        api.onAdd = { query ->
            assertQuery(query)
            assertNotNull(CFDictionaryGetValue(query, kSecValueData))
            assertEquals(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, CFDictionaryGetValue(query, kSecAttrAccessible))
            errSecDuplicateItem
        }
        IosKeychainVault("piko.test", api).write("account", byteArrayOf())
        assertEquals(listOf("update", "add", "update"), api.calls)
    }

    @Test
    fun missingItemIsAddedOnceAndAddFailurePropagates() {
        val api = FakeApi()
        api.onUpdate = { _, _ -> errSecItemNotFound }
        val vault = IosKeychainVault("piko.test", api)
        vault.write("account", byteArrayOf(7))
        assertEquals(listOf("update", "add"), api.calls)
        api.calls.clear()
        api.onAdd = { errSecInteractionNotAllowed }
        assertFailsWith<VaultUnavailableException> { vault.write("account", byteArrayOf(8)) }
        assertEquals(listOf("update", "add"), api.calls)
    }

    @Test
    fun accessFailureDoesNotTriggerDeleteOrPlaintextFallback() {
        val api = FakeApi()
        api.onUpdate = { _, _ -> errSecInteractionNotAllowed }
        assertFailsWith<VaultUnavailableException> {
            IosKeychainVault("piko.test", api).write("account", byteArrayOf(1))
        }
        assertEquals(listOf("update"), api.calls)
    }

    @Test
    fun deletionIsIdempotentButReportsUnavailableStorage() {
        val api = FakeApi()
        val vault = IosKeychainVault("piko.test", api)
        api.onDelete = { query -> assertQuery(query); errSecItemNotFound }
        vault.delete("account")
        api.onDelete = { _ -> errSecSuccess }
        vault.delete("account")
        api.onDelete = { _ -> errSecInteractionNotAllowed }
        assertFailsWith<VaultUnavailableException> { vault.delete("account") }
    }

    private fun assertQuery(query: CFDictionaryRef) {
        assertEquals(kSecClassGenericPassword, CFDictionaryGetValue(query, kSecClass))
        assertEquals(kCFBooleanFalse, CFDictionaryGetValue(query, kSecAttrSynchronizable))
        assertNotNull(CFDictionaryGetValue(query, kSecAttrService))
        assertNotNull(CFDictionaryGetValue(query, kSecAttrAccount))
        assertNull(CFDictionaryGetValue(query, kSecAttrAccessGroup))
    }

    private class FakeApi : KeychainApi {
        val calls = mutableListOf<String>()
        var onRead: (CFDictionaryRef, CPointer<CFTypeRefVar>) -> Int = { _, _ -> errSecItemNotFound }
        var onUpdate: (CFDictionaryRef, CFDictionaryRef) -> Int = { _, _ -> errSecSuccess }
        var onAdd: (CFDictionaryRef) -> Int = { errSecSuccess }
        var onDelete: (CFDictionaryRef) -> Int = { errSecSuccess }
        override fun copyMatching(query: CFDictionaryRef, result: CPointer<CFTypeRefVar>): Int {
            calls += "read"
            return onRead(query, result)
        }
        override fun update(query: CFDictionaryRef, attributes: CFDictionaryRef): Int {
            calls += "update"
            return onUpdate(query, attributes)
        }
        override fun add(query: CFDictionaryRef): Int {
            calls += "add"
            return onAdd(query)
        }
        override fun delete(query: CFDictionaryRef): Int {
            calls += "delete"
            return onDelete(query)
        }
    }
}
