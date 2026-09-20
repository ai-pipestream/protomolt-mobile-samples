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

    private val engineThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var courtIndex: CourtIndex? = null
    private var pending: Job? = null

    suspend fun open(context: Context, launchQuery: String?, resetIndex: Boolean) {
        if (courtIndex != null) return
        try {
            val directory = File(context.filesDir, "court-index")
            val opened = withContext(engineThread) {
                // `--ez resetIndex true` deletes the stored index first, so a launch re-ingests.
                if (resetIndex) directory.deleteRecursively()
                CourtIndex(context.assets, directory)
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
                val result = withContext(engineThread) { opened.search(trimmed, limit = opened.opinions.size) }
                results = result.hits
                lastQuery = result.stats
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = Phase.Failed(e.toString())
            }
        }
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

        fun routeName(route: String) = when (route) {
            "bm25_search" -> "Keyword"
            "search" -> "Similarity"
            else -> route
        }
    }
}
