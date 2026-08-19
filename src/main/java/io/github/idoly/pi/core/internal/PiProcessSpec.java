package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record PiProcessSpec(
        List<String> command,
        Path directory,
        Map<String, String> env
) {
    private static final System.Logger LOGGER =
            System.getLogger("io.github.idoly.pi.process");

    PiProcessSpec {
        command = List.copyOf(command);
        env = Map.copyOf(env);
    }

    static PiProcessSpec create(Path workspace) {
        return create(workspace, null);
    }

    static PiProcessSpec create(Path workspace, PiToolBridge toolBridge) {
        try {
            workspace = requireDirectory(workspace, "Working directory");

            Path extensionFile = toolBridge == null
                    ? null
                    : requireFile(toolBridge.extensionFile(), "Generated Tool extension");

            List<String> command = new ArrayList<>();
            command.addAll(isWindows()
                    ? List.of("cmd.exe", "/d", "/s", "/c", "pi")
                    : List.of("pi"));
            command.addAll(List.of(
                    "--mode", "rpc",
                    "--no-approve",
                    "--no-context-files",
                    "--no-extensions", "--no-skills", "--no-prompt-templates", "--no-themes"
            ));
            if (extensionFile != null) {
                appendOption(command, "--extension", extensionFile.toString());
            }
            Map<String, String> env = new LinkedHashMap<>();
            if (toolBridge != null) {
                env.putAll(toolBridge.environment());
            }
            env.put("PI_SKIP_VERSION_CHECK", "1");
            return new PiProcessSpec(command, workspace, env);
        } catch (IOException cause) {
            throw new PiException("Failed to prepare pi launch paths", cause);
        }
    }

    PiProcessConfig config() {
        return new PiProcessConfig(
                command,
                directory,
                env,
                256,
                1024,
                16 * 1024 * 1024,
                Duration.ofSeconds(3),
                line -> LOGGER.log(System.Logger.Level.DEBUG, line)
        );
    }

    private static Path requireFile(Path path, String name) throws IOException {
        Path real = path.toRealPath();
        if (!Files.isRegularFile(real)) {
            throw new PiException(name + " must be a regular file: " + real);
        }
        return real;
    }

    private static Path requireDirectory(Path path, String name) throws IOException {
        Path real = path.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new PiException(name + " must be a directory: " + real);
        }
        return real;
    }

    private static void appendOption(
            List<String> command,
            String optionName,
            String optionValue
    ) {
        command.add(optionName);
        command.add(optionValue);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
