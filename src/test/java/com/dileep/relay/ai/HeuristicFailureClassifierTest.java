package com.dileep.relay.ai;

import com.dileep.relay.ai.impl.HeuristicFailureClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The no-model path. It must work on its own, because it is the fallback for everything else. */
class HeuristicFailureClassifierTest {

	private final HeuristicFailureClassifier classifier = new HeuristicFailureClassifier();

	private static FailureContext status(int code) {
		return new FailureContext(code, "body", null, "invoice.paid");
	}

	@Test
	@DisplayName("401 and 403 are permanent - retrying a rejected key cannot help")
	void authIsNotRetryable() {
		assertThat(classifier.classify(status(401)).category()).isEqualTo(FailureCategory.AUTH);
		assertThat(classifier.classify(status(403)).category()).isEqualTo(FailureCategory.AUTH);
		assertThat(classifier.classify(status(401)).retryable()).isFalse();
	}

	@Test
	@DisplayName("4xx other than 408/429 is our mistake and will not fix itself")
	void clientErrorsAreNotRetryable() {
		assertThat(classifier.classify(status(400)).category()).isEqualTo(FailureCategory.CLIENT_ERROR);
		assertThat(classifier.classify(status(422)).retryable()).isFalse();
	}

	@Test
	@DisplayName("429 is retryable, 408 and 5xx are server errors")
	void transientCodes() {
		assertThat(classifier.classify(status(429)).category()).isEqualTo(FailureCategory.RATE_LIMITED);
		assertThat(classifier.classify(status(408)).category()).isEqualTo(FailureCategory.SERVER_ERROR);
		assertThat(classifier.classify(status(503)).category()).isEqualTo(FailureCategory.SERVER_ERROR);
		assertThat(classifier.classify(status(503)).retryable()).isTrue();
	}

	@Test
	@DisplayName("no status at all means the request never landed")
	void noStatusIsNetwork() {
		FailureContext context = new FailureContext(null, null, "HttpConnectTimeoutException", "invoice.paid");

		assertThat(classifier.classify(context).category()).isEqualTo(FailureCategory.NETWORK);
		assertThat(classifier.classify(context).retryable()).isTrue();
	}

	@Test
	@DisplayName("a status it cannot name degrades to UNKNOWN, which stays retryable")
	void unnameableStatusIsUnknown() {
		FailureClassification classification = classifier.classify(status(302));

		assertThat(classification.category()).isEqualTo(FailureCategory.UNKNOWN);
		assertThat(classification.retryable()).isTrue();
	}
}
