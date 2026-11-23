package shop.terramoor.github.io.lyrics

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/** TTML is timing metadata here. Untimed TTML still falls back to track info. */
internal object TtmlEnds {
    private val clock = Regex("""(?:(\d{1,4}):)?(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?""")
    private val offset = Regex("""(\d{1,7}(?:\.\d{1,3})?)(ms|s)""")

    fun parse(text: String): Map<Long, Long> {
        if (text.isBlank() || text.contains("<!DOCTYPE", ignoreCase = true)) return emptyMap()
        return runCatching {
            val parser = Xml.newPullParser().apply { setInput(text.reader()) }
            val ends = mutableMapOf<Long, Long>()
            val inheritedStarts = mutableListOf(0L)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && ends.size < 4000) {
                if (event == XmlPullParser.START_TAG) {
                    require(parser.depth <= 64)
                    val parent = inheritedStarts.last()
                    val explicitStart = timestamp(parser.getAttributeValue(null, "begin"))
                    val start = parent + (explicitStart ?: 0L)
                    inheritedStarts.add(start)
                    if (parser.name == "p" && explicitStart != null) {
                        val end = timestamp(parser.getAttributeValue(null, "end"))?.plus(parent)
                            ?: timestamp(parser.getAttributeValue(null, "dur"))?.plus(start)
                        if (end != null && end > start) ends.putIfAbsent(start, end)
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    inheritedStarts.removeAt(inheritedStarts.lastIndex)
                }
                event = parser.next()
            }
            ends
        }.getOrDefault(emptyMap())
    }

    internal fun timestamp(value: String?): Long? {
        if (value == null) return null
        clock.matchEntire(value)?.destructured?.let { (hour, minute, second, fraction) ->
            if (second.toInt() >= 60 || (hour.isNotEmpty() && minute.toInt() >= 60)) return null
            val seconds = (hour.toLongOrNull() ?: 0) * 3600 + minute.toLong() * 60 + second.toLong()
            return seconds * 1000 + fraction.padEnd(3, '0').toLong()
        }
        val (number, unit) = offset.matchEntire(value)?.destructured ?: return null
        return (number.toDouble() * if (unit == "s") 1000 else 1).toLong()
    }
}
