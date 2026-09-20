import CourtSearchKit
import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: SearchModel
    @State private var showingEngine = false

    var body: some View {
        NavigationStack {
            Group {
                switch model.phase {
                case .opening:
                    ProgressView("Building the on-device index…")
                case .failed(let message):
                    ScrollView { Text(message).font(.callout.monospaced()).padding() }
                case .ready:
                    list
                }
            }
            .navigationTitle("Court Search")
            .navigationDestination(for: BrowseRoute.self) { _ in BrowseView() }
            .navigationDestination(for: String.self) { id in
                if let opinion = model.opinions.first(where: { $0.id == id }) {
                    OpinionView(opinion: opinion, showingEngine: $showingEngine)
                }
            }
            .searchable(text: $model.query, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: "Search \(model.opinions.count) opinions")
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
        }
        .sheet(isPresented: $showingEngine) { EnginePanel().environmentObject(model) }
    }

    private var list: some View {
        List {
            EngineStrip(showingEngine: $showingEngine, showsLastQuery: model.results != nil)
                .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 8, trailing: 16))
                .listRowSeparator(.hidden)

            if let results = model.results {
                if results.isEmpty {
                    NoMatches().listRowSeparator(.hidden)
                } else {
                    ForEach(results) { hit in
                        NavigationLink(value: hit.id) { CaseRow(opinion: hit.opinion, hit: hit) }
                    }
                }
            } else {
                StartCard().listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .animation(.snappy(duration: 0.25), value: model.results?.map(\.id))
    }
}

struct BrowseRoute: Hashable {}

/// What the app shows before a search: what to do, words that work, and the
/// way into similarity search. Results replace it as soon as a word is typed.
struct StartCard: View {
    @EnvironmentObject private var model: SearchModel

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Search the opinions").font(.system(.title2, design: .serif).weight(.semibold))
                Text("Type a word or phrase from an opinion. Results appear as you type, and every search runs on this phone.")
                    .font(.system(.body, design: .serif)).foregroundStyle(.secondary)
            }
            VStack(alignment: .leading, spacing: 10) {
                Text("Try one of these").font(.subheadline).foregroundStyle(.secondary)
                SuggestionChips()
            }
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                Text("Looking for cases like one you know? Open an opinion to see the ones closest to it in meaning.")
                    .font(.system(.body, design: .serif)).foregroundStyle(.secondary)
                NavigationLink(value: BrowseRoute()) {
                    Text("Browse all \(model.opinions.count) opinions").font(.body.weight(.medium))
                }
                .foregroundStyle(Theme.oxblood)
            }
        }
        .padding(.vertical, 12)
    }
}

struct SuggestionChips: View {
    @EnvironmentObject private var model: SearchModel
    @State private var taps = 0

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(SearchModel.suggestions, id: \.self) { word in
                Button {
                    taps += 1
                    model.query = word
                } label: {
                    Text(word).font(.system(.callout, design: .serif))
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Theme.chipFill, in: Capsule())
                        .foregroundStyle(Theme.oxblood)
                }
                .buttonStyle(.plain)
            }
        }
        .sensoryFeedback(.selection, trigger: taps)
    }
}

struct NoMatches: View {
    @EnvironmentObject private var model: SearchModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("No opinion has a word starting with “\(model.query.trimmingCharacters(in: .whitespaces))”")
                .font(.system(.title3, design: .serif))
            Text("Search looks at the words of each opinion, not at case names. These all find something:")
                .font(.system(.body, design: .serif)).foregroundStyle(.secondary)
            SuggestionChips()
        }
        .padding(.vertical, 12)
    }
}

struct BrowseView: View {
    @EnvironmentObject private var model: SearchModel

    var body: some View {
        List(model.opinions) { opinion in
            NavigationLink(value: opinion.id) { CaseRow(opinion: opinion, hit: nil) }
        }
        .listStyle(.plain)
        .navigationTitle("All opinions")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Wraps its children onto as many lines as they need.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(subviews, width: proposal.width ?? .infinity)
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0,
                      height: rows.last.map { $0.y + $0.height } ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in arrange(subviews, width: bounds.width) {
            for item in row.items {
                subviews[item.index].place(at: CGPoint(x: bounds.minX + item.x, y: bounds.minY + row.y), proposal: .unspecified)
            }
        }
    }

    private struct Row { var items: [(index: Int, x: CGFloat)] = []; var y: CGFloat = 0; var height: CGFloat = 0; var width: CGFloat = 0 }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows = [Row()]
        for (index, subview) in subviews.enumerated() {
            let size = subview.sizeThatFits(.unspecified)
            if rows[rows.count - 1].width + size.width > width, !rows[rows.count - 1].items.isEmpty {
                let last = rows[rows.count - 1]
                rows.append(Row(y: last.y + last.height + spacing))
            }
            rows[rows.count - 1].items.append((index, rows[rows.count - 1].width))
            rows[rows.count - 1].width += size.width + spacing
            rows[rows.count - 1].height = max(rows[rows.count - 1].height, size.height)
        }
        return rows
    }
}

/// A case as a citation. With a hit: the engine's snippet, the author, the score.
/// Without: the panel line.
struct CaseRow: View {
    let opinion: Opinion
    let hit: SearchHit?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(opinion.title).font(.system(.title3, design: .serif).italic())
            Text(opinion.citation).font(.system(.subheadline, design: .serif)).foregroundStyle(.secondary)
            if let snippet = hit?.snippet {
                SnippetText(snippet: snippet).padding(.top, 4)
            } else if hit == nil, let panel = opinion.panel {
                Text(panel).font(.system(.footnote, design: .serif)).foregroundStyle(.secondary)
            }
            if hit != nil || opinion.isUnpublished {
                HStack(spacing: 8) {
                    if let author = opinion.authorLine, hit != nil {
                        Text(author).font(.system(.footnote, design: .serif)).foregroundStyle(.secondary)
                    }
                    if opinion.isUnpublished {
                        Text("Unpublished").font(.caption).foregroundStyle(Theme.oxblood)
                    }
                    Spacer()
                    if let hit { Text(String(format: "%.3f", hit.score)).engineLabel().monospacedDigit() }
                }
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 6)
    }
}

/// The engine's snippet: serif, with the highlighter on the words it matched.
struct SnippetText: View {
    let snippet: Snippet

    var body: some View {
        Text(attributed).font(.system(.callout, design: .serif)).lineSpacing(3)
    }

    private var attributed: AttributedString {
        var text = AttributedString(snippet.cutAtStart ? "…" : "")
        for run in snippet.runs {
            var piece = AttributedString(run.text)
            if run.highlighted {
                piece.backgroundColor = Theme.highlighter
                piece.foregroundColor = Theme.highlighterInk
            }
            text += piece
        }
        if snippet.cutAtEnd { text += AttributedString("…") }
        return text
    }
}

/// One slate row: what the engine just did, or what it holds before any query.
struct EngineStrip: View {
    @EnvironmentObject private var model: SearchModel
    @Binding var showingEngine: Bool
    /// The strip describes what is on screen: a query's numbers beside its results,
    /// the index's own facts beside the start card.
    var showsLastQuery = true

    var body: some View {
        Button { showingEngine = true } label: {
            HStack(alignment: .firstTextBaseline) {
                if showsLastQuery, let last = model.lastQuery, let index = model.index {
                    metric(String(format: "%.1f ms", last.engineMilliseconds), "engine time")
                    metric("\(last.hits) of \(index.documents)", "opinions")
                    metric(EngineStrip.routeName(last.route), "query")
                } else if let index = model.index {
                    metric("\(index.documents)", "opinions")
                    metric(ByteCountFormatter.string(fromByteCount: index.bytesOnDisk, countStyle: .file), "on disk")
                    metric("\(index.vectorDimensions)-dim", "vectors")
                }
                metric("On device", "no network")
                Image(systemName: "chevron.up").font(.caption.weight(.semibold)).foregroundStyle(Theme.slateInk)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(Theme.slateSurface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .animation(.snappy(duration: 0.25), value: model.lastQuery?.engineMilliseconds)
        .accessibilityLabel("Engine details")
    }

    private func metric(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value).engineValue().lineLimit(1).minimumScaleFactor(0.8)
                .contentTransition(.numericText())
            Text(label).engineLabel().lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    static func routeName(_ route: String) -> String {
        switch route {
        case "bm25_search": "Keyword"
        case "search": "Similarity"
        default: route
        }
    }
}

struct EnginePanel: View {
    @EnvironmentObject private var model: SearchModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if let last = model.lastQuery {
                    Section {
                        row("Route", last.route)
                        row("Engine time", String(format: "%.2f ms", last.engineMilliseconds))
                        row("Selection", String(format: "%.2f ms", last.selectionMilliseconds))
                        row("Round trip", String(format: "%.2f ms", last.roundTripMilliseconds))
                        row("Hits", "\(last.hits)")
                        row("Segments", "\(last.segments)")
                        row("Shards", "\(last.shards)")
                    } header: {
                        Text("Last query")
                    } footer: {
                        Text("Engine time is what the engine reports. Round trip is measured by the app and adds protobuf encoding and the call across the language boundary.")
                    }
                }
                if let index = model.index {
                    Section("Index") {
                        row("Opinions", "\(index.documents)")
                        row("Vectors", "\(index.documents) × \(index.vectorDimensions)")
                        row("On disk", ByteCountFormatter.string(fromByteCount: index.bytesOnDisk, countStyle: .file))
                        row(index.ingestSeconds == nil ? "Opened" : "Built in",
                            index.ingestSeconds.map { String(format: "%.2f s", $0) } ?? "from disk, nothing ingested")
                        row("Plan fingerprint", String(index.planFingerprint.prefix(12)))
                    }
                }
                Section("Privacy") {
                    Text("No network permission. The engine links no networking code.")
                        .font(.subheadline).foregroundStyle(Theme.slateInk)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.slateSurface)
            .navigationTitle("Engine")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ label: String, _ value: String) -> some View {
        LabeledContent(label) { Text(value).monospacedDigit().foregroundStyle(Theme.slateInk) }
    }
}

struct OpinionView: View {
    @EnvironmentObject private var model: SearchModel
    let opinion: Opinion
    @Binding var showingEngine: Bool
    @State private var similar: [SearchHit]?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(opinion.title).font(.system(.title, design: .serif).italic())
                    Text(opinion.citation).font(.system(.body, design: .serif)).foregroundStyle(.secondary)
                    if let panel = opinion.panel {
                        Text(panel).font(.system(.subheadline, design: .serif)).foregroundStyle(.secondary)
                    }
                    if let author = opinion.authorLine {
                        Text("Opinion by \(author)").font(.system(.subheadline, design: .serif)).foregroundStyle(.secondary)
                    }
                    if opinion.isUnpublished { Text("Unpublished").font(.caption).foregroundStyle(Theme.oxblood) }
                }
                .padding(.bottom, 16)

                EngineStrip(showingEngine: $showingEngine).padding(.bottom, 20)

                Text("Similar opinions").font(.system(.headline, design: .serif)).padding(.bottom, 4)
                if let similar {
                    ForEach(similar) { hit in
                        NavigationLink(value: hit.id) {
                            HStack(alignment: .firstTextBaseline) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(hit.opinion.title).font(.system(.body, design: .serif).italic())
                                        .multilineTextAlignment(.leading)
                                    Text(hit.opinion.citation).font(.system(.footnote, design: .serif)).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(String(format: "%.3f", hit.score)).engineLabel().monospacedDigit()
                            }
                            .padding(.vertical, 8)
                        }
                        .buttonStyle(.plain)
                        Divider()
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding()
                }

                Text("Opinion").font(.system(.headline, design: .serif)).padding(.top, 24).padding(.bottom, 8)
                ForEach(Array(opinion.paragraphs.enumerated()), id: \.offset) { _, paragraph in
                    Text(paragraph).font(.system(.body, design: .serif)).lineSpacing(5).padding(.bottom, 12)
                }
            }
            .frame(maxWidth: 640, alignment: .leading)
            .padding(.horizontal, 20).padding(.vertical, 12)
            .frame(maxWidth: .infinity)
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let url = URL(string: opinion.sourceURI) {
                ToolbarItem(placement: .topBarTrailing) { Link("CourtListener", destination: url) }
            }
        }
        .task(id: opinion.id) { similar = await model.neighbours(of: opinion) }
    }
}
