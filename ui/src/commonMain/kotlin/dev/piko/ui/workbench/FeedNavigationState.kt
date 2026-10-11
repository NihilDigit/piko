package dev.piko.ui.workbench

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.presentation.FileLocation
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.ui.navigation.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 信息流的呈现位置由工作区保留；队列和播放资源仍属于业务会话。 */
internal class FeedNavigationState(
    private val scope: CoroutineScope,
    private val currentLocation: () -> FileLocation,
    private val currentPath: () -> List<PikoPathBreadcrumb>,
    private val open: suspend (List<PikoPathBreadcrumb>) -> Unit,
    private val closeSession: () -> Unit,
    private val isCurrent: () -> Boolean,
) {
    var shown by mutableStateOf<Boolean?>(null)
    var returnLocation by mutableStateOf<FileLocation?>(null)
    var home by mutableStateOf<FileLocation?>(null)
    var detourTab by mutableStateOf<Long?>(null)
    var opened by mutableStateOf(false)
    private var browseEntry by mutableStateOf<Screen.Browse?>(null)
    private var prepareJob: Job? = null
    private var opening = false
    private var generation = 0L

    fun prepare(ready: Flow<Boolean>) {
        if (opened || opening || !isCurrent()) return
        prepareJob?.cancel()
        val request = ++generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            ready.first { it }
            if (request != generation || !isCurrent()) return@launch
            opening = true
            home = currentLocation()
            try {
                open(currentPath())
                if (request == generation && isCurrent()) opened = true
            } finally { if (request == generation) opening = false }
        }
        prepareJob = job
        job.start()
    }

    fun markBrowse(entry: Screen.Browse) { browseEntry = entry }

    fun resume(): FileLocation? = returnLocation.also {
        returnLocation = null
        browseEntry = null
    }

    fun returningFrom(screen: Screen?): FileLocation? = if (screen != null && screen == browseEntry) resume() else null

    fun close() {
        generation++
        prepareJob?.cancel()
        prepareJob = null
        opening = false
        shown = false
        returnLocation = null
        home = null
        detourTab = null
        opened = false
        browseEntry = null
        closeSession()
    }
}
