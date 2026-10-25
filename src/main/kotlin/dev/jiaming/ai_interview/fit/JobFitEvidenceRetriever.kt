package dev.jiaming.ai_interview.fit

import dev.jiaming.ai_interview.common.sha256Hex
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.rag.RagContextId
import dev.jiaming.ai_interview.rag.RagContextSnippet
import dev.jiaming.ai_interview.rag.RagDocumentIndexHandle
import dev.jiaming.ai_interview.rag.RagIndexingService
import dev.jiaming.ai_interview.rag.RagProperties
import dev.jiaming.ai_interview.rag.RagRetrievalService
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import java.util.UUID
import java.util.concurrent.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** RAW candidate passage bound to one frozen snapshot source. [id] is `sourceType:resourceId:contextId`, stable across rounds and
 * independent of labels; the retriever supplies its RAG chunk context ID. [contentHash] is the frozen source label/text hash, not the passage hash.
 * [sourceLabel] and [text] are RAW, unescaped source data. Construction validates neither ID format nor snapshot membership. */
data class JobFitEvidencePassage(
    val id: String,
    val snapshotId: String,
    val sourceType: JobFitSourceType,
    val resourceId: UUID,
    val contentHash: String,
    val sourceLabel: String,
    val text: String,
)

/** RAW passages keyed by the original query, in first-occurrence request order; duplicate keys collapse and blank queries map to [].
 * [degradedSources] aggregates kinds with any local fallback across this call; a kind can also have vector results. */
data class JobFitRetrieval(
    val passagesByQuery: Map<String, List<JobFitEvidencePassage>>,
    val degradedSources: Set<JobFitSourceType>,
)

/** Searches a caller-authorized frozen snapshot's resume and experiences, excluding target-job candidate evidence.
 * Trusts snapshot ownership established by JobFitSourceSnapshotService; performs no owner lookup. */
@Service
class JobFitEvidenceRetriever(
    private val chunker: SectionAwareTextChunker,
    private val indexingService: RagIndexingService,
    private val retrievalService: RagRetrievalService,
    private val properties: RagProperties,
) {
    /** Synchronously blocks on database indexing, embedding and vector search, including for empty/all-blank query lists; may write shared indexes.
     * Accepts RAW queries; trims them for search but preserves original map keys. Duplicate keys repeat work and retain the last result.
     * Returns at most [RagProperties.assessmentMaxSnippets] RAW passages per query, fusing source-separated ranks with RRF and ID tie breaks.
     * Limits each source/section to [RagProperties.maxSnippetsPerSection]; drops unbound or non-verbatim vector hits.
     * Index/search runtime failures use local term-overlap chunks and mark degradation; local fallback failures propagate.
     * Runtime exceptions reaching this boundary with interruption causes/flags propagate; an indexing return with its flag set throws CancellationException.
     */
    fun retrieve(snapshot: JobFitSourceSnapshot, queries: List<String>): JobFitRetrieval {
        val degraded = linkedSetOf<JobFitSourceType>()
        val groups = CANDIDATE_TYPES.map { type -> indexGroup(type, snapshot.sources.filter { it.sourceType == type }, degraded) }
        val localChunks = mutableMapOf<UUID, List<LocalChunk>>()
        val passagesByQuery = queries.associateWith { query ->
            if (query.isBlank()) emptyList() else select(groups.flatMap { search(snapshot, it, query.trim(), degraded, localChunks) })
        }
        return JobFitRetrieval(passagesByQuery, degraded)
    }

    private fun indexGroup(sourceType: JobFitSourceType, sources: List<JobFitFrozenSource>, degraded: MutableSet<JobFitSourceType>): IndexGroup {
        val indexed = linkedMapOf<RagDocumentIndexHandle, MutableList<JobFitFrozenSource>>()
        val local = mutableListOf<JobFitFrozenSource>()
        for (source in sources) {
            val handle = try {
                indexingService.ensureIndexed(document(source)).orElse(null)
            } catch (exception: RuntimeException) {
                rethrowIfInterrupted(exception)
                log.warn("job_fit_index_fallback sourceType={}", source.sourceType)
                null
            }
            if (Thread.currentThread().isInterrupted) throw CancellationException("Job Fit retrieval interrupted")
            if (handle == null) local += source else indexed.getOrPut(handle) { mutableListOf() } += source
        }
        if (local.isNotEmpty()) degraded += sourceType
        return IndexGroup(sourceType, indexed, local)
    }

    private fun search(
        snapshot: JobFitSourceSnapshot, group: IndexGroup, query: String, degraded: MutableSet<JobFitSourceType>,
        localChunks: MutableMap<UUID, List<LocalChunk>>,
    ): List<RankedList> {
        val lists = mutableListOf<RankedList>()
        if (group.indexed.isNotEmpty()) try {
            val hits = retrievalService.retrieve(query, group.indexed.keys.toList(), properties.resumeCandidateTopK())
            lists += RankedList(hits.flatMap { bind(snapshot, group, it) })
        } catch (exception: RuntimeException) {
            rethrowIfInterrupted(exception)
            log.warn("job_fit_retrieval_fallback sourceType={}", group.sourceType)
            degraded += group.sourceType
            lists += localSearch(snapshot, group.indexed.values.flatten(), query, localChunks)
        }
        if (group.local.isNotEmpty()) lists += localSearch(snapshot, group.local, query, localChunks)
        return lists
    }

    // Vector indexes are content-addressed and may be shared across owners, so identity always comes from the snapshot source, never metadata.
    private fun bind(snapshot: JobFitSourceSnapshot, group: IndexGroup, hit: RagContextSnippet): List<Candidate> {
        val metadata = hit.metadata.orEmpty()
        val handle = group.indexed.keys.firstOrNull { it.indexId.toString() == metadata["indexId"]?.toString() } ?: return emptyList()
        val text = hit.content.orEmpty().removePrefix("Section: ${metadata["section"]}\n").trim()
        if (text.isEmpty()) return emptyList()
        return group.indexed.getValue(handle).filter { text in it.text }.map { source ->
            Candidate(passage(snapshot, source, hit.sourceContextId(), text), metadata["section"]?.toString().orEmpty())
        }
    }

    // shortcut: term-overlap ranking over frozen chunks, upgrade to BM25 if degraded retrieval quality matters.
    private fun localSearch(
        snapshot: JobFitSourceSnapshot, sources: List<JobFitFrozenSource>, query: String, localChunks: MutableMap<UUID, List<LocalChunk>>,
    ): RankedList {
        val terms = terms(query)
        val scored = sources.flatMap { source ->
            localChunks.getOrPut(source.resourceId) {
                chunker.chunk(source.text).map { chunk ->
                    val contextId = RagContextId.forChunk(documentType(source.sourceType).metadataValue(), chunk)
                    LocalChunk(Candidate(passage(snapshot, source, contextId, chunk.content.trim()), chunk.section), terms(chunk.content))
                }
            }.map { it.candidate to it.terms.count(terms::contains) }
        }
        val ranked = scored.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Candidate, Int>> { it.second }.thenBy { it.first.passage.id })
        return RankedList(ranked.take(properties.resumeCandidateTopK()).map { it.first })
    }

    private fun terms(text: String) = text.lowercase().split(TERM_SEPARATOR).filter { it.length > 1 }.toSet()

    private fun select(lists: List<RankedList>): List<JobFitEvidencePassage> {
        val scores = linkedMapOf<String, Pair<Candidate, Double>>()
        lists.forEach { list ->
            list.candidates.forEachIndexed { rank, candidate ->
                val previous = scores[candidate.passage.id]?.second ?: 0.0
                scores[candidate.passage.id] = candidate to previous + 1.0 / (properties.rrfK() + rank + 1)
            }
        }
        val selected = mutableListOf<Candidate>()
        for ((candidate, _) in scores.values.sortedWith(compareByDescending<Pair<Candidate, Double>> { it.second }.thenBy { it.first.passage.id })) {
            if (selected.size >= properties.assessmentMaxSnippets()) break
            val sameSection = selected.count { it.passage.resourceId == candidate.passage.resourceId && it.section == candidate.section }
            if (sameSection < properties.maxSnippetsPerSection()) selected += candidate
        }
        return selected.map(Candidate::passage)
    }

    private fun passage(snapshot: JobFitSourceSnapshot, source: JobFitFrozenSource, contextId: String, text: String) = JobFitEvidencePassage(
        "${documentType(source.sourceType).metadataValue()}:${source.resourceId}:$contextId", snapshot.snapshotId, source.sourceType,
        source.resourceId, source.contentHash, source.sourceLabel, text,
    )

    private fun document(source: JobFitFrozenSource) = ResolvedDocument(documentType(source.sourceType), source.resourceId, sha256Hex(source.text), source.text, null)

    private fun documentType(type: JobFitSourceType) = when (type) {
        JobFitSourceType.RESUME -> DocumentSourceType.RESUME
        JobFitSourceType.EXPERIENCE -> DocumentSourceType.EXPERIENCE
        JobFitSourceType.TARGET_JOB -> error("Target-job text is never candidate evidence")
    }

    private fun rethrowIfInterrupted(exception: RuntimeException) {
        if (Thread.currentThread().isInterrupted || generateSequence<Throwable>(exception) { it.cause }.any { it is InterruptedException }) throw exception
    }

    private class IndexGroup(val sourceType: JobFitSourceType, val indexed: Map<RagDocumentIndexHandle, List<JobFitFrozenSource>>, val local: List<JobFitFrozenSource>)
    private class RankedList(val candidates: List<Candidate>)
    private class Candidate(val passage: JobFitEvidencePassage, val section: String)
    private class LocalChunk(val candidate: Candidate, val terms: Set<String>)

    companion object {
        private val log = LoggerFactory.getLogger(JobFitEvidenceRetriever::class.java)
        private val CANDIDATE_TYPES = listOf(JobFitSourceType.RESUME, JobFitSourceType.EXPERIENCE)
        private val TERM_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
    }
}
