package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;
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

class PiProcessSpecTest {
    @TempDir
    Path temp;

    @Test
    void buildsFixedCommandFromHostPiConfiguration() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));

        PiProcessSpec processSpec = PiProcessSpec.create(workspace);

        assertTrue(processSpec.command().contains("pi"));
        assertContainsSequence(processSpec.command(), "--mode", "rpc");
        assertFalse(processSpec.command().contains("--tools"));
        assertTrue(processSpec.command().containsAll(List.of(
                "--no-approve", "--no-context-files", "--no-extensions",
                "--no-skills", "--no-prompt-templates", "--no-themes")));
        assertFalse(processSpec.command().contains("--provider"));
        assertFalse(processSpec.command().contains("--model"));
        assertFalse(processSpec.command().contains("--session-dir"));
        assertEquals("1", processSpec.env().get("PI_SKIP_VERSION_CHECK"));
    }

    @Test
    void rejectsMissingWorkspace() {
        assertThrows(PiException.class,
                () -> PiProcessSpec.create(temp.resolve("missing-workspace")));
    }

    @Test
    void addsOnlyFrameworkGeneratedToolExtension() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("tool-workspace"));

        try (PiToolBridge toolBridge = PiToolBridge.open(List.of(new LaunchTool()))) {
            PiProcessSpec processSpec = PiProcessSpec.create(workspace, toolBridge);

            assertContainsSequence(processSpec.command(), "--extension",
                    toolBridge.extensionFile().toRealPath().toString());
            assertFalse(processSpec.command().contains("--tools"));
            assertFalse(processSpec.command().contains("--skill"));
            assertFalse(processSpec.command().contains("--prompt-template"));
            assertTrue(processSpec.env().containsKey("PI_JAVA_TOOL_TOKEN"));
        }
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
