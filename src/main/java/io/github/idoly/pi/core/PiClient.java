package io.github.idoly.pi.core;

import io.github.idoly.pi.core.PiConnection.Request;
import io.github.idoly.pi.RequestRejectedException;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiImage;
import io.github.idoly.pi.protocol.PiResponse;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns one Pi process and session, with at most one active activity. */
public final class PiClient implements AutoCloseable {

    /** Lifecycle of this process/session. */
    public enum State {
        STARTING,
        IDLE,
        ACTIVE,
        STOPPING,
        TERMINATED,
        FAILED
    }

    private static final Set<String> ACTIVITY_COMMANDS = Set.of(
            "prompt",
            "steer",
            "follow_up",
            "abort"
    );

    private static final Set<String> SESSION_COMMANDS = Set.of(
            "new_session",
            "switch_session",
            "fork",
            "clone",
            "compact",
            "bash"
    );

    final Object lifecycle = new Object();
    private final PiConfig config;
    private final PiConnection connection;
    private final PiPublisher<PiEvent> events;
    private final Consumer<String> diagnostics;
    private volatile State state = State.STARTING;
    private PiActivity active;
    private boolean changingSession;
    private CompletionStage<Void> sessionCancellation;

    PiClient(PiConnection connection, PiConfig config, Consumer<String> diagnostics) {
        this.config = config;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.diagnostics = diagnostics;
        events = new PiPublisher<>(PiThreads.executor(), config.eventBufferCapacity(), config.eventBufferLimit(), PiCodec::recordBytes, diagnostics);
    }

    /** Starts Pi in the Java process's current working directory. */
    public static PiClient open() {
        return open(".");
    }

    /** Starts Pi in an existing directory; relative paths use the Java working directory. */
    public static PiClient open(String workspace) {
        return open(PiConfig.builder(workspace).build());
    }

    /** Starts Pi using explicit settings. Tools execute in Pi, not in this SDK. */
    public static PiClient open(PiConfig config) {
        Objects.requireNonNull(config, "config");
        PiClient client = null;
        try {
            PiProcess process = new PiProcess(config.processConfig());
            PiConnection connection = new PiConnection(process, TimeUnit.SECONDS.toMillis(config.requestTimeout()), UUID.randomUUID().toString());
            connection.diagnostics(process::diagnostic);
            client = new PiClient(connection, config, process::diagnostic);
            client.start().toCompletableFuture().join();
            client.request("get_state").toCompletableFuture().join();
            return client;
        } catch (RuntimeException failure) {
            if (client != null) client.close();
            Throwable cause = unwrap(failure);
            throw cause instanceof RuntimeException runtime ? runtime : new PiException("Failed to open Pi", cause);
        }
    }

    CompletionStage<Void> start() {
        connection.listen(this::onInbound, this::failClient);
        return connection.start().whenComplete((ignored, failure) -> {
            synchronized (lifecycle) {
                if (state != State.STARTING) return;
                state = failure == null ? State.IDLE : State.FAILED;
                if (failure != null) events.fail(failure);
            }
        });
    }

    public PiActivity prompt(String message) { return prompt(message, 600); }
    /** Starts an activity with a positive timeout in seconds. */
    public PiActivity prompt(String message, long timeoutSeconds) { return prompt(message, List.of(), timeoutSeconds); }

    /** Starts an activity with images and a positive timeout in seconds. */
    public PiActivity prompt(String message, List<PiImage> images, long timeoutSeconds) {
        images = images == null ? List.of() : List.copyOf(images);
        if (timeoutSeconds < 1) throw new IllegalArgumentException("Activity timeout must be positive seconds");
        synchronized (lifecycle) {
            if (state != State.IDLE || changingSession) {
                throw new IllegalStateException("Prompt requires an idle session, current state is " + state);
            }
            String id = connection.nextRequestId();
            Request command = Request.message(id, "prompt", message, images);
            active = new PiActivity(id, connection, this, config, diagnostics);
            state = State.ACTIVE;
            PiActivity activity = active;
            activity.start(command, timeoutSeconds);
            return activity;
        }
    }

    public CompletionStage<PiResponse> request(String command) { return request(command, Map.of()); }
    public CompletionStage<PiResponse> request(String command, Map<String, ?> arguments) {
        return request(command, arguments, config.requestTimeout());
    }
    /** Sends a request with a positive response timeout in seconds. */
    public CompletionStage<PiResponse> request(String command, Map<String, ?> arguments, long timeoutSeconds) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("Response timeout must be positive seconds");
        Objects.requireNonNull(command, "command");
        if (ACTIVITY_COMMANDS.contains(command)) throw new IllegalArgumentException(command + " is managed by PiActivity");
        if ("extension_ui_response".equals(command)) throw new IllegalArgumentException("Use respondToUi for extension dialogs");
        synchronized (lifecycle) {
            requireRunning();
            boolean exclusive = SESSION_COMMANDS.contains(command);
            if (exclusive && (changingSession || state != State.IDLE)) {
                throw new IllegalStateException(command + " requires an idle session without a session operation");
            }
            CompletionStage<PiResponse> result = connection.request(command, arguments, TimeUnit.SECONDS.toMillis(timeoutSeconds));
            if (exclusive) {
                changingSession = true;
                result = result.whenComplete((response, failure) -> {
                    synchronized (lifecycle) {
                        changingSession = false;
                        if (failure != null && !(unwrap(failure) instanceof RequestRejectedException)
                                && (state == State.IDLE || state == State.ACTIVE)) state = State.FAILED;
                    }
                });
            }
            return external(result);
        }
    }

    /** Replies using the dialog id; completes after writing, without waiting for an RPC response. */
    public CompletionStage<Void> respondToUi(String id, Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        if (values.size() != 1 || !(values.get("value") instanceof String
                || values.get("confirmed") instanceof Boolean || Boolean.TRUE.equals(values.get("cancelled")))) {
            throw new IllegalArgumentException("Supply one dialog value, confirmation, or cancellation");
        }
        synchronized (lifecycle) {
            requireRunning();
            return external(connection.sendUi(id, values));
        }
    }

    /** Cancels current session work, including work independently started by an extension. */
    public CompletionStage<Void> abort() {
        synchronized (lifecycle) {
            requireRunning();
            if (active != null) return active.abort();
            if (sessionCancellation != null) return sessionCancellation;
            if (changingSession) throw new IllegalStateException("A session operation is in progress");
            changingSession = true;
            CompletableFuture<Void> result = new CompletableFuture<>();
            CompletionStage<Void> stage = result.minimalCompletionStage();
            sessionCancellation = stage;
            connection.request("clear_queue").thenCompose(ignored -> connection.request("abort"))
                    .whenComplete((response, failure) -> {
                        synchronized (lifecycle) {
                            changingSession = false;
                            sessionCancellation = null;
                            if (state == State.IDLE || state == State.ACTIVE) {
                                if (failure == null) state = State.IDLE;
                                else state = State.FAILED;
                            }
                        }
                        PiThreads.callbacks().execute(() -> {
                            if (failure == null) result.complete(null);
                            else result.completeExceptionally(unwrap(failure));
                        });
                    });
            return stage;
        }
    }

    public Flow.Publisher<PiEvent> events() { return events; }
    public State state() { return state; }

    @Override public void close() {
        synchronized (lifecycle) {
            if (state == State.TERMINATED) return;
            if (state != State.STOPPING) {
                state = State.STOPPING;
                PiActivity activity = active;
                active = null;
                if (activity != null) activity.fail(new PiException("Client closed while activity was active"));
            }
        }
        try { connection.close(); }
        finally {
            synchronized (lifecycle) { state = State.TERMINATED; }
            events.complete();
        }
    }

    boolean isActive(PiActivity activity) { return active == activity && state == State.ACTIVE; }
    void release(PiActivity activity) { release(activity, false); }
    void release(PiActivity activity, boolean independentRun) {
        if (isActive(activity)) { active = null; state = independentRun ? State.ACTIVE : State.IDLE; }
    }
    void fail(PiActivity activity) {
        if (isActive(activity)) { active = null; state = State.FAILED; }
    }

    /** Caller holds lifecycle; checking ownership and sending form one operation. */
    CompletionStage<PiResponse> control(PiActivity activity, Request command) {
        if (!isActive(activity)) throw new IllegalStateException(command.type() + " requires an active activity");
        return connection.request(command);
    }

    private void requireRunning() {
        if (state != State.IDLE && state != State.ACTIVE) {
            throw new IllegalStateException("Client is not running: " + state);
        }
    }

    static <T> CompletionStage<T> external(CompletionStage<T> internal) {
        return internal.handleAsync((value, failure) -> {
            if (failure != null) throw new CompletionException(unwrap(failure));
            return value;
        }, PiThreads.callbacks());
    }
    static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private void onInbound(Object message) {
        if (!(message instanceof PiEvent event)) return;
        synchronized (lifecycle) {
            if (state == State.STOPPING || state == State.TERMINATED) return;
            events.publish(event);
            if (active != null) active.onEvent(event);
            else if ("agent_start".equals(event.type()) && state == State.IDLE) state = State.ACTIVE;
            else if ("agent_settled".equals(event.type()) && state == State.ACTIVE) state = State.IDLE;
        }
    }

    private void failClient(Throwable failure) {
        synchronized (lifecycle) {
            if (state == State.STOPPING || state == State.TERMINATED) return;
            state = State.FAILED;
            PiActivity activity = active;
            active = null;
            if (activity != null) activity.fail(failure);
            events.fail(failure);
        }
    }
}
