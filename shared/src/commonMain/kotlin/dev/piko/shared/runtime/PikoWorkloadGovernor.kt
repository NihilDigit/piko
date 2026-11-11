package dev.piko.shared.runtime

import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class WorkPurpose {
    Playback,
    ClipFeed,
    GalleryDiscovery,
    CoverPrefetch,
    BackgroundSync,
}

enum class WorkPhase {
    /** 冷启动、定位拖动或缓冲不足；网络优先级由账号级需求租约表达。 */
    Urgent,
    /** 稳定播放且缓冲充足。 */
    Steady,
    /** 播放器暂停或本地/静态资源展示。 */
    Paused,
    /** 普通活跃工作。 */
    Active,
}

data class WorkBudget(
    val hasActivePlayback: Boolean = false,
    val hasUrgentPlayback: Boolean = false,
    val galleryDiscoveryConcurrency: Int = DEFAULT_GALLERY_DISCOVERY_CONCURRENCY,
    val coverPrefetchConcurrency: Int = DEFAULT_COVER_PREFETCH_CONCURRENCY,
    val allowClipPrefetch: Boolean = true,
    val allowBackgroundSync: Boolean = true,
) {
    companion object {
        const val DEFAULT_GALLERY_DISCOVERY_CONCURRENCY = 6
        const val DEFAULT_COVER_PREFETCH_CONCURRENCY = 8
    }
}

interface WorkLease : AutoCloseable {
    fun updatePhase(phase: WorkPhase)
    override fun close()
}

/**
 * 进程级工作阶段汇总；不根据播放器存在与否暂停无关业务：
 * 各业务会话（播放器、瀑布流、信息流、后台同步）仅声明自身工作目的与阶段，
 * 不互相直接引用或手写页面级 `pausedByHost` 布尔开关。
 */
class PikoWorkloadGovernor {
    private class Entry(
        val purpose: WorkPurpose,
        var phase: WorkPhase,
    )

    private val lock = PikoLock()
    private var nextId = 1L
    private val entries = LinkedHashMap<Long, Entry>()
    private val mutableBudget = MutableStateFlow(WorkBudget())
    val budget: StateFlow<WorkBudget> = mutableBudget.asStateFlow()

    fun register(purpose: WorkPurpose, initialPhase: WorkPhase = WorkPhase.Active): WorkLease {
        val id = withPikoLock(lock) {
            val allocated = nextId++
            entries[allocated] = Entry(purpose, initialPhase)
            recomputeLocked()
            allocated
        }
        return object : WorkLease {
            private var closed = false

            override fun updatePhase(phase: WorkPhase) {
                withPikoLock(lock) {
                    if (closed) return
                    val entry = entries[id] ?: return
                    if (entry.phase == phase) return
                    entry.phase = phase
                    recomputeLocked()
                }
            }

            override fun close() {
                withPikoLock(lock) {
                    if (closed) return
                    closed = true
                    if (entries.remove(id) != null) {
                        recomputeLocked()
                    }
                }
            }
        }
    }

    /** 查询当前目录扫描并发度上限（当前恒定开放至 [WorkBudget.DEFAULT_GALLERY_DISCOVERY_CONCURRENCY]，不因播放暂停无关业务）。 */
    suspend fun awaitDiscoveryConcurrency(requestedMax: Int): Int {
        val allowed = budget.map { it.galleryDiscoveryConcurrency }.first { it > 0 }
        return minOf(requestedMax.coerceAtLeast(1), allowed)
    }

    /** 校验后台同步准入预算（当前默认常开，播放器网络让位经由 SDK 账号级传输需求租约调度，不在此处阻塞后台任务）。 */
    suspend fun awaitBackgroundSyncAllowed() {
        budget.first { it.allowBackgroundSync }
    }

    private fun recomputeLocked() {
        val playbacks = entries.values.filter { it.purpose == WorkPurpose.Playback }
        val hasPlayback = playbacks.any { it.phase == WorkPhase.Steady || it.phase == WorkPhase.Urgent }
        val hasUrgent = playbacks.any { it.phase == WorkPhase.Urgent }
        val next = WorkBudget(hasActivePlayback = hasPlayback, hasUrgentPlayback = hasUrgent)
        if (mutableBudget.value != next) {
            mutableBudget.value = next
        }
    }

    companion object {
        val Shared = PikoWorkloadGovernor()
    }
}

/**
 * 汇总应用内所有窗口（主窗口、独立播放器、独立信息流）的焦点状态，
 * 区分「应用内部多窗口焦点交接」与「从外部应用重新激活回到前台」。
 */
class AppPresence(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val backgroundGrace: Duration = 800.milliseconds,
) {
    private val lock = PikoLock()
    private val focusedWindows = LinkedHashSet<String>()
    private var unfocusedSince: TimeMark? = null
    private val mutableIsActive = MutableStateFlow(true)
    val isAppActive: StateFlow<Boolean> = mutableIsActive.asStateFlow()

    /**
     * 更新某个窗口的焦点状态。返回 `true` 当且仅当整个应用在失焦超过 [backgroundGrace] 后重新回到前台
     * （应用内主窗口与播放器窗口互切焦点不会触发返回 `true`）。
     */
    fun onWindowFocusChanged(windowId: String, focused: Boolean): Boolean = withPikoLock(lock) {
        val wasEmpty = focusedWindows.isEmpty()
        if (focused) {
            focusedWindows.add(windowId)
        } else {
            focusedWindows.remove(windowId)
        }
        val isEmpty = focusedWindows.isEmpty()
        mutableIsActive.value = !isEmpty
        when {
            !wasEmpty && isEmpty -> {
                unfocusedSince = timeSource.markNow()
                false
            }
            wasEmpty && !isEmpty -> {
                val mark = unfocusedSince
                unfocusedSince = null
                mark != null && mark.elapsedNow() >= backgroundGrace
            }
            else -> false
        }
    }

    fun onWindowClosed(windowId: String) = withPikoLock(lock) {
        val wasEmpty = focusedWindows.isEmpty()
        if (focusedWindows.remove(windowId) && !wasEmpty && focusedWindows.isEmpty()) {
            unfocusedSince = timeSource.markNow()
            mutableIsActive.value = false
        }
    }
}
