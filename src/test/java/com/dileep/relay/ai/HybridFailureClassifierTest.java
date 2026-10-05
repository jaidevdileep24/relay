package com.dileep.relay.ai;

import com.dileep.relay.ai.impl.HybridFailureClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Routing rules: who gets asked, and who gets to be wrong. */
class HybridFailureClassifierTest {

	/** Counts calls so a test can assert the expensive path was skipped. */
	private static final class CountingClassifier implements FailureClassifier {
		final AtomicInteger calls = new AtomicInteger();
		private final FailureClassification answer;

		CountingClassifier(FailureClassification answer) {
			this.answer = answer;
		}

		@Override
		public FailureClassification classify(FailureContext context) {
			calls.incrementAndGet();
			return answer;
		}
	}

	@Test
	@DisplayName("no body means no model call - a timeout has nothing to read")
	void skipsTheModelWhenThereIsNoBody() {
		CountingClassifier model = new CountingClassifier(
				FailureClassification.of(FailureCategory.AUTH, "should never be asked"));
		FailureClassifier heuristic = new CountingClassifier(
				FailureClassification.of(FailureCategory.NETWORK, "timeout"));

		FailureClassification result = new HybridFailureClassifier(heuristic, model)
				.classify(new FailureContext(null, null, "timed out", "invoice.paid"));

		assertThat(result.category()).isEqualTo(FailureCategory.NETWORK);
		assertThat(model.calls.get()).isZero();
	}

	@Test
	@DisplayName("the model wins over the status code when there is a body to read")
	void modelOverridesTheHeuristic() {
		// The premise of the phase: 200 OK with an auth error in the body.
		FailureClassifier model = new CountingClassifier(
				FailureClassification.of(FailureCategory.AUTH, "body says invalid signature"));
		FailureClassifier heuristic = new CountingClassifier(
				FailureClassification.of(FailureCategory.SERVER_ERROR, "HTTP 500"));

		FailureClassification result = new HybridFailureClassifier(heuristic, model)
				.classify(new FailureContext(500, "{\"error\":\"invalid signature\"}", null, "invoice.paid"));

		assertThat(result.category()).isEqualTo(FailureCategory.AUTH);
		assertThat(result.retryable()).isFalse();
	}

	@Test
	@DisplayName("an UNKNOWN from the model falls back to the heuristic, never to nothing")
	void unknownFallsBack() {
		FailureClassifier model = new CountingClassifier(FailureClassification.unknown("model down"));
		FailureClassifier heuristic = new CountingClassifier(
				FailureClassification.of(FailureCategory.SERVER_ERROR, "HTTP 500"));

		FailureClassification result = new HybridFailureClassifier(heuristic, model)
				.classify(new FailureContext(500, "something went wrong", null, "invoice.paid"));

		assertThat(result.category()).isEqualTo(FailureCategory.SERVER_ERROR);
	}
}
