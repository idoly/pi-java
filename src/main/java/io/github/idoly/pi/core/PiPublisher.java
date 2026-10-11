package io.github.idoly.pi.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/** Bounded subscriptions; callbacks, diagnostics and task submission run outside state locks. */
final class PiPublisher<T> implements Flow.Publisher<T> {
    private final List<Subscription> subscribers = new CopyOnWriteArrayList<>();
    private final Executor executor;
    private final int capacity;
    private final long byteLimit;
    private final ToLongFunction<? super T> size;
    private final Consumer<String> diagnostics;
    private List<Entry<T>> early;
    private long earlyBytes;
    private boolean terminated;
    private Throwable failure;

    PiPublisher(Executor executor, int capacity, long byteLimit, ToLongFunction<? super T> size,
                Consumer<String> diagnostics) {
        this(executor, capacity, byteLimit, size, diagnostics, false);
    }

    PiPublisher(Executor executor, int capacity, long byteLimit, ToLongFunction<? super T> size,
                Consumer<String> diagnostics, boolean replay) {
        this.executor = executor;
        this.capacity = capacity;
        this.byteLimit = byteLimit;
        this.size = size;
        this.diagnostics = diagnostics;
        early = replay ? new ArrayList<>() : null;
    }

    @Override public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        Subscription state;
        synchronized (this) {
            state = new Subscription(subscriber, early, earlyBytes, terminated, failure);
            early = null;
            earlyBytes = 0;
            subscribers.add(state);
        }
        try { subscriber.onSubscribe(state); }
        catch (Throwable error) { state.cancel(); return; }
        state.activate();
    }

    void publish(T item) {
        synchronized (this) {
            if (terminated || early == null && subscribers.isEmpty()) return;
        }
        Entry<T> entry = new Entry<>(item, Math.max(1, size.applyAsLong(item)));
        Iterator<Subscription> targets;
        PiException overflow = null;
        synchronized (this) {
            if (terminated) return;
            if (early != null) {
                if (early.size() < capacity && entry.bytes <= byteLimit - earlyBytes) {
                    early.add(entry);
                    earlyBytes += entry.bytes;
                    return;
                }
                overflow = new PiException("Replay buffer exceeded " + capacity + " records or " + byteLimit + " bytes");
                failure = overflow;
                terminated = true;
                early.clear();
                earlyBytes = 0;
            }
            targets = subscribers.iterator();
        }
        if (overflow != null) {
            diagnostics.accept(overflow.getMessage());
            while (targets.hasNext()) targets.next().terminate(overflow);
        } else {
            while (targets.hasNext()) targets.next().offer(entry);
        }
    }
    void complete() { terminate(null); }
    void fail(Throwable failure) { terminate(failure); }
    private void terminate(Throwable failure) {
        Iterator<Subscription> targets;
        synchronized (this) {
            if (terminated) return;
            this.failure = failure;
            terminated = true;
            if (failure != null && early != null) { early.clear(); earlyBytes = 0; }
            targets = subscribers.iterator();
        }
        while (targets.hasNext()) targets.next().terminate(failure);
    }

    private record Entry<T>(T value, long bytes) {}

    private final class Subscription implements Flow.Subscription {
        private final Flow.Subscriber<? super T> subscriber;
        private final ArrayDeque<Entry<T>> queue = new ArrayDeque<>();
        private long queuedBytes;
        private long demand;
        private boolean ready;
        private boolean draining;
        private boolean cancelled;
        private boolean terminal;
        private Throwable error;

        Subscription(Flow.Subscriber<? super T> subscriber, List<Entry<T>> replay, long bytes,
                     boolean terminal, Throwable error) {
            this.subscriber = subscriber;
            this.terminal = terminal;
            this.error = error;
            if (error == null && replay != null) { queue.addAll(replay); queuedBytes = bytes; }
        }
        @Override public void request(long n) {
            if (n <= 0) { terminate(new IllegalArgumentException("Flow demand must be positive")); return; }
            boolean run;
            synchronized (this) {
                if (cancelled) return;
                long sum = demand + n;
                demand = sum < 0 ? Long.MAX_VALUE : sum;
                run = scheduleLocked();
            }
            dispatch(run);
        }
        @Override public void cancel() {
            synchronized (this) { cancelled = true; queue.clear(); queuedBytes = 0; }
            subscribers.remove(this);
        }
        void activate() {
            boolean run;
            synchronized (this) { ready = true; run = scheduleLocked(); }
            dispatch(run);
        }
        void offer(Entry<T> entry) {
            boolean run;
            String overflow = null;
            synchronized (this) {
                if (cancelled || terminal) return;
                if (queue.size() == capacity || entry.bytes > byteLimit - queuedBytes) {
                    error = new PiException("Subscriber buffer exceeded " + capacity + " records or " + byteLimit + " bytes");
                    terminal = true; queue.clear(); queuedBytes = 0;
                    overflow = error.getMessage();
                } else { queue.addLast(entry); queuedBytes += entry.bytes; }
                run = scheduleLocked();
            }
            try { if (overflow != null) diagnostics.accept(overflow); }
            finally { dispatch(run); }
        }
        void terminate(Throwable failure) {
            boolean run;
            synchronized (this) {
                if (cancelled || terminal) return;
                error = failure; terminal = true;
                if (failure != null) { queue.clear(); queuedBytes = 0; }
                run = scheduleLocked();
            }
            dispatch(run);
        }
        private boolean scheduleLocked() {
            if (!ready || draining || cancelled || !(demand > 0 && !queue.isEmpty() || terminal && queue.isEmpty())) return false;
            draining = true;
            return true;
        }
        private void dispatch(boolean run) { if (run) executor.execute(this::drain); }
        private void drain() {
            for (;;) {
                Entry<T> next;
                Throwable failure;
                synchronized (this) {
                    if (cancelled) { draining = false; return; }
                    if (demand > 0 && !queue.isEmpty()) {
                        demand--; next = queue.removeFirst(); queuedBytes -= next.bytes; failure = null;
                    } else if (terminal && queue.isEmpty()) {
                        cancelled = true; draining = false; next = null; failure = error;
                    } else { draining = false; return; }
                }
                try {
                    if (next != null) subscriber.onNext(next.value);
                    else {
                        subscribers.remove(this);
                        if (failure == null) subscriber.onComplete(); else subscriber.onError(failure);
                        return;
                    }
                } catch (Throwable ignored) { cancel(); return; }
            }
        }
    }
}
