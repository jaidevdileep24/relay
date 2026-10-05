package com.dileep.relay.domain;

import com.dileep.relay.ai.FailureCategory;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * A classification already paid for, keyed by the fingerprint of the failure
 * that produced it.
 *
 * <p>Not an audit row - {@code delivery_attempt} keeps those. This is purely a
 * spend guard, which is why it carries {@code hitCount}: that number is the
 * answer to "is the cache earning its keep".
 */
@Entity
@Table(name = "failure_classification")
public class FailureClassificationCache {

	@Id
	private String fingerprint;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private FailureCategory category;

	@Column(name = "retry_after_secs")
	private Long retryAfterSecs;

	private String reasoning;

	/** Which provider and model produced this, so a bad model's output can be found and purged. */
	@Column(nullable = false)
	private String classifier;

	@Column(name = "hit_count", nullable = false)
	private long hitCount = 0;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "last_used_at", nullable = false)
	private Instant lastUsedAt;

	protected FailureClassificationCache() { /* JPA */ }

	public FailureClassificationCache(String fingerprint, FailureCategory category, Long retryAfterSecs,
	                                  String reasoning, String classifier) {
		this.fingerprint = fingerprint;
		this.category = category;
		this.retryAfterSecs = retryAfterSecs;
		this.reasoning = reasoning;
		this.classifier = classifier;
		this.createdAt = DbTime.now();
		this.lastUsedAt = this.createdAt;
	}

	public String getFingerprint() { return fingerprint; }
	public FailureCategory getCategory() { return category; }
	public Long getRetryAfterSecs() { return retryAfterSecs; }
	public String getReasoning() { return reasoning; }
	public String getClassifier() { return classifier; }
	public long getHitCount() { return hitCount; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getLastUsedAt() { return lastUsedAt; }
}
