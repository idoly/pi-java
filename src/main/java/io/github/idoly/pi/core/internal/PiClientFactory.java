package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiClient;
import io.github.idoly.pi.core.PiException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** Internal entry point used by {@link PiClient#open(Path, Object[])}. */
public final class PiClientFactory {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private PiClientFactory() {
    }

    /** Opens and verifies a client. */
    public static PiClient open(Path workspace, Object... toolInstances) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(toolInstances, "toolInstances");
        PiToolBridge toolBridge = null;
        PiClientImpl client = null;
        try {
            if (toolInstances.length > 0) {
                toolBridge = PiToolBridge.open(List.of(toolInstances));
            }
            PiTransport transport = new PiProcess(
                    PiProcessConfig.create(workspace, toolBridge));
            client = create(transport, REQUEST_TIMEOUT, newPrefix(), toolBridge);
            client.start().toCompletableFuture().join();
            client.request("get_state").toCompletableFuture().join();
            return client;
        } catch (RuntimeException failure) {
            if (client != null) {
                client.close();
            } else if (toolBridge != null) {
                toolBridge.close();
            }
            throw unwrapFailure(failure);
        }
    }

    static PiClientImpl createForTest(PiTransport transport) {
        PiClientImpl client = create(transport, REQUEST_TIMEOUT, "test", null);
        try {
            client.start().toCompletableFuture().join();
            return client;
        } catch (RuntimeException failure) {
            client.close();
            throw unwrapFailure(failure);
        }
    }

    private static PiClientImpl create(
            PiTransport transport,
            Duration timeout,
            String prefix,
            PiToolBridge toolBridge
    ) {
        Objects.requireNonNull(transport, "transport");
        return new PiClientImpl(
                new PiRpcConnection(transport, timeout, prefix), toolBridge);
    }

    private static RuntimeException unwrapFailure(RuntimeException failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        return cause instanceof RuntimeException runtime
                ? runtime
                : new PiException("Failed to open pi client", cause);
    }

    private static String newPrefix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
