package com.dileep.relay.domain;

import com.dileep.relay.ai.FailureCategory;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Immutable audit record of one HTTP attempt against an endpoint.
 *
 * <p>This is the table that answers "we never got the webhook" in thirty
 * seconds instead of three hours. Never updated, only inserted.
 */
@Entity
@Table(name = "delivery_attempt")
public class DeliveryAttempt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "delivery_id", nullable = false)
	private Delivery delivery;

	@Column(name = "attempt_number", nullable = false)
	private int attemptNumber;

	/** Which replay cycle this attempt belongs to. See {@link Delivery#getReplayCount()}. */
	@Column(name = "replay_count", nullable = false)
	private int replayCount;

	/** Null when the request never got a response (timeout, DNS, TLS). */
	@Column(name = "http_status")
	private Integer httpStatus;

	@Column(name = "response_body")
	private String responseBody;

	@Column(name = "error_message")
	private String errorMessage;

	@Column(name = "duration_ms", nullable = false)
	private int durationMs;

	@Column(name = "attempted_at", nullable = false, updatable = false)
	private Instant attemptedAt;

	/** What the triage decided, or null when no classifier ran (success). */
	@Enumerated(EnumType.STRING)
	@Column(name = "failure_category")
	private FailureCategory failureCategory;

	/** The classifier's own words. The only record of why a misclassification happened. */
	@Column(name = "failure_reasoning")
	private String failureReasoning;

	protected DeliveryAttempt() { /* JPA */ }

	public DeliveryAttempt(Delivery delivery, int attemptNumber, int replayCount, Integer httpStatus,
	                       String responseBody, String errorMessage, int durationMs,
	                       FailureCategory failureCategory, String failureReasoning) {
		this.delivery = delivery;
		this.attemptNumber = attemptNumber;
		this.replayCount = replayCount;
		this.httpStatus = httpStatus;
		this.responseBody = responseBody;
		this.errorMessage = errorMessage;
		this.durationMs = durationMs;
		this.failureCategory = failureCategory;
		this.failureReasoning = failureReasoning;
		this.attemptedAt = DbTime.now();
	}

	public Long getId() { return id; }
	public Delivery getDelivery() { return delivery; }
	public int getAttemptNumber() { return attemptNumber; }
	public int getReplayCount() { return replayCount; }
	public Integer getHttpStatus() { return httpStatus; }
	public String getResponseBody() { return responseBody; }
	public String getErrorMessage() { return errorMessage; }
	public int getDurationMs() { return durationMs; }
	public Instant getAttemptedAt() { return attemptedAt; }
	public FailureCategory getFailureCategory() { return failureCategory; }
	public String getFailureReasoning() { return failureReasoning; }
}
