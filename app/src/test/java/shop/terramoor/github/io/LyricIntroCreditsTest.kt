package shop.terramoor.github.io

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.lyrics.LyricPayload
import shop.terramoor.github.io.lyrics.LyricPresentation
import shop.terramoor.github.io.lyrics.LyricRows
import shop.terramoor.github.io.lyrics.LyricWord
import shop.terramoor.github.io.lyrics.Timeline
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.MissingTranslationMode
import shop.terramoor.github.io.settings.SecondRowContent

class LyricIntroCreditsTest {
    private val enabled = CapsuleSettings(introDots = true, wordLyrics = true)
    private fun lyrics(text: String, title: String = "远方", artist: String = "甲") =
        checkNotNull(Timeline.parse(text.trimIndent(), "", "", titles = listOf(title), artist = artist))

    @Test fun convertedQrcCreditsWithWordTimingDoNotShortenTheCountdown() {
        val timeline = lyrics("""
            [00:00]《远方》－甲 / 乙
            [00:01]<00:01>词 Lyrics：<00:01.500>丙<00:02>
            [00:02]<00:02>曲：<00:02.500>丁<00:03>
            [00:03]和声编写 / 和声：甲
            [00:04.000]
            [00:12]<00:12>灯火<00:13>渐亮<00:14>
            [00:15]推开门
        """, artist = "甲, 乙")
        val presentation = LyricPresentation(timeline, enabled.copy(doubleLine = false))
        assertTrue(timeline.lines[1].words.size > 1)
        val track = LyricRows("远方", "甲, 乙")
        for (position in listOf(0L, 2500L, 11999L)) {
            val index = presentation.indexAt(position)
            assertEquals(-1, index)
            assertEquals(12000L, presentation.nextBoundary(index))
            val intro = checkNotNull(presentation.introAt(position) { track })
            assertTrue(intro.isIntro)
            assertEquals(12000L, intro.endMs)
            assertEquals(track, intro.rows)
        }
        val firstIndex = presentation.indexAt(12000L)
        val first = checkNotNull(presentation.frameAt(firstIndex, 20000L))
        assertNull(presentation.introAt(12000L))
        assertNull(first.priorRows)
        assertEquals(12000L, first.priorCountdown?.endMs)
        assertEquals(12000L, first.displayStartMs)
        assertEquals(listOf(LyricWord(0, 2, 12000L, 13000L), LyricWord(2, 4, 13000L, 14000L)), first.rows.firstWords)
        assertEquals(14000L, presentation.nextBoundary(firstIndex))
    }

    @Test fun convertedYrcCreditsAreFilteredWithoutChangingTheTranslationsOrLaterRows() {
        val payload = JSONObject().put("songName", "Night").put("artist", "Singer")
            .put("lyric", "[00:00]Night - Singer\n[00:01]作詞: Writer\n[00:02]作曲: Composer\n" +
                "[00:15]Turn the light on\n[00:18]曲：故事的一部分\n[00:22]Take a breath")
            .put("translationLyric", "[00:15]打开灯\n[00:22]深呼吸")
        val timeline = checkNotNull(LyricPayload.parse(payload.toString(), "Night", true))
        val translation = LyricPresentation(timeline, enabled)
        assertEquals(15000L, translation.nextBoundary(-1))
        assertEquals(LyricRows("Turn the light on", "打开灯"), translation.frameAt(0, 25000L)?.rows)

        val double = LyricPresentation(timeline, enabled.copy(secondRowContent = SecondRowContent.LYRIC))
        assertEquals(18000L, double.nextBoundary(0))
        assertEquals("曲：故事的一部分", double.frameAt(0, 25000L)?.rows?.second)
        assertEquals("曲：故事的一部分", double.frameAt(double.indexAt(18000L), 25000L)?.rows?.first)
        assertEquals(15000L, double.introAt(1200L)?.endMs)
    }

    @Test fun qrcOpeningCreditsWorkWithLocalizedTitlesAndAlternateArtistCredits() {
        // QQ Music IDs 212917681 and 204141723: real header forms and timestamps, synthetic sung rows.
        val samples = listOf(
            Triple("命ばっかり", "ぬゆり", "ぬゆり (Lanndo)/v flower/结月缘 (結月ゆかり)"),
            Triple("命ばっかり (只为生命)", "結月ゆかり/flower", "结月缘 (結月ゆかり)/v flower"),
        )
        for ((i, sample) in samples.withIndex()) {
            val (title, artist, creditedArtists) = sample
            val first = if (i == 0) 16708L else 16746L
            val firstTag = if (i == 0) "00:16.708" else "00:16.746"
            val creditTags = if (i == 0) listOf("00:04.170", "00:08.350", "00:12.530")
                else listOf("00:04.180", "00:08.370", "00:12.550")
            val header = "命ばっかり - $creditedArtists"
            val payload = JSONObject().put("songName", title).put("artist", artist).put("lyric", """
                [00:00]$header
                [${creditTags[0]}]词：ぬゆり
                [${creditTags[1]}]曲：ぬゆり
                [${creditTags[2]}]编曲：ぬゆり
                [$firstTag]<$firstTag>歌い<00:17.500>出す<00:18.000>
                [00:19]次の言葉
            """.trimIndent())
            val timeline = checkNotNull(LyricPayload.parse(payload.toString(), title, true, artist))
            val on = LyricPresentation(timeline, enabled.copy(doubleLine = false))
            assertEquals(-1, on.indexAt(first - 1))
            assertEquals(first, on.nextBoundary(-1))
            assertEquals(first, on.introAt(0L)?.endMs)
            assertEquals(first, on.introAt(first - 1)?.endMs)
            assertNull(on.introAt(first))
            val firstFrame = checkNotNull(on.frameAt(on.indexAt(first), 30000L))
            assertEquals("歌い出す", firstFrame.rows.first)
            assertEquals(first, firstFrame.rows.firstWords.first().startMs)

            val off = LyricPresentation(timeline, enabled.copy(introDots = false))
            assertEquals(header, off.frameAt(0, 30000L)?.rows?.first)
            assertEquals(4, off.indexAt(first))
        }
    }

    @Test fun relaxedQrcHeaderMatchRequiresTheCurrentTitleAndTheOpeningPosition() {
        val wrongTitle = lyrics("[00:00]另一首歌 - 乙\n[00:01]词：甲\n[00:06]灯还是亮着")
        val afterCredit = lyrics("[00:00]编曲：甲\n[00:05]远方 - 灯影\n[00:06]词：故事的一部分")
        assertEquals("另一首歌 - 乙", LyricPresentation(wrongTitle, enabled).lines.first().text)
        assertEquals("远方 - 灯影", LyricPresentation(afterCredit, enabled).lines.first().text)
    }

    @Test fun qrcOpeningCreditsIdentifyTheHeaderWhenPlaybackMetadataUsesAnotherLanguage() {
        // QQ Music IDs 202714435 and 202071047. Header credits and first vocal times match the source.
        val original = """
            [00:00.724]ピースサイン - 米津玄師 (よねづ けんし)
            [00:06.932]词：米津玄師
            [00:10.892]曲：米津玄師
        """.trimIndent()
        val tvEdit = """
            [00:00.710]ピースサイン (TV edit.) - 米津玄師 (よねづ けんし)
            [00:05.384]词：米津玄師
            [00:08.294]曲：米津玄師
            [00:11.518]编曲：米津玄師
        """.trimIndent()
        val cases = listOf(Triple(original, "00:18.955", 18955L), Triple(tvEdit, "00:19.078", 19078L))
        for ((credits, tag, first) in cases) {
            for ((title, artist) in listOf("Peace Sign" to "Kenshi Yonezu", "V字手势" to "米津玄师")) {
                val payload = JSONObject().put("songName", title).put("artist", artist)
                    .put("lyric", "$credits\n[$tag]新しい歌")
                    .put("rawLyric", "$credits\n[$tag]<$tag>新しい<00:19.500>歌<00:20.000>")
                    .put("translationLyric", "[$tag]新的歌")
                val timeline = checkNotNull(LyricPayload.parse(payload.toString(), title, true, artist))
                val presentation = LyricPresentation(timeline, enabled)
                assertEquals(-1, presentation.indexAt(first - 1))
                assertEquals(first, presentation.introAt(first - 1)?.endMs)
                val frame = checkNotNull(presentation.frameAt(0, 25000L))
                assertEquals(first, frame.displayStartMs)
                assertEquals("新しい歌", frame.rows.first)
                assertEquals("新的歌", frame.rows.second)
                assertEquals(listOf(LyricWord(0, 3, first, 19500L), LyricWord(3, 4, 19500L, 20000L)), frame.rows.firstWords)
                val withoutFilter = LyricPresentation(timeline, enabled.copy(introDots = false))
                assertEquals(timeline.lines.first().text, withoutFilter.lines.first().text)
            }
        }
    }

    @Test fun unrelatedOpeningLineNeedsBothMatchingSongwritingCreditsToBeTreatedAsAHeader() {
        val opening = "月光 - 夢"
        for (source in listOf(
            "[00:00]$opening\n[00:02]词：夢\n[00:04]曲：甲",
            "[00:00]$opening\n[00:02]词：夢\n[00:04]作詞：夢",
            "[00:00]$opening\n[00:02]落下脚步\n[00:04]词：夢\n[00:05]曲：夢",
            "[00:05]$opening\n[00:06]词：夢\n[00:07]曲：夢",
        )) {
            val timeline = lyrics("$source\n[00:10]灯还是亮着")
            assertEquals(opening, LyricPresentation(timeline, enabled).lines.first().text)
        }
    }

    @Test fun qrcOpeningCreditsMatchTheTitleWithoutADuplicatedFeaturingSuffix() {
        val title = "ありふれたせかいせいふく feat.初音ミク (feat. 初音ミク)"
        val artist = "ピノキオP"
        // QQ Music 236163301: opening credits and timestamps; sung text is synthetic.
        val header = "ありふれたせかいせいふく - ピノキオP (匹诺曹P)/初音ミク (初音未来)"
        val payload = JSONObject().put("songName", title).put("artist", artist).put("lyric", """
            [00:00.616]$header
            [00:06.181]词：ピノキオP
            [00:07.476]曲：ピノキオP
            [00:08.958]编曲：ピノキオP
            [00:26.116]<00:26.116>歌い<00:27.000>出す<00:28.867>
            [00:28.867]次の言葉
        """.trimIndent())
        val timeline = checkNotNull(LyricPayload.parse(payload.toString(), title, true, artist))
        val on = LyricPresentation(timeline, enabled)
        assertEquals(26116L, on.nextBoundary(-1))
        assertEquals(26116L, on.introAt(26115L)?.endMs)
        assertNull(on.introAt(26116L))
        assertEquals("歌い出す", on.frameAt(on.indexAt(26116L), 40000L)?.rows?.first)
        assertEquals(26116L, on.lines.first().words.first().startMs)
        assertEquals(header, LyricPresentation(timeline, enabled.copy(introDots = false)).lines.first().text)
    }

    @Test fun featuringWordInTheSongNameDoesNotMatchAnUnrelatedHeader() {
        val timeline = lyrics("[00:00]A - 甲\n[00:01]作曲：乙\n[00:05]歌唱", title = "A Feat of Courage")
        assertEquals("A - 甲", LyricPresentation(timeline, enabled).lines.first().text)
    }

    @Test fun recognizesBilingualCompoundAndUnpunctuatedProductionCredits() {
        val timeline = lyrics("""
            [00:00]《远·方》—未登记的艺名
            [00:01]作詞・作曲 甲
            [00:02]【录音棚 Recording Studio＝示例录音室】
            [00:03]弦乐监制 Strings Conductor：乙
            [00:04]混音 Mixing／母带 Master — 丙
            [00:05]作曲（寄件人）：丁
            [00:06]Lyrics by A. Writer
            [00:07]项目统筹 Coordinated Production：戊
            [00:08]【本歌曲的歌词翻译版权由示例工作室享有。】
            [00:12]夜里灯火亮着
            [00:14]Lyrics come to mind
            [00:18]制作人：这下你听见了
        """)
        val on = LyricPresentation(timeline, enabled)
        assertEquals(12000L, on.introAt(11999L)?.endMs)
        assertEquals(listOf("夜里灯火亮着", "Lyrics come to mind", "制作人：这下你听见了"), on.lines.map { it.text })
        assertSame(timeline.lines, LyricPresentation(timeline, enabled.copy(introDots = false)).lines)
    }

    @Test fun titleHeaderRecognizesAliasAndArtistFirstWithoutASeparateCredit() {
        for (header in listOf("《远·方！》 | Lanndo", "乙 feat. 甲 / 远方", "远方 (Acoustic) — 乙 & Lanndo")) {
            val timeline = lyrics("[00:00]$header\n[00:07]夜里灯火亮着", artist = "甲 (Lanndo)、乙")
            val on = LyricPresentation(timeline, enabled)
            assertEquals(header, 7000L, on.nextBoundary(-1))
            assertEquals(header, "夜里灯火亮着", on.frameAt(0, 10000L)?.rows?.first)
        }
    }

    @Test fun flexibleTranslationCreditsDoNotHideSpokenLinesOrReplaceFirstTranslation() {
        val timeline = checkNotNull(Timeline.parse(
            "[00:00]作词 — 示例作者\n[00:08]First\n[00:10]Second", "",
            """
                [00:02]Translated by K
                [00:08]第一句
                [00:08]翻译贡献者 · 罗马音：示例组
                [00:08]本翻译作品的著作权由示例组持有
                [00:10]（独白）
            """.trimIndent(),
        ))
        val on = LyricPresentation(timeline, enabled)
        assertEquals(8000L, on.nextBoundary(-1))
        assertEquals(listOf("第一句", "（独白）"), on.lines.map { it.translation })
    }

    @Test fun switchingOffRestoresTheSameOriginalRowsAndBoundaries() {
        val timeline = lyrics("""
            [00:00]远方 - 甲
            [00:01]作曲：乙
            [00:06]远方
            [00:09]听见了回声
        """)
        val before = LyricPresentation(timeline, enabled.copy(introDots = false))
        val on = LyricPresentation(timeline, enabled)
        val off = LyricPresentation(timeline, enabled.copy(introDots = false))
        assertSame(timeline.lines, before.lines)
        assertSame(before.lines, off.lines)
        assertSame(on.lines, LyricPresentation(timeline, enabled).lines)
        assertEquals(1000L, off.nextBoundary(0))
        assertEquals("作曲：乙", off.frameAt(off.indexAt(1500L), 10000L)?.rows?.first)
        assertEquals("远方", on.frameAt(on.indexAt(6000L), 10000L)?.rows?.first)
        assertNull(off.introAt(1500L))
    }

    @Test fun bareTitleAndSeparateArtistNeedAFollowingCredit() {
        val timeline = lyrics("""
            [00:00]远方
            [00:01]甲
            [00:02]詞／曲：乙
            [00:05]远方
            [00:07]灯还是亮着
        """)
        val presentation = LyricPresentation(timeline, enabled)
        assertEquals(5000L, presentation.nextBoundary(-1))
        assertEquals("远方", presentation.frameAt(0, 10000L)?.rows?.first)
    }

    @Test fun uncertainOpeningAndTrackHeaderWithWrongArtistStayVisible() {
        for (opening in listOf("远方", "远方 - 另一个人", "作词是我的爱好", "歌手说：等我回来")) {
            val timeline = lyrics("[00:01]$opening\n[00:03]听见了回声\n[00:06]作曲：乙")
            val presentation = LyricPresentation(timeline, enabled)
            assertSame(timeline.lines, presentation.lines)
            assertNull(presentation.introAt(0L))
            assertEquals(1000L, presentation.nextBoundary(-1))
            assertEquals(opening, presentation.frameAt(0, 10000L)?.rows?.first)
        }
    }

    @Test fun prefixedSongTitleCanBeTheFirstSungLineAfterCredits() {
        val timeline = lyrics("[00:02]作曲: 甲\n[00:03]远方\n[00:06]夜正长")
        val presentation = LyricPresentation(timeline, enabled)
        assertEquals(3000L, presentation.introAt(2999L)?.endMs)
        assertEquals("远方", presentation.frameAt(0, 10000L)?.rows?.first)
    }

    @Test fun creditOnlyTimelineHasNoCountdownOrNextBoundary() {
        val timeline = lyrics("[00:00]远方 - 甲\n[00:01]Lyrics by: 乙\n[00:02]制作人: 丙\n[00:03.00]")
        val presentation = LyricPresentation(timeline, enabled)
        assertTrue(presentation.lines.isEmpty())
        assertEquals(-1, presentation.indexAt(0L))
        assertNull(presentation.introAt(0L))
        assertNull(presentation.nextBoundary(-1))
        assertNull(presentation.frameAt(0, 10000L))
    }

    @Test fun exactCopyrightAndDuplicateCreditsDoNotReplaceTheRealTranslation() {
        val timeline = checkNotNull(Timeline.parse(
            "[00:01]作曲：甲\n[00:08]First\n[00:12]Second\n[00:16]Third",
            "",
            """
                [00:08.060]第一句
                [00:08]QQ音乐享有本翻译作品的著作权。
                [00:08]译者：乙
                [00:12]第二句
                [00:12]本歌曲翻译版权归示例译者所有
                [00:16]第三句
                [00:16]//
            """.trimIndent(),
        ))
        val on = LyricPresentation(timeline, enabled)
        assertEquals(8000L, on.introAt(7999L)?.endMs)
        assertEquals(listOf("第一句", "第二句", "第三句"), on.lines.map { it.translation })
        val off = LyricPresentation(timeline, enabled.copy(introDots = false))
        assertEquals(listOf(null, "译者：乙", "本歌曲翻译版权归示例译者所有", "//"), off.lines.map { it.translation })
    }

    @Test fun realTranslationSurvivesAContributorWithTheSameTimestampInEitherOrder() {
        for (entries in listOf(
            "[00:08]Translation by: A\n[00:08]第一句",
            "[00:08]第一句\n[00:08]Translation by: A",
        )) {
            val timeline = checkNotNull(Timeline.parse("[00:08]First\n[00:10]Second", "", entries))
            assertEquals("第一句", LyricPresentation(timeline, enabled).frameAt(0, 15000L)?.rows?.second)
        }
    }

    @Test fun holderFirstCopyrightCannotOverwriteTimedTranslations() {
        for (notice in listOf(
            "示例工作室享有本翻译作品的著作权",
            "【版權聲明：例子翻譯組 享有 本翻譯作品的著作權】。",
        )) {
            val payload = JSONObject()
                .put("lyric", "[00:02]$notice\n[00:08]First\n[00:12]Second\n[00:16]Third")
                .put("translationLyric", """
                    [00:02]$notice
                    [00:08]第一句
                    [00:08]$notice
                    [00:12]$notice
                    [00:12.060]第二句
                    [00:16]她问谁享有本翻译作品的著作权？
                """.trimIndent())
            val timeline = checkNotNull(LyricPayload.parse(payload.toString(), null, false))
            val on = LyricPresentation(timeline, enabled)
            assertEquals(notice, 8000L, on.introAt(7999L)?.endMs)
            assertEquals(notice, listOf("第一句", "第二句", "她问谁享有本翻译作品的著作权？"),
                on.lines.map { it.translation })
            val off = LyricPresentation(timeline, enabled.copy(introDots = false))
            assertEquals(notice, off.lines.first().text)
            assertEquals(notice, off.lines[1].translation)
        }
    }

    @Test fun aCopyrightRowInTheOriginalDoesNotStealANearbyFirstTranslation() {
        val timeline = checkNotNull(Timeline.parse(
            "[00:07.940]示例工作室享有本翻译作品的著作权\n[00:08]First\n[00:12]Second",
            "",
            "[00:07.960]第一句\n[00:12]第二句",
        ))
        val on = LyricPresentation(timeline, enabled)
        assertEquals(8000L, on.nextBoundary(-1))
        assertEquals(LyricRows("First", "第一句"), on.frameAt(on.indexAt(8000L), 16000L)?.rows)
        assertEquals(LyricRows("Second", "第二句"), on.frameAt(on.indexAt(12000L), 16000L)?.rows)
    }

    @Test fun placeholdersUseTheExistingMissingTranslationMode() {
        val timeline = checkNotNull(Timeline.parse(
            "[00:08]First\n[00:12]Second\n[00:16]Third", "",
            "[00:08]第二排只是第一句的译文\n[00:12] // \n[00:16]最后一句",
        ))
        val singleFallback = LyricPresentation(timeline, enabled)
        assertEquals(LyricRows("Second", missingTranslationSingleLine = true), singleFallback.frameAt(1, 20000L)?.rows)
        val doubleFallback = LyricPresentation(timeline, enabled.copy(
            missingTranslationMode = MissingTranslationMode.DOUBLE_LYRICS,
        ))
        assertEquals("Third", doubleFallback.frameAt(1, 20000L)?.rows?.second)
    }

    @Test fun openingContributorAndRightsLinesAreSkippedButSimilarLyricTextIsNot() {
        val timeline = lyrics("""
            [00:00]未经著作权人许可，不得翻唱、翻录或使用
            [00:01]歌词提供：甲
            [00:02]LRC 制作 / 校对：乙
            [00:03]打轴：丙
            [00:04]［by:丁］
            [00:12]（合唱）
            [00:13]我想翻译你的梦
            [00:14]校对：故事的一部分
            [00:15]//
        """)
        val presentation = LyricPresentation(timeline, enabled)
        assertEquals(12000L, presentation.nextBoundary(-1))
        assertEquals(listOf("（合唱）", "我想翻译你的梦", "校对：故事的一部分", "//"), presentation.lines.map { it.text })
    }

    @Test fun instrumentalNoticeDoesNotCreateAnEndlessCountdown() {
        val timeline = lyrics("[00:00]作曲：甲\n[00:03]此歌曲为没有填词的纯音乐，请您欣赏。\n[00:13]")
        val presentation = LyricPresentation(timeline, enabled)
        assertTrue(presentation.lines.isEmpty())
        assertNull(presentation.introAt(0L))
        assertNull(presentation.nextBoundary(-1))
        assertEquals(-1, presentation.indexAt(15000L))
    }

    @Test fun narratedOrTranslatedContentIsKept() {
        val timeline = checkNotNull(Timeline.parse(
            "[00:01]作词：甲\n[00:09]（独白）\n[00:12]啦啦啦\n[00:16]First",
            "",
            "[00:08]歌词翻译：乙\n[00:09]我把那句话翻译成风\n[00:12]（齐唱）\n[00:16]他说翻译版权归谁所有都不重要",
        ))
        val presentation = LyricPresentation(timeline, enabled)
        assertEquals(9000L, presentation.nextBoundary(-1))
        assertEquals(listOf("（独白）", "啦啦啦", "First"), presentation.lines.map { it.text })
        assertEquals(listOf("我把那句话翻译成风", "（齐唱）", "他说翻译版权归谁所有都不重要"),
            presentation.lines.map { it.translation })
    }
}
