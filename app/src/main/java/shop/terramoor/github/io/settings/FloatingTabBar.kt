package shop.terramoor.github.io.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.itos.coui.navigation.CouiNavigationItem
import com.itos.coui.navigation.NavigationBar
import shop.terramoor.github.io.R

internal enum class SettingsTab(@StringRes val title: Int) {
    HOME(R.string.tab_home),
    TUNING(R.string.tab_tuning),
    SETTINGS(R.string.lyrics_tab_settings),
    ABOUT(R.string.tab_about),
}

@Composable
internal fun FloatingTabBar(
    selected: SettingsTab,
    onSelect: (SettingsTab) -> Boolean,
) {
    val view = LocalView.current
    val tabs = SettingsTab.entries
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        // COUI subtracts its outer padding and both attachment slots even when the slots are hidden.
        val outerPadding = 32.dp
        val attachmentSpace = 136.dp
        val menuWidth = (24.dp + 64.dp * tabs.size + 8.dp * (tabs.size - 1))
            .coerceAtMost((maxWidth - outerPadding).coerceAtLeast(0.dp))
        NavigationBar(
            modifier = Modifier.requiredWidth(menuWidth + attachmentSpace + outerPadding),
            items = tabs.map { CouiNavigationItem(stringResource(it.title), tabIcon(it)) },
            selectedIndex = selected.ordinal,
            dark = isSystemInDarkTheme(),
            onSelection = { index ->
                val tab = tabs[index]
                if (tab != selected) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    onSelect(tab)
                }
            },
        )
    }
}

/** Mirrors COUI Kit alpha11's 16dp top, 12dp menu inset and 8dp item gaps. */
internal fun floatingSelectedTabHit(
    x: Float, y: Float, barWidth: Float, requestedMenuWidth: Float,
    leftInset: Float, rightInset: Float, selected: Int, count: Int, rtl: Boolean, dp: Float,
): Boolean {
    if (count <= 0 || selected !in 0 until count) return false
    val menuWidth = (requestedMenuWidth - leftInset - rightInset).coerceAtLeast(0f)
    val itemWidth = (menuWidth - (24 + 8 * (count - 1)) * dp) / count
    if (itemWidth <= 0f) return false
    val physicalIndex = if (rtl) count - selected - 1 else selected
    val menuLeft = (barWidth - menuWidth) / 2f + (leftInset - rightInset) / 2f
    val itemLeft = menuLeft + 12f * dp + physicalIndex * (itemWidth + 8f * dp)
    return x >= itemLeft && x <= itemLeft + itemWidth && y >= 20f * dp && y <= 68f * dp
}

@Composable
private fun tabIcon(tab: SettingsTab): ImageVector = when (tab) {
    SettingsTab.HOME -> ImageVector.vectorResource(R.drawable.ic_tab_home)
    SettingsTab.TUNING -> com.itos.coui.icons.CouiIcons.Outlined.SliderCustom
    SettingsTab.SETTINGS -> ImageVector.vectorResource(R.drawable.ic_lyrics_provider)
    SettingsTab.ABOUT -> ImageVector.vectorResource(R.drawable.ic_tab_about)
}
