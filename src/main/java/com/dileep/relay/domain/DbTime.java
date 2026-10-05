package com.dileep.relay.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * "Now", at the precision Postgres TIMESTAMPTZ actually stores.
 *
 * <p>Java's clock has nanoseconds; the column keeps microseconds. Stamping an
 * entity with raw {@code Instant.now()} meant the response built from memory
 * and the same row read back later disagreed in the last three digits - an
 * idempotent replay returned a different {@code createdAt} than the original.
 */
public final class DbTime {

	private DbTime() {
	}

	public static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}
}
