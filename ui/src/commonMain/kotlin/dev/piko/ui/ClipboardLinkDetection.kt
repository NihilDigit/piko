package dev.piko.ui

import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.extractLinks

/** 仅在进程内保留，切换页面或账号也不会重复提示同一份剪贴板。 */
internal class ClipboardLinkDetection {
    private val detectedTexts = mutableSetOf<String>()
    private val handledLinks = mutableSetOf<String>()

    fun handled(text: String) {
        handledLinks += extractLinks(text).map { it.key }
    }

    fun detect(text: String?): String? {
        if (text.isNullOrBlank() || text in detectedTexts) return null
        val links = extractLinks(text).filter {
            it.key !in handledLinks && (it.isMagnet || InstantSheetState.findShareLink(it.uri) != null)
        }
        if (links.isEmpty()) return null
        detectedTexts += text
        handledLinks += links.map { it.key }
        return links.joinToString("\n") { it.uri }
    }

    companion object {
        val session = ClipboardLinkDetection()
    }
}
