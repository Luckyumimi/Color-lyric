package shop.terramoor.github.io.settings

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.itos.coui.input.CouiHapticFeedback
import com.itos.coui.input.performCouiHapticFeedback
import com.itos.coui.switch.Switch
import io.github.suqi8.coui.kmp.basic.BasicComponent

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TactileSwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    enabled: Boolean,
    summary: String? = null,
    onLongClick: (() -> Unit)? = null,
    startAction: (@Composable () -> Unit)? = null,
) {
    val view = LocalView.current
    val change: (Boolean) -> Unit = { target ->
        if (enabled && target != checked) {
            if (!view.performCouiHapticFeedback(CouiHapticFeedback.Switch)) {
                view.performHapticFeedback(
                    if (target) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF,
                )
            }
            onCheckedChange(target)
        }
    }
    val gesture = if (onLongClick == null) Modifier else Modifier.combinedClickable(
        enabled = enabled,
        role = Role.Switch,
        onClick = { change(!checked) },
        onLongClick = {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongClick()
        },
    )
    BasicComponent(
        modifier = gesture.semantics { toggleableState = ToggleableState(checked) },
        title = title,
        summary = summary,
        startAction = startAction,
        role = Role.Switch,
        enabled = enabled,
        onClick = if (onLongClick == null) ({ change(!checked) }) else null,
        endActions = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                onDragCheckedChange = change,
                enabled = enabled,
                hapticFeedbackEnabled = false,
            )
        },
    )
}
