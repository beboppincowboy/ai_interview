package dev.jiaming.ai_interview.fit.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.fit.JobFitEvidenceRetriever
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

class JobFitMcpConfigurationTests {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(JobFitMcpConfiguration::class.java, TestInfrastructure::class.java)

    @Test
    fun disabledByDefaultRegistersNoEndpointOrClient() {
        runner.run {
            assertThat(it.startupFailure).isNull()
            assertThat(it.getBeansOfType(ServletRegistrationBean::class.java)).isEmpty()
            assertThat(it.getBeansOfType(JobFitMcpClient::class.java)).isEmpty()
        }
    }

    @Test
    fun enabledRequiresAUsableToken() {
        runner.withPropertyValues("app.job-fit.mcp.enabled=true").run {
            assertThat(it.startupFailure).hasStackTraceContaining("JOB_FIT_MCP_TOKEN must be at least 32 characters")
        }
        runner.withPropertyValues("app.job-fit.mcp.enabled=true", "app.job-fit.mcp.token=too-short").run {
            assertThat(it.startupFailure).hasStackTraceContaining("JOB_FIT_MCP_TOKEN must be at least 32 characters")
        }
    }

    @Test
    fun enabledServesTheEndpointOnlyWhereTheWorkerRuns() {
        mapOf("worker" to true, "all" to true, "api" to false).forEach { (mode, served) ->
            runner.withPropertyValues("app.job-fit.mcp.enabled=true", "app.job-fit.mcp.token=$TOKEN", "app.runtime.mode=$mode").run {
                assertThat(it.startupFailure).isNull()
                val servlet = it.getBean(ServletRegistrationBean::class.java)
                assertThat(servlet.urlMappings).containsExactly(JOB_FIT_MCP_ENDPOINT)
                assertThat(servlet.isEnabled).describedAs(mode).isEqualTo(served)
                assertThat(it.getBean(JobFitMcpClient::class.java)).isNotNull()
            }
        }
    }

    @Test
    fun propertiesNeverPrintTheToken() {
        assertThat(JobFitMcpProperties(enabled = true, token = TOKEN).toString()).doesNotContain(TOKEN)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobFitMcpProperties::class)
    class TestInfrastructure {
        @Bean fun retriever() = Mockito.mock(JobFitEvidenceRetriever::class.java)
        @Bean fun runtimeModeProperties(environment: Environment) = RuntimeModeProperties(environment.getProperty("app.runtime.mode"))
        @Bean fun objectMapper() = ObjectMapper()
    }

    private companion object {
        const val TOKEN = "test-token-0123456789-abcdefghijklmnop"
    }
}
