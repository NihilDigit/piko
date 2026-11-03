package dev.piko.shared.search

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCompatibilityMapping

internal actual fun nfkcNormalize(text: String): String =
    (text as NSString).precomposedStringWithCompatibilityMapping
