package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;
import io.github.idoly.pi.core.PiClientConfig;
import io.github.idoly.pi.core.PiResourcePolicy;

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
    private static final Duration SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(3);
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

    static PiProcessConfig create(PiClientConfig clientConfig, PiToolBridge toolBridge) {
        Objects.requireNonNull(clientConfig, "clientConfig");
        try {
            Path directory = requireDirectory(clientConfig.workspace(), "Working directory");
            Path extension = toolBridge == null
                    ? null
                    : requireFile(toolBridge.extensionFile(), "Generated Tool extension");

            List<String> command = new ArrayList<>(clientConfig.command().isEmpty()
                    ? defaultCommand() : clientConfig.command());
            command.addAll(List.of("--mode", "rpc"));
            addResourcePolicy(command, clientConfig.resourcePolicy());
            clientConfig.provider().ifPresent(value -> addOption(command, "--provider", value));
            clientConfig.model().ifPresent(value -> addOption(command, "--model", value));
            if (extension != null) {
                addOption(command, "--extension", extension.toString());
            }

            Map<String, String> env = new LinkedHashMap<>(clientConfig.environment());
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
                    SHUTDOWN_GRACE_PERIOD,
                    line -> LOGGER.log(System.Logger.Level.DEBUG, line)
            );
        } catch (IOException cause) {
            throw new PiException("Failed to prepare pi launch paths", cause);
        }
    }

    private static List<String> defaultCommand() {
        return isWindows()
                ? List.of("cmd.exe", "/d", "/s", "/c", "pi")
                : List.of("pi");
    }

    private static void addResourcePolicy(
            List<String> command,
            PiResourcePolicy resourcePolicy
    ) {
        switch (resourcePolicy) {
            case ISOLATED -> command.addAll(List.of(
                    "--no-approve",
                    "--no-context-files",
                    "--no-extensions",
                    "--no-skills",
                    "--no-prompt-templates",
                    "--no-themes"
            ));
            case GLOBAL_ONLY -> command.add("--no-approve");
            case TRUST_PROJECT -> command.add("--approve");
        }
    }

    private static void addOption(List<String> command, String name, String value) {
        command.add(name);
        command.add(value);
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
