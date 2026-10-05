package com.dileep.relay.api.error;

/** Thrown when a request collides with existing state (e.g. idempotency reuse with a different body). */
public class ConflictException extends RuntimeException {
	public ConflictException(String message) { super(message); }
}
