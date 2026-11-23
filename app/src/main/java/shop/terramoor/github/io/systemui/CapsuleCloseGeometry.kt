package shop.terramoor.github.io.systemui

import android.graphics.Rect
import android.view.View
import kotlin.math.roundToInt

internal fun View.topDisplayCutout(): Rect? = display?.cutout?.boundingRectTop?.takeUnless { it.isEmpty }
    ?: rootWindowInsets?.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }

internal fun capsuleCloseOffset(anchorX: Float, nativeCenterX: Float, parentScale: Float): Int =
    ((anchorX - nativeCenterX) / parentScale).roundToInt()
