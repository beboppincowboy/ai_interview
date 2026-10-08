package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.storage.StoredObjectPage
import dev.jiaming.ai_interview.storage.StoredObjectSummary
import java.time.Instant
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.mockito.Mockito
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate

class ResumeStorageCleanupServiceTests {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val storage = Mockito.mock(ResumeStorageService::class.java)
    private val service = ResumeStorageCleanupService(jdbc, storage)

    @Test
    fun commitsCleanupIntentBeforeDeletingAndAcknowledgesOnlyAfterSuccess() {
        whenever(jdbc.update(Mockito.anyString(), Mockito.any())).thenReturn(1)

        service.scheduleAndDelete("resumes/orphan.pdf")

        val order = inOrder(jdbc, storage)
        order.verify(jdbc).update(
            "INSERT INTO ai_interview_app.storage_cleanup (storage_key) VALUES (?) ON CONFLICT (storage_key) DO NOTHING",
            "resumes/orphan.pdf"
        )
        order.verify(storage).delete("resumes/orphan.pdf")
        order.verify(jdbc).update("DELETE FROM ai_interview_app.storage_cleanup WHERE storage_key = ?", "resumes/orphan.pdf")
    }

    @Test
    fun leavesDurableIntentWhenObjectDeleteFails() {
        whenever(jdbc.update(Mockito.anyString(), Mockito.any())).thenReturn(1)
        Mockito.doThrow(IllegalStateException("offline")).`when`(storage).delete("resumes/orphan.pdf")

        service.scheduleAndDelete("resumes/orphan.pdf")

        Mockito.verify(jdbc, Mockito.never()).update(
            "DELETE FROM ai_interview_app.storage_cleanup WHERE storage_key = ?", "resumes/orphan.pdf"
        )
    }

    @Test
    fun deletesTheObjectEvenWhenTheCleanupIntentCannotBeRecorded() {
        Mockito.doThrow(DataAccessResourceFailureException("database down")).`when`(jdbc).update(
            Mockito.startsWith("INSERT INTO ai_interview_app.storage_cleanup"), Mockito.any<Any>()
        )

        service.scheduleAndDelete("resumes/orphan.pdf")

        Mockito.verify(storage).delete("resumes/orphan.pdf")
    }

    @Test
    fun orphanSweepDeletesNothingWhenTheReferenceLookupFails() {
        val now = Instant.parse("2026-10-05T12:00:00Z")
        whenever(storage.listObjects(null, 100)).thenReturn(
            StoredObjectPage(listOf(StoredObjectSummary("resumes/old/resume.pdf", now.minusSeconds(3 * 86_400))), false)
        )
        whenever(jdbc.queryForList(Mockito.anyString(), Mockito.eq(String::class.java), Mockito.any<Any>()))
            .thenThrow(DataAccessResourceFailureException("database down"))

        assertThatThrownBy { service.sweepOrphans(now) }.isInstanceOf(DataAccessResourceFailureException::class.java)
        Mockito.verify(storage, Mockito.never()).delete(Mockito.anyString())
    }
}
