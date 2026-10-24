package dev.jiaming.ai_interview.rag

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.Mockito
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import java.lang.reflect.Modifier

class RagRetrievalServiceTests {
	@Test
	@Suppress("UNCHECKED_CAST")
	fun retrievalFilterContainsEachIndexAndItsClaimVersion() {
		val vectorStore = Mockito.mock(VectorStore::class.java)
		val provider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<VectorStore>
		Mockito.`when`(provider.ifAvailable).thenReturn(vectorStore)
		Mockito.`when`(vectorStore.similaritySearch(any<SearchRequest>())).thenReturn(listOf(Document("doc", "context", mapOf<String, Any>("contextId" to "resume:experience:0", "indexId" to "current", "claimVersion" to 4L))))
		val service = RagRetrievalService(provider, SimpleMeterRegistry())
		val resumeIndex = UUID.randomUUID()
		val jobIndex = UUID.randomUUID()

		val result = service.retrieve("backend engineering", listOf(RagDocumentIndexHandle(resumeIndex, 4L), RagDocumentIndexHandle(jobIndex, 2L)), 6)

		assertThat(result.map { it.sourceContextId() }).containsExactly("resume:experience:0")
		val request = ArgumentCaptor.forClass(SearchRequest::class.java)
		Mockito.verify(vectorStore).similaritySearch(request.capture())
		val filter = request.value.filterExpression.toString()
		assertThat(filter).contains(resumeIndex.toString(), jobIndex.toString(), "claimVersion", "4", "2")
	}

	@Test
	fun everyPublicRetrieveOverloadTakesAnIndexScope() {
		val scopeTypes = setOf(RagDocumentIndexHandle::class.java, List::class.java)
		val retrieveMethods = RagRetrievalService::class.java.declaredMethods.filter { it.name == "retrieve" && Modifier.isPublic(it.modifiers) }

		assertThat(retrieveMethods).isNotEmpty
		assertThat(retrieveMethods).allSatisfy { method -> assertThat(method.parameterTypes.toList()).containsAnyElementsOf(scopeTypes) }
	}

	@Test
	@Suppress("UNCHECKED_CAST")
	fun singleIndexRetrievalFiltersByThatIndex() {
		val vectorStore = Mockito.mock(VectorStore::class.java)
		val provider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<VectorStore>
		Mockito.`when`(provider.ifAvailable).thenReturn(vectorStore)
		Mockito.`when`(vectorStore.similaritySearch(any<SearchRequest>())).thenReturn(emptyList())
		val service = RagRetrievalService(provider, SimpleMeterRegistry())
		val index = UUID.randomUUID()

		service.retrieve("backend engineering", RagDocumentIndexHandle(index, 3L), 6)

		val request = ArgumentCaptor.forClass(SearchRequest::class.java)
		Mockito.verify(vectorStore).similaritySearch(request.capture())
		assertThat(request.value.filterExpression).isNotNull
		assertThat(request.value.filterExpression.toString()).contains(index.toString(), "claimVersion", "3")
	}

	@Test
	@Suppress("UNCHECKED_CAST")
	fun missingIndexScopeReturnsEmptyWithoutSearching() {
		val vectorStore = Mockito.mock(VectorStore::class.java)
		val provider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<VectorStore>
		Mockito.`when`(provider.ifAvailable).thenReturn(vectorStore)
		val service = RagRetrievalService(provider, SimpleMeterRegistry())

		assertThat(service.retrieve("backend engineering", null as RagDocumentIndexHandle?, 6)).isEmpty()
		assertThat(service.retrieve("backend engineering", emptyList(), 6)).isEmpty()
		Mockito.verifyNoInteractions(vectorStore)
	}
}
