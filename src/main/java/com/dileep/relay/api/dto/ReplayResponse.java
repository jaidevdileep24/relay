package com.dileep.relay.api.dto;

/**
 * Result of a bulk replay.
 *
 * @param replayed how many terminal deliveries went back on the queue; zero
 *                 when there was nothing to replay, which is a success, not an
 *                 error
 */
public record ReplayResponse(int replayed) {}
