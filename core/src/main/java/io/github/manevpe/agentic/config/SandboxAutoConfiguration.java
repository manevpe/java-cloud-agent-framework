package io.github.manevpe.agentic.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the real Kubernetes client bean, only when sandbox execution is
 * actually enabled ({@code agentic.sandbox.enabled=true}) — e.g. when this
 * service is running inside a cluster with the RBAC needed to create and
 * exec into workspace Pods. When disabled (the default), no {@link
 * KubernetesClient} bean exists and {@code LoggingSandboxWorkspaceClient}
 * is used instead of {@code KubernetesSandboxWorkspaceClient}.
 *
 * <p>This bean's default in-cluster auto-detection ({@code
 * KubernetesClientBuilder().build()}) is used as-is: it correctly
 * authenticates every fabric8 call {@code
 * KubernetesSandboxWorkspaceClient} still makes through it (pod
 * create/get/list/delete/watch). {@code pods/exec} is deliberately
 * <strong>not</strong> among those — see {@code
 * KubernetesSandboxWorkspaceClient#exec} for why exec is instead shelled
 * out to the real {@code kubectl} binary, working around a confirmed
 * fabric8 client bug where its {@code pods/exec} WebSocket handshake is
 * rejected with a bare 403 by the API server (reproduced on fabric8
 * 6.13.5 and 7.3.1, both the okhttp and vertx transports, and two
 * different Kubernetes versions) even though the same ServiceAccount
 * token works correctly for {@code pods/exec} via {@code kubectl}
 * directly.
 */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "agentic.sandbox", name = "enabled", havingValue = "true")
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
