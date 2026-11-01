package dev.piko.shared.smoke

import dev.piko.shared.data.AccountScopedPreferences
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.state.InstantActionKind
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.sync.SyncedSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceSaveRememberedStateTest {
    private class Rig(val scope: CoroutineScope) {
        val server = FakePikPakServer()
        val prefs = MemoryPreferences()
        val provider = server.provider()
        val instant = InstantMagnetRepository(provider)
        val drive = PikoDriveRepository(provider, prefs)
        private val preview = PreviewTempFolder(provider, drive, instant, scope)
        private val packs = OfflinePackTracker(instant, drive, prefs)
        private val records = InstantSaveRecords(provider, null, scope)
        fun sheet(input: String = "") = InstantSheetState(instant, drive, prefs, preview, packs, records, scope, input)
    }

    @Test
    fun defaultIsUncheckedAndPreferenceIsDeviceLocal() = smoke { scope ->
        val rig = Rig(scope)
        assertFalse(rig.prefs.saveAsReferenceFlow.value)
        assertFalse(rig.sheet().saveAsReference)
        assertTrue(SyncedSettings.none { it.key.contains("reference", ignoreCase = true) })
        val scoped = AccountScopedPreferences(rig.prefs, rig.provider, MemoryCacheStore())
        scoped.setSaveAsReference(true)
        assertTrue(rig.prefs.saveAsReferenceFlow.value)
        assertTrue(scoped.saveAsReferenceFlow.first())
    }

    @Test
    fun singleSelectionPersistsAndNewDraftRestoresIt() = smoke { scope ->
        val rig = Rig(scope)
        val first = rig.sheet()
        first.updateSaveAsReference(true)
        awaitUntil("单条勾选已保存") { rig.prefs.saveAsReferenceFlow.value }
        val next = rig.sheet()
        awaitUntil("新草稿恢复勾选") { next.saveAsReference }
        next.updateSaveAsReference(false)
        awaitUntil("取消勾选已保存") { !rig.prefs.saveAsReferenceFlow.value }
        assertFalse(rig.sheet().saveAsReference)
    }

    @Test
    fun batchSharesRememberedStateAndPersistsChanges() = smoke { scope ->
        val rig = Rig(scope)
        rig.prefs.setSaveAsReference(true)
        val links = ('a'..'b').map { "magnet:?xt=urn:btih:" + it.toString().repeat(40) }
        links.forEach { rig.server.indexMagnet(it, resourceListBody("movie", listOf(Triple("movie.mkv", 100L, "GCID")))) }
        val state = rig.sheet(links.joinToString("\n"))
        val batch = assertNotNull(state.batch)
        awaitUntil("批量共享恢复的勾选") { batch.saveAsReference && batch.rows.all { it.state.saveAsReference } }
        batch.updateSaveAsReference(false)
        awaitUntil("批量取消已保存") { !rig.prefs.saveAsReferenceFlow.value }
        assertFalse(state.saveAsReference)
        assertTrue(batch.rows.none { it.state.saveAsReference })
        batch.updateSaveAsReference(true)
        awaitUntil("批量勾选已保存") { rig.prefs.saveAsReferenceFlow.value }
        val next = rig.sheet()
        awaitUntil("批量选择用于新草稿") { next.saveAsReference }
    }

    @Test
    fun rememberedReferenceAllowsAllDirectLinksInBatch() = smoke { scope ->
        val rig = Rig(scope)
        rig.prefs.setSaveAsReference(true)
        val links = listOf(
            "ed2k://|file|first.mkv|1024|0123456789ABCDEF0123456789ABCDEF|/",
            "ed2k://|file|second.mkv|2048|ABCDEF0123456789ABCDEF0123456789|/",
        )
        val state = rig.sheet(links.joinToString("\n"))
        val batch = assertNotNull(state.batch)
        awaitUntil("直接链接批量可保存") { batch.saveAsReference && batch.canSaveAll }
        assertNull(batch.blockedReason)
        assertTrue(batch.rows.none { it.state.effectiveSaveAsReference })
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        batch.saveAll()
        assertEquals(2, assertIs<InstantSaveOutcome.OfflineTaskCreated>(outcome.await()).submittedCount)
        assertEquals(links.toSet(), rig.server.tasksSnapshot().map { it.url }.toSet())
        assertTrue(rig.prefs.saveAsReferenceFlow.value)
    }

    @Test
    fun rememberedReferenceNeverBlocksEd2kOfflineSubmission() = smoke { scope ->
        val rig = Rig(scope)
        rig.prefs.setSaveAsReference(true)
        val link = "ed2k://|file|movie.mkv|1024|0123456789ABCDEF0123456789ABCDEF|/"
        val state = rig.sheet(link)
        awaitUntil("ed2k 解析与目标就绪") { state.resolution != null && state.target != null && state.saveAsReference }
        assertFalse(state.supportsReferenceSave)
        assertFalse(state.effectiveSaveAsReference)
        assertTrue(state.canSaveSelection)
        assertEquals(InstantActionKind.SUBMIT_OFFLINE, state.primaryAction?.kind)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        state.performPrimaryAction()
        assertIs<InstantSaveOutcome.OfflineTaskCreated>(outcome.await())
        assertEquals(link, rig.server.tasksSnapshot().single().url)
        assertNull(state.errorMessage)
        assertTrue(rig.prefs.saveAsReferenceFlow.value)
    }
}
