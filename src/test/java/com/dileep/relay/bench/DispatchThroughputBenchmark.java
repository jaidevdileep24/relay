package com.dileep.relay.bench;

import com.dileep.relay.dispatch.DispatchTask;
import com.dileep.relay.dispatch.HttpSender;
import com.dileep.relay.dispatch.SendResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Measures the dispatch engine alone: claim (TX 1) -> send -> record (TX 2),
 * against a real Postgres, with the receiver replaced by a stub that sleeps
 * for a fixed latency and returns 200.
 *
 * <p>Not part of {@code mvn test}: it is skipped unless {@code -Dbench} is set
 * (a {@code -Dtest='!Foo'} pattern overrides surefire's includes, so the class
 * name alone does not keep it out). Run it explicitly:
 * <pre>
 * mvn test -Dbench -Dtest=DispatchThroughputBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dbench.deliveries=5000 -Dbench.latencyMs=50
 * </pre>
 * Any {@code relay.*} key can be overridden the same way, e.g.
 * {@code -Drelay.dispatch.worker-threads=32}.
 *
 * <p>The rate limiter is off: it is a per-endpoint policy, and this measures
 * how fast the engine can go when policy is not the constraint.
 *
 * <p>Seeding is done with {@code next_attempt_at} in the future, then released
 * in one UPDATE, so the clock starts with the whole backlog due at once and
 * the poller cannot start early on a half-seeded table.
 */
@SpringBootTest(properties = {
		"relay.dispatch.enabled=true",
		"relay.rate-limit.enabled=false",
		"logging.level.com.dileep.relay=INFO"
})
@Import(DispatchThroughputBenchmark.StubConfig.class)
@EnabledIfSystemProperty(named = "bench", matches = ".*")
class DispatchThroughputBenchmark {

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

	static final int DELIVERIES = Integer.getInteger("bench.deliveries", 5000);
	static final int LATENCY_MS = Integer.getInteger("bench.latencyMs", 50);
	static final int ENDPOINTS = Integer.getInteger("bench.endpoints", 20);

	/** A receiver that always answers 200 after a fixed delay. No sockets. */
	static class SleepingSender implements HttpSender {
		@Override
		public SendResult send(DispatchTask task) {
			try {
				Thread.sleep(LATENCY_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new SendResult(200, "ok", null, LATENCY_MS);
		}
	}

	@TestConfiguration
	static class StubConfig {
		@Bean
		@Primary
		HttpSender sleepingSender() {
			return new SleepingSender();
		}
	}

	@Autowired JdbcTemplate jdbc;

	@Test
	void drainBacklog() throws Exception {
		seed();

		long start = System.nanoTime();
		jdbc.update("UPDATE delivery SET next_attempt_at = now() WHERE state = 'PENDING'");

		long deadline = start + 30L * 60 * 1_000_000_000L;
		int done = 0;
		while (done < DELIVERIES && System.nanoTime() < deadline) {
			Thread.sleep(100);
			done = jdbc.queryForObject("SELECT count(*) FROM delivery WHERE state = 'SUCCEEDED'", Integer.class);
		}
		double seconds = (System.nanoTime() - start) / 1e9;

		Map<String, Object> lag = jdbc.queryForMap("""
				SELECT percentile_cont(0.50) WITHIN GROUP (ORDER BY ms) AS p50,
				       percentile_cont(0.95) WITHIN GROUP (ORDER BY ms) AS p95,
				       percentile_cont(0.99) WITHIN GROUP (ORDER BY ms) AS p99
				  FROM (SELECT extract(epoch FROM a.attempted_at - first.t) * 1000 AS ms
				          FROM delivery_attempt a,
				               (SELECT min(attempted_at) AS t FROM delivery_attempt) first) x
				""");
		Integer attempts = jdbc.queryForObject("SELECT count(*) FROM delivery_attempt", Integer.class);

		String report = """

				==================== DISPATCH BENCHMARK ====================
				deliveries      %d   (%d endpoints, receiver latency %d ms)
				worker-threads  %s   batch-size %s   poll-interval-ms %s
				delivered       %d in %.2f s
				throughput      %.1f deliveries/s
				attempts        %d   (duplicates: %d)
				completion time since first send  p50 %.0f ms  p95 %.0f ms  p99 %.0f ms
				============================================================
				""".formatted(DELIVERIES, ENDPOINTS, LATENCY_MS,
				prop("relay.dispatch.worker-threads"), prop("relay.dispatch.batch-size"),
				prop("relay.dispatch.poll-interval-ms"),
				done, seconds, done / seconds, attempts, attempts - done,
				num(lag.get("p50")), num(lag.get("p95")), num(lag.get("p99")));
		System.out.println(report);

		if (done < DELIVERIES) {
			throw new AssertionError("only " + done + " of " + DELIVERIES + " delivered before the deadline");
		}
	}

	private void seed() {
		UUID app = UUID.randomUUID();
		jdbc.update("INSERT INTO application(id, name) VALUES (?, 'bench')", app);

		List<Object[]> endpoints = new java.util.ArrayList<>();
		for (int i = 0; i < ENDPOINTS; i++) {
			endpoints.add(new Object[]{UUID.randomUUID(), app, "https://receiver-" + i + ".example/hook", "whsec_bench"});
		}
		jdbc.batchUpdate("INSERT INTO endpoint(id, application_id, url, secret) VALUES (?, ?, ?, ?)", endpoints);

		// One message per delivery, spread round-robin across the endpoints.
		jdbc.update("""
				INSERT INTO message(id, application_id, event_type, payload)
				SELECT gen_random_uuid(), ?, 'bench.event', jsonb_build_object('n', g)
				  FROM generate_series(1, ?) g
				""", app, DELIVERIES);
		jdbc.update("""
				INSERT INTO delivery(message_id, endpoint_id, state, next_attempt_at)
				SELECT m.id, e.id, 'PENDING', now() + interval '1 day'
				  FROM (SELECT id, row_number() OVER () - 1 AS rn FROM message) m
				  JOIN (SELECT id, row_number() OVER () - 1 AS rn FROM endpoint) e
				    ON e.rn = m.rn % ?
				""", ENDPOINTS);
	}

	private static String prop(String key) {
		String v = System.getProperty(key);
		return v == null ? "(yml)" : v;
	}

	private static double num(Object o) {
		return o == null ? 0 : ((Number) o).doubleValue();
	}
}
