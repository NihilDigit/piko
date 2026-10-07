package dev.piko.shared.state

import dev.piko.shared.data.PikoClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 当前账号变了（换号，或退出到登录页）时调 [onLeave]，参数是离开的那个账号，在这个作用域的线程上调。
 * 断线重连换的是同一账号的新 client，不算；退出后登回同一账号也不算，退出那一下已经调过。
 *
 * 进程级的会话（添加链接、查重、规范命名、解压、信息流、归档）与桌面端的独立窗口都在这里结束，
 * 不各自收集账号：各管各的时候总有漏掉的，信息流的存盘就曾不分账号。
 */
fun CoroutineScope.launchOnAccountLeave(clients: PikoClientProvider, onLeave: (left: String) -> Unit): Job = launch {
    var previous: String? = null
    clients.currentClient.map { it?.account }.distinctUntilChanged().collect { account ->
        val left = previous
        previous = account
        if (left != null) onLeave(left)
    }
}
