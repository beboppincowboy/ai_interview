package dev.jiaming.ai_interview.storage

import java.time.Instant

@JvmRecord
data class StoredObjectSummary(val key: String, val lastModified: Instant)

@JvmRecord
data class StoredObjectPage(val objects: List<StoredObjectSummary>, val truncated: Boolean)
