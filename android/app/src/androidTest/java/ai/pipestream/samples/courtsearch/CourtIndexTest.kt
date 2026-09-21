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

    /** Search by meaning, and this device's embedder against the Java vectors. Skipped when the APK has no model. */
    @Test
    fun searchByMeaningAndEmbedderConformance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = Embedder.install(context.assets, context.filesDir)
        org.junit.Assume.assumeNotNull(model)
        val directory = File(context.cacheDir, "court-${System.nanoTime()}")
        try {
            CourtIndex(context.assets, directory, model).use { index ->
                val embedder = index.embedderStats!!
                assertEquals(512, embedder.dimensions)
                assertEquals("Rust and Java embedders must agree bit for bit", 0f, embedder.fixtureWorstDelta, 0f)

                // No word in common with the caption it finds.
                val result = index.searchByMeaning("insurance company refused to pay the claim")
                assertEquals("Baker v. St. Paul Travelers Insurance", result.hits.first().opinion.title)
                assertEquals("search", result.stats.route)
                val embedding = result.stats.embedding!!
                assertEquals(7, embedding.words)
                assertTrue(embedding.spelledOutWords.isEmpty())
                assertTrue(embedding.pieces >= 7)
                assertTrue(result.stats.topScore > result.stats.lowScore)
                // Why this opinion: the question's own words, nearest first.
                assertEquals("insurance", result.hits[0].closestWords.first())

                // Where the meaning was found. The nearest sentence is about paying a
                // premium for coverage: it answers the question without sharing one of
                // its words, which is the point of searching by meaning.
                val passage = result.hits[0].passage!!
                assertTrue(passage.text, passage.text.contains("paid a premium"))
                assertEquals(passage.location, index.heat(result.hits[0].opinion)!!.hottest)
                assertEquals("segmentation must match tools/passages_reference.py", 4075, index.passageCount)

                // WordPiece never gives up on a word: gibberish still gets a vector,
                // spelled out letter by letter, and is reported as such.
                assertEquals(listOf("qzxvkjw"), index.searchByMeaning("insurance qzxvkjw").stats.embedding!!.spelledOutWords)
                assertTrue(index.searchByMeaning("").hits.isEmpty())
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

        // The reading view highlights the engine's own matched forms, stems included.
        val aguirre = index.opinions.first { it.title == "United States v. Aguirre-Gonzalez" }
        val forms = index.matchedForms(aguirre, "sentencing")
        assertTrue(forms.toString(), "sentencing" in forms && "sentence" in forms)

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
