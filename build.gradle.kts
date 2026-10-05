plugins {
	java
	kotlin("jvm") version "2.4.20"
	kotlin("plugin.spring") version "2.4.20"
	id("org.springframework.boot") version "4.0.4"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.jiaming"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

kotlin {
	jvmToolchain(21)
}

repositories {
	mavenCentral()
}

extra["springAiVersion"] = "2.0.0-M4"
extra["awsSdkVersion"] = "2.29.52"

springBoot {
	mainClass.set("dev.jiaming.ai_interview.AiInterviewApplicationKt")
}

dependencies {
	implementation(kotlin("reflect"))
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("io.github.jan-tennert.supabase:postgrest-kt:3.8.0")
	implementation("io.ktor:ktor-client-cio-jvm:3.5.1")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.ai:spring-ai-google-genai-embedding")
	implementation("org.springframework.ai:spring-ai-starter-model-google-genai")
	implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
	implementation("org.apache.pdfbox:pdfbox:3.0.8")
	implementation("org.apache.tika:tika-core:3.3.2")
	implementation("org.apache.tika:tika-parsers-standard-package:3.3.2")
	implementation("software.amazon.awssdk:s3")
	implementation("software.amazon.awssdk:sqs")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation(kotlin("test"))
	testImplementation("io.ktor:ktor-client-mock-jvm:3.5.1")
	testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
	testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.awaitility:awaitility")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-localstack")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
		mavenBom("software.amazon.awssdk:bom:${property("awsSdkVersion")}")
		mavenBom("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.register<JavaExec>("supabaseMigrate") {
	description = "Runs the standalone Supabase Flyway migration or runtime-role bootstrap."
	group = "database"
	dependsOn(tasks.named("classes"))
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("dev.jiaming.ai_interview.supabase.SupabaseMigrationMain")
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
	java.srcDir("src/integrationTest/java")
	resources.srcDir("src/integrationTest/resources")
	compileClasspath += sourceSets.main.get().output
	runtimeClasspath += output + compileClasspath
}

kotlin.sourceSets.maybeCreate("integrationTest").kotlin.srcDir("src/integrationTest/kotlin")
// Like the test compilation, integration tests may set main's internal members, e.g. RedisRequestGuard's short TTLs.
kotlin.target.compilations.getByName("integrationTest").associateWith(kotlin.target.compilations.getByName("main"))

configurations[integrationTestSourceSet.implementationConfigurationName]
	.extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName]
	.extendsFrom(configurations.testRuntimeOnly.get())

val integrationTest by tasks.registering(Test::class) {
	description = "Runs PostgreSQL, Redis and LocalStack integration tests."
	group = "verification"
	testClassesDirs = integrationTestSourceSet.output.classesDirs
	classpath = integrationTestSourceSet.runtimeClasspath
	shouldRunAfter(tasks.test)
}

tasks.check {
	dependsOn(integrationTest)
}
