package ai.pipestream.samples.courtsearch

import ai.pipestream.search.mobile.ProtomoltSearch
import ai.protomolt.search.mobile.v1.Mobile
import ai.protomolt.search.v1.Search
import com.google.protobuf.ByteString

/** A failure the engine reported through its MobileResponse envelope. */
class EngineException(val operation: String, val code: Int, message: String) :
    Exception("$operation: [$code] $message")

/**
 * Typed calls over the engine's byte ABI. Every call blocks until the engine
 * answers, so callers keep it off the main thread.
 */
class SearchEngine(request: Mobile.MobileOpenRequest, create: Boolean) : AutoCloseable {
    private val handle: Long

    init {
        val opened = Mobile.MobileOpenResponse.parseFrom(
            payload("open", ProtomoltSearch.nativeOpen(request.toByteArray(), create)))
        if (!opened.noEgress) throw EngineException("open", -1, "runtime did not certify no-egress")
        handle = opened.handle
    }

    fun planIndex(request: Search.PlanIndexRequest): Search.PlanIndexResponse =
        Search.PlanIndexResponse.parseFrom(
            payload("planIndex", ProtomoltSearch.nativePlanIndex(handle, request.toByteArray())))

    fun ingestMapped(batch: Mobile.MobileIngestMappedBatch): Search.IngestMappedResponse =
        Search.IngestMappedResponse.parseFrom(
            payload("ingestMapped", ProtomoltSearch.nativeIngestMapped(handle, batch.toByteArray())))

    fun query(request: Search.QueryRequest): Search.QueryResponse =
        Search.QueryResponse.parseFrom(
            payload("query", ProtomoltSearch.nativeQuery(handle, request.toByteArray())))

    fun flush() {
        payload("flush", ProtomoltSearch.nativeFlush(handle))
    }

    override fun close() {
        payload("close", ProtomoltSearch.nativeClose(handle))
    }

    private fun payload(operation: String, bytes: ByteArray): ByteString {
        val envelope = Mobile.MobileResponse.parseFrom(bytes)
        return when (envelope.outcomeCase) {
            Mobile.MobileResponse.OutcomeCase.PAYLOAD -> envelope.payload
            Mobile.MobileResponse.OutcomeCase.ERROR ->
                throw EngineException(operation, envelope.error.codeValue, envelope.error.message)
            else -> throw EngineException(operation, -1, "empty response envelope")
        }
    }
}
