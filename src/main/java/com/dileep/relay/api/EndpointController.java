package com.dileep.relay.api;

import com.dileep.relay.api.dto.CreateEndpointRequest;
import com.dileep.relay.api.dto.EndpointResponse;
import com.dileep.relay.api.dto.ReplayResponse;
import com.dileep.relay.service.DeliveryService;
import com.dileep.relay.service.EndpointService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications/{applicationId}/endpoints")
public class EndpointController {

	private final EndpointService endpointService;
	private final DeliveryService deliveryService;

	public EndpointController(EndpointService endpointService, DeliveryService deliveryService) {
		this.endpointService = endpointService;
		this.deliveryService = deliveryService;
	}

	@PostMapping
	@ApiResponse(responseCode = "201", description = "Endpoint created; the signing secret is returned only in this response")
	public ResponseEntity<EndpointResponse> create(@PathVariable UUID applicationId,
	                                               @Valid @RequestBody CreateEndpointRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(endpointService.create(applicationId, request));
	}

	@GetMapping
	public List<EndpointResponse> list(@PathVariable UUID applicationId) {
		return endpointService.listByApplication(applicationId);
	}

	/**
	 * Clears a tripped breaker. Manual on purpose - the breaker fired because
	 * the endpoint was broken, and only its owner can say it is fixed.
	 */
	@PostMapping("/{endpointId}/enable")
	public EndpointResponse enable(@PathVariable UUID applicationId,
	                               @PathVariable UUID endpointId) {
		return endpointService.enable(applicationId, endpointId);
	}

	/**
	 * Replays every terminal delivery for this endpoint: the post-outage
	 * button. 409 while the endpoint is still disabled, since the breaker
	 * would re-trip and strand the backlog a second time.
	 */
	@PostMapping("/{endpointId}/deliveries/replay")
	public ReplayResponse replayFailed(@PathVariable UUID applicationId,
	                                   @PathVariable UUID endpointId) {
		return deliveryService.replayFailedForEndpoint(applicationId, endpointId);
	}
}
