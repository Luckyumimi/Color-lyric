package shop.terramoor.github.io.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.menu.CouiMenuDefaults
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.basic.ArrowUpDown
import io.github.suqi8.coui.kmp.icon.basic.Check
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
import io.github.suqi8.coui.kmp.utils.rememberCouiHaptic

@Composable
internal fun TuningDropdown(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    titleAction: (@Composable () -> Unit)? = null,
    onSelectedIndexChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberCouiMenuAnchor()
    val haptic = rememberCouiHaptic()
    val colors = COUITheme.colorScheme
    val menuStyle = CouiMenuDefaults.style(dark = isSystemInDarkTheme())
    val open = {
        haptic(CouiHapticEffect.Strength)
        anchor.open { expanded = true }
    }
    val end: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            Text(
                text = items.getOrNull(selectedIndex).orEmpty(),
                color = if (enabled) colors.onSurfaceSecondary else colors.disabledOnSecondaryVariant,
                style = COUITheme.textStyles.body2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterVertically).widthIn(max = 156.dp).padding(end = 4.dp),
            )
            Box(Modifier.align(Alignment.CenterVertically)) {
                Icon(
                    imageVector = COUIIcons.Basic.ArrowUpDown,
                    contentDescription = null,
                    tint = if (enabled) colors.onSurfaceVariantActions else colors.disabledOnSecondaryVariant,
                    modifier = Modifier.size(14.dp, 24.dp),
                )
                Menu(
                    expanded = expanded && enabled,
                    onDismissRequest = { expanded = false },
                    items = items.mapIndexed { index, label ->
                        CouiMenuItem(index, label, icon = COUIIcons.Basic.Check.takeIf { index == selectedIndex })
                    },
                    onItemClick = { item ->
                        expanded = false
                        onSelectedIndexChange(item.value)
                    },
                    style = menuStyle,
                    anchor = anchor,
                )
            }
    }
    val modifier = Modifier.couiMenuAnchor(anchor)
    if (titleAction == null) {
        BasicComponent(modifier = modifier, title = title, enabled = enabled, role = Role.DropdownList,
            holdDownState = expanded && enabled, onClick = open, endActions = end)
    } else {
        BasicComponent(modifier = modifier, enabled = enabled, role = Role.DropdownList,
            holdDownState = expanded && enabled, onClick = open, endActions = end) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = if (enabled) colors.onSurface else colors.disabledOnSecondaryVariant,
                    style = COUITheme.textStyles.body1, modifier = Modifier.weight(1f, fill = false))
                titleAction()
            }
        }
    }
}
