package dev.piko.ui.workbench

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

internal class AccountPresentationStores {
    private class Owner : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    // 资源注册不能随一次组合快照回滚；只用世代变化通知界面重新绑定。
    private var account: String? = null
    private var owner: Owner? = null
    var generation by mutableLongStateOf(0L)
        private set

    fun forAccount(account: String): ViewModelStoreOwner {
        if (this.account == account) return requireNotNull(owner)
        owner?.viewModelStore?.clear()
        return Owner().also { this.account = account; owner = it }
    }

    fun leave(account: String) {
        if (this.account != account) return
        owner?.viewModelStore?.clear()
        owner = null
        this.account = null
        generation++
    }
}
