package dev.jiaming.ai_interview.storage

/** Every S3 error is thrown as ApiRequestException 502 STORAGE_UNAVAILABLE, the same code in API responses and failed jobs. */
interface ObjectStorageService {
    /** Stores bytes and returns bucket/key/size metadata; S3 errors become 502 `STORAGE_UNAVAILABLE`. */
    fun put(key: String, content: ByteArray, contentType: String?, metadata: Map<String, String>?, tags: Map<String, String>?): StoredObject
    /** Loads content and metadata; S3 errors become 502 `STORAGE_UNAVAILABLE`. */
    fun get(key: String): StoredObjectContent
    /** Deletes the object; null/blank keys are a no-op in the S3 implementation. */
    fun delete(key: String?)
    /** Replaces object tags; null/blank keys are a no-op in the S3 implementation. */
    fun tag(key: String?, tags: Map<String, String>?)
}
