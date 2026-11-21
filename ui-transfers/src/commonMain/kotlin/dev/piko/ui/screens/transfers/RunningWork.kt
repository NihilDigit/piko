package dev.piko.ui

import dev.piko.shared.i18n.tr
import dev.piko.shared.media.proxy.ExternalPlayerStreams
import dev.piko.shared.state.TransferKind
import dev.piko.ui.screens.transfers.TaskPresenter

fun PikoServices.runningWork(): List<WorkProgress> {
    val externalWork = if (ExternalPlayerStreams.active.value) {
        listOf(
            WorkProgress(
                kind = WorkKind.FILES,
                title = tr(
                    "正在为外部播放器提供串流",
                    "Streaming to external player",
                    "外部プレーヤーへストリーミング中",
                    "외부 플레이어로 스트리밍 중",
                    "正在為外部播放器提供串流",
                ),
                meter = WorkMeter.None,
                status = ExternalPlayerStreams.activeFileName.value
                    ?: tr("本地代理运行中", "Local proxy active", "ローカルプロキシ稼働中", "로컬 프록시 활성", "本機代理程式運作中"),
            ),
        )
    } else {
        emptyList()
    }
    if (driveRepository.currentAccount == null) return externalWork
    val state = taskCenter
    val presenter = TaskPresenter(state, folderVaultSession)
    val transferWork = state.activity.active.map { item ->
        val row = presenter.present(item)
        val kind = when {
            TransferKind.DOWNLOAD.matches(item) -> WorkKind.DOWNLOAD
            TransferKind.UPLOAD.matches(item) -> WorkKind.UPLOAD
            else -> WorkKind.FILES
        }
        WorkProgress(kind, row.title, row.meter, row.status, row.detail)
    }
    return transferWork + externalWork
}
