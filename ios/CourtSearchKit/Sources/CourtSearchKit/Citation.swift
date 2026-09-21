import Foundation

/// A snippet the engine cut around matched words, as alternating plain and
/// highlighted runs. Runs, not offsets: the engine reports UTF-16 offsets into
/// the original text, and collapsing whitespace for display would shift them.
public struct Snippet: Sendable {
    public struct Run: Sendable {
        public let text: String
        public let highlighted: Bool
    }
    public let runs: [Run]
    public let cutAtStart: Bool
    public let cutAtEnd: Bool
}

extension Opinion {
    private static let months = ["Jan.", "Feb.", "Mar.", "Apr.", "May", "June", "July", "Aug.", "Sept.", "Oct.", "Nov.", "Dec."]

    /// `No. 08-1855 (1st Cir. Feb. 12, 2010)`
    public var citation: String {
        let parts = dateFiled.split(separator: "-").compactMap { Int($0) }
        var date = dateFiled
        if parts.count == 3, (1...12).contains(parts[1]) {
            date = "\(Self.months[parts[1] - 1]) \(parts[2]), \(parts[0])"
        }
        let docket = docketNumber.isEmpty ? "" : "No. \(docketNumber) "
        return "\(docket)(\([court, date].filter { !$0.isEmpty }.joined(separator: " ")))"
    }

    /// `Before Torruella, Selya, Howard`, or nil when the source names no panel.
    public var panel: String? { judges.isEmpty ? nil : "Before \(judges)" }

    /// `Howard, J.`, or nil when the source names no author.
    public var authorLine: String? { author.isEmpty ? nil : "\(author), J." }

    public var isUnpublished: Bool { status.caseInsensitiveCompare("Unpublished") == .orderedSame }

    /// The opinion text as real paragraphs. The source is a double-spaced PDF
    /// extraction: every LINE is followed by a blank line, so blank lines mean
    /// nothing, and a paragraph starts where a line is indented six spaces or
    /// more. Lines are joined and their spacing (the text is also justified with
    /// runs of spaces) collapsed. Every platform uses this exact rule, because the
    /// heatmap's passage vectors depend on where paragraphs begin and end.
    public var paragraphs: [String] {
        var paragraphs: [String] = []
        var current: [String] = []
        for line in body.split(separator: "\n", omittingEmptySubsequences: false) {
            let text = String(line).collapsingWhitespace
            // Blank lines, and the page markers ("-7-") the extraction left in the flow.
            if text.isEmpty || text.range(of: #"^-\s?\d+\s?-$"#, options: .regularExpression) != nil { continue }
            if line.prefix(while: { $0 == " " }).count >= 6, !current.isEmpty {
                paragraphs.append(current.joined(separator: " "))
                current = []
            }
            current.append(text)
        }
        if !current.isEmpty { paragraphs.append(current.joined(separator: " ")) }
        return paragraphs
    }
}

extension String {
    var collapsingWhitespace: String {
        split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }
}
