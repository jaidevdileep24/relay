package com.dileep.relay.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * An event submitted by an {@link Application}. Immutable once created.
 *
 * <p>One Message fans out into N {@link Delivery} rows - one per subscribed
 * endpoint. The Message itself is never mutated; delivery state lives on
 * Delivery.
 *
 * <p>{@code payload} is stored as Postgres JSONB. Hibernate 6 maps this
 * natively with {@link JdbcTypeCode} - no extra library needed.
 */
@Entity
@Table(name = "message")
public class Message implements Persistable<UUID> {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "application_id", nullable = false)
	private Application application;

	@Column(name = "event_type", nullable = false)
	private String eventType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String payload;

	/**
	 * Caller-supplied idempotency key. A unique partial index on
	 * (application_id, idempotency_key) is what actually enforces
	 * uniqueness - see V1__initial_schema.sql.
	 */
	@Column(name = "idempotency_key")
	private String idempotencyKey;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/**
	 * The id is assigned here, not by the database, so Spring Data cannot tell
	 * a new Message from a detached one and {@code save()} falls back to
	 * {@code merge()} - a SELECT before every INSERT on the ingest hot path.
	 * Telling it explicitly removes that query. Never persisted.
	 */
	@Transient
	private boolean isNew = true;

	@PostLoad
	@PostPersist
	void markNotNew() { this.isNew = false; }

	@Override
	public boolean isNew() { return isNew; }

	protected Message() { /* JPA */ }

	public Message(Application application, String eventType, String payload, String idempotencyKey) {
		this.id = UUID.randomUUID();
		this.application = application;
		this.eventType = eventType;
		this.payload = payload;
		this.idempotencyKey = idempotencyKey;
		this.createdAt = DbTime.now();
	}

	@Override
	public UUID getId() { return id; }
	public Application getApplication() { return application; }
	public String getEventType() { return eventType; }
	public String getPayload() { return payload; }
	public String getIdempotencyKey() { return idempotencyKey; }
	public Instant getCreatedAt() { return createdAt; }
}
