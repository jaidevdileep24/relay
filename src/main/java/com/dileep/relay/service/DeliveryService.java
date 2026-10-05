package com.dileep.relay.service;

import com.dileep.relay.api.dto.DeliveryAttemptResponse;
import com.dileep.relay.api.dto.DeliveryResponse;
import com.dileep.relay.api.dto.TriageInsightsResponse;
import com.dileep.relay.api.dto.PageResponse;
import com.dileep.relay.api.dto.ReplayResponse;
import com.dileep.relay.domain.DeliveryState;

import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface DeliveryService {
    PageResponse<DeliveryResponse> listByApplication(UUID applicationId, DeliveryState state, Pageable pageable);

    PageResponse<DeliveryAttemptResponse> listAttempts(UUID applicationId, Long deliveryId, Pageable pageable);

    /**
     * Puts one terminal delivery back on the queue.
     *
     * <p>The row is reused rather than recreated - uq_delivery_message_endpoint
     * permits exactly one delivery per (message, endpoint) - so attempt_count
     * resets and replay_count increments to keep the audit trail unambiguous.
     *
     * <p>Only FAILED and DISABLED can be replayed. A PENDING delivery may be
     * leased by a worker at this instant, and resetting it underneath would
     * duplicate the send; SUCCEEDED has nothing to retry. Both are 409.
     *
     * <p>The receiver sees the same webhook-id it saw before, so a correct
     * receiver dedupes the replay away. That is invariant 4 paying for itself:
     * because we never promised exactly-once, replay is safe to offer.
     */
    DeliveryResponse replay(UUID applicationId, Long deliveryId);

    /** What failure triage has decided and saved for one application. 404 if unknown. */
    TriageInsightsResponse triageInsights(UUID applicationId);

    /**
     * Replays every terminal delivery for one endpoint - the post-outage
     * operation. Rejected while the endpoint is disabled: the breaker would
     * simply re-trip and strand the backlog again, so the endpoint has to be
     * re-enabled first.
     */
    ReplayResponse replayFailedForEndpoint(UUID applicationId, UUID endpointId);
}
