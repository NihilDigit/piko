package dev.piko.shared.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.state.DuplicateDirectoryListing
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateKind
import dev.piko.shared.state.DuplicateKeepRule
import dev.piko.shared.state.DuplicateListing
import dev.piko.shared.state.SessionListing
import dev.piko.shared.state.orderedRows
import dev.piko.shared.data.DriveLibrary

class DuplicateReportViewModel {
    var kind by mutableStateOf(DuplicateKind.IDENTICAL)
        private set

    fun selectKind(value: DuplicateKind) {
        kind = value
    }

    var keepRule by mutableStateOf(DuplicateKeepRule.RECOMMENDED)
        private set

    fun selectKeepRule(rule: DuplicateKeepRule) { keepRule = rule }

    fun listing(report: DuplicateFinderState, folderId: String): SessionListing =
        DuplicateListing(report, kind, keepRule = { keepRule }, kindProvider = { kind })

    private fun groups(finder: DuplicateFinderState) = when (kind) {
        DuplicateKind.IDENTICAL -> finder.report.identical
        DuplicateKind.VERSIONS -> finder.report.versions
    }

    fun smartSelectIds(finder: DuplicateFinderState): Set<String> = groups(finder)
        .flatMap { it.orderedRows(keepRule).drop(1) }.mapTo(mutableSetOf()) { it.file.id }

    fun invertSelectionIds(finder: DuplicateFinderState, currentSelected: Set<String>): Set<String> = groups(finder)
        .flatMap { it.rows }.map { it.file.id }.filterTo(mutableSetOf()) { it !in currentSelected }
}
