package dev.jiaming.ai_interview.storage

/** Every S3 error is thrown as ApiRequestException 502 STORAGE_UNAVAILABLE, the same code in API responses and failed jobs. */
interface ObjectStorageService {
    fun put(key: String, content: ByteArray, contentType: String?, metadata: Map<String, String>?, tags: Map<String, String>?): StoredObject
    fun get(key: String): StoredObjectContent
    fun delete(key: String?)
    fun tag(key: String?, tags: Map<String, String>?)
}
