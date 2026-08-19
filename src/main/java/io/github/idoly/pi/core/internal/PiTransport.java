package io.github.idoly.pi.core.internal;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

interface PiTransport extends AutoCloseable {
    CompletionStage<Void> start();

    CompletionStage<Void> send(PiCommand command);

    Flow.Publisher<Object> inbound();

    CompletionStage<Integer> exit();


    @Override
    void close();
}
