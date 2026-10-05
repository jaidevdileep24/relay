package com.dileep.relay.api.error;

public class NotFoundException extends RuntimeException {
	public NotFoundException(String resource, Object id) {
		super("%s not found: %s".formatted(resource, id));
	}
}
