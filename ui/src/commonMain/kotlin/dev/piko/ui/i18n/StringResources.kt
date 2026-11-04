package dev.piko.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.piko.shared.i18n.PikoLocale
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

internal expect fun syncPlatformResourceLocale(languageTag: String)

private fun syncResourceLocaleToEffective(): dev.piko.data.auth.AppLanguage {
    val language = PikoLocale.effectiveLanguage
    syncPlatformResourceLocale(language.tag)
    return language
}

/** Refresh the resource environment on language changes without recreating screen state. */
@Composable
fun stringResource(resource: StringResource): String {
    val language = syncResourceLocaleToEffective()
    return key(language) {
        org.jetbrains.compose.resources.stringResource(resource)
    }
}

@Composable
fun stringResource(resource: StringResource, vararg args: Any): String {
    val language = syncResourceLocaleToEffective()
    return key(language) {
        org.jetbrains.compose.resources.stringResource(resource, *args)
    }
}

@Composable
fun pluralStringResource(resource: PluralStringResource, quantity: Int, vararg args: Any): String {
    val language = syncResourceLocaleToEffective()
    return key(language) {
        org.jetbrains.compose.resources.pluralStringResource(resource, quantity, *args)
    }
}
