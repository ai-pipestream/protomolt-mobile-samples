package ai.pipestream.samples.courtsearch

import ai.protomolt.search.mobile.v1.Mobile
import ai.protomolt.search.v1.Search
import android.content.res.AssetManager
import com.google.protobuf.ByteString
import court.v1.Court
import org.json.JSONObject
import java.io.File

/** One opinion from the bundled fixture, with its precomputed potion-512 vector. */
class Opinion(
    val id: String, val title: String, val body: String, val sourceUri: String,
    val dateFiled: String, val docketNumber: String, val court: String,
    val judges: String, val author: String, val status: String, val embedding: List<Float>,
) {
    /** `No. 08-1855 (1st Cir. Feb. 12, 2010)` */
    val citation: String by lazy {
        val parts = dateFiled.split("-").mapNotNull { it.toIntOrNull() }
        val date = if (parts.size == 3 && parts[1] in 1..12) "${MONTHS[parts[1] - 1]} ${parts[2]}, ${parts[0]}" else dateFiled
        val docket = if (docketNumber.isEmpty()) "" else "No. $docketNumber "
        "$docket(${listOf(court, date).filter { it.isNotEmpty() }.joinToString(" ")})"
    }

    /** `Before Torruella, Selya, Howard`, or null when the source names no panel. */
    val panel: String? get() = judges.takeIf { it.isNotEmpty() }?.let { "Before $it" }

    /** `Howard, J.`, or null when the source names no author. */
    val authorLine: String? get() = author.takeIf { it.isNotEmpty() }?.let { "$it, J." }

    val isUnpublished: Boolean get() = status.equals("Unpublished", ignoreCase = true)

    /** The opinion text as real paragraphs; see [Segmenter.paragraphs]. */
    val paragraphs: List<String> by lazy { Segmenter.paragraphs(body) }

    /** Paragraphs, each cut into sentences: the units the heatmap shades. */
    val sentences: List<List<String>> by lazy { paragraphs.map(Segmenter::sentences) }

    private companion object {
        val MONTHS = listOf("Jan.", "Feb.", "Mar.", "Apr.", "May", "June", "July", "Aug.", "Sept.", "Oct.", "Nov.", "Dec.")
    }
}

internal fun String.collapseWhitespace() = trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

/**
 * A snippet the engine cut around matched words, as alternating plain and
 * highlighted runs. Runs, not offsets: the engine reports UTF-16 offsets into the
 * original text, and collapsing whitespace for display would shift them.
 */
class Snippet(val runs: List<Run>, val cutAtStart: Boolean, val cutAtEnd: Boolean) {
    class Run(val text: String, val highlighted: Boolean)
}

/**
 * What the embedder made of a question: the dense-query counterpart of the keyword
 * path's snippet and match count.
 */
class EmbeddingStats(
    val milliseconds: Double,
    /** WordPiece pieces the model saw, `[UNK]`s included. */
    val pieces: Int,
    val words: Int,
    /**
     * Words the model has no entry for and had to spell out in three or more pieces.
     * WordPiece never gives up on a word (it falls back to single letters), so
     * "unknown" is not a useful idea here; "spelled out" is.
     */
    val spelledOutWords: List<String>,
    val dimensions: Int,
)

/** A sentence's place in an opinion: which paragraph, which sentence of it. */
data class PassageLocation(val paragraph: Int, val sentence: Int)

/** The sentence of an opinion nearest a question, and how near. */
class Passage(val text: String, val location: PassageLocation, val similarity: Float)

/**
 * Per-sentence closeness of one opinion to the last Meaning question: where in the
 * document the meaning was found. `similarities[paragraph][sentence]`; units too
 * short to carry meaning have no value.
 */
class Heat(val question: String, val similarities: List<List<Float?>>, val hottest: PassageLocation?)

/**
 * `snippet` is present on keyword hits only; the engine refuses it on any other
 * query shape. Meaning hits carry `passage` (the nearest sentence) and
 * `closestWords` (the question's words whose own vectors lie nearest this opinion).
 */
class SearchHit(
    val opinion: Opinion, val score: Float, val snippet: Snippet?,
    val closestWords: List<String> = emptyList(), val passage: Passage? = null,
    /** Every snippet the engine cut for this hit; one, unless more were asked for. */
    val snippets: List<Snippet> = emptyList(),
)

/** What the engine reported about one query, plus the app-measured round trip. */
class QueryStats(
    /** The route as the engine names it: `bm25_search`, `search`. */
    val route: String,
    val engineMilliseconds: Double,
    val selectionMilliseconds: Double,
    /** Wall time around the call: protobuf encode/decode and the JNI hop included. */
    val roundTripMilliseconds: Double,
    val hits: Int, val segments: Int, val shards: Int,
    /** Present on Meaning queries: what the embedder made of the question. */
    val embedding: EmbeddingStats? = null,
    /** Highest and lowest similarity among the returned hits. */
    val topScore: Float = 0f, val lowScore: Float = 0f,
)

class SearchResult(val hits: List<SearchHit>, val stats: QueryStats)

/**
 * The on-device embedder, when a model is bundled. `fixtureWorstDelta` is the
 * largest per-component difference between this phone's vectors for the 25 fixture
 * texts and the vectors the Java implementation produced: 0 means the two
 * implementations agree bit for bit on this hardware.
 */
class EmbedderStats(val dimensions: Int, val loadSeconds: Double, val fixtureWorstDelta: Float, val fixtureSeconds: Double)

/** `ingestSeconds` is null when the index already existed and nothing was ingested. */
class IndexStats(
    val documents: Int, val vectorDimensions: Int, val bytesOnDisk: Long,
    val ingestSeconds: Double?, val planFingerprint: String,
)

/**
 * The court sample's private on-device index: plan, mapped ingest, flush, and the
 * demo queries, in the sequence tools/wire-probe verified on the host. Every
 * method blocks; call it off the main thread.
 */
class CourtIndex(assets: AssetManager, directory: File, modelDirectory: File? = null) : AutoCloseable {
    val opinions: List<Opinion> = loadFixture(assets)
    val stats: IndexStats

    /** Null when no model is bundled: search by meaning is then unavailable. */
    val embedderStats: EmbedderStats?
    private val embedder: Embedder?

    /** One vector per sentence, `[opinion][paragraph][sentence]`, built once. */
    private var passageVectors: List<List<List<FloatArray?>>>? = null
    var passageSeconds: Double? = null
        private set
    var passageCount = 0
        private set
    private var lastQuestion: Pair<String, FloatArray>? = null
    private val engine: SearchEngine

    init {
        if (modelDirectory != null) {
            var start = System.nanoTime()
            val loaded = Embedder(modelDirectory)
            val loadSeconds = (System.nanoTime() - start) / 1e9
            start = System.nanoTime()
            var worst = 0f
            for (opinion in opinions) {
                val vector = loaded.embed(embedText(opinion)) ?: continue
                for (i in vector.indices) worst = maxOf(worst, kotlin.math.abs(vector[i] - opinion.embedding[i]))
            }
            embedder = loaded
            embedderStats = EmbedderStats(loaded.dimensions, loadSeconds, worst, (System.nanoTime() - start) / 1e9)
        } else {
            embedder = null
            embedderStats = null
        }
        directory.mkdirs()
        // The engine persists an image plus sidecars (court.tv.live, court.tv.wal, …)
        // and `create` refuses to overwrite any of them, so existence means "any
        // file of this index", not the image alone.
        val exists = directory.list().orEmpty().any { it.startsWith("court.tv") }
        val shard = Mobile.MobileShardConfig.newBuilder()
            .setIndexPath(File(directory, "court.tv").path)
            .addFacetFields("id")
            // Setting this table replaces the engine's ["body"] default. The body column
            // goes first (entry 0 is what an unqualified LexicalQuery searches), then
            // every other text field the plan lands.
            .addBm25Fields("body")
            .addBm25Fields("title")
        engine = SearchEngine(Mobile.MobileOpenRequest.newBuilder().addShards(shard).build(), create = !exists)

        val descriptorSet = ByteString.copyFrom(assets.open("court.desc").use { it.readBytes() })
        // Planning is deterministic and cheap; doing it on every open keeps the
        // fingerprint on hand for the engine panel, not only on first launch.
        val fingerprint = engine.planIndex(
            Search.PlanIndexRequest.newBuilder().setDescriptorSet(descriptorSet).setMessageType(MESSAGE_TYPE).build()
        ).plan.fingerprint
        var seconds: Double? = null
        if (!exists) {
            val start = System.nanoTime()
            ingest(descriptorSet, fingerprint)
            seconds = (System.nanoTime() - start) / 1e9
        }
        stats = IndexStats(
            opinions.size, opinions.firstOrNull()?.embedding?.size ?: 0,
            directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }, seconds, fingerprint)
    }

    /**
     * BM25 over opinion bodies, with type-ahead: the last word is also sent as a
     * prefix. The term dictionary holds stems, so the text leg covers a finished
     * word ("sentencing" → "sentenc") and the prefix leg an unfinished one
     * ("hab" → "habeas"); the engine scores the union.
     */
    fun search(text: String, limit: Int = 5): SearchResult = run(lexical(text), limit, snippets = 1)

    /**
     * The exact words of `opinion` that a keyword query matched, as the engine marked
     * them: "sentence", "sentenced", "sentencing" for `sentencing`. The reading view
     * highlights these. They come from the engine's own highlights (up to 64 snippets
     * of this opinion), so the app never stems or guesses; a form that appears only
     * beyond those snippets is missed.
     */
    fun matchedForms(opinion: Opinion, text: String): List<String> =
        run(lexical(text), opinions.size, snippets = 64).hits.firstOrNull { it.opinion.id == opinion.id }
            ?.snippets.orEmpty().flatMap { it.runs }.filter { it.highlighted }
            .map { it.text.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    private fun lexical(text: String): Search.SearchQuery.Builder {
        val lexical = Search.LexicalQuery.newBuilder().setText(text).setAnalysis(BODY_SPEC)
        val last = text.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
        if (last.isNotEmpty() && !text.last().isWhitespace()) {
            lexical.addPrefixes(Search.TermPrefix.newBuilder().setPrefix(last).setMaxExpansions(32))
        }
        return Search.SearchQuery.newBuilder().setId("lexical").setLexical(lexical)
    }

    /**
     * Search by meaning: embed the text on the device, query by vector. The
     * question need not share a word with the opinions it finds.
     */
    fun searchByMeaning(text: String, limit: Int = 5): SearchResult {
        val embedder = embedder ?: throw EngineException("searchByMeaning", -1, "no embedding model is bundled")
        val start = System.nanoTime()
        val vector = embedder.embed(text)
        val embedMilliseconds = (System.nanoTime() - start) / 1e6

        // Each word on its own, to compare with every hit below.
        val words = words(text)
        val wordVectors = words.mapNotNull { word -> embedder.embed(word)?.let { word to it } }
        val embedding = EmbeddingStats(embedMilliseconds, embedder.pieces(text), words.size,
            words.filter { embedder.pieces(it) >= 3 }, embedder.dimensions)
        // No word of the text in the model's vocabulary: it has no vector, and the
        // engine refuses zero vectors, so there is nothing to ask.
        vector ?: return SearchResult(emptyList(), QueryStats("search", 0.0, 0.0, 0.0, 0, 0, 0, embedding))

        val result = run(Search.SearchQuery.newBuilder().setId("dense")
            .setDense(Search.DenseQuery.newBuilder().addAllVector(vector.asList())), limit, snippets = 0)
        lastQuestion = text to vector
        val passages = preparePassages()
        // Function words sit near everything and explain nothing.
        val content = wordVectors.filter { it.first !in FUNCTION_WORDS }
        val hits = result.hits.map { hit ->
            val closest = content.map { (word, own) -> word to dot(own, hit.opinion.embedding) }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(2).map { it.first }
            val row = opinions.indexOfFirst { it.id == hit.opinion.id }
            val heat = similarities(passages[row], vector)
            val passage = heat.second?.let { best ->
                heat.first[best.paragraph][best.sentence]?.let { Passage(hit.opinion.sentences[best.paragraph][best.sentence], best, it) }
            }
            SearchHit(hit.opinion, hit.score, null, closest, passage)
        }
        val stats = result.stats
        return SearchResult(hits, QueryStats(stats.route, stats.engineMilliseconds, stats.selectionMilliseconds,
            stats.roundTripMilliseconds, stats.hits, stats.segments, stats.shards, embedding, stats.topScore, stats.lowScore))
    }

    /**
     * The cross-platform parity report: the fixed query set of tools/wire-probe
     * `--parity`, one line, scores as raw f32 bit patterns so a single differing bit
     * shows. macOS, iOS, and Android should print the same line.
     */
    fun parityReport(): String {
        val keywords = listOf("habeas", "hab", "sentencing", "qualified immun", "maritime")
        val questions = listOf("insurance company refused to pay the claim", "contract dispute sent to arbitration",
            "deported despite fear of persecution", "the prison sentence was too long",
            "fired after complaining about discrimination")
        fun entry(key: String, result: SearchResult) = key + "=" + result.hits.joinToString(",") { hit ->
            "${opinions.indexOfFirst { it.id == hit.opinion.id }}:" + String.format("%08x", java.lang.Float.floatToRawIntBits(hit.score))
        }
        val entries = mutableListOf<String>()
        for (text in keywords) entries += entry("k:$text", search(text, limit = 25))
        if (embedder != null) for (text in questions) entries += entry("m:$text", searchByMeaning(text, limit = 8))
        entries += entry("s:0", neighbours(opinions[0], limit = 6))
        return "court-parity " + entries.joinToString(";")
    }

    /** Where in `opinion` the last Meaning question found its meaning; null before any Meaning query. */
    fun heat(opinion: Opinion): Heat? {
        val (question, vector) = lastQuestion ?: return null
        val row = opinions.indexOfFirst { it.id == opinion.id }.takeIf { it >= 0 } ?: return null
        val heat = similarities(preparePassages()[row], vector)
        return Heat(question, heat.first, heat.second)
    }

    /**
     * Embeds every sentence of every opinion, once. Static embeddings make this cheap
     * enough to do on the phone: no forward pass, a table lookup and a mean.
     */
    fun preparePassages(): List<List<List<FloatArray?>>> {
        passageVectors?.let { return it }
        val embedder = embedder ?: return emptyList()
        val start = System.nanoTime()
        var count = 0
        val vectors = opinions.map { opinion ->
            opinion.sentences.map { paragraph ->
                paragraph.map { sentence ->
                    // Headings, signature lines, and stray fragments carry no meaning
                    // worth shading, and their tiny vectors are noisy.
                    if (sentence.codePointCount(0, sentence.length) < Segmenter.MINIMUM_UNIT) null
                    else embedder.embed(sentence).also { count++ }
                }
            }
        }
        passageVectors = vectors
        passageCount = count
        passageSeconds = (System.nanoTime() - start) / 1e9
        return vectors
    }

    /** Nearest neighbours of a stored opinion's own vector. */
    fun neighbours(of: Opinion, limit: Int = 5): SearchResult = run(
        Search.SearchQuery.newBuilder().setId("dense")
            .setDense(Search.DenseQuery.newBuilder().addAllVector(of.embedding)), limit, snippets = 0)

    override fun close() {
        engine.flush()
        engine.close()
        embedder?.close()
    }

    private fun run(search: Search.SearchQuery.Builder, limit: Int, snippets: Int): SearchResult {
        val request = Search.QueryRequest.newBuilder()
            .setRequestId("court-sample").setK(limit).setSelectionK(limit)
            .setSelection(Search.SelectionQuery.newBuilder().setSearch(search))
            .setProfile(true)
        if (snippets > 0) {
            request.setHighlight(Search.HighlightSpec.newBuilder()
                .setMaxSnippets(snippets).setMaxChars(180).setMode(Search.HighlightMode.HIGHLIGHT_MODE_WINDOW))
        }
        val start = System.nanoTime()
        val response = engine.query(request.build())
        val roundTrip = (System.nanoTime() - start) / 1e6

        // Row ids are assigned in ingest order from first_id 0, so a hit's doc_id
        // indexes the fixture directly.
        val hits = response.hitsList.mapNotNull { hit ->
            opinions.getOrNull(hit.docId.toInt())?.let { opinion ->
                val cut = hit.snippetsList.map { snippet(it, opinion.body.length) }
                SearchHit(opinion, hit.score, cut.firstOrNull(), snippets = cut)
            }
        }
        val profile = response.profile
        return SearchResult(hits, QueryStats(
            response.executed, profile.totalMs.toDouble(), profile.selectionMs.toDouble(), roundTrip,
            hits.size, profile.segmentsTotal.toInt(), profile.shardsTotal.toInt(),
            topScore = hits.maxOfOrNull { it.score } ?: 0f, lowScore = hits.minOfOrNull { it.score } ?: 0f))
    }

    /**
     * Slices the engine's snippet at its highlight bounds. Offsets are UTF-16 code
     * units of the ORIGINAL text, which is exactly what a Kotlin String indexes.
     */
    private fun snippet(source: Search.Snippet, bodyLength: Int): Snippet {
        val text = source.text
        val runs = mutableListOf<Snippet.Run>()
        var cursor = 0
        fun append(from: Int, to: Int, highlighted: Boolean) {
            if (to <= from) return
            val piece = text.substring(from, to)
            val collapsed = piece.collapseWhitespace()
            if (collapsed.isEmpty() && runs.isEmpty()) return
            val lead = if (piece.first().isWhitespace() && runs.isNotEmpty()) " " else ""
            val trail = if (piece.last().isWhitespace()) " " else ""
            // The whitespace tokenizer's tokens keep their punctuation ("immunity."),
            // so a mark can end on a full stop. The highlighter covers the word only.
            val word = if (highlighted) collapsed.trimEnd { !it.isLetterOrDigit() } else collapsed
            runs += Snippet.Run(lead + word + if (word.length == collapsed.length) trail else "", highlighted)
            if (word.length < collapsed.length) runs += Snippet.Run(collapsed.substring(word.length) + trail, false)
        }
        for (mark in source.highlightsList) {
            val lower = (mark.start - source.start).toInt().coerceIn(cursor, text.length)
            val upper = (mark.end - source.start).toInt().coerceIn(lower, text.length)
            append(cursor, lower, false)
            append(lower, upper, true)
            cursor = upper
        }
        append(cursor, text.length, false)
        return Snippet(runs, source.start > 0, source.end.toInt() < bodyLength)
    }

    private fun ingest(descriptorSet: ByteString, fingerprint: String) {
        val bind = Search.MappedBind.newBuilder()
            .setDescriptorSet(descriptorSet)
            .setMessageType(MESSAGE_TYPE)
            .setExpectedFingerprint(fingerprint)
            .setBodyPath("body")
        // field_analysis names EVERY text path, body included, and replaces the
        // legacy `analysis` field; the two are mutually exclusive.
        for (path in listOf("title", "body")) {
            bind.addFieldAnalysis(Search.MappedFieldAnalysis.newBuilder().setPath(path).setAnalysis(BODY_SPEC))
        }
        val batch = Mobile.MobileIngestMappedBatch.newBuilder().setShard(0)
            .addRequests(Search.IngestMappedRequest.newBuilder().setBind(bind))
        for (opinion in opinions) {
            val message = Court.Opinion.newBuilder()
                .setId(opinion.id).setTitle(opinion.title).setBody(opinion.body)
                .addAllEmbedding(opinion.embedding).build()
            batch.addRequests(Search.IngestMappedRequest.newBuilder().setDocument(message.toByteString()))
        }
        val ingested = engine.ingestMapped(batch.build())
        if (ingested.added != opinions.size.toLong() || ingested.firstId != 0L) {
            throw EngineException("ingestMapped", -1,
                "added ${ingested.added} from first_id ${ingested.firstId}, expected ${opinions.size} from 0")
        }
        engine.flush()
    }

    private companion object {
        const val MESSAGE_TYPE = "court.v1.Opinion"

        val FUNCTION_WORDS = setOf("a", "an", "the", "of", "to", "in", "on", "at", "by", "for", "with", "about", "after",
            "before", "despite", "while", "and", "or", "but", "was", "were", "is", "are", "be", "been", "it", "its",
            "this", "that", "too", "very", "not", "no", "from", "as", "into", "over", "under")

        /** Lowercased words, punctuation trimmed, in order, without repeats. */
        fun words(text: String): List<String> = text.lowercase().split(Regex("\\s+"))
            .map { word -> word.trim { !it.isLetterOrDigit() } }.filter { it.isNotEmpty() }.distinct()

        fun dot(a: FloatArray, b: List<Float>): Float {
            var sum = 0f
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }

        fun similarities(paragraphs: List<List<FloatArray?>>, question: FloatArray): Pair<List<List<Float?>>, PassageLocation?> {
            var hottest: PassageLocation? = null
            var best = Float.NEGATIVE_INFINITY
            val values = paragraphs.mapIndexed { p, paragraph ->
                paragraph.mapIndexed { n, vector ->
                    vector?.let {
                        var similarity = 0f
                        for (i in it.indices) similarity += it[i] * question[i]
                        if (similarity > best) { best = similarity; hottest = PassageLocation(p, n) }
                        similarity
                    }
                }
            }
            return values to hottest
        }

        /**
         * The Java sample's embedding input, reproduced exactly: title, newline, and
         * `body.substring(0, 2000)`. Kotlin's take counts UTF-16 units, as Java's does.
         */
        fun embedText(opinion: Opinion) = opinion.title + "\n" + opinion.body.take(2000)

        /**
         * Whitespace tokenizer, Porter stemmer, full term vectors from normalized
         * stems, and the strip-invisible / whitespace / accent-fold / full-case-fold
         * char filters: the engine's `body_spec()`. The native analyzer has no
         * default, and ingest and query must use the same spec.
         */
        val BODY_SPEC: Search.AnalysisSpec = Search.AnalysisSpec.newBuilder()
            .setTokenizer(1).setStemmer(2).setTermVectorMode(1).setTermVectorSource(3)
            .addAllCharFilters(listOf(1, 2, 15, 6)).build()

        fun loadFixture(assets: AssetManager): List<Opinion> =
            assets.open("court_opinions_potion512.ndjson").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.map { line ->
                    val row = JSONObject(line)
                    val vector = row.getJSONArray("embedding")
                    Opinion(
                        id = row.getString("doc_id"), title = row.getString("title"), body = row.getString("body"),
                        sourceUri = row.getString("source_uri"), dateFiled = row.optString("date_filed"),
                        docketNumber = row.optString("docket_number"), court = row.optString("court"),
                        judges = row.optString("judges"), author = row.optString("author"), status = row.optString("status"),
                        embedding = List(vector.length()) { vector.getDouble(it).toFloat() },
                    )
                }.toList()
            }
    }
}
