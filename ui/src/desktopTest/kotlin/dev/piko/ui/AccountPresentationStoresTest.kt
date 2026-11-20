package dev.piko.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.piko.ui.workbench.AccountPresentationStores
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class AccountPresentationStoresTest {
    private class Probe : ViewModel() {
        var cleared = false
            private set
        override fun onCleared() { cleared = true }
    }

    private fun model(owner: ViewModelStoreOwner) =
        ViewModelProvider.create(owner, viewModelFactory { initializer { Probe() } })[Probe::class]

    @Test
    fun rebuildingTheBindingRetainsModelsAndLeavingTheAccountReleasesThem() {
        val stores = AccountPresentationStores()
        val firstOwner = stores.forAccount("a")
        val first = model(firstOwner)
        assertTrue(stores.forAccount("a") === firstOwner)
        assertTrue(model(stores.forAccount("a")) === first)
        assertFalse(first.cleared)

        val secondOwner = stores.forAccount("b")
        val second = model(secondOwner)
        val generation = stores.generation
        assertTrue(first.cleared)
        stores.leave("a")
        assertEquals(generation, stores.generation)
        assertFalse(second.cleared, "旧界面退出不能清除新账号状态")
        stores.leave("b")
        assertTrue(stores.generation > generation)
        assertTrue(second.cleared)
        assertTrue(stores.forAccount("a") !== firstOwner)
        stores.leave("a")
    }

    @Test
    fun staleRecompositionDuringAccountSwitchDoesNotEvictNewActiveAccount() {
        val stores = AccountPresentationStores()
        val oldOwner = stores.forAccount("acct-old", activeAccount = "acct-old")
        val oldModel = model(oldOwner)
        assertFalse(oldModel.cleared)

        // Account switches from acct-old -> acct-new: PikoServices tears down old store, new account enters.
        stores.leave("acct-old")
        assertTrue(oldModel.cleared)

        val newOwner = stores.forAccount("acct-new", activeAccount = "acct-new")
        val newModel = model(newOwner)
        assertFalse(newModel.cleared)

        // Simulate outgoing Crossfade frame recomposing with stale account="acct-old" while active="acct-new".
        val staleOwner = stores.forAccount("acct-old", activeAccount = "acct-new")
        assertTrue(staleOwner !== newOwner)
        assertFalse(newModel.cleared, "Stale Crossfade recomposition must never clear the new active account's store")
        assertTrue(stores.forAccount("acct-new", activeAccount = "acct-new") === newOwner)

        // Rapid flip acct-new -> acct-third -> stale acct-old & stale acct-new calls.
        stores.leave("acct-new")
        assertTrue(newModel.cleared)
        val thirdOwner = stores.forAccount("acct-third", activeAccount = "acct-third")
        val thirdModel = model(thirdOwner)
        assertTrue(stores.forAccount("acct-old", activeAccount = "acct-third") !== thirdOwner)
        assertTrue(stores.forAccount("acct-new", activeAccount = "acct-third") !== thirdOwner)
        assertFalse(thirdModel.cleared)
        assertTrue(stores.forAccount("acct-third", activeAccount = "acct-third") === thirdOwner)
        stores.leave("acct-third")
        assertTrue(thirdModel.cleared)
    }
}
