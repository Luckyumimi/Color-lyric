package shop.terramoor.github.io.systemui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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

/** Draws each lyric lane once, with a fixed mask over the camera. */
internal class CapsuleLyricStrip(private val density: Float) {
    private val firstRow = Lane(density)
    private val secondRow = Lane()
    private val priorFirstRow = Lane(density)
    private val priorSecondRow = Lane()
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
        avoidCameraWords = settings.cameraWordAvoidance && frame?.isTrackInfo != true && frame?.isIntro != true
        introColor = ink.first
        introOpacity = settings[CapsuleTuning.FIRST_ROW_OPACITY] / 100f
        fun setup(main: Lane, second: Lane, rows: LyricRows?) {
            val size = settings.effectiveFirstRowSize(rows?.missingTranslationSingleLine == true)
            main.update(rows?.first, size * density, settings[CapsuleTuning.FIRST_ROW_OPACITY], Typeface.DEFAULT_BOLD,
                ink.first, ink.texture, rows?.firstWords.orEmpty(), avoidCameraWords, ink.gradient)
            // An upcoming lyric uses the same glyphs before and after promotion.
            val secondFace = if (rows?.nextLyricStartMs != null) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            second.update(rows?.second, settings.effectiveSecondRowSize * density,
                settings[CapsuleTuning.SECOND_ROW_OPACITY], secondFace, ink.second, ink.texture,
                rows?.secondWords.orEmpty(), avoidCameraWords, ink.gradient)
        }
        setup(firstRow, secondRow, frame?.rows)
        setup(priorFirstRow, priorSecondRow, frame?.priorRows)
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
        if (current.isIntro) {
            if (position >= current.endMs) return null
            val fallbackSplit = (left + right) / 2f
            val infoRight = if (seam == null) fallbackSplit else lensLeft
            val dotsLeft = if (seam == null) fallbackSplit else lensRight
            val infoDelay = drawIntroTrack(canvas, left, top, infoRight, bottom, position, current.endMs, opacity, textureArea)
            return introDots.draw(canvas, dotsLeft, top, right, bottom, density,
                position, current.endMs, introColor, opacity * introOpacity) ?: infoDelay
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
        val phase = if (current.priorRows != null && motion != LineMotion.NONE) {
            ((position - current.displayStartMs) / 260f).coerceIn(0f, 1f)
        } else 1f
        val easing = 1f - (1f - phase) * (1f - phase)
        val travel = if (motion == LineMotion.UP) bottom - top else 0f
        val center = (top + bottom) / 2
        val marquee = if (current.isTrackInfo) {
            val overflow = viewport.travel(firstRow.width) / density
            val inset = feather.trailingInset / density
            trackInfoMarquee?.sample(position, trackInfoSettled, overflow, inset)
                ?: TrackTitleMarquee.sample(position, overflow, inset)
        } else null
        val secondIsStatic = current.isTrackInfo || current.rows.nextLyricStartMs != null
        val priorPreviewIsStatic = current.priorRows?.nextLyricStartMs != null
        firstRow.sampleWords(position)
        secondRow.sampleWords(if (secondIsStatic) Long.MIN_VALUE else position)
        priorFirstRow.sampleWords(minOf(position, current.displayStartMs))
        priorSecondRow.sampleWords(if (priorPreviewIsStatic) Long.MIN_VALUE else minOf(position, current.displayStartMs))
        // The timed original supplies the progress even when the translation is above it.
        val wordScroll = firstRow.wordScrollAt(position) ?: if (secondIsStatic) null else secondRow.wordScrollAt(position)
        val progress = marquee?.progress ?: wordScroll?.progress ?: current.scrollFraction(position)
        val priorPosition = minOf(position, current.displayStartMs)
        val priorScroll = priorFirstRow.wordScrollAt(priorPosition)
            ?: if (priorPreviewIsStatic) null else priorSecondRow.wordScrollAt(priorPosition)
        val priorProgress = priorScroll?.progress ?: 1f
        val promote = phase < 1f && motion == LineMotion.UP && current.promotesPreviousLyric
        val crossfade = motion == LineMotion.FADE
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
            if (phase < 1f) {
                drawPair(destination, priorFirstRow, priorSecondRow, viewport, feather,
                    center - travel * easing, priorProgress, priorPreviewIsStatic, opacity * if (crossfade) 1f - easing else 1f)
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
        if (phase < 1f) return 0L
        val firstDelay = firstRow.wordRedrawDelay(position)
        val secondDelay = if (secondIsStatic) null else secondRow.wordRedrawDelay(position)
        val highlightDelay = minOf(firstDelay ?: Long.MAX_VALUE, secondDelay ?: Long.MAX_VALUE)
        if (highlightDelay == 0L) return 0L
        val scrollDelay = if (wordScroll != null) wordScroll.redrawDelayMs
            else 0L.takeIf { progress < 1f && current.endMs > current.startMs }
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
        position: Long, end: Long, opacity: Float, artworkArea: RectF,
    ): Long? {
        if (right <= left) return null
        val save = canvas.save()
        try {
            if (!canvas.clipRect(left, top, right, bottom)) return null
            val viewport = CapsuleTextViewport(left, right, lyricAlignment)
            val feather = viewport.outerFeather(density)
            val firstTop = pairTop((top + bottom) / 2f, firstRow, secondRow)
            fun drawInfoRow(lane: Lane, y: Float): Long? {
                lane.textureArea.set(artworkArea)
                lane.textureOffsetX = 0f
                lane.cameraSeam = 0f
                val marquee = TrackTitleMarquee.sampleIntro(position, end, viewport.travel(lane.width) / density,
                    feather.trailingInset / density)
                lane.draw(canvas, viewport, feather, y, marquee.progress, opacity)
                return marquee.redrawDelayMs
            }
            val titleDelay = drawInfoRow(firstRow, firstTop)
            val artistDelay = drawInfoRow(secondRow, firstTop + firstRow.height)
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

    private class Lane(private val rowUnsungOffsetPx: Float = 0f) {
        private val outerMask = OuterLyricMask()
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
                wordScroll = if (measured != null && measured.getParagraphDirection(0) == Layout.DIR_LEFT_TO_RIGHT) {
                    val start = measured.getLineLeft(0)
                    WordLyricScroll.fromLayout(words, width) { index ->
                        measured.getPrimaryHorizontal(words[index].until) - start
                    }
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
        }

        fun wordScrollAt(position: Long): WordLyricScroll.Sample? = wordScroll?.sample(position)

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
            val x = viewport.left + offset - measured.getLineLeft(0) * scale
            val originalColor = paint.color
            val originalAlpha = paint.alpha
            val viewportWidth = viewport.right - viewport.left
            val shift = cameraWordShift(cameraUnits, width * scale, viewportWidth, cameraSeam, offset, head, tail, scale)
                ?: cameraWordShift(cameraGraphemes, width * scale, viewportWidth, cameraSeam, offset, head, tail, scale)
            val crossing = cameraWordFade(cameraGraphemes, cameraSeam, offset, scale, shift)
            val lowerMargin = if (words.isEmpty()) 0f else unsungOffsetPx
            try {
                outerMask.draw(canvas, viewport.left, y, viewport.right, y + measured.height * scale + lowerMargin,
                    feather, fade) { target ->
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
                    drawLayout(target, measured, shift, crossing, unsungOffsetPx) { dx, dy, brightness ->
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
            unsungOffsetPx: Float,
            prepare: (dx: Float, dy: Float, alpha: Float) -> Unit,
        ) {
            val length = measured.text.length
            val active = words.getOrNull(wordIndex)
            if (shift == null && crossing == null && (words.isEmpty() || active?.until == length && wordFraction == 1f)) {
                prepare(0f, 0f, 1f)
                measured.draw(canvas)
                return
            }

            fun drawRange(from: Int, until: Int, alpha: Float, dy: Float) {
                if (from >= until) return
                val split = shift?.split?.coerceIn(from, until) ?: until
                fun drawPart(first: Int, last: Int, dx: Float, alpha: Float) {
                    if (first >= last || alpha <= 0f) return
                    val save = canvas.save()
                    try {
                        prepare(dx, dy, alpha)
                        if (first == 0 && last == length || canvas.clipPath(selection(measured, first, last))) {
                            measured.draw(canvas)
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
                drawRange(active.from, active.until, lerp(UNPLAYED_ALPHA, 1f, wordFraction), unsungOffsetPx * wordOffset)
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
            const val UNPLAYED_ALPHA = 0.55f
            const val OPENING_PUNCTUATION = "([{（「『〈《“‘"
            const val FOLLOWING_PUNCTUATION = ")]}）」。』〉》”’，。、！？；：,.!?;:…"
        }
    }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction
