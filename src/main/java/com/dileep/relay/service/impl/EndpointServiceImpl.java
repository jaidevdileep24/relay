package com.dileep.relay.service.impl;

import com.dileep.relay.api.dto.CreateEndpointRequest;
import com.dileep.relay.api.dto.EndpointResponse;
import com.dileep.relay.api.error.NotFoundException;
import com.dileep.relay.domain.Application;
import com.dileep.relay.domain.Endpoint;
import com.dileep.relay.repository.ApplicationRepository;
import com.dileep.relay.repository.EndpointRepository;
import com.dileep.relay.service.EndpointService;
import com.dileep.relay.service.SsrfGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class EndpointServiceImpl implements EndpointService {

	private final EndpointRepository endpointRepository;
	private final ApplicationRepository applicationRepository;
	private final SsrfGuard ssrfGuard;

	private static final SecureRandom RANDOM = new SecureRandom();

	public EndpointServiceImpl(EndpointRepository endpointRepository,
							   ApplicationRepository applicationRepository,
							   SsrfGuard ssrfGuard) {
		this.endpointRepository = endpointRepository;
		this.applicationRepository = applicationRepository;
		this.ssrfGuard = ssrfGuard;
	}

	@Override
	@Transactional
	public EndpointResponse create(UUID applicationId, CreateEndpointRequest request) {
		Application application = applicationRepository.findById(applicationId)
				.orElseThrow(() -> new NotFoundException("Application", applicationId));

		ssrfGuard.validate(request.url());

		String secret = generateSecret();

		Endpoint endpoint = new Endpoint(application, request.url(), secret, request.description());
		if (request.eventTypes() != null) {
			endpoint.setEventTypes(new HashSet<>(request.eventTypes()));
		}

		Endpoint saved = endpointRepository.save(endpoint);
		return toResponse(saved, secret);
	}

	@Override
	@Transactional(readOnly = true)
	public List<EndpointResponse> listByApplication(UUID applicationId) {
		if (!applicationRepository.existsById(applicationId)) {
			throw new NotFoundException("Application", applicationId);
		}
		return endpointRepository.findByApplicationId(applicationId).stream()
				.map(endpoint -> toResponse(endpoint, null))
				.toList();
	}
	@Override
	@Transactional(readOnly = true)
	public EndpointResponse get(UUID endpointId) {
		return endpointRepository.findById(endpointId)
		.map(endpoint -> toResponse(endpoint, null))
				.orElseThrow(() -> new NotFoundException("Endpoint", endpointId));
	}

	@Override
	@Transactional
	public void disable(UUID endpointId, String reason) {
		Endpoint endpoint = endpointRepository.findById(endpointId)
				.orElseThrow( () -> new NotFoundException("Endpoint", endpointId));
		endpoint.setEnabled(false);
		endpoint.setDisabledReason(reason);
	}

	@Override
	@Transactional
	public EndpointResponse enable(UUID applicationId, UUID endpointId) {
		Endpoint endpoint = endpointRepository.findById(endpointId)
				.orElseThrow(() -> new NotFoundException("Endpoint", endpointId));

		// Tenancy check, same reasoning as the delivery reads: wrong owner is a
		// 404, never a 403, so the response never confirms the id exists.
		if (!endpoint.getApplication().getId().equals(applicationId)) {
			throw new NotFoundException("Endpoint", endpointId);
		}

		endpoint.setEnabled(true);
		endpoint.setDisabledReason(null);
		endpoint.setConsecutiveFailures(0);
		return toResponse(endpoint, null);
	}

	private static EndpointResponse toResponse(Endpoint endpoint, String secret) {
		return new EndpointResponse(
				endpoint.getId(),
				endpoint.getUrl(),
				endpoint.getDescription(),
				endpoint.isEnabled(),
				endpoint.getDisabledReason(),
				endpoint.getEventTypes(),
				secret,
				endpoint.getCreatedAt()
		);
	}

	private static String generateSecret() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
