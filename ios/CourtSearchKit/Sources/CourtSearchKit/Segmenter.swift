import Foundation

/// Cuts a paragraph into sentences for the heatmap. A port of
/// tools/passages_reference.py, which is the specification: every platform must
/// cut identically, because each unit gets its own vector, and the tests assert
/// the unit count that script prints. ASCII rules on Unicode scalars, on purpose,
/// so that no platform's Unicode tables can make them disagree.
enum Segmenter {
    /// Shorter sentences are merged into their neighbour; shorter units are not embedded.
    static let minimumUnit = 40

    private static let abbreviations: Set<String> = [
        "v", "vs", "no", "nos", "cir", "inc", "co", "corp", "ltd", "llc", "supp", "stat", "sec", "art", "cf", "id",
        "ibid", "see", "mr", "mrs", "ms", "dr", "hon", "jr", "sr", "st", "ch", "para", "p", "pp", "n", "al", "ed", "op",
        "cit", "app", "mass", "cal", "ins", "cas", "assoc", "bros", "mfg", "dist", "div", "dep't", "gov't", "ass'n",
        "int'l", "nat'l", "e.g", "i.e", "u.s", "u.s.c", "r.i",
    ]
    private static let openers = Set("\"'([\u{201C}\u{2018}".unicodeScalars)
    private static let closers = Set("\"')]\u{201D}\u{2019}".unicodeScalars)
    private static let enders = Set(".?!".unicodeScalars)
    private static let space: Unicode.Scalar = " "

    static func sentences(_ paragraph: String) -> [String] {
        let text = Array(paragraph.unicodeScalars)
        let count = text.count
        var cuts: [Int] = []
        var i = 0
        while i < count {
            guard enders.contains(text[i]) else { i += 1; continue }
            var j = i + 1
            while j < count, closers.contains(text[j]) { j += 1 }
            if j < count, text[j] == space, j + 1 < count {
                let next = text[j + 1]
                let starts = ("A"..."Z").contains(next) || openers.contains(next)
                var k = i
                while k > 0, text[k - 1] != space { k -= 1 }
                var word = Array(text[k..<i])
                while let first = word.first, openers.contains(first) { word.removeFirst() }
                let lowered = String(String.UnicodeScalarView(word)).lowercased()
                let abbreviation = text[i] == "." && (word.count <= 1 || abbreviations.contains(lowered))
                if starts, !abbreviation { cuts.append(j) }
            }
            i = j
        }
        var parts: [[Unicode.Scalar]] = []
        var start = 0
        for cut in cuts {
            parts.append(Array(text[start..<cut]))
            start = cut + 1
        }
        parts.append(Array(text[start...]))

        var merged: [[Unicode.Scalar]] = []
        for part in parts {
            if let last = merged.last, part.count < minimumUnit || last.count < minimumUnit {
                merged[merged.count - 1] = last + [space] + part
            } else {
                merged.append(part)
            }
        }
        return merged.map { String(String.UnicodeScalarView($0)) }
    }
}

extension Opinion {
    /// Paragraphs, each cut into sentences: the units the heatmap shades.
    public var sentences: [[String]] { paragraphs.map(Segmenter.sentences) }
}
