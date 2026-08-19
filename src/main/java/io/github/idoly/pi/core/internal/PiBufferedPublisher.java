package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

final class PiBufferedPublisher<T> implements Flow.Publisher<T> {
    private final PiPublisher<T> publisher;
    private final int capacity;
    private final List<T> buffer = new ArrayList<>();
    private boolean subscribed;
    private boolean overflow;
    private boolean terminated;
    private Throwable failure;

    PiBufferedPublisher(Executor executor, int capacity) {
        this.publisher = new PiPublisher<>(executor, capacity);
        this.capacity = capacity;
    }

    @Override
    public synchronized void subscribe(Flow.Subscriber<? super T> subscriber) {
        if (subscribed) {
            publisher.subscribe(subscriber);
            return;
        }

        subscribed = true;
        List<T> buffered = List.copyOf(buffer);
        buffer.clear();
        publisher.subscribe(subscriber);
        if (overflow) {
            publisher.fail(new PiException(
                    "Activity event replay buffer exceeded " + capacity + " messages"));
            return;
        }
        buffered.forEach(publisher::publish);
        if (terminated) {
            if (failure == null) {
                publisher.complete();
            } else {
                publisher.fail(failure);
            }
        }
    }

    void publish(T item) {
        synchronized (this) {
            if (!subscribed) {
                if (buffer.size() == capacity) {
                    overflow = true;
                    buffer.clear();
                } else if (!overflow) {
                    buffer.add(item);
                }
                return;
            }
        }
        publisher.publish(item);
    }

    void complete() {
        synchronized (this) {
            terminated = true;
            if (!subscribed) {
                return;
            }
        }
        publisher.complete();
    }

    void fail(Throwable failure) {
        synchronized (this) {
            terminated = true;
            this.failure = failure;
            if (!subscribed) {
                return;
            }
        }
        publisher.fail(failure);
    }
}
