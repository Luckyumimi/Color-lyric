package shop.terramoor.github.io.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.DividerDefaults
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.SliderDefaults
import io.github.suqi8.coui.kmp.preference.SliderPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
import io.github.suqi8.coui.kmp.utils.rememberCouiHaptic
import shop.terramoor.github.io.R
import shop.terramoor.github.io.settings.TerraMoorApp.Connection
import kotlin.math.roundToInt

@Composable
internal fun TuningPage(
    modifier: Modifier,
    bottomClearance: Dp,
    background: Color,
    state: TerraMoorApp.State,
    app: TerraMoorApp,
    previewVisible: Boolean,
) {
    val haptic = rememberCouiHaptic()
    val settings = state.settings
    val ready = state.connection == Connection.READY

    var previewLanguage by rememberSaveable { mutableStateOf(PreviewLanguage.JAPANESE) }
    val sidePadding = 12.dp
    val divider = Modifier.padding(horizontal = DividerDefaults.CardInset)
    val transitionHeight = 22.dp
    Column(modifier) {
        Box(Modifier.fillMaxWidth().padding(start = sidePadding, top = 8.dp, end = sidePadding, bottom = 4.dp)) {
            SettingsGroup(stringResource(R.string.preview_title)) {
                TuningDropdown(
                    title = stringResource(R.string.preview_sample),
                    items = PreviewLanguage.entries.map { stringResource(it.label) },
                    selectedIndex = PreviewLanguage.entries.indexOf(previewLanguage),
                    onSelectedIndexChange = { previewLanguage = PreviewLanguage.entries[it] },
                )
                HorizontalDivider(divider)
                AndroidView(
                    factory = ::CapsulePreviewView,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    update = {
                        it.show(settings, previewLanguage)
                        it.setPreviewVisible(previewVisible)
                    },
                )
            }
        }
        SettingsList(
            Modifier.weight(1f).fillMaxWidth().drawWithContent {
                drawContent()
                val fadeHeight = minOf(transitionHeight.toPx(), size.height)
                if (fadeHeight > 0f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to background,
                            0.25f to background,
                            1f to background.copy(alpha = 0f),
                            endY = fadeHeight,
                        ),
                        size = Size(size.width, fadeHeight),
                    )
                }
            },
            bottomClearance,
            horizontalPadding = sidePadding,
            topPadding = transitionHeight + 2.dp,
        ) {
            val slider: @Composable (CapsuleTuning, Int, Int, Boolean) -> Unit = { option, title, format, enabled ->
                val range = if (option == CapsuleTuning.SECOND_ROW_SIZE) settings.secondRowSizeRange else option.range
                TuningSlider(option, title, format, settings[option].coerceIn(range), range, enabled,
                    onChange = { next ->
                        if (next != settings[option].coerceIn(range)) {
                            app.previewTuning(option, next)
                            haptic(CouiHapticEffect.Strength)
                        }
                    },
                    onFinish = app::flushTuning,
                    canReset = enabled,
                    showReset = settings[option] != option.default,
                    onReset = {
                        haptic(CouiHapticEffect.Strength)
                        app.reset(option)
                    },
                )
            }
            SettingsGroup(stringResource(R.string.section_text_tuning)) {
                TactileSwitchPreference(
                    checked = settings.doubleLine,
                    onCheckedChange = { app.set(CapsuleSettings.DOUBLE_LINE, it) },
                    title = stringResource(R.string.double_line_lyrics),
                    enabled = ready,
                )
                HorizontalDivider(divider)
                TuningDropdown(
                    title = stringResource(R.string.second_row_content),
                    items = listOf(stringResource(R.string.second_row_translation), stringResource(R.string.second_row_lyric)),
                    selectedIndex = settings.secondRowContent.ordinal,
                    enabled = ready && settings.doubleLine,
                    onSelectedIndexChange = { app.setSecondRowContent(SecondRowContent.entries[it]) },
                )
                HorizontalDivider(divider)
                slider(CapsuleTuning.FIRST_ROW_SIZE, R.string.tune_first_row_size, R.string.value_dp, ready)
                HorizontalDivider(divider)
                slider(CapsuleTuning.SECOND_ROW_SIZE, R.string.tune_second_row_size, R.string.value_dp, ready && settings.doubleLine)
                HorizontalDivider(divider)
                slider(CapsuleTuning.FIRST_ROW_OPACITY, R.string.tune_first_row_opacity, R.string.value_percent, ready)
                HorizontalDivider(divider)
                slider(CapsuleTuning.SECOND_ROW_OPACITY, R.string.tune_second_row_opacity, R.string.value_percent, ready && settings.doubleLine)
                if (settings.doubleLine && settings.secondRowContent == SecondRowContent.TRANSLATION) {
                    HorizontalDivider(divider)
                    TuningDropdown(
                        title = stringResource(R.string.missing_translation_mode),
                        items = listOf(
                            stringResource(R.string.missing_translation_single),
                            stringResource(R.string.missing_translation_double_lyrics),
                        ),
                        selectedIndex = settings.missingTranslationMode.ordinal,
                        enabled = ready,
                        onSelectedIndexChange = { app.setMissingTranslationMode(MissingTranslationMode.entries[it]) },
                    )
                    if (settings.missingTranslationMode == MissingTranslationMode.SINGLE_LINE) {
                        HorizontalDivider(divider)
                        TactileSwitchPreference(
                            checked = settings.fallbackSingleLineExpand,
                            onCheckedChange = { app.set(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND, it) },
                            title = stringResource(R.string.fallback_single_line_expand),
                            enabled = ready,
                        )
                    }
                }
                HorizontalDivider(divider)
                TuningDropdown(
                    title = stringResource(R.string.lyric_alignment),
                    items = listOf(stringResource(R.string.lyric_align_center), stringResource(R.string.lyric_align_left)),
                    selectedIndex = settings.lyricAlignment.ordinal,
                    enabled = ready,
                    onSelectedIndexChange = { app.setLyricAlignment(LyricAlignment.entries[it]) },
                )
                HorizontalDivider(divider)
                slider(CapsuleTuning.ISLAND_WIDTH, R.string.tune_island_width, R.string.value_dp, ready)
            }
            SettingsGroup(stringResource(R.string.section_capsule_elements)) {
                TactileSwitchPreference(
                    checked = settings.showCover,
                    onCheckedChange = { app.set(CapsuleSettings.SHOW_COVER, it) },
                    title = stringResource(R.string.show_cover),
                    enabled = ready,
                )
                HorizontalDivider(divider)
                TactileSwitchPreference(
                    checked = settings.showWaveform,
                    onCheckedChange = { app.set(CapsuleSettings.SHOW_WAVEFORM, it) },
                    title = stringResource(R.string.show_waveform),
                    enabled = ready,
                )
            }
            SettingsGroup(stringResource(R.string.section_camera)) {
                TuningDropdown(
                    title = stringResource(R.string.camera_fade),
                    items = listOf(stringResource(R.string.camera_fade_off), stringResource(R.string.camera_fade_on)),
                    selectedIndex = if (settings.cameraFade) 1 else 0,
                    enabled = ready,
                    onSelectedIndexChange = { app.set(CapsuleSettings.CAMERA_FADE, it == 1) },
                )
                HorizontalDivider(divider)
                slider(CapsuleTuning.CAMERA_FADE_RADIUS, R.string.tune_camera_radius, R.string.value_dp, ready && settings.cameraFade)
            }
            SettingsGroup(stringResource(R.string.section_artwork_tuning)) {
                TactileSwitchPreference(
                    checked = settings.artwork,
                    onCheckedChange = { app.set(CapsuleSettings.ARTWORK, it) },
                    title = stringResource(R.string.artwork_background),
                    summary = stringResource(R.string.artwork_background_summary),
                    enabled = ready,
                )
                HorizontalDivider(divider)
                slider(CapsuleTuning.BLUR, R.string.tune_blur, R.string.value_number, ready && settings.artwork)
                HorizontalDivider(divider)
                HorizontalDivider(divider)
                slider(CapsuleTuning.ARTWORK_DIM, R.string.tune_artwork_dim, R.string.value_percent, ready && settings.artwork)
            }
            SettingsGroup(stringResource(R.string.section_lyric_behavior)) {
                val switchOptions = listOf(
                    LyricSwitchMode.AFTER_END to R.string.lyric_switch_after_end,
                    LyricSwitchMode.AT_NEXT_START to R.string.lyric_switch_next_start,
                )
                TuningDropdown(
                    title = stringResource(R.string.single_line_switch_mode),
                    items = switchOptions.map { stringResource(it.second) },
                    selectedIndex = switchOptions.indexOfFirst { it.first == settings.singleLineSwitchMode },
                    enabled = ready,
                    onSelectedIndexChange = { app.setSingleLineSwitchMode(switchOptions[it].first) },
                )
                HorizontalDivider(divider)
                TuningDropdown(
                    title = stringResource(R.string.double_line_switch_mode),
                    items = switchOptions.map { stringResource(it.second) },
                    selectedIndex = switchOptions.indexOfFirst { it.first == settings.doubleLineSwitchMode },
                    enabled = ready && settings.doubleLine,
                    onSelectedIndexChange = { app.setDoubleLineSwitchMode(switchOptions[it].first) },
                )
                HorizontalDivider(divider)
                val motionOptions = listOf(
                    LineMotion.NONE to R.string.motion_none,
                    LineMotion.UP to R.string.motion_up,
                    LineMotion.FADE to R.string.motion_fade,
                )
                TuningDropdown(
                    title = stringResource(R.string.section_motion),
                    items = motionOptions.map { stringResource(it.second) },
                    selectedIndex = motionOptions.indexOfFirst { it.first == settings.motion },
                    enabled = ready,
                    onSelectedIndexChange = { app.setMotion(motionOptions[it].first) },
                )
                HorizontalDivider(divider)
                TuningDropdown(
                    title = stringResource(R.string.section_no_lyrics),
                    items = listOf(stringResource(R.string.no_lyrics_empty), stringResource(R.string.no_lyrics_track)),
                    selectedIndex = NoLyricsMode.entries.indexOf(settings.noLyricsMode),
                    enabled = ready,
                    onSelectedIndexChange = { app.setNoLyrics(NoLyricsMode.entries[it]) },
                )
            }
            SettingsGroup(stringResource(R.string.section_reset)) {
                BasicComponent(
                    title = stringResource(R.string.reset_tuning),
                    enabled = ready,
                    onClick = {
                        app.resetTuning()
                    },
                )
            }
        }
    }
}

@Composable
private fun TuningSlider(
    option: CapsuleTuning,
    @StringRes title: Int,
    @StringRes format: Int,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onFinish: () -> Unit,
    canReset: Boolean,
    showReset: Boolean,
    onReset: () -> Unit,
) {
    SliderPreference(
        title = stringResource(title),
        valueText = stringResource(format, value),
        endActions = if (showReset) {
            {
                val colors = COUITheme.colorScheme
                IconButton(
                    onClick = onReset,
                    enabled = canReset,
                    backgroundColor = Color.Transparent,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_restore_rounded),
                        contentDescription = stringResource(R.string.reset_slider, stringResource(title)),
                        tint = if (canReset) colors.onSurfaceVariantActions else colors.disabledOnSecondaryVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else null,
        value = value.toFloat(),
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first) / option.step - 1,
        enabled = enabled,
        onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
        onValueChangeFinished = onFinish,
        hapticEffect = SliderDefaults.SliderHapticEffect.None,
    )
}
