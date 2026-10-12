package io.github.idoly.pi.core;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Shared SDK value checks and completion handling; package-private implementation support. */
final class PiSupport {
    private PiSupport() {}

    static long positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    static int positive(int value, String name) {
        return (int) positive((long) value, name);
    }

    static long nonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
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
}
