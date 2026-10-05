package com.dileep.relay.api.error;

/**
 * The request is well-formed but cannot be honoured as stated - e.g. an
 * Idempotency-Key reused with a different body. Maps to 422, which is what the
 * IETF Idempotency-Key draft specifies for that case.
 */
public class UnprocessableException extends RuntimeException {
	public UnprocessableException(String message) { super(message); }
}
