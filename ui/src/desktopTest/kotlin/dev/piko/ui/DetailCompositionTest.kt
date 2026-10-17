package dev.piko.ui

import dev.piko.ui.components.*
import dev.piko.ui.screens.transfers.TaskListOrder
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.state.TransferItem
import dev.piko.shared.state.TransferSections
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.*

class DetailCompositionTest {
    @Test
    fun ownersRetainDetailsAcrossPresentationRecreationAndReleaseThemIndependently() {
        val store = DetailPanelStore()
        val key = DetailPanelKey<String>(DetailPanelSpec("详情", "关闭详情"))
        val firstOwner = Any()
        val secondOwner = Any()
        val first = store.stateFor(firstOwner, key)
        first.show("first")
        store.stateFor(secondOwner, key).show("second")
        assertSame(first, store.stateFor(firstOwner, key))
        assertEquals("first", store.stateFor(firstOwner, key).target)
        store.forget(firstOwner)
        assertNull(first.target)
        assertEquals("second", store.stateFor(secondOwner, key).target)
        assertNull(store.stateFor(firstOwner, key).target)
        store.clear()
    }

    @Test
    fun replacingDetailsPreservesDraggedPositionAndInstancesAreIndependent() {
        val first = DetailPanelState<String>(DetailPanelSpec("任务详情", "关闭任务详情"))
        val second = DetailPanelState<String>(DetailPanelSpec("属性", "关闭属性"))
        first.show("a")
        first.panel.position = DpOffset(20.dp, 30.dp)
        first.show("b", PanelAnchor.AtPoint(DpOffset(80.dp, 90.dp)))
        assertEquals(DpOffset(20.dp, 30.dp), first.panel.position)
        assertNull(second.target)
        first.close()
        assertNull(first.target)
        first.show("c", PanelAnchor.AtPoint(DpOffset(80.dp, 90.dp)))
        assertNull(first.panel.position)
        assertEquals(PanelAnchor.AtPoint(DpOffset(80.dp, 90.dp)), first.panel.anchor)
    }

    @Test
    fun batchOrderUpdatesProgressWithoutMovingRowsBetweenGroups() {
        val a = TransferItem.Local(DownloadTask("a", "a", "a.mp4", "a", 100, status = DownloadStatus.DOWNLOADING))
        val b = TransferItem.Local(DownloadTask("b", "b", "b.mp4", "b", 100, status = DownloadStatus.PAUSED))
        val order = TaskListOrder(TransferSections(listOf(a, b), emptyList(), emptyList(), emptyList()))
        val completed = a.copy(task = a.task.copy(status = DownloadStatus.COMPLETED, downloadedBytes = 100))
        val live = TransferSections(listOf(b), emptyList(), listOf(completed), emptyList())
        val stable = order.apply(live)
        assertEquals(listOf(a.key, b.key), stable.inProgress.map { it.key })
        assertEquals(DownloadStatus.COMPLETED, (stable.inProgress.first() as TransferItem.Local).task.status)
        assertTrue(stable.completed.isEmpty())
        val removed = order.apply(TransferSections(listOf(b), emptyList(), emptyList(), emptyList()))
        assertEquals(listOf(b.key), removed.inProgress.map { it.key })
    }
}
