// The deployable Spring Boot application — the framework runtime itself
// (webhook ingress, workflow engine, LLM/Jira/GitHub/Slack/Neo4j/sandbox
// integrations, plugin loading). Ships with zero built-in agents: see
// ADR-0011/ADR-0012. The out-of-the-box `agents` module is deliberately
// NOT a dependency here — it's loaded at runtime from a configured
// plugins directory (ADR-0010's ServiceLoader mechanism), exactly like
// any third-party agent jar would be.
plugins {
    id("java")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencyManagement {
    imports {
        // 2.0.0 is the first Spring AI release targeting Spring Boot
        // 4.1/Spring Framework 7 (this project's stack); 1.0.0 was built
        // against Framework 6 and crashes at runtime (e.g.
        // OpenAiApi calling the removed HttpHeaders.addAll(MultiValueMap)
        // overload) once a provider's ChatModel bean is actually built.
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
    }
}

dependencies {
    // Web / core Spring
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Persistence: JPA/Hibernate against Postgres, schema managed by Liquibase.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    runtimeOnly("org.postgresql:postgresql")

    // Workflow YAML config parsing.
    implementation("org.yaml:snakeyaml")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Graph execution engine.
    implementation("org.bsc.langgraph4j:langgraph4j-core:1.8.20")

    // Observability.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // OpenAPI/Swagger UI documentation for the REST ingress controllers.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // Kubernetes API client for dispatching sandbox execution, and for pod
    // create/get/list/delete/watch in KubernetesSandboxWorkspaceClient.
    // NOTE: `pods/exec` is NOT done through this client — both the 7.x and
    // 6.13.5 lines' `exec(...)` perform a WebSocket upgrade for it
    // (regardless of transport module), which was observed live to be
    // rejected with a bare 403 by the API server, even though the exact
    // same ServiceAccount's bearer token was proven (via a direct
    // `kubectl --token=... exec`) to have fully correct RBAC and to work
    // fine for exec via kubectl's own client — i.e. this is a fabric8
    // client-side WebSocket-handshake bug, not a cluster/RBAC/version
    // issue. See KubernetesSandboxWorkspaceClient#exec for the
    // kubectl-subprocess workaround used instead.
    implementation("io.fabric8:kubernetes-client:7.3.1")

    // Real GitHub integration: JGit for local clone/branch/commit/push
    // (applying a unified diff produced by the sandbox workspace), plain
    // RestClient (already on the classpath via spring-boot-starter-web)
    // for the GitHub REST API itself (PR creation/comments/file reads).
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")

    // LLM integration: Vertex AI Gemini via Spring AI. Config-gated;
    // see LlmAutoConfiguration for why the starter's own autoconfiguration is
    // excluded by default.
    // Google GenAI provider (Vertex AI Gemini backend) — Spring AI 2.0
    // renamed/restructured the old vertex-ai-gemini starter into a unified
    // "Google GenAI" module (spring-ai-starter-model-google-genai) that
    // supports both the Gemini Developer API and Vertex AI as backends;
    // see GoogleGenAiAutoConfiguration (formerly VertexAiGeminiAutoConfiguration).
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai")

    // GitHub Models provider (models.github.ai) — an officially supported,
    // OpenAI-compatible inference API authenticated with a GitHub PAT.
    // Reuses Spring AI's generic OpenAI-API-compatible ChatModel pointed at
    // a custom base-url; see GitHubModelsAutoConfiguration. (Deliberately
    // NOT the unofficial/ToS-risky route of proxying GitHub Copilot's own
    // internal completions endpoint.)
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // Knowledge graph integration: plain Neo4j Java driver, mirroring
    // this project's hand-rolled-port style rather than Spring Data Neo4j.
    implementation("org.neo4j.driver:neo4j-java-driver:6.2.0")

    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4.1 modularized MockMvc test support out of spring-boot-test-autoconfigure.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:neo4j")
    testImplementation("org.assertj:assertj-core:3.26.3")
    // Fabric8's in-process mock Kubernetes API server, for verifying Job specs
    // submitted by KubernetesSandboxJobDispatcher without a real cluster.
    testImplementation("io.fabric8:kubernetes-junit-jupiter:7.3.1")
    testImplementation("io.fabric8:kubernetes-server-mock:7.3.1")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // LlmEnabledWiringTest exercises the Vertex AI-backed ChatModel bean
    // with agentic.llm.enabled=true and no apiKey, so com.google.genai.Client
    // resolves Application Default Credentials while constructing the
    // client — even though the test never makes an actual API call. That
    // resolution only needs a *loadable* credentials file, not a working
    // one, so point it at a throwaway, non-functional test fixture
    // (src/test/resources/fake-gcp-service-account.json) rather than
    // relying on a real `gcloud auth application-default login` having
    // been run on the machine running the build (never true on a clean
    // CI runner).
    if (System.getenv("GOOGLE_APPLICATION_CREDENTIALS").isNullOrBlank()) {
        environment("GOOGLE_APPLICATION_CREDENTIALS", "${projectDir}/src/test/resources/fake-gcp-service-account.json")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
