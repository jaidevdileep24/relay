package com.dileep.relay.api;


import com.dileep.relay.api.dto.DeliveryAttemptResponse;
import com.dileep.relay.api.dto.DeliveryResponse;
import com.dileep.relay.api.dto.PageResponse;
import com.dileep.relay.api.dto.ReplayResponse;
import com.dileep.relay.domain.DeliveryState;
import com.dileep.relay.service.DeliveryService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications/{applicationId}/deliveries")
public class DeliveryController {
    private static final Set<String> DELIVERY_SORT = Set.of("id", "createdAt", "nextAttemptAt", "state", "attemptCount");
    private static final Set<String> ATTEMPT_SORT = Set.of("id", "attemptNumber", "replayCount", "attemptedAt");

    private final DeliveryService deliveryService;

    public  DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping
    public PageResponse<DeliveryResponse> list(
            @PathVariable UUID applicationId,
            @RequestParam(required = false) DeliveryState state,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
            ){
        return deliveryService.listByApplication(applicationId, state, Pages.check(pageable, DELIVERY_SORT));
    }

    @GetMapping("/{deliveryId}/attempts")
    public PageResponse<DeliveryAttemptResponse> listAttempts(
            @PathVariable UUID applicationId,
            @PathVariable Long deliveryId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable
    ) {
        return deliveryService.listAttempts(applicationId, deliveryId, Pages.check(pageable, ATTEMPT_SORT));
    }

    /**
     * Puts one dead delivery back on the queue. 409 if it is PENDING (a worker
     * may hold it right now) or SUCCEEDED (nothing to retry).
     */
    @PostMapping("/{deliveryId}/replay")
    public DeliveryResponse replay(
            @PathVariable UUID applicationId,
            @PathVariable Long deliveryId) {

        return deliveryService.replay(applicationId, deliveryId);
    }
}
