package dev.jiaming.ai_interview.rag

import dev.jiaming.ai_interview.document.DocumentChunk
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class RagIndexingService private constructor(
    private val vectorStoreProvider: ObjectProvider<VectorStore>,
    private val repository: RagDocumentIndexRepository,
    private val retrievalService: RagRetrievalService,
    private val properties: RagProperties,
    private val chunker: SectionAwareTextChunker,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    private val embeddingModel: String
) {
    @Autowired
    internal constructor(
        vectorStoreProvider: ObjectProvider<VectorStore>, repository: RagDocumentIndexRepository,
        retrievalService: RagRetrievalService, properties: RagProperties, chunker: SectionAwareTextChunker,
        meterRegistry: MeterRegistry, environment: Environment
    ) : this(vectorStoreProvider, repository, retrievalService, properties, chunker, meterRegistry, Clock.systemUTC(),
        environment.getProperty("spring.ai.google.genai.embedding.text.options.model", properties.embeddingModel()))

    internal constructor(
        vectorStoreProvider: ObjectProvider<VectorStore>, repository: RagDocumentIndexRepository,
        retrievalService: RagRetrievalService, properties: RagProperties, chunker: SectionAwareTextChunker,
        meterRegistry: MeterRegistry, clock: Clock
    ) : this(vectorStoreProvider, repository, retrievalService, properties, chunker, meterRegistry, clock, properties.embeddingModel())

    /** Synchronously reads/writes shared index claims and vectors for RAW document text; returns a ready handle or empty for local fallback.
     * Indexing failures attempt claim/vector cleanup; interruption restores the thread flag and rethrows the runtime failure.
     * Claim acquisition/read failures propagate.
     */
    fun ensureIndexed(document: ResolvedDocument): java.util.Optional<RagDocumentIndexHandle> {
        if (vectorStoreProvider.getIfAvailable() == null) return java.util.Optional.empty()
        val identity = RagDocumentIndexIdentity(document.sourceType(), document.contentHash(), embeddingModel,
            properties.embeddingDimensions(), properties.chunkSchema())
        val now = clock.instant()
        val candidateId = UUID.randomUUID()
        if (repository.insertClaim(candidateId, identity, now)) {
            record("created", document)
            return indexOwnedClaim(document, candidateId, 1L)
        }
        val current = repository.find(identity).orElse(null) ?: return java.util.Optional.empty()
        if (current.status == RagDocumentIndexStatus.READY) {
            repository.touchReady(current.indexId, current.claimVersion, now)
            record("reused", document)
            return java.util.Optional.of(RagDocumentIndexHandle(current.indexId, current.claimVersion))
        }
        if (eligibleForTakeover(current, now) && repository.takeOver(current, now, now.minus(STALE_INDEXING), now.minus(FAILED_RETRY))) {
            val claimVersion = current.claimVersion + 1
            deletePreviousClaims(current.indexId)
            record("takeover", document)
            return indexOwnedClaim(document, current.indexId, claimVersion)
        }
        record("local_fallback", document)
        return java.util.Optional.empty()
    }

    fun cleanupStale(retention: Duration, limit: Int): Int {
        val now = clock.instant()
        val cutoff = now.minus(retention)
        val deletingCutoff = now.minus(STALE_DELETING)
        var deleted = 0
        for (index in repository.cleanupCandidates(cutoff, deletingCutoff, limit)) {
            val claimVersion = repository.claimDeleting(index, cutoff, deletingCutoff, now).orElse(-1L)
            if (claimVersion < 0) continue
            try {
                retrievalService.deleteIndex(index.indexId)
                repository.deleteClaimed(index.indexId, claimVersion)
                deleted++
            } catch (exception: RuntimeException) {
                repository.restoreDeleteFailure(index.indexId, claimVersion, clock.instant())
                log.warn("rag_index_cleanup_failed indexId={} claimVersion={}", index.indexId, claimVersion)
            }
        }
        meterRegistry.counter("ai.rag.cleanup", "outcome", "deleted").increment(deleted.toDouble())
        return deleted
    }

    private fun indexOwnedClaim(resolvedDocument: ResolvedDocument, indexId: UUID, claimVersion: Long): java.util.Optional<RagDocumentIndexHandle> {
        val documents = documents(resolvedDocument, chunks(resolvedDocument), indexId, claimVersion)
        try {
            val vectorStore = vectorStoreProvider.getIfAvailable()
            if (vectorStore == null) {
                markFailed(indexId, claimVersion, "VECTOR_STORE_UNAVAILABLE", resolvedDocument)
                return java.util.Optional.empty()
            }
            if (documents.isNotEmpty()) {
                vectorStore.delete(documents.map { it.id })
                vectorStore.add(documents)
            }
            if (!repository.markReady(indexId, claimVersion, documents.size, clock.instant())) {
                retrievalService.deleteIndexClaim(indexId, claimVersion)
                record("claim_lost", resolvedDocument)
                log.info("rag_index_claim_lost indexId={} claimVersion={}", indexId, claimVersion)
                return java.util.Optional.empty()
            }
            record("ready", resolvedDocument)
            log.info("rag_index_ready indexId={} claimVersion={} sourceType={} documents={} model={}",
                indexId, claimVersion, resolvedDocument.sourceType(), documents.size, embeddingModel)
            return java.util.Optional.of(RagDocumentIndexHandle(indexId, claimVersion))
        } catch (exception: RuntimeException) {
            val interrupted = Thread.currentThread().isInterrupted ||
                generateSequence<Throwable>(exception) { it.cause }.any { it is InterruptedException }
            try {
                markFailed(indexId, claimVersion, "VECTOR_INDEX_FAILED", resolvedDocument)
                retrievalService.deleteIndexClaim(indexId, claimVersion)
            } catch (cleanupException: RuntimeException) {
                log.warn("rag_index_failure_cleanup_failed indexId={} claimVersion={}", indexId, claimVersion)
            }
            log.warn("rag_index_failed indexId={} claimVersion={} sourceType={}", indexId, claimVersion, resolvedDocument.sourceType())
            if (interrupted || Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                throw exception
            }
            return java.util.Optional.empty()
        }
    }

    private fun markFailed(indexId: UUID, claimVersion: Long, errorCode: String, document: ResolvedDocument) {
        if (!repository.markFailed(indexId, claimVersion, errorCode, clock.instant())) record("claim_lost", document)
        else record("failed", document)
    }

    private fun deletePreviousClaims(indexId: UUID) {
        try { retrievalService.deleteIndex(indexId) }
        catch (exception: RuntimeException) { log.warn("rag_index_takeover_cleanup_failed indexId={}", indexId) }
    }

    private fun eligibleForTakeover(index: RagDocumentIndex, now: Instant): Boolean = when (index.status) {
        RagDocumentIndexStatus.INDEXING -> index.indexingStartedAt == null || index.indexingStartedAt.isBefore(now.minus(STALE_INDEXING))
        RagDocumentIndexStatus.FAILED -> index.updatedAt.isBefore(now.minus(FAILED_RETRY))
        else -> false
    }

    private fun chunks(document: ResolvedDocument): List<DocumentChunk> = if (document.persistedChunks().isNotEmpty()) document.persistedChunks()
        else chunker.chunk(document.normalizedText()).map { chunk ->
            DocumentChunk(chunk.index, chunk.section, chunk.content, RagContextId.forChunk(document.sourceType().metadataValue(), chunk))
        }

    private fun documents(document: ResolvedDocument, chunks: List<DocumentChunk>, indexId: UUID, claimVersion: Long): List<Document> =
        chunks.map { chunk ->
            val contextId = if (chunk.contextId.isNullOrBlank()) RagContextId.forChunk(document.sourceType().metadataValue(), chunk.section, chunk.index) else chunk.contextId
            val id = UUID.nameUUIDFromBytes("$indexId:$claimVersion:$contextId".toByteArray(StandardCharsets.UTF_8)).toString()
            val metadata = linkedMapOf<String, Any>("indexId" to indexId.toString(), "claimVersion" to claimVersion,
                "contextId" to contextId, "sourceType" to document.sourceType().metadataValue(), "section" to chunk.section, "chunkIndex" to chunk.index)
            Document(id, "Section: ${chunk.section}\n${chunk.content}", metadata)
        }

    private fun record(outcome: String, document: ResolvedDocument) {
        meterRegistry.counter("ai.rag.index", "outcome", outcome, "source", document.sourceType().metadataValue()).increment()
    }

    companion object {
        private val log = LoggerFactory.getLogger(RagIndexingService::class.java)
        private val STALE_INDEXING = Duration.ofMinutes(10)
        private val FAILED_RETRY = Duration.ofMinutes(5)
        private val STALE_DELETING = Duration.ofMinutes(10)
    }
}
