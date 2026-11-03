package dev.piko.shared.search

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCompatibilityMapping

internal actual fun nfkcNormalize(text: String): String =
    NSString.create(string = text).precomposedStringWithCompatibilityMapping
