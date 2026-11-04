package dev.piko.ui.i18n

import java.util.Locale

internal actual fun syncPlatformResourceLocale(languageTag: String) {
    val expected = Locale.forLanguageTag(languageTag)
    if (Locale.getDefault() != expected) Locale.setDefault(expected)
}
