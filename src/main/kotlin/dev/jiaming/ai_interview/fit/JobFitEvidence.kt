package dev.jiaming.ai_interview.fit

import java.util.UUID

/** Evidence strength for one requirement; this is independent of who judged it and with what confidence. */
enum class JobFitEvidenceStatus {
    SUPPORTED,
    PARTIALLY_SUPPORTED,
    NOT_ESTABLISHED,
}

/** Source kinds included in a frozen Job Fit run. Target-job passages define requirements, not candidate ability. */
enum class JobFitSourceType {
    RESUME,
    TARGET_JOB,
    EXPERIENCE,
}

/** CURRENT matches current sources; OUTDATED has changed sources; UNKNOWN has no freshness guarantee, independently of job progress. */
enum class JobFitFreshness {
    CURRENT,
    OUTDATED,
    UNKNOWN,
}

/** JUDGED has a status and provenance; UNCHECKED has neither and can only accompany NOT_ESTABLISHED. */
enum class JobFitJudgmentState {
    JUDGED,
    UNCHECKED,
}

enum class JobFitTraceKind {
    RETRIEVAL,
    JUDGMENT,
    FALLBACK,
    VERIFICATION,
}

/** Frozen source identity and non-blank label/hash; hash encoding is unspecified. Invalid inputs throw [IllegalArgumentException]. */
data class JobFitSourceManifestEntry(
    val sourceType: JobFitSourceType,
    val resourceId: UUID,
    val sourceLabel: String,
    val contentHash: String,
) {
    init {
        require(sourceLabel.isNotBlank()) { "A source manifest entry must have an immutable label" }
        require(contentHash.isNotBlank()) { "A source manifest entry must have a content hash" }
    }
}

/** RAW source text: quote is a non-blank exact substring of passage, without semantic verification or HTML escaping.
 * IDs, hash, label, and passage must be non-blank; invalid inputs throw [IllegalArgumentException].
 */
data class JobFitEvidenceItem(
    val id: String,
    val snapshotId: String,
    val sourceType: JobFitSourceType,
    val resourceId: UUID,
    val contentHash: String,
    val sourceLabel: String,
    val passage: String,
    val quote: String,
) {
    init {
        require(id.isNotBlank()) { "Evidence ID must not be blank" }
        require(snapshotId.isNotBlank()) { "Evidence snapshot ID must not be blank" }
        require(contentHash.isNotBlank()) { "Evidence content hash must not be blank" }
        require(sourceLabel.isNotBlank()) { "Evidence source label must not be blank" }
        require(passage.isNotBlank()) { "Evidence passage must not be blank" }
        require(quote.isNotBlank() && passage.contains(quote)) {
            "Evidence quote must be an exact substring of its frozen passage"
        }
    }
}

/** Canonical requirement assessment; targetJobEvidenceIds define the requirement, evidenceIds cite candidate records.
 * IDs are unique within each list; positive statuses need evidence, and non-SUPPORTED statuses need a gap description.
 * SUPPORTED forbids conflicts and non-blank gaps; invalid inputs throw [IllegalArgumentException].
 */
data class JobFitRequirementAssessment(
    val requirementId: String,
    val requirement: String,
    val status: JobFitEvidenceStatus,
    val targetJobEvidenceIds: List<String>,
    val evidenceIds: List<String>,
    val conflictIds: List<String> = emptyList(),
    val missingEvidenceDescription: String? = null,
) {
    init {
        require(requirementId.isNotBlank()) { "Requirement ID must not be blank" }
        require(requirement.isNotBlank()) { "Requirement text must not be blank" }
        require(targetJobEvidenceIds.isNotEmpty() && targetJobEvidenceIds.all(String::isNotBlank)) {
            "Each assessment must reference target-job evidence for the requirement"
        }
        require(targetJobEvidenceIds.distinct().size == targetJobEvidenceIds.size) { "Target-job evidence IDs must be unique" }
        require(evidenceIds.all(String::isNotBlank) && evidenceIds.distinct().size == evidenceIds.size) {
            "Candidate evidence IDs must be non-blank and unique"
        }
        require(conflictIds.all(String::isNotBlank) && conflictIds.distinct().size == conflictIds.size) {
            "Conflict IDs must be non-blank and unique"
        }
        when (status) {
            JobFitEvidenceStatus.SUPPORTED -> {
                require(evidenceIds.isNotEmpty()) { "Supported assessments must cite candidate evidence" }
                require(conflictIds.isEmpty()) { "A disputed assessment cannot be fully supported" }
                require(missingEvidenceDescription.isNullOrBlank()) { "Supported assessments cannot describe missing evidence" }
            }
            JobFitEvidenceStatus.PARTIALLY_SUPPORTED -> {
                require(evidenceIds.isNotEmpty()) { "Partially supported assessments must cite candidate evidence" }
                require(!missingEvidenceDescription.isNullOrBlank()) { "Partially supported assessments must describe the evidence gap" }
            }
            JobFitEvidenceStatus.NOT_ESTABLISHED -> require(!missingEvidenceDescription.isNullOrBlank()) {
                "Not established assessments must describe what the records do not establish"
            }
        }
    }
}

/** A contradiction with non-blank ID/description and at least two distinct evidence IDs; invalid inputs throw [IllegalArgumentException].
 * [JobFitEvidenceMetadata] additionally requires those IDs to cite at least two candidate source records.
 */
data class JobFitEvidenceConflict(
    val id: String,
    val description: String,
    val evidenceIds: List<String>,
) {
    init {
        require(id.isNotBlank()) { "Conflict ID must not be blank" }
        require(description.isNotBlank()) { "Conflict description must not be blank" }
        require(evidenceIds.size >= 2 && evidenceIds.all(String::isNotBlank) && evidenceIds.distinct().size == evidenceIds.size) {
            "A conflict must reference at least two distinct evidence items"
        }
    }
}

/** Non-blank provider/model/rubric identity; invalid inputs throw [IllegalArgumentException]. Probabilities belong to [JobFitJudgment]. */
data class JobFitJudgmentProvenance(
    val provider: String,
    val model: String,
    val rubricVersion: String,
) {
    init {
        require(provider.isNotBlank()) { "Judgment provider must not be blank" }
        require(model.isNotBlank()) { "Judgment model identity must not be blank" }
        require(rubricVersion.isNotBlank()) { "Judgment rubric version must not be blank" }
    }
}

/** JUDGED requires status/provenance; positive statuses require candidate evidence. UNCHECKED forbids status, provenance, evidence, and probabilities.
 * Evidence IDs are unique; supplied probabilities are finite 0..1 values, not necessarily normalized or aligned with status.
 * Invalid inputs throw [IllegalArgumentException]; [JobFitEvidenceMetadata] binds this judgment to its assessment.
 */
data class JobFitJudgment(
    val requirementId: String,
    val state: JobFitJudgmentState,
    val provenance: JobFitJudgmentProvenance? = null,
    val status: JobFitEvidenceStatus? = null,
    val evidenceIds: List<String> = emptyList(),
    val missingEvidence: List<String> = emptyList(),
    val providerProbabilities: Map<JobFitEvidenceStatus, Double> = emptyMap(),
) {
    init {
        require(requirementId.isNotBlank()) { "Judgment requirement ID must not be blank" }
        require(evidenceIds.all(String::isNotBlank) && evidenceIds.distinct().size == evidenceIds.size) {
            "Judgment evidence IDs must be non-blank and unique"
        }
        require(missingEvidence.all(String::isNotBlank)) { "Judgment missing-evidence descriptions must not be blank" }
        require(providerProbabilities.values.all { it.isFinite() && it in 0.0..1.0 }) {
            "Judgment probabilities must be finite values from 0 to 1"
        }
        when (state) {
            JobFitJudgmentState.JUDGED -> {
                require(provenance != null && status != null) { "A judged result requires status and provenance" }
                if (status == JobFitEvidenceStatus.SUPPORTED || status == JobFitEvidenceStatus.PARTIALLY_SUPPORTED) {
                    require(evidenceIds.isNotEmpty()) { "A positive judgment must cite candidate evidence" }
                }
            }
            JobFitJudgmentState.UNCHECKED -> require(
                provenance == null && status == null && evidenceIds.isEmpty() && providerProbabilities.isEmpty(),
            ) { "An unchecked judgment cannot carry a status or provenance" }
        }
    }
}

/** Compact user-facing summary; callers must exclude private model reasoning. No content filtering or HTML escaping occurs.
 * Summary must be non-blank; round is nullable and non-negative. Invalid inputs throw [IllegalArgumentException].
 */
data class JobFitTraceEvent(
    val kind: JobFitTraceKind,
    val summary: String,
    val round: Int? = null,
) {
    init {
        require(summary.isNotBlank()) { "Trace summary must not be blank" }
        require(round == null || round >= 0) { "Trace round cannot be negative" }
    }
}

/** Validates one source snapshot: exactly one resume/target job, unique identities, and catalog references matching snapshot/source/hash/label.
 * Each assessment requires one judgment with the same candidate evidence set; JUDGED status matches, UNCHECKED requires NOT_ESTABLISHED.
 * Citing any conflict member requires linking that conflict and citing all its evidence IDs.
 * Only schema version 1 is accepted; fallbackReason is non-blank iff fallbackUsed, otherwise null.
 * Invalid inputs throw [IllegalArgumentException]. RAW source text is retained; structural validation does not establish semantic support.
 */
data class JobFitEvidenceMetadata(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val snapshotId: String,
    val sourceManifest: List<JobFitSourceManifestEntry>,
    val assessments: List<JobFitRequirementAssessment>,
    val evidenceCatalog: List<JobFitEvidenceItem>,
    val conflicts: List<JobFitEvidenceConflict> = emptyList(),
    val judgments: List<JobFitJudgment> = emptyList(),
    val trace: List<JobFitTraceEvent> = emptyList(),
    val fallbackUsed: Boolean = false,
    val fallbackReason: String? = null,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported Job Fit evidence schema version: $schemaVersion" }
        require(snapshotId.isNotBlank()) { "Snapshot ID must not be blank" }
        require(sourceManifest.isNotEmpty()) { "Agentic results must include a source manifest" }
        require(sourceManifest.count { it.sourceType == JobFitSourceType.RESUME } == 1) {
            "A Job Fit source manifest must contain exactly one resume"
        }
        require(sourceManifest.count { it.sourceType == JobFitSourceType.TARGET_JOB } == 1) {
            "A Job Fit source manifest must contain exactly one target job"
        }
        val sourceEntries = sourceManifest.associateBy { it.sourceType to it.resourceId }
        require(sourceEntries.size == sourceManifest.size) { "Source manifest identities must be unique" }

        val evidenceById = evidenceCatalog.associateBy(JobFitEvidenceItem::id)
        require(evidenceById.size == evidenceCatalog.size) { "Evidence IDs must be unique within a snapshot" }
        evidenceCatalog.forEach { item ->
            require(item.snapshotId == snapshotId) { "Evidence belongs to a different snapshot" }
            val source = sourceEntries[item.sourceType to item.resourceId]
            require(source != null && source.contentHash == item.contentHash && source.sourceLabel == item.sourceLabel) {
                "Evidence source does not match the frozen source manifest"
            }
        }

        val conflictById = conflicts.associateBy(JobFitEvidenceConflict::id)
        require(conflictById.size == conflicts.size) { "Conflict IDs must be unique" }
        conflicts.forEach { conflict ->
            val sourceIdentities = conflict.evidenceIds.map { evidenceId ->
                val evidence = evidenceById[evidenceId] ?: throw IllegalArgumentException("Unknown conflict evidence reference: $evidenceId")
                require(evidence.sourceType != JobFitSourceType.TARGET_JOB) { "Conflicts must cite candidate source records" }
                evidence.sourceType to evidence.resourceId
            }.distinct()
            require(sourceIdentities.size >= 2) { "A conflict must cite at least two candidate source records" }
        }

        val assessmentById = assessments.associateBy(JobFitRequirementAssessment::requirementId)
        require(assessmentById.size == assessments.size) { "Requirement IDs must be unique within a result" }
        assessments.forEach { assessment ->
            assessment.targetJobEvidenceIds.forEach { evidenceId ->
                val evidence = evidenceById[evidenceId] ?: throw IllegalArgumentException("Unknown target-job evidence reference: $evidenceId")
                require(evidence.sourceType == JobFitSourceType.TARGET_JOB) { "Target-job references must point to target-job evidence" }
            }
            assessment.evidenceIds.forEach { evidenceId ->
                val evidence = evidenceById[evidenceId] ?: throw IllegalArgumentException("Unknown candidate evidence reference: $evidenceId")
                require(evidence.sourceType != JobFitSourceType.TARGET_JOB) { "Candidate evidence cannot cite the target job" }
            }
            assessment.conflictIds.forEach { conflictId ->
                val conflict = conflictById[conflictId] ?: throw IllegalArgumentException("Unknown conflict reference: $conflictId")
                require(conflict.evidenceIds.all(assessment.evidenceIds::contains)) {
                    "Assessment must cite both sides of every referenced conflict"
                }
            }
            conflicts.forEach { conflict ->
                require(!conflict.evidenceIds.any(assessment.evidenceIds::contains) || conflict.id in assessment.conflictIds) {
                    "Assessment must reference every declared conflict whose evidence it cites"
                }
            }
        }

        val judgmentByRequirement = judgments.associateBy(JobFitJudgment::requirementId)
        require(judgmentByRequirement.size == judgments.size) { "Judgment requirement IDs must be unique" }
        judgments.forEach { judgment ->
            require(judgment.requirementId in assessmentById) { "Judgment refers to an unknown requirement" }
            judgment.evidenceIds.forEach { evidenceId ->
                val evidence = evidenceById[evidenceId] ?: throw IllegalArgumentException("Unknown judgment evidence reference: $evidenceId")
                require(evidence.sourceType != JobFitSourceType.TARGET_JOB) { "Judgments cannot use target-job evidence as candidate support" }
            }
        }
        assessments.forEach { assessment ->
            val judgment = judgmentByRequirement[assessment.requirementId]
                ?: throw IllegalArgumentException("Assessment is missing its requirement judgment")
            require(assessment.evidenceIds.toSet() == judgment.evidenceIds.toSet()) {
                "Assessment and judgment must reference the same candidate evidence"
            }
            if (judgment.state == JobFitJudgmentState.UNCHECKED) {
                require(assessment.status == JobFitEvidenceStatus.NOT_ESTABLISHED) {
                    "Unchecked judgments can only produce Not established assessments"
                }
            } else {
                require(assessment.status == judgment.status) {
                    "Assessment status must match its judged status"
                }
            }
        }

        require(if (fallbackUsed) !fallbackReason.isNullOrBlank() else fallbackReason == null) {
            "Fallback use must have a reason, and unused fallback must not have one"
        }
    }

    /** Pure projection of SUPPORTED assessments in input order; joins RAW quotes with spaces in each assessment's evidenceIds order. */
    fun legacyMatchedRequirements(): List<MatchedRequirement> {
        val evidenceById = evidenceCatalog.associateBy(JobFitEvidenceItem::id)
        return assessments.filter { it.status == JobFitEvidenceStatus.SUPPORTED }.map { assessment ->
            MatchedRequirement(assessment.requirement, assessment.evidenceIds.joinToString(" ") { evidenceById.getValue(it).quote })
        }
    }

    /** Pure projection of non-SUPPORTED assessments in input order; guidance is the required missingEvidenceDescription. */
    fun legacyMissingRequirements(): List<MissingRequirement> = assessments
        .filter { it.status != JobFitEvidenceStatus.SUPPORTED }
        .map { MissingRequirement(it.requirement, requireNotNull(it.missingEvidenceDescription)) }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
