package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Test
import shop.terramoor.github.io.systemui.TrackInfoDisplayMarquee
import shop.terramoor.github.io.systemui.TrackTitleMarquee

class TrackTitleMarqueeTest {
    @Test fun introCanStartScrollingBeforeTheFirstLyric() {
        assertEquals(TrackTitleMarquee.Sample(0f, null), TrackTitleMarquee.sampleIntro(1500, 3000, 0f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0f, 1L), TrackTitleMarquee.sampleIntro(749, 3000, 24f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.5f, 0L), TrackTitleMarquee.sampleIntro(1250, 3000, 24f, 8f))
        assertEquals(TrackTitleMarquee.Sample(1f, 3000L), TrackTitleMarquee.sampleIntro(1750, 3000, 24f, 8f))
    }

    @Test fun fittingTitleNeverRequestsAnotherDraw() {
        assertEquals(TrackTitleMarquee.Sample(0f, null), TrackTitleMarquee.sample(2000, 0f, 8f))
    }

    @Test fun overflowingTitleHoldsAtStartAndScrollsForward() {
        // 56 dp of overflow + 8 dp of edge inset at 32 dp/s takes 2000 ms.
        assertEquals(TrackTitleMarquee.Sample(0f, 3000L), TrackTitleMarquee.sample(0, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0f, 1L), TrackTitleMarquee.sample(2999, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0f, 0L), TrackTitleMarquee.sample(3000, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.5f, 0L), TrackTitleMarquee.sample(4000, 56f, 8f))
    }

    @Test fun overflowingTitleHoldsAtEndForThreeSecondsThenScrollsBack() {
        assertEquals(TrackTitleMarquee.Sample(1f, 3000L), TrackTitleMarquee.sample(5000, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(1f, 1L), TrackTitleMarquee.sample(7999, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(1f, 0L), TrackTitleMarquee.sample(8000, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.5f, 0L), TrackTitleMarquee.sample(9000, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.0005f, 0L), TrackTitleMarquee.sample(9999, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0f, 3000L), TrackTitleMarquee.sample(10000, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0f, 1L), TrackTitleMarquee.sample(12999, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.5f, 0L), TrackTitleMarquee.sample(14000, 56f, 8f))
    }

    @Test fun manuallyReopenedTitleStartsItsHoldAfterTheCapsuleSettles() {
        val marquee = TrackInfoDisplayMarquee()
        assertEquals(TrackTitleMarquee.Sample(0f, null), marquee.sample(200, false, 120f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0f, 3000L), marquee.sample(550, true, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.5f, 0L), marquee.sample(4550, true, 56f, 8f))
        marquee.reset()
        assertEquals(TrackTitleMarquee.Sample(0f, null), marquee.sample(10000, false, 120f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0f, 3000L), marquee.sample(10400, true, 56f, 8f))
    }

    @Test fun nativeWidthChangesKeepTheMarqueePhaseUntilTheViewportSettles() {
        val marquee = TrackInfoDisplayMarquee()
        marquee.sample(100, true, 56f, 8f)
        assertEquals(0.875f, marquee.sample(8350, true, 56f, 8f).progress)
        // These transient widths would produce different progress at the same elapsed time.
        assertEquals(TrackTitleMarquee.Sample(0.875f, null), marquee.sample(8400, false, 120f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.875f, null), marquee.sample(8450, false, 0f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.875f, 0L), marquee.sample(8500, true, 56f, 8f))
        assertEquals(0.5f, marquee.sample(9250, true, 56f, 8f).progress)
    }
}
