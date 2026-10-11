package dev.piko.shared.state

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.presentation.ActionAvailability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/** 保存流程只接收目录上下文，不依赖活动标签或移动返回栈。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveTargetBinding(private val currentAccount: () -> String?, accounts: Flow<String?>) {
    private class Bound(val account: String, val context: SaveTargetContext)
    private val bound = MutableStateFlow<Bound?>(null)
    val context = SaveTargetContext(
        currentFolder = { bound.value?.takeIf { it.account == currentAccount() }?.context?.currentFolder?.invoke() ?: PikoDriveRepository.ROOT_BREADCRUMB },
        folders = combine(bound, accounts) { owner, account -> owner?.takeIf { it.account == account } }
            .flatMapLatest { it?.context?.folders ?: flowOf(PikoDriveRepository.ROOT_BREADCRUMB) },
        availability = { folder -> bound.value?.takeIf { it.account == currentAccount() }?.context?.availability?.invoke(folder)
            ?: ActionAvailability.Blocked("请先打开当前账号的文件工作区") },
        actionChanges = combine(bound, accounts) { owner, account -> owner?.takeIf { it.account == account } }
            .flatMapLatest { it?.context?.actionChanges?.onStart { emit(Unit) } ?: flowOf(Unit) },
    )

    fun bind(account: String, context: SaveTargetContext): () -> Unit {
        if (account != currentAccount()) return {}
        val owner = Bound(account, context)
        bound.value = owner
        return { bound.compareAndSet(owner, null); Unit }
    }
}
