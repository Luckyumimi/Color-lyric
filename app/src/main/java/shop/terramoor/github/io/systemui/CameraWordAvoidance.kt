package shop.terramoor.github.io.systemui

import kotlin.math.abs

internal data class CameraWordBounds(val from: Int, val until: Int, val left: Float, val right: Float)

internal data class CameraWordShift(val split: Int, val offset: Float) {
    val shiftsSuffix: Boolean get() = offset > 0f
}

internal data class CameraLyricTravel(val head: Float, val tail: Float)

internal data class CameraWordFade(val unit: CameraWordBounds, val majorityOnRight: Boolean, val opacity: Float)

/** Clears the right feather at the head, without reducing the scrolling viewport. */
internal fun cameraHeadInset(inset: Float, head: Float, origin: Float): Float =
    (inset - (head - origin).coerceAtLeast(0f)).coerceAtLeast(0f)

/** Gives a tightly packed line room to scroll when a whole group will not fit at rest. */
internal fun cameraLyricTravel(
    units: List<CameraWordBounds>, width: Float, viewWidth: Float, seam: Float, origin: Float,
): CameraLyricTravel? {
    if (seam <= 0f || seam >= viewWidth || width > viewWidth) return null
    val unit = crossingUnit(units, seam - origin) ?: return null
    if (cameraWordShift(units, width, viewWidth, seam, origin, origin, origin) != null) return null
    val unitWidth = unit.right - unit.left
    return when {
        seam + unitWidth <= viewWidth -> CameraLyricTravel(origin, seam - unit.right)
        seam >= unitWidth -> CameraLyricTravel(seam - unit.left, origin)
        else -> null
    }
}

internal fun cameraWordFade(
    units: List<CameraWordBounds>, seam: Float, origin: Float, scale: Float, shift: CameraWordShift?,
): CameraWordFade? {
    if (seam <= 0f || scale <= 0f) return null
    val unshifted = crossingUnit(units, (seam - origin) / scale)
        ?.takeUnless { shift?.appliesTo(it) == true }
    val delta = shift?.offset ?: 0f
    val crossing = unshifted ?: crossingUnit(units, (seam - origin - delta) / scale)
        ?.takeIf { shift?.appliesTo(it) == true } ?: return null
    val left = origin + crossing.left * scale + if (unshifted == null) delta else 0f
    val fractionOnLeft = (seam - left) / ((crossing.right - crossing.left) * scale)
    return CameraWordFade(crossing, fractionOnLeft < 0.5f, (2f * abs(fractionOnLeft - 0.5f)).coerceIn(0f, 1f))
}

private fun CameraWordShift.appliesTo(unit: CameraWordBounds): Boolean =
    if (shiftsSuffix) unit.from >= split else unit.until <= split

private fun crossingUnit(units: List<CameraWordBounds>, lookup: Float): CameraWordBounds? {
    val index = units.binarySearch { unit ->
        when {
            unit.right <= lookup -> -1
            unit.left >= lookup -> 1
            else -> 0
        }
    }
    return units.getOrNull(index)?.takeIf { lookup - it.left > 0.05f && it.right - lookup > 0.05f }
}

/** Positions whole text clusters beside the seam at rest and fades the displacement into scrolling. */
internal fun cameraWordShift(
    units: List<CameraWordBounds>, width: Float, viewWidth: Float, seam: Float,
    origin: Float, head: Float, tail: Float, scale: Float = 1f,
): CameraWordShift? {
    if (units.isEmpty() || seam <= 0f || seam >= viewWidth || scale <= 0f) return null

    fun crossing(at: Float): CameraWordBounds? = crossingUnit(units, (seam - at) / scale)

    fun moveRight(unit: CameraWordBounds, at: Float, weight: Float): CameraWordShift? =
        CameraWordShift(unit.from, (seam - (at + unit.left * scale)) * weight)
            .takeIf { seam + (unit.right - unit.left) * scale <= viewWidth }

    fun moveLeft(unit: CameraWordBounds, at: Float, weight: Float): CameraWordShift? =
        CameraWordShift(unit.until, (seam - (at + unit.right * scale)) * weight)
            .takeIf { seam >= (unit.right - unit.left) * scale }

    val travel = head - tail
    if (travel <= 0f) {
        val unit = crossing(origin) ?: return null
        val right = moveRight(unit, origin, 1f)?.takeIf { origin + width + it.offset <= viewWidth + 0.05f }
        val left = moveLeft(unit, origin, 1f)?.takeIf { origin + it.offset >= -0.05f }
        return listOfNotNull(right, left).minByOrNull { abs(it.offset) }
    }

    fun weight(unit: CameraWordBounds, distance: Float): Float {
        val window = minOf((unit.right - unit.left) * scale * 2f, travel * 0.5f)
        val remaining = (1f - distance / window).coerceIn(0f, 1f)
        return remaining * remaining * (3f - 2f * remaining)
    }

    if (origin > (head + tail) * 0.5f) {
        val unit = crossing(head) ?: return null
        val amount = weight(unit, head - origin)
        return if (amount > 0f) moveRight(unit, head, amount) else null
    }
    val unit = crossing(tail) ?: return null
    val amount = weight(unit, origin - tail)
    return if (amount > 0f) moveLeft(unit, tail, amount) else null
}
