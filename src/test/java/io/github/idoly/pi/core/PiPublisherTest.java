package io.github.idoly.pi.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiPublisherTest {
    @Test
    void countLimitFailsOnceWithoutDeliveringBufferedItems() {
        Executor direct = Runnable::run;
        PiPublisher<Integer> publisher = new PiPublisher<>(direct, 1, 100, ignored -> 1, ignored -> {});
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);

        publisher.publish(1);
        publisher.publish(2);
        assertInstanceOf(PiException.class, subscriber.error);
        subscriber.subscription.request(1);
        assertEquals(1, subscriber.errorCount);
        assertTrue(subscriber.items.isEmpty());
    }

    @Test
    void invalidDemandFailsWithoutDrainingBufferedData() {
        PiPublisher<Integer> publisher = new PiPublisher<>(Runnable::run, 2, 100, ignored -> 1, ignored -> {});
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        publisher.publish(1);
        subscriber.subscription.request(0);
        assertInstanceOf(IllegalArgumentException.class, subscriber.error);
        subscriber.subscription.request(1);
        assertEquals(1, subscriber.errorCount);
        assertTrue(subscriber.items.isEmpty());
    }

    @Test
    void failureDoesNotRequireDemandAndSignalsAfterOnSubscribe() {
        PiPublisher<Integer> publisher = new PiPublisher<>(Runnable::run, 2, 100, ignored -> 1, ignored -> {});
        List<String> order = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) {
                publisher.publish(1);
                publisher.fail(new PiException("closed"));
                order.add("subscribed");
            }
            public void onNext(Integer item) { order.add("next"); }
            public void onError(Throwable failure) { order.add("error"); }
            public void onComplete() { order.add("complete"); }
        });
        assertEquals(List.of("subscribed", "error"), order);
    }

    @Test
    void byteBudgetFailsWithoutDeliveringBufferedItems() {
        PiPublisher<Integer> publisher = new PiPublisher<>(Runnable::run, 100, 5, Integer::longValue, ignored -> {});
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        publisher.publish(3);
        publisher.publish(3);
        assertInstanceOf(PiException.class, subscriber.error);
        assertTrue(subscriber.error.getMessage().contains("5 bytes"));
        subscriber.subscription.request(Long.MAX_VALUE);
        assertEquals(1, subscriber.errorCount);
        assertTrue(subscriber.items.isEmpty());
    }

    @Test
    void byteBudgetIsReturnedWhenItemsAreConsumed() {
        PiPublisher<Integer> publisher = new PiPublisher<>(Runnable::run, 100, 5, Integer::longValue, ignored -> {});
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);
        for (int i = 0; i < 20; i++) publisher.publish(5);
        assertNull(subscriber.error);
        assertEquals(20, subscriber.items.size());
        publisher.publish(6);
        assertInstanceOf(PiException.class, subscriber.error);
    }

    @Test
    void replayByteBudgetAppliesBeforeAnySubscription() {
        PiPublisher<Integer> publisher = new PiPublisher<>(Runnable::run, 100, 5, Integer::longValue, ignored -> {}, true);
        publisher.publish(3); publisher.publish(3);
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        assertInstanceOf(PiException.class, subscriber.error);
    }

    @Test
    void failureDropsEarlyReplayWithoutRequiringDemand() {
        PiPublisher<Integer> publisher = new PiPublisher<>(Runnable::run, 100, 5, Integer::longValue, ignored -> {}, true);
        publisher.publish(3);
        publisher.fail(new PiException("failed"));
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        assertEquals("failed", subscriber.error.getMessage());
    }

    @Test
    void firstSubscriberGetsReplayBeforeLiveRecordsAndLaterSubscribersDoNot() {
        PiPublisher<Integer> publisher = new PiPublisher<>(Runnable::run, 10, 100, Integer::longValue, ignored -> {}, true);
        publisher.publish(1);
        List<Integer> first = new ArrayList<>();
        List<Integer> second = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) {
                publisher.publish(2);
                subscription.request(Long.MAX_VALUE);
            }
            public void onNext(Integer item) { first.add(item); }
            public void onError(Throwable failure) { errors.add(failure); }
            public void onComplete() {}
        });
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(Integer item) { second.add(item); }
            public void onError(Throwable failure) { errors.add(failure); }
            public void onComplete() {}
        });
        publisher.publish(3);
        assertEquals(List.of(1, 2, 3), first);
        assertEquals(List.of(3), second);
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void lateFirstSubscriptionReplaysRecordsThenCompletes() {
        PiPublisher<Integer> publisher = new PiPublisher<>(Runnable::run, 10, 100, Integer::longValue, ignored -> {}, true);
        List<String> seen = new ArrayList<>();
        publisher.publish(1);
        publisher.complete();
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { seen.add("subscribe"); subscription.request(Long.MAX_VALUE); }
            public void onNext(Integer item) { seen.add("item=" + item); }
            public void onError(Throwable failure) { seen.add("error"); }
            public void onComplete() { seen.add("complete"); }
        });
        assertEquals(List.of("subscribe", "item=1", "complete"), seen);
    }

    @Test
    void directExecutorNeverCallsSubscriberWhileHoldingStateLocks() {
        for (boolean fail : List.of(false, true)) {
            PiPublisher<Integer> publisher = new PiPublisher<>(Runnable::run, 10, 100, Integer::longValue, ignored -> {});
            List<Boolean> locked = new ArrayList<>();
            List<String> callbacks = new ArrayList<>();
            publisher.subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;
                private void record(String callback) {
                    callbacks.add(callback);
                    locked.add(Thread.holdsLock(publisher) || Thread.holdsLock(subscription));
                }
                public void onSubscribe(Flow.Subscription subscription) {
                    this.subscription = subscription;
                    record("subscribe");
                    subscription.request(Long.MAX_VALUE);
                }
                public void onNext(Integer item) { record("next"); }
                public void onError(Throwable error) { record("error"); }
                public void onComplete() { record("complete"); }
            });
            publisher.publish(1);
            if (fail) publisher.fail(new PiException("failed")); else publisher.complete();
            assertEquals(List.of("subscribe", "next", fail ? "error" : "complete"), callbacks);
            assertEquals(List.of(false, false, false), locked);
        }
    }

    @Test
    void blockedDirectCallbackDoesNotLockSubscriberRegistrationOrTermination() throws Exception {
        PiPublisher<Integer> publisher = new PiPublisher<>(Runnable::run, 10, 100, Integer::longValue, ignored -> {});
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(Integer item) {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            }
            public void onError(Throwable failure) {}
            public void onComplete() {}
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var publishing = executor.submit(() -> publisher.publish(1));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture.runAsync(() -> publisher.subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription subscription) {}
                public void onNext(Integer item) {}
                public void onError(Throwable failure) {}
                public void onComplete() {}
            })).get(2, TimeUnit.SECONDS);
            CompletableFuture.runAsync(publisher::complete).get(2, TimeUnit.SECONDS);
            assertFalse(publishing.isDone());
            release.countDown();
            publishing.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void sizeAndOverflowDiagnosticsRunOutsideStateLocks() {
        AtomicReference<PiPublisher<Integer>> owner = new AtomicReference<>();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        List<Boolean> locked = new ArrayList<>();
        PiPublisher<Integer> publisher = new PiPublisher<>(Runnable::run, 10, 1, item -> {
            locked.add(Thread.holdsLock(owner.get()));
            return item;
        }, line -> locked.add(Thread.holdsLock(owner.get()) || Thread.holdsLock(subscription.get())));
        owner.set(publisher);
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription value) { subscription.set(value); }
            public void onNext(Integer item) {}
            public void onError(Throwable failure) {}
            public void onComplete() {}
        });
        publisher.publish(2);
        assertEquals(List.of(false, false), locked);
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<Integer> {
        private final List<Integer> items = new ArrayList<>();
        private Flow.Subscription subscription;
        private Throwable error;
        private int errorCount;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(Integer item) {
            items.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            errorCount++;
        }

        @Override
        public void onComplete() {
        }
    }
}
