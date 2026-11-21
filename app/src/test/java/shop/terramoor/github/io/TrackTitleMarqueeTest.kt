package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Test
import shop.terramoor.github.io.systemui.TrackTitleMarquee

class TrackTitleMarqueeTest {
    @Test fun fittingTitleNeverRequestsAnotherDraw() {
        assertEquals(TrackTitleMarquee.Sample(0f, null), TrackTitleMarquee.sample(2000, 0f, 8f))
    }

    @Test fun overflowingTitleHoldsAtStartAndScrollsForward() {
        // 56 dp of overflow + 8 dp of edge inset at 32 dp/s takes 2000 ms.
        assertEquals(TrackTitleMarquee.Sample(0f, 900L), TrackTitleMarquee.sample(0, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0f, 1L), TrackTitleMarquee.sample(899, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0f, 0L), TrackTitleMarquee.sample(900, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.5f, 0L), TrackTitleMarquee.sample(1900, 56f, 8f))
    }

    @Test fun overflowingTitleHoldsAtEndForThreeSecondsThenScrollsBack() {
        assertEquals(TrackTitleMarquee.Sample(1f, 3000L), TrackTitleMarquee.sample(2900, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(1f, 1L), TrackTitleMarquee.sample(5899, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(1f, 0L), TrackTitleMarquee.sample(5900, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.5f, 0L), TrackTitleMarquee.sample(6900, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.0005f, 0L), TrackTitleMarquee.sample(7899, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0f, 900L), TrackTitleMarquee.sample(7900, 56f, 8f))
        assertEquals(TrackTitleMarquee.Sample(0.5f, 0L), TrackTitleMarquee.sample(9800, 56f, 8f))
    }
}
