import CourtSearchKit
import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: SearchModel

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
            .navigationDestination(for: String.self) { id in
                if let opinion = model.opinions.first(where: { $0.id == id }) {
                    OpinionView(opinion: opinion)
                }
            }
        }
        .searchable(text: $model.query, prompt: "Search opinion text, e.g. habeas")
        .onSubmit(of: .search) { Task { await model.search() } }
        .onChange(of: model.query) { text in
            if text.isEmpty { Task { await model.search() } }
        }
    }

    private var list: some View {
        List {
            if let results = model.results {
                Section("\(results.count) matches · BM25 over opinion bodies") {
                    ForEach(results) { hit in
                        OpinionRow(opinion: hit.opinion, score: hit.score)
                    }
                }
            } else {
                Section("\(model.opinions.count) opinions") {
                    ForEach(model.opinions) { OpinionRow(opinion: $0, score: nil) }
                }
            }
            if let stats = model.stats {
                Section("Index") {
                    LabeledContent("On disk", value: ByteCountFormatter.string(fromByteCount: stats.bytesOnDisk, countStyle: .file))
                    LabeledContent("Ingest", value: stats.ingestSeconds.map { String(format: "%.2f s this launch", $0) } ?? "reopened, nothing ingested")
                    LabeledContent("Network", value: "none — the engine links no sockets")
                }
            }
        }
    }
}

struct OpinionRow: View {
    let opinion: Opinion
    let score: Float?

    var body: some View {
        NavigationLink(value: opinion.id) {
            HStack {
                Text(opinion.title)
                Spacer()
                if let score {
                    Text(String(format: "%.3f", score)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
        }
    }
}

struct OpinionView: View {
    @EnvironmentObject private var model: SearchModel
    let opinion: Opinion
    @State private var similar: [SearchHit] = []

    var body: some View {
        List {
            Section("Similar opinions · nearest neighbours of this opinion's vector") {
                if similar.isEmpty { ProgressView() }
                ForEach(similar) { OpinionRow(opinion: $0.opinion, score: $0.score) }
            }
            Section("Opinion") {
                if let url = URL(string: opinion.sourceURI) {
                    Link("CourtListener", destination: url)
                }
                Text(opinion.body.prefix(4000)).font(.footnote)
            }
        }
        .navigationTitle(opinion.title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: opinion.id) { similar = await model.neighbours(of: opinion) }
    }
}
