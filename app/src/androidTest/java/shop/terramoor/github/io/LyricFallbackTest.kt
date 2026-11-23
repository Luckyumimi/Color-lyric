package shop.terramoor.github.io

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.lyrics.LyricPayload
import shop.terramoor.github.io.lyrics.LyricWord
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.NoLyricsMode

@RunWith(AndroidJUnit4::class)
class LyricFallbackTest {
    @Test
    fun providerConvertedTtmlSpansUseTheSongClock() {
        val payload = JSONObject().put("songName", "Fixture")
            .put("lyric", "[00:08]Hi there!\n[00:12]Plain")
            .put("rawLyric", "[00:08]<00:08>Hi <00:09>there!<00:11>\n[00:12]Plain")
            .put("ttml", """<tt xmlns="http://www.w3.org/ns/ttml">
                <body><div><p begin="00:08.000" end="00:11.000"><span begin="00:08.000" end="00:08.500">Hi</span> <span begin="00:09.000" end="00:11.000">there!</span></p>
                <p begin="00:12.000" end="00:14.000">Plain</p></div></body></tt>""")
        val lines = requireNotNull(LyricPayload.parse(payload.toString(), "Fixture", false)).lines
        assertEquals(listOf(LyricWord(0, 3, 8000L, 8500L), LyricWord(3, 9, 9000L, 11000L)), lines[0].words)
        assertEquals(emptyList<LyricWord>(), lines[1].words)
    }

    @Test
    fun noLyricFlagsAndStaticTtmlProvideNoTimeline() {
        val staticTtml = """
            <tt xmlns="http://www.w3.org/ns/ttml"><body dur="180s"><div>
                <p>First</p><p>Second</p>
            </div></body></tt>
        """.trimIndent()
        val payload = JSONObject().put("songName", "Fixture").put("ttml", staticTtml)
        assertNull(LyricPayload.parse(null, "Fixture", false))
        assertNull(LyricPayload.parse(payload.toString(), "Fixture", false))
        assertNull(LyricPayload.parse(payload.put("rawLyric", "[00:00]First\n[00:00]Second").toString(), "Fixture", false))

        payload.put("lyric", "[00:01]First\n[00:02]Second")
        assertNotNull(LyricPayload.parse(payload.toString(), "Fixture", false))
        assertNull(LyricPayload.parse(payload.put("noLyric", true).toString(), "Fixture", false))
    }

    @Test
    fun timedTtmlContributesLineEndsToSynchronizedLyrics() {
        val payload = JSONObject().put("songName", "Fixture").put("lyric", "[00:01]First\n[00:05]Second")
            .put("ttml", """<tt xmlns="http://www.w3.org/ns/ttml"><body begin="1s"><div>
                <p begin="0s" dur="2.6s">First</p><p begin="4s" end="9s">Second</p>
            </div></body></tt>""")
        val timeline = checkNotNull(LyricPayload.parse(payload.toString(), "Fixture", false))
        assertEquals(3600L, timeline.displayStart(1))
        assertEquals(10000L, timeline.lines[1].endMs)
    }

    @Test
    fun missingPreferenceDefaultsToTrackAndExplicitEmptyIsRespected() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val name = "lyric-fallback-test"
        context.deleteSharedPreferences(name)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            assertEquals(NoLyricsMode.TRACK, CapsuleSettings.read(prefs).noLyricsMode)
            prefs.edit().putString(CapsuleSettings.NO_LYRICS, NoLyricsMode.EMPTY.name).apply()
            assertEquals(NoLyricsMode.EMPTY, CapsuleSettings.read(prefs).noLyricsMode)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
