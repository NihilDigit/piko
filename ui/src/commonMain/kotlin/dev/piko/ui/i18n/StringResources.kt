package dev.piko.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.piko.shared.i18n.PikoLocale
import java.util.Locale
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

private fun syncJvmLocaleToEffective(): dev.piko.data.auth.AppLanguage {
    val language = PikoLocale.effectiveLanguage
    val expected = Locale.forLanguageTag(language.tag)
    if (Locale.getDefault() != expected) {
        Locale.setDefault(expected)
    }
    return language
}

/** Refresh the resource environment on language changes without recreating screen state. */
@Composable
fun stringResource(resource: StringResource): String {
    val language = syncJvmLocaleToEffective()
    return key(language) {
        org.jetbrains.compose.resources.stringResource(resource)
    }
}

@Composable
fun stringResource(resource: StringResource, vararg args: Any): String {
    val language = syncJvmLocaleToEffective()
    return key(language) {
        org.jetbrains.compose.resources.stringResource(resource, *args)
    }
}

@Composable
fun pluralStringResource(resource: PluralStringResource, quantity: Int, vararg args: Any): String {
    val language = syncJvmLocaleToEffective()
    return key(language) {
        org.jetbrains.compose.resources.pluralStringResource(resource, quantity, *args)
    }
}
