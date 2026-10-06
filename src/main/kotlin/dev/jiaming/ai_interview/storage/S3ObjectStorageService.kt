package dev.jiaming.ai_interview.storage

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ExpirationStatus
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.LifecycleExpiration
import software.amazon.awssdk.services.s3.model.LifecycleRule
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.Tag
import software.amazon.awssdk.services.s3.model.Tagging

@Service
class S3ObjectStorageService(private val s3Client: S3Client, private val properties: StorageProperties) : ObjectStorageService {
    private val bucketReady = AtomicBoolean(false)
    private val lifecycleReady = AtomicBoolean(false)

    override fun put(key: String, content: ByteArray, contentType: String?, metadata: Map<String, String>?, tags: Map<String, String>?): StoredObject {
        ensureBucket()
        try {
            val request = PutObjectRequest.builder()
                .bucket(bucket())
                .key(key)
                .contentType(if (contentType.isNullOrBlank()) "application/octet-stream" else contentType)
                .metadata(metadata ?: emptyMap())
                .tagging(encodedTags(tags))
                .build()
            s3Client.putObject(request, RequestBody.fromBytes(content))
            return StoredObject(bucket(), key, content.size.toLong())
        } catch (exception: S3Exception) {
            throw storageUnavailable(exception)
        }
    }

    override fun get(key: String): StoredObjectContent {
        ensureBucket()
        try {
            val responseBytes = s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket()).key(key).build())
            val response = responseBytes.response()
            return StoredObjectContent(responseBytes.asByteArray(), response.contentType(), response.metadata())
        } catch (exception: S3Exception) {
            throw storageUnavailable(exception)
        }
    }

    override fun delete(key: String?) {
        if (key.isNullOrBlank()) return
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket()).key(key).build())
        } catch (exception: S3Exception) {
            throw storageUnavailable(exception)
        }
    }

    override fun tag(key: String?, tags: Map<String, String>?) {
        if (key.isNullOrBlank()) return
        try {
            s3Client.putObjectTagging(
                PutObjectTaggingRequest.builder().bucket(bucket()).key(key)
                    .tagging(Tagging.builder().tagSet(toTags(tags)).build()).build()
            )
        } catch (exception: S3Exception) {
            throw storageUnavailable(exception)
        }
    }

    override fun list(prefix: String, startAfter: String?, maxKeys: Int): StoredObjectPage {
        ensureBucket()
        try {
            val request = ListObjectsV2Request.builder().bucket(bucket()).prefix(prefix).maxKeys(maxKeys)
            if (startAfter != null) request.startAfter(startAfter)
            val response = s3Client.listObjectsV2(request.build())
            return StoredObjectPage(response.contents().map { StoredObjectSummary(it.key(), it.lastModified()) }, response.isTruncated == true)
        } catch (exception: S3Exception) {
            throw storageUnavailable(exception)
        }
    }

    private fun ensureBucket() {
        if (bucketReady.get()) {
            ensurePendingLifecycle()
            return
        }
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket()).build())
            bucketReady.set(true)
        } catch (exception: NoSuchBucketException) {
            createBucket()
        } catch (exception: S3Exception) {
            if (exception.statusCode() == 404) createBucket() else throw storageUnavailable(exception)
        }
        ensurePendingLifecycle()
    }

    private fun createBucket() {
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket()).build())
            bucketReady.set(true)
        } catch (exception: S3Exception) {
            throw storageUnavailable(exception)
        }
    }

    private fun ensurePendingLifecycle() {
        if (lifecycleReady.get()) return
        try {
            val rules = mutableListOf<LifecycleRule>()
            try {
                rules.addAll(s3Client.getBucketLifecycleConfiguration(GetBucketLifecycleConfigurationRequest.builder().bucket(bucket()).build()).rules())
            } catch (exception: S3Exception) {
                if (exception.statusCode() != 404) throw exception
            }
            rules.removeIf { it.id() == PENDING_LIFECYCLE_RULE }
            rules.add(pendingLifecycleRule())
            s3Client.putBucketLifecycleConfiguration(
                PutBucketLifecycleConfigurationRequest.builder().bucket(bucket())
                    .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(rules).build()).build()
            )
            lifecycleReady.set(true)
        } catch (exception: S3Exception) {
            if (exception.statusCode() == 400 || exception.statusCode() == 403 || exception.statusCode() == 501) {
                lifecycleReady.set(true)
                log.warn("resume_pending_lifecycle_unavailable bucket={} status={} reason={}", bucket(), exception.statusCode(), exception.message)
                return
            }
            throw storageUnavailable(exception)
        }
    }

    private fun pendingLifecycleRule(): LifecycleRule {
        val days = maxOf(1, (properties.pendingRetentionHours + 23) / 24)
        return LifecycleRule.builder().id(PENDING_LIFECYCLE_RULE).status(ExpirationStatus.ENABLED)
            .filter(LifecycleRuleFilter.builder().tag(Tag.builder().key("processing-status").value("pending").build()).build())
            .expiration(LifecycleExpiration.builder().days(days).build()).build()
    }

    private fun toTags(tags: Map<String, String>?): List<Tag> = tags.orEmpty().toSortedMap()
        .map { (key, value) -> Tag.builder().key(key).value(value).build() }

    private fun encodedTags(tags: Map<String, String>?): String? = tags.orEmpty().toSortedMap()
        .takeIf { it.isNotEmpty() }
        ?.entries
        ?.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun bucket(): String = properties.bucket?.takeUnless { it.isBlank() }
        ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "S3 bucket is not configured")

    private fun storageUnavailable(exception: Exception) = ResponseStatusException(
        HttpStatus.BAD_GATEWAY,
        "Object storage is not reachable. Start LocalStack S3 or check S3_ENDPOINT/S3 credentials.",
        exception
    )

    private companion object {
        const val PENDING_LIFECYCLE_RULE = "ai-interview-pending-resumes"
        val log = LoggerFactory.getLogger(S3ObjectStorageService::class.java)
    }
}
