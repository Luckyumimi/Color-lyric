package shop.terramoor.github.io.settings

import android.os.SystemClock
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog as CouiDialog
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
import io.github.suqi8.coui.kmp.utils.rememberCouiHaptic
import kotlinx.coroutines.flow.collectLatest
import shop.terramoor.github.io.R

@Composable
internal fun TranslationHelpButton(onClick: () -> Unit) {
    val haptic = rememberCouiHaptic()
    TextButton(
        text = stringResource(R.string.translation_help),
        onClick = { haptic(CouiHapticEffect.Strength); onClick() },
        minWidth = 0.dp,
        minHeight = 44.dp,
        insideMargin = PaddingValues(horizontal = 6.dp),
        textStyle = COUITheme.textStyles.body2,
        colors = ButtonDefaults.textButtonColorsBorderless(textColor = unavailableColor()),
    )
}

@Composable
internal fun TranslationHelpDialog(onDismiss: () -> Unit, onHide: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptic by rememberUpdatedState(rememberCouiHaptic())
    val hide by rememberUpdatedState(onHide)
    var progress by remember { mutableFloatStateOf(0f) }
    val holdLabel = stringResource(R.string.translation_help_hold_to_hide)
    LaunchedEffect(interaction) {
        interaction.interactions.collectLatest { event ->
            if (event !is PressInteraction.Press) return@collectLatest
            val start = SystemClock.uptimeMillis()
            var ticks = 0
            haptic(CouiHapticEffect.Strength)
            try {
                while (progress < 1f) {
                    withFrameNanos {
                        progress = ((SystemClock.uptimeMillis() - start) / HOLD_MS.toFloat()).coerceIn(0f, 1f)
                    }
                    val step = (progress * 4).toInt()
                    if (step > ticks) {
                        ticks = step
                        haptic(CouiHapticEffect.Strength)
                    }
                }
                hide()
            } finally {
                progress = 0f
            }
        }
    }
    CouiDialog(
        onDismissRequest = onDismiss,
        confirmAction = CouiDialogAction(stringResource(R.string.translation_help_cancel), onClick = onDismiss),
        title = stringResource(R.string.translation_help),
        message = stringResource(R.string.translation_help_message),
        placement = CouiDialogPlacement.Center,
        dark = isSystemInDarkTheme(),
    ) {
        val edge = 16.dp
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(horizontal = edge).padding(bottom = 6.dp)
                .alpha(if (progress > 0f) 1f else 0f),
            progress = progress,
        )
        TextButton(
            text = stringResource(R.string.translation_help_hide),
            onClick = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = edge).semantics {
                onLongClick(label = holdLabel) {
                    haptic(CouiHapticEffect.Strength)
                    hide()
                    true
                }
            },
            interactionSource = interaction,
            colors = ButtonDefaults.textButtonColors(textColor = unavailableColor()),
        )
    }
}

private const val HOLD_MS = 1_200L
