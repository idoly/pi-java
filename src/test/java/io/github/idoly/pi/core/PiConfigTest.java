package io.github.idoly.pi.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiConfigTest {
    @TempDir Path temp;

    @Test
    void usesIsolatedDefaults() throws Exception {
        Path workspace = Path.of(".");

        PiConfig config = PiConfig.builder().build();

        assertEquals(".", config.workspace());
        assertEquals(workspace.toRealPath(), config.processConfig().directory());
        assertEquals(PiConfig.ResourcePolicy.ISOLATED, config.resourcePolicy());
        assertTrue(config.command().isEmpty());
        assertEquals(15, config.requestTimeout());
        assertFalse(config.noSession());
        assertFalse(config.processConfig().command().contains("--no-session"));
    }

    @Test
    void validatesStartupOptionsAndBudgets() {
        PiConfig config = PiConfig.builder()
                .args(List.of("--no-session", "--tools", "read,grep"))
                .retainMessages(true).eventBufferLimit(4096).rpcLineLimit(8192).build();
        assertEquals(List.of("--no-session", "--tools", "read,grep"), config.args());
        assertTrue(config.retainMessages());
        assertThrows(IllegalArgumentException.class, () -> PiConfig.builder().args(List.of("--mode", "json")));
        assertThrows(IllegalArgumentException.class, () -> PiConfig.builder().eventBufferCapacity(0));
        assertThrows(IllegalArgumentException.class, () -> PiConfig.builder().eventBufferLimit(0));
        assertThrows(IllegalArgumentException.class, () -> PiConfig.builder().rpcLineLimit(0));
    }

    @Test
    void rawArgsFollowPiOptionValueRules() {
        List<String> args = List.of("--system-prompt", "--help", "--append-system-prompt", "",
                "--name", "-v", "--extension-flag=--mode");
        assertEquals(args, PiConfig.builder().args(args).build().args());
        assertThrows(IllegalArgumentException.class,
                () -> PiConfig.builder().args(List.of("--system-prompt", "value", "--help")));
        assertThrows(IllegalArgumentException.class, () -> PiConfig.builder().args(List.of("--resume")));
    }

    @Test
    void rejectsEmptyCommandAndNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
                PiConfig.builder("workspace").command(List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                PiConfig.builder("workspace").requestTimeout(0));
        assertThrows(IllegalArgumentException.class, () -> PiConfig.builder().requestTimeout(-1));
    }

    @Test void buildsIsolatedRpcCommand() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        var config = PiConfig.builder(workspace.toString()).build().processConfig();
        assertTrue(config.command().contains("pi"));
        assertOption(config.command(), "--mode", "rpc");
        assertTrue(config.command().containsAll(List.of("--no-approve", "--no-context-files",
                "--no-extensions", "--no-skills", "--no-prompt-templates", "--no-themes")));
        assertFalse(config.command().contains("--extension"));
        assertFalse(config.command().contains("--tools"));
        assertEquals("1", config.env().get("PI_SKIP_VERSION_CHECK"));
    }

    @Test void mapsExplicitSettings() throws Exception {
        PiConfig options = PiConfig.builder(temp.toString())
                .command(List.of("custom-pi")).resourcePolicy(PiConfig.ResourcePolicy.TRUST_PROJECT)
                .noSession(true).requestTimeout(30)
                .provider("openai").model("test-model").environment("PI_TEST_ENV", "configured").build();
        var config = options.processConfig();
        assertTrue(options.noSession());
        assertEquals(30, options.requestTimeout());
        assertTrue(config.command().contains("--no-session"));
        assertEquals(temp.toRealPath(), config.directory());
        assertEquals("custom-pi", config.command().get(0));
        assertTrue(config.command().contains("--approve"));
        assertOption(config.command(), "--provider", "openai");
        assertOption(config.command(), "--model", "test-model");
        assertEquals("configured", config.env().get("PI_TEST_ENV"));
    }

    @Test void globalPolicyDoesNotApproveProject() {
        var config = PiConfig.builder(temp.toString())
                .resourcePolicy(PiConfig.ResourcePolicy.GLOBAL_ONLY).build().processConfig();
        assertTrue(config.command().contains("--no-approve"));
        assertFalse(config.command().contains("--no-extensions"));
        assertFalse(config.command().contains("--approve"));
    }

    @Test void rejectsMissingWorkspace() {
        assertThrows(PiException.class, () -> PiClient.open(temp.resolve("missing").toString()));
    }

    @Test void rejectsFileAsWorkspace() throws Exception {
        Path file = Files.writeString(temp.resolve("workspace.txt"), "not a directory");
        assertThrows(PiException.class, () -> PiClient.open(file.toString()));
    }

    @Test void explicitlyLoadsPiCodemodeAndSearchUnderIsolatedPolicy() {
        var config = PiConfig.builder(temp.toString())
                .codemode(true).toolSearch(true).build().processConfig();
        assertTrue(config.command().contains("--no-extensions"));
        assertTrue(config.command().contains("builtin:codemode"));
        assertTrue(config.command().contains("builtin:tool-search"));
        assertOption(config.command(), "--tools", "+codemode,+tool_search");
    }

    @Test void separatesLaunchCommandFromExplicitPiOptions() {
        var config = PiConfig.builder(temp.toString())
                .command(List.of("node", "custom-cli.js"))
                .noSession(true).tools("read", "grep").appendSystemPrompt("rules.txt")
                .args(List.of("--extension-flag", "value"))
                .rpcLineLimit(32 * 1024 * 1024).eventBufferCapacity(2048).eventBufferLimit(4096).build().processConfig();
        assertEquals(List.of("node", "custom-cli.js"), config.command().subList(0, 2));
        assertTrue(config.command().contains("--no-session"));
        assertOption(config.command(), "--tools", "read,grep");
        assertOption(config.command(), "--append-system-prompt", "rules.txt");
        assertOption(config.command(), "--extension-flag", "value");
        assertEquals(32 * 1024 * 1024, config.maxLineBytes());
        assertEquals(2048, config.eventBufferCapacity());
        assertEquals(4096, config.eventBufferLimit());
    }

    private static void assertOption(List<String> command, String name, String value) {
        int index = command.indexOf(name);
        assertTrue(index >= 0 && index + 1 < command.size());
        assertEquals(value, command.get(index + 1));
    }
}
