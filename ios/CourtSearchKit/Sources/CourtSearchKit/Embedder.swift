import CourtEmbedder
import Foundation

/// Text to unit vector, on the device: the sample's own Rust embedder
/// (embedder-ffi) over a Model2Vec table. The engine ships no embedder by
/// design, so the app is the caller that supplies vectors.
final class Embedder {
    private let handle: OpaquePointer
    let dimensions: Int

    init(directory: URL) throws {
        guard let handle = court_embedder_open(directory.path) else {
            throw EngineError(operation: "embedder", code: -1, message: String(cString: court_embedder_last_error()))
        }
        self.handle = handle
        dimensions = court_embedder_dim(handle)
    }

    deinit { court_embedder_close(handle) }

    /// WordPiece pieces the text tokenizes to, `[UNK]`s included.
    func pieces(_ text: String) -> Int {
        text.utf8CString.withUnsafeBufferPointer { bytes in
            bytes.withMemoryRebound(to: UInt8.self) { court_embedder_pieces(handle, $0.baseAddress, $0.count - 1) }
        }
    }

    /// Nil when the text has no vector: empty, or entirely out of vocabulary.
    func embed(_ text: String) throws -> [Float]? {
        var vector = [Float](repeating: 0, count: dimensions)
        let written = text.utf8CString.withUnsafeBufferPointer { bytes in
            bytes.withMemoryRebound(to: UInt8.self) { utf8 in
                // utf8CString carries a trailing NUL the embedder must not see.
                court_embedder_embed(handle, utf8.baseAddress, utf8.count - 1, &vector, vector.count)
            }
        }
        if written < 0 {
            throw EngineError(operation: "embed", code: -1, message: String(cString: court_embedder_last_error()))
        }
        return written == 0 ? nil : vector
    }
}
