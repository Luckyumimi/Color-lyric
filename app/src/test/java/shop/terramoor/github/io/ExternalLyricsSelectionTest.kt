package shop.terramoor.github.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.lyrics.provider.ExternalLyricsProvider.LYRICON
import shop.terramoor.github.io.lyrics.provider.ExternalLyricsProvider.SUPER_LYRIC
import shop.terramoor.github.io.lyrics.provider.ExternalLyricsSelection

class ExternalLyricsSelectionTest {
    @Test fun anOldRequestCannotReverseASourceSwitch() {
        val selection = ExternalLyricsSelection()
        assertTrue(selection.update(LYRICON, 10, true))
        assertTrue(selection.update(SUPER_LYRIC, 20, true))
        assertFalse(selection.update(LYRICON, 10, false))
        assertFalse(selection.update(LYRICON, 10, true))
        assertTrue(selection.matches(SUPER_LYRIC, 20))
        assertTrue(selection.update(LYRICON, 30, true))
        assertFalse(selection.matches(LYRICON, 10))
    }

    @Test fun aReleaseBeforeItsStartCannotReopenTheSubscription() {
        val selection = ExternalLyricsSelection()
        assertTrue(selection.update(LYRICON, 10, false))
        assertFalse(selection.update(LYRICON, 10, true))
        assertNull(selection.source)
        assertTrue(selection.update(LYRICON, 20, true))
        assertTrue(selection.update(LYRICON, 20, true))
    }
}
