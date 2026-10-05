package com.dileep.relay.api;

import com.dileep.relay.api.dto.TriageInsightsResponse;
import com.dileep.relay.service.DeliveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications/{applicationId}/insights")
public class InsightsController {

	private final DeliveryService deliveryService;

	public InsightsController(DeliveryService deliveryService) {
		this.deliveryService = deliveryService;
	}

	/** Failure triage at a glance: categories seen, deliveries stopped early, retries saved. */
	@GetMapping("/triage")
	public TriageInsightsResponse triage(@PathVariable UUID applicationId) {
		return deliveryService.triageInsights(applicationId);
	}
}
