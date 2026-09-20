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

    /// The opinion text as reflowed paragraphs: the source is hard-wrapped and
    /// centred with spaces, which reads as noise on a phone.
    public var paragraphs: [String] {
        body.components(separatedBy: "\n")
            .split(whereSeparator: { $0.trimmingCharacters(in: .whitespaces).isEmpty })
            .map { $0.joined(separator: " ").collapsingWhitespace }
            .filter { !$0.isEmpty }
    }
}

extension String {
    var collapsingWhitespace: String {
        split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }
}
