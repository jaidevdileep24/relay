package com.dileep.relay.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A destination URL owned by an {@link Application}.
 *
 * <p>{@code eventTypes} empty means "subscribe to everything". A non-empty set
 * means only those event types fan out to this endpoint.
 *
 * <p>{@code version} enables optimistic locking: two workers auto-disabling the
 * same endpoint concurrently will produce an OptimisticLockException on the
 * loser rather than silently clobbering each other.
 */
@Entity
@Table(name = "endpoint")
public class Endpoint {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "application_id", nullable = false)
	private Application application;

	@Column(nullable = false)
	private String url;

	/** Shared secret for HMAC-SHA256 request signing. */
	@Column(nullable = false)
	private String secret;

	private String description;

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(name = "disabled_reason")
	private String disabledReason;

	@Column(name = "consecutive_failures", nullable = false)
	private int consecutiveFailures = 0;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(
		name = "endpoint_event_type",
		joinColumns = @JoinColumn(name = "endpoint_id")
	)
	@Column(name = "event_type", nullable = false)
	private Set<String> eventTypes = new HashSet<>();

	@Version
	private Long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Endpoint() { /* JPA */ }

	public Endpoint(Application application, String url, String secret, String description) {
		this.id = UUID.randomUUID();
		this.application = application;
		this.url = url;
		this.secret = secret;
		this.description = description;
		this.createdAt = DbTime.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void touch() { this.updatedAt = DbTime.now(); }

	/** True if this endpoint should receive the given event type. */
	public boolean subscribesTo(String eventType) {
		return eventTypes.isEmpty() || eventTypes.contains(eventType);
	}

	public UUID getId() { return id; }
	public Application getApplication() { return application; }
	public String getUrl() { return url; }
	public void setUrl(String url) { this.url = url; }
	public String getSecret() { return secret; }
	public void setSecret(String secret) { this.secret = secret; }
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }
	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public String getDisabledReason() { return disabledReason; }
	public void setDisabledReason(String r) { this.disabledReason = r; }
	public Set<String> getEventTypes() { return eventTypes; }
	public void setEventTypes(Set<String> t) { this.eventTypes = t; }
	public Long getVersion() { return version; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
	public int getConsecutiveFailures() { return consecutiveFailures; }
	public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
}
