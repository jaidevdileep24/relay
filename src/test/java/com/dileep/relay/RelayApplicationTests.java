package com.dileep.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Boots the whole app against a REAL Postgres in a container.
 *
 * <p>Shares the single container defined in {@link AbstractIntegrationTest}.
 */
class RelayApplicationTests extends AbstractIntegrationTest {

	@Test
	@DisplayName("the context loads, Flyway applies, and every entity matches the schema")
	void contextLoads() {
		// Verifies: Spring wiring is valid, Flyway migrations apply cleanly,
		// and every JPA entity matches the schema (ddl-auto: validate).
	}
}
