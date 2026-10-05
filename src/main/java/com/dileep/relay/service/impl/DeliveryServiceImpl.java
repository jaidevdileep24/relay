package com.dileep.relay.service.impl;

import com.dileep.relay.api.dto.DeliveryAttemptResponse;
import com.dileep.relay.api.dto.DeliveryResponse;
import com.dileep.relay.api.dto.PageResponse;
import com.dileep.relay.api.dto.ReplayResponse;
import com.dileep.relay.api.dto.TriageInsightsResponse;
import com.dileep.relay.api.error.ConflictException;
import com.dileep.relay.api.error.NotFoundException;
import com.dileep.relay.config.RelayProperties;
import com.dileep.relay.domain.DbTime;
import com.dileep.relay.domain.Delivery;
import com.dileep.relay.domain.DeliveryAttempt;
import com.dileep.relay.domain.DeliveryState;
import com.dileep.relay.domain.Endpoint;
import com.dileep.relay.repository.ApplicationRepository;
import com.dileep.relay.repository.DeliveryAttemptRepository;
import com.dileep.relay.repository.DeliveryRepository;
import com.dileep.relay.repository.EndpointRepository;
import com.dileep.relay.repository.FailureClassificationRepository;
import com.dileep.relay.service.DeliveryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DeliveryServiceImpl implements DeliveryService {

    /** The only states a replay may touch. PENDING may be leased right now. */
    private static final Set<DeliveryState> REPLAYABLE =
            Set.of(DeliveryState.FAILED, DeliveryState.DISABLED);

    private final ApplicationRepository applicationRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final EndpointRepository endpointRepository;
    private final FailureClassificationRepository classificationRepository;
    private final RelayProperties properties;

    public DeliveryServiceImpl(ApplicationRepository applicationRepository,
                               DeliveryRepository deliveryRepository,
                               DeliveryAttemptRepository attemptRepository,
                               EndpointRepository endpointRepository,
                               FailureClassificationRepository classificationRepository,
                               RelayProperties properties) {
        this.applicationRepository = applicationRepository;
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.endpointRepository = endpointRepository;
        this.classificationRepository = classificationRepository;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public TriageInsightsResponse triageInsights(UUID applicationId) {
        if (!applicationRepository.existsById(applicationId)) {
            throw new NotFoundException("Application", applicationId);
        }
        Map<String, Long> byCategory = new LinkedHashMap<>();
        attemptRepository.countFailuresByCategory(applicationId)
                .forEach(c -> byCategory.put(c.getCategory(), c.getCount()));

        DeliveryRepository.TriageSavings savings = deliveryRepository.triageSavings(applicationId);
        FailureClassificationRepository.CacheStats cache = classificationRepository.cacheStats();

        return new TriageInsightsResponse(
                properties.getAi().getProvider().name().toLowerCase(),
                byCategory,
                savings.getStoppedEarly(),
                savings.getRetriesSaved(),
                cache.getEntries(),
                cache.getHits());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeliveryResponse> listByApplication(UUID applicationId,
                                                            DeliveryState state,
                                                            Pageable pageable) {
        // Same answer as the endpoints list: an unknown application is a 404,
        // not an empty page that looks like "no deliveries yet".
        if (!applicationRepository.existsById(applicationId)) {
            throw new NotFoundException("Application", applicationId);
        }
        Page<Delivery> page = state == null
                ? deliveryRepository.findByMessageApplicationId(applicationId, pageable)
                : deliveryRepository.findByMessageApplicationIdAndState(applicationId, state, pageable);

        return PageResponse.of(page, DeliveryServiceImpl::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeliveryAttemptResponse> listAttempts(UUID applicationId,
                                                              Long deliveryId,
                                                              Pageable pageable) {
        // Tenancy check. A delivery owned by another application must be
        // indistinguishable from one that does not exist - the ids are
        // sequential, so any difference in the response is an enumeration oracle.
        loadOwned(applicationId, deliveryId);

        return PageResponse.of(
                attemptRepository.findByDeliveryIdOrderByReplayCountDescAttemptNumberDesc(deliveryId, pageable),
                DeliveryServiceImpl::toResponse);
    }

    @Override
    @Transactional
    public DeliveryResponse replay(UUID applicationId, Long deliveryId) {
        Delivery delivery = loadOwned(applicationId, deliveryId);

        if (!REPLAYABLE.contains(delivery.getState())) {
            throw new ConflictException(
                    "delivery %d is %s and cannot be replayed".formatted(deliveryId, delivery.getState()));
        }

        // Mutated through the entity rather than a bulk UPDATE so @Version still
        // guards it: if a concurrent replay of the same row commits first, this
        // one fails rather than double-incrementing replay_count.
        delivery.setState(DeliveryState.PENDING);
        delivery.setAttemptCount(0);
        delivery.setReplayCount(delivery.getReplayCount() + 1);
        delivery.setNextAttemptAt(DbTime.now());
        delivery.setLastError(null);

        return toResponse(delivery);
    }

    @Override
    @Transactional
    public ReplayResponse replayFailedForEndpoint(UUID applicationId, UUID endpointId) {
        Endpoint endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new NotFoundException("Endpoint", endpointId));

        if (!endpoint.getApplication().getId().equals(applicationId)) {
            throw new NotFoundException("Endpoint", endpointId);
        }

        if (!endpoint.isEnabled()) {
            throw new ConflictException(
                    "endpoint %s is disabled; re-enable it before replaying".formatted(endpointId));
        }

        return new ReplayResponse(deliveryRepository.replayTerminalForEndpoint(endpointId));
    }

    /** Loads a delivery only if it belongs to this application. Wrong owner reads as absent. */
    private Delivery loadOwned(UUID applicationId, Long deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));

        if (!delivery.getMessage().getApplication().getId().equals(applicationId)) {
            throw new NotFoundException("Delivery", deliveryId);
        }
        return delivery;
    }

    /**
     * {@code getId()} on a lazy proxy reads the foreign key already held in the
     * delivery row - the one getter that never triggers a SELECT.
     */
    private static DeliveryResponse toResponse(Delivery delivery) {
        return new DeliveryResponse(
                delivery.getId(),
                delivery.getMessage().getId(),
                delivery.getEndpoint().getId(),
                delivery.getState(),
                delivery.getAttemptCount(),
                delivery.getNextAttemptAt(),
                delivery.getLastError(),
                delivery.getLastFailureCategory(),
                delivery.getLastFailureReasoning(),
                delivery.getRetriesSaved(),
                delivery.getCreatedAt()
        );
    }

    private static DeliveryAttemptResponse toResponse(DeliveryAttempt attempt) {
        return new DeliveryAttemptResponse(
                attempt.getId(),
                attempt.getAttemptNumber(),
                attempt.getReplayCount(),
                attempt.getHttpStatus(),
                attempt.getResponseBody(),
                attempt.getErrorMessage(),
                attempt.getDurationMs(),
                attempt.getAttemptedAt(),
                attempt.getFailureCategory(),
                attempt.getFailureReasoning()
        );
    }
}