package dev.piko.shared.search

import java.text.Normalizer

internal actual fun nfkcNormalize(text: String): String {
    var needsNormalization = false
    for (i in 0 until text.length) {
        if (text[i].code >= 0x80) {
            needsNormalization = true
            break
        }
    }
    if (!needsNormalization) return text
    return if (Normalizer.isNormalized(text, Normalizer.Form.NFKC)) {
        text
    } else {
        Normalizer.normalize(text, Normalizer.Form.NFKC)
    }
}
