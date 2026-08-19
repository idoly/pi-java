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
import java.util.Objects;
import java.util.function.Consumer;

record PiProcessConfig(
        List<String> command,
        Path directory,
        Map<String, String> env,
        int commandCapacity,
        int subscriberCapacity,
        int maxLineBytes,
        Duration gracePeriod,
        Consumer<String> stderr
) {
    private static final System.Logger LOGGER =
            System.getLogger("io.github.idoly.pi.process");

    PiProcessConfig {
        command = List.copyOf(command);
        directory = Objects.requireNonNull(directory, "directory");
        env = Map.copyOf(Objects.requireNonNull(env, "env"));
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (commandCapacity < 1 || subscriberCapacity < 1 || maxLineBytes < 1) {
            throw new IllegalArgumentException("capacities must be positive");
        }
        if (gracePeriod == null || gracePeriod.isNegative()) {
            throw new IllegalArgumentException("gracePeriod must not be negative");
        }
        stderr = Objects.requireNonNull(stderr, "stderr");
    }

    static PiProcessConfig create(Path workspace, PiToolBridge toolBridge) {
        try {
            Path directory = requireDirectory(workspace, "Working directory");
            Path extension = toolBridge == null
                    ? null
                    : requireFile(toolBridge.extensionFile(), "Generated Tool extension");

            List<String> command = new ArrayList<>(isWindows()
                    ? List.of("cmd.exe", "/d", "/s", "/c", "pi")
                    : List.of("pi"));
            command.addAll(List.of(
                    "--mode", "rpc",
                    "--no-approve",
                    "--no-context-files",
                    "--no-extensions",
                    "--no-skills",
                    "--no-prompt-templates",
                    "--no-themes"
            ));
            if (extension != null) {
                command.add("--extension");
                command.add(extension.toString());
            }

            Map<String, String> env = new LinkedHashMap<>();
            if (toolBridge != null) {
                env.putAll(toolBridge.environment());
            }
            env.put("PI_SKIP_VERSION_CHECK", "1");

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
        } catch (IOException cause) {
            throw new PiException("Failed to prepare pi launch paths", cause);
        }
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

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
