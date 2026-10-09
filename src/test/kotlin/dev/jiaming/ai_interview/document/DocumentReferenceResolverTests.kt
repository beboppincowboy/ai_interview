package dev.jiaming.ai_interview.document

import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.targetjob.TargetJobPersistenceService
import dev.jiaming.ai_interview.resume.ResumePersistenceService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.util.Optional
import java.util.UUID

class DocumentReferenceResolverTests {

    private val resumes = Mockito.mock(ResumePersistenceService::class.java)
    private val targetJobs = Mockito.mock(TargetJobPersistenceService::class.java)
    private val hasher = ContentHasher()
    private val resolver = DocumentReferenceResolver(resumes, targetJobs)
    private val userId = UUID.randomUUID()

    @Test
    fun requiresAResumeReference() {
        expectCode(null, null, "RESUME_REFERENCE_REQUIRED", HttpStatus.BAD_REQUEST)
    }

    @Test
    fun loadsTheReadyResumeByIdWithItsPersistedChunks() {
        val resumeId = UUID.randomUUID()
        val chunks = listOf(DocumentChunk(0, "Experience", "Built an API", "resume:experience:0"))
        val resume = ResolvedDocument(DocumentSourceType.RESUME, resumeId, hasher.sha256("Resume text"), "Resume text", chunks)
        ready(resumeId, resume)

        val result = resolver.resolveStrict(userId, resumeId, null)

        assertThat(result.resume()).isEqualTo(resume)
        assertThat(result.resume().persistedChunks()).isEqualTo(chunks)
        assertThat(result.targetJob()).isEmpty()
    }

    @Test
    fun resolvesTheTargetJobById() {
        val resumeId = UUID.randomUUID()
        val targetJobId = UUID.randomUUID()
        val targetJob = document(DocumentSourceType.JOB_DESCRIPTION, targetJobId, "Job text")
        ready(resumeId, document(DocumentSourceType.RESUME, resumeId, "Resume text"))
        Mockito.`when`(targetJobs.findDocument(userId, targetJobId)).thenReturn(Optional.of(targetJob))

        assertThat(resolver.resolveStrict(userId, resumeId, targetJobId).targetJob()).contains(targetJob)
    }

    @Test
    fun unknownResumeIdRaisesNotFound() {
        val resumeId = UUID.randomUUID()
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.empty())

        expectCode(resumeId, null, "RESUME_NOT_FOUND", HttpStatus.NOT_FOUND)
    }

    @Test
    fun pendingOrFailedResumeIsNotReady() {
        listOf("PENDING", "FAILED").forEach { status ->
            val resumeId = UUID.randomUUID()
            Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of(status))

            expectCode(resumeId, null, "RESUME_NOT_READY", HttpStatus.CONFLICT)
        }
    }

    @Test
    fun readyStatusButMissingDocumentRaisesNotReady() {
        val resumeId = UUID.randomUUID()
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"))
        Mockito.`when`(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.empty())

        expectCode(resumeId, null, "RESUME_NOT_READY", HttpStatus.CONFLICT)
    }

    @Test
    fun unknownTargetJobIdRaisesNotFound() {
        val resumeId = UUID.randomUUID()
        val targetJobId = UUID.randomUUID()
        ready(resumeId, document(DocumentSourceType.RESUME, resumeId, "Resume text"))
        Mockito.`when`(targetJobs.findDocument(userId, targetJobId)).thenReturn(Optional.empty())

        expectCode(resumeId, targetJobId, "TARGET_JOB_NOT_FOUND", HttpStatus.NOT_FOUND)
    }

    private fun ready(resumeId: UUID, resume: ResolvedDocument) {
        Mockito.`when`(resumes.findProcessingStatus(userId, resumeId)).thenReturn(Optional.of("READY"))
        Mockito.`when`(resumes.findReadyDocument(userId, resumeId)).thenReturn(Optional.of(resume))
    }

    private fun expectCode(resumeId: UUID?, targetJobId: UUID?, code: String, status: HttpStatus) {
        assertThatThrownBy { resolver.resolveStrict(userId, resumeId, targetJobId) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { exception ->
                assertThat(exception.code()).isEqualTo(code)
                assertThat(exception.status()).isEqualTo(status)
            }
    }

    private fun document(sourceType: DocumentSourceType, id: UUID, text: String) =
        ResolvedDocument(sourceType, id, hasher.sha256(text), text, emptyList())
}
