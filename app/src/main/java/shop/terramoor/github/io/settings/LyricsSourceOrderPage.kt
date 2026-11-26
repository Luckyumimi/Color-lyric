package shop.terramoor.github.io.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.itos.coui.checkbox.Checkbox as CouiCheckbox
import com.itos.coui.checkbox.CouiCheckboxDefaults
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.theme.CouiTheme
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.theme.COUITheme
import shop.terramoor.github.io.R
import kotlin.math.abs

internal fun moveLyricSource(order: List<String>, id: String, destination: Int): List<String> {
    val from = order.indexOf(id)
    if (from < 0 || destination !in order.indices || from == destination) return order
    return order.toMutableList().apply { add(destination, removeAt(from)) }
}

/** Assign a new order to the enabled slots, leaving disabled rows where they are. */
internal fun reorderEnabledLyricSources(rows: List<String>, enabledOrder: List<String>): List<String> {
    val enabled = enabledOrder.toSet()
    val replacements = enabledOrder.iterator()
    return rows.map { if (it in enabled) replacements.next() else it }
}

/** Disabled rows are grouped at the bottom when the page opens. */
@Composable
internal fun LyricsSourceOrderPage(sources: List<String>, all: List<String>, onChange: (List<String>) -> Unit) {
    var draft by remember { mutableStateOf(sources) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var settlingSource by remember { mutableStateOf<String?>(null) }
    var ordered by remember { mutableStateOf(sources + all.filterNot(sources::contains)) }
    var dragStartOrder by remember { mutableStateOf(emptyList<String>()) }
    var dragStartRows by remember { mutableStateOf(emptyList<String>()) }
    var offset by remember { mutableFloatStateOf(0f) }
    val cardColor = CouiPageDefaults.themeColors().card
    val density = LocalDensity.current
    val view = LocalView.current
    val rowHeight = 64.dp
    val rowHeightPx = with(density) { rowHeight.toPx() }
    LaunchedEffect(sources) { if (dragging == null) draft = sources }
    // Keep the layout origin stable during both dragging and the release animation.
    val anchors = remember { ordered }
    fun moveEnabled(id: String, destination: Int) {
        draft = moveLyricSource(draft, id, destination)
        ordered = reorderEnabledLyricSources(ordered, draft)
        onChange(draft)
    }
    val moveUp = stringResource(R.string.lyrics_hub_move_up)
    val moveDown = stringResource(R.string.lyrics_hub_move_down)
    val handle = stringResource(R.string.lyrics_hub_drag_handle)

    SettingSummary(stringResource(R.string.lyrics_hub_reorder_hint))
    SettingsGroup(null) {
        Box(Modifier.fillMaxWidth().height(rowHeight * all.size)) {
            anchors.forEachIndexed { anchor, id ->
                key(id) {
                    val index = draft.indexOf(id)
                    val enabled = index >= 0
                    val target = (ordered.indexOf(id) - anchor) * rowHeightPx
                    val position = remember { Animatable(target) }
                    var releaseOffset by remember { mutableStateOf<Float?>(null) }
                    val isDragging = dragging == id
                    LaunchedEffect(isDragging, target) {
                        if (!isDragging) {
                            releaseOffset?.let {
                                position.snapTo(it)
                                releaseOffset = null
                            }
                            position.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
                            if (settlingSource == id) settlingSource = null
                        }
                    }
                    fun setEnabled(checked: Boolean) {
                        val next = if (checked) ordered.filter { it == id || it in draft } else draft - id
                        draft = next
                        onChange(next)
                    }
                    val dragModifier = if (enabled) {
                        Modifier.pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragStartOrder = draft
                                    dragStartRows = ordered
                                    offset = releaseOffset ?: position.value
                                    releaseOffset = null
                                    dragging = id
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                },
                                onDragEnd = {
                                    releaseOffset = offset
                                    settlingSource = id
                                    dragging = null
                                    onChange(draft)
                                },
                                onDragCancel = {
                                    releaseOffset = offset
                                    settlingSource = id
                                    dragging = null
                                    draft = dragStartOrder
                                    ordered = dragStartRows
                                },
                            ) { change, delta ->
                                change.consume()
                                val enabledSlots = dragStartOrder.map(dragStartRows::indexOf)
                                offset = (offset + delta.y).coerceIn(
                                    (enabledSlots.first() - anchor) * rowHeightPx,
                                    (enabledSlots.last() - anchor) * rowHeightPx,
                                )
                                val pointerSlot = anchor + offset / rowHeightPx
                                val destination = enabledSlots.indices.minBy { abs(enabledSlots[it] - pointerSlot) }
                                if (destination != draft.indexOf(id)) {
                                    draft = moveLyricSource(dragStartOrder, id, destination)
                                    ordered = reorderEnabledLyricSources(dragStartRows, draft)
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                        }.semantics {
                            contentDescription = handle
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction(moveUp) {
                                    moveEnabled(id, draft.indexOf(id) - 1)
                                    true
                                })
                                if (index in 0 until draft.lastIndex) add(CustomAccessibilityAction(moveDown) {
                                    moveEnabled(id, draft.indexOf(id) + 1)
                                    true
                                })
                            }
                        }
                    } else Modifier
                    Box(Modifier.offset(y = rowHeight * anchor).fillMaxWidth().height(rowHeight)
                        .zIndex(if (isDragging || settlingSource == id) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) offset else releaseOffset ?: position.value
                        }.then(if (isDragging || settlingSource == id) Modifier.background(cardColor) else Modifier)) {
                        BasicComponent(
                            modifier = Modifier.fillMaxWidth().height(rowHeight),
                            title = sourceName(id),
                            summary = if (enabled) stringResource(R.string.lyrics_hub_priority, index + 1)
                                else stringResource(R.string.lyrics_hub_disabled),
                            startAction = {
                                Box(Modifier.size(48.dp).toggleable(
                                    value = enabled, role = Role.Checkbox,
                                    onValueChange = {
                                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                                        setEnabled(it)
                                    },
                                ), contentAlignment = Alignment.Center) {
                                    CouiCheckbox(checked = enabled,
                                        colors = CouiCheckboxDefaults.colors(CouiTheme.colorSchemeFor(isSystemInDarkTheme()).checkboxAccent,
                                            isSystemInDarkTheme()))
                                }
                            },
                            endActions = {
                                Box(Modifier.size(48.dp).then(dragModifier), contentAlignment = Alignment.Center) {
                                    if (enabled) Icon(
                                        painter = painterResource(R.drawable.ic_lyrics_reorder),
                                        contentDescription = null, modifier = Modifier.size(22.dp),
                                        tint = COUITheme.colorScheme.onSurfaceVariantActions,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            repeat(all.lastIndex) { index ->
                Box(Modifier.offset(y = rowHeight * (index + 1)).fillMaxWidth().zIndex(2f)) { HubDivider() }
            }
        }
    }
    SettingSummary(stringResource(R.string.lyrics_hub_apple_network_notice))
}
