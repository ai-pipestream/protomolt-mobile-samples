import Foundation
import SwiftProtobuf


/// A failure the engine reported through its MobileResponse envelope, or an
/// envelope that carried neither payload nor error.
public struct EngineError: Error, CustomStringConvertible {
    public let operation: String
    public let code: Int
    public let message: String
    public var description: String { "\(operation): [\(code)] \(message)" }
}

/// Typed calls over the engine's byte ABI. Every call blocks until the engine
/// answers, so callers keep it off the main thread (CourtIndex is an actor).
final class SearchEngine {
    private(set) var handle: UInt64

    init(open request: Ai_Protomolt_Search_Mobile_V1_MobileOpenRequest, create: Bool) throws {
        let opened: Ai_Protomolt_Search_Mobile_V1_MobileOpenResponse = try Self.decode(
            "open", ProtomoltSearchMobile.open(try request.serializedData(), create: create))
        guard opened.noEgress else {
            throw EngineError(operation: "open", code: -1, message: "runtime did not certify no-egress")
        }
        handle = opened.handle
    }

    func planIndex(_ request: Ai_Protomolt_Search_V1_PlanIndexRequest) throws -> Ai_Protomolt_Search_V1_PlanIndexResponse {
        try Self.decode("planIndex", ProtomoltSearchMobile.planIndex(handle: handle, request: try request.serializedData()))
    }

    func ingestMapped(_ batch: Ai_Protomolt_Search_Mobile_V1_MobileIngestMappedBatch) throws -> Ai_Protomolt_Search_V1_IngestMappedResponse {
        try Self.decode("ingestMapped", ProtomoltSearchMobile.ingestMapped(handle: handle, request: try batch.serializedData()))
    }

    func query(_ request: Ai_Protomolt_Search_V1_QueryRequest) throws -> Ai_Protomolt_Search_V1_QueryResponse {
        try Self.decode("query", ProtomoltSearchMobile.query(handle: handle, request: try request.serializedData()))
    }

    func flush() throws {
        let _: Ai_Protomolt_Search_Mobile_V1_MobileFlushResponse = try Self.decode(
            "flush", ProtomoltSearchMobile.flush(handle: handle))
    }

    func close() throws {
        let _: Ai_Protomolt_Search_Mobile_V1_MobileCloseResponse = try Self.decode(
            "close", ProtomoltSearchMobile.close(handle: handle))
    }

    private static func decode<T: SwiftProtobuf.Message>(_ operation: String, _ bytes: Data) throws -> T {
        let envelope = try Ai_Protomolt_Search_Mobile_V1_MobileResponse(serializedBytes: bytes)
        switch envelope.outcome {
        case .payload(let payload):
            return try T(serializedBytes: payload)
        case .error(let error):
            throw EngineError(operation: operation, code: error.code.rawValue, message: error.message)
        case nil:
            throw EngineError(operation: operation, code: -1, message: "empty response envelope")
        }
    }
}
