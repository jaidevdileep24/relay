package com.dileep.relay.retry;


import com.dileep.relay.config.RelayProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter: a random delay in
 * {@code [0, min(cap, base * 2^n)]}.
 *
 * <p>The randomness is the point. A fixed delay makes every delivery that failed
 * during an outage retry at the same instant, knocking the receiver over again
 * the moment it recovers.
 *
 * <p>Defaults give 0-2s, 0-4s, 0-8s ... 0-128s, then FAILED on attempt 8. The
 * 1h cap never fires at {@code max-attempts: 8}; it matters past attempt 12.
 *
 * <p>{@code ThreadLocalRandom} not {@code SecureRandom} - nobody attacks a retry
 * schedule, and a shared {@code SecureRandom} would serialise all 8 threads.
 *
 * <p>{@code Math.min(attemptNumber, 32)} guards the shift: Java's {@code <<} on a
 * long uses only the low 6 bits, so {@code x << 64} silently means {@code x << 0}.
 */
@Component
public class FullJitterRetryPolicy implements RetryPolicy {

    private final RelayProperties.Retry config;

    public FullJitterRetryPolicy(RelayProperties properties) {
        this.config = properties.getRetry();
    }

    @Override
    public Optional<Duration> nextDelay(int attemptNumber) {
        if(attemptNumber < 1 ) {
            throw new IllegalArgumentException("Attempt must be >= 1, got " + attemptNumber);
        }

        if( attemptNumber >= config.getMaxAttempts()) {
            return Optional.empty();
        }

        int shift = Math.min(attemptNumber, 32);
        long exponential = config.getBaseDelayMs() << shift;
        long window = Math.min(exponential, config.getMaxDelayMs());

        return Optional.of(Duration.ofMillis(ThreadLocalRandom.current().nextLong(window + 1)));
    }

}
