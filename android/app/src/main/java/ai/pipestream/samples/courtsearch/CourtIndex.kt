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

    /**
     * The opinion text as reflowed paragraphs: the source is hard-wrapped and
     * centred with spaces, which reads as noise on a phone.
     */
    val paragraphs: List<String> by lazy {
        body.split(Regex("\\n\\s*\\n")).map { it.collapseWhitespace() }.filter { it.isNotEmpty() }
    }

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

/** `snippet` is present on keyword hits only; the engine refuses it on any other query shape. */
class SearchHit(val opinion: Opinion, val score: Float, val snippet: Snippet?)

/** What the engine reported about one query, plus the app-measured round trip. */
class QueryStats(
    /** The route as the engine names it: `bm25_search`, `search`. */
    val route: String,
    val engineMilliseconds: Double,
    val selectionMilliseconds: Double,
    /** Wall time around the call: protobuf encode/decode and the JNI hop included. */
    val roundTripMilliseconds: Double,
    val hits: Int, val segments: Int, val shards: Int,
)

class SearchResult(val hits: List<SearchHit>, val stats: QueryStats)

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
class CourtIndex(assets: AssetManager, directory: File) : AutoCloseable {
    val opinions: List<Opinion> = loadFixture(assets)
    val stats: IndexStats
    private val engine: SearchEngine

    init {
        directory.mkdirs()
        // The engine persists an image plus sidecars (court.tv.live, court.tv.wal, …)
        // and `create` refuses to overwrite any of them, so existence means "any
        // file of this index", not the image alone.
        val exists = directory.list().orEmpty().any { it.startsWith("court.tv") }
        val shard = Mobile.MobileShardConfig.newBuilder()
            .setIndexPath(File(directory, "court.tv").path)
            .addFacetFields("id")
            // Order matters: the FIRST bm25 field is what an unqualified LexicalQuery
            // searches. Every string field the plan lands must be declared here.
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
    fun search(text: String, limit: Int = 5): SearchResult {
        val lexical = Search.LexicalQuery.newBuilder().setText(text).setAnalysis(BODY_SPEC)
        val last = text.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
        if (last.isNotEmpty() && !text.last().isWhitespace()) {
            lexical.addPrefixes(Search.TermPrefix.newBuilder().setPrefix(last).setMaxExpansions(32))
        }
        return run(Search.SearchQuery.newBuilder().setId("lexical").setLexical(lexical), limit, snippets = true)
    }

    /**
     * Nearest neighbours of a stored opinion's own vector. Phase 0 has no on-device
     * embedder, so queries are documents, as in the Java sample.
     */
    fun neighbours(of: Opinion, limit: Int = 5): SearchResult = run(
        Search.SearchQuery.newBuilder().setId("dense")
            .setDense(Search.DenseQuery.newBuilder().addAllVector(of.embedding)), limit, snippets = false)

    override fun close() {
        engine.flush()
        engine.close()
    }

    private fun run(search: Search.SearchQuery.Builder, limit: Int, snippets: Boolean): SearchResult {
        val request = Search.QueryRequest.newBuilder()
            .setRequestId("court-sample").setK(limit).setSelectionK(limit)
            .setSelection(Search.SelectionQuery.newBuilder().setSearch(search))
            .setProfile(true)
        if (snippets) {
            request.setHighlight(Search.HighlightSpec.newBuilder()
                .setMaxSnippets(1).setMaxChars(180).setMode(Search.HighlightMode.HIGHLIGHT_MODE_WINDOW))
        }
        val start = System.nanoTime()
        val response = engine.query(request.build())
        val roundTrip = (System.nanoTime() - start) / 1e6

        // Row ids are assigned in ingest order from first_id 0, so a hit's doc_id
        // indexes the fixture directly.
        val hits = response.hitsList.mapNotNull { hit ->
            opinions.getOrNull(hit.docId.toInt())?.let { opinion ->
                SearchHit(opinion, hit.score, hit.snippetsList.firstOrNull()?.let { snippet(it, opinion.body.length) })
            }
        }
        val profile = response.profile
        return SearchResult(hits, QueryStats(
            response.executed, profile.totalMs.toDouble(), profile.selectionMs.toDouble(), roundTrip,
            hits.size, profile.segmentsTotal.toInt(), profile.shardsTotal.toInt()))
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
