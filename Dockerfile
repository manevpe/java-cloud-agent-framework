# syntax=docker/dockerfile:1

# --- Build stage -------------------------------------------------------
# Builds every jar (the core Spring Boot app + every out-of-the-box
# agent/tool module — one Gradle module/jar each, see ADR-0014) with the
# project's own Gradle wrapper, so the image is reproducible without
# requiring Gradle/JDK on the host building it.
#
# Deliberately copies the whole build context in one layer rather than
# listing each module's build.gradle.kts individually for a separate
# dependency-warmup layer: with 13+ modules now (core + 5 tools + 7
# agents), a hand-maintained COPY list silently goes stale the moment a
# module is added/renamed/removed (as happened here across ADR-0014's
# module split) — see .dockerignore for what's excluded (build outputs,
# .git, docs, etc.), which keeps this layer's cache-busting to genuine
# source/build-file changes only.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace
COPY . .

# Every out-of-the-box tool/agent module's jar task, plus the core app's
# bootJar. Keep this list in sync with settings.gradle.kts's `include(...)`
# (minus agents-integration-tests, which is test-only and never shipped).
RUN ./gradlew --no-daemon \
    :core:bootJar \
    :tools:tool-support:jar \
    :tools:workspace-setup-tool:jar \
    :tools:file-read-tool:jar \
    :tools:file-edit-tool:jar \
    :tools:ask-human-tool:jar \
    :tools:github-api-tool:jar \
    :tools:http-request-tool:jar \
    :agents:planning-agent:jar \
    :agents:conversational-planning-agent:jar \
    :agents:coding-agent:jar \
    :agents:jira-updater-agent:jar \
    :agents:slack-gate-agent:jar \
    :agents:pr-comment-gate-agent:jar \
    :agents:conversation-resume-gate-agent:jar

# --- Bare runtime stage --------------------------------------------------
# JRE-only, non-root, minimal image with zero built-in agents — the
# "bring your own agents/workflows/tools" deployment target (ADR-0011).
# Layered jar extraction (rather than a single fat jar) keeps unchanged
# dependency layers cacheable across rebuilds that only touch application
# code.
FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app

# kubectl is needed by KubernetesSandboxWorkspaceClient#exec, which shells
# out to it for `pods/exec` instead of using fabric8's own exec (see that
# class's Javadoc for the confirmed fabric8 WebSocket-handshake bug this
# works around).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && ARCH="$(dpkg --print-architecture)" \
    && curl -fsSLo /usr/local/bin/kubectl "https://dl.k8s.io/release/$(curl -fsSL https://dl.k8s.io/release/stable.txt)/bin/linux/${ARCH}/kubectl" \
    && chmod +x /usr/local/bin/kubectl \
    && apt-get purge -y curl \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 1001 agentic \
    && useradd --uid 1001 --gid agentic --shell /bin/bash --create-home agentic

COPY --from=build /workspace/core/build/libs/*.jar /app/application.jar
RUN java -Djarmode=tools -jar /app/application.jar extract --destination /app/extracted \
    && rm /app/application.jar

# Domain knowledge files (e.g. ./knowledge/dnd) referenced by workflow
# nodes' own `knowledgeSources: [{type: directory, path: ./knowledge/...}]`
# config are relative to the app's working directory at runtime
# (/app/extracted below), so bake them in here. Unlike workflow YAML
# files, these aren't ConfigMap-mounted: they're plain text committed to
# this repo, so a rebuild-on-change tradeoff is acceptable for now.
COPY --from=build /workspace/knowledge /app/extracted/knowledge
RUN chown -R agentic:agentic /app/extracted/knowledge

# Workflow definition YAML files are NOT baked into the image — they're
# provided at deploy time via a Kubernetes ConfigMap volume mount (see
# deploy/helm/.../templates/configmap.yaml + deployment.yaml), so a workflow
# can be added/changed with `helm upgrade` alone, no image rebuild. This
# absolute default path is where that ConfigMap is mounted; override via
# AGENTIC_WORKFLOWS_DIRECTORY for other environments (e.g. local docker run
# with a bind-mounted directory, or the repo's own workflows/examples).
ENV AGENTIC_WORKFLOWS_DIRECTORY=/config/workflows

# Empty by default (this is the bare image — no built-in agents) but
# always present, so `agentic.plugins.directory` has somewhere to point
# at out of the box: mount a volume here (see the Helm chart's
# `.Values.volumes`/`.Values.volumeMounts` escape hatch) or derive a
# custom image that COPYs plugin jars into it, exactly like the
# `-with-default-modules` stage below does.
ENV AGENTIC_PLUGINS_DIRECTORY=/opt/agentic/plugins
RUN mkdir -p /opt/agentic/plugins && chown agentic:agentic /opt/agentic/plugins

USER agentic:agentic
EXPOSE 8080

WORKDIR /app/extracted
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseContainerSupport", \
            "-jar", "application.jar"]

# --- Convenience "with-default-modules" runtime stage --------------------
# Same bare image, plus every out-of-the-box agent/tool jar pre-copied
# into the plugins directory — build with `--target runtime-with-default-modules`
# (or `docker buildx bake`/CI tags it as the `-with-default-modules` image
# variant). A team that wants a fully custom agent set keeps using the
# plain `runtime` target/image above instead.
FROM runtime AS runtime-with-default-modules
USER root
COPY --from=build /workspace/tools/*/build/libs/*.jar /opt/agentic/plugins/
COPY --from=build /workspace/agents/*/build/libs/*.jar /opt/agentic/plugins/
RUN chown -R agentic:agentic /opt/agentic/plugins
USER agentic:agentic

