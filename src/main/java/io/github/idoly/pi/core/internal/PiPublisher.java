package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

final class PiPublisher<T> implements Flow.Publisher<T> {
    private final List<Subscription<T>> subscribers = new CopyOnWriteArrayList<>();
    private final Executor executor;
    private final int capacity;
    private volatile boolean terminated;
    private volatile Throwable failure;

    PiPublisher(Executor executor, int capacity) {
        this.executor = executor;
        this.capacity = capacity;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("subscriber");
        }
        Subscription<T> state = new Subscription<>(subscriber, executor, capacity, subscribers);
        if (terminated) {
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            if (failure == null) {
                subscriber.onComplete();
            } else {
                subscriber.onError(failure);
            }
            return;
        }
        subscribers.add(state);
        subscriber.onSubscribe(state);
        if (terminated && subscribers.remove(state)) {
            state.terminate(failure);
        }
    }

    void publish(T item) {
        if (terminated) {
            return;
        }
        for (Subscription<T> subscriber : subscribers) {
            subscriber.offer(item);
        }
    }

    void complete() {
        terminate(null);
    }

    void fail(Throwable failure) {
        terminate(failure);
    }

    private void terminate(Throwable failure) {
        if (terminated) {
            return;
        }
        synchronized (this) {
            if (terminated) {
                return;
            }
            this.failure = failure;
            terminated = true;
        }
        for (Subscription<T> subscriber : subscribers) {
            subscriber.terminate(failure);
        }
    }

    private enum EmptySubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    }

    private static final class Subscription<T> implements Flow.Subscription {
        private final Flow.Subscriber<? super T> subscriber;
        private final Executor executor;
        private final int capacity;
        private final List<Subscription<T>> subscribers;
        private final ArrayDeque<T> queue = new ArrayDeque<>();
        private long demand;
        private boolean draining;
        private boolean cancelled;
        private boolean terminated;
        private Throwable failure;

        private Subscription(
                Flow.Subscriber<? super T> subscriber,
                Executor executor,
                int capacity,
                List<Subscription<T>> subscribers
        ) {
            this.subscriber = subscriber;
            this.executor = executor;
            this.capacity = capacity;
            this.subscribers = subscribers;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                terminate(new IllegalArgumentException("Flow demand must be positive"));
                return;
            }
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                demand = addDemand(demand, n);
                scheduleLocked();
            }
        }

        @Override
        public void cancel() {
            synchronized (this) {
                cancelled = true;
                queue.clear();
            }
            subscribers.remove(this);
        }

        private void offer(T item) {
            synchronized (this) {
                if (cancelled || terminated) {
                    return;
                }
                if (queue.size() == capacity) {
                    failure = new PiException(
                            "Subscriber buffer exceeded " + capacity + " messages");
                    terminated = true;
                } else {
                    queue.addLast(item);
                }
                scheduleLocked();
            }
        }

        private void terminate(Throwable failure) {
            synchronized (this) {
                if (cancelled || terminated) {
                    return;
                }
                this.failure = failure;
                terminated = true;
                scheduleLocked();
            }
        }

        private void scheduleLocked() {
            if (!draining) {
                draining = true;
                executor.execute(this::drain);
            }
        }

        private void drain() {
            while (true) {
                T next;
                Throwable failure;
                boolean completed;
                synchronized (this) {
                    if (cancelled) {
                        draining = false;
                        return;
                    }
                    if (demand > 0 && !queue.isEmpty()) {
                        demand--;
                        next = queue.removeFirst();
                        failure = null;
                        completed = false;
                    } else if (terminated && queue.isEmpty()) {
                        cancelled = true;
                        draining = false;
                        next = null;
                        failure = this.failure;
                        completed = failure == null;
                    } else {
                        draining = false;
                        return;
                    }
                }

                if (next != null) {
                    try {
                        subscriber.onNext(next);
                    } catch (Throwable ignored) {
                        cancel();
                        return;
                    }
                } else {
                    subscribers.remove(this);
                    if (completed) {
                        subscriber.onComplete();
                    } else {
                        subscriber.onError(failure);
                    }
                    return;
                }
            }
        }

        private static long addDemand(long current, long increment) {
            long sum = current + increment;
            return sum < 0 ? Long.MAX_VALUE : sum;
        }
    }
}
