package dev.jiaming.ai_interview.supabase

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.UUID

@Testcontainers
class SupabaseMigrationIntegrationTests {
    @Test
    fun `empty database runs preserved migrations without a baseline or Supabase roles`() {
        dropApiRoles()
        val database = createDatabase()
        try {
            val dataSource = dataSource(database)
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA extensions")
                    statement.execute("CREATE FUNCTION extensions.provider_marker() RETURNS integer LANGUAGE sql IMMUTABLE AS 'SELECT 1'")
                }
            }

            SupabaseMigrationRunner(dataSource).migrate()

            assertHistory(dataSource, baseline = false)
            assertViewShape(dataSource)
            dataSource.connection.use { connection ->
                assertThat(roleExists(connection, "anon")).isFalse()
                assertThat(roleExists(connection, "authenticated")).isFalse()
                assertThat(roleExists(connection, "service_role")).isFalse()
            }
        } finally {
            dropDatabase(database)
            dropApiRoles()
        }
    }

    @Test
    fun `provider objects baseline at zero and grants stay narrow through reruns`() {
        dropApiRoles()
        val database = createDatabase()
        try {
            val dataSource = dataSource(database)
            createApiRoles()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE public.provider_marker (id integer)")
                    statement.execute("INSERT INTO public.provider_marker VALUES (1)")
                    statement.execute("CREATE SCHEMA extensions")
                    statement.execute("CREATE EXTENSION \"uuid-ossp\" WITH SCHEMA extensions")
                    statement.execute("CREATE EXTENSION pgcrypto WITH SCHEMA extensions")
                    statement.execute("CREATE EXTENSION vector WITH SCHEMA extensions")
                    statement.execute("CREATE FUNCTION extensions.provider_marker() RETURNS integer LANGUAGE sql IMMUTABLE AS 'SELECT 1'")
                    statement.execute("GRANT USAGE ON SCHEMA public TO anon, authenticated, service_role")
                    statement.execute("GRANT SELECT ON public.provider_marker TO anon, authenticated, service_role")
                    statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE ai_interview GRANT SELECT ON TABLES TO anon, authenticated, service_role")
                }
            }

            migrateThroughV8(dataSource)
            assertViewShape(dataSource, includeV9Columns = false)
            val v1ToV8Checksums = migrationChecksums(dataSource)
            assertThat(v1ToV8Checksums.keys).containsExactly("1", "2", "3", "4", "5", "6", "7", "8")

            val runner = SupabaseMigrationRunner(dataSource)
            runner.migrate()

            assertHistory(dataSource, baseline = true)
            assertViewShape(dataSource)
            assertThat(migrationChecksums(dataSource).filterKeys { it in v1ToV8Checksums }).isEqualTo(v1ToV8Checksums)
            val userId = UUID.randomUUID()
            val jobId = UUID.randomUUID()
            val resumeId = UUID.randomUUID()
            val jobDescriptionId = UUID.randomUUID()
            val targetJobId = UUID.randomUUID()
            val practiceSetId = UUID.randomUUID()
            val attemptId = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("INSERT INTO ai_interview_app.app_users (id, email) VALUES ('$userId', 'migration-test@example.test')")
                    statement.execute("""INSERT INTO ai_interview_app.background_jobs
                        (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, max_attempts)
                        VALUES ('$jobId', '$userId', 'RESUME_SCORE', 'resume', '$resumeId', 'QUEUED', 'QUEUED',
                        '{"resumeId":"$resumeId","jobDescriptionId":"$jobDescriptionId","targetJobId":"$targetJobId","practiceSetId":"$practiceSetId","attemptId":"$attemptId","prompt":"PRIVATE_PROMPT_MARKER","answerText":"PRIVATE_ANSWER_MARKER"}'::jsonb, 3)""")
                    statement.execute("CREATE SEQUENCE ai_interview_app.runtime_test_seq")
                }
            }
            assertServiceRoleCanReadSanitizedView(database, jobId, resumeId, jobDescriptionId, targetJobId, practiceSetId, attemptId)
            assertApiRolesCannotReadApplicationData(database)
            assertProviderObjectStillAccessible(database)

            val password = "runtime' test password"
            runner.bootstrapRuntime(password)
            assertRuntimePrivileges(database, password, jobId, userId)
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("ALTER ROLE ai_interview_runtime BYPASSRLS") }
            }
            assertThatThrownBy { runner.bootstrapRuntime(password) }.hasMessageContaining("privileged attributes")
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("ALTER ROLE ai_interview_runtime NOBYPASSRLS") }
            }
            runner.migrate()
            assertHistory(dataSource, baseline = true)
            assertThat(migrationChecksums(dataSource).filterKeys { it in v1ToV8Checksums }).isEqualTo(v1ToV8Checksums)
        } finally {
            dropDatabase(database)
            dropApiRoles()
        }
    }

    @Test
    fun `initial migration rejects existing application objects without history`() {
        val database = createDatabase()
        try {
            val dataSource = dataSource(database)
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE public.background_jobs (id integer)") }
            }

            assertThatThrownBy { SupabaseMigrationRunner(dataSource).migrate() }
                .hasMessageContaining("application objects already exist without Flyway history")
            dataSource.connection.use { connection -> assertThat(relationExists(connection, "flyway_schema_history")).isFalse() }
        } finally {
            dropDatabase(database)
        }
    }

    private fun assertHistory(dataSource: DriverManagerDataSource, baseline: Boolean) {
        dataSource.connection.use { connection ->
            val versions = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM public.flyway_schema_history WHERE type = 'SQL' AND success ORDER BY installed_rank").use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
            assertThat(versions).containsExactly(*(1..19).map(Int::toString).toTypedArray())
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = 'public.vector_store'::regclass AND attname = 'embedding'").use { result ->
                    result.next(); assertThat(result.getString(1)).isEqualTo("vector(1024)")
                }
                statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_vector_store_embedding'").use { result ->
                    result.next(); assertThat(result.getString(1)).contains("USING hnsw", "vector_cosine_ops")
                }
            }
            val baselineRows = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM public.flyway_schema_history WHERE type = 'BASELINE' AND success").use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
            assertThat(baselineRows).containsExactlyElementsOf(if (baseline) listOf("0") else emptyList())
        }
    }

    private fun assertViewShape(dataSource: DriverManagerDataSource, includeV9Columns: Boolean = true) {
        dataSource.connection.use { connection ->
            val columns = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'ai_interview_api' AND table_name = 'job_status' ORDER BY ordinal_position").use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
            val v1ToV8Columns = listOf(
                "id", "user_id", "job_type", "status", "stage", "attempts", "result_payload", "last_error",
                "error_code", "retryable", "created_at", "started_at", "completed_at", "resource_id", "request_payload"
            )
            assertThat(columns).containsExactlyElementsOf(
                if (includeV9Columns) v1ToV8Columns + listOf("max_attempts", "resource_type") else v1ToV8Columns
            )
            val options = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT reloptions::text FROM pg_class WHERE oid = 'ai_interview_api.job_status'::regclass").use { result ->
                    result.next(); result.getString(1)
                }
            }
            assertThat(options).contains("security_invoker=true")
        }
    }

    private fun assertServiceRoleCanReadSanitizedView(
        database: String,
        jobId: UUID,
        resumeId: UUID,
        jobDescriptionId: UUID,
        targetJobId: UUID,
        practiceSetId: UUID,
        attemptId: UUID
    ) {
        dataSource(database, "service_role", "service_role_test").connection.use { connection ->
            connection.prepareStatement("SELECT request_payload::text, max_attempts, resource_type FROM ai_interview_api.job_status WHERE id = ?").use { statement ->
                statement.setObject(1, jobId)
                statement.executeQuery().use { result ->
                    assertThat(result.next()).isTrue()
                    val payload = result.getString(1)
                    assertThat(payload).contains(resumeId.toString(), jobDescriptionId.toString(), targetJobId.toString(), practiceSetId.toString(), attemptId.toString())
                    assertThat(payload).doesNotContain("PRIVATE_PROMPT_MARKER", "PRIVATE_ANSWER_MARKER", "prompt", "answerText")
                    assertThat(result.getInt(2)).isEqualTo(3)
                    assertThat(result.getString(3)).isEqualTo("resume")
                }
            }
            assertThatThrownBy {
                connection.createStatement().use { it.executeQuery("SELECT request_fingerprint FROM ai_interview_app.background_jobs") }
            }.isInstanceOf(SQLException::class.java)
            assertThatThrownBy {
                connection.createStatement().use { it.executeQuery("SELECT id FROM ai_interview_app.experiences") }
            }.isInstanceOf(SQLException::class.java)
        }
    }

    private fun assertApiRolesCannotReadApplicationData(database: String) {
        listOf("anon" to "anon_test", "authenticated" to "authenticated_test").forEach { (role, password) ->
            dataSource(database, role, password).connection.use { connection ->
                assertDenied(connection, "SELECT id FROM ai_interview_api.job_status")
                assertDenied(connection, "SELECT id FROM ai_interview_app.background_jobs")
                assertDenied(connection, "SELECT id FROM ai_interview_app.experiences")
                FRONTEND_TABLES.forEach { assertDenied(connection, "SELECT 1 FROM ai_interview_app.$it") }
                assertDenied(connection, "SELECT id FROM public.vector_store")
            }
        }
    }

    private fun assertProviderObjectStillAccessible(database: String) {
        dataSource(database, "anon", "anon_test").connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM public.provider_marker").use { result -> assertThat(result.next()).isTrue() }
            }
        }
    }

    private fun assertRuntimePrivileges(database: String, password: String, jobId: UUID, userId: UUID) {
        dataSource(database, "ai_interview_runtime", password).connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit FROM pg_roles WHERE rolname = current_user").use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getBoolean(1)).isFalse()
                }
            }
            connection.prepareStatement("UPDATE ai_interview_app.background_jobs SET stage = 'PROCESSING' WHERE id = ?").use { statement ->
                statement.setObject(1, jobId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
            connection.prepareStatement("INSERT INTO ai_interview_app.experiences (user_id, title, description, source, content_hash) VALUES (?, ?, ?, ?, ?)").use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, "Runtime access test")
                statement.setString(3, "The app runtime can write the private experience table.")
                statement.setString(4, "FORM")
                statement.setString(5, "runtime-experience-hash")
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
            connection.createStatement().use {
                it.executeQuery("SELECT nextval('ai_interview_app.runtime_test_seq')").use { result -> assertThat(result.next()).isTrue() }
            }
            FRONTEND_TABLES.forEach { table ->
                connection.createStatement().use { it.executeQuery("SELECT count(*) FROM ai_interview_app.$table").use { result -> assertThat(result.next()).isTrue() } }
            }
            connection.createStatement().use {
                it.executeUpdate("INSERT INTO public.vector_store (content, metadata) VALUES ('runtime test', '{}'::json)")
            }
            assertThatThrownBy {
                connection.createStatement().use { it.execute("CREATE TABLE ai_interview_app.runtime_must_not_ddl (id integer)") }
            }.isInstanceOf(SQLException::class.java)
        }
    }

    private fun assertDenied(connection: Connection, sql: String) {
        assertThatThrownBy { connection.createStatement().use { it.executeQuery(sql) } }.isInstanceOf(SQLException::class.java)
    }

    private fun createApiRoles() {
        DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password).use { connection ->
            listOf("anon" to "anon_test", "authenticated" to "authenticated_test", "service_role" to "service_role_test").forEach { (role, password) ->
                connection.createStatement().use { it.execute("CREATE ROLE $role LOGIN PASSWORD '$password'") }
            }
        }
    }

    private fun dropApiRoles() {
        DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password).use { connection ->
            listOf("ai_interview_runtime", "anon", "authenticated", "service_role").forEach { role ->
                if (roleExists(connection, role)) connection.createStatement().use { it.execute("DROP ROLE $role") }
            }
        }
    }

    private fun roleExists(connection: Connection, role: String): Boolean = connection.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ?)"
    ).use { statement ->
        statement.setString(1, role)
        statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
    }

    private fun relationExists(connection: Connection, relation: String): Boolean = connection.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = ?)"
    ).use { statement ->
        statement.setString(1, relation)
        statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
    }

    private fun createDatabase(): String {
        val name = "u2_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE \"$name\"") }
        }
        return name
    }

    private fun migrateThroughV8(dataSource: DriverManagerDataSource) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas("public")
            .defaultSchema("public")
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .baselineDescription("Supabase provider objects")
            .initSql("SET search_path TO public, extensions")
            .ignoreMigrationPatterns("*:pending")
            .cleanDisabled(true)
            .target(MigrationVersion.fromVersion("8"))
            .load()
        flyway.baseline()
        flyway.migrate()
    }

    private fun migrationChecksums(dataSource: DriverManagerDataSource): Map<String, Int> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version, checksum FROM public.flyway_schema_history WHERE type = 'SQL' AND success ORDER BY installed_rank").use { result ->
                buildMap<String, Int> { while (result.next()) put(result.getString(1), result.getInt(2)) }
            }
        }
    }

    private fun dropDatabase(name: String) {
        DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password).use { connection ->
            connection.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$name\"") }
        }
    }

    private fun dataSource(database: String, username: String = POSTGRES.username, password: String = POSTGRES.password) =
        DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            setUrl("${POSTGRES.jdbcUrl.substringBeforeLast('/')}/$database")
            setUsername(username)
            setPassword(password)
            setConnectionProperties(Properties().apply { setProperty("currentSchema", "public,extensions") })
        }

    companion object {
        @Container
        @JvmField
        val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_migration_test")
            .withUsername("ai_interview")
            .withPassword("ai_interview")

        // Private tables added for the frontend contract (V10-V19): runtime-only, never Data API readable.
        val FRONTEND_TABLES = listOf("storage_cleanup", "resume_scores", "job_fits", "experience_suggestions",
            "practice_sets", "practice_questions", "answer_attempts", "voice_sessions")
    }
}
