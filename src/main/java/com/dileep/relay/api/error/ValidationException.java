package com.dileep.relay.api.error;

/** Thrown for domain-level validation the bean-validation annotations can't express (e.g. SSRF checks). */
public class ValidationException extends RuntimeException {
	public ValidationException(String message) { super(message); }
}
