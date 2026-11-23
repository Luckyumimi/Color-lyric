package shop.terramoor.github.io.lyrics

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/** Reads Provider TTML word spans without changing the existing line or translation sources. */
internal object TtmlWords {
    private val auxiliaryRoles = setOf("x-bg", "x-translation", "x-roman", "translation", "romanization", "background")
    private data class Span(val depth: Int, val from: Int, val start: Long, val end: Long?)

    fun parse(text: String): Map<Long, LyricWordLine> {
        if (!text.contains("span") || text.contains("<!DOCTYPE", ignoreCase = true)) return emptyMap()
        return runCatching {
            val parser = Xml.newPullParser().apply { setInput(text.reader()) }
            val lines = HashMap<Long, LyricWordLine>()
            val starts = mutableListOf(0L)
            val line = StringBuilder()
            val words = ArrayList<LyricWord>()
            var lineDepth = 0
            var lineStart = 0L
            var bodyDepth = 0
            var skipDepth = 0
            var span: Span? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && lines.size < 4000) {
                val depth = parser.depth
                when (event) {
                    XmlPullParser.START_TAG -> {
                        require(depth <= 64)
                        val parent = starts.last()
                        val begin = parser.getAttributeValue(null, "begin")
                        val start = timeInSong(begin, parent)
                        starts.add(start ?: parent)
                        if (parser.name == "body") bodyDepth = depth
                        if (bodyDepth != 0 && skipDepth == 0 && isAuxiliary(parser)) skipDepth = depth
                        if (bodyDepth != 0 && skipDepth == 0 && parser.name == "p" && lineDepth == 0 && start != null) {
                            lineDepth = depth
                            lineStart = start
                            line.setLength(0)
                            words.clear()
                        } else if (lineDepth != 0 && skipDepth == 0 && parser.name == "span" && span == null && start != null) {
                            val end = timeInSong(parser.getAttributeValue(null, "end"), parent)
                                ?: TtmlEnds.timestamp(parser.getAttributeValue(null, "dur"))?.plus(start)
                            span = Span(depth, line.length, start, end)
                        }
                    }
                    XmlPullParser.TEXT -> if (lineDepth != 0 && skipDepth == 0) line.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        span?.takeIf { it.depth == depth }?.let {
                            if (line.length > it.from) words += LyricWord(it.from, line.length, it.start, it.end)
                            span = null
                        }
                        if (lineDepth == depth) {
                            if (line.length <= 8192 && words.size in 1..512) {
                                lines.putIfAbsent(lineStart, LyricWordLine(line.toString(), words.toList()))
                            }
                            lineDepth = 0
                        }
                        if (skipDepth == depth) skipDepth = 0
                        if (bodyDepth == depth) bodyDepth = 0
                        starts.removeAt(starts.lastIndex)
                    }
                }
                event = parser.next()
            }
            lines
        }.getOrDefault(emptyMap())
    }

    private fun timeInSong(value: String?, parent: Long): Long? = TtmlEnds.timestamp(value)?.let {
        // Provider clock expressions already carry the song time. Offset units inherit the parent.
        if (value?.contains(':') == true) it else parent + it
    }

    private fun isAuxiliary(parser: XmlPullParser): Boolean = (0 until parser.attributeCount).any { index ->
        parser.getAttributeName(index) == "role" && parser.getAttributeValue(index) in auxiliaryRoles
    }
}
