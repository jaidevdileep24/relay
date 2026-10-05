package com.dileep.relay.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A tenant of the platform - the company sending events.
 * Owns endpoints and messages.
 */
@Entity
@Table(name = "application")
public class Application {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Application() { /* JPA */ }

	public Application(String name) {
		this.id = UUID.randomUUID();
		this.name = name;
		this.createdAt = DbTime.now();
	}

	public UUID getId() { return id; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
	public Instant getCreatedAt() { return createdAt; }
}
