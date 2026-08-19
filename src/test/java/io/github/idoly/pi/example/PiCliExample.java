package io.github.idoly.pi.example;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.idoly.pi.core.PiActivity;
import io.github.idoly.pi.core.PiClient;
import io.github.idoly.pi.core.PiPrompt;
import io.github.idoly.pi.core.PiResult;
import io.github.idoly.pi.protocol.PiResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/** Example command-line adapter for one-shot prompts and interactive sessions. */
public final class PiCliExample {
    private static final String USAGE = """
            Usage: PiCliExample [options] <prompt...>
                   PiCliExample --interactive [options] [initial prompt...]

            Options:
              -w, --workspace <path>  Workspace directory (default: current directory)
              -t, --timeout <seconds> Activity timeout in seconds (default: 600)
              -s, --session <file>    Resume a session JSONL file
              -i, --interactive       Keep one Client open for a conversation
              -h, --help              Show this help
                  --version           Show the version

            Use - as the prompt to read UTF-8 text from stdin.
            Interactive commands: :session, :switch <file>, :new, :help, :quit
            """;

    private PiCliExample() {
    }

    /** Runs the command-line adapter and exits with its status code. */
    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    static int run(String[] args, InputStream input, PrintStream output, PrintStream error) {
        try {
            Options options = parse(args);
            if (options.help()) {
                output.print(USAGE);
                return 0;
            }
            if (options.version()) {
                output.println(version());
                return 0;
            }

            String prompt = options.stdin()
                    ? readPrompt(input)
                    : String.join(" ", options.prompt());
            if (!options.interactive() && prompt.isBlank()) {
                throw new CliException("Prompt must not be blank");
            }

            try (PiClient client = PiClient.open(options.workspace())) {
                Thread shutdownHook = new Thread(client::close, "pi-cli-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                try {
                    if (options.session() != null) {
                        switchSession(client, options.session());
                    }
                    if (options.interactive()) {
                        return converse(client, prompt, options.timeout(), input, output, error);
                    }
                    ask(client, prompt, options.timeout(), output);
                    return 0;
                } finally {
                    removeShutdownHook(shutdownHook);
                }
            }
        } catch (CliException failure) {
            error.println("Error: " + failure.getMessage());
            error.println("Run with --help for usage.");
            return 2;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            error.println("Interrupted");
            return 130;
        } catch (ExecutionException failure) {
            return report(failure.getCause(), error);
        } catch (Exception failure) {
            return report(failure, error);
        }
    }

    private static Options parse(String[] args) {
        Path workspace = Path.of(".");
        Duration timeout = Duration.ofMinutes(10);
        Path session = null;
        List<String> prompt = new ArrayList<>();
        boolean optionsEnded = false;
        boolean interactive = false;

        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (!optionsEnded && "--".equals(argument)) {
                optionsEnded = true;
            } else if (!optionsEnded && ("-h".equals(argument) || "--help".equals(argument))) {
                return new Options(
                        workspace, timeout, session, List.of(), false, false, true, false);
            } else if (!optionsEnded && "--version".equals(argument)) {
                return new Options(
                        workspace, timeout, session, List.of(), false, false, false, true);
            } else if (!optionsEnded
                    && ("-i".equals(argument) || "--interactive".equals(argument))) {
                interactive = true;
            } else if (!optionsEnded && ("-w".equals(argument) || "--workspace".equals(argument))) {
                workspace = Path.of(requireValue(args, ++index, argument));
            } else if (!optionsEnded && ("-t".equals(argument) || "--timeout".equals(argument))) {
                timeout = parseTimeout(requireValue(args, ++index, argument));
            } else if (!optionsEnded && ("-s".equals(argument) || "--session".equals(argument))) {
                session = Path.of(requireValue(args, ++index, argument));
            } else if (!optionsEnded && argument.startsWith("-") && !"-".equals(argument)) {
                throw new CliException("Unknown option: " + argument);
            } else {
                prompt.add(argument);
            }
        }

        if (prompt.isEmpty() && !interactive) {
            throw new CliException("Prompt is required unless --interactive is used");
        }
        boolean stdin = prompt.size() == 1 && "-".equals(prompt.get(0));
        if (!stdin && prompt.contains("-")) {
            throw new CliException("The stdin marker - must be the only prompt argument");
        }
        if (stdin && interactive) {
            throw new CliException("The stdin prompt marker - cannot be used with --interactive");
        }
        return new Options(
                workspace, timeout, session, List.copyOf(prompt),
                stdin, interactive, false, false);
    }

    private static int converse(
            PiClient client,
            String initialPrompt,
            Duration timeout,
            InputStream input,
            PrintStream output,
            PrintStream error
    ) throws IOException, InterruptedException, ExecutionException {
        if (!initialPrompt.isBlank()) {
            ask(client, initialPrompt, timeout, output);
        }

        boolean terminal = input == System.in && System.console() != null;
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8));
        while (true) {
            if (terminal) {
                error.print("you> ");
                error.flush();
            }
            String line = reader.readLine();
            if (line == null) {
                return 0;
            }
            String command = line.strip();
            if (":quit".equals(command) || ":exit".equals(command)) {
                return 0;
            }
            if (command.isEmpty()) {
                continue;
            }
            if (":help".equals(command)) {
                output.println("Commands: :session, :switch <file>, :new, :help, :quit");
                continue;
            }
            if (":session".equals(command)) {
                printSession(client, error);
                continue;
            }
            if (command.startsWith(":switch ")) {
                switchSession(client, Path.of(command.substring(8).strip()));
                printSession(client, error);
                continue;
            }
            if (":new".equals(command)) {
                client.request("new_session").toCompletableFuture().get();
                printSession(client, error);
                continue;
            }
            if (command.startsWith(":")) {
                error.println("Unknown command: " + command);
                continue;
            }
            ask(client, line, timeout, output);
        }
    }

    private static void ask(
            PiClient client,
            String prompt,
            Duration timeout,
            PrintStream output
    ) throws InterruptedException, ExecutionException {
        PiPrompt request = new PiPrompt(prompt, List.of(), timeout);
        PiActivity activity = client.prompt(request);
        PiResult result = activity.completion().toCompletableFuture().get();
        output.println(result.text());
    }

    static void switchSession(PiClient client, Path session)
            throws InterruptedException, ExecutionException {
        PiResponse response = client.request("switch_session", Map.of(
                "sessionPath", session.toAbsolutePath().normalize().toString()
        )).toCompletableFuture().get();
        JsonNode cancelled = requireData(response).get("cancelled");
        if (cancelled == null || !cancelled.isBoolean()) {
            throw new CliException("switch_session response is missing cancelled");
        }
        if (cancelled.booleanValue()) {
            throw new CliException("Session switch was cancelled: " + session);
        }
    }

    static void printSession(PiClient client, PrintStream output)
            throws InterruptedException, ExecutionException {
        PiResponse response = client.request("get_state").toCompletableFuture().get();
        JsonNode data = requireData(response);
        String sessionId = requireText(data, "sessionId", "get_state");
        String sessionFile = requireText(data, "sessionFile", "get_state");
        output.println("sessionId=" + sessionId);
        output.println("sessionFile=" + sessionFile);
    }

    private static JsonNode requireData(PiResponse response) {
        if (response.data() == null || !response.data().isObject()) {
            throw new CliException(response.command() + " response is missing data");
        }
        return response.data();
    }

    private static String requireText(JsonNode data, String field, String command) {
        JsonNode value = data.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new CliException(command + " response is missing " + field);
        }
        return value.textValue();
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new CliException("Missing value for " + option);
        }
        return args[index];
    }

    private static Duration parseTimeout(String value) {
        try {
            long seconds = Long.parseLong(value);
            if (seconds <= 0) {
                throw new NumberFormatException();
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException failure) {
            throw new CliException("Timeout must be a positive number of seconds: " + value);
        }
    }

    private static String readPrompt(InputStream input) throws IOException {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
    }

    private static int report(Throwable failure, PrintStream error) {
        Throwable cause = failure == null ? new IllegalStateException("Unknown failure") : failure;
        String message = cause.getMessage();
        error.println("Error: " + (message == null ? cause.getClass().getSimpleName() : message));
        return 1;
    }

    private static void removeShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // The hook is already running during JVM shutdown.
        }
    }

    private static String version() {
        String version = PiCliExample.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }

    private record Options(
            Path workspace,
            Duration timeout,
            Path session,
            List<String> prompt,
            boolean stdin,
            boolean interactive,
            boolean help,
            boolean version
    ) {
    }

    private static final class CliException extends RuntimeException {
        private CliException(String message) {
            super(message);
        }
    }
}
