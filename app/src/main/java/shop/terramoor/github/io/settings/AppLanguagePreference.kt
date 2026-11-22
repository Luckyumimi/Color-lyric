package shop.terramoor.github.io.settings

import android.app.LocaleConfig
import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import shop.terramoor.github.io.R
import java.util.Locale

@Composable
internal fun AppLanguagePreference() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val manager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    val supported = remember(context) {
        LocaleConfig(context).supportedLocales?.let { locales ->
            List(locales.size()) { locales[it] }
        }.orEmpty()
    }
    var selected by remember(manager, configuration) { mutableStateOf(manager.applicationLocales) }
    var systemLocale by remember(manager, configuration) { mutableStateOf(manager.systemLocales[0]) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        selected = manager.applicationLocales
        systemLocale = manager.systemLocales[0]
    }

    TuningDropdown(
        title = stringResource(R.string.app_language),
        items = listOf(stringResource(R.string.app_language_system)) + remember(supported, systemLocale) {
            val traditionalChinese = systemLocale?.let {
                LocaleList.matchesLanguageAndScript(Locale.TRADITIONAL_CHINESE, it)
            } == true
            supported.map { languageName(it, traditionalChinese) }
        },
        selectedIndex = if (selected.isEmpty) 0 else {
            supported.indexOf(selected[0]).let { if (it == -1) -1 else it + 1 }
        },
    ) { index ->
        val next = if (index == 0) LocaleList.getEmptyLocaleList() else LocaleList(supported[index - 1])
        if (next != selected) {
            manager.applicationLocales = next
            selected = manager.applicationLocales
        }
    }
}

private fun languageName(locale: Locale, traditionalChineseSystem: Boolean): String = when (locale.toLanguageTag()) {
    "zh-Hans" -> if (traditionalChineseSystem) "殘體字" else "简体中文"
    "zh-Hant-HK" -> "繁體中文（香港）"
    "zh-Hant-TW" -> "繁體中文（台灣）"
    else -> locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}
