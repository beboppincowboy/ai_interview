package dev.jiaming.ai_interview.document

/** RAG index source kinds. EXPERIENCE is internal to Job Fit retrieval and is not an upload or source-selection option. */
enum class DocumentSourceType(private val metadata: String) {
    RESUME("resume"),
    JOB_DESCRIPTION("job_description"),
    EXPERIENCE("experience"),
    ;

    fun metadataValue(): String = metadata
}
