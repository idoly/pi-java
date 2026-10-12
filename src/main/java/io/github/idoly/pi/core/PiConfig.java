package io.github.idoly.pi.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Immutable launch and runtime configuration for one {@link PiClient}. */
public final class PiConfig {
    /** Controls resource loading; it does not provide an operating-system sandbox. */
    public enum ResourcePolicy {
        ISOLATED(List.of("--no-approve", "--no-context-files", "--no-extensions", "--no-skills", "--no-prompt-templates", "--no-themes")),
        GLOBAL_ONLY(List.of("--no-approve")),
        TRUST_PROJECT(List.of("--approve"));

        private final List<String> arguments;

        ResourcePolicy(List<String> arguments) { this.arguments = arguments; }
    }

    private final String workspace;
    private final List<String> command;
    private final List<String> args;
    private final boolean noSession;
    private final boolean retainMessages;
    private final int eventBufferLimit;
    private final int eventBufferCapacity;
    private final int rpcLineLimit;
    private final Consumer<String> diagnostics;
    private final long requestTimeout;
    private final long activityTimeout;
    private final int commandBufferCapacity;
    private final long shutdownGracePeriod;
    private final ResourcePolicy resourcePolicy;
    private final String provider;
    private final String model;
    private final Map<String, String> environment;
    private final int activityResultLimit;
    private final boolean codemode;
    private final boolean toolSearch;

    private PiConfig(Builder builder) {
        workspace = builder.workspace;
        command = List.copyOf(builder.command);
        List<String> launchArgs = new ArrayList<>();
        builder.options.values().forEach(launchArgs::addAll);
        launchArgs.addAll(builder.args);
        args = List.copyOf(launchArgs);
        noSession = builder.options.containsKey("--no-session");
        retainMessages = builder.retainMessages;
        eventBufferLimit = builder.eventBufferLimit;
        eventBufferCapacity = builder.eventBufferCapacity;
        rpcLineLimit = builder.rpcLineLimit;
        diagnostics = builder.diagnostics;
        requestTimeout = builder.requestTimeout;
        activityTimeout = builder.activityTimeout;
        commandBufferCapacity = builder.commandBufferCapacity;
        shutdownGracePeriod = builder.shutdownGracePeriod;
        resourcePolicy = builder.resourcePolicy;
        provider = builder.optionValue("--provider");
        model = builder.optionValue("--model");
        environment = Map.copyOf(builder.environment);
        activityResultLimit = builder.activityResultLimit;
        codemode = builder.codemode;
        toolSearch = builder.toolSearch;
    }

    /** Creates a builder for the Java process's current working directory. */
    public static Builder builder() {
        return builder(".");
    }

    /** Creates a builder with the isolated defaults of {@link PiClient#open(String)}. */
    public static Builder builder(String workspace) {
        return new Builder(workspace);
    }

    public String workspace() {
        return workspace;
    }

    /**
     * Returns the command prefix used to launch pi, or an empty list for the platform default.
     * The RPC mode and configured options are appended by pi-java.
     */
    public List<String> command() {
        return command;
    }

    /** Additional Pi options from named methods and args; raw args are appended last. */
    public List<String> args() { return args; }
    public boolean noSession() { return noSession; }
    public boolean retainMessages() { return retainMessages; }
    public int eventBufferLimit() { return eventBufferLimit; }
    public int eventBufferCapacity() { return eventBufferCapacity; }
    public int rpcLineLimit() { return rpcLineLimit; }
    /** Optional diagnostic callback, null when disabled. */
    public Consumer<String> diagnostics() { return diagnostics; }

    /** RPC response timeout in seconds. */
    public long requestTimeout() {
        return requestTimeout;
    }

    /** Default Activity timeout in seconds. */
    public long activityTimeout() { return activityTimeout; }
    /** Maximum outbound requests waiting for pipe writes. */
    public int commandBufferCapacity() { return commandBufferCapacity; }
    /** Graceful process shutdown wait in seconds, before forced cleanup. */
    public long shutdownGracePeriod() { return shutdownGracePeriod; }

    public ResourcePolicy resourcePolicy() {
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

    public int activityResultLimit() { return activityResultLimit; }
    public boolean codemode() { return codemode; }
    public boolean toolSearch() { return toolSearch; }

    /** Resolves launch options once; PiProcess consumes the resulting immutable snapshot. */
    PiProcess.Config processConfig() {
        try {
            Path directory = Path.of(workspace).toRealPath();
            if (!Files.isDirectory(directory)) throw new PiException("Workspace must be a directory: " + directory);
            boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
            List<String> launch = new ArrayList<>(command.isEmpty()
                    ? windows ? List.of("cmd.exe", "/d", "/s", "/c", "pi") : List.of("pi") : command);
            launch.addAll(List.of("--mode", "rpc"));
            launch.addAll(resourcePolicy.arguments);
            List<String> addedTools = new ArrayList<>();
            if (codemode) { launch.addAll(List.of("--extension", "builtin:codemode")); addedTools.add("+codemode"); }
            if (toolSearch) { launch.addAll(List.of("--extension", "builtin:tool-search")); addedTools.add("+tool_search"); }
            if (!addedTools.isEmpty()) launch.addAll(List.of("--tools", String.join(",", addedTools)));
            launch.addAll(args);
            Map<String, String> env = new LinkedHashMap<>(environment);
            env.put("PI_SKIP_VERSION_CHECK", "1");
            return new PiProcess.Config(launch, directory, env, commandBufferCapacity, rpcLineLimit,
                    TimeUnit.SECONDS.toMillis(shutdownGracePeriod), diagnostics);
        } catch (IOException failure) { throw new PiException("Failed to prepare Pi launch paths", failure); }
    }

    /** Builds one immutable client configuration. */
    public static final class Builder {
        private static final Set<String> VALUE_OPTIONS = Set.of(
                "--provider", "--model", "--api-key", "--system-prompt", "--append-system-prompt", "--name", "-n",
                "--session", "--session-id", "--fork", "--session-dir", "--models", "--tools", "-t",
                "--exclude-tools", "-xt", "--thinking", "--extension", "-e", "--skill", "--prompt-template",
                "--theme", "--use-theme", "--tui-mode");
        private static final Set<String> RPC_MANAGED_OPTIONS = Set.of(
                "--", "--mode", "--print", "-p", "--help", "-h", "--version", "-v", "--export", "--list-models",
                "--resume", "-r");

        private final String workspace;
        private List<String> command = List.of();
        private List<String> args = List.of();
        private boolean retainMessages;
        private int eventBufferLimit = 8 * 1024 * 1024;
        private int eventBufferCapacity = 1024;
        private int rpcLineLimit = 16 * 1024 * 1024;
        private Consumer<String> diagnostics;
        private long requestTimeout = 15;
        private long activityTimeout = 600;
        private int commandBufferCapacity = 256;
        private long shutdownGracePeriod = 3;
        private ResourcePolicy resourcePolicy = ResourcePolicy.ISOLATED;
        private int activityResultLimit = 16 * 1024 * 1024;
        private boolean codemode;
        private boolean toolSearch;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private final Map<String, List<String>> options = new LinkedHashMap<>();

        private Builder(String workspace) {
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

        /** Raw Pi or extension options, appended after named settings. Pi validates their values. */
        public Builder args(List<String> args) {
            List<String> copy = List.copyOf(args);
            for (int i = 0; i < copy.size(); i++) {
                String option = copy.get(i);
                if (RPC_MANAGED_OPTIONS.contains(option)) {
                    throw new IllegalArgumentException(option + " is managed by the RPC SDK or requires interactive startup");
                }
                // Pi consumes the next token as a value for these options, even if it starts with '-'.
                if (VALUE_OPTIONS.contains(option)) i++;
                else if (option.startsWith("--") && !option.contains("=") && i + 1 < copy.size()
                        && !copy.get(i + 1).startsWith("-") && !copy.get(i + 1).startsWith("@")) i++;
            }
            this.args = copy;
            return this;
        }

        /** Pi --api-key runtime override; provider environment variables are also supported. */
        public Builder apiKey(String key) { return option("--api-key", key); }
        /** Pi --models comma-separated patterns, including provider IDs and globs. */
        public Builder models(String... patterns) { return option("--models", String.join(",", List.of(patterns))); }
        /** Pi --thinking level; model support and accepted levels are resolved by Pi. */
        public Builder thinking(String level) { return option("--thinking", level); }
        /** Pi --session file or ID. */
        public Builder session(String fileOrId) { return option("--session", fileOrId); }
        /** Pi --session-id exact project ID, creating a session if missing. */
        public Builder sessionId(String id) { return option("--session-id", id); }
        /** Pi --session-dir storage and lookup directory. */
        public Builder sessionDir(String directory) { return option("--session-dir", directory); }
        /** Pi --name session display name. */
        public Builder name(String name) { return option("--name", name); }
        /** Pi --fork source session; conflicts with other session selectors are validated by Pi. */
        public Builder fork(String fileOrId) { return option("--fork", fileOrId); }
        /** Pi --continue, selecting the most recent session in the current directory. */
        public Builder continueSession(boolean enabled) { return flag("--continue", enabled); }
        /** Pi --tools selection, including patterns and +name/-name changes to defaults. */
        public Builder tools(String... names) { return option("--tools", String.join(",", List.of(names))); }
        /** Pi --exclude-tools patterns. */
        public Builder excludeTools(String... names) { return option("--exclude-tools", String.join(",", List.of(names))); }
        /** Pi --no-tools disables all tools initially. */
        public Builder noTools(boolean enabled) { return flag("--no-tools", enabled); }
        /** Pi --no-builtin-tools keeps extension and custom tools. */
        public Builder noBuiltinTools(boolean enabled) { return flag("--no-builtin-tools", enabled); }
        /** Pi --system-prompt text or file. Empty text is passed through. */
        public Builder systemPrompt(String textOrFile) { return option("--system-prompt", textOrFile); }
        /** Repeated Pi --append-system-prompt text or files, in call order. */
        public Builder appendSystemPrompt(String... textOrFiles) { return repeated("--append-system-prompt", textOrFiles); }
        /** Repeated Pi --extension paths or builtin:name references. */
        public Builder extensions(String... paths) { return repeated("--extension", paths); }
        /** Repeated Pi --skill paths. */
        public Builder skills(String... paths) { return repeated("--skill", paths); }
        /** Repeated Pi --prompt-template paths. */
        public Builder promptTemplates(String... paths) { return repeated("--prompt-template", paths); }
        /** Repeated Pi --theme paths. */
        public Builder themes(String... paths) { return repeated("--theme", paths); }
        /** Pi --no-extensions; explicit extensions still load. */
        public Builder noExtensions(boolean enabled) { return flag("--no-extensions", enabled); }
        /** Pi --no-skills; explicit skills still load. */
        public Builder noSkills(boolean enabled) { return flag("--no-skills", enabled); }
        /** Pi --no-prompt-templates; explicit templates still load. */
        public Builder noPromptTemplates(boolean enabled) { return flag("--no-prompt-templates", enabled); }
        /** Pi --no-themes; explicit themes still load. */
        public Builder noThemes(boolean enabled) { return flag("--no-themes", enabled); }
        /** Pi --no-context-files. */
        public Builder noContextFiles(boolean enabled) { return flag("--no-context-files", enabled); }
        /** Pi --no-mcp disables its built-in MCP integration. */
        public Builder noMcp(boolean enabled) { return flag("--no-mcp", enabled); }
        /** Pi --offline disables startup network activity. */
        public Builder offline(boolean enabled) { return flag("--offline", enabled); }

        /** Keeps the Pi session in memory when true; defaults to Pi's session persistence. */
        public Builder noSession(boolean enabled) { return flag("--no-session", enabled); }
        /** By default only final assistant output is retained; true retains this activity's messages. */
        public Builder retainMessages(boolean retain) { retainMessages = retain; return this; }
        /** UTF-8 JSON budget per event subscription and early replay, plus a count limit. */
        public Builder eventBufferLimit(int bytes) {
            eventBufferLimit = PiSupport.positive(bytes, "eventBufferLimit");
            return this;
        }
        /** Maximum queued records per public event subscription and early replay. */
        public Builder eventBufferCapacity(int records) {
            eventBufferCapacity = PiSupport.positive(records, "eventBufferCapacity");
            return this;
        }
        /** Maximum single incoming JSONL record; increasing this requires enough process memory. */
        public Builder rpcLineLimit(int bytes) {
            rpcLineLimit = PiSupport.positive(bytes, "rpcLineLimit");
            return this;
        }
        /** Optional bounded asynchronous diagnostic callback. May contain stderr or error text. */
        public Builder diagnostics(Consumer<String> diagnostics) {
            this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            return this;
        }

        /** Sets the RPC response deadline in seconds. */
        public Builder requestTimeout(long seconds) {
            requestTimeout = PiSupport.positive(seconds, "requestTimeout seconds");
            return this;
        }

        /** Sets the default Activity deadline in seconds. */
        public Builder activityTimeout(long seconds) {
            activityTimeout = PiSupport.positive(seconds, "activityTimeout seconds");
            return this;
        }

        /** Sets the number of requests allowed to wait for outbound writes. */
        public Builder commandBufferCapacity(int requests) {
            commandBufferCapacity = PiSupport.positive(requests, "commandBufferCapacity");
            return this;
        }

        /** Sets graceful shutdown wait in seconds; zero requests immediate forced cleanup. */
        public Builder shutdownGracePeriod(long seconds) {
            shutdownGracePeriod = PiSupport.nonNegative(seconds, "shutdownGracePeriod seconds");
            return this;
        }

        public Builder resourcePolicy(ResourcePolicy resourcePolicy) {
            this.resourcePolicy = Objects.requireNonNull(resourcePolicy, "resourcePolicy");
            return this;
        }

        public Builder provider(String provider) {
            return option("--provider", requireNonBlank(provider, "provider"));
        }

        public Builder model(String model) {
            return option("--model", requireNonBlank(model, "model"));
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

        /** Explicitly loads and activates built-in Codemode, including with ISOLATED resources. */
        public Builder codemode(boolean enabled) { codemode = enabled; return this; }

        /** Explicitly loads and activates tool search for deferred tools. */
        public Builder toolSearch(boolean enabled) { toolSearch = enabled; return this; }

        /** Budget for retained message JSON plus the current streamed text, in UTF-8 bytes. */
        public Builder activityResultLimit(int bytes) {
            activityResultLimit = PiSupport.positive(bytes, "activityResultLimit");
            return this;
        }

        public PiConfig build() {
            return new PiConfig(this);
        }

        private String optionValue(String flag) {
            List<String> arguments = options.get(flag);
            return arguments == null ? null : arguments.get(1);
        }

        private Builder option(String flag, String value) {
            options.put(flag, List.of(flag, Objects.requireNonNull(value, flag)));
            return this;
        }

        private Builder flag(String flag, boolean enabled) {
            if (enabled) options.put(flag, List.of(flag));
            else options.remove(flag);
            return this;
        }

        private Builder repeated(String flag, String[] values) {
            List<String> arguments = new ArrayList<>(options.getOrDefault(flag, List.of()));
            for (String value : values) {
                arguments.add(flag);
                arguments.add(Objects.requireNonNull(value, flag));
            }
            options.put(flag, List.copyOf(arguments));
            return this;
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
