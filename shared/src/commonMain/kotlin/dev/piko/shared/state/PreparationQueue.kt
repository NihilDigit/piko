package dev.piko.shared.state

import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.PikPakStreamReader
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 有界候补准备与预取队列：后台预取共享 [backgroundSlots] 个槽位，前台播放（优先级不低于
 * [PikPakStreamReader.STREAMING_PRIORITY]）额外保有一个保留槽位；排队中的票据随 [priority] 动态原地提升。
 */
internal class PreparationQueue(private val backgroundSlots: Int = 2) {
    private class Ticket(val priority: StateFlow<Int>, var active: Boolean = false)
    private val lock = PikoLock()
    private val tickets = mutableListOf<Ticket>()
    private val revision = MutableStateFlow(0L)
    private fun changed() = revision.update { it + 1 }

    suspend fun <T> run(priority: StateFlow<Int>, block: suspend () -> T): T = coroutineScope {
        val ticket = Ticket(priority)
        withPikoLock(lock) { tickets.add(ticket); changed() }
        val watcher = launch { priority.collect { changed() } }
        try {
            while (true) {
                val seen = revision.value
                val admitted = withPikoLock(lock) {
                    val count = tickets.count { it.active }
                    val next = tickets.filter {
                        !it.active && count < backgroundSlots + if (it.priority.value >= PikPakStreamReader.STREAMING_PRIORITY) 1 else 0
                    }.maxByOrNull { it.priority.value }
                    if (next === ticket) { ticket.active = true; changed(); true } else false
                }
                if (admitted) break
                revision.first { it != seen }
            }
            block()
        } finally {
            watcher.cancel()
            withPikoLock(lock) { tickets.remove(ticket); changed() }
        }
    }
}
