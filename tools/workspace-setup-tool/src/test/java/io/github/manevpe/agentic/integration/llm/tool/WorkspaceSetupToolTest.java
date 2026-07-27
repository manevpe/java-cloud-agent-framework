package io.github.manevpe.agentic.integration.llm.tool;

import io.github.manevpe.agentic.integration.SandboxWorkspaceClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceSetupToolTest {

    private final FakeSandboxWorkspaceClient fakeClient = new FakeSandboxWorkspaceClient();
    private final WorkspaceSetupTool tool = new WorkspaceSetupTool(fakeClient);

    @Test
    void gitCloneDelegatesToWorkspaceClientAndReturnsWorkspaceId() {
        String workspaceId = tool.gitClone("acme/example-service", "main");

        assertThat(fakeClient.openedRepositories).containsEntry(workspaceId, "acme/example-service");
    }

    @Test
    void listReadAndSearchDelegateToWorkspaceClient() {
        String workspaceId = tool.gitClone("acme/example-service", null);

        assertThat(tool.listWorkspaceFiles(workspaceId, "src", 2)).contains("src/Main.java");
        assertThat(tool.readWorkspaceFile(workspaceId, "src/Main.java")).isEqualTo("content of src/Main.java");
        assertThat(tool.searchWorkspace(workspaceId, "TODO")).contains("src/Main.java:1: TODO");
    }

    @Test
    void closeAllOpenedInCurrentCallClosesEveryWorkspaceOpenedOnThisThreadThenForgetsThem() {
        String first = tool.gitClone("acme/service-a", null);
        String second = tool.gitClone("acme/service-b", null);

        tool.closeAllOpenedInCurrentCall();

        assertThat(fakeClient.closedWorkspaceIds).containsExactlyInAnyOrder(first, second);

        // A second call with nothing newly opened should close nothing further.
        tool.closeAllOpenedInCurrentCall();
        assertThat(fakeClient.closedWorkspaceIds).hasSize(2);
    }

    @Test
    void closeAllOpenedInCurrentCallIsThreadConfinedAcrossConcurrentPlanningTurns() throws InterruptedException {
        String[] otherThreadWorkspaceId = new String[1];
        Thread other = new Thread(() -> otherThreadWorkspaceId[0] = tool.gitClone("acme/other-thread-repo", null));
        other.start();
        other.join();

        String thisThreadWorkspaceId = tool.gitClone("acme/this-thread-repo", null);
        tool.closeAllOpenedInCurrentCall();

        // Only the workspace opened on *this* thread should have been closed —
        // the other thread's workspace, tracked in its own ThreadLocal, must be untouched.
        assertThat(fakeClient.closedWorkspaceIds).containsExactly(thisThreadWorkspaceId);
        assertThat(fakeClient.closedWorkspaceIds).doesNotContain(otherThreadWorkspaceId[0]);
    }

    private static class FakeSandboxWorkspaceClient implements SandboxWorkspaceClient {

        private final Map<String, String> openedRepositories = new ConcurrentHashMap<>();
        private final List<String> closedWorkspaceIds = new ArrayList<>();
        private int counter = 0;

        @Override
        public synchronized WorkspaceHandle open(String repository, String ref) {
            String workspaceId = "workspace-" + (++counter);
            openedRepositories.put(workspaceId, repository);
            return new WorkspaceHandle(workspaceId, repository);
        }

        @Override
        public List<String> listFiles(String workspaceId, String directory, Integer maxDepth) {
            return List.of("src/Main.java");
        }

        @Override
        public String readFile(String workspaceId, String path) {
            return "content of " + path;
        }

        @Override
        public List<String> search(String workspaceId, String pattern) {
            return List.of("src/Main.java:1: " + pattern);
        }

        @Override
        public synchronized void close(String workspaceId) {
            closedWorkspaceIds.add(workspaceId);
        }

        @Override
        public void writeFile(String workspaceId, String path, String content) {
            // Not exercised by WorkspaceSetupTool (read-only tools); no-op.
        }

        @Override
        public SandboxWorkspaceClient.CommandResult runCommand(String workspaceId, String command) {
            return new SandboxWorkspaceClient.CommandResult(0, "");
        }

        @Override
        public String diff(String workspaceId) {
            return "";
        }
    }
}
