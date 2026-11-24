package shop.terramoor.github.io.systemui

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Rect
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LyricTintMode
import kotlin.math.roundToInt

/** Owned by a media session or preview. No quantization runs in the lyric draw loop. */
internal class ArtworkLyricTint {
    private var source: Bitmap? = null
    private val seeds = mutableMapOf<LyricTintMode, Extraction>()
    private var settings: CapsuleSettings? = null
    private var background: Bitmap? = null
    private var aspect = 0f
    private var baseColor = 0
    private var ink = LyricInk.WHITE
    var outcome = "NO_ARTWORK"
        private set
    var extraction = "NOT_RUN"
        private set
    var extractionDetail = "NOT_RUN"
        private set
    var contrastDetail = "NOT_RUN"
        private set
    var cacheDetail = "NOT_RUN"
        private set
    private var sourceGeneration = 0
    var calculationId = 0L
        private set

    fun resolve(
        source: Bitmap?, background: Bitmap?, settings: CapsuleSettings,
        aspect: Float = 0f, baseColor: Int = 0xFF000000.toInt(),
    ): LyricInk {
        val sameSource = this.source === source
        val generation = source?.takeUnless { it.isRecycled }?.generationId ?: 0
        cacheDetail = "sameSource=$sameSource, sourceGeneration=$generation, previousGeneration=$sourceGeneration"
        if (!sameSource || generation != sourceGeneration) {
            this.source = source
            this.settings = null
            seeds.clear()
        }
        sourceGeneration = generation
        if (!settings.lyricTintEnabled || source == null || source.isRecycled) {
            outcome = if (settings.lyricTintEnabled) "NO_ARTWORK" else "DISABLED"
            extraction = "NOT_RUN"
            extractionDetail = "NOT_RUN"
            contrastDetail = "NOT_RUN"
            this.settings = null
            this.background = null
            return LyricInk.WHITE
        }
        if (this.settings == settings && this.background === background &&
            this.aspect == aspect && this.baseColor == baseColor) {
            cacheDetail += ", ink=HIT"
            return ink
        }
        calculationId++
        val mode = settings.lyricTintMode
        val cachedSeed = seeds.containsKey(mode)
        val result = seeds.getOrPut(mode) { extract(source, mode) }
        cacheDetail += ", ink=MISS, seed=${if (cachedSeed) "HIT" else "MISS"}"
        val seed = result.seed
        extraction = result.status
        extractionDetail = result.detail
        contrastDetail = "NOT_RUN"
        ink = if (mode == LyricTintMode.TEXTURE) {
            val texture = textureBitmap(source, settings)
            if (texture == null) LyricInk.WHITE else LyricInk(-1, -1, texture)
        } else if (mode == LyricTintMode.GRADIENT) {
            contrastDetail = "contrastPolicy=hyper_cover_palette"
            result.coverInk?.withCoverTintStrength(settings[CapsuleTuning.TINT_STRENGTH]) ?: LyricInk.WHITE
        } else if (seed == null) LyricInk.WHITE
        else {
            val samples = sampleBackground(background, aspect, baseColor, settings[CapsuleTuning.ARTWORK_DIM])
            val backgroundKind = if (background == null || background.isRecycled || background.config == Bitmap.Config.HARDWARE) "BASE" else "BITMAP"
            lyricInk(seed, samples, settings) { contrastDetail = "source=$backgroundKind, dim=${settings[CapsuleTuning.ARTWORK_DIM]}, $it" }
        }
        outcome = when {
            mode == LyricTintMode.TEXTURE && ink.texture != null -> "TEXTURE"
            ink.gradient != null -> "GRADIENT"
            ink != LyricInk.WHITE -> "TINTED"
            seed == null && result.coverInk == null -> "NO_USABLE_ACCENT"
            else -> "WHITE_INK"
        }
        this.settings = settings
        this.background = background
        this.aspect = aspect
        this.baseColor = baseColor
        return ink
    }

    private data class Extraction(
        val seed: LabColor?, val status: String, val detail: String = "unavailable", val coverInk: LyricInk? = null,
    )

    private fun extract(source: Bitmap, mode: LyricTintMode): Extraction {
        return try {
            if (mode == LyricTintMode.GRADIENT) return extractCoverPalette(source)
            val scale = minOf(1f, 32f / maxOf(source.width, source.height))
            val width = (source.width * scale).roundToInt().coerceAtLeast(1)
            val height = (source.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(source, width, height, true)
            val sample = if (scaled.config == Bitmap.Config.HARDWARE) {
                scaled.copy(Bitmap.Config.ARGB_8888, false) ?: return Extraction(null, "HARDWARE_COPY_FAILED")
            } else scaled
            val pixels = IntArray(width * height)
            sample.getPixels(pixels, 0, width, 0, 0, width, height)
            val colors = pixels.filter { it ushr 24 >= 128 }.map { it or 0xFF000000.toInt() }.toIntArray()
            val sampleDetail = "sample=${width}x$height, opaque=${colors.size}/${pixels.size}, config=${sample.config}, generation=${source.generationId}"
            if (colors.isEmpty()) return Extraction(null, "NO_OPAQUE_PIXELS", sampleDetail)
            var candidates = "algorithm=$mode"
            val seed = when (mode) {
                LyricTintMode.TEXTURE -> ArtworkQuantization.medianCut(colors)
                LyricTintMode.MEDIAN_CUT -> ArtworkQuantization.medianCut(colors)
                LyricTintMode.OKLCH -> ArtworkQuantization.oklch(colors)
                LyricTintMode.GRADIENT -> error("Cover palette is resolved separately")
                LyricTintMode.WALLPAPER -> {
                    // WallpaperColors uses the OS's Material quantizer and ranked candidates.
                    val palette = WallpaperColors.fromBitmap(sample)
                    val colors = listOfNotNull(palette.primaryColor, palette.secondaryColor, palette.tertiaryColor)
                    val labs = colors.map { LabColor.fromRgb(it.toArgb()) }
                    val chosen = labs.indexOfFirst { it.chroma >= 0.018 }
                    candidates = "threshold=0.018, selected=$chosen, candidates=" + colors.mapIndexed { index, color ->
                        "#${color.toArgb().toUInt().toString(16)}:${"%.4f".format(java.util.Locale.ROOT, labs[index].chroma)}"
                    }.joinToString("|")
                    labs.getOrNull(chosen)
                }
            }
            val fallback = if (seed == null) {
                fun average(shift: Int) = colors.map { channel(it, shift) }.average().roundToInt()
                LabColor.fromRgb(opaqueRgb(average(16), average(8), average(0)))
            } else null
            Extraction(seed ?: fallback, if (seed == null) "AVERAGE_FALLBACK" else "CANDIDATE_FOUND",
                "$sampleDetail, $candidates")
        } catch (error: IllegalArgumentException) {
            Extraction(null, error.javaClass.simpleName)
        } catch (error: IllegalStateException) {
            Extraction(null, error.javaClass.simpleName)
        }
    }

    private fun extractCoverPalette(source: Bitmap): Extraction {
        val readable = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return Extraction(null, "HARDWARE_COPY_FAILED")
        } else source
        val palette = try {
            hyperCoverLyricInk(readable)
        } finally {
            if (readable !== source) readable.recycle()
        }
        val endpoints = if (palette?.gradient != null) 2 else if (palette != null) 1 else 0
        return Extraction(
            null, if (palette == null) "NO_PALETTE" else "CANDIDATE_FOUND",
            "algorithm=HYPER_COVER_PALETTE, cover=${source.width}x${source.height}, endpoints=$endpoints",
            coverInk = palette,
        )
    }

    private fun textureBitmap(source: Bitmap, settings: CapsuleSettings): Bitmap? {
        return try {
            val edge = 64
            val scale = minOf(1f, edge.toFloat() / maxOf(source.width, source.height))
            val width = (source.width * scale).roundToInt().coerceAtLeast(1)
            val height = (source.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(source, width, height, true)
            val sample = if (scaled.config == Bitmap.Config.HARDWARE) scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
            sample ?: return null
            val softened = blurArtwork(sample, 25)
            val pixels = IntArray(width * height)
            softened.getPixels(pixels, 0, width, 0, 0, width, height)
            val saturation = 2f
            val contrast = 1f
            val brightness = settings[CapsuleTuning.TEXTURE_BRIGHTNESS] / 100f
            val strength = settings[CapsuleTuning.TINT_STRENGTH] / 100f
            for (index in pixels.indices) {
                val color = pixels[index]
                val gray = 0.2126f * channel(color, 16) + 0.7152f * channel(color, 8) + 0.0722f * channel(color, 0)
                fun adjusted(shift: Int): Int {
                    val saturated = gray + (channel(color, shift) - gray) * saturation
                    val contrasted = (saturated - 127.5f) * contrast + 127.5f
                    val lightened = contrasted + (255f - contrasted) * brightness
                    return (255f + (lightened - 255f) * strength).roundToInt().coerceIn(0, 255)
                }
                pixels[index] = opaqueRgb(adjusted(16), adjusted(8), adjusted(0))
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun sampleBackground(bitmap: Bitmap?, aspect: Float, base: Int, dim: Int): IntArray {
        if (bitmap == null || bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE) return intArrayOf(base)
        val crop = Rect(0, 0, bitmap.width, bitmap.height)
        if (aspect > 0f) artworkCrop(bitmap, aspect, crop)
        val black = 0xFF000000.toInt()
        val dimAlpha = ((dim * 255 + 50) / 100) / 255.0
        return IntArray(27) { sample ->
            val x = crop.left + ((sample % 9 + 0.5) * crop.width() / 9).toInt()
            val y = crop.top + ((sample / 9 + 0.5) * crop.height() / 3).toInt()
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel ushr 24) * CapsuleSettings.ARTWORK_BACKGROUND_ALPHA / (255.0 * 255.0)
            blendInk(blendInk(base, pixel, alpha), black, dimAlpha)
        }
    }
}
