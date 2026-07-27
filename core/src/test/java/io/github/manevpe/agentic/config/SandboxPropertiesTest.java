package io.github.manevpe.agentic.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SandboxPropertiesTest {

    @Test
    void widensActiveDeadlineWhenTooCloseToCommandTimeout() {
        SandboxProperties properties = new SandboxProperties(
                true, "default", null, 900, 1800, false, null);

        // 900s deadline would let Kubernetes kill the pod mid-command
        // (command-timeout-seconds is 1800s), so it must be widened.
        assertThat(properties.workspaceActiveDeadlineSeconds()).isEqualTo(2400);
    }

    @Test
    void keepsExplicitActiveDeadlineWhenAlreadySafelyAboveCommandTimeout() {
        SandboxProperties properties = new SandboxProperties(
                true, "default", null, 3600, 1800, false, null);

        assertThat(properties.workspaceActiveDeadlineSeconds()).isEqualTo(3600);
    }

    @Test
    void defaultsToGenerousDeadlineWhenUnset() {
        SandboxProperties properties = new SandboxProperties(
                true, "default", null, 0, 1800, false, null);

        assertThat(properties.workspaceActiveDeadlineSeconds()).isEqualTo(7200);
    }
}
