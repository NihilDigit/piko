package dev.piko.ui.i18n

import platform.Foundation.NSUserDefaults

internal actual fun syncPlatformResourceLocale(languageTag: String) {
    val defaults = NSUserDefaults.standardUserDefaults
    val languages = listOf(languageTag)
    if (defaults.stringArrayForKey("AppleLanguages") != languages) {
        defaults.setObject(languages, forKey = "AppleLanguages")
    }
}
