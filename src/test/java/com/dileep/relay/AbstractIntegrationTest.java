package com.dileep.relay;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for every integration test: one real Postgres 16, shared by all of them.
 *
 * <p>H2 is not an option here. It has neither {@code SKIP LOCKED} nor
 * {@code JSONB}, so it would let the dispatcher's claim query and the message
 * payload column pass tests and then fail in production.
 *
 * <p>The container is a static field started once and never stopped -
 * Testcontainers' Ryuk sidecar reaps it when the JVM exits. That keeps the
 * suite to a single container instead of one per test class.
 *
 * <p>The dispatcher is switched off. Otherwise its 1-second poll would claim
 * the rows a test just inserted and mutate them mid-assertion.
 */
@SpringBootTest(properties = "relay.dispatch.enabled=false")
public abstract class AbstractIntegrationTest {

	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void datasourceProps(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}
}
