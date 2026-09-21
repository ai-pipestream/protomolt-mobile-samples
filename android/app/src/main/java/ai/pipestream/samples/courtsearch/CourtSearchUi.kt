package ai.pipestream.samples.courtsearch

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.KeyboardArrowDown
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.width

private sealed interface Screen {
    data object Search : Screen
    data object Browse : Screen
    data class Reading(val opinion: Opinion) : Screen
}

/** DESIGN.md's three screens and the engine panel, which any of them can open. */
@Composable
fun CourtSearchApp(model: SearchModel, jumpOnOpen: Boolean = false) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Search) }
    // `--ei open 1`: land on a result's reading view, for scripts and demos.
    LaunchedEffect(model.pendingOpen) {
        model.pendingOpen?.let { stack += Screen.Reading(it); model.pendingOpen = null }
    }
    var showingEngine by remember { mutableStateOf(false) }
    val open: (Opinion) -> Unit = { stack += Screen.Reading(it) }
    val back: () -> Unit = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (stack.last() == Screen.Search) model.returnedToResults()
    }
    BackHandler(enabled = stack.size > 1, onBack = back)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(stack.last(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { screen ->
            when (screen) {
                Screen.Search -> SearchScreen(model, open, onBrowse = { stack += Screen.Browse }) { showingEngine = true }
                Screen.Browse -> BrowseScreen(model, open, back)
                is Screen.Reading -> OpinionScreen(model, screen.opinion, open, back, jumpOnOpen) { showingEngine = true }
            }
        }
    }
    if (showingEngine) EnginePanel(model) { showingEngine = false }
}

private val screenPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp)

@Composable
private fun SearchScreen(model: SearchModel, onOpen: (Opinion) -> Unit, onBrowse: () -> Unit, onEngine: () -> Unit) {
    when (val phase = model.phase) {
        SearchModel.Phase.Opening -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text("Building the on-device index…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is SearchModel.Phase.Failed -> Text(
            phase.message, Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        SearchModel.Phase.Ready -> LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentPadding = screenPadding,
        ) {
            item(key = "title") { Text("Court Search", Modifier.padding(top = 28.dp, bottom = 10.dp), style = Reporter.screenTitle) }
            item(key = "search") { SearchField(model) }
            if (model.embedder != null) item(key = "mode") { ModePicker(model) }
            item(key = "strip") { EngineStrip(model, onEngine, Modifier.padding(top = 12.dp, bottom = 8.dp), showsLastQuery = model.results != null) }
            val results = model.results
            when {
                results == null -> item(key = "start") { StartCard(model, onBrowse, Modifier.animateItem()) }
                results.isEmpty() -> item(key = "none") { NoMatches(model, Modifier.animateItem()) }
                else -> items(results, key = { it.opinion.id }) { hit ->
                    Column(Modifier.animateItem()) {
                        CaseRow(hit.opinion, hit, onOpen, topScore = model.lastQuery?.topScore ?: 0f)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(model: SearchModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val fill = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(0xFFEEEEEF)
    TextField(
        value = model.query,
        onValueChange = model::updateQuery,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(if (model.mode == SearchModel.Mode.Meaning) "Describe what you are looking for" else "Search ${model.opinions.size} opinions") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (model.query.isNotEmpty()) {
                IconButton(onClick = { model.updateQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = fill, unfocusedContainerColor = fill,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

@Composable
private fun ModePicker(model: SearchModel) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        SearchModel.Mode.entries.forEachIndexed { i, mode ->
            SegmentedButton(selected = model.mode == mode, onClick = { model.updateMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, SearchModel.Mode.entries.size), icon = {}) { Text(mode.label) }
        }
    }
}

/** One slate row: what the engine just did, or what it holds before any query. */
@Composable
private fun EngineStrip(model: SearchModel, onOpen: () -> Unit, modifier: Modifier = Modifier, showsLastQuery: Boolean = true) {
    val voices = LocalVoices.current
    val index = model.index ?: return
    // The strip describes what is on screen: a query's numbers beside its results,
    // the index's own facts beside the start card.
    val last = model.lastQuery.takeIf { showsLastQuery }
    Surface(
        modifier.fillMaxWidth().clickable(onClickLabel = "Engine details", onClick = onOpen),
        shape = RoundedCornerShape(12.dp), color = voices.slateSurface,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val embedding = last?.embedding
            if (last != null && embedding != null) {
                // A Meaning query has two costs, and the strip shows both.
                Metric(String.format("%.2f ms", embedding.milliseconds), "embed")
                Metric(String.format("%.1f ms", last.engineMilliseconds), "search")
                Metric("Top ${last.hits}", "nearest")
            } else if (last != null) {
                Metric(String.format("%.1f ms", last.engineMilliseconds), "engine time")
                // A dense query returns a top-k, not a count of matches.
                if (last.route == "search") Metric("Top ${last.hits}", "nearest") else Metric("${last.hits} of ${index.documents}", "opinions")
                Metric(SearchModel.routeName(last.route), "query")
            } else {
                Metric("${index.documents}", "opinions")
                Metric(Formatter.formatShortFileSize(LocalContext.current, index.bytesOnDisk), "on disk")
                Metric("${index.vectorDimensions}-dim", "vectors")
            }
            Metric("On device", "no network")
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = voices.slateInk)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Metric(value: String, label: String) {
    Column(Modifier.weight(1f)) {
        AnimatedContent(value, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = label) {
            Text(it, style = Engine.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(label, style = Engine.label, color = LocalVoices.current.slateInk, maxLines = 1)
    }
}

/**
 * What the app shows before a search: what to do, words that work, and the way
 * into similarity search. Results replace it as soon as a word is typed.
 */
@Composable
private fun StartCard(model: SearchModel, onBrowse: () -> Unit, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val meaning = model.mode == SearchModel.Mode.Meaning
            Text(if (meaning) "Search by meaning" else "Search the opinions", style = Reporter.heading)
            Text(if (meaning) "Describe the situation in your own words. The phone turns your words into a vector and finds the opinions nearest to it, even when they share no words with you."
                else "Type a word or phrase from an opinion. Results appear as you type, and every search runs on this phone.",
                style = Reporter.body, color = secondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Try one of these", style = MaterialTheme.typography.bodyMedium, color = secondary)
            SuggestionChips(model)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Looking for cases like one you know? Open an opinion to see the ones closest to it in meaning.",
                style = Reporter.body, color = secondary)
            Row(Modifier.fillMaxWidth().clickable(onClick = onBrowse).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Browse all ${model.opinions.size} opinions", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = LocalVoices.current.oxblood)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChips(model: SearchModel) {
    val haptics = LocalHapticFeedback.current
    val oxblood = LocalVoices.current.oxblood
    val fill = LocalVoices.current.chipFill
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (word in if (model.mode == SearchModel.Mode.Meaning) SearchModel.questions else SearchModel.suggestions) {
            Surface(shape = CircleShape, color = fill, modifier = Modifier.clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                model.updateQuery(word)
            }) {
                Text(word, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = Reporter.snippet, color = oxblood)
            }
        }
    }
}

@Composable
private fun NoMatches(model: SearchModel, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val meaning = model.mode == SearchModel.Mode.Meaning
        Text(if (meaning) "None of those words are in the model’s vocabulary" else "No opinion has a word starting with “${model.query.trim()}”",
            style = Reporter.caption.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Normal))
        Text(if (meaning) "Text the model has never seen has no vector to search with. These all find something:"
            else "Search looks at the words of each opinion, not at case names. These all find something:",
            style = Reporter.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SuggestionChips(model)
    }
}

/** A case as a citation. With a hit: the engine's snippet, the author, the score. Without: the panel line. */
@Composable
private fun CaseRow(opinion: Opinion, hit: SearchHit?, onOpen: (Opinion) -> Unit, topScore: Float = 0f) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clickable { onOpen(opinion) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(opinion.title, style = Reporter.caption)
            Text(opinion.citation, style = Reporter.citation, color = secondary)
            val snippet = hit?.snippet
            val passage = hit?.passage
            if (snippet != null) SnippetText(snippet, Modifier.padding(top = 4.dp))
            else if (passage != null) {
                // The dense counterpart of a snippet: the sentence nearest the question.
                Row(Modifier.padding(top = 4.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(LocalVoices.current.highlighter))
                    Text(passage.text, Modifier.padding(start = 8.dp), style = Reporter.snippet, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (hit.closestWords.isNotEmpty()) {
                    Text("Closest words: ${hit.closestWords.joinToString(", ")}", style = Engine.label, color = LocalVoices.current.slateInk)
                }
            } else opinion.panel?.let { Text(it, style = Reporter.small, color = secondary) }
            if (hit != null || opinion.isUnpublished) {
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hit != null) opinion.authorLine?.let { Text(it, style = Reporter.small, color = secondary) }
                    if (opinion.isUnpublished) Text("Unpublished", style = Engine.label, color = LocalVoices.current.oxblood)
                    Spacer(Modifier.weight(1f))
                    if (hit?.passage != null && topScore > 0f) {
                        // Similarity at a glance, relative to the best hit on screen.
                        val ink = LocalVoices.current.slateInk
                        Box(Modifier.width(56.dp).height(4.dp).background(ink.copy(alpha = 0.25f), CircleShape)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((hit.score / topScore).coerceIn(0f, 1f)).background(ink, CircleShape))
                        }
                    }
                    if (hit != null) Text(String.format("%.3f", hit.score), style = Engine.label.copy(fontFeatureSettings = "tnum"), color = LocalVoices.current.slateInk)
                }
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The engine's snippet: serif, with the highlighter on the words it matched. */
@Composable
private fun SnippetText(snippet: Snippet, modifier: Modifier = Modifier) {
    val mark = SpanStyle(background = LocalVoices.current.highlighter, color = LocalVoices.current.highlighterInk)
    Text(buildAnnotatedString {
        if (snippet.cutAtStart) append("…")
        for (run in snippet.runs) if (run.highlighted) withStyle(mark) { append(run.text.trim()) }.also {
            if (run.text.endsWith(" ")) append(" ")
        } else append(run.text)
        if (snippet.cutAtEnd) append("…")
    }, modifier, style = Reporter.snippet)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnginePanel(model: SearchModel, onDismiss: () -> Unit) {
    val voices = LocalVoices.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = voices.slateSurface) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            item { Text("Engine", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleLarge) }
            model.lastQuery?.let { last ->
                item { PanelHeader("Last query") }
                item {
                    PanelGroup(listOfNotNull(
                        last.embedding?.let { "Embed question" to String.format("%.2f ms", it.milliseconds) },
                        last.embedding?.let { "Question" to "${it.words} words, ${it.pieces} pieces" },
                        last.embedding?.let { "Spelled out" to (it.spelledOutWords.joinToString(", ").ifEmpty { "none" }) },
                        last.embedding?.let { "Similarity" to String.format("%.3f best, %.3f last shown", last.topScore, last.lowScore) },
                        "Route" to last.route,
                        "Engine time" to String.format("%.2f ms", last.engineMilliseconds),
                        "Selection" to String.format("%.2f ms", last.selectionMilliseconds),
                        "Round trip" to String.format("%.2f ms", last.roundTripMilliseconds),
                        "Hits" to "${last.hits}", "Segments" to "${last.segments}", "Shards" to "${last.shards}"))
                }
                item {
                    Text("Engine time is what the engine reports. Round trip is measured by the app and adds protobuf encoding and the call across the language boundary.",
                        Modifier.padding(top = 8.dp), style = Engine.label, color = voices.slateInk)
                }
            }
            model.index?.let { index ->
                item { PanelHeader("Index") }
                item {
                    PanelGroup(listOf(
                        "Opinions" to "${index.documents}",
                        "Vectors" to "${index.documents} × ${index.vectorDimensions}",
                        "On disk" to Formatter.formatShortFileSize(LocalContext.current, index.bytesOnDisk),
                        (if (index.ingestSeconds == null) "Opened" else "Built in") to
                            (index.ingestSeconds?.let { String.format("%.2f s", it) } ?: "from disk, nothing ingested"),
                        "Plan fingerprint" to index.planFingerprint.take(12)))
                }
            }
            model.embedder?.let { embedder ->
                item { PanelHeader("Embedder") }
                item {
                    PanelGroup(listOfNotNull(
                        "Model" to Embedder.MODEL,
                        "Vectors" to "${embedder.dimensions}-dim, unit length",
                        "Loaded in" to String.format("%.0f ms", embedder.loadSeconds * 1000),
                        "Against Java vectors" to (if (embedder.fixtureWorstDelta == 0f) "identical, 25 of 25" else String.format("max Δ %.2g", embedder.fixtureWorstDelta)),
                        model.passages?.let { "Passages embedded" to String.format("%d in %.2f s", it.first, it.second) }))
                }
                item {
                    Text("A word the model has no entry for is spelled out from smaller pieces, down to single letters, so every word gets a vector; three or more pieces means the model does not really know it. Passages are the opinions’ sentences, embedded on this phone to show where a question’s meaning was found. The 25 opinion vectors in the index were computed by a Java implementation. On launch this phone embeds the same 25 texts with its own Rust implementation and compares, component by component.",
                        Modifier.padding(top = 8.dp), style = Engine.label, color = voices.slateInk)
                }
            }
            item { PanelHeader("Privacy") }
            item { Text("No network permission. The engine links no networking code.", style = Engine.row, color = voices.slateInk) }
        }
    }
}

@Composable
private fun PanelHeader(text: String) {
    Text(text, Modifier.padding(top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = LocalVoices.current.slateInk)
}

@Composable
private fun PanelGroup(rows: List<Pair<String, String>>) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {
        Column {
            rows.forEachIndexed { i, (label, value) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = Engine.row)
                    Text(value, style = Engine.row, color = LocalVoices.current.slateInk)
                }
                if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun NavBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, Modifier.weight(1f), style = Reporter.sectionHeading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        action?.invoke()
    }
}

@Composable
private fun BrowseScreen(model: SearchModel, onOpen: (Opinion) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        NavBar("All opinions", onBack)
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            items(model.opinions, key = { it.id }) { opinion ->
                CaseRow(opinion, null, onOpen)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/**
 * One place the query landed in the opinion: a matched word (Keyword) or a shaded
 * sentence (Meaning). `fraction` is how far down its paragraph it starts, which is
 * what lets a jump land on the mark itself inside a long paragraph.
 */
private class Mark(val paragraph: Int, val fraction: Float, val start: Int, val length: Int, val sentence: Int? = null)

private class Page(val paragraphs: List<AnnotatedString>, val marks: List<Mark>)

/**
 * Meaning: shade only what stands out, the top fifth of sentences by closeness, from
 * a faint wash upward. A graded wash never changes the ink: in the dark a partial
 * wash over black is a mid brown, and dark ink on it is unreadable, so the text keeps
 * its own colour and the wash is capped where light text still reads. Dark ink
 * belongs only on the full-strength highlighter: keyword matches and the current mark.
 */
private fun shaded(sentences: List<List<String>>, heat: Heat, highlighter: Color, dark: Boolean): Page {
    val ranked = heat.similarities.flatten().filterNotNull().sorted()
    val top = ranked.lastOrNull() ?: 0f
    val floor = if (ranked.size > 5) ranked[(ranked.size * 0.8).toInt()] else Float.POSITIVE_INFINITY
    val strongestWash = if (dark) 0.42f else 0.75f
    val marks = mutableListOf<Mark>()
    val paragraphs = sentences.mapIndexed { p, paragraph ->
        val length = paragraph.sumOf { it.length + 1 }.coerceAtLeast(1)
        var offset = 0
        buildAnnotatedString {
            paragraph.forEachIndexed { n, sentence ->
                val value = heat.similarities.getOrNull(p)?.getOrNull(n)
                if (value != null && value > floor && top > floor) {
                    val shade = 0.15f + (strongestWash - 0.15f) * (value - floor) / (top - floor)
                    withStyle(SpanStyle(background = highlighter.copy(alpha = shade))) { append(sentence) }
                    marks += Mark(p, offset.toFloat() / length, offset, sentence.length, n)
                } else append(sentence)
                if (n < paragraph.lastIndex) append(" ")
                offset += sentence.length + 1
            }
        }
    }
    return Page(paragraphs, marks)
}

/** Keyword: the full highlighter on every whole-word occurrence of a form the engine matched. */
private fun marked(sentences: List<List<String>>, forms: List<String>, highlighter: Color, ink: Color): Page {
    val plain = sentences.map { it.joinToString(" ") }
    if (forms.isEmpty()) return Page(plain.map { AnnotatedString(it) }, emptyList())
    val regex = Regex("(?<![\\p{L}\\p{N}])(?:${forms.joinToString("|") { Regex.escape(it) }})(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    val marks = mutableListOf<Mark>()
    val paragraphs = plain.mapIndexed { p, paragraph ->
        buildAnnotatedString {
            append(paragraph)
            for (match in regex.findAll(paragraph)) {
                addStyle(SpanStyle(background = highlighter, color = ink), match.range.first, match.range.last + 1)
                marks += Mark(p, match.range.first.toFloat() / paragraph.length.coerceAtLeast(1), match.range.first, match.value.length)
            }
        }
    }
    return Page(paragraphs, marks)
}

@Composable
private fun OpinionScreen(model: SearchModel, opinion: Opinion, onOpen: (Opinion) -> Unit, onBack: () -> Unit, jumpOnOpen: Boolean, onEngine: () -> Unit) {
    var similar by remember(opinion.id) { mutableStateOf<List<SearchHit>?>(null) }
    var heat by remember(opinion.id) { mutableStateOf<Heat?>(null) }
    var forms by remember(opinion.id) { mutableStateOf<List<String>>(emptyList()) }
    var page by remember(opinion.id) { mutableStateOf(Page(emptyList(), emptyList())) }
    var current by remember(opinion.id) { mutableStateOf<Int?>(null) }
    val sentences = remember(opinion.id) { opinion.sentences }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val voices = LocalVoices.current
    val dark = isSystemInDarkTheme()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    // Fixed items before the paragraphs: header, strip, heading, similar block,
    // "Opinion" heading, and the legend when there is one.
    val similarRows = similar?.size ?: 1
    val hasLegend = page.marks.isNotEmpty()
    val firstParagraph = 3 + similarRows + 1 + (if (hasLegend) 1 else 0)

    // Lands a mark a third of the way down the screen, however long its paragraph.
    suspend fun jump(index: Int) {
        val mark = page.marks.getOrNull(index) ?: return
        current = index
        val item = firstParagraph + mark.paragraph
        list.scrollToItem(item)
        val size = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == item }?.size ?: 0
        val viewport = list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset
        list.animateScrollToItem(item, (mark.fraction * size - viewport * 0.33f).toInt())
    }

    LaunchedEffect(opinion.id, dark) {
        // Marks first: opening the opinion runs a similarity query of its own, which
        // would otherwise replace the question being explained.
        val found = model.heat(opinion)
        heat = found
        forms = if (found == null) model.matchedForms(opinion) else emptyList()
        page = if (found != null) shaded(sentences, found, voices.highlighter, dark)
            else marked(sentences, forms, voices.highlighter, voices.highlighterInk)
        current = null
        similar = model.neighbours(opinion)
    }
    // `--ez jump true`: land on the strongest mark, for scripts and demos.
    LaunchedEffect(page, similar, jumpOnOpen) {
        if (!jumpOnOpen || similar == null || page.marks.isEmpty() || current != null) return@LaunchedEffect
        val hottest = heat?.hottest
        jump(page.marks.indexOfFirst { it.paragraph == hottest?.paragraph && it.sentence == hottest.sentence }.coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            NavBar("", onBack) {
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(opinion.sourceUri))) }) { Text("CourtListener") }
            }
            Box(Modifier.fillMaxWidth(), Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 640.dp), state = list,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = if (hasLegend) 140.dp else 40.dp)) {
                    item {
                        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(opinion.title, style = Reporter.captionLarge)
                            Text(opinion.citation, style = Reporter.body, color = secondary)
                            opinion.panel?.let { Text(it, style = Reporter.citation, color = secondary) }
                            opinion.authorLine?.let { Text("Opinion by $it", style = Reporter.citation, color = secondary) }
                            if (opinion.isUnpublished) Text("Unpublished", style = Engine.label, color = voices.oxblood)
                        }
                    }
                    item { EngineStrip(model, onEngine, Modifier.padding(bottom = 20.dp)) }
                    item { Text("Similar opinions", Modifier.padding(bottom = 4.dp), style = Reporter.sectionHeading) }
                    val hits = similar
                    if (hits == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
                    else items(hits, key = { "similar-" + it.opinion.id }) { hit ->
                        Column {
                            Row(Modifier.fillMaxWidth().clickable { onOpen(hit.opinion) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(hit.opinion.title, style = Reporter.captionSmall)
                                    Text(hit.opinion.citation, style = Reporter.small, color = secondary)
                                }
                                Text(String.format("%.3f", hit.score), style = Engine.label.copy(fontFeatureSettings = "tnum"), color = voices.slateInk)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    item { Text("Opinion", Modifier.padding(top = 24.dp, bottom = 8.dp), style = Reporter.sectionHeading) }
                    if (hasLegend) item {
                        // What the highlighter means on this page, in the engine's voice.
                        val note = heat?.let { "Shaded by closeness to “${it.question}”. The deeper the highlighter, the nearer the sentence. ${page.marks.size} passages; the arrows step through them." }
                            ?: "Highlighted: ${forms.joinToString(", ")}, the words of this opinion the engine matched. ${page.marks.size} places; the arrows step through them."
                        Surface(Modifier.fillMaxWidth().padding(bottom = 14.dp), shape = RoundedCornerShape(12.dp), color = voices.slateSurface) {
                            Text(note, Modifier.padding(12.dp), style = Engine.label, color = voices.slateInk)
                        }
                    }
                    items(page.paragraphs.size) { p ->
                        // The current mark is underlined at full strength, so the counter's
                        // "3 of 12" has a visible 3.
                        val mark = current?.let(page.marks::getOrNull)?.takeIf { it.paragraph == p }
                        val text = if (mark == null) page.paragraphs[p] else buildAnnotatedString {
                            append(page.paragraphs[p])
                            addStyle(SpanStyle(background = voices.highlighter, color = voices.highlighterInk,
                                textDecoration = TextDecoration.Underline), mark.start, mark.start + mark.length)
                        }
                        Text(text, Modifier.padding(bottom = 12.dp), style = Reporter.body)
                    }
                }
            }
        }
        // Previous and next mark, in reading order, with the position between them.
        if (page.marks.isNotEmpty()) {
            Surface(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp), shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val count = page.marks.size
                    // First press lands on the first mark going down, the last going up.
                    IconButton(onClick = { scope.launch { jump(current?.let { (it - 1 + count) % count } ?: (count - 1)) } }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous highlight", tint = voices.oxblood)
                    }
                    Text(current?.let { "${it + 1} of $count" } ?: "$count", style = Engine.label.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                        color = voices.slateInk, modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = { scope.launch { jump(current?.let { (it + 1) % count } ?: 0) } }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next highlight", tint = voices.oxblood)
                    }
                }
            }
        }
    }
}
