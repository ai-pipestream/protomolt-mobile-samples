package ai.pipestream.samples.courtsearch

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Phase 0 acceptance checks, on an emulator or device. Expected results are
 * the ones tools/wire-probe, the iOS test, and the Java/Lucene sample all produce.
 */
@RunWith(AndroidJUnit4::class)
class CourtIndexTest {
    @Test
    fun ingestSearchAndReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "court-${System.nanoTime()}")
        try {
            CourtIndex(context.assets, directory).use { index ->
                assertEquals(25, index.stats.documents)
                assertNotNull(index.stats.ingestSeconds)
                assertTrue(index.stats.bytesOnDisk > 0)
                assertDemoQueries(index)
            }
            CourtIndex(context.assets, directory).use { index ->
                assertNull("reopen must attach to the existing index, not re-ingest", index.stats.ingestSeconds)
                assertDemoQueries(index)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertDemoQueries(index: CourtIndex) {
        val habeas = index.search("habeas")
        assertEquals(listOf("Forsyth v. Spencer", "United States v. Dowdell"), habeas.hits.map { it.opinion.title })
        assertEquals("bm25_search", habeas.stats.route)
        assertTrue(habeas.stats.engineMilliseconds > 0)
        // The engine cuts the snippet and marks the match; the app only slices it.
        assertEquals(listOf("habeas"), habeas.hits[0].snippet!!.runs.filter { it.highlighted }.map { it.text.trim() })
        assertEquals("No. 09-1011 (1st Cir. Feb. 16, 2010)", habeas.hits[0].opinion.citation)

        // Type-ahead: an unfinished word finds what the finished one does.
        assertEquals(
            listOf("Forsyth v. Spencer", "United States v. Dowdell"),
            index.search("hab").hits.map { it.opinion.title }.take(2))

        val similar = index.neighbours(index.opinions[0])
        assertEquals("search", similar.stats.route)
        assertNull("snippets are a keyword-only feature", similar.hits[0].snippet)
        assertEquals(
            listOf(
                "United States v. Davila-Gonzalez",
                "United States v. Rodríguez-Vélez",
                "United States v. De-la-Rosa-Ramos",
                "United States v. Diaz",
                "United States v. Ekasala",
            ),
            similar.hits.map { it.opinion.title })
        assertEquals(1.0f, similar.hits[0].score, 0.01f)
    }
}
