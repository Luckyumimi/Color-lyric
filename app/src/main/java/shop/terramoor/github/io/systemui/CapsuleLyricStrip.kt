package shop.terramoor.github.io.systemui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricRows
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LineMotion
import shop.terramoor.github.io.settings.LyricAlignment
import kotlin.math.ceil
import kotlin.math.floor

/** Draws each lyric lane once, with a fixed mask over the camera. */
internal class CapsuleLyricStrip(private val density: Float) {
    private val firstRow = Lane()
    private val secondRow = Lane()
    private val priorFirstRow = Lane()
    private val priorSecondRow = Lane()
    private var frame: LyricFrame? = null
    private var motion = LineMotion.UP
    private var lyricAlignment = LyricAlignment.CENTER
    private var fadePx = 0f
    private val cameraMask = CameraLyricMask()

    fun paintDiagnostics() = "first=${firstRow.paintDiagnostics()}, second=${secondRow.paintDiagnostics()}, cameraFadePx=$fadePx"

    fun update(frame: LyricFrame?, settings: CapsuleSettings, ink: LyricInk = LyricInk.WHITE) {
        this.frame = frame
        motion = settings.motion
        lyricAlignment = settings.lyricAlignment
        fun setup(main: Lane, second: Lane, rows: LyricRows?) {
            val size = settings.effectiveFirstRowSize(rows?.missingTranslationSingleLine == true)
            main.update(rows?.first, size * density, settings[CapsuleTuning.FIRST_ROW_OPACITY], Typeface.DEFAULT_BOLD,
                ink.first, ink.texture)
            // An upcoming lyric uses the same glyphs before and after promotion.
            val secondFace = if (rows?.nextLyricStartMs != null) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            second.update(rows?.second, settings.effectiveSecondRowSize * density,
                settings[CapsuleTuning.SECOND_ROW_OPACITY], secondFace, ink.second, ink.texture)
        }
        setup(firstRow, secondRow, frame?.rows)
        setup(priorFirstRow, priorSecondRow, frame?.priorRows)
        fadePx = if (settings.cameraFade) settings[CapsuleTuning.CAMERA_FADE_RADIUS] * density else 0f
    }

    /** Returns null for static content, 0 for the next frame, or a delay for the next change. */
    fun draw(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        camera: CapsuleCamera?, position: Long,
        opacity: Float = 1f,
        artworkArea: RectF? = null,
    ): Long? {
        val current = frame ?: return null
        if (right <= left || bottom <= top || opacity <= 0f) return null
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
        if (compactRight <= left) return null
        val viewport = CapsuleTextViewport(left, compactRight, lyricAlignment)
        val textureArea = artworkArea ?: RectF(left, top, right, bottom)
        val lanes = listOf(firstRow, secondRow, priorFirstRow, priorSecondRow)
        for (lane in lanes) lane.textureArea.set(textureArea)
        val feather = viewport.outerFeather(density)
        val phase = if (current.priorRows != null && motion != LineMotion.NONE) {
            ((position - current.displayStartMs) / 260f).coerceIn(0f, 1f)
        } else 1f
        val easing = 1f - (1f - phase) * (1f - phase)
        val travel = if (motion == LineMotion.UP) bottom - top else 0f
        val center = (top + bottom) / 2
        val marquee = if (current.isTrackInfo) {
            TrackTitleMarquee.sample(position, viewport.travel(firstRow.width) / density, feather.trailingInset / density)
        } else null
        val progress = marquee?.progress ?: current.scrollFraction(position)
        val secondIsStatic = current.isTrackInfo || current.rows.nextLyricStartMs != null
        val priorPreviewIsStatic = current.priorRows?.nextLyricStartMs != null
        val promote = phase < 1f && motion == LineMotion.UP && current.promotesPreviousLyric
        val crossfade = motion == LineMotion.FADE
        fun drawTrack(destination: Canvas, physicalOffset: Float = 0f) {
            for (lane in lanes) lane.textureOffsetX = physicalOffset
            if (promote) {
                val oldFirstTop = pairTop(center, priorFirstRow, priorSecondRow)
                val newFirstTop = pairTop(center, firstRow, secondRow)
                val newSecondTop = newFirstTop + firstRow.height
                priorFirstRow.draw(destination, viewport, feather, oldFirstTop - priorFirstRow.height * easing,
                    1f, opacity * (1f - easing))
                secondRow.draw(destination, viewport, feather, newSecondTop + secondRow.height * (1f - easing),
                    if (secondIsStatic) 0f else progress, opacity * easing)
                // Draw the promoted lyric once; position, size and opacity meet both row endpoints.
                firstRow.drawPromoted(destination, viewport, feather, priorSecondRow,
                    oldFirstTop + priorFirstRow.height, newFirstTop, progress, easing, opacity)
                return
            }
            if (phase < 1f) {
                drawPair(destination, priorFirstRow, priorSecondRow, viewport, feather,
                    center - travel * easing, 1f, priorPreviewIsStatic, opacity * if (crossfade) 1f - easing else 1f)
            }
            drawPair(destination, firstRow, secondRow, viewport, feather,
                center + travel * (1f - easing), progress, secondIsStatic, opacity * if (crossfade) easing else 1f)
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
                        drawTrack(destination, skippedWidth)
                    }
                } finally {
                    destination.restoreToCount(rightSave)
                }
            }
        }
        val scrollingWidth = if (secondIsStatic) firstRow.width else maxOf(firstRow.width, secondRow.width)
        val scrolling = viewport.travel(scrollingWidth) > 0f
        val save = canvas.save()
        try {
            if (!canvas.clipRect(left, top, right, bottom)) return null
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
        if (marquee != null) return marquee.redrawDelayMs
        return 0L.takeIf { phase < 1f || (scrolling && progress < 1f && current.endMs > current.startMs) }
    }

    private fun drawPair(
        canvas: Canvas, main: Lane, second: Lane, viewport: CapsuleTextViewport,
        feather: OuterLyricFeather, center: Float, progress: Float, staticSecond: Boolean, alpha: Float,
    ) {
        val y = pairTop(center, main, second)
        main.draw(canvas, viewport, feather, y, progress, alpha)
        second.draw(canvas, viewport, feather, y + main.height, if (staticSecond) 0f else progress, alpha)
    }

    private fun pairTop(center: Float, first: Lane, second: Lane) = center - (first.height + second.height) / 2f

    private class Lane {
        private val outerMask = OuterLyricMask()
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        private var layout: StaticLayout? = null
        private var text: String? = null
        private var texture: Bitmap? = null
        private var textureShader: BitmapShader? = null
        private val textureMatrix = Matrix()
        val textureArea = RectF()
        var textureOffsetX = 0f
        private val textureCrop = Rect()
        fun paintDiagnostics() = "#${paint.color.toUInt().toString(16)}/alpha=${paint.alpha}/present=${layout != null}"
        val height: Int get() = layout?.height ?: 0
        val width: Float get() = layout?.getLineWidth(0) ?: 0f

        fun update(value: String?, size: Float, opacity: Int, face: Typeface, rgb: Int, bitmap: Bitmap?) {
            if (texture !== bitmap) {
                texture = bitmap
                textureShader = bitmap?.takeUnless { it.isRecycled }?.let {
                    BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }
            }
            if (value != text || size != paint.textSize || face != paint.typeface) {
                text = value
                paint.textSize = size
                paint.typeface = face
                layout = value?.let {
                    val singleLine = it.replace('\n', ' ')
                    val width = (ceil(Layout.getDesiredWidth(singleLine, paint)).toInt() + 2).coerceAtLeast(1)
                    StaticLayout.Builder.obtain(singleLine, 0, singleLine.length, paint, width)
                        .setIncludePad(false).setMaxLines(1).build()
                }
            }
            paint.color = rgb
            paint.alpha = (opacity * 255 + 50) / 100
        }

        fun draw(
            canvas: Canvas, viewport: CapsuleTextViewport, feather: OuterLyricFeather,
            y: Float, progress: Float, alpha: Float,
        ) {
            drawAt(canvas, viewport, feather, y, viewport.textOffset(width, progress, feather.trailingInset),
                scale = 1f, inkAlpha = paint.alpha * alpha, fade = viewport.edgeFade(width, progress, feather))
        }

        fun drawPromoted(
            canvas: Canvas, viewport: CapsuleTextViewport, feather: OuterLyricFeather, previous: Lane,
            fromTop: Float, toTop: Float, progress: Float, phase: Float, alpha: Float,
        ) {
            val measured = layout ?: return
            if (phase == 0f) {
                previous.draw(canvas, viewport, feather, fromTop, 0f, alpha)
                return
            }
            val baseline = measured.getLineBaseline(0)
            val oldBaseline = previous.layout?.getLineBaseline(0) ?: return
            val scale = lerp(previous.paint.textSize / paint.textSize, 1f, phase)
            val y = lerp(fromTop + oldBaseline, toTop + baseline, phase) - baseline * scale
            val x = lerp(viewport.textOffset(previous.width, 0f, feather.trailingInset),
                viewport.textOffset(width, progress, feather.trailingInset), phase)
            val fromFade = viewport.edgeFade(previous.width, 0f, feather)
            val toFade = viewport.edgeFade(width, progress, feather)
            val fade = EdgeFade(lerp(fromFade.left, toFade.left, phase), lerp(fromFade.right, toFade.right, phase))
            drawAt(canvas, viewport, feather, y, x, scale,
                lerp(previous.paint.alpha.toFloat(), paint.alpha.toFloat(), phase) * alpha, fade,
                blendInk(previous.paint.color, paint.color, phase.toDouble()))
        }

        private fun drawAt(
            canvas: Canvas, viewport: CapsuleTextViewport, feather: OuterLyricFeather,
            y: Float, offset: Float, scale: Float, inkAlpha: Float, fade: EdgeFade, color: Int = paint.color,
        ) {
            val measured = layout ?: return
            if (inkAlpha <= 0f) return
            val x = viewport.left + offset - measured.getLineLeft(0) * scale
            val originalColor = paint.color
            try {
                outerMask.draw(canvas, viewport.left, y, viewport.right, y + measured.height * scale,
                    feather, fade) { target ->
                    paint.color = color
                    paint.alpha = inkAlpha.toInt()
                    val image = texture
                    val shader = textureShader
                    if (image != null && shader != null && !image.isRecycled &&
                        textureArea.width() > 0f && textureArea.height() > 0f) {
                        artworkCrop(image, textureArea.width() / textureArea.height(), textureCrop)
                        val sx = textureArea.width() / textureCrop.width()
                        val sy = textureArea.height() / textureCrop.height()
                        textureMatrix.setScale(sx / scale, sy / scale)
                        textureMatrix.postTranslate(
                            (textureArea.left - textureCrop.left * sx - textureOffsetX - x) / scale,
                            (textureArea.top - textureCrop.top * sy - y) / scale,
                        )
                        shader.setLocalMatrix(textureMatrix)
                        paint.shader = shader
                    }
                    target.translate(x, y)
                    if (scale != 1f) target.scale(scale, scale)
                    measured.draw(target)
                }
            } finally {
                paint.shader = null
                paint.color = originalColor
            }
        }
    }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction
