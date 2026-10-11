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
}
