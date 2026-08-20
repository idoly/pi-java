package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;
import io.github.idoly.pi.core.PiClientConfig;
import io.github.idoly.pi.core.PiResourcePolicy;
import io.github.idoly.pi.core.tool.PiTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiProcessConfigTest {
    @TempDir
    Path temp;

    @Test
    void buildsFixedCommandFromHostPiConfiguration() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));

        PiProcessConfig config = PiProcessConfig.create(defaultConfig(workspace), null);

        assertTrue(config.command().contains("pi"));
        assertContainsSequence(config.command(), "--mode", "rpc");
        assertFalse(config.command().contains("--tools"));
        assertTrue(config.command().containsAll(List.of(
                "--no-approve", "--no-context-files", "--no-extensions",
                "--no-skills", "--no-prompt-templates", "--no-themes")));
        assertFalse(config.command().contains("--provider"));
        assertFalse(config.command().contains("--model"));
        assertFalse(config.command().contains("--session-dir"));
        assertEquals("1", config.env().get("PI_SKIP_VERSION_CHECK"));
    }

    @Test
    void mapsExplicitClientConfigurationToPiArguments() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("configured-workspace"));
        PiClientConfig clientConfig = PiClientConfig.builder(workspace)
                .command(List.of("custom-pi"))
                .resourcePolicy(PiResourcePolicy.TRUST_PROJECT)
                .provider("openai")
                .model("gpt-5.6")
                .environment("PI_TEST_ENV", "configured")
                .build();

        PiProcessConfig config = PiProcessConfig.create(clientConfig, null);

        assertEquals("custom-pi", config.command().get(0));
        assertTrue(config.command().contains("--approve"));
        assertContainsSequence(config.command(), "--provider", "openai");
        assertContainsSequence(config.command(), "--model", "gpt-5.6");
        assertEquals("configured", config.env().get("PI_TEST_ENV"));
        assertEquals("1", config.env().get("PI_SKIP_VERSION_CHECK"));
    }

    @Test
    void globalOnlyPolicyKeepsGlobalResourcesWithoutTrustingProject() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("global-workspace"));
        PiClientConfig clientConfig = PiClientConfig.builder(workspace)
                .resourcePolicy(PiResourcePolicy.GLOBAL_ONLY)
                .build();

        PiProcessConfig config = PiProcessConfig.create(clientConfig, null);

        assertTrue(config.command().contains("--no-approve"));
        assertFalse(config.command().contains("--no-extensions"));
        assertFalse(config.command().contains("--approve"));
    }

    @Test
    void rejectsMissingWorkspace() {
        assertThrows(PiException.class,
                () -> PiProcessConfig.create(
                        defaultConfig(temp.resolve("missing-workspace")), null));
    }

    @Test
    void addsOnlyFrameworkGeneratedToolExtension() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("tool-workspace"));

        try (PiToolBridge toolBridge = PiToolBridge.open(List.of(new LaunchTool()))) {
            PiProcessConfig config = PiProcessConfig.create(
                    defaultConfig(workspace), toolBridge);

            assertContainsSequence(config.command(), "--extension",
                    toolBridge.extensionFile().toRealPath().toString());
            assertFalse(config.command().contains("--tools"));
            assertFalse(config.command().contains("--skill"));
            assertFalse(config.command().contains("--prompt-template"));
            assertTrue(config.env().containsKey("PI_JAVA_TOOL_TOKEN"));
        }
    }

    private static PiClientConfig defaultConfig(Path workspace) {
        return PiClientConfig.builder(workspace).build();
    }

    static final class LaunchTool {
        @PiTool(name = "java_tool", description = "Test Java Tool")
        public String invoke() {
            return "ok";
        }
    }

    private static void assertContainsSequence(List<String> values, String first, String second) {
        int index = values.indexOf(first);
        assertTrue(index >= 0 && index + 1 < values.size());
        assertEquals(second, values.get(index + 1));
    }
}
