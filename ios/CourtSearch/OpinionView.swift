import CourtSearchKit
import SwiftUI

/// One place the query landed in the opinion: a matched word (Keyword) or a shaded
/// sentence (Meaning). `fraction` is how far down its paragraph it starts, which is
/// what lets a jump land on the mark itself inside a long paragraph.
private struct Mark: Equatable {
    let paragraph: Int
    let fraction: CGFloat
    /// Where the mark sits in its paragraph, in characters, so the current one can
    /// be underlined without rebuilding the page.
    let start: Int
    let length: Int
    /// Meaning marks only: which sentence of the paragraph.
    var sentence: Int?
}

/// The reading view. The highlighter means one thing here as everywhere: this is
/// where your query landed. Exact in Keyword mode, graded in Meaning mode; and the
/// arrows at the bottom right step through the marks in reading order.
struct OpinionView: View {
    @EnvironmentObject private var model: SearchModel
    @Environment(\.colorScheme) private var colorScheme
    let opinion: Opinion
    @Binding var showingEngine: Bool

    @State private var similar: [SearchHit]?
    @State private var heat: Heat?
    @State private var forms: [String] = []
    /// Computed once per opinion: cutting the text and ranking its sentences on
    /// every redraw would be wasted work.
    @State private var paragraphs: [AttributedString] = []
    /// The page as built, before the current mark is underlined.
    @State private var base: [AttributedString] = []
    @State private var marks: [Mark] = []
    @State private var current: Int?
    @State private var jumpTarget: Mark?

    var body: some View {
        ScrollViewReader { reader in
            ScrollView {
                // Not lazy: scrollTo in a lazy stack lands on an estimate, and a jump
                // to a mark has to land on the mark.
                VStack(alignment: .leading, spacing: 0) {
                    header
                    EngineStrip(showingEngine: $showingEngine).padding(.bottom, 20)
                    similarOpinions
                    Text("Opinion").font(.system(.headline, design: .serif)).padding(.top, 24).padding(.bottom, 8)
                    legend
                    ForEach(paragraphs.indices, id: \.self) { index in
                        Text(paragraphs[index]).font(.system(.body, design: .serif)).lineSpacing(5)
                            .padding(.bottom, 12)
                            .id(index)
                    }
                }
                .frame(maxWidth: 640, alignment: .leading)
                .padding(.horizontal, 20).padding(.vertical, 12)
                .frame(maxWidth: .infinity)
            }
            .onChange(of: jumpTarget) { _, target in
                guard let target else { return }
                // Aligning the mark's own height within its paragraph to the same
                // height of the screen puts it in view however long the paragraph is.
                // No clamping: for a paragraph taller than the screen, any other anchor
                // can push the mark off it. The insets below keep the edges clear.
                withAnimation(.snappy) {
                    reader.scrollTo(target.paragraph, anchor: UnitPoint(x: 0.5, y: target.fraction))
                }
            }
        }
        // Room at both edges, so a mark never lands under the navigation bar or the
        // navigator.
        .safeAreaInset(edge: .top, spacing: 0) { Color.clear.frame(height: marks.isEmpty ? 0 : 28) }
        .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: marks.isEmpty ? 0 : 120) }
        .overlay(alignment: .bottomTrailing) { navigator }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let url = URL(string: opinion.sourceURI) {
                ToolbarItem(placement: .topBarTrailing) { Link("CourtListener", destination: url) }
            }
        }
        .task(id: opinion.id) { await load() }
        .onChange(of: colorScheme) { _, _ in Task { await load() } }
    }

    // MARK: Pieces

    private var header: some View {
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
    }

    @ViewBuilder private var similarOpinions: some View {
        Text("Similar opinions").font(.system(.headline, design: .serif)).padding(.bottom, 4)
        if let similar {
            ForEach(similar) { hit in
                NavigationLink(value: hit.id) {
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(hit.opinion.title).font(.system(.body, design: .serif).italic()).multilineTextAlignment(.leading)
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
    }

    /// What the highlighter means on this page, in the engine's voice.
    @ViewBuilder private var legend: some View {
        if let note = legendText {
            Text(note).engineLabel()
                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.slateSurface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .padding(.bottom, 14)
        }
    }

    private var legendText: String? {
        if let heat, !marks.isEmpty {
            return "Shaded by closeness to “\(heat.question)”. The deeper the highlighter, the nearer the sentence. \(marks.count) passages; the arrows step through them."
        }
        if !forms.isEmpty {
            return "Highlighted: \(forms.joined(separator: ", ")), the words of this opinion the engine matched. \(marks.count) places; the arrows step through them."
        }
        return nil
    }

    /// Previous and next mark, in reading order, with the position between them.
    @ViewBuilder private var navigator: some View {
        if !marks.isEmpty {
            VStack(spacing: 0) {
                Button { step(-1) } label: { Image(systemName: "chevron.up").frame(width: 44, height: 40) }
                    .accessibilityLabel("Previous highlight")
                Text(current.map { "\($0 + 1) of \(marks.count)" } ?? "\(marks.count)")
                    .font(.caption2.weight(.semibold)).monospacedDigit().foregroundStyle(Theme.slateInk)
                    .frame(minWidth: 44).padding(.horizontal, 6)
                Button { step(1) } label: { Image(systemName: "chevron.down").frame(width: 44, height: 40) }
                    .accessibilityLabel("Next highlight")
            }
            .font(.body.weight(.semibold))
            .foregroundStyle(Theme.oxblood)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .shadow(color: .black.opacity(0.12), radius: 8, y: 2)
            .padding(.trailing, 16).padding(.bottom, 20)
        }
    }

    private func step(_ direction: Int) {
        guard !marks.isEmpty else { return }
        // First press lands on the first mark going down, the last going up.
        let next = current.map { ($0 + direction + marks.count) % marks.count } ?? (direction > 0 ? 0 : marks.count - 1)
        select(next)
    }

    /// Underlines the current mark, so the counter's "3 of 12" has a visible 3.
    private func select(_ index: Int) {
        if let previous = current, marks.indices.contains(previous) { paragraphs[marks[previous].paragraph] = base[marks[previous].paragraph] }
        let mark = marks[index]
        var text = base[mark.paragraph]
        let lower = text.index(text.startIndex, offsetByCharacters: mark.start)
        let upper = text.index(lower, offsetByCharacters: mark.length)
        text[lower..<upper].underlineStyle = .init(pattern: .solid, color: Theme.oxblood)
        text[lower..<upper].backgroundColor = Theme.highlighter
        text[lower..<upper].foregroundColor = Theme.highlighterInk
        paragraphs[mark.paragraph] = text
        current = index
        jumpTarget = mark
    }

    // MARK: Building the text

    private func load() async {
        let sentences = opinion.sentences
        // Marks first: opening the opinion runs a similarity query of its own, which
        // would otherwise replace the question being explained.
        heat = await model.heat(for: opinion)
        forms = heat == nil ? await model.matchedForms(in: opinion) : []
        let built = heat.map { Self.shaded(sentences, by: $0, dark: colorScheme == .dark) } ?? Self.marked(sentences, forms: forms)
        base = built.text
        paragraphs = built.text
        marks = built.marks
        current = nil
        similar = await model.neighbours(of: opinion)

        // `-jump YES`: land on the strongest mark, for scripts and demos.
        if UserDefaults.standard.bool(forKey: "jump"), !marks.isEmpty {
            try? await Task.sleep(for: .milliseconds(400))
            let strongest = heat?.hottest.flatMap { hottest in
                marks.firstIndex { $0.paragraph == hottest.paragraph && $0.sentence == hottest.sentence }
            } ?? 0
            select(strongest)
        }
    }

    /// Meaning: shade only what stands out, the top fifth of sentences by closeness,
    /// from a faint wash to the full highlighter. Everything else stays clean.
    ///
    /// A graded wash never changes the ink. In the dark a partial wash over black is
    /// a mid brown, and dark ink on it is unreadable, so the text keeps its own
    /// colour and the wash is capped where light text still reads. Dark ink belongs
    /// only on the full-strength highlighter: keyword matches and the current mark.
    private static func shaded(_ sentences: [[String]], by heat: Heat, dark: Bool) -> (text: [AttributedString], marks: [Mark]) {
        let strongestWash = dark ? 0.42 : 0.75
        let ranked = heat.similarities.flatMap { $0.compactMap { $0 } }.sorted()
        let top = ranked.last ?? 0
        let floor = ranked.count > 5 ? ranked[Int(Double(ranked.count) * 0.8)] : .infinity
        var marks: [Mark] = []
        let text = sentences.enumerated().map { p, paragraph in
            let length = max(1, paragraph.reduce(0) { $0 + $1.count + 1 })
            var offset = 0
            var built = AttributedString()
            for (n, sentence) in paragraph.enumerated() {
                var piece = AttributedString(sentence)
                let value = heat.similarities.indices.contains(p) && heat.similarities[p].indices.contains(n) ? heat.similarities[p][n] : nil
                if let value, value > floor, top > floor {
                    let shade = 0.15 + (strongestWash - 0.15) * Double((value - floor) / (top - floor))
                    piece.backgroundColor = Theme.highlighter.opacity(shade)
                    marks.append(Mark(paragraph: p, fraction: CGFloat(offset) / CGFloat(length), start: offset,
                                      length: sentence.count, sentence: n))
                }
                built += piece
                if n < paragraph.count - 1 { built += AttributedString(" ") }
                offset += sentence.count + 1
            }
            return built
        }
        return (text, marks)
    }

    /// Keyword: the full highlighter on every whole-word occurrence of a form the
    /// engine matched.
    private static func marked(_ sentences: [[String]], forms: [String]) -> (text: [AttributedString], marks: [Mark]) {
        let plain = sentences.map { $0.joined(separator: " ") }
        let pattern = forms.map(NSRegularExpression.escapedPattern(for:)).joined(separator: "|")
        guard !forms.isEmpty, let regex = try? NSRegularExpression(pattern: "(?<![\\p{L}\\p{N}])(?:\(pattern))(?![\\p{L}\\p{N}])", options: .caseInsensitive) else {
            return (plain.map { AttributedString($0) }, [])
        }
        var marks: [Mark] = []
        let text = plain.enumerated().map { p, paragraph in
            var built = AttributedString(paragraph)
            let whole = NSRange(paragraph.startIndex..., in: paragraph)
            for match in regex.matches(in: paragraph, range: whole) {
                guard let range = Range(match.range, in: paragraph),
                      let lower = AttributedString.Index(range.lowerBound, within: built),
                      let upper = AttributedString.Index(range.upperBound, within: built) else { continue }
                built[lower..<upper].backgroundColor = Theme.highlighter
                built[lower..<upper].foregroundColor = Theme.highlighterInk
                marks.append(Mark(paragraph: p, fraction: CGFloat(match.range.location) / CGFloat(max(1, whole.length)),
                                  start: paragraph.distance(from: paragraph.startIndex, to: range.lowerBound),
                                  length: paragraph.distance(from: range.lowerBound, to: range.upperBound)))
            }
            return built
        }
        return (text, marks)
    }
}
