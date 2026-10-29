package dev.piko.shared.i18n

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.piko.data.auth.AppLanguage

/** Only platform entrypoints bind the system locale, before changing the process locale. */
object PikoLocale {
    var systemLanguageTag: String = "zh-CN"
        private set
    private var currentPreference: AppLanguage = AppLanguage.ZH_CN
    private val language = mutableStateOf(AppLanguage.ZH_CN)
    @Volatile
    var revision: Long = 0L
        private set
    val effectiveLanguageState: State<AppLanguage> get() = language
    val effectiveLanguage: AppLanguage get() = language.value

    fun bindSystemLocale(languageTag: String) {
        systemLanguageTag = languageTag
        if (currentPreference == AppLanguage.SYSTEM) {
            updateEffectiveLanguage(resolve(AppLanguage.SYSTEM))
        }
    }

    fun resolve(preference: AppLanguage): AppLanguage = if (preference != AppLanguage.SYSTEM) preference else {
        when (systemLanguageTag.substringBefore('-').substringBefore('_').lowercase()) {
            "zh" -> AppLanguage.ZH_CN
            "ja" -> AppLanguage.JA
            "ko" -> AppLanguage.KO
            else -> AppLanguage.EN
        }
    }

    fun apply(preference: AppLanguage) {
        currentPreference = preference
        updateEffectiveLanguage(resolve(preference))
    }

    private fun updateEffectiveLanguage(next: AppLanguage) {
        if (language.value != next) {
            revision++
            language.value = next
        }
    }
}

fun tr(zh: String, en: String, ja: String = en, ko: String = en): String = when (PikoLocale.effectiveLanguage) {
    AppLanguage.ZH_CN -> zh
    AppLanguage.JA -> ja
    AppLanguage.KO -> ko
    else -> en
}

/** English counters for inline translations and synchronous notices. */
fun enCount(value: Any?, singular: String, plural: String = singular + "s"): String {
    val isOne = (value as? Number)?.toDouble() == 1.0 || value == "1"
    return "$value ${if (isOne) singular else plural}"
}
