package dev.piko.shared.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.state.DuplicateDirectoryListing
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateKind

class DuplicateReportViewModel {
    var kind by mutableStateOf(DuplicateKind.IDENTICAL)
        private set

    fun selectKind(value: DuplicateKind) {
        kind = value
    }

    fun listing(report: DuplicateFinderState, folderId: String) =
        DuplicateDirectoryListing(report, folderId) { kind }
}
