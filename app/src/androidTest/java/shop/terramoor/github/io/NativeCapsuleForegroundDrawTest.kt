package shop.terramoor.github.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.systemui.nativeCapsuleDrawableMatches

@RunWith(AndroidJUnit4::class)
class NativeCapsuleForegroundDrawTest {
    @Test fun frameworkForegroundResetIsCorrectedAtTheDrawableDrawBoundary() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            var recordedWidth = 0
            var repair = false
            var observedBefore = Rect()
            var observedDraw = Rect()
            var writes = 0
            val foreground = object : GradientDrawable() {
                override fun draw(canvas: Canvas) {
                    observedBefore = Rect(bounds)
                    if (repair && !nativeCapsuleDrawableMatches(200, 40, 100, recordedWidth,
                            bounds.left, bounds.top, bounds.right, bounds.bottom)) {
                        recordedWidth = 100
                        setBounds(50, 0, 150, 40)
                        writes++
                    }
                    observedDraw = Rect(bounds)
                    super.draw(canvas)
                }
            }.apply {
                setColor(Color.TRANSPARENT)
                setStroke(2, Color.WHITE)
                cornerRadius = 20f
            }
            val view = FrameLayout(context).apply {
                setForeground(foreground)
                measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY))
                layout(0, 0, 200, 40)
            }
            val bitmap = Bitmap.createBitmap(200, 40, Bitmap.Config.ARGB_8888)
            try {
                recordedWidth = 100
                foreground.setBounds(50, 0, 150, 40)
                view.draw(Canvas(bitmap))
                assertEquals(Rect(0, 0, 200, 40), observedDraw)
                assertEquals(0, Color.alpha(bitmap.getPixel(50, 20)))
                assertFalse(nativeCapsuleDrawableMatches(200, 40, 100, recordedWidth,
                    observedDraw.left, observedDraw.top, observedDraw.right, observedDraw.bottom))

                // A layout causes Android to recompute the foreground after the pre-draw setter.
                view.layout(1, 0, 201, 40)
                foreground.setBounds(50, 0, 150, 40)
                repair = true
                bitmap.eraseColor(Color.TRANSPARENT)
                view.draw(Canvas(bitmap))
                assertEquals(Rect(0, 0, 200, 40), observedBefore)
                assertEquals(Rect(50, 0, 150, 40), observedDraw)
                assertTrue(nativeCapsuleDrawableMatches(200, 40, 100, recordedWidth,
                    observedDraw.left, observedDraw.top, observedDraw.right, observedDraw.bottom))
                assertTrue(Color.alpha(bitmap.getPixel(50, 20)) > 0)
                assertTrue(Color.alpha(bitmap.getPixel(149, 20)) > 0)
                view.draw(Canvas(bitmap))
                assertEquals(1, writes)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
