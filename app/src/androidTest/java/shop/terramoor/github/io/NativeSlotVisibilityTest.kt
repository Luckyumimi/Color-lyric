package shop.terramoor.github.io

import android.view.View
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.systemui.NativeSlotVisibility

@RunWith(AndroidJUnit4::class)
class NativeSlotVisibilityTest {
    @Test
    fun hidingPreservesMeasuredWidthAndRestoresOnlyOwnedVisibility() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val parent = LinearLayout(context)
            val cover = View(context)
            val waveform = View(context)
            parent.addView(cover, LinearLayout.LayoutParams(80, 40))
            parent.addView(waveform, LinearLayout.LayoutParams(60, 40))
            val coverState = NativeSlotVisibility(cover)
            val waveformState = NativeSlotVisibility(waveform)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST)
            parent.measure(widthSpec, heightSpec)
            val originalWidth = parent.measuredWidth
            for (hideCover in listOf(false, true)) for (hideWaveform in listOf(false, true)) {
                coverState.apply(hideCover)
                waveformState.apply(hideWaveform)
                parent.measure(widthSpec, heightSpec)
                assertEquals(originalWidth, parent.measuredWidth)
                assertEquals(if (hideCover) View.INVISIBLE else View.VISIBLE, cover.visibility)
                assertEquals(if (hideWaveform) View.INVISIBLE else View.VISIBLE, waveform.visibility)
            }
            cover.visibility = View.VISIBLE
            coverState.apply(true)
            assertEquals(View.INVISIBLE, cover.visibility)
            coverState.restore()
            waveformState.restore()
            assertEquals(View.VISIBLE, cover.visibility)
            assertEquals(View.VISIBLE, waveform.visibility)
            cover.visibility = View.GONE
            coverState.apply(true)
            coverState.restore()
            assertEquals(View.GONE, cover.visibility)
            cover.visibility = View.INVISIBLE
            coverState.apply(true)
            coverState.restore()
            assertEquals(View.INVISIBLE, cover.visibility)
        }
    }
}
