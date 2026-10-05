package com.dileep.relay.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint is what makes the cache worth having. If it were computed
 * over raw bodies the hit rate would be zero, because real error bodies carry a
 * request id or timestamp.
 */
class FailureFingerprintTest {

	private static FailureContext body(String text) {
		return new FailureContext(500, text, null, "invoice.paid");
	}

	@Test
	@DisplayName("identical failures fingerprint identically")
	void stable() {
		assertThat(FailureFingerprint.of(body("upstream down")))
				.isEqualTo(FailureFingerprint.of(body("upstream down")));
	}

	@Test
	@DisplayName("request ids and timestamps are normalised away - this is the whole point")
	void volatileIdentifiersAreCollapsed() {
		String one = "request 8fa21bc09d failed at 10:32:11";
		String two = "request 9bc03ff17a failed at 11:04:58";

		assertThat(FailureFingerprint.of(body(one))).isEqualTo(FailureFingerprint.of(body(two)));
	}

	@Test
	@DisplayName("genuinely different errors do not collide")
	void differentBodiesDiffer() {
		assertThat(FailureFingerprint.of(body("invalid signature")))
				.isNotEqualTo(FailureFingerprint.of(body("unknown event type")));
	}

	@Test
	@DisplayName("the status code is part of the key")
	void statusIsPartOfTheKey() {
		FailureContext a = new FailureContext(500, "boom", null, "invoice.paid");
		FailureContext b = new FailureContext(401, "boom", null, "invoice.paid");

		assertThat(FailureFingerprint.of(a)).isNotEqualTo(FailureFingerprint.of(b));
	}

	@Test
	@DisplayName("a null body does not blow up")
	void nullsAreSafe() {
		assertThat(FailureFingerprint.of(new FailureContext(null, null, null, "x"))).isNotBlank();
	}
}
