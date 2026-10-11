package dev.piko.shared.state

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.ActionAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

class SaveTargetContext(
    val currentFolder: () -> PikoPathBreadcrumb,
    val folders: Flow<PikoPathBreadcrumb>,
    val availability: (String) -> ActionAvailability = { ActionAvailability.Available },
    val actionChanges: Flow<Unit> = emptyFlow(),
) {
    companion object {
        fun fixed(folder: PikoPathBreadcrumb = PikoDriveRepository.ROOT_BREADCRUMB) =
            SaveTargetContext({ folder }, flowOf(folder))
    }
}
