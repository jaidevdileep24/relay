package com.dileep.relay.service;

import com.dileep.relay.api.dto.CreateEndpointRequest;
import com.dileep.relay.api.dto.EndpointResponse;

import java.util.List;
import java.util.UUID;

public interface EndpointService {

	/**
	 * Registers a destination URL. Generates and returns the signing secret -
	 * this is the only time the secret is ever returned.
	 *
	 * <p>Must reject URLs that point anywhere internal (SSRF): private ranges,
	 * link-local, loopback - and re-check after DNS resolution, since a public
	 * hostname can resolve to 127.0.0.1.
	 */
	EndpointResponse create(UUID applicationId, CreateEndpointRequest request);

	List<EndpointResponse> listByApplication(UUID applicationId);

	EndpointResponse get(UUID endpointId);

	/** Auto-disable, called when a failure is classified as permanent. */
	void disable(UUID endpointId, String reason);

	/**
	 * Clears a tripped breaker and puts the endpoint back in service.
	 *
	 * <p>Also resets {@code consecutiveFailures}. Leaving it at the threshold
	 * would re-trip the breaker on the very next failure, so the endpoint would
	 * survive exactly one delivery - and a replay of a large backlog would die
	 * immediately.
	 *
	 * <p>Deliberately manual. The breaker tripped because the endpoint was
	 * broken; something has to assert it is fixed, and a timer cannot.
	 */
	EndpointResponse enable(UUID applicationId, UUID endpointId);
}
