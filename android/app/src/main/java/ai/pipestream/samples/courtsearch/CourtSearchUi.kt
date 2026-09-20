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

private sealed interface Screen {
    data object Search : Screen
    data object Browse : Screen
    data class Reading(val opinion: Opinion) : Screen
}

/** DESIGN.md's three screens and the engine panel, which any of them can open. */
@Composable
fun CourtSearchApp(model: SearchModel) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Search) }
    var showingEngine by remember { mutableStateOf(false) }
    val open: (Opinion) -> Unit = { stack += Screen.Reading(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1, onBack = back)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(stack.last(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { screen ->
            when (screen) {
                Screen.Search -> SearchScreen(model, open, onBrowse = { stack += Screen.Browse }) { showingEngine = true }
                Screen.Browse -> BrowseScreen(model, open, back)
                is Screen.Reading -> OpinionScreen(model, screen.opinion, open, back) { showingEngine = true }
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
            item(key = "strip") { EngineStrip(model, onEngine, Modifier.padding(top = 12.dp, bottom = 8.dp), showsLastQuery = model.results != null) }
            val results = model.results
            when {
                results == null -> item(key = "start") { StartCard(model, onBrowse, Modifier.animateItem()) }
                results.isEmpty() -> item(key = "none") { NoMatches(model, Modifier.animateItem()) }
                else -> items(results, key = { it.opinion.id }) { hit ->
                    Column(Modifier.animateItem()) {
                        CaseRow(hit.opinion, hit, onOpen)
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
        placeholder = { Text("Search ${model.opinions.size} opinions") },
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
            if (last != null) {
                Metric(String.format("%.1f ms", last.engineMilliseconds), "engine time")
                Metric("${last.hits} of ${index.documents}", "opinions")
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
            Text("Search the opinions", style = Reporter.heading)
            Text("Type a word or phrase from an opinion. Results appear as you type, and every search runs on this phone.",
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
        for (word in SearchModel.suggestions) {
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
        Text("No opinion has a word starting with “${model.query.trim()}”", style = Reporter.caption.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Normal))
        Text("Search looks at the words of each opinion, not at case names. These all find something:",
            style = Reporter.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SuggestionChips(model)
    }
}

/** A case as a citation. With a hit: the engine's snippet, the author, the score. Without: the panel line. */
@Composable
private fun CaseRow(opinion: Opinion, hit: SearchHit?, onOpen: (Opinion) -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clickable { onOpen(opinion) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(opinion.title, style = Reporter.caption)
            Text(opinion.citation, style = Reporter.citation, color = secondary)
            val snippet = hit?.snippet
            if (snippet != null) SnippetText(snippet, Modifier.padding(top = 4.dp))
            else if (hit == null) opinion.panel?.let { Text(it, style = Reporter.small, color = secondary) }
            if (hit != null || opinion.isUnpublished) {
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hit != null) opinion.authorLine?.let { Text(it, style = Reporter.small, color = secondary) }
                    if (opinion.isUnpublished) Text("Unpublished", style = Engine.label, color = LocalVoices.current.oxblood)
                    Spacer(Modifier.weight(1f))
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
                    PanelGroup(listOf(
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

@Composable
private fun OpinionScreen(model: SearchModel, opinion: Opinion, onOpen: (Opinion) -> Unit, onBack: () -> Unit, onEngine: () -> Unit) {
    var similar by remember(opinion.id) { mutableStateOf<List<SearchHit>?>(null) }
    LaunchedEffect(opinion.id) { similar = model.neighbours(opinion) }
    val context = LocalContext.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        NavBar("", onBack) {
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(opinion.sourceUri))) }) { Text("CourtListener") }
        }
        Box(Modifier.fillMaxWidth(), Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
                item {
                    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(opinion.title, style = Reporter.captionLarge)
                        Text(opinion.citation, style = Reporter.body, color = secondary)
                        opinion.panel?.let { Text(it, style = Reporter.citation, color = secondary) }
                        opinion.authorLine?.let { Text("Opinion by $it", style = Reporter.citation, color = secondary) }
                        if (opinion.isUnpublished) Text("Unpublished", style = Engine.label, color = LocalVoices.current.oxblood)
                    }
                }
                item { EngineStrip(model, onEngine, Modifier.padding(bottom = 20.dp)) }
                item { Text("Similar opinions", Modifier.padding(bottom = 4.dp), style = Reporter.sectionHeading) }
                val hits = similar
                if (hits == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
                else items(hits, key = { "similar-" + it.opinion.id }) { hit ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(hit.opinion) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(hit.opinion.title, style = Reporter.captionSmall)
                            Text(hit.opinion.citation, style = Reporter.small, color = secondary)
                        }
                        Text(String.format("%.3f", hit.score), style = Engine.label.copy(fontFeatureSettings = "tnum"), color = LocalVoices.current.slateInk)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item { Text("Opinion", Modifier.padding(top = 24.dp, bottom = 8.dp), style = Reporter.sectionHeading) }
                items(opinion.paragraphs.size) { i -> Text(opinion.paragraphs[i], Modifier.padding(bottom = 12.dp), style = Reporter.body) }
            }
        }
    }
}
