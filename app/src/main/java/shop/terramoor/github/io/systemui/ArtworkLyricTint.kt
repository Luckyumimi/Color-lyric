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

    fun resolve(
        source: Bitmap?, background: Bitmap?, settings: CapsuleSettings,
        aspect: Float = 0f, baseColor: Int = 0xFF000000.toInt(),
    ): LyricInk {
        if (this.source !== source) {
            this.source = source
            this.settings = null
            seeds.clear()
        }
        if (!settings.lyricTintEnabled || source == null || source.isRecycled) {
            outcome = if (settings.lyricTintEnabled) "NO_ARTWORK" else "DISABLED"
            extraction = "NOT_RUN"
            this.settings = null
            this.background = null
            return LyricInk.WHITE
        }
        if (this.settings == settings && this.background === background &&
            this.aspect == aspect && this.baseColor == baseColor) return ink
        val mode = settings.lyricTintMode
        val result = seeds.getOrPut(mode) { extract(source, mode) }
        val seed = result.seed
        extraction = result.status
        ink = if (seed == null) LyricInk.WHITE
        else lyricInk(seed, sampleBackground(background, aspect, baseColor, settings[CapsuleTuning.ARTWORK_DIM]), settings)
        outcome = when {
            seed == null -> "NO_USABLE_ACCENT"
            ink != LyricInk.WHITE -> "TINTED"
            else -> "WHITE_AFTER_CONTRAST"
        }
        this.settings = settings
        this.background = background
        this.aspect = aspect
        this.baseColor = baseColor
        return ink
    }

    private data class Extraction(val seed: LabColor?, val status: String)

    private fun extract(source: Bitmap, mode: LyricTintMode): Extraction {
        return try {
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
            if (colors.isEmpty()) return Extraction(null, "NO_OPAQUE_PIXELS")
            val seed = when (mode) {
                LyricTintMode.MEDIAN_CUT -> ArtworkQuantization.medianCut(colors)
                LyricTintMode.OKLCH -> ArtworkQuantization.oklch(colors)
                LyricTintMode.WALLPAPER -> {
                    // WallpaperColors uses the OS's Material quantizer and ranked candidates.
                    val palette = WallpaperColors.fromBitmap(sample)
                    sequenceOf(palette.primaryColor, palette.secondaryColor, palette.tertiaryColor)
                        .filterNotNull()
                        .map { LabColor.fromRgb(it.toArgb()) }
                        .firstOrNull { it.chroma >= 0.018 }
                }
            }
            Extraction(seed, if (seed == null) "NO_CANDIDATE" else "CANDIDATE_FOUND")
        } catch (error: IllegalArgumentException) {
            Extraction(null, error.javaClass.simpleName)
        } catch (error: IllegalStateException) {
            Extraction(null, error.javaClass.simpleName)
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
