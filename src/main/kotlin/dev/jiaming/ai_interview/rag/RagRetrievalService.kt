package dev.jiaming.ai_interview.rag

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.Filter.Expression
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
class RagRetrievalService(
    private val vectorStoreProvider: ObjectProvider<VectorStore>,
    private val meterRegistry: MeterRegistry
) {
    /**
     * Searches only the vectors of [indexes] (each at its claim version). A blank query or no indexes returns [].
     * Throws IllegalStateException when no VectorStore is configured; vector-store errors propagate.
     */
    fun retrieve(query: String?, indexes: List<RagDocumentIndexHandle>?, topK: Int): List<RagContextSnippet> {
        val normalized = normalizedQuery(query)
        if (normalized.isEmpty() || indexes.isNullOrEmpty()) return emptyList()
        return search(SearchRequest.builder().query(normalized).topK(topK).filterExpression(filter(indexes)).build())
    }

    /** Searches only the vectors of [index]; a null index or blank query returns []. Throws IllegalStateException when no VectorStore is configured. */
    fun retrieve(query: String?, index: RagDocumentIndexHandle?, topK: Int) =
        if (index == null) emptyList() else retrieve(query, listOf(index), topK)

    fun deleteIndexClaim(indexId: UUID, claimVersion: Long) {
        val builder = FilterExpressionBuilder()
        vectorStore().delete(builder.and(builder.eq("indexId", indexId.toString()), builder.eq("claimVersion", claimVersion)).build())
    }

    fun deleteIndex(indexId: UUID) {
        val builder = FilterExpressionBuilder()
        vectorStore().delete(builder.eq("indexId", indexId.toString()).build())
    }

    private fun search(request: SearchRequest): List<RagContextSnippet> {
        val startedAt = System.nanoTime()
        try {
            val snippets = vectorStore().similaritySearch(request).map(::toSnippet)
            meterRegistry.counter("ai.rag.retrieval", "outcome", "success").increment()
            return snippets
        } catch (exception: RuntimeException) {
            meterRegistry.counter("ai.rag.retrieval", "outcome", "failed").increment()
            throw exception
        } finally {
            meterRegistry.timer("ai.rag.retrieval.duration").record(Duration.ofMillis(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)))
        }
    }

    private fun toSnippet(document: Document) = RagContextSnippet(document.id, document.text, document.metadata, document.score)

    private fun filter(indexes: List<RagDocumentIndexHandle>): Expression {
        val builder = FilterExpressionBuilder()
        var combined: FilterExpressionBuilder.Op? = null
        for (index in indexes) {
            val current = builder.and(builder.eq("indexId", index.indexId.toString()), builder.eq("claimVersion", index.claimVersion))
            combined = combined?.let { builder.or(it, current) } ?: current
        }
        return checkNotNull(combined).build()
    }

    private fun normalizedQuery(query: String?) = query?.trim() ?: ""
    private fun vectorStore() = vectorStoreProvider.getIfAvailable() ?: error("VectorStore is not available")
}
