package io.github.idoly.pi.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable launch and runtime configuration for one {@link PiClient}. */
public final class PiClientConfig {
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final Path workspace;
    private final List<String> command;
    private final Duration requestTimeout;
    private final PiResourcePolicy resourcePolicy;
    private final String provider;
    private final String model;
    private final Map<String, String> environment;

    private PiClientConfig(Builder builder) {
        workspace = builder.workspace;
        command = List.copyOf(builder.command);
        requestTimeout = builder.requestTimeout;
        resourcePolicy = builder.resourcePolicy;
        provider = builder.provider;
        model = builder.model;
        environment = Map.copyOf(builder.environment);
    }

    /** Creates a builder whose defaults preserve the isolated behavior of {@link PiClient#open(Path, Object[])}. */
    public static Builder builder(Path workspace) {
        return new Builder(workspace);
    }

    public Path workspace() {
        return workspace;
    }

    /**
     * Returns the command prefix used to launch pi, or an empty list for the platform default.
     * The RPC mode and configured options are appended by pi-java.
     */
    public List<String> command() {
        return command;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public PiResourcePolicy resourcePolicy() {
        return resourcePolicy;
    }

    public Optional<String> provider() {
        return Optional.ofNullable(provider);
    }

    public Optional<String> model() {
        return Optional.ofNullable(model);
    }

    public Map<String, String> environment() {
        return environment;
    }

    /** Builds one immutable client configuration. */
    public static final class Builder {
        private final Path workspace;
        private List<String> command = List.of();
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private PiResourcePolicy resourcePolicy = PiResourcePolicy.ISOLATED;
        private String provider;
        private String model;
        private final Map<String, String> environment = new LinkedHashMap<>();

        private Builder(Path workspace) {
            this.workspace = Objects.requireNonNull(workspace, "workspace");
        }

        /** Sets a custom command prefix, for example an absolute pi executable path. */
        public Builder command(List<String> command) {
            Objects.requireNonNull(command, "command");
            if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("command must contain non-blank arguments");
            }
            this.command = List.copyOf(command);
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
            return this;
        }

        public Builder resourcePolicy(PiResourcePolicy resourcePolicy) {
            this.resourcePolicy = Objects.requireNonNull(resourcePolicy, "resourcePolicy");
            return this;
        }

        public Builder provider(String provider) {
            this.provider = requireNonBlank(provider, "provider");
            return this;
        }

        public Builder model(String model) {
            this.model = requireNonBlank(model, "model");
            return this;
        }

        /** Adds or replaces one environment variable inherited by the pi process. */
        public Builder environment(String name, String value) {
            environment.put(requireNonBlank(name, "environment name"),
                    Objects.requireNonNull(value, "environment value"));
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            Objects.requireNonNull(environment, "environment");
            environment.forEach(this::environment);
            return this;
        }

        public PiClientConfig build() {
            return new PiClientConfig(this);
        }
    }

    private static Duration requirePositive(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return duration;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
