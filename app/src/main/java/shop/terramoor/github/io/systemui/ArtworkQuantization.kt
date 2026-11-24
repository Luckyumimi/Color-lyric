package shop.terramoor.github.io.systemui

import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val SWATCH_COUNT = 6

internal object ArtworkQuantization {
    private data class Swatch(val color: LabColor, val population: Int)

    // Split the box with the greatest RGB spread at its population median.
    fun medianCut(pixels: IntArray): LabColor? {
        if (pixels.isEmpty()) return null
        val boxes = mutableListOf(RgbBox(pixels.asList()))
        while (boxes.size < SWATCH_COUNT) {
            val box = boxes.maxBy { it.spread }
            if (box.spread == 0) break
            val sorted = box.colors.sortedBy { channel(it, box.axis) }
            val channelAtMedian = channel(sorted[sorted.size / 2], box.axis)
            val firstAtMedian = sorted.indexOfFirst { channel(it, box.axis) == channelAtMedian }
            val firstAfterMedian = sorted.indexOfFirst { channel(it, box.axis) > channelAtMedian }
            val split = if (firstAtMedian > 0) firstAtMedian else firstAfterMedian
            boxes.remove(box)
            boxes.add(RgbBox(sorted.subList(0, split)))
            boxes.add(RgbBox(sorted.subList(split, sorted.size)))
        }
        return choose(boxes.map { box ->
            fun average(shift: Int) = box.colors.map { channel(it, shift) }.average().roundToInt()
            Swatch(LabColor.fromRgb(opaqueRgb(average(16), average(8), average(0))), box.colors.size)
        })
    }

    private class RgbBox(val colors: List<Int>) {
        private val ranges = listOf(16, 8, 0).associateWith { shift ->
            channel(colors.maxBy { channel(it, shift) }, shift) - channel(colors.minBy { channel(it, shift) }, shift)
        }
        val axis = ranges.maxBy { it.value }.key
        val spread = ranges.getValue(axis)
    }

    // Cluster in the Cartesian form of OKLCH so that hues near 0° and 360° stay adjacent.
    fun oklch(pixels: IntArray): LabColor? = choose(cluster(pixels))

    private fun cluster(pixels: IntArray): List<Swatch> {
        if (pixels.isEmpty()) return emptyList()
        val histogram = pixels.asIterable().groupingBy { it }.eachCount()
        val points = histogram.map { (rgb, count) -> Swatch(LabColor.fromRgb(rgb), count) }
        val centers = mutableListOf(points.maxBy { it.population }.color)
        repeat(minOf(SWATCH_COUNT, points.size) - 1) {
            centers += points.maxBy { point ->
                centers.minOf { center -> point.color.distanceSquared(center) } * sqrt(point.population.toDouble())
            }.color
        }
        val populations = IntArray(centers.size)
        repeat(5) {
            val light = DoubleArray(centers.size)
            val a = DoubleArray(centers.size)
            val b = DoubleArray(centers.size)
            populations.fill(0)
            for ((color, count) in points) {
                val index = centers.indices.minBy { color.distanceSquared(centers[it]) }
                populations[index] += count
                light[index] += color.lightness * count
                a[index] += color.a * count
                b[index] += color.b * count
            }
            for (i in centers.indices) {
                val count = populations[i]
                if (count > 0) centers[i] = LabColor(light[i] / count, a[i] / count, b[i] / count)
            }
        }
        return centers.mapIndexed { index, color -> Swatch(color, populations[index]) }
    }

    private fun choose(swatches: List<Swatch>): LabColor? =
        usableSwatches(swatches).maxByOrNull(::accentScore)?.color

    private fun accentScore(swatch: Swatch): Double = sqrt(swatch.population.toDouble()) * swatch.color.chroma

    private fun usableSwatches(swatches: List<Swatch>): Sequence<Swatch> {
        return representativeSwatches(swatches).filter { it.color.chroma >= MIN_ACCENT_CHROMA }
    }

    private fun representativeSwatches(swatches: List<Swatch>): Sequence<Swatch> {
        val minPopulation = maxOf(1, swatches.sumOf { it.population } / 50)
        return swatches.asSequence().filter { it.population >= minPopulation }
    }

    private const val MIN_ACCENT_CHROMA = 0.018
}
