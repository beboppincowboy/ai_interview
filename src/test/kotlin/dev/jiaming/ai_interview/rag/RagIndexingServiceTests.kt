package dev.jiaming.ai_interview.rag

import dev.jiaming.ai_interview.document.DocumentChunk
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.fit.JobFitEvidenceRetriever
import dev.jiaming.ai_interview.fit.JobFitFrozenSource
import dev.jiaming.ai_interview.fit.JobFitSourceSnapshot
import dev.jiaming.ai_interview.fit.JobFitSourceType
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.Mockito
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.OptionalLong
import java.util.UUID

class RagIndexingServiceTests {

    private val vectorStore = Mockito.mock(VectorStore::class.java)

    @Suppress("UNCHECKED_CAST")
    private val vectorStoreProvider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<VectorStore>

    private val repository = Mockito.mock(RagDocumentIndexRepository::class.java)
    private val retrievalService = Mockito.mock(RagRetrievalService::class.java)

    @Test
    fun ownsNewClaimAndWritesFencedVectorMetadata() {
        Mockito.`when`(vectorStoreProvider.ifAvailable).thenReturn(vectorStore)
        Mockito.`when`(repository.insertClaim(any(), any(), eq(NOW))).thenReturn(true)
        Mockito.`when`(repository.markReady(any(), eq(1L), eq(1), eq(NOW))).thenReturn(true)
        val service = service()

        val result = service.ensureIndexed(document())

        assertThat(result).isPresent()
        val documents = listCaptor()
        Mockito.verify(vectorStore).add(documents.capture())
        val stored = documents.value[0]
        assertThat(stored.metadata)
            .containsEntry("indexId", result.get().indexId.toString())
            .containsEntry("claimVersion", 1L)
            .containsEntry("contextId", "resume:experience:0")
        assertThat(stored.id).isNotBlank()
        assertThat(stored.text).isEqualTo("Section: Experience\nBuilt an API")
    }

    @Test
    fun experienceTextIsChunkedLocallyAndClaimedUnderItsOwnSourceKind() {
        Mockito.`when`(vectorStoreProvider.ifAvailable).thenReturn(vectorStore)
        Mockito.`when`(repository.insertClaim(any(), any(), eq(NOW))).thenReturn(true)
        Mockito.`when`(repository.markReady(any(), eq(1L), any(), eq(NOW))).thenReturn(true)

        service().ensureIndexed(ResolvedDocument(DocumentSourceType.EXPERIENCE, UUID.randomUUID(), "experience-hash", "Cluster migration\nDeployed Kubernetes.", null))

        Mockito.verify(repository).insertClaim(any(), eq(identity().copy(sourceType = DocumentSourceType.EXPERIENCE, contentHash = "experience-hash")), eq(NOW))
        val documents = listCaptor()
        Mockito.verify(vectorStore).add(documents.capture())
        assertThat(documents.value).isNotEmpty().allSatisfy {
            assertThat(it.metadata).containsEntry("sourceType", "experience")
            assertThat(it.metadata["contextId"].toString()).startsWith("experience:")
        }
    }

    @Test
    fun staleClaimTakeoverCannotBeMarkedReadyByLostOwner() {
        Mockito.`when`(vectorStoreProvider.ifAvailable).thenReturn(vectorStore)
        val identity = identity()
        val indexId = UUID.randomUUID()
        val stale = RagDocumentIndex(
            indexId,
            identity,
            RagDocumentIndexStatus.INDEXING,
            4L,
            NOW.minusSeconds(11 * 60),
            0,
            NOW.minusSeconds(11 * 60),
            NOW.minusSeconds(11 * 60)
        )
        Mockito.`when`(repository.insertClaim(any(), any(), eq(NOW))).thenReturn(false)
        Mockito.`when`(repository.find(any())).thenReturn(Optional.of(stale))
        Mockito.`when`(repository.takeOver(eq(stale), eq(NOW), any(), any())).thenReturn(true)
        Mockito.`when`(repository.markReady(indexId, 5L, 1, NOW)).thenReturn(false)

        val result = service().ensureIndexed(document())

        assertThat(result).isEmpty()
        Mockito.verify(retrievalService).deleteIndex(indexId)
        Mockito.verify(retrievalService).deleteIndexClaim(indexId, 5L)
    }

    @Test
    fun freshIndexingClaimFallsBackWithoutDuplicateEmbedding() {
        Mockito.`when`(vectorStoreProvider.ifAvailable).thenReturn(vectorStore)
        val indexId = UUID.randomUUID()
        val fresh = RagDocumentIndex(
            indexId,
            identity(),
            RagDocumentIndexStatus.INDEXING,
            2L,
            NOW.minusSeconds(30),
            0,
            NOW.minusSeconds(30),
            NOW.minusSeconds(30)
        )
        Mockito.`when`(repository.insertClaim(any(), any(), eq(NOW))).thenReturn(false)
        Mockito.`when`(repository.find(any())).thenReturn(Optional.of(fresh))

        assertThat(service().ensureIndexed(document())).isEmpty()
        Mockito.verify(vectorStore, Mockito.never()).add(any())
        Mockito.verify(repository, Mockito.never()).takeOver(any(), any(), any(), any())
    }

    @Test
    fun readyDocumentIndexIsReusedWithoutASecondEmbeddingWrite() {
        Mockito.`when`(vectorStoreProvider.ifAvailable).thenReturn(vectorStore)
        Mockito.`when`(repository.insertClaim(any(), any(), eq(NOW))).thenReturn(true, false)
        Mockito.`when`(repository.markReady(any(), eq(1L), eq(1), eq(NOW))).thenReturn(true)
        val service = service()

        val first = service.ensureIndexed(document()).orElseThrow()
        val ready = RagDocumentIndex(
            first.indexId,
            identity(),
            RagDocumentIndexStatus.READY,
            first.claimVersion,
            NOW,
            1,
            NOW,
            NOW
        )
        Mockito.`when`(repository.find(any())).thenReturn(Optional.of(ready))

        assertThat(service.ensureIndexed(document())).contains(first)
        Mockito.verify(vectorStore, Mockito.times(1)).add(any())
        Mockito.verify(repository).touchReady(first.indexId, first.claimVersion, NOW)
    }

    @Test
    fun wrappedIndexingInterruptStopsJobFitRetrievalAfterClaimCleanup() {
        Mockito.`when`(vectorStoreProvider.ifAvailable).thenReturn(vectorStore)
        Mockito.`when`(repository.insertClaim(any(), any(), eq(NOW))).thenReturn(true)
        val failure = IllegalStateException("embedding interrupted", InterruptedException())
        Mockito.doThrow(failure).`when`(vectorStore).add(any())
        val source = JobFitFrozenSource(JobFitSourceType.EXPERIENCE, UUID.randomUUID(), "Cluster", "Deployed Kubernetes.")
        val retriever = JobFitEvidenceRetriever(SectionAwareTextChunker(), service(), retrievalService,
            RagProperties(1_024, "gemini-embedding-001", "section-block-v3"))

        try {
            assertThatThrownBy { retriever.retrieve(JobFitSourceSnapshot("snapshot", NOW, listOf(source)), listOf("Kubernetes")) }
                .isSameAs(failure)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            Mockito.verify(repository).markFailed(any(), eq(1L), eq("VECTOR_INDEX_FAILED"), eq(NOW))
            Mockito.verify(retrievalService).deleteIndexClaim(any(), eq(1L))
            Mockito.verify(retrievalService, Mockito.never()).retrieve(any<String>(), any<List<RagDocumentIndexHandle>>(), any<Int>())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun ordinaryIndexingFailureStillAllowsLocalFallback() {
        Mockito.`when`(vectorStoreProvider.ifAvailable).thenReturn(vectorStore)
        Mockito.`when`(repository.insertClaim(any(), any(), eq(NOW))).thenReturn(true)
        Mockito.doThrow(IllegalStateException("vectors unavailable")).`when`(vectorStore).add(any())

        assertThat(service().ensureIndexed(document())).isEmpty()
        assertThat(Thread.currentThread().isInterrupted).isFalse()
        Mockito.verify(repository).markFailed(any(), eq(1L), eq("VECTOR_INDEX_FAILED"), eq(NOW))
        Mockito.verify(retrievalService).deleteIndexClaim(any(), eq(1L))
    }

    @Test
    fun terminalCleanupDeletesEveryClaimAndUsesAFencedDeleteClaim() {
        val indexId = UUID.randomUUID()
        val ready = RagDocumentIndex(
            indexId,
            identity(),
            RagDocumentIndexStatus.READY,
            4L,
            NOW.minusSeconds(60),
            1,
            NOW.minusSeconds(8 * 24 * 60 * 60),
            NOW.minusSeconds(8 * 24 * 60 * 60)
        )
        Mockito.`when`(repository.cleanupCandidates(any(), any(), eq(10))).thenReturn(listOf(ready))
        Mockito.`when`(repository.claimDeleting(eq(ready), any(), any(), eq(NOW))).thenReturn(OptionalLong.of(5L))

        val deleted = service().cleanupStale(Duration.ofDays(7), 10)

        assertThat(deleted).isEqualTo(1)
        Mockito.verify(retrievalService).deleteIndex(indexId)
        Mockito.verify(repository).deleteClaimed(indexId, 5L)
    }

    private fun service() = RagIndexingService(
        vectorStoreProvider,
        repository,
        retrievalService,
        RagProperties(1_024, "gemini-embedding-001", "section-block-v3"),
        SectionAwareTextChunker(),
        SimpleMeterRegistry(),
        Clock.fixed(NOW, ZoneOffset.UTC)
    )

    private fun document() = ResolvedDocument(
        DocumentSourceType.RESUME,
        UUID.randomUUID(),
        "resume-hash",
        "EXPERIENCE\nBuilt an API",
        listOf(DocumentChunk(0, "Experience", "Built an API", "resume:experience:0"))
    )

    private fun identity() = RagDocumentIndexIdentity(
        DocumentSourceType.RESUME,
        "resume-hash",
        "gemini-embedding-001",
        1_024,
        "section-block-v3"
    )

    @Suppress("UNCHECKED_CAST")
    private fun listCaptor(): ArgumentCaptor<List<Document>> =
        ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<Document>>

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-20T12:00:00Z")
    }
}
