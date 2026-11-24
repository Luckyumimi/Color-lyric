package shop.terramoor.github.io.lyrics

import java.text.Normalizer
import java.util.Locale

/** Recognizes the leading metadata block after the provider has flattened it into lyric rows. */
internal object IntroLyricCredits {
    private val creditLabels = setOf(
        "词", "詞", "曲", "词曲", "詞曲", "作词", "作詞", "作曲", "作词作曲", "作詞作曲", "填词", "填詞",
        "原曲", "编曲", "編曲", "歌名", "歌曲名", "曲名", "歌手", "演唱", "原唱", "专辑", "專輯",
        "制作", "製作", "制作人", "製作人", "配唱制作人", "专辑制作人", "执行制作", "制作公司", "制作团队",
        "制作统筹", "词曲统筹", "混音", "混音师", "母带", "母帶", "母带后制", "母带工程师", "混音母带",
        "录音", "錄音", "录音师", "录音室", "和声", "和聲", "和声编写", "合声", "音频编辑",
        "吉他", "贝斯", "键盘", "钢琴", "弦乐", "二胡", "古筝", "监制", "出品", "出品人", "统筹", "厂牌",
        "策划", "推广", "宣发", "宣发总监", "宣发策划", "音乐总监", "音响总监", "op", "sp",
        "title", "songtitle", "artist", "album", "lyrics", "lyricist", "lyricsby", "writtenby", "composer",
        "composedby", "musicby", "arranger", "arrangement", "arrangedby", "producer", "producedby",
        "mixedby", "masteredby", "vocalsby",
        "翻译", "翻譯", "译者", "譯者", "翻译者", "翻譯者", "歌词翻译", "歌詞翻譯", "译制", "譯製",
        "翻译校对", "翻譯校對", "中文字幕", "字幕制作", "字幕製作", "translationby", "translatedby",
        "歌词提供", "歌詞提供", "供词", "供詞", "歌词来源", "歌詞來源", "歌词制作", "歌詞製作", "lrc制作", "lrc製作",
        "歌词录入", "歌詞錄入",
        "校对", "校對", "歌词校对", "歌詞校對", "打轴", "打軸", "时轴", "時軸", "时间轴", "時間軸",
        "词作者", "詞作者", "曲作者", "作词人", "作詞人", "作曲人", "编曲人", "編曲人", "作詞者", "作曲者",
        "原词", "原詞", "原曲词", "原曲詞", "中文词", "中文詞", "唄", "歌唱", "主唱", "伴唱", "合音", "配唱",
        "录音棚", "錄音棚", "录制", "錄製", "弦乐团", "弦樂團", "弦乐录音", "弦乐录制", "弦乐编写", "弦乐监制",
        "音乐制作助理", "音乐统筹", "音乐发行", "制作发行", "制作指导", "总监制", "项目统筹", "项目企划", "项目总监",
        "发行", "發行", "发行人", "发行公司", "歌曲发行", "音乐工程", "封面设计", "企划", "营销推广", "营销策划",
        "歌词贡献者", "歌詞貢獻者", "翻译贡献者", "翻譯貢獻者", "音译", "音譯", "罗马音", "羅馬音", "romanization",
        "music", "wordsby", "vocal", "vocals", "sungby", "featuring", "performedby", "translation",
        "executiveproducer", "backingvocal", "backgroundvocal", "gangvocals", "mix", "mixing", "mixengineer", "mixingengineer",
        "master", "mastering", "masteringengineer", "recording", "recordingengineer", "recordingstudio", "programming",
        "guitar", "bass", "keyboard", "string", "strings", "stringsconductor", "stringrecorder", "stringsarrangedby",
        "stringsarrangement", "stringsdirectedby", "stringsrecordedby", "musicdirector", "productionassistant",
        "coordinatedproduction", "coordinator", "supervisesinging", "directedby", "assistantdirector", "digitalediting",
    )
    private val spaceOnlyLabels = setOf(
        "作词", "作詞", "作曲", "词曲", "詞曲", "作词作曲", "作詞作曲", "编曲", "編曲", "制作人", "製作人", "混音师", "錄音師", "录音师",
        "lyricsby", "wordsby", "musicby", "writtenby", "composedby", "arrangedby", "producedby", "sungby", "vocalsby",
        "performedby", "translatedby", "translationby", "mixedby", "masteredby",
    )

    private val titleSeparator = Regex("[-–—|/•·:]| by ")
    private val creditSeparator = Regex("[:=–—]| - ")
    private val headerDashes = setOf("-", "–", "—")
    private val headerLyricistLabels = setOf("词", "詞", "作词", "作詞", "lyricist", "lyricsby", "wordsby")
    private val headerComposerLabels = setOf("曲", "作曲", "composer", "composedby", "musicby")
    private val roleSeparator = Regex("[/&+、・·]")
    private val bilingualBoundary = Regex("(?<=\\p{IsHan})(?=[a-z])")
    private val qualifier = Regex("(?:\\s*\\([^()]+\\))+$")
    private val featuringSuffix = Regex("""\s+(?:(?:feat|ft)\.\s*|featuring\s+)[^()\s][^()]*$""")
    private val artistSeparator = Regex("[,/&、×;]| f(?:ea)?t\\.? ")
    private val embeddedAlias = Regex("\\(([^()]+)\\)")
    private val nonNameChars = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val spaces = Regex("\\s+")
    private val rightsNotices = setOf(
        "未经著作权人许可,不得翻唱、翻录或使用", "未經著作權人許可,不得翻唱、翻錄或使用",
    )
    private val translationRights = Regex(
        """(?:(?:本|[该該])(?:歌曲|首|曲)?(?:的)?)?(?:歌[词詞])?翻[译譯](?:作品)?(?:的)?(?:版[权權]|著作[权權])\s*:?\s*(?:均)?(?:[归歸][^。!?;:]{1,64}(?:所有|持有)|由[^。!?;:]{1,64}(?:享有|持有|所有))""",
    )
    private val holderFirstTranslationRights = Regex(
        """[^。!?;:,]{1,128}?\s*享有\s*(?:本|[该該])\s*翻[译譯]\s*作品\s*(?:的\s*)?(?:著作[权權]|版[权權])""",
    )
    private val instrumentalNotices = setOf(
        "纯音乐,请欣赏", "純音樂,請欣賞", "纯音乐,无歌词", "純音樂,無歌詞",
        "此歌曲为没有填词的纯音乐,请您欣赏", "此歌曲為沒有填詞的純音樂,請您欣賞",
    )

    fun prefixSize(lines: List<LyricLine>, titles: List<String>, artist: String?): Int {
        val trackTitles = titles.flatMap { titleKeys(normalize(it)) }.toSet()
        val trackArtists = artistKeys(normalize(artist.orEmpty())).toSet()
        fun matchesTitle(value: String): Boolean = titleKeys(value).any { it in trackTitles }
        fun matchesArtist(value: String): Boolean = artistKeys(value).any { it in trackArtists }
        fun nextNonblank(after: Int): Int {
            var index = after + 1
            while (index < lines.size && lines[index].text.isBlank()) index++
            return index
        }
        fun isCreditAt(index: Int): Boolean = lines.getOrNull(index)?.text?.let(::normalize)?.let(::isCredit) == true

        val opening = nextNonblank(-1)
        var index = 0
        while (index < lines.size) {
            val text = normalize(lines[index].text)
            if (text.isEmpty() || isOpeningMetadata(text)) {
                index++
                continue
            }
            val next = nextNonblank(index)
            val followedByCredit = isCreditAt(next)
            val header = titleSeparator.findAll(text).any { separator ->
                val left = text.substring(0, separator.range.first).trimEnd()
                val right = text.substring(separator.range.last + 1).trimStart()
                (matchesTitle(left) && (matchesArtist(right) ||
                    (index == opening && followedByCredit && separator.value != ":" && right.isNotEmpty()))) ||
                    (matchesArtist(left) && matchesTitle(right)) ||
                    // A provider can label the same track in another language. QQ's opening
                    // title row is corroborated by separate lyricist and composer credits.
                    (index == opening && lines[index].startMs <= 3_000L && separator.value in headerDashes &&
                        nameKey(left).isNotEmpty() && hasOpeningCreditsForArtist(lines, next, right))
            }
            if (header) {
                index++
                continue
            }
            if (matchesTitle(text)) {
                // A sung first line can be the title. Only remove a bare title immediately before credits.
                if (followedByCredit) {
                    index = next
                    continue
                }
                val separateArtist = lines.getOrNull(next)?.text?.let(::normalize)
                if (separateArtist != null && matchesArtist(separateArtist) && isCreditAt(nextNonblank(next))) {
                    index = next + 1
                    continue
                }
            }
            break
        }
        return index
    }

    private fun hasOpeningCreditsForArtist(lines: List<LyricLine>, start: Int, artist: String): Boolean {
        val creditedArtists = artistKeys(artist).toSet()
        if (creditedArtists.isEmpty()) return false
        var hasLyricist = false
        var hasComposer = false
        for (index in start until lines.size) {
            val line = lines[index]
            if (line.startMs > 30_000L) break
            val text = normalize(line.text)
            if (text.isBlank()) continue
            if (!isOpeningMetadata(text)) break
            val content = unwrap(text)
            val separator = creditSeparator.find(content) ?: continue
            val label = nameKey(content.substring(0, separator.range.first))
            if (label !in headerLyricistLabels && label !in headerComposerLabels) continue
            val name = content.substring(separator.range.last + 1).trim()
            if (artistKeys(name).none { it in creditedArtists }) continue
            if (label in headerLyricistLabels) hasLyricist = true
            if (label in headerComposerLabels) hasComposer = true
            if (hasLyricist && hasComposer) return true
        }
        return false
    }

    fun skipTranslationRow(value: String): Boolean {
        val text = normalize(value)
        return text.isEmpty() || text == "//" || isRightsNotice(text)
    }

    fun isOpeningTranslationCredit(value: String): Boolean = isOpeningMetadata(normalize(value))

    fun isCopyrightNotice(value: String): Boolean = isRightsNotice(normalize(value))

    private fun isOpeningMetadata(text: String): Boolean = isCredit(text) || isRightsNotice(text) ||
        text.removeSurrounding("[", "]").removeSurrounding("(", ")").trim().trimEnd('。', '.', '!', ' ') in instrumentalNotices

    private fun isRightsNotice(text: String): Boolean {
        val notice = unwrap(text.trimEnd('。', '.', ' ', '!'))
            .removePrefix("版权声明:").removePrefix("版權聲明:").trim().let(::unwrap).trimEnd('。', '.', ' ', '!')
        return notice in rightsNotices || translationRights.matches(notice) || holderFirstTranslationRights.matches(notice)
    }

    private fun isCredit(text: String): Boolean {
        val content = unwrap(text)
        val separator = creditSeparator.find(content)?.takeIf { it.range.first in 1..48 }
        if (separator != null && content.substring(separator.range.last + 1).isNotBlank()) {
            val label = content.substring(0, separator.range.first).trimEnd()
            if ((label == "by" && content != text) || isCreditLabel(label)) return true
        }
        // Only distinctive roles can omit punctuation; words like "lyrics", "artist" and "曲" need a separator.
        return content.take(49).indices.any { boundary ->
            content[boundary] == ' ' && boundary < content.lastIndex &&
                nameKey(content.substring(0, boundary)) in spaceOnlyLabels
        }
    }

    private fun isCreditLabel(label: String): Boolean {
        if (nameKey(label) in creditLabels) return true
        return label.split(roleSeparator).all { part ->
            val compact = nameKey(part)
            val base = nameKey(part.trim().replace(qualifier, ""))
            val bilingual = compact.split(bilingualBoundary)
            compact in creditLabels || (base.length >= 2 && base in creditLabels) ||
                (bilingual.size == 2 && bilingual.all { it in creditLabels })
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(spaces, " ").trim()

    private fun unwrap(value: String): String = value.removeSurrounding("[", "]").removeSurrounding("(", ")")
        .removeSurrounding("【", "】").removeSurrounding("「", "」").trim()

    private fun nameKey(value: String): String = nonNameChars.replace(value, "")

    private fun nameKeys(value: String): List<String> {
        val title = unwrap(value.trim()).removeSurrounding("《", "》")
        return listOf(nameKey(title), nameKey(title.replace(qualifier, ""))).filter(String::isNotEmpty)
    }

    private fun titleKeys(value: String): List<String> {
        val title = unwrap(value.trim()).removeSurrounding("《", "》")
        val bare = title.replace(qualifier, "")
        val withoutFeaturing = bare.replace(featuringSuffix, "")
        val keys = nameKeys(title)
        return if (bare == withoutFeaturing) keys else keys + nameKeys(withoutFeaturing)
    }

    private fun artistKeys(value: String): List<String> = nameKeys(value) + value.split(artistSeparator).flatMap { part ->
        nameKeys(part) + embeddedAlias.findAll(part).map { nameKey(it.groupValues[1]) }.filter(String::isNotEmpty).toList()
    }
}
