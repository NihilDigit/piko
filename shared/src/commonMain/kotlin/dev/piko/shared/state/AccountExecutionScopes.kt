package dev.piko.shared.state

import dev.piko.shared.data.PikoDriveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 已接受的文件操作不依附页面；活动账号改变时取消对应的执行作用域。 */
class AccountExecutionScopes(private val repository: PikoDriveRepository, private val parent: CoroutineScope) {
    private data class Execution(val account: String, val job: Job, val scope: CoroutineScope)
    private var execution: Execution? = null

    fun forAccount(account: String): CoroutineScope {
        if (repository.currentAccount != account) {
            val cancelled = SupervisorJob(parent.coroutineContext[Job]).also { it.cancel() }
            return CoroutineScope(parent.coroutineContext + cancelled)
        }
        execution?.takeIf { it.account == account && it.job.isActive && repository.currentAccount == account }?.let { return it.scope }
        execution?.job?.cancel()
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        execution = Execution(account, job, scope)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.accountChanges.first { it != account }
            job.cancel()
        }
        return scope
    }
}
