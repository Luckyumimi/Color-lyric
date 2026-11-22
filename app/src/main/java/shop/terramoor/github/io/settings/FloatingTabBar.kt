package shop.terramoor.github.io.settings

import android.view.HapticFeedbackConstants
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.itos.coui.icons.CouiIcons
import com.itos.coui.navigation.CouiNavigationItem
import com.itos.coui.navigation.NavigationBar
import shop.terramoor.github.io.R

internal enum class SettingsTab(@StringRes val title: Int) {
    HOME(R.string.tab_home),
    TUNING(R.string.tab_tuning),
    ABOUT(R.string.tab_about),
}

@Composable
internal fun FloatingTabBar(selected: SettingsTab, onSelect: (SettingsTab) -> Boolean) {
    val view = LocalView.current
    val tabs = SettingsTab.entries
    var selectionReset by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        // COUI Kit reserves 68dp at each end for optional attachments. Keep the
        // capsule itself at LuckyTool's compact item width, even without attachments.
        val itemWidth = 52.dp
        val outerPadding = 32.dp
        val attachmentSpace = 136.dp
        val menuWidth = (24.dp + itemWidth * tabs.size + 8.dp * (tabs.size - 1))
            .coerceAtMost((maxWidth - outerPadding).coerceAtLeast(0.dp))
        // COUI updates its own selection during a gesture, even if the pager rejects it.
        key(selectionReset) {
            NavigationBar(
                modifier = Modifier.requiredWidth(menuWidth + attachmentSpace + outerPadding),
                items = tabs.map { CouiNavigationItem(stringResource(it.title), tabIcon(it)) },
                selectedIndex = selected.ordinal,
                dark = isSystemInDarkTheme(),
                onSelection = { index ->
                    val tab = tabs[index]
                    if (tab != selected) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        if (!onSelect(tab)) selectionReset++
                    }
                },
            )
        }
    }
}

@Composable
private fun tabIcon(tab: SettingsTab): ImageVector = when (tab) {
    SettingsTab.HOME -> ImageVector.vectorResource(R.drawable.ic_tab_home)
    SettingsTab.TUNING -> CouiIcons.Outlined.SliderCustom
    SettingsTab.ABOUT -> ImageVector.vectorResource(R.drawable.ic_tab_about)
}
