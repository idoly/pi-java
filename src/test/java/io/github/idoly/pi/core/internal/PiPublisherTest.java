package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PiPublisherTest {
    @Test
    void terminatesSlowSubscriberOnOverflow() {
        Executor direct = Runnable::run;
        PiPublisher<Integer> publisher = new PiPublisher<>(direct, 1);
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);

        publisher.publish(1);
        publisher.publish(2);
        subscriber.subscription.request(1);

        assertInstanceOf(PiException.class, subscriber.error);
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<Integer> {
        private Flow.Subscription subscription;
        private Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(Integer item) {
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onComplete() {
        }
    }
}
