package io.github.idoly.pi.core.internal;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    PiProcessConfig {
        command = List.copyOf(command);
        env = env == null ? Map.of() : Map.copyOf(env);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (commandCapacity < 1 || subscriberCapacity < 1 || maxLineBytes < 1) {
            throw new IllegalArgumentException("capacities must be positive");
        }
        if (gracePeriod == null || gracePeriod.isNegative()) {
            throw new IllegalArgumentException("gracePeriod must not be negative");
        }
        stderr = stderr == null ? ignored -> { } : stderr;
    }
}
