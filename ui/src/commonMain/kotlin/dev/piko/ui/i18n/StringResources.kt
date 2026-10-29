package dev.piko.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.piko.shared.i18n.PikoLocale
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.PluralStringResource

/** Refresh the resource environment on language changes without recreating screen state. */
@Composable
fun stringResource(resource: StringResource): String = key(PikoLocale.effectiveLanguage) {
    org.jetbrains.compose.resources.stringResource(resource)
}

@Composable
fun stringResource(resource: StringResource, vararg args: Any): String = key(PikoLocale.effectiveLanguage) {
    org.jetbrains.compose.resources.stringResource(resource, *args)
}

@Composable
fun pluralStringResource(resource: PluralStringResource, quantity: Int, vararg args: Any): String = key(PikoLocale.effectiveLanguage) {
    org.jetbrains.compose.resources.pluralStringResource(resource, quantity, *args)
}
