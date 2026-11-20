package dev.piko.shared.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateKind
import dev.piko.shared.state.DuplicateKeepRule
import dev.piko.shared.state.DuplicateListing
import dev.piko.shared.state.SessionListing
import dev.piko.shared.state.orderedRows

class DuplicateReportViewModel {
    var kind by mutableStateOf(DuplicateKind.IDENTICAL)
        private set

    fun selectKind(value: DuplicateKind) {
        kind = value
    }

    var keepRule by mutableStateOf(DuplicateKeepRule.RECOMMENDED)
        private set

    fun selectKeepRule(rule: DuplicateKeepRule) { keepRule = rule }

    fun listing(report: DuplicateFinderState): SessionListing =
        DuplicateListing(report, kind, keepRule = { keepRule }, kindProvider = { kind })

    private fun groups(finder: DuplicateFinderState) = when (kind) {
        DuplicateKind.IDENTICAL -> finder.report.identical
        DuplicateKind.VERSIONS -> finder.report.versions
    }

    fun smartSelectIds(finder: DuplicateFinderState): Set<String> {
        val list = groups(finder)
        if (list.isEmpty()) return emptySet()
        val rule = keepRule
        val out = HashSet<String>(list.size * 2)
        for (i in 0 until list.size) {
            val ordered = list[i].orderedRows(rule)
            for (j in 1 until ordered.size) {
                out.add(ordered[j].file.id)
            }
        }
        return out
    }

    fun invertSelectionIds(finder: DuplicateFinderState, currentSelected: Set<String>): Set<String> {
        val list = groups(finder)
        if (list.isEmpty()) return emptySet()
        val out = HashSet<String>(list.size * 2)
        for (i in 0 until list.size) {
            val rows = list[i].rows
            for (j in 0 until rows.size) {
                val id = rows[j].file.id
                if (id !in currentSelected) out.add(id)
            }
        }
        return out
    }
}
