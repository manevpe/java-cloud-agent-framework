package io.github.manevpe.agentic.integration.kubernetes;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.github.manevpe.agentic.config.GitHubProperties;
import io.github.manevpe.agentic.config.SandboxProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the workspace {@code Pod} spec {@link KubernetesSandboxWorkspaceClient}
 * builds, at the same testing depth {@code KubernetesSandboxJobDispatcherTest}
 * uses for the build/test Job: a Mock Server checks the submitted spec is
 * correct, without exercising genuine pod-ready-waiting or {@code exec}
 * (which would require a real kubelet) — see {@code buildWorkspacePod}'s
 * Javadoc and ADR-0005 for why that gap is deliberately accepted here too.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesSandboxWorkspaceClientTest {

    private KubernetesClient kubernetesClient;

    private static final GitHubProperties GITHUB_PROPERTIES = new GitHubProperties(
            true, "test-token", null, null, null, null);

    @Test
    void buildsWorkspacePodWithExpectedSpec() {
        SandboxProperties properties = new SandboxProperties(
                true, "agentic-sandboxes", "ghcr.io/example/sandbox-workspace:1.2.3", 3600, 1800, false, null);
        KubernetesSandboxWorkspaceClient client =
                new KubernetesSandboxWorkspaceClient(kubernetesClient, properties, GITHUB_PROPERTIES);

        Pod pod = client.buildWorkspacePod();

        assertThat(pod.getMetadata().getNamespace()).isEqualTo("agentic-sandboxes");
        assertThat(pod.getMetadata().getGenerateName()).isEqualTo("agentic-workspace-");
        assertThat(pod.getMetadata().getLabels())
                .containsEntry("app.kubernetes.io/managed-by", "java-cloud-agent-framework")
                .containsEntry("agentic.io/purpose", "repo-workspace");
        assertThat(pod.getSpec().getActiveDeadlineSeconds()).isEqualTo(3600L);
        assertThat(pod.getSpec().getRestartPolicy()).isEqualTo("Never");

        assertThat(pod.getSpec().getContainers()).hasSize(1);
        var container = pod.getSpec().getContainers().get(0);
        assertThat(container.getName()).isEqualTo("workspace");
        assertThat(container.getImage()).isEqualTo("ghcr.io/example/sandbox-workspace:1.2.3");
        assertThat(container.getCommand()).containsExactly("sh", "-c", "sleep 3600");
    }

    @Test
    void addsPrivilegedDockerInDockerSidecarWhenEnabled() {
        SandboxProperties properties = new SandboxProperties(
                true, "agentic-sandboxes", "ghcr.io/example/sandbox-workspace:1.2.3", 3600, 1800,
                true, "docker:27-dind");
        KubernetesSandboxWorkspaceClient client =
                new KubernetesSandboxWorkspaceClient(kubernetesClient, properties, GITHUB_PROPERTIES);

        Pod pod = client.buildWorkspacePod();

        assertThat(pod.getSpec().getContainers()).hasSize(2);
        var workspaceContainer = pod.getSpec().getContainers().get(0);
        assertThat(workspaceContainer.getName()).isEqualTo("workspace");
        assertThat(workspaceContainer.getEnv())
                .anySatisfy(env -> {
                    assertThat(env.getName()).isEqualTo("DOCKER_HOST");
                    assertThat(env.getValue()).isEqualTo("tcp://localhost:2375");
                });

        var dindContainer = pod.getSpec().getContainers().get(1);
        assertThat(dindContainer.getName()).isEqualTo("docker-daemon");
        assertThat(dindContainer.getImage()).isEqualTo("docker:27-dind");
        assertThat(dindContainer.getSecurityContext().getPrivileged()).isTrue();
        assertThat(dindContainer.getEnv())
                .anySatisfy(env -> {
                    assertThat(env.getName()).isEqualTo("DOCKER_TLS_CERTDIR");
                    assertThat(env.getValue()).isEqualTo("");
                });
    }
}
