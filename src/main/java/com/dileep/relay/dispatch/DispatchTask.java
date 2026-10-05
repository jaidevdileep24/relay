package com.dileep.relay.dispatch;

import java.util.UUID;

/**
 * Immutable snapshot of everything one send needs, built inside the claim
 * transaction.
 *
 * <p>It exists because the sender runs after that transaction commits. Handing
 * it a {@code Delivery} instead would mean touching LAZY associations on a
 * detached entity - {@code LazyInitializationException} - and sharing one JPA
 * entity across the worker threads.
 *
 * @param endpointId  the rate limiter's key; the endpoint itself is gone by then
 * @param replayCount which replay cycle this attempt belongs to, for the audit trail
 */
public record DispatchTask(Long deliveryId, int attemptNumber, int replayCount, UUID messageId,
                           UUID endpointId, String eventType, String payload, String url,
                           String secret) {}
