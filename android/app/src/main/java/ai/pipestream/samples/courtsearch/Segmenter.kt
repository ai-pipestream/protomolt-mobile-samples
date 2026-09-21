package ai.pipestream.samples.courtsearch

/**
 * Cuts an opinion into paragraphs and sentences for the heatmap. A port of
 * tools/passages_reference.py, which is the specification: every platform must
 * cut identically, because each unit gets its own vector, and the tests assert
 * the unit count that script prints. ASCII rules on purpose, so that no
 * platform's Unicode tables can make them disagree.
 */
object Segmenter {
    /** Shorter sentences are merged into their neighbour; shorter units are not embedded. */
    const val MINIMUM_UNIT = 40

    private val abbreviations = setOf(
        "v", "vs", "no", "nos", "cir", "inc", "co", "corp", "ltd", "llc", "supp", "stat", "sec", "art", "cf", "id",
        "ibid", "see", "mr", "mrs", "ms", "dr", "hon", "jr", "sr", "st", "ch", "para", "p", "pp", "n", "al", "ed", "op",
        "cit", "app", "mass", "cal", "ins", "cas", "assoc", "bros", "mfg", "dist", "div", "dep't", "gov't", "ass'n",
        "int'l", "nat'l", "e.g", "i.e", "u.s", "u.s.c", "r.i")
    private const val OPENERS = "\"'([\u201C\u2018"
    private const val CLOSERS = "\"')]\u201D\u2019"
    private val pageMarker = Regex("-\\s?\\d+\\s?-")
    private val whitespace = Regex("\\s+")

    /**
     * The source is a double-spaced PDF extraction: every LINE is followed by a
     * blank line, so blank lines mean nothing, and a paragraph starts where a line
     * is indented six spaces or more. Page markers ("-7-") are dropped.
     */
    fun paragraphs(body: String): List<String> {
        val out = mutableListOf<String>()
        val current = mutableListOf<String>()
        for (line in body.split("\n")) {
            val text = line.trim().split(whitespace).filter { it.isNotEmpty() }.joinToString(" ")
            if (text.isEmpty() || pageMarker.matches(text)) continue
            if (line.length - line.trimStart(' ').length >= 6 && current.isNotEmpty()) {
                out += current.joinToString(" "); current.clear()
            }
            current += text
        }
        if (current.isNotEmpty()) out += current.joinToString(" ")
        return out
    }

    fun sentences(paragraph: String): List<String> {
        // Code points, as the reference counts them, not UTF-16 units.
        val text = paragraph.codePoints().toArray()
        val count = text.size
        val cuts = mutableListOf<Int>()
        var i = 0
        while (i < count) {
            if (text[i] != '.'.code && text[i] != '?'.code && text[i] != '!'.code) { i++; continue }
            var j = i + 1
            while (j < count && CLOSERS.indexOf(text[j].toChar()) >= 0 && text[j] < 0x10000) j++
            if (j < count && text[j] == ' '.code && j + 1 < count) {
                val next = text[j + 1]
                val starts = next in 'A'.code..'Z'.code || (next < 0x10000 && OPENERS.indexOf(next.toChar()) >= 0)
                var k = i
                while (k > 0 && text[k - 1] != ' '.code) k--
                var from = k
                while (from < i && text[from] < 0x10000 && OPENERS.indexOf(text[from].toChar()) >= 0) from++
                val word = String(text, from, i - from).lowercase()
                val abbreviation = text[i] == '.'.code && (i - from <= 1 || word in abbreviations)
                if (starts && !abbreviation) cuts += j
            }
            i = j
        }
        val parts = mutableListOf<IntArray>()
        var start = 0
        for (cut in cuts) { parts += text.copyOfRange(start, cut); start = cut + 1 }
        parts += text.copyOfRange(start, count)

        val merged = mutableListOf<IntArray>()
        for (part in parts) {
            val last = merged.lastOrNull()
            if (last != null && (part.size < MINIMUM_UNIT || last.size < MINIMUM_UNIT)) {
                merged[merged.lastIndex] = last + intArrayOf(' '.code) + part
            } else merged += part
        }
        return merged.map { String(it, 0, it.size) }
    }
}
