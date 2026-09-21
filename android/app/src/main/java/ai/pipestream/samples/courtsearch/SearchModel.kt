package ai.pipestream.samples.courtsearch

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Owns the on-device index for the UI. Every engine call blocks, so they all run
 * on one background thread; Compose state is only touched from the main thread.
 */
class SearchModel(private val scope: CoroutineScope) {
    sealed interface Phase {
        data object Opening : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    var phase: Phase by mutableStateOf(Phase.Opening)
        private set
    var opinions: List<Opinion> by mutableStateOf(emptyList())
        private set
    var index: IndexStats? by mutableStateOf(null)
        private set

    /** Null while the query is empty: the start card shows instead. */
    var results: List<SearchHit>? by mutableStateOf(null)
        private set

    /** The most recent query of either kind; what the engine strip reports. */
    var lastQuery: QueryStats? by mutableStateOf(null)
        private set
    var query by mutableStateOf("")
        private set

    enum class Mode(val label: String) { Keyword("Keyword"), Meaning("Meaning") }

    var mode by mutableStateOf(Mode.Keyword)
        private set

    /** Null when no model is bundled: the Meaning mode is then not offered. */
    var embedder: EmbedderStats? by mutableStateOf(null)
        private set

    private val engineThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var courtIndex: CourtIndex? = null
    private var pending: Job? = null

    fun updateMode(next: Mode) {
        if (next == mode) return
        mode = next
        val text = query
        query = ""
        updateQuery(text)
    }

    suspend fun open(context: Context, launchQuery: String?, resetIndex: Boolean, launchMode: String? = null, open: Int? = null, parity: Boolean = false) {
        launchOpen = open
        if (courtIndex != null) return
        try {
            val directory = File(context.filesDir, "court-index")
            val opened = withContext(engineThread) {
                // `--ez resetIndex true` deletes the stored index first, so a launch re-ingests.
                if (resetIndex) directory.deleteRecursively()
                CourtIndex(context.assets, directory, Embedder.install(context.assets, context.filesDir))
            }
            embedder = opened.embedderStats
            opened.embedderStats?.let {
                Log.i("court-index", String.format("embedder dims=%d loadSeconds=%.3f fixtureWorstDelta=%g fixtureSeconds=%.3f",
                    it.dimensions, it.loadSeconds, it.fixtureWorstDelta, it.fixtureSeconds))
            }
            // The vector engine picks its kernels from these at run time, so they belong
            // beside any score comparison between devices.
            val features = runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }.orEmpty().split(" ") }.getOrDefault(emptyList())
            Log.i("court-index", "cpu dotprod=${if ("asimddp" in features) 1 else 0} i8mm=${if ("i8mm" in features) 1 else 0}")
            // `--ez parity true`: print the cross-platform report (tools/parity_compare.py).
            // logcat truncates long lines, so it goes out one query per line.
            if (parity) withContext(engineThread) { opened.parityReport() }.removePrefix("court-parity ").split(";")
                .forEach { Log.i("court-parity", it) }
            if (launchMode == "meaning" && embedder != null) mode = Mode.Meaning
            if (embedder != null) scope.launch {
                withContext(engineThread) { opened.preparePassages() }
                opened.passageSeconds?.let {
                    passages = opened.passageCount to it
                    Log.i("court-index", String.format("passages count=%d seconds=%.3f", opened.passageCount, it))
                }
            }
            courtIndex = opened
            opinions = opened.opinions
            index = opened.stats
            // One line in logcat, readable from a host with `adb logcat -s court-index`.
            Log.i("court-index", "documents=${opened.stats.documents} bytes=${opened.stats.bytesOnDisk} " +
                "ingestSeconds=${opened.stats.ingestSeconds?.let { String.format("%.3f", it) } ?: "reopened"}")
            phase = Phase.Ready
            // `--es query habeas` on `adb shell am start` opens straight onto results.
            if (launchQuery != null) updateQuery(launchQuery)
        } catch (e: Exception) {
            phase = Phase.Failed(e.toString())
        }
    }

    /** Search as you type, 200 ms after the last keystroke. */
    fun updateQuery(text: String) {
        if (text == query) return
        query = text
        pending?.cancel()
        // Leading space only: a trailing space means the last word is finished,
        // which is what turns the type-ahead prefix off.
        val trimmed = text.trimStart()
        val opened = courtIndex
        if (opened == null || trimmed.isBlank()) {
            results = null
            return
        }
        pending = scope.launch {
            delay(200)
            try {
                // Ask for every opinion: the corpus is small, and "N of 25" on the engine
                // strip must be the number that matched, not a page size.
                val meaning = mode == Mode.Meaning
                val result = withContext(engineThread) {
                    if (meaning) opened.searchByMeaning(trimmed, limit = 8) else opened.search(trimmed, limit = opened.opinions.size)
                }
                results = result.hits
                lastQuery = result.stats
                launchOpen?.let { position ->
                    launchOpen = null
                    pendingOpen = result.hits.getOrNull(position - 1)?.opinion
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = Phase.Failed(e.toString())
            }
        }
    }

    /** Passage vectors are built once, in the background, right after launch. */
    var passages: Pair<Int, Double>? by mutableStateOf(null)
        private set

    /** `--ei open 1`: the result to open once the launch query has answered. */
    var pendingOpen: Opinion? by mutableStateOf(null)
    private var launchOpen: Int? = null

    /**
     * Where the current Meaning question found its meaning in `opinion`; null in
     * Keyword mode or with no question on screen.
     */
    suspend fun heat(opinion: Opinion): Heat? {
        val opened = courtIndex ?: return null
        if (mode != Mode.Meaning || results == null) return null
        return runCatching { withContext(engineThread) { opened.heat(opinion) } }.getOrNull()
    }

    /**
     * The words of `opinion` the current Keyword query matched, as the engine marked
     * them; empty in Meaning mode or with no query on screen.
     */
    suspend fun matchedForms(opinion: Opinion): List<String> {
        val opened = courtIndex ?: return emptyList()
        val text = query.trimStart()
        if (mode != Mode.Keyword || results == null || text.isBlank()) return emptyList()
        return runCatching { withContext(engineThread) { opened.matchedForms(opinion, text) } }.getOrDefault(emptyList())
    }

    suspend fun neighbours(of: Opinion): List<SearchHit> {
        val opened = courtIndex ?: return emptyList()
        val result = runCatching { withContext(engineThread) { opened.neighbours(of, limit = 6) } }.getOrNull()
            ?: return emptyList()
        lastQuery = result.stats
        // The nearest neighbour of a stored vector is itself; drop it.
        return result.hits.filter { it.opinion.id != of.id }
    }

    suspend fun close() = withContext(engineThread) { courtIndex?.close() }

    companion object {
        /**
         * Every suggestion returns results in the bundled corpus, across different
         * areas of law. DESIGN.md lists the same set for every platform.
         */
        val suggestions = listOf("habeas", "sentencing", "conspiracy", "insurance",
            "maritime", "arbitration", "forfeiture", "qualified immunity")

        /**
         * Questions in plain language, none sharing a caption word with what it finds.
         * Each was checked against the bundled corpus with tools/wire-probe --meaning.
         */
        val questions = listOf("insurance company refused to pay the claim", "contract dispute sent to arbitration",
            "deported despite fear of persecution", "the prison sentence was too long",
            "fired after complaining about discrimination")

        fun routeName(route: String) = when (route) {
            "bm25_search" -> "Keyword"
            "search" -> "Similarity"
            else -> route
        }
    }
}
