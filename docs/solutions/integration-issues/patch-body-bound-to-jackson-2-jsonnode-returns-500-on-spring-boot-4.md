---
title: "PATCH body bound to a Jackson 2 JsonNode returns 500 on Spring Boot 4"
date: 2026-10-03
category: integration-issues
module: resume-api
problem_type: integration_issue
component: api_layer
severity: high
symptoms:
  - "PATCH /api/resumes/{id} returned HTTP 500 on the real API, so the job-title Save on the score page failed"
  - "ApiExceptionHandler logged api_request_failed for HttpMessageConversionException (\"Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]\")"
  - "Root exception was tools.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `com.fasterxml.jackson.databind.JsonNode` (no Creators, like default constructor, exist)"
  - "Unit, integration and MSW-backed web tests all passed; only the opt-in live API suite failed, in 'marks the score stale when the job title changes'"
root_cause: wrong_api
resolution_type: code_fix
framework_version: "spring boot 4.0.4 (jackson 3 tools.jackson for MVC, jackson 2 com.fasterxml.jackson for app code)"
related_components:
  - testing_framework
  - frontend
tags:
  - spring-boot-4
  - jackson-3
  - jackson-2
  - requestbody
  - jsonnode
  - message-converter
  - live-api-test
---

# PATCH body bound to a Jackson 2 JsonNode returns 500 on Spring Boot 4

## Problem

This app runs two Jackson generations side by side. Application code uses Jackson 2 (`com.fasterxml.jackson`): `JacksonConfig.kt` builds the `ObjectMapper` bean, and services such as `ResumeLibraryService` inject it. Spring MVC does not use that bean to read request bodies. Spring Boot 4 reads them with `JacksonJsonHttpMessageConverter` on a Jackson 3 (`tools.jackson`) mapper. `build.gradle.kts` adds both Kotlin modules (lines 35 and 38).

`ResumeController.patch` declared `@RequestBody body: com.fasterxml.jackson.databind.JsonNode`. The Jackson 3 mapper cannot construct that Jackson 2 class, so every `PATCH /api/resumes/{id}` failed before it reached the service. The UI uses that endpoint for the job-title Save on the score page.

## Symptoms

- `PATCH /api/resumes/{id}` with `{"jobTitle":"Staff Engineer"}` returned HTTP 500 with code `INTERNAL_ERROR` and the message "The request could not be completed" (`ApiExceptionHandler.kt:57`).
- The API log showed `api_request_failed exceptionType=org.springframework.http.converter.HttpMessageConversionException` with "Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]". The cause was `tools.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of com.fasterxml.jackson.databind.JsonNode (no Creators, like default constructor, exist)`.
- Every automated layer was green: unit tests, Testcontainers integration tests and the web tests. Only the opt-in live suite failed (`apps/web/tests/liveApi.test.ts`, run with `LIVE_API_URL=http://127.0.0.1:3000` against the Docker Compose stack), in "marks the score stale when the job title changes".

## What Didn't Work

- **No test sent a PATCH body through the controller.** `ResumeControllerTests` imported the `patch` request builder but never called it. `ResumeLibraryIntegrationTests` called the service directly with a Jackson 2 tree built by the test's own mapper. The web tests ran against the MSW fake API, so no JVM controller handled the request.
- **Blaming MockMvc's default converters was wrong.** The fixing session first assumed `standaloneSetup(...).build()` reads bodies with a Jackson 2 converter and would have hidden the bug. It does not. Spring's `DefaultHttpMessageConverters` chooses `JacksonJsonHttpMessageConverter` whenever Jackson 3 is on the classpath, and falls back to `MappingJackson2HttpMessageConverter` only when it is not (spring-web 7.0.6). A default-setup PATCH test fails with the same `HttpMessageConversionException`. The missing piece was a test that sends a body.
- **The Jackson 2/3 split was found once before, for a different gap (session history).** During the Kotlin production upgrade on 2026-09-28, `JacksonConfigTests` showed that the MVC mapper (Jackson 3) and the application mapper (Jackson 2) are separate. That session added the Jackson 3 Kotlin module so plain Kotlin data-class request bodies bind. It did not cover a controller parameter typed as a Jackson 2 class.
- **Rejected alternatives:**
  - `spring.http.converters.preferred-json-mapper=jackson2` would move every endpoint onto the Jackson 2 converter, which Spring Boot 4 deprecates for removal.
  - A typed request class (`data class PatchResume(val name: String?, val jobTitle: String?)`) cannot tell an absent key from an explicit `null`. The service needs that difference: `jobTitle: null` clears the title, and an absent key leaves it alone.
  - Binding `tools.jackson.databind.JsonNode` would push a Jackson 3 tree into a service whose validation is written against the Jackson 2 tree.

## Solution

The controller binds a plain `Map`, and the service converts it to the Jackson 2 tree with the app's own mapper. The fix is on branch `codex/real-api-checks`, which had no PR open as of this writing.

Before:

```kotlin
import com.fasterxml.jackson.databind.JsonNode
// ...
@PatchMapping("/{resumeId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
fun patch(@PathVariable resumeId: UUID, @RequestBody body: JsonNode): ResumeLibraryItem = resumeLibraryService.patch(resumeId, body)
```

After (`ResumeController.kt:46-48`):

```kotlin
@PatchMapping("/{resumeId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
// A map, not a JsonNode: Spring Boot reads request bodies with Jackson 3, which cannot build the Jackson 2 tree type.
fun patch(@PathVariable resumeId: UUID, @RequestBody body: Map<String, Any?>): ResumeLibraryItem = resumeLibraryService.patch(resumeId, body)
```

`ResumeLibraryService.kt:81` adds one overload. The existing `patch(resumeId, body: JsonNode)` at line 83 and its validation stay unchanged.

```kotlin
fun patch(resumeId: UUID, fields: Map<String, Any?>): ResumeLibraryItem = patch(resumeId, objectMapper.valueToTree<JsonNode>(fields))
```

Tests:

- `ResumeControllerTests.patchReadsTheBodyWithTheJacksonConverterTheApplicationUses` sends `{"name":"Backend","jobTitle":null}` through MockMvc. It verifies the service receives `mapOf("name" to "Backend", "jobTitle" to null)`. The test failed with the production `InvalidDefinitionException` before the fix.
- `ResumeLibraryIntegrationTests` clears the title through the map overload: `library.patch(id, mapOf("jobTitle" to null))`.

Verified with `./gradlew check` (266 unit and 121 integration tests). The live suite passed 6 of 6 after rebuilding the Compose stack, and the browser job-title Save returned 200.

## Why This Works

- Each Jackson generation stays on its own side. Jackson 3 reads the request into a `Map`, and Jackson 2 turns the map into the `JsonNode` the service validates. They meet only at plain Java types. The response side already works this way: `JobStatusResponse.jsonValue` (`JobStatusResponse.kt:20`) converts a Jackson 2 tree into maps and lists before Jackson 3 serializes `result: Any?`.
- Explicit `null` survives. Jackson 3 stores `"jobTitle": null` as a null map entry, and `valueToTree` turns it into a `NullNode`. `body.has("jobTitle")` is then true and `isNull` is true, so the title is cleared. An absent key gives `has == false`, so the title is left alone (`ResumeLibraryService.kt:94-95`).
- The failure was a 500, not a 400, because of how the converter reports errors. The converter rethrows `InvalidDefinitionException`, an error in the target type, as the parent `HttpMessageConversionException`. It turns other read errors into the subclass `HttpMessageNotReadableException`. `ApiExceptionHandler` maps only the subclass to 400 `INVALID_REQUEST` (`ApiExceptionHandler.kt:40`), so the parent falls through to the catch-all 500. A 500 with "Type definition error" therefore points at the controller's parameter type, while a 400 points at the client's body. A non-object body such as `[1,2]` now fails in the converter with a 400.
- No other endpoint was affected. Every other `@RequestBody` in `src/main` binds a Kotlin data class. The remaining Jackson 2 `JsonNode` uses (job payloads, the AI client) go through the app's own `ObjectMapper`, not Spring's converter.

## Prevention

- **Give every endpoint that takes a body at least one MockMvc test that sends one.** This is the control that would have caught the bug. The default `standaloneSetup(controller).build()` already reads with the Jackson 3 converter. Add `.setControllerAdvice(ApiExceptionHandler())` when the test should check error mapping too.

  ```kotlin
  @Test
  fun patchReadsTheBody() {
      val id = UUID.randomUUID()
      Mockito.`when`(libraryService.patch(eq(id), any<Map<String, Any?>>())).thenReturn(item)
      mockMvc.perform(patch("/api/resumes/{id}", id).contentType(MediaType.APPLICATION_JSON)
          .content("""{"name":"Backend","jobTitle":null}"""))
          .andExpect(status().isOk)
      Mockito.verify(libraryService).patch(id, mapOf("name" to "Backend", "jobTitle" to null))
  }
  ```

- **Never type a `@RequestBody`, or a field inside one, as a `com.fasterxml.jackson.*` class.** Bind a Kotlin data class or a `Map`, and convert to Jackson 2 inside the service with the app's `ObjectMapper`. One way to enforce this is a guard test (not in the tree): scan `@RestController` classes and fail when any `@RequestBody` parameter type starts with `com.fasterxml.jackson.databind.`. It sees only top-level parameter types, not a Jackson 2 type nested inside a request class.
- **Run the live suite after changing a controller signature or the JSON configuration.** It sends real HTTP through the real converter, which is how this bug was found. With the Compose `app` profile running, run `LIVE_API_URL=http://127.0.0.1:3000 npm test -- liveApi` in `apps/web`. It makes real AI calls, so it is opt-in and never runs in CI.
- **When the log shows a 500 with "Type definition error", check the controller's parameter types first.** That message comes from the target type, not from the client's body.
