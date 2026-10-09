package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.document.DocumentChunk
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.rag.*
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.Comparator
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Optional

@Service
class CoachRagContextService @Autowired constructor(
    private val chunker: SectionAwareTextChunker,
    private val indexingService: RagIndexingService,
    private val retrievalService: RagRetrievalService,
    private val meterRegistry: MeterRegistry,
    private val properties: RagProperties
) {
    internal constructor(chunker: SectionAwareTextChunker, indexingService: RagIndexingService,
        retrievalService: RagRetrievalService, meterRegistry: MeterRegistry) :
        this(chunker, indexingService, retrievalService, meterRegistry, RagProperties(1024, 8, "gemini-embedding-001", "section-block-v3"))

    fun jobFitContext(input: CoachAnalysisInput) = ragContext(input.resume(), input.targetJob(), jobFitQueries(input),
        SelectionProfile("job-fit", properties.assessmentContextBudget(), properties.assessmentJobDescriptionMinimum()))
    fun practiceQuestionContext(input: CoachAnalysisInput) = ragContext(input.resume(), input.targetJob(), questionQueries(input),
        SelectionProfile("practice-questions", properties.questionContextBudget(), properties.questionJobDescriptionMinimum()))
    fun feedbackContext(input: CoachFeedbackInput) = ragContext(input.resume(), input.targetJob(), feedbackQueries(input),
        SelectionProfile("feedback", properties.feedbackContextBudget(), properties.feedbackJobDescriptionMinimum()))

    // Suggestions send a resume whole within the direct-context budget; a longer one is narrowed to what retrieval finds for the job.
    fun suggestionSourceText(resume: ResolvedDocument, targetJob: ResolvedDocument): String =
        if (safe(resume.normalizedText()).length <= DIRECT_CONTEXT_LIMIT) resume.normalizedText()
        else ragContext(resume, Optional.empty(), suggestionQueries(targetJob),
            SelectionProfile("suggestions", properties.assessmentContextBudget(), 0)).context

    private fun ragContext(resume: ResolvedDocument, targetJob: Optional<ResolvedDocument>, queries: List<String>, profile: SelectionProfile): CoachRagContext {
        val documents = mutableListOf(resume)
        targetJob.ifPresent(documents::add)
        if (documents.sumOf { safe(it.normalizedText()).length } <= DIRECT_CONTEXT_LIMIT) {
            val snippets = documents.flatMap(::localSnippets)
            meterRegistry.counter("ai.rag.context", "mode", "direct", "workflow", profile.name).increment()
            return context(snippets, Int.MAX_VALUE, false, false)
        }
        val indexed = LinkedHashMap<DocumentSourceType, IndexedDocument>()
        val originalContent = originalContent(documents)
        for (document in documents) try {
            indexed[document.sourceType()] = IndexedDocument(document, indexingService.ensureIndexed(document))
        } catch (exception: RuntimeException) {
            indexed[document.sourceType()] = IndexedDocument(document, Optional.empty())
            log.warn("rag_index_fallback sourceType={}", document.sourceType())
        }
        val candidates = LinkedHashMap<String, Candidate>()
        val fallbackSources = LinkedHashSet<DocumentSourceType>()
        for (query in queries) {
            val retrievalQuery = safe(query).trim()
            if (retrievalQuery.isEmpty()) continue
            for (source in indexed.values) {
                if (source.index.isEmpty()) { fallbackSources += source.document.sourceType(); continue }
                try {
                    val retrieved = retrievalService.retrieve(retrievalQuery, source.index.orElseThrow(), candidateTopK(source.document.sourceType()))
                    if (retrieved.isEmpty()) fallbackSources += source.document.sourceType()
                    retrieved.forEachIndexed { rank, snippet -> addVectorCandidate(candidates, restoreOriginalContent(snippet, originalContent), source.document.sourceType(), rank + 1) }
                } catch (exception: RuntimeException) {
                    fallbackSources += source.document.sourceType()
                    log.warn("rag_retrieval_fallback sourceType={}", source.document.sourceType())
                }
            }
        }
        for (sourceType in fallbackSources) indexed[sourceType]?.let { addLocalCandidates(candidates, it.document) }
        if (candidates.isEmpty()) documents.forEach { addLocalCandidates(candidates, it) }
        val snippets = select(candidates.values, profile)
        val vectorBacked = snippets.any { candidates[it.sourceContextId()]!!.vectorBacked }
        meterRegistry.counter("ai.rag.context", "mode", if (vectorBacked) "retrieval" else "local", "workflow", profile.name).increment()
        log.info("rag_context_ready mode={} workflow={} candidates={} snippets={}", if (vectorBacked) "retrieval" else "local", profile.name, candidates.size, snippets.size)
        return context(snippets, profile.budget, vectorBacked, true)
    }

    private fun select(candidates: Iterable<Candidate>, profile: SelectionProfile): List<RagContextSnippet> {
        val ranked = candidates.sortedWith(candidateOrder())
        val selected = mutableListOf<Candidate>()
        selectFrom(ranked, selected, profile.jobDescriptionMinimum, DocumentSourceType.JOB_DESCRIPTION, true, profile)
        selectFrom(ranked, selected, profile.budget, null, true, profile)
        selectFrom(ranked, selected, profile.budget, null, false, profile)
        selected.sortWith(candidateOrder())
        selected.forEach { meterRegistry.counter("ai.rag.context.selection", "workflow", profile.name,
            "source", it.sourceType.metadataValue(), "mode", if (it.vectorBacked) "retrieval" else "local").increment() }
        return selected.map { it.snippet }
    }

    private fun selectFrom(ranked: List<Candidate>, selected: MutableList<Candidate>, limit: Int,
        requiredSource: DocumentSourceType?, enforceSectionLimit: Boolean, profile: SelectionProfile) {
        var selectedForSource = 0
        for (candidate in ranked) {
            if (selected.size >= profile.budget) return
            if (requiredSource != null && candidate.sourceType != requiredSource) continue
            if (requiredSource != null && selectedForSource >= limit) return
            if (candidate in selected || (enforceSectionLimit && sectionLimitReached(selected, candidate)) || nearDuplicate(selected, candidate)) continue
            selected += candidate
            if (requiredSource != null) selectedForSource++
        }
    }

    private fun sectionLimitReached(selected: List<Candidate>, candidate: Candidate) =
        selected.count { it.sourceType == candidate.sourceType && it.section == candidate.section } >= properties.sectionMaximum()
    private fun nearDuplicate(selected: List<Candidate>, candidate: Candidate) = selected.any {
        it.sourceType == candidate.sourceType && it.section == candidate.section && kotlin.math.abs(it.chunkIndex - candidate.chunkIndex) <= 1 &&
            overlappingBoundary(safe(it.snippet.content), safe(candidate.snippet.content))
    }
    private fun overlappingBoundary(first: String, second: String): Boolean {
        val left = normalizedBoundary(first, false); val right = normalizedBoundary(second, true)
        return left.length >= 80 && right.length >= 80 && (left.endsWith(right) || right.startsWith(left))
    }
    private fun normalizedBoundary(value: String, prefix: Boolean): String {
        val normalized = safe(value).replace(Regex("\\s+"), " ").trim()
        val length = minOf(180, normalized.length)
        return if (prefix) normalized.substring(0, length) else normalized.substring(normalized.length - length)
    }
    private fun candidateOrder(): Comparator<Candidate> = compareByDescending<Candidate> { it.rrfScore }.thenBy { it.bestRank }.thenBy { it.snippet.sourceContextId() }
    private fun addVectorCandidate(candidates: MutableMap<String, Candidate>, snippet: RagContextSnippet, sourceType: DocumentSourceType, rank: Int) {
        val candidate = candidates.getOrPut(snippet.sourceContextId()) { Candidate(snippet, sourceType, true) }
        candidate.addRank(rank, properties.rrfK())
    }
    private fun addLocalCandidates(candidates: MutableMap<String, Candidate>, document: ResolvedDocument) =
        localSnippets(document).forEach { candidates.putIfAbsent(it.sourceContextId(), Candidate(it, document.sourceType(), false)) }
    private fun candidateTopK(sourceType: DocumentSourceType) = if (sourceType == DocumentSourceType.JOB_DESCRIPTION) properties.jobDescriptionCandidateTopK() else properties.resumeCandidateTopK()
    private fun originalContent(documents: List<ResolvedDocument>): Map<String, String> = HashMap<String, String>().apply {
        documents.forEach { document -> localSnippets(document).forEach { put(it.sourceContextId(), it.content ?: "") } }
    }
    private fun restoreOriginalContent(snippet: RagContextSnippet, original: Map<String, String>) =
        RagContextSnippet(snippet.id, original[snippet.sourceContextId()] ?: snippet.content, snippet.metadata, snippet.score)
    private fun context(snippets: List<RagContextSnippet>, maxSnippets: Int, vectorBacked: Boolean, truncateContent: Boolean) =
        CoachRagContext(formatSnippets(snippets, maxSnippets, truncateContent), vectorBacked)

    private fun jobFitQueries(input: CoachAnalysisInput): List<String> {
        val jd = jobDescriptionQueryExcerpt(input.targetJob())
        return listOf(
            "required qualifications must-have skills experience and responsibilities target job $jd",
            "resume evidence accomplishments projects skills tools and measurable impact target job",
            "job description requirements missing candidate evidence and role alignment $jd",
        )
    }
    private fun suggestionQueries(targetJob: ResolvedDocument): List<String> {
        val jd = jobDescriptionQueryExcerpt(Optional.of(targetJob))
        return listOf(
            "required qualifications skills and responsibilities $jd",
            "accomplishments projects tools and measurable impact relevant to $jd",
        )
    }
    private fun questionQueries(input: CoachAnalysisInput): List<String> {
        val jd = jobDescriptionQueryExcerpt(input.targetJob())
        return listOf("strongest projects ownership technical complexity Software Engineer", "weakest resume areas missing detail interview probe Software Engineer",
            "system design architecture scaling data flow production tradeoffs", "debugging incident response observability database cache production",
            "collaboration leadership stakeholder tradeoff communication", "job description requirements role specific tooling $jd")
    }
    private fun feedbackQueries(input: CoachFeedbackInput) = listOf(fallback(input.questionText(), ""), input.expectedSignals().joinToString(" "),
        fallback(input.category(), ""), "source experience and project context expected evidence answer evaluation")

    private fun localSnippets(document: ResolvedDocument): List<RagContextSnippet> {
        val chunks = if (document.persistedChunks().isEmpty()) chunker.chunk(document.normalizedText()).map {
            DocumentChunk(it.index, it.section, it.content, RagContextId.forChunk(document.sourceType().metadataValue(), it))
        } else document.persistedChunks()
        return chunks.map { chunk ->
            val contextId = if (chunk.contextId.isNullOrBlank()) RagContextId.forChunk(document.sourceType().metadataValue(), chunk.section, chunk.index) else chunk.contextId
            RagContextSnippet("local-${document.sourceType().metadataValue()}-${chunk.index}", chunk.content, mapOf(
                "contextId" to contextId, "sourceType" to document.sourceType().metadataValue(), "section" to fallback(chunk.section, "section"), "chunkIndex" to chunk.index), null)
        }
    }
    private fun jobDescriptionQueryExcerpt(value: Optional<ResolvedDocument>) = value.map { it.normalizedText() }.map { truncate(it, 1000).replace('\n', ' ') }.orElse("")
    private fun formatSnippets(snippets: List<RagContextSnippet>, max: Int, truncate: Boolean): String =
        if (snippets.isEmpty()) "No retrieved context was available." else snippets.take(max).joinToString("\n\n") { formatSnippet(it, truncate) }
    private fun formatSnippet(snippet: RagContextSnippet, truncate: Boolean): String {
        val metadata = snippet.metadata ?: emptyMap()
        return """[contextId=${snippet.sourceContextId()} source=${metadataValue(metadata, "sourceType")} section=${metadataValue(metadata, "section")} score=${snippet.score?.let { "%.4f".format(it) } ?: "n/a"}]
${if (truncate) truncate(snippet.content, 1200) else safe(snippet.content)}"""
    }
    private fun metadataValue(metadata: Map<String, Any>, key: String) = metadata[key]?.toString() ?: "unknown"
    private fun truncate(value: String?, limit: Int): String { val safe = safe(value); return if (safe.length <= limit) safe else safe.substring(0, limit) + "\n[truncated]" }
    private fun fallback(value: String?, default: String) = if (blank(value)) default else value!!.trim()
    private fun safe(value: String?) = value.orEmpty()
    private fun blank(value: String?) = value.isNullOrBlank()

    private data class IndexedDocument(val document: ResolvedDocument, val index: Optional<RagDocumentIndexHandle>)
    private data class SelectionProfile(val name: String, val budget: Int, val jobDescriptionMinimum: Int)
    private class Candidate(val snippet: RagContextSnippet, val sourceType: DocumentSourceType, val vectorBacked: Boolean) {
        var rrfScore = 0.0
            private set
        var bestRank = Int.MAX_VALUE
            private set
        val section get() = snippet.metadata?.getOrDefault("section", "section").toString()
        val chunkIndex get() = (snippet.metadata?.get("chunkIndex") as? Number)?.toInt() ?: Int.MAX_VALUE
        fun addRank(rank: Int, rrfK: Int) { rrfScore += 1.0 / (rrfK + rank); bestRank = minOf(bestRank, rank) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CoachRagContextService::class.java)
        private const val DIRECT_CONTEXT_LIMIT = 6_000
    }
}
