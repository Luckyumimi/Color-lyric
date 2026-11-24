package shop.terramoor.github.io.systemui

import android.graphics.Bitmap
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class LyricInk(
    val first: Int, val second: Int, val texture: Bitmap? = null, val gradient: LyricGradient? = null,
) {
    companion object {
        val WHITE = LyricInk(-1, -1)
    }
}

internal data class LyricGradient(val start: Int, val center: Int, val end: Int)

internal data class LabColor(val lightness: Double, val a: Double, val b: Double) {
    val chroma: Double get() = hypot(a, b)

    fun distanceSquared(other: LabColor): Double {
        val light = lightness - other.lightness
        val da = a - other.a
        val db = b - other.b
        return light * light + da * da + db * db
    }

    fun toRgb(): Int {
        val rgb = linearRgb()
        return opaqueRgb(srgb(rgb.red), srgb(rgb.green), srgb(rgb.blue))
    }

    private data class LinearRgb(val red: Double, val green: Double, val blue: Double)

    private fun linearRgb(): LinearRgb {
        val l = cube(lightness + 0.3963377774 * a + 0.2158037573 * b)
        val m = cube(lightness - 0.1055613458 * a - 0.0638541728 * b)
        val s = cube(lightness - 0.0894841775 * a - 1.2914855480 * b)
        val red = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val green = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val blue = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        return LinearRgb(red, green, blue)
    }

    companion object {
        // OKLab conversion matrices from CSS Color 4's sample conversions.
        fun fromRgb(color: Int): LabColor {
            val red = linear(channel(color, 16))
            val green = linear(channel(color, 8))
            val blue = linear(channel(color, 0))
            val l = cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue)
            val m = cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue)
            val s = cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue)
            return LabColor(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }
    }
}

internal fun channel(color: Int, shift: Int) = (color ushr shift) and 255

internal fun opaqueRgb(red: Int, green: Int, blue: Int) =
    (255 shl 24) or (red shl 16) or (green shl 8) or blue

internal fun blendInk(from: Int, to: Int, amount: Double): Int {
    fun mix(shift: Int): Int {
        val start = channel(from, shift)
        return (start + (channel(to, shift) - start) * amount).roundToInt()
    }
    return opaqueRgb(mix(16), mix(8), mix(0))
}

private fun cube(x: Double) = x * x * x
private fun srgb(value: Double): Int {
    val x = value.coerceIn(0.0, 1.0)
    val encoded = if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1.0 / 2.4) - 0.055
    return (255 * encoded).roundToInt()
}

private val linearSrgb = DoubleArray(256) { index ->
    val x = index / 255.0
    if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
}
private fun linear(x: Int) = linearSrgb[x]
internal fun luminance(color: Int) =
    0.2126 * linear(channel(color, 16)) + 0.7152 * linear(channel(color, 8)) + 0.0722 * linear(channel(color, 0))

internal fun lyricInk(seed: LabColor?, background: IntArray, settings: CapsuleSettings,
                      observe: ((String) -> Unit)? = null): LyricInk {
    if (seed == null) return LyricInk.WHITE
    val amount = settings[CapsuleTuning.TINT_STRENGTH] / 100.0
    val tint = LabColor(1.0 - (1.0 - TINT_LIGHTNESS) * amount, seed.a * amount, seed.b * amount)
    val color = tint.toRgb()
    val backgroundLuminances = background.map(::luminance)
    observe?.invoke("backgroundLuminance=${backgroundLuminances.minOrNull()}..${backgroundLuminances.maxOrNull()}, " +
        "initial=#${color.toUInt().toString(16)}, contrastPolicy=preserve_tint")
    return LyricInk(color, color)
}

private const val TINT_LIGHTNESS = 0.95
