package shop.terramoor.github.io.systemui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import shop.terramoor.github.io.lyrics.LyricCountdown
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricRows
import shop.terramoor.github.io.lyrics.LyricWord
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LineMotion
import shop.terramoor.github.io.settings.LyricAlignment
import kotlin.math.ceil
import kotlin.math.floor

private const val CAMERA_FADE_RADIUS_DP = 2f
private const val WORD_SWEEP_FEATHER_DP = 3f
private const val LONG_WORD_GLOW_RADIUS_DP = 1.5f
private const val LONG_WORD_GLOW_ALPHA = 0.55f
private const val UNPLAYED_ALPHA = 0.55f
internal const val LYRIC_MOTION_DURATION_MS = 260L

/** Draws each lyric lane once, with a fixed mask over the camera. */
internal class CapsuleLyricStrip(private val density: Float) {
    private val firstRow = Lane(density, rowUnsungOffsetPx = density)
    private val secondRow = Lane(density)
    private val priorFirstRow = Lane(density, rowUnsungOffsetPx = density)
    private val priorSecondRow = Lane(density)
    private var frame: LyricFrame? = null
    private var motion = LineMotion.UP
    private var lyricAlignment = LyricAlignment.CENTER
    private var fadePx = 0f
    private val cameraMask = CameraLyricMask()
    private val introDots = CapsuleIntroDots()
    private var introColor = 0
    private var introOpacity = 1f
    private var avoidCameraWords = false
    private val lanes = listOf(firstRow, secondRow, priorFirstRow, priorSecondRow)

    fun paintDiagnostics() = "first=${firstRow.paintDiagnostics()}, second=${secondRow.paintDiagnostics()}, cameraFadePx=$fadePx"

    fun update(frame: LyricFrame?, settings: CapsuleSettings, ink: LyricInk = LyricInk.WHITE) {
        this.frame = frame
        motion = settings.motion
        lyricAlignment = settings.lyricAlignment
        avoidCameraWords = settings.cameraWordAvoidance && frame?.isTrackInfo != true
        introColor = ink.first
        introOpacity = settings[CapsuleTuning.FIRST_ROW_OPACITY] / 100f
        fun setup(main: Lane, second: Lane, rows: LyricRows?, avoidWords: Boolean) {
            val size = settings.effectiveFirstRowSize(rows?.missingTranslationSingleLine == true)
            main.update(rows?.first, size * density, settings[CapsuleTuning.FIRST_ROW_OPACITY], Typeface.DEFAULT_BOLD,
                ink.first, ink.texture, rows?.firstWords.orEmpty(), avoidWords, ink.gradient)
            // An upcoming lyric uses the same glyphs before and after promotion.
            val secondFace = if (rows?.nextLyricStartMs != null) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            second.update(rows?.second, settings.effectiveSecondRowSize * density,
                settings[CapsuleTuning.SECOND_ROW_OPACITY], secondFace, ink.second, ink.texture,
                rows?.secondWords.orEmpty(), avoidWords, ink.gradient)
        }
        setup(firstRow, secondRow, frame?.rows, avoidCameraWords && frame?.countdown == null)
        setup(priorFirstRow, priorSecondRow, frame?.priorRows,
            avoidCameraWords && frame?.priorCountdown == null && frame?.priorTrackProgress == null)
        fadePx = if (settings.cameraFade) CAMERA_FADE_RADIUS_DP * density else 0f
    }

    /** Returns null for static content, 0 for the next frame, or a delay for the next change. */
    fun draw(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        camera: CapsuleCamera?, position: Long,
        opacity: Float = 1f,
        artworkArea: RectF? = null,
        trackInfoMarquee: TrackInfoDisplayMarquee? = null,
        trackInfoSettled: Boolean = true,
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
        val textureArea = artworkArea ?: RectF(left, top, right, bottom)
        fun drawCountdown(countdown: LyricCountdown, main: Lane, second: Lane, time: Long, offsetY: Float, alpha: Float): Long? {
            val fallbackSplit = (left + right) / 2f
            val infoRight = if (seam == null) fallbackSplit else lensLeft
            val dotsLeft = if (seam == null) (if (main.width > 0f) fallbackSplit else left) else lensRight
            val elapsed = (time - countdown.startMs).coerceAtLeast(0L)
            val duration = countdown.endMs - countdown.startMs
            val save = canvas.save()
            try {
                if (!canvas.clipRect(left, top, right, bottom)) return null
                val infoDelay = if (countdown.isIntro) drawIntroTrack(canvas, left, top, infoRight, bottom,
                    main, second, elapsed, duration, alpha, textureArea, offsetY) else null
                return introDots.draw(canvas, dotsLeft, top, right, bottom, density,
                    elapsed, duration, countdown.isIntro, introColor, alpha * introOpacity, offsetY) ?: infoDelay
            } finally {
                canvas.restoreToCount(save)
            }
        }
        val phase = if ((current.priorRows != null || current.priorCountdown != null) && motion != LineMotion.NONE) {
            ((position - current.motionStartMs).toFloat() / LYRIC_MOTION_DURATION_MS).coerceIn(0f, 1f)
        } else 1f
        val easing = 1f - (1f - phase) * (1f - phase)
        val travel = if (motion == LineMotion.UP) bottom - top else 0f
        val crossfade = motion == LineMotion.FADE
        val incomingY = travel * (1f - easing)
        val incomingOpacity = opacity * if (crossfade) easing else 1f
        val countdown = current.countdown
        if (countdown != null) {
            if (position >= countdown.endMs) return null
            if (phase >= 1f) return drawCountdown(countdown, firstRow, secondRow, position, 0f, opacity)
        }
        val skippedWidth = lensRight - lensLeft
        val compactRight = right - skippedWidth
        if (compactRight <= left) return null
        val capsuleCenter = compactCapsuleCenter(textureArea.centerX(), lensLeft, skippedWidth)
        val viewport = CapsuleTextViewport(left, compactRight, lyricAlignment, capsuleCenter)
        for (lane in lanes) {
            lane.textureArea.set(textureArea)
            lane.cameraSeam = if (avoidCameraWords && skippedWidth > 0f && lensLeft > left && lensRight < right) lensLeft - left else 0f
        }
        val feather = viewport.outerFeather(density)
        val anchor = wordFollowAnchor(compactRight - left, lensLeft - left, feather.trailingInset)
        val center = (top + bottom) / 2
        val marquee = if (current.isTrackInfo) {
            val overflow = viewport.travel(firstRow.width) / density
            val inset = feather.trailingInset / density
            trackInfoMarquee?.sample(position, trackInfoSettled, overflow, inset)
                ?: TrackTitleMarquee.sample(position, overflow, inset)
        } else null
        val secondIsStatic = current.isTrackInfo || current.rows.nextLyricStartMs != null
        val priorPreviewIsStatic = current.priorRows?.nextLyricStartMs != null || current.priorTrackProgress != null
        firstRow.sampleWords(position)
        secondRow.sampleWords(if (secondIsStatic) Long.MIN_VALUE else position)
        priorFirstRow.sampleWords(minOf(position, current.displayStartMs))
        priorSecondRow.sampleWords(if (priorPreviewIsStatic) Long.MIN_VALUE else minOf(position, current.displayStartMs))
        // The timed original supplies the progress even when the translation is above it.
        val wordProgress = firstRow.wordProgress(viewport, feather, anchor, secondRow.takeUnless { secondIsStatic })
            ?: if (secondIsStatic) null else secondRow.wordProgress(viewport, feather, anchor, firstRow)
        val progress = marquee?.progress ?: wordProgress ?: current.scrollFraction(position)
        val priorProgress = current.priorTrackProgress ?: priorFirstRow.wordProgress(viewport, feather, anchor, priorSecondRow.takeUnless { priorPreviewIsStatic })
            ?: (if (priorPreviewIsStatic) null else priorSecondRow.wordProgress(viewport, feather, anchor, priorFirstRow)) ?: 1f
        val promote = countdown == null && phase < 1f && motion == LineMotion.UP && current.promotesPreviousLyric
        fun drawTrack(destination: Canvas, physicalOffset: Float = 0f) {
            for (lane in lanes) lane.textureOffsetX = physicalOffset
            if (promote) {
                val oldFirstTop = pairTop(center, priorFirstRow, priorSecondRow)
                val newFirstTop = pairTop(center, firstRow, secondRow)
                val newSecondTop = newFirstTop + firstRow.height
                priorFirstRow.draw(destination, viewport, feather, oldFirstTop - priorFirstRow.height * easing,
                    priorProgress, opacity * (1f - easing))
                secondRow.draw(destination, viewport, feather, newSecondTop + secondRow.height * (1f - easing),
                    if (secondIsStatic) 0f else progress, opacity * easing)
                // Draw the promoted lyric once; position, size and opacity meet both row endpoints.
                firstRow.drawPromoted(destination, viewport, feather, priorSecondRow,
                    oldFirstTop + priorFirstRow.height, newFirstTop, progress, easing, opacity)
                return
            }
            if (phase < 1f && current.priorCountdown == null) {
                drawPair(destination, priorFirstRow, priorSecondRow, viewport, feather,
                    center - travel * easing, priorProgress, priorPreviewIsStatic, opacity * if (crossfade) 1f - easing else 1f)
            }
            if (countdown == null) {
                drawPair(destination, firstRow, secondRow, viewport, feather,
                    center + incomingY, progress, secondIsStatic, incomingOpacity)
            }
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
        val save = canvas.save()
        try {
            if (!canvas.clipRect(left, top, right, bottom)) return null
            if (phase < 1f) current.priorCountdown?.let {
                drawCountdown(it, priorFirstRow, priorSecondRow, it.endMs, -travel * easing,
                    opacity * if (crossfade) 1f - easing else 1f)
            }
            if (seam != null && seam.x + seam.radiusX + fadePx > left &&
                seam.x - seam.radiusX - fadePx < right
            ) {
                val blur = minOf(fadePx * 0.3f, 1.8f * density)
                cameraMask.draw(canvas, left, top, right, bottom, seam, fadePx, blur, ::drawLyrics)
            } else {
                drawLyrics(canvas)
            }
            if (countdown != null) {
                drawCountdown(countdown, firstRow, secondRow, position, incomingY, incomingOpacity)
            }
        } finally {
            canvas.restoreToCount(save)
        }
        if (marquee != null) return marquee.redrawDelayMs
        if (phase < 1f) return 0L
        val firstDelay = firstRow.wordRedrawDelay(position)
        val secondDelay = if (secondIsStatic) null else secondRow.wordRedrawDelay(position)
        val highlightDelay = minOf(firstDelay ?: Long.MAX_VALUE, secondDelay ?: Long.MAX_VALUE)
        if (highlightDelay == 0L) return 0L
        val scrollDelay = 0L.takeIf { wordProgress == null && progress < 1f && current.endMs > current.startMs }
        if (scrollDelay != null && scrollDelay < highlightDelay) {
            val scrollingWidth = if (secondIsStatic) firstRow.width else maxOf(firstRow.width, secondRow.width)
            val scrolling = viewport.travel(scrollingWidth) > 0f || firstRow.extraCameraTravel(viewport) != null ||
                !secondIsStatic && secondRow.extraCameraTravel(viewport) != null
            if (scrolling) return scrollDelay
        }
        return highlightDelay.takeUnless { it == Long.MAX_VALUE }
    }

    private fun drawIntroTrack(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        main: Lane, second: Lane, position: Long, end: Long, opacity: Float, artworkArea: RectF, offsetY: Float,
    ): Long? {
        if (right <= left) return null
        val save = canvas.save()
        try {
            if (!canvas.clipRect(left, top, right, bottom)) return null
            val viewport = CapsuleTextViewport(left, right, lyricAlignment)
            val feather = viewport.outerFeather(density)
            val firstTop = pairTop((top + bottom) / 2f + offsetY, main, second)
            fun drawInfoRow(lane: Lane, y: Float): Long? {
                lane.textureArea.set(artworkArea)
                lane.textureOffsetX = 0f
                lane.cameraSeam = 0f
                val marquee = TrackTitleMarquee.sampleIntro(position, end, viewport.travel(lane.width) / density,
                    feather.trailingInset / density)
                lane.draw(canvas, viewport, feather, y, marquee.progress, opacity)
                return marquee.redrawDelayMs
            }
            val titleDelay = drawInfoRow(main, firstTop)
            val artistDelay = drawInfoRow(second, firstTop + main.height)
            return if (titleDelay != null && artistDelay != null) minOf(titleDelay, artistDelay)
            else titleDelay ?: artistDelay
        } finally {
            canvas.restoreToCount(save)
        }
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

    private class Lane(density: Float, private val rowUnsungOffsetPx: Float = 0f) {
        private val outerMask = OuterLyricMask()
        private val cameraBufferPx = CAMERA_FADE_RADIUS_DP * density
        private val wordSweep by lazy { WordHighlightSweep(WORD_SWEEP_FEATHER_DP * density) }
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        private var layout: StaticLayout? = null
        private var text: String? = null
        private var texture: Bitmap? = null
        private var textureShader: BitmapShader? = null
        private var gradient: LyricGradient? = null
        private var gradientShader: LinearGradient? = null
        private val textureMatrix = Matrix()
        val textureArea = RectF()
        var textureOffsetX = 0f
        private val textureCrop = Rect()
        private var words = emptyList<LyricWord>()
        private var wordSource = emptyList<LyricWord>()
        private var wordScroll: WordLyricScroll? = null
        private var wordIndex = -1
        private var wordFraction = 0f
        private var wordOffset = 0f
        private var wordEmphasis = 0f
        private val wordGlowRadiusPx = LONG_WORD_GLOW_RADIUS_DP * density
        private var avoidWords = false
        private var cameraUnits = emptyList<CameraWordBounds>()
        private var cameraGraphemes = emptyList<CameraWordBounds>()
        private val paths = HashMap<Long, Path>()
        var cameraSeam = 0f
        fun paintDiagnostics() = "#${paint.color.toUInt().toString(16)}/alpha=${paint.alpha}/present=${layout != null}"
        val height: Int get() = layout?.height ?: 0
        val width: Float get() = layout?.getLineWidth(0) ?: 0f

        fun update(
            value: String?, size: Float, opacity: Int, face: Typeface, rgb: Int, bitmap: Bitmap?,
            newWords: List<LyricWord>, avoidCamera: Boolean, newGradient: LyricGradient?,
        ) {
            if (texture !== bitmap) {
                texture = bitmap
                textureShader = bitmap?.takeUnless { it.isRecycled }?.let {
                    BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }
            }
            val layoutChanged = value != text || size != paint.textSize || face != paint.typeface
            if (layoutChanged) {
                text = value
                paint.textSize = size
                paint.typeface = face
                layout = value?.let {
                    val singleLine = it.replace('\n', ' ')
                    val width = (ceil(Layout.getDesiredWidth(singleLine, paint)).toInt() + 2).coerceAtLeast(1)
                    StaticLayout.Builder.obtain(singleLine, 0, singleLine.length, paint, width)
                        .setIncludePad(false).setMaxLines(1).build()
                }
                paths.clear()
                avoidWords = false
                wordSource = emptyList()
                words = emptyList()
                wordScroll = null
                cameraUnits = emptyList()
                cameraGraphemes = emptyList()
            }
            val measured = layout
            if (layoutChanged || gradient != newGradient) {
                gradient = newGradient
                gradientShader = if (measured != null && newGradient != null && width > 0f) {
                    val start = measured.getLineLeft(0)
                    LinearGradient(start, 0f, start + width, 0f,
                        intArrayOf(newGradient.start, newGradient.center, newGradient.end), null, Shader.TileMode.CLAMP)
                } else null
            }
            if (avoidCamera != avoidWords) {
                avoidWords = avoidCamera
                if (measured != null && avoidCamera) {
                    cameraUnits = cameraWordBounds(measured, groupLatin = true)
                    cameraGraphemes = cameraWordBounds(measured, groupLatin = false)
                } else {
                    cameraUnits = emptyList()
                    cameraGraphemes = emptyList()
                }
            }
            if (wordSource != newWords) {
                wordSource = newWords
                words = if (measured == null) emptyList() else safeWordRanges(measured, newWords)
                wordScroll = if (measured != null && measured.getParagraphDirection(0) == Layout.DIR_LEFT_TO_RIGHT &&
                    measured.text.indices.none(measured::isRtlCharAt)
                ) {
                    val start = measured.getLineLeft(0)
                    WordLyricScroll.fromLayout(words, width) { measured.getPrimaryHorizontal(it) - start }
                } else null
            }
            paint.color = rgb
            paint.alpha = (opacity * 255 + 50) / 100
        }

        fun sampleWords(position: Long) {
            val match = words.binarySearchBy(position) { it.startMs }
            wordIndex = if (match < 0) -match - 2 else match
            while (wordIndex + 1 < words.size && words[wordIndex + 1].startMs <= position) wordIndex++
            val word = words.getOrNull(wordIndex)
            wordFraction = word?.fraction(position) ?: 0f
            wordOffset = word?.baselineOffset(position) ?: 1f
            wordEmphasis = if (rowUnsungOffsetPx > 0f) word?.sustainEmphasis(position) ?: 0f else 0f
        }

        fun wordProgress(
            viewport: CapsuleTextViewport, feather: OuterLyricFeather, anchor: Float, other: Lane?,
        ): Float? {
            val highlight = wordScroll?.highlightAt(wordIndex, wordFraction) ?: return null
            if (viewport.travel(width) > 0f) return followHighlight(viewport, feather, anchor, highlight)
            if (other != null && viewport.travel(other.width) > 0f) {
                // A short timed original still drives a longer, untimed translation.
                return other.followHighlight(viewport, feather, anchor, highlight / width * other.width)
            }
            // A short line can still need a little travel to clear the camera.
            return highlight / width
        }

        private fun followHighlight(
            viewport: CapsuleTextViewport, feather: OuterLyricFeather, anchor: Float, highlight: Float,
        ): Float {
            val head = viewport.textOffset(width, 0f, feather.trailingInset)
            val tail = viewport.textOffset(width, 1f, feather.trailingInset)
            val measured = layout ?: return 0f
            return wordFollowProgress(highlight, anchor, head, tail) { origin ->
                val shift = cameraShift(width, viewport.right - viewport.left, origin, head, tail, 1f)
                val clusterShift = if (shift != null && shift.shiftsSuffix &&
                    highlight >= measured.getPrimaryHorizontal(shift.split) - measured.getLineLeft(0)
                ) shift.offset else 0f
                val inset = if (cameraSeam > 0f && origin + highlight + clusterShift >= cameraSeam)
                    cameraHeadInset(cameraBufferPx, head, origin) else 0f
                clusterShift + inset
            }
        }

        private fun cameraShift(
            lineWidth: Float, viewportWidth: Float, origin: Float, head: Float, tail: Float, scale: Float,
        ): CameraWordShift? = cameraWordShift(cameraUnits, lineWidth, viewportWidth, cameraSeam, origin, head, tail, scale)
            ?: cameraWordShift(cameraGraphemes, lineWidth, viewportWidth, cameraSeam, origin, head, tail, scale)

        fun wordRedrawDelay(position: Long): Long? {
            val word = words.getOrNull(wordIndex)
            if (word != null && position < (word.endMs ?: word.startMs)) return 0L
            return words.getOrNull(wordIndex + 1)?.let { (it.startMs - position).coerceAtLeast(1L) }
        }

        fun draw(
            canvas: Canvas, viewport: CapsuleTextViewport, feather: OuterLyricFeather,
            y: Float, progress: Float, alpha: Float,
        ) {
            val extraTravel = extraCameraTravel(viewport)
            val head = extraTravel?.head ?: viewport.textOffset(width, 0f, feather.trailingInset)
            val tail = extraTravel?.tail ?: viewport.textOffset(width, 1f, feather.trailingInset)
            val offset = if (extraTravel != null) lerp(head, tail, progress) else viewport.textOffset(width, progress, feather.trailingInset)
            drawAt(canvas, viewport, feather, y, offset, scale = 1f,
                inkAlpha = paint.alpha * alpha, fade = viewport.edgeFade(width, progress, feather), head = head, tail = tail)
        }

        fun extraCameraTravel(viewport: CapsuleTextViewport): CameraLyricTravel? {
            if (cameraUnits.isEmpty() || cameraSeam <= 0f || viewport.travel(width) > 0f) return null
            val viewportWidth = viewport.right - viewport.left
            val origin = viewport.textOffset(width, 0f)
            if (cameraWordShift(cameraUnits, width, viewportWidth, cameraSeam, origin, origin, origin) != null) return null
            return cameraLyricTravel(cameraUnits, width, viewportWidth, cameraSeam, origin)
                ?: cameraLyricTravel(cameraGraphemes, width, viewportWidth, cameraSeam, origin)
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
            val previousHead = previous.extraCameraTravel(viewport)?.head ?: viewport.textOffset(previous.width, 0f, feather.trailingInset)
            val travel = extraCameraTravel(viewport)
            val head = travel?.head ?: viewport.textOffset(width, 0f, feather.trailingInset)
            val tail = travel?.tail ?: viewport.textOffset(width, 1f, feather.trailingInset)
            val targetOffset = if (travel != null) lerp(head, tail, progress) else viewport.textOffset(width, progress, feather.trailingInset)
            val x = lerp(previousHead, targetOffset, phase)
            val fromFade = viewport.edgeFade(previous.width, 0f, feather)
            val toFade = viewport.edgeFade(width, progress, feather)
            val fade = EdgeFade(lerp(fromFade.left, toFade.left, phase), lerp(fromFade.right, toFade.right, phase))
            drawAt(canvas, viewport, feather, y, x, scale,
                lerp(previous.paint.alpha.toFloat(), paint.alpha.toFloat(), phase) * alpha, fade,
                blendInk(previous.paint.color, paint.color, phase.toDouble()),
                head = lerp(previousHead, head, phase), tail = lerp(previousHead, tail, phase),
                unsungOffsetPx = lerp(previous.rowUnsungOffsetPx, rowUnsungOffsetPx, phase),
            )
        }

        private fun drawAt(
            canvas: Canvas, viewport: CapsuleTextViewport, feather: OuterLyricFeather,
            y: Float, offset: Float, scale: Float, inkAlpha: Float, fade: EdgeFade, color: Int = paint.color,
            head: Float, tail: Float, unsungOffsetPx: Float = rowUnsungOffsetPx,
        ) {
            val measured = layout ?: return
            if (inkAlpha <= 0f) return
            val inset = if (cameraSeam > 0f && textureOffsetX > 0f) cameraHeadInset(cameraBufferPx, head, offset) else 0f
            val x = viewport.left + offset + inset - measured.getLineLeft(0) * scale
            val originalColor = paint.color
            val originalAlpha = paint.alpha
            val viewportWidth = viewport.right - viewport.left
            val shift = cameraShift(width * scale, viewportWidth, offset, head, tail, scale)
            val crossing = cameraWordFade(cameraGraphemes, cameraSeam, offset, scale, shift)
            val lowerMargin = if (words.isEmpty()) 0f else unsungOffsetPx
            val emphasis = if (rowUnsungOffsetPx > 0f) wordEmphasis * (unsungOffsetPx / rowUnsungOffsetPx) else 0f
            val top = if (emphasis > 0f) y - unsungOffsetPx - 2f * wordGlowRadiusPx * scale else y
            val bottom = y + measured.height * scale + lowerMargin
            try {
                outerMask.draw(canvas, viewport.left, top, viewport.right, bottom, feather, fade) masked@{ target ->
                    // Clip from the translated split so ink from the left slice cannot reappear on the right.
                    if (inset > 0f && !target.clipRect(viewport.left + cameraSeam + inset, top, viewport.right, bottom)) return@masked
                    paint.color = color
                    val image = texture
                    val shader = textureShader
                    val textured = image != null && shader != null && !image.isRecycled &&
                        textureArea.width() > 0f && textureArea.height() > 0f
                    val sx: Float
                    val sy: Float
                    if (textured) {
                        artworkCrop(image, textureArea.width() / textureArea.height(), textureCrop)
                        sx = textureArea.width() / textureCrop.width()
                        sy = textureArea.height() / textureCrop.height()
                    } else {
                        sx = 0f
                        sy = 0f
                    }
                    drawLayout(target, measured, shift, crossing, unsungOffsetPx, emphasis) { dx, dy, brightness, sweep ->
                        paint.alpha = (inkAlpha * brightness).toInt()
                        if (textured) {
                            textureMatrix.setScale(sx / scale, sy / scale)
                            textureMatrix.postTranslate(
                                (textureArea.left - textureCrop.left * sx - textureOffsetX - x - dx) / scale,
                                (textureArea.top - textureCrop.top * sy - y - dy) / scale,
                            )
                            shader.setLocalMatrix(textureMatrix)
                            paint.shader = shader
                        } else paint.shader = gradientShader
                        if (sweep) paint.shader = wordSweep.applyTo(paint.shader, color)
                        target.translate(x + dx, y + dy)
                        if (scale != 1f) target.scale(scale, scale)
                    }
                }
            } finally {
                paint.shader = null
                paint.color = originalColor
                paint.alpha = originalAlpha
            }
        }

        private fun drawLayout(
            canvas: Canvas, measured: StaticLayout, shift: CameraWordShift?, crossing: CameraWordFade?,
            unsungOffsetPx: Float, emphasis: Float,
            prepare: (dx: Float, dy: Float, alpha: Float, sweep: Boolean) -> Unit,
        ) {
            val length = measured.text.length
            val active = words.getOrNull(wordIndex)
            if (shift == null && crossing == null && (words.isEmpty() || active?.until == length && wordFraction == 1f)) {
                prepare(0f, 0f, 1f, false)
                measured.draw(canvas)
                return
            }

            fun drawRange(from: Int, until: Int, alpha: Float, dy: Float, sweep: Boolean = false, glow: Float = 0f) {
                if (from >= until) return
                val split = shift?.split?.coerceIn(from, until) ?: until
                fun drawPart(first: Int, last: Int, dx: Float, alpha: Float) {
                    if (first >= last || alpha <= 0f) return
                    val save = canvas.save()
                    try {
                        prepare(dx, dy, alpha, sweep)
                        if (first == 0 && last == length || canvas.clipPath(selection(measured, first, last))) {
                            val glowAlpha = (paint.alpha * glow * LONG_WORD_GLOW_ALPHA).toInt()
                            if (glowAlpha == 0) {
                                measured.draw(canvas)
                            } else {
                                paint.setShadowLayer(wordGlowRadiusPx, 0f, 0f, Color.argb(glowAlpha, 255, 255, 255))
                                try {
                                    measured.draw(canvas)
                                } finally {
                                    paint.clearShadowLayer()
                                }
                            }
                        }
                    } finally {
                        canvas.restoreToCount(save)
                    }
                }
                fun drawBand(first: Int, last: Int, dx: Float) {
                    if (first >= last) return
                    val unit = crossing?.unit
                    if (unit == null || unit.until <= first || unit.from >= last) {
                        drawPart(first, last, dx, alpha)
                        return
                    }
                    val startFade = maxOf(first, unit.from)
                    val endFade = minOf(last, unit.until)
                    drawPart(first, startFade, dx, alpha)
                    if (crossing.majorityOnRight == (textureOffsetX > 0f)) {
                        drawPart(startFade, endFade, dx, alpha * crossing.opacity)
                    }
                    drawPart(endFade, last, dx, alpha)
                }
                drawBand(from, split, shift?.offset?.takeIf { !shift.shiftsSuffix } ?: 0f)
                drawBand(split, until, shift?.offset?.takeIf { shift.shiftsSuffix } ?: 0f)
            }

            if (words.isEmpty()) {
                drawRange(0, length, 1f, 0f)
            } else if (active == null) {
                drawRange(0, length, UNPLAYED_ALPHA, unsungOffsetPx)
            } else if (wordFraction == 1f) {
                drawRange(0, active.until, 1f, 0f)
                drawRange(active.until, length, UNPLAYED_ALPHA, unsungOffsetPx)
            } else {
                drawRange(0, active.from, 1f, 0f)
                drawRange(active.until, length, UNPLAYED_ALPHA, unsungOffsetPx)
                val sweeping = wordFraction > 0f &&
                    wordSweep.move(selection(measured, active.from, active.until), wordFraction)
                val alpha = if (sweeping) 1f else UNPLAYED_ALPHA
                val lift = rowUnsungOffsetPx * emphasis
                drawRange(active.from, active.until, alpha, unsungOffsetPx * wordOffset - lift, sweeping, emphasis)
            }
        }

        private fun selection(layout: StaticLayout, from: Int, until: Int): Path {
            val key = (from.toLong() shl 32) or until.toLong()
            paths[key]?.let { return it }
            if (paths.size >= 96) paths.clear()
            return Path().also { path ->
                layout.getSelectionPath(from, until, path)
                paths[key] = path
            }
        }

        private fun safeWordRanges(layout: StaticLayout, ranges: List<LyricWord>): List<LyricWord> {
            if (ranges.isEmpty()) return ranges
            val letters = BreakIterator.getCharacterInstance().apply { setText(layout.text.toString()) }
            return ranges.takeIf { words -> words.all { word ->
                word.from in 0 until word.until && word.until <= layout.text.length &&
                    letters.isBoundary(word.from) && letters.isBoundary(word.until)
            } }.orEmpty()
        }

        private fun cameraWordBounds(layout: StaticLayout, groupLatin: Boolean): List<CameraWordBounds> {
            val value = layout.text.toString()
            if (value.indices.any(layout::isRtlCharAt)) return emptyList()
            val breaks = BreakIterator.getCharacterInstance().apply { setText(value) }
            val units = ArrayList<IntRange>()
            var from = breaks.first()
            var until = breaks.next()
            while (until != BreakIterator.DONE) {
                val letter = value[from]
                val prior = units.lastOrNull()
                val previousChar = value.getOrNull(from - 1)
                if (!letter.isWhitespace()) {
                    val joins = prior != null && previousChar != null && prior.last + 1 == from &&
                        ((groupLatin && isLatinWordChar(letter) && isLatinWordChar(previousChar)) ||
                            letter in FOLLOWING_PUNCTUATION || previousChar in OPENING_PUNCTUATION)
                    if (joins) units[units.lastIndex] = prior.first until until
                    else units += from until until
                }
                from = until
                until = breaks.next()
            }
            val lineLeft = layout.getLineLeft(0)
            return units.mapNotNull { range ->
                val l = layout.getPrimaryHorizontal(range.first) - lineLeft
                val r = layout.getPrimaryHorizontal(range.last + 1) - lineLeft
                if (r > l) CameraWordBounds(range.first, range.last + 1, l, r) else null
            }
        }

        private fun isLatinWordChar(char: Char): Boolean = char.isDigit() || char in "'’-" ||
            char.isLetter() && Character.UnicodeScript.of(char.code) == Character.UnicodeScript.LATIN

        private companion object {
            const val OPENING_PUNCTUATION = "([{（「『〈《“‘"
            const val FOLLOWING_PUNCTUATION = ")]}）」。』〉》”’，。、！？；：,.!?;:…"
        }
    }
}

/** Modulates the word's existing ink in layout coordinates, even when the camera splits it. */
private class WordHighlightSweep(private val maxFeatherWidth: Float) {
    private val bounds = RectF()
    private val matrix = Matrix()
    private val mask = LinearGradient(
        0f, 0f, 1f, 0f,
        IntArray(5) { index ->
            val fraction = index / 4f
            val eased = fraction * fraction * (3f - 2f * fraction)
            val alpha = (255f * (1f - (1f - UNPLAYED_ALPHA) * eased)).toInt()
            Color.argb(alpha, 255, 255, 255)
        },
        null, Shader.TileMode.CLAMP,
    )
    private var solidColor = Color.TRANSPARENT
    private var solidInk: Shader? = null
    private var source: Shader? = null
    private var composed: ComposeShader? = null

    fun move(selection: Path, fraction: Float): Boolean {
        selection.computeBounds(bounds, true)
        val width = bounds.width()
        if (width <= 0f) return false
        val feather = minOf(maxFeatherWidth, width / 2f)
        // At the exact start and end, the entire transition is outside the word.
        val gradientStart = bounds.left - feather + (width + feather) * fraction
        matrix.setScale(feather, 1f)
        matrix.postTranslate(gradientStart, 0f)
        mask.setLocalMatrix(matrix)
        return true
    }

    fun applyTo(ink: Shader?, color: Int): Shader {
        val original = ink ?: solidShader(color)
        composed?.takeIf { source === original }?.let { return it }
        source = original
        return ComposeShader(original, mask, PorterDuff.Mode.DST_IN).also { composed = it }
    }

    private fun solidShader(color: Int): Shader {
        solidInk?.takeIf { solidColor == color }?.let { return it }
        solidColor = color
        val opaque = color or Color.BLACK
        return LinearGradient(0f, 0f, 1f, 0f, opaque, opaque, Shader.TileMode.CLAMP).also { solidInk = it }
    }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction
