package shop.terramoor.github.io

import io.github.andrealtb.coloroslyrics.provider.parser.ttml.TtmlParser
import io.github.andrealtb.coloroslyrics.provider.parser.yrc.YrcParser
import io.github.andrealtb.coloroslyrics.provider.universal.api.StructuredLyrics
import io.github.andrealtb.coloroslyrics.provider.universal.api.SongMatchScorer
import io.github.andrealtb.coloroslyrics.provider.universal.api.TrackQuery
import io.github.andrealtb.coloroslyrics.provider.universal.api.TtmlLyrics
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalLyricPayloadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.json.JSONObject
import shop.terramoor.github.io.lyrics.Timeline

class BuiltinLyricsFormatTest {
    @Test
    fun qrcAndYrcPreserveWordsThroughTheAppTimeline() {
        val qrc = StructuredLyrics.parseQrc("[1000,2000]Hello (1000,1000)world(2000,1000)")
        val yrc = YrcParser.parse("[1000,2000](1000,1000,0)Hello (2000,1000,0)world")
        assertEquals(2, qrc.single().words.size)
        assertEquals(2, yrc.single().words!!.size)
        val raw = StructuredLyrics.toEnhancedLrc(qrc)
        val timeline = Timeline.parse(StructuredLyrics.toPlainLrc(qrc), raw, "")!!
        assertEquals("Hello world", timeline.lines.single().text)
        assertEquals(listOf(1000L, 2000L), timeline.lines.single().words.map { it.startMs })
    }

    @Test
    fun ttmlKeepsWordTimingAndInlineTranslation() {
        val document = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:00:01.000" end="00:00:03.000"><span begin="00:00:01.000" end="00:00:02.000">Hello </span><span begin="00:00:02.000" end="00:00:03.000">world</span><span role="x-translation">你好</span></p>
            </div></body></tt>""".trimIndent()
        val parsed = TtmlParser.parse(document)!!
        assertEquals(2, parsed.lines.single().words!!.size)
        val result = TtmlLyrics.toResult(document, TrackQuery("Song", "Artist"), "test", null)!!
        assertTrue(result.rawLyric!!.contains("<00:01.000>"))
        assertTrue(result.translationLyric!!.contains("你好"))
        val timeline = Timeline.parse(result.lyric, result.rawLyric!!, result.translationLyric!!)!!
        assertEquals("你好", timeline.lines.single().translation)
    }

    @Test
    fun copyrightLinesAndMissingTranslationsDoNotShiftOtherLines() {
        val original = StructuredLyrics.parseLrc("[00:00.000]作词：某人\n[00:02.000]First\n[00:04.000]Second\n[00:08.000]Third")
        val translated = StructuredLyrics.parseLrc("[00:01.000]某公司享有本翻译作品的著作权\n[00:04.000]第二句\n[00:08.000]第三句")
        val merged = StructuredLyrics.merge(original, translated)
        assertEquals(listOf("", "", "第二句", "第三句"), merged.map { it.second })
    }

    @Test
    fun oneRealLyricLineIsNotCachedAsInstrumental() {
        assertFalse(SongMatchScorer.isInstrumentalOrCreditsOnly("[00:01.000]One real line"))
        assertTrue(SongMatchScorer.isInstrumentalOrCreditsOnly("[00:01.000]纯音乐，请欣赏"))
    }

    @Test
    fun processingSwitchesCanRebuildTheFullPlayerPayload() {
        val original = """{"lyric":"[00:01.000]Hello world","rawLyric":"[00:01.000]<00:01.000>Hello <00:02.000>world<00:03.000>","translationLyric":"[00:01.000]你好"}"""
        val reduced = JSONObject(original)
        UniversalLyricPayloadPolicy.apply(reduced, wordTiming = false, rawLyric = false, translation = false)
        assertFalse(reduced.has("rawLyric"))
        assertFalse(reduced.has("translationLyric"))
        val restored = JSONObject(original)
        UniversalLyricPayloadPolicy.apply(restored, wordTiming = true, rawLyric = true, translation = true)
        assertTrue(restored.getString("rawLyric").contains("<00:02.000>"))
        assertTrue(restored.getString("translationLyric").contains("你好"))
    }

    @Test
    fun disablingWordTimingAlsoRemovesTtmlTimingAndAllTranslationAliases() {
        val payload = JSONObject().apply {
            put("lyric", "[00:01.000]Hello")
            put("rawLyric", "[00:01.000]<00:01.000>Hello<00:02.000>")
            put("ttml", "<tt><body/></tt>")
            put("translatedLyric", "[00:01.000]你好")
            put("lyricTranslation", "[00:01.000]你好")
        }
        UniversalLyricPayloadPolicy.apply(payload, wordTiming = false, rawLyric = true, translation = false)
        assertFalse(payload.has("ttml"))
        assertFalse(payload.has("translatedLyric"))
        assertFalse(payload.has("lyricTranslation"))
        assertFalse(payload.getString("rawLyric").contains("<00:01.000>"))
    }
}
