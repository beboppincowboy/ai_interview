package dev.jiaming.ai_interview.fit

import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.rag.RagContextSnippet
import dev.jiaming.ai_interview.rag.RagDocumentIndexHandle
import dev.jiaming.ai_interview.rag.RagIndexingService
import dev.jiaming.ai_interview.rag.RagProperties
import dev.jiaming.ai_interview.rag.RagRetrievalService
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CancellationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq

class JobFitEvidenceRetrieverTests {
    private val indexing = Mockito.mock(RagIndexingService::class.java)
    private val retrieval = Mockito.mock(RagRetrievalService::class.java)
    private val retriever = JobFitEvidenceRetriever(SectionAwareTextChunker(), indexing, retrieval, RagProperties(1024, "gemini-embedding-001", "section-block-v3"))

    private val resume = JobFitFrozenSource(JobFitSourceType.RESUME, UUID.randomUUID(), "Backend", "Built Kotlin services.")
    private val targetJob = JobFitFrozenSource(JobFitSourceType.TARGET_JOB, UUID.randomUUID(), "Platform", "Requires Kubernetes.")
    private val experience = JobFitFrozenSource(JobFitSourceType.EXPERIENCE, UUID.randomUUID(), "Cluster migration", "Cluster migration\nDeployed Kubernetes on EKS.")
    private val snapshot = JobFitSourceSnapshot("snapshot-1", Instant.parse("2026-10-25T00:00:00Z"), listOf(resume, targetJob, experience))

    @Test
    fun aSavedExperienceSuppliesEvidenceMissingFromTheResume() {
        val resumeIndex = index(resume)
        val experienceIndex = index(experience)
        hits(resumeIndex)
        hits(experienceIndex, hit(experienceIndex, "experience:summary:0", "Summary", "Deployed Kubernetes on EKS."))

        val result = retriever.retrieve(snapshot, listOf("Kubernetes"))

        val passage = result.passagesByQuery.getValue("Kubernetes").single()
        assertThat(passage.sourceType).isEqualTo(JobFitSourceType.EXPERIENCE)
        assertThat(passage.resourceId).isEqualTo(experience.resourceId)
        assertThat(passage.sourceLabel).isEqualTo("Cluster migration")
        assertThat(passage.contentHash).isEqualTo(experience.contentHash)
        assertThat(passage.snapshotId).isEqualTo("snapshot-1")
        assertThat(passage.text).isEqualTo("Deployed Kubernetes on EKS.")
        assertThat(result.degradedSources).isEmpty()
        Mockito.verify(indexing, Mockito.never()).ensureIndexed(argThat { sourceType() == DocumentSourceType.JOB_DESCRIPTION })
    }

    @Test
    fun aSharedIndexForIdenticalTextBindsHitsOnlyToTheCallersOwnSources() {
        val shared = RagDocumentIndexHandle(UUID.randomUUID(), 1L)
        val otherOwner = experience.copy(resourceId = UUID.randomUUID(), sourceLabel = "Someone else's project")
        val otherSnapshot = JobFitSourceSnapshot("snapshot-2", snapshot.capturedAt, listOf(resume.copy(resourceId = UUID.randomUUID()), targetJob, otherOwner))
        Mockito.`when`(indexing.ensureIndexed(argThat<ResolvedDocument> { sourceType() == DocumentSourceType.EXPERIENCE })).thenReturn(Optional.of(shared))
        Mockito.`when`(indexing.ensureIndexed(argThat<ResolvedDocument> { sourceType() == DocumentSourceType.RESUME })).thenReturn(Optional.empty())
        hits(shared, hit(shared, "experience:summary:0", "Summary", "Deployed Kubernetes on EKS."))

        val mine = retriever.retrieve(snapshot, listOf("Kubernetes")).passagesByQuery.getValue("Kubernetes")
        val theirs = retriever.retrieve(otherSnapshot, listOf("Kubernetes")).passagesByQuery.getValue("Kubernetes")

        assertThat(mine.filter { it.sourceType == JobFitSourceType.EXPERIENCE }.map { it.resourceId to it.sourceLabel })
            .containsExactly(experience.resourceId to "Cluster migration")
        assertThat(theirs.filter { it.sourceType == JobFitSourceType.EXPERIENCE }.map { it.resourceId to it.sourceLabel })
            .containsExactly(otherOwner.resourceId to "Someone else's project")
    }

    @Test
    fun aVectorHitThatIsNotVerbatimInTheSnapshotIsDropped() {
        val experienceIndex = index(experience)
        index(resume).also(::hits)
        hits(experienceIndex, hit(experienceIndex, "experience:summary:0", "Summary", "Ran Kubernetes for a bank."))

        val passages = retriever.retrieve(snapshot, listOf("Kubernetes")).passagesByQuery.getValue("Kubernetes")

        assertThat(passages).noneMatch { it.text.contains("bank") }
    }

    @Test
    fun withoutVectorsRetrievalSearchesFrozenLocalChunksAndRecordsDegradation() {
        Mockito.`when`(indexing.ensureIndexed(any())).thenReturn(Optional.empty())

        val result = retriever.retrieve(snapshot, listOf("Kubernetes"))

        assertThat(result.passagesByQuery.getValue("Kubernetes")).singleElement().satisfies({
            assertThat(it.resourceId).isEqualTo(experience.resourceId)
            assertThat(experience.text).contains(it.text)
        })
        assertThat(result.degradedSources).containsExactlyInAnyOrder(JobFitSourceType.RESUME, JobFitSourceType.EXPERIENCE)
        Mockito.verifyNoInteractions(retrieval)
    }

    @Test
    fun aFailedVectorSearchFallsBackToLocalChunksForThatSourceKind() {
        index(resume).also(::hits)
        val experienceIndex = index(experience)
        Mockito.`when`(retrieval.retrieve(anyOrNull<String>(), eq(listOf(experienceIndex)), any<Int>())).thenThrow(IllegalStateException("pgvector down"))

        val result = retriever.retrieve(snapshot, listOf("Kubernetes"))

        assertThat(result.passagesByQuery.getValue("Kubernetes").map { it.resourceId }).containsExactly(experience.resourceId)
        assertThat(result.degradedSources).containsExactly(JobFitSourceType.EXPERIENCE)
    }

    @Test
    fun anInterruptedSearchPropagatesInsteadOfFallingBack() {
        index(resume).also(::hits)
        val experienceIndex = index(experience)
        Mockito.`when`(retrieval.retrieve(anyOrNull<String>(), eq(listOf(experienceIndex)), any<Int>()))
            .thenThrow(IllegalStateException("cancelled", InterruptedException()))

        assertThatThrownBy { retriever.retrieve(snapshot, listOf("Kubernetes")) }.hasRootCauseInstanceOf(InterruptedException::class.java)
    }

    @Test
    fun anIndexingFailureFallsBackToLocalChunksAndRecordsDegradation() {
        Mockito.`when`(indexing.ensureIndexed(any())).thenThrow(IllegalStateException("claim table locked"))

        val result = retriever.retrieve(snapshot, listOf("Kubernetes"))

        assertThat(result.passagesByQuery.getValue("Kubernetes").map { it.resourceId }).containsExactly(experience.resourceId)
        assertThat(result.degradedSources).containsExactlyInAnyOrder(JobFitSourceType.RESUME, JobFitSourceType.EXPERIENCE)
    }

    @Test
    fun anInterruptSwallowedByIndexingStopsRetrievalInsteadOfFallingBack() {
        // RagIndexingService catches the wrapped interrupt and returns empty; only the restored interrupt flag survives.
        Mockito.`when`(indexing.ensureIndexed(any())).thenAnswer {
            Thread.currentThread().interrupt()
            Optional.empty<RagDocumentIndexHandle>()
        }

        try {
            assertThatThrownBy { retriever.retrieve(snapshot, listOf("Kubernetes")) }.isInstanceOf(CancellationException::class.java)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun duplicatePassagesCollapseAndRepeatingARoundAddsNothingNew() {
        val second = JobFitFrozenSource(JobFitSourceType.EXPERIENCE, UUID.randomUUID(), "Talk", "Spoke about Kubernetes at KubeCon.")
        val wider = snapshot.copy(sources = snapshot.sources + second)
        Mockito.`when`(indexing.ensureIndexed(any())).thenReturn(Optional.empty())

        val first = retriever.retrieve(wider, listOf("Kubernetes", "KubeCon Kubernetes"))
        val again = retriever.retrieve(wider, listOf("KubeCon Kubernetes", "Kubernetes"))

        val kubernetes = first.passagesByQuery.getValue("Kubernetes")
        assertThat(kubernetes.map { it.id }).doesNotHaveDuplicates().hasSize(2)
        assertThat(again.passagesByQuery).isEqualTo(first.passagesByQuery)
        val seen = first.passagesByQuery.values.flatten().map { it.id }.toSet()
        assertThat(again.passagesByQuery.values.flatten().map { it.id }.toSet() - seen).isEmpty()
    }

    private fun index(source: JobFitFrozenSource): RagDocumentIndexHandle {
        val handle = RagDocumentIndexHandle(UUID.randomUUID(), 1L)
        Mockito.`when`(indexing.ensureIndexed(argThat<ResolvedDocument> { resourceId() == source.resourceId })).thenReturn(Optional.of(handle))
        return handle
    }

    private fun hits(index: RagDocumentIndexHandle, vararg snippets: RagContextSnippet) {
        Mockito.`when`(retrieval.retrieve(anyOrNull<String>(), eq(listOf(index)), any<Int>())).thenReturn(snippets.toList())
    }

    private fun hit(index: RagDocumentIndexHandle, contextId: String, section: String, content: String) = RagContextSnippet(
        UUID.randomUUID().toString(), "Section: $section\n$content",
        mapOf("indexId" to index.indexId.toString(), "claimVersion" to index.claimVersion, "contextId" to contextId, "section" to section, "chunkIndex" to 0),
        0.9,
    )
}
