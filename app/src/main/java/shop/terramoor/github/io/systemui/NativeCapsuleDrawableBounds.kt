package shop.terramoor.github.io.systemui

/** The OEM setter centers both drawables in the measured capsule. */
internal fun nativeCapsuleDrawableMatches(
    measuredWidth: Int, measuredHeight: Int, desiredWidth: Int, recordedWidth: Int?,
    left: Int, top: Int, right: Int, bottom: Int,
): Boolean {
    if (measuredWidth <= 0 || measuredHeight <= 0 || desiredWidth <= 0 || desiredWidth > measuredWidth) return false
    val expectedLeft = nativeCapsuleClipLeft(measuredWidth, desiredWidth)
    return recordedWidth == desiredWidth && left == expectedLeft && top == 0 &&
        right == expectedLeft + desiredWidth && bottom == measuredHeight
}
