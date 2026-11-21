package shop.terramoor.github.io.systemui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.lyrics.LyricSecondRow
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LineMotion
import shop.terramoor.github.io.settings.LyricAlignment
import kotlin.math.ceil
import kotlin.math.floor

/** Draws each lyric lane once, with a fixed mask over the camera. */
internal class CapsuleLyricStrip(private val density: Float) {
    private val title = Lane(true)
    private val translation = Lane(false)
    private val priorTitle = Lane(true)
    private val priorTranslation = Lane(false)
    private var frame: LyricFrame? = null
    private var motion = LineMotion.UP
    private var lyricAlignment = LyricAlignment.CENTER
    private var fadePx = 0f
    private val cameraMask = CameraLyricMask()

    fun update(frame: LyricFrame?, settings: CapsuleSettings) {
        this.frame = frame
        motion = settings.motion
        lyricAlignment = settings.lyricAlignment
        fun setup(
            main: Lane,
            second: Lane,
            line: LyricLine?,
            secondText: String?,
            secondRow: LyricSecondRow,
            expandSingleLine: Boolean,
        ) {
            val value = when (secondRow) {
                LyricSecondRow.TRANSLATION -> secondText
                LyricSecondRow.LYRIC -> secondText
            }?.takeIf(String::isNotBlank)
            val size = settings.effectiveTitleSize(hasSecondLine = value != null, expandSingleLine = expandSingleLine)
            main.update(line?.text, size * density, settings[CapsuleTuning.TITLE_OPACITY])
            second.update(value, settings.effectiveSecondRowSize * density, settings[CapsuleTuning.SECOND_ROW_OPACITY])
        }
        setup(title, translation, frame?.line, frame?.secondText,
            frame?.secondRow ?: LyricSecondRow.TRANSLATION,
            frame?.singleLineExpanded ?: false)
        setup(priorTitle, priorTranslation, frame?.priorLine, frame?.priorSecondText,
            frame?.priorSecondRow ?: frame?.secondRow ?: LyricSecondRow.TRANSLATION,
            frame?.priorSingleLineExpanded ?: false)
        fadePx = if (settings.cameraFade) settings[CapsuleTuning.CAMERA_FADE_RADIUS] * density else 0f
    }

    fun draw(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        camera: CapsuleCamera?, position: Long,
        opacity: Float = 1f,
    ): Boolean {
        val current = frame ?: return false
        if (right <= left || bottom <= top || opacity <= 0f) return false
        // The same logical strip feeds both sides. Snap the physical seam to pixel boundaries
        // so complementary slices cannot lose a column to fractional clip rounding.
        val seam = camera?.let {
            val start = floor(it.x - it.radiusX)
            val end = ceil(it.x + it.radiusX)
            it.copy(x = (start + end) / 2f, radiusX = (end - start) / 2f)
        }
        val lensLeft = seam?.let { (it.x - it.radiusX).coerceIn(left, right) } ?: left
        val lensRight = seam?.let { (it.x + it.radiusX).coerceIn(left, right) } ?: left
        val skippedWidth = lensRight - lensLeft
        val compactRight = right - skippedWidth
        if (compactRight <= left) return false
        val viewport = CapsuleTextViewport(left, compactRight, lyricAlignment)
        val feather = viewport.outerFeather(density)
        val phase = if (current.priorLine != null && motion != LineMotion.NONE) {
            ((position - current.displayStartMs) / 260f).coerceIn(0f, 1f)
        } else 1f
        val easing = 1f - (1f - phase) * (1f - phase)
        val direction = when (motion) {
            LineMotion.UP -> 1f
            LineMotion.DOWN -> -1f
            else -> 0f
        }
        val travel = (bottom - top) * direction
        val center = (top + bottom) / 2
        val progress = current.scrollFraction(position)
        val crossfade = motion == LineMotion.FADE
        fun drawTrack(destination: Canvas) {
            if (phase < 1f) {
                drawPair(destination, priorTitle, priorTranslation, viewport, feather,
                    center - travel * easing, 1f, opacity * if (crossfade) 1f - easing else 1f)
            }
            drawPair(destination, title, translation, viewport, feather,
                center + travel * (1f - easing), progress, opacity * if (crossfade) easing else 1f)
        }
        fun drawLyrics(destination: Canvas) {
            if (skippedWidth == 0f) {
                drawTrack(destination)
            } else {
                // Both clips show adjacent slices of the same layout, even when the seam crosses a glyph.
                val leftSave = destination.save()
                try {
                    if (destination.clipRect(left, top, lensLeft, bottom)) drawTrack(destination)
                } finally {
                    destination.restoreToCount(leftSave)
                }
                val rightSave = destination.save()
                try {
                    if (destination.clipRect(lensRight, top, right, bottom)) {
                        destination.translate(skippedWidth, 0f)
                        drawTrack(destination)
                    }
                } finally {
                    destination.restoreToCount(rightSave)
                }
            }
        }
        val scrolling = viewport.travel(maxOf(title.width, translation.width)) > 0f
        val save = canvas.save()
        try {
            if (!canvas.clipRect(left, top, right, bottom)) return false
            if (seam != null && seam.x + seam.radiusX + fadePx > left &&
                seam.x - seam.radiusX - fadePx < right
            ) {
                val blur = minOf(fadePx * 0.3f, 1.8f * density)
                cameraMask.draw(canvas, left, top, right, bottom, seam, fadePx, blur, ::drawLyrics)
            } else {
                drawLyrics(canvas)
            }
        } finally {
            canvas.restoreToCount(save)
        }
        return phase < 1f || (scrolling && progress < 1f && current.endMs > current.line.startMs)
    }

    private fun drawPair(
        canvas: Canvas, main: Lane, second: Lane, viewport: CapsuleTextViewport,
        feather: OuterLyricFeather, center: Float, progress: Float, alpha: Float,
    ) {
        val y = center - (main.height + second.height) / 2f
        main.draw(canvas, viewport, feather, y, progress, alpha)
        second.draw(canvas, viewport, feather, y + main.height, progress, alpha)
    }

    private class Lane(bold: Boolean) {
        private val outerMask = OuterLyricMask()
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        private var layout: StaticLayout? = null
        private var text: String? = null
        val height: Int get() = layout?.height ?: 0
        val width: Float get() = layout?.getLineWidth(0) ?: 0f

        fun update(value: String?, size: Float, opacity: Int) {
            if (value != text || size != paint.textSize) {
                text = value
                paint.textSize = size
                layout = value?.let {
                    val singleLine = it.replace('\n', ' ')
                    val width = (ceil(Layout.getDesiredWidth(singleLine, paint)).toInt() + 2).coerceAtLeast(1)
                    StaticLayout.Builder.obtain(singleLine, 0, singleLine.length, paint, width)
                        .setIncludePad(false).setMaxLines(1).build()
                }
            }
            paint.color = Color.argb((opacity * 255 + 50) / 100, 255, 255, 255)
        }

        fun draw(
            canvas: Canvas, viewport: CapsuleTextViewport, feather: OuterLyricFeather,
            y: Float, progress: Float, alpha: Float,
        ) {
            val measured = layout ?: return
            if (alpha <= 0f) return
            val x = viewport.left + viewport.textOffset(width, progress, feather.trailingInset) - measured.getLineLeft(0)
            val originalAlpha = paint.alpha
            try {
                val fade = viewport.edgeFade(width, progress, feather)
                outerMask.draw(canvas, viewport.left, y, viewport.right, y + measured.height,
                    feather, fade) { target ->
                    paint.alpha = (originalAlpha * alpha).toInt()
                    target.translate(x, y)
                    measured.draw(target)
                }
            } finally {
                paint.alpha = originalAlpha
            }
        }
    }
}
