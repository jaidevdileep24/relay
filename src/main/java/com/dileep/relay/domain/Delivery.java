package com.dileep.relay.domain;

import com.dileep.relay.ai.FailureCategory;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * One (message x endpoint) pair - the unit of work for the dispatcher.
 *
 * <p>This row is simultaneously:
 * <ul>
 *   <li>the <b>outbox record</b> - written in the same transaction as the
 *       Message, so a crash can never lose the intent to deliver;</li>
 *   <li>the <b>queue entry</b> - claimed by workers via
 *       {@code FOR UPDATE SKIP LOCKED};</li>
 *   <li>the <b>state machine</b> - see {@link DeliveryState}.</li>
 * </ul>
 */
@Entity
@Table(name = "delivery")
public class Delivery {

	/** Pooled sequence, not IDENTITY, so fan-out INSERTs batch. See V5. */
	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "delivery_seq")
	@SequenceGenerator(name = "delivery_seq", sequenceName = "delivery_id_seq", allocationSize = 50)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "message_id", nullable = false)
	private Message message;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "endpoint_id", nullable = false)
	private Endpoint endpoint;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private DeliveryState state = DeliveryState.PENDING;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount = 0;

	/** When this delivery becomes eligible for its next attempt. */
	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "last_error")
	private String lastError;

	/**
	 * How many times this delivery has been replayed. Attempt numbers restart
	 * at 1 on every replay, so this is what keeps the audit trail unambiguous:
	 * an attempt is identified by (replayCount, attemptNumber), not by
	 * attemptNumber alone.
	 */
	@Column(name = "replay_count", nullable = false)
	private int replayCount = 0;

	/** Triage verdict on the most recent failed attempt; cleared on success. */
	@Enumerated(EnumType.STRING)
	@Column(name = "last_failure_category")
	private FailureCategory lastFailureCategory;

	@Column(name = "last_failure_reasoning")
	private String lastFailureReasoning;

	/** Attempts skipped because triage called a failure permanent. See V6. */
	@Column(name = "retries_saved", nullable = false)
	private int retriesSaved = 0;

	@Version
	private Long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Delivery() { /* JPA */ }

	public Delivery(Message message, Endpoint endpoint) {
		this.message = message;
		this.endpoint = endpoint;
		this.state = DeliveryState.PENDING;
		this.nextAttemptAt = DbTime.now();   // eligible immediately
		this.createdAt = DbTime.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void touch() { this.updatedAt = DbTime.now(); }

	public Long getId() { return id; }
	public Message getMessage() { return message; }
	public Endpoint getEndpoint() { return endpoint; }
	public DeliveryState getState() { return state; }
	public void setState(DeliveryState state) { this.state = state; }
	public int getAttemptCount() { return attemptCount; }
	public void setAttemptCount(int c) { this.attemptCount = c; }
	public Instant getNextAttemptAt() { return nextAttemptAt; }
	public void setNextAttemptAt(Instant t) { this.nextAttemptAt = t; }
	public String getLastError() { return lastError; }
	public void setLastError(String e) { this.lastError = e; }
	public int getReplayCount() { return replayCount; }
	public void setReplayCount(int c) { this.replayCount = c; }
	public FailureCategory getLastFailureCategory() { return lastFailureCategory; }
	public String getLastFailureReasoning() { return lastFailureReasoning; }
	public int getRetriesSaved() { return retriesSaved; }

	/** Records the latest triage verdict; {@code null} clears it. */
	public void setDiagnosis(FailureCategory category, String reasoning) {
		this.lastFailureCategory = category;
		this.lastFailureReasoning = reasoning;
	}

	public void addRetriesSaved(int n) { this.retriesSaved += n; }
	public Long getVersion() { return version; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
